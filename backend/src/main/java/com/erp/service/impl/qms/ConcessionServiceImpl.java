package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.qms.ConcessionDao;
import com.erp.dao.qms.ConcessionWriteoffDao;
import com.erp.dao.qms.NcrDao;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.qms.Concession;
import com.erp.entity.qms.ConcessionWriteoff;
import com.erp.entity.qms.Ncr;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.qms.ConcessionService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 让步接收实现（spec concession-acceptance，tasks 7.1~7.6）。
 * 申请要素全必填（422 逐条）；提交 = 底座并行双签（质量经理 + 技术负责人，缺一不生效）；
 * 通过回调置 APPROVED + GR 行 CONCESSION；核销逐次校验三限制（有效期/累计量/范围）。
 */
@Slf4j
@Service
public class ConcessionServiceImpl implements ConcessionService {

    private final ConcessionDao concessionDao;
    private final ConcessionWriteoffDao writeoffDao;
    private final NcrDao ncrDao;
    private final GoodsReceiptLineDao lineDao;
    private final ApprovalEngine approvalEngine;

    public ConcessionServiceImpl(ConcessionDao concessionDao,
                                 ConcessionWriteoffDao writeoffDao,
                                 NcrDao ncrDao,
                                 GoodsReceiptLineDao lineDao,
                                 ApprovalEngine approvalEngine) {
        this.concessionDao = concessionDao;
        this.writeoffDao = writeoffDao;
        this.ncrDao = ncrDao;
        this.lineDao = lineDao;
        this.approvalEngine = approvalEngine;
    }

    // ================= 7.1 申请（要素校验 + 仅限本批） =================

    @Override
    @Transactional
    public Concession create(Map<String, Object> body) {
        requireRole("发起让步接收", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");

        // ---- 带出上下文（仅限本批：一切字段来自 NCR/GR 行快照，前端不可覆盖）----
        String ncrId = str(body.get("ncrId"));
        Ncr ncr = hasText(ncrId) ? ncrDao.selectById(ncrId) : null;
        if (ncr == null) {
            throw new ServiceException(422, "必须关联 NCR（仅限不合格批次申请让步）");
        }
        if (!"CONCESSION".equals(ncr.getStatus())) {
            throw new ServiceException(422, "NCR 处置须先评审为让步接收（当前 " + ncr.getStatus()
                    + " / " + ncr.getDisposition() + "）");
        }
        if ("1".equals(ncr.getRegulatoryFlag())) {
            throw new ServiceException(422, "安全/法规 CTQ 不合格禁止让步接收（BR-4.12-26）");
        }
        Concession existed = concessionDao.selectOne(new LambdaQueryWrapper<Concession>()
                .eq(Concession::getNcrId, ncr.getId())
                .in(Concession::getStatus, "DRAFT", "PENDING_APPROVE")
                .last("LIMIT 1"));
        if (existed != null) {
            throw new ServiceException(422, "该 NCR 已有在途让步申请：" + existed.getConcessionNo());
        }

        // ---- 要素校验（tasks 7.1：缺失 422 逐条列出）----
        List<String> missing = new ArrayList<>();
        String reason = str(body.get("reason"));
        String techAssess = str(body.get("techAssess"));
        String riskAssess = str(body.get("riskAssess"));
        String limitUntil = str(body.get("limitUntil"));
        String limitQtyStr = str(body.get("limitQty"));
        String limitScope = str(body.get("limitScope"));
        if (!hasText(reason)) {
            missing.add("让步原因（BR-4.2-26）");
        }
        if (!hasText(techAssess)) {
            missing.add("技术评估结论");
        }
        if (!hasText(riskAssess)) {
            missing.add("风险评估");
        }
        if (!hasText(limitUntil)) {
            missing.add("限制条件-有效期至（BR-4.12-28）");
        }
        if (!hasText(limitQtyStr)) {
            missing.add("限制条件-批准数量上限（BR-4.12-28）");
        }
        if (!hasText(limitScope)) {
            missing.add("限制条件-使用范围（BR-4.12-28）");
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "申请要素缺失：" + String.join("、", missing));
        }
        BigDecimal limitQty;
        try {
            limitQty = new BigDecimal(limitQtyStr.trim());
        } catch (Exception e) {
            throw new ServiceException(422, "批准数量上限必须为数字");
        }
        if (limitQty.signum() <= 0) {
            throw new ServiceException(422, "批准数量上限必须大于 0");
        }
        BigDecimal batchQty = ncr.getQty() == null ? BigDecimal.ZERO : ncr.getQty();
        if (limitQty.compareTo(batchQty) > 0) {
            throw new ServiceException(422, "批准数量上限 " + limitQty.stripTrailingZeros().toPlainString()
                    + " 超过本批不合格量 " + batchQty.stripTrailingZeros().toPlainString() + "（仅限本批）");
        }
        LocalDate until;
        try {
            until = LocalDate.parse(limitUntil.trim());
        } catch (Exception e) {
            throw new ServiceException(422, "有效期格式须为 yyyy-MM-dd");
        }
        if (!until.isAfter(LocalDate.now())) {
            throw new ServiceException(422, "限制有效期须晚于今天");
        }

        Concession c = new Concession();
        c.setConcessionNo(nextNo());
        c.setNcrId(ncr.getId());
        c.setNcrNo(ncr.getNcrNo());
        c.setLotId(ncr.getLotId());
        c.setGrId(ncr.getGrId());
        c.setGrLineId(ncr.getGrLineId());
        c.setItemCode(ncr.getItemCode());
        c.setItemName(ncr.getItemName());
        c.setBatchNo(ncr.getBatchNo());
        c.setQty(batchQty);
        c.setReason(reason.trim());
        c.setTechAssess(techAssess.trim());
        c.setRiskAssess(riskAssess.trim());
        c.setLimitUntil(until);
        c.setLimitQty(limitQty);
        c.setLimitScope(limitScope.trim());
        c.setStatus("DRAFT");
        c.setCreateBy(SecurityUtils.getCurrentUserId());
        concessionDao.insert(c);
        log.info("concession {} created for NCR {}", c.getConcessionNo(), ncr.getNcrNo());
        return c;
    }

