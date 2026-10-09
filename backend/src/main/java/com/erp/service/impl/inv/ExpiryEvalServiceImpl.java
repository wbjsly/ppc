package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.ExpiryEvalDao;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.entity.inv.ExpiryEval;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.inv.InvScrapOrderLine;
import com.erp.entity.inv.InvStock;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.inv.ExpiryEvalService;
import com.erp.service.inv.FreezeService;
import com.erp.service.inv.ScrapOrderService;
import com.erp.entity.inv.InvFreeze;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 效期质量评估实现（4.10.3，spec expiry-management 需求④）。
 * 发起：锁定批次校验 + 重复未关闭 422 + 质量角色分流。
 * 判定三分支（design D4）：SCRAP 生成报废单+审批；RELEASE 校验有效期+审批；
 * FREEZE 调冻结链即刻 CLOSED。审批回调同事务（底座契约）。
 * 同人防闭环由底座 rejectSelfSign 拦截（BIZ=ExpiryEval）。
 */
@Slf4j
@Service
public class ExpiryEvalServiceImpl implements ExpiryEvalService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final ExpiryEvalDao evalDao;
    private final InvBatchDao batchDao;
    private final InvStockDao stockDao;
    private final ApprovalEngine approvalEngine;
    private final ScrapOrderService scrapOrderService;
    private final FreezeService freezeService;

    public ExpiryEvalServiceImpl(ExpiryEvalDao evalDao, InvBatchDao batchDao,
                                 InvStockDao stockDao, ApprovalEngine approvalEngine,
                                 ScrapOrderService scrapOrderService,
                                 FreezeService freezeService) {
        this.evalDao = evalDao;
        this.batchDao = batchDao;
        this.stockDao = stockDao;
        this.approvalEngine = approvalEngine;
        this.scrapOrderService = scrapOrderService;
        this.freezeService = freezeService;
    }

    // ---------- 发起 ----------

    @Override
    @Transactional
    public ExpiryEval submit(String itemCode, String batchNo, String evalNote, String remark) {
        requireQuality("发起效期评估");
        if (isBlank(itemCode) || isBlank(batchNo)) {
            throw new ServiceException(422, "物料与批次必填");
        }
        if (isBlank(evalNote)) {
            throw new ServiceException(422, "评估说明必填");
        }
        InvBatch b = requireBatch(itemCode.trim(), batchNo.trim());
        if (!"1".equals(str(b.getExpiryLockFlag()))) {
            throw new ServiceException(422, "仅锁定批次可发起评估（当前未锁定，"
                    + itemCode + "/" + batchNo + "）");
        }
        Long open = evalDao.selectCount(new LambdaQueryWrapper<ExpiryEval>()
                .eq(ExpiryEval::getItemCode, itemCode.trim())
                .eq(ExpiryEval::getBatchNo, batchNo.trim())
                .in(ExpiryEval::getStatus, ExpiryEval.ST_PENDING_EVAL, ExpiryEval.ST_PENDING_APPR));
        if (open != null && open > 0) {
            throw new ServiceException(422, "该批次存在未关闭的评估单，不可重复发起");
        }

        ExpiryEval e = new ExpiryEval();
        e.setEvalNo(nextEvalNo());
        e.setItemCode(itemCode.trim());
        e.setItemName(b.getItemName());
        e.setBatchNo(batchNo.trim());
        e.setWarehouseCode(pickWarehouse(itemCode.trim(), batchNo.trim()));
        e.setLockSource(isBlank(b.getLockSource()) ? InvBatch.SRC_AUTO : b.getLockSource());
        e.setStatus(ExpiryEval.ST_PENDING_EVAL);
        e.setEvalNote(evalNote.trim());
        e.setEvalBy(currentUser());
        e.setRemark(remark);
        evalDao.insert(e);
        log.info("expiry eval {} submitted: {}/{} by {}", e.getEvalNo(),
                e.getItemCode(), e.getBatchNo(), e.getEvalBy());
        return e;
    }

    // ---------- 判定三分支 ----------

    @Override
    @Transactional
    public Map<String, Object> submitConclusion(String evalId, String conclusion,
                                                String releaseUntil, String evalNote) {
        requireQuality("提交评估判定");
        ExpiryEval e = evalDao.selectById(evalId);
        if (e == null) {
            throw new ServiceException(422, "评估单不存在");
        }
        if (!ExpiryEval.ST_PENDING_EVAL.equals(e.getStatus())) {
            throw new ServiceException(422, "仅待判定状态可提交判定（当前 " + e.getStatus() + "）");
        }
        if (!isBlank(evalNote)) {
            e.setEvalNote(evalNote.trim());   // 判定说明可覆盖/补充发起说明
        }

        Map<String, Object> out = new LinkedHashMap<>();
        switch (conclusion == null ? "" : conclusion.trim()) {
            case ExpiryEval.C_SCRAP -> submitScrap(e, out);
            case ExpiryEval.C_RELEASE -> submitRelease(e, releaseUntil, out);
            case ExpiryEval.C_FREEZE -> submitFreeze(e, out);
            default -> throw new ServiceException(422,
                    "判定结论取值域：报废 SCRAP / 让步放行 RELEASE / 转质量冻结 FREEZE");
        }
        evalDao.updateById(e);
        out.put("evalNo", e.getEvalNo());
        out.put("status", e.getStatus());
        return out;
    }

    /** SCRAP：生成关联报废单（reason=OTHER 免会签语义）+ 挂单节点审批 */
    private void submitScrap(ExpiryEval e, Map<String, Object> out) {
        if (isBlank(e.getWarehouseCode())) {
            throw new ServiceException(422, "批次无在库库存行，无法生成报废单");
        }
        BigDecimal onHand = batchOnHand(e.getWarehouseCode(), e.getItemCode(), e.getBatchNo());
        if (onHand.signum() <= 0) {
            throw new ServiceException(422, "批次无在手库存，无法报废");
        }
        InvScrapOrder head = new InvScrapOrder();
        head.setWarehouseCode(e.getWarehouseCode());
        head.setReason(InvScrapOrder.R_OTHER);
        head.setRemark("效期评估 " + e.getEvalNo() + " 关联报废");
        InvScrapOrderLine line = new InvScrapOrderLine();
        line.setItemCode(e.getItemCode());
        line.setItemName(e.getItemName());
        line.setBatchNo(e.getBatchNo());
        line.setQty(onHand);
        Map<String, Object> scrap = scrapOrderService.createFromEval(head, List.of(line));
        // detail 返回 {order, lines} 嵌套结构
        Object orderObj = scrap.get("order");
        String scrapNo = orderObj instanceof com.erp.entity.inv.InvScrapOrder oo
                ? oo.getScrapNo() : String.valueOf(scrap.get("scrapNo"));

        e.setConclusion(ExpiryEval.C_SCRAP);
        e.setStatus(ExpiryEval.ST_PENDING_APPR);
        e.setScrapDocNo(scrapNo);
        submitApproval(e);
        out.put("scrapNo", scrapNo);
    }

    /** RELEASE：放行有效期 > 今日 + 挂单节点审批（通过回调写豁免） */
    private void submitRelease(ExpiryEval e, String releaseUntil, Map<String, Object> out) {
        if (isBlank(releaseUntil)) {
            throw new ServiceException(422, "让步放行必填放行有效期");
        }
        LocalDate until;
        try {
            until = LocalDate.parse(releaseUntil.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new ServiceException(422, "放行有效期格式须为 yyyy-MM-dd");
        }
        if (!until.isAfter(LocalDate.now())) {
            throw new ServiceException(422, "放行有效期必须晚于今日（当前 " + releaseUntil + "）");
        }
        e.setConclusion(ExpiryEval.C_RELEASE);
        e.setStatus(ExpiryEval.ST_PENDING_APPR);
        e.setReleaseUntil(until);
        submitApproval(e);
        out.put("releaseUntil", until.toString());
    }

    /** FREEZE：调 4.9 冻结链（内部通道跳发起角色，审批照挂）+ 即刻 CLOSED */
    private void submitFreeze(ExpiryEval e, Map<String, Object> out) {
        InvFreeze req = new InvFreeze();
        req.setFreezeType(InvFreeze.T_QUALITY);
        req.setWarehouseCode(e.getWarehouseCode() == null ? "" : e.getWarehouseCode());
        req.setItemCode(e.getItemCode());
        req.setItemName(e.getItemName());
        req.setBatchNo(e.getBatchNo());
        // 转冻结后质量域按其流程处置，冻结量取在手总量
        BigDecimal onHand = e.getWarehouseCode() == null ? BigDecimal.ZERO
                : batchOnHand(e.getWarehouseCode(), e.getItemCode(), e.getBatchNo());
        req.setQty(onHand.signum() > 0 ? onHand : BigDecimal.ONE);
        req.setReason("效期评估 " + e.getEvalNo() + " 转质量冻结：" + e.getEvalNote());
        req.setScope(InvFreeze.SCOPE_BATCH);
        InvFreeze f = freezeService.applyFromReview(req);

        e.setConclusion(ExpiryEval.C_FREEZE);
        e.setStatus(ExpiryEval.ST_CLOSED);
        e.setFreezeNo(f.getFreezeNo());
        e.setCloseAt(java.time.LocalDateTime.now());
        out.put("freezeNo", f.getFreezeNo());
    }

    private void submitApproval(ExpiryEval e) {
        var inst = approvalEngine.submit(ExpiryEval.BIZ_TYPE, e.getId(),
                "效期评估审批：" + e.getEvalNo() + " / " + e.getItemCode(),
                null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_QUALITY_MGR", "质量经理评估审批"))));
        e.setApprId(inst.getId());
        if (evalDao.updateById(e) == 0) {
            throw new ServiceException(409, "评估单状态更新冲突");
        }
    }

    // ---------- 查询 ----------

    @Override
    public Map<String, Object> page(String status, String itemCode, String keyword,
                                    long current, long size) {
        Page<ExpiryEval> p = evalDao.selectPage(new Page<>(Math.max(current, 1),
                Math.max(size, 1)), new LambdaQueryWrapper<ExpiryEval>()
                .eq(!isBlank(status), ExpiryEval::getStatus, trimOrNull(status))
                .eq(!isBlank(itemCode), ExpiryEval::getItemCode, trimOrNull(itemCode))
                .and(!isBlank(keyword), w -> w.like(ExpiryEval::getEvalNo, keyword)
                        .or().like(ExpiryEval::getBatchNo, keyword)
                        .or().like(ExpiryEval::getItemCode, keyword))
                .orderByDesc(ExpiryEval::getCreateDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExpiryEval e : p.getRecords()) {
            out.add(toMap(e));
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", out);
        page.put("total", p.getTotal());
        return page;
    }

    @Override
    public Map<String, Object> detail(String id) {
        ExpiryEval e = evalDao.selectById(id);
        if (e == null) {
            throw new ServiceException(422, "评估单不存在");
        }
        return toMap(e);
    }

    private Map<String, Object> toMap(ExpiryEval e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("evalNo", e.getEvalNo());
        m.put("itemCode", e.getItemCode());
        m.put("itemName", e.getItemName());
        m.put("batchNo", e.getBatchNo());
        m.put("warehouseCode", e.getWarehouseCode());
        m.put("lockSource", e.getLockSource());
        m.put("status", e.getStatus());
        m.put("conclusion", e.getConclusion());
        m.put("evalNote", e.getEvalNote());
        m.put("releaseUntil", e.getReleaseUntil());
        m.put("scrapDocNo", e.getScrapDocNo());
        m.put("freezeNo", e.getFreezeNo());
        m.put("apprId", e.getApprId());
        m.put("apprOpinion", e.getApprOpinion());
        m.put("evalBy", e.getEvalBy());
        m.put("closeAt", e.getCloseAt());
        m.put("createDate", e.getCreateDate());
        return m;
    }

    // ---------- helpers ----------

    private InvBatch requireBatch(String itemCode, String batchNo) {
        List<InvBatch> rows = batchDao.selectList(new LambdaQueryWrapper<InvBatch>()
                .eq(InvBatch::getItemCode, itemCode)
                .eq(InvBatch::getBatchNo, batchNo)
                .last("FOR UPDATE"));
        if (rows.isEmpty()) {
            throw new ServiceException(422, "批次台账不存在：" + itemCode + "/" + batchNo);
        }
        return rows.get(0);
    }

    private String pickWarehouse(String itemCode, String batchNo) {
        List<InvStock> rows = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getItemCode, itemCode)
                .eq(InvStock::getBatchNo, batchNo)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0).getWarehouseCode();
    }

    private BigDecimal batchOnHand(String wh, String item, String batch) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvStock r : stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, wh)
                .eq(InvStock::getItemCode, item)
                .eq(InvStock::getBatchNo, batch))) {
            total = total.add(r.getQty() == null ? BigDecimal.ZERO : r.getQty());
        }
        return total;
    }

    /** EV+yyMMdd+4位日流水（唯一键冲突重试） */
    private String nextEvalNo() {
        String prefix = "EV" + LocalDate.now().format(NO_DAY) + "-";
        for (int attempt = 0; attempt < 5; attempt++) {
            Long cnt = evalDao.selectCount(new LambdaQueryWrapper<ExpiryEval>()
                    .likeRight(ExpiryEval::getEvalNo, prefix));
            String no = prefix + String.format("%04d", (cnt == null ? 0 : cnt) + 1 + attempt);
            Long dup = evalDao.selectCount(new LambdaQueryWrapper<ExpiryEval>()
                    .eq(ExpiryEval::getEvalNo, no));
            if (dup == null || dup == 0) {
                return no;
            }
        }
        return prefix + System.nanoTime() % 100000;
    }

    private void requireQuality(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r)
                || "ROLE_QUALITY_ENG".equalsIgnoreCase(r)
                || "ROLE_QUALITY_MGR".equalsIgnoreCase(r))) {
            return;
        }
        throw new ServiceException(403, action + "：需要质量工程/质量经理/管理员角色");
    }

    private String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    private static String trimOrNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