    // ================= 7.2 提交双签 =================

    @Override
    @Transactional
    public Concession submit(String id) {
        requireRole("提交让步接收审批", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Concession c = require(id);
        if ("PENDING_APPROVE".equals(c.getStatus())) {
            throw new ServiceException(422, "审批中，不可重复提交");
        }
        if (!"DRAFT".equals(c.getStatus()) && !"REJECTED".equals(c.getStatus())) {
            throw new ServiceException(422, "当前状态不可提交：" + c.getStatus());
        }
        // 并行双签：同 SEQ 两节点（质量经理 + 技术负责人），全过才 APPROVED（C-4.12-01）
        List<List<ApprovalNodeSpec>> chain = List.of(List.of(
                ApprovalNodeSpec.sign("ROLE_QUALITY_MGR", "质量经理（第 1 签）"),
                ApprovalNodeSpec.sign("ROLE_TECH_OWNER", "技术负责人（第 2 签）")));
        var inst = approvalEngine.submit("Concession", c.getId(),
                "让步接收双签：" + c.getConcessionNo() + " / " + c.getItemCode(),
                "ROLE_QUALITY_DIRECTOR", chain);
        c.setStatus("PENDING_APPROVE");
        c.setApprovalId(inst.getId());
        c.setSubmittedBy(SecurityUtils.getCurrentUserId());
        c.setSubmittedDate(LocalDateTime.now());
        if (concessionDao.updateById(c) == 0) {
            throw new ServiceException(409, "让步单状态更新冲突，请刷新重试");
        }
        return c;
    }

    // ================= 作废（记录永久：仅 DRAFT → CANCELLED 留痕） =================

    @Override
    @Transactional
    public Concession cancel(String id, String reason) {
        requireRole("作废让步接收", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Concession c = require(id);
        if (!"DRAFT".equals(c.getStatus())) {
            throw new ServiceException(422, "仅草稿可作废（记录永久，已提交单据走驳回链）：" + c.getStatus());
        }
        if (!hasText(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "作废原因必填（至少 2 字）");
        }
        c.setStatus("CANCELLED");
        c.setRejectReason(reason.trim());
        concessionDao.updateById(c);
        return c;
    }

    // ================= 7.4 核销放行 =================

    @Override
    // 不加 @Transactional：越界时须先落 REJECTED 留痕再抛 422，
    // 事务回滚会吃掉该凭据（推送质量经理依赖它）。单条 insert 自提交即原子。
    public Map<String, Object> writeOff(Map<String, Object> body) {
        requireRole("让步核销放行", "ROLE_INSPECTOR", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR",
                "ROLE_WAREHOUSE", "ROLE_ADMIN");
        String concessionId = str(body.get("concessionId"));
        Concession c = require(concessionId);
        if (!"APPROVED".equals(c.getStatus())) {
            throw new ServiceException(422, "让步单未批准，不可核销（当前 " + c.getStatus() + "）");
        }
        BigDecimal qty;
        try {
            qty = new BigDecimal(String.valueOf(body.get("qty")));
        } catch (Exception e) {
            throw new ServiceException(422, "核销数量必须为数字");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "核销数量必须大于 0");
        }
        String scope = str(body.get("scope"));
        String operator = SecurityUtils.getCurrentUserId();
        BigDecimal used = usedOf(c.getId());
        BigDecimal cap = c.getLimitQty() == null ? c.getQty() : c.getLimitQty();
        BigDecimal remain = cap.subtract(used);

        // ---- 三限制逐次校验（BR-4.12-28）----
        String reject = null;
        if (c.getLimitUntil() != null && c.getLimitUntil().isBefore(LocalDate.now())) {
            reject = "让步已过期（有效期至 " + c.getLimitUntil() + "）";
        } else if (remain.signum() <= 0) {
            reject = "让步额度已用尽（上限 " + cap.stripTrailingZeros().toPlainString() + "）";
        } else if (qty.compareTo(remain) > 0) {
            reject = "超出剩余额度（剩余 " + remain.stripTrailingZeros().toPlainString()
                    + "，本次 " + qty.stripTrailingZeros().toPlainString() + "）";
        } else if (hasText(c.getLimitScope()) && (!hasText(scope) || !scope.contains(c.getLimitScope()))) {
            reject = "使用范围不符（限：" + c.getLimitScope() + "，本次：" + (scope == null ? "空" : scope) + "）";
        }

        ConcessionWriteoff w = new ConcessionWriteoff();
        w.setConcessionId(c.getId());
        w.setConcessionNo(c.getConcessionNo());
        w.setNcrId(c.getNcrId());
        w.setItemCode(c.getItemCode());
        w.setBatchNo(c.getBatchNo());
        w.setQty(qty);
        w.setScope(scope);
        w.setRefDocNo(str(body.get("refDocNo")));
        w.setOperator(operator);
        w.setOperatorName(operator);
        w.setWriteDate(LocalDateTime.now());
        w.setCreateBy(operator);

        if (reject != null) {
            // 越界尝试：REJECTED 留痕（推送质量经理的凭据）+ 422 阻断
            w.setStatus("REJECTED");
            w.setRejectReason(reject);
            w.setRemainQty(remain);
            writeoffDao.insert(w);
            log.warn("concession write-off rejected: {} reason={}", c.getConcessionNo(), reject);
            throw new ServiceException(422, "核销被拒：" + reject);
        }

        BigDecimal after = remain.subtract(qty);
        w.setStatus("OK");
        w.setRemainQty(after);
        writeoffDao.insert(w);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("writeoff", w);
        out.put("concessionNo", c.getConcessionNo());
        out.put("usedQty", used.add(qty));
        out.put("remainQty", after);
        out.put("exhausted", after.signum() == 0);
        log.info("concession {} write-off {} → remain {}", c.getConcessionNo(), qty, after);
        return out;
    }

    @Override
    public List<ConcessionWriteoff> writeoffs(String concessionId) {
        return writeoffDao.selectList(new LambdaQueryWrapper<ConcessionWriteoff>()
                .eq(ConcessionWriteoff::getConcessionId, concessionId)
                .orderByDesc(ConcessionWriteoff::getCreateDate));
    }

    // ================= 查询 =================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String keyword, String status) {
        LambdaQueryWrapper<Concession> qw = new LambdaQueryWrapper<Concession>()
                .eq(hasText(status), Concession::getStatus, status)
                .and(hasText(keyword), w -> w.like(Concession::getConcessionNo, keyword)
                        .or().like(Concession::getItemCode, keyword)
                        .or().like(Concession::getNcrNo, keyword))
                .orderByDesc(Concession::getCreateDate);
        Page<Concession> raw = concessionDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Concession c : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("concessionNo", c.getConcessionNo());
            m.put("ncrNo", c.getNcrNo());
            m.put("ncrId", c.getNcrId());
            m.put("itemCode", c.getItemCode());
            m.put("itemName", c.getItemName());
            m.put("batchNo", c.getBatchNo());
            m.put("qty", c.getQty());
            m.put("limitQty", c.getLimitQty());
            m.put("limitUntil", c.getLimitUntil());
            m.put("limitScope", c.getLimitScope());
            m.put("status", c.getStatus());
            m.put("usedQty", usedOf(c.getId()));
            m.put("submittedDate", c.getSubmittedDate());
            m.put("createDate", c.getCreateDate());
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        Concession c = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("concession", c);
        out.put("usedQty", usedOf(c.getId()));
        BigDecimal cap = c.getLimitQty() == null ? c.getQty() : c.getLimitQty();
        out.put("remainQty", cap.subtract(usedOf(c.getId())));
        if (hasText(c.getApprovalId())) {
            out.put("approval", approvalEngine.getInstance(c.getApprovalId()));
        }
        out.put("writeoffs", writeoffs(id));
        return out;
    }

    // ================= 内部 =================

    private BigDecimal usedOf(String concessionId) {
        List<ConcessionWriteoff> list = writeoffDao.selectList(new LambdaQueryWrapper<ConcessionWriteoff>()
                .eq(ConcessionWriteoff::getConcessionId, concessionId)
                .eq(ConcessionWriteoff::getStatus, "OK"));
        BigDecimal used = BigDecimal.ZERO;
        for (ConcessionWriteoff w : list) {
            if (w.getQty() != null) {
                used = used.add(w.getQty());
            }
        }
        return used;
    }

    private Concession require(String id) {
        Concession c = concessionDao.selectById(id);
        if (c == null) {
            throw new ServiceException(404, "让步接收单不存在：" + id);
        }
        return c;
    }

    private String nextNo() {
        String prefix = "CC" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = concessionDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : userRoles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
