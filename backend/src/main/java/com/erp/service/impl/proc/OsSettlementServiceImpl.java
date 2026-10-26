package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.proc.OsApDao;
import com.erp.dao.proc.OsSettlementDao;
import com.erp.dao.proc.OsSettlementLineDao;
import com.erp.dao.mrp.MrpDispatchDao;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.proc.OsAp;
import com.erp.entity.proc.OsSettlement;
import com.erp.entity.proc.OsSettlementLine;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.proc.OsSettlementService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外协对账与应付挂账实现（change add-outsourcing-processing，spec outsourcing-settlement，task 6.1/6.2）。
 *
 * 外协 PO 识别：派工单 OUTSOURCE_FLAG=1 按 PO 号回联（5.6.3 链落的 poNo）——PO SOURCE 枚举无 OUTSOURCE 值。
 * 未对账判定：收货过账行（STATUS=POSTED）未被 erp_proc_os_settlement_line 引用
 * （UK_PROC_OSRL_GR_LINE 全局唯一 = 重复引用 schema 级防线，含草稿引用也占位）。
 * 金额 = WITHIN_TOLERANCE_QTY（过账量）× GR 行 PO 成交价快照；无价行 422（外协 PO 行必有价）。
 */
@Slf4j
@Service
public class OsSettlementServiceImpl implements OsSettlementService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OsSettlementDao osrDao;
    private final OsSettlementLineDao lineDao;
    private final OsApDao apDao;
    private final GoodsReceiptDao grDao;
    private final GoodsReceiptLineDao grLineDao;
    private final MrpDispatchDao dispatchDao;
    /** 供应商编码解析（GR 头存 ID，对账单 supplierCode 存编码——字段语义与列表筛选按编码） */
    private final com.erp.dao.mdm.MdmSupplierDao supplierDao;
    private final ApprovalEngine approvalEngine;

    public OsSettlementServiceImpl(OsSettlementDao osrDao,
                                   OsSettlementLineDao lineDao,
                                   OsApDao apDao,
                                   GoodsReceiptDao grDao,
                                   GoodsReceiptLineDao grLineDao,
                                   MrpDispatchDao dispatchDao,
                                   com.erp.dao.mdm.MdmSupplierDao supplierDao,
                                   // @Lazy 断环：ApprovalServiceImpl 收集回调 → 回调依赖本服务 → 本服务依赖审批引擎
                                   @org.springframework.context.annotation.Lazy
                                           ApprovalEngine approvalEngine) {
        this.osrDao = osrDao;
        this.lineDao = lineDao;
        this.apDao = apDao;
        this.grDao = grDao;
        this.grLineDao = grLineDao;
        this.dispatchDao = dispatchDao;
        this.supplierDao = supplierDao;
        this.approvalEngine = approvalEngine;
    }

    // ---------- 6.1 按 PO 汇总生成 ----------

    @Override
    @Transactional
    public OsSettlement generate(String poNo) {
        requireRole("生成外协对账单");
        if (!hasText(poNo)) {
            throw new ServiceException(422, "外协 PO 号必填");
        }
        String po = poNo.trim();
        // 外协 PO 识别：派工单回联（OUTSOURCE_FLAG=1）
        Long os = dispatchDao.selectCount(new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getPoNo, po)
                .eq(MrpDispatch::getOutsourceFlag, "1"));
        if (os == null || os == 0) {
            throw new ServiceException(422, "非外协 PO（无外协派工单回联）：" + po);
        }
        // 同 PO 已有在途单据（草稿/审批中）→ 拒绝重复建单（防 GR 行被两张草稿争抢）
        Long active = osrDao.selectCount(new LambdaQueryWrapper<OsSettlement>()
                .eq(OsSettlement::getPoNo, po)
                .in(OsSettlement::getStatus, OsSettlement.ST_DRAFT, OsSettlement.ST_CONFIRMED));
        if (active != null && active > 0) {
            throw new ServiceException(422, "该 PO 已有草稿/审批中的对账单，请先处理");
        }
        // 该 PO 全部已过账收货行
        List<GoodsReceipt> grs = grDao.selectList(new LambdaQueryWrapper<GoodsReceipt>()
                .eq(GoodsReceipt::getPoNo, po));
        List<String> grIds = grs.stream().map(GoodsReceipt::getId).toList();
        if (grIds.isEmpty()) {
            throw new ServiceException(422, "该 PO 无收货记录，无可对账明细：" + po);
        }
        Map<String, String> grNoById = new LinkedHashMap<>();
        for (GoodsReceipt g : grs) {
            grNoById.put(g.getId(), g.getGrNo());
        }
        List<GoodsReceiptLine> posted = grLineDao.selectList(new LambdaQueryWrapper<GoodsReceiptLine>()
                .in(GoodsReceiptLine::getGrId, grIds)
                .eq(GoodsReceiptLine::getStatus, "POSTED"));
        if (posted.isEmpty()) {
            // 无已过账收货行 → 无可对账明细（早退，避免下方 IN () 空集合 SQL 语法错）
            throw new ServiceException(422, "该 PO 无未对账收货明细（无已过账收货行）：" + po);
        }
        // 排除已被引用行（含草稿——唯一键占位）
        List<OsSettlementLine> used = lineDao.selectList(new LambdaQueryWrapper<OsSettlementLine>()
                .in(OsSettlementLine::getGrLineId,
                        posted.stream().map(GoodsReceiptLine::getId).toList()));
        java.util.Set<String> usedIds = used.stream().map(OsSettlementLine::getGrLineId)
                .collect(java.util.stream.Collectors.toSet());
        List<GoodsReceiptLine> pending = posted.stream()
                .filter(l -> !usedIds.contains(l.getId()))
                .toList();
        if (pending.isEmpty()) {
            throw new ServiceException(422, "该 PO 无未对账收货明细：" + po);
        }
        // 供应商取自最新收货单（同 PO 同供应商）
        GoodsReceipt last = grs.get(grs.size() - 1);
        // supplierCode 存供应商**编码**（GR 头存的是 ID；字段语义与列表筛选均按编码）
        String supId = last.getSupplierId();
        String supCode = supId;
        String supName = last.getSupplierName();
        if (supId != null && !supId.isEmpty()) {
            com.erp.entity.mdm.MdmSupplier sup = supplierDao.selectOne(
                    new LambdaQueryWrapper<com.erp.entity.mdm.MdmSupplier>()
                            .eq(com.erp.entity.mdm.MdmSupplier::getId, supId)
                            .last("LIMIT 1"));
            if (sup != null) {
                supCode = sup.getSupplierCode();
                supName = sup.getSupplierName();
            }
        }

        OsSettlement h = new OsSettlement();
        h.setOsrNo(nextOsrNo());
        h.setPoNo(po);
        h.setSupplierCode(supCode);
        h.setSupplierName(supName);
        h.setStatus(OsSettlement.ST_DRAFT);
        h.setTotalQty(BigDecimal.ZERO);
        h.setTotalAmt(BigDecimal.ZERO);
        h.setCreateBy(SecurityUtils.getCurrentUserId());
        osrDao.insert(h);

        int no = 1;
        BigDecimal tQty = BigDecimal.ZERO;
        BigDecimal tAmt = BigDecimal.ZERO;
        for (GoodsReceiptLine gl : pending) {
            BigDecimal qty = nvl(gl.getWithinToleranceQty());
            if (qty.signum() <= 0) {
                continue;
            }
            BigDecimal price = gl.getUnitPrice();
            if (price == null) {
                throw new ServiceException(422, "收货行无 PO 成交价，无法对账：行 " + gl.getId()
                        + " 物料 " + gl.getItemCode());
            }
            OsSettlementLine l = new OsSettlementLine();
            l.setOsrId(h.getId());
            l.setLineNo(no++);
            l.setGrLineId(gl.getId());
            l.setGrNo(grNoById.get(gl.getGrId()));
            l.setItemCode(gl.getItemCode());
            l.setItemName(gl.getItemName());
            l.setQty(qty);
            l.setUnitPrice(price);
            l.setAmount(qty.multiply(price).setScale(2, java.math.RoundingMode.HALF_UP));
            lineDao.insert(l);
            tQty = tQty.add(qty);
            tAmt = tAmt.add(l.getAmount());
        }
        if (no == 1) {
            throw new ServiceException(422, "该 PO 无有效对账数量（过账量均为 0）：" + po);
        }
        h.setTotalQty(tQty);
        h.setTotalAmt(tAmt);
        osrDao.updateById(h);
        log.info("外协对账单 {} generated po={} lines={} amt={}", h.getOsrNo(), po, no - 1, tAmt);
        return h;
    }

    // ---------- 查询 ----------

    @Override
    public List<OsSettlement> list(String poNo, String supplierCode, String status) {
        requireRead();
        return osrDao.selectList(new LambdaQueryWrapper<OsSettlement>()
                .eq(hasText(poNo), OsSettlement::getPoNo, trim(poNo))
                .eq(hasText(supplierCode), OsSettlement::getSupplierCode, trim(supplierCode))
                .eq(hasText(status), OsSettlement::getStatus, trim(status))
                .orderByDesc(OsSettlement::getCreateDate));
    }

    @Override
    public Map<String, Object> detail(String id) {
        requireRead();
        OsSettlement h = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("osr", h);
        out.put("lines", lineDao.selectList(new LambdaQueryWrapper<OsSettlementLine>()
                .eq(OsSettlementLine::getOsrId, id)
                .orderByAsc(OsSettlementLine::getLineNo)));
        return out;
    }

    @Override
    @Transactional
    public void delete(String id) {
        requireRole("删除外协对账单");
        OsSettlement h = require(id);
        if (!OsSettlement.ST_DRAFT.equals(h.getStatus())) {
            throw new ServiceException(422, "仅草稿对账单可删除，当前 " + h.getStatus());
        }
        // 物理删行释放 GR_LINE_ID 唯一键（逻辑删不释放唯一键，同收货行将无法重新汇总）
        lineDao.physicallyDeleteByOsrId(id);
        osrDao.deleteById(id);
        log.info("外协对账单 {} deleted（收货行引用物理释放）", h.getOsrNo());
    }

    // ---------- 6.2 审批与挂账 ----------

    @Override
    @Transactional
    public OsSettlement submit(String id) {
        requireRole("提交外协对账审批");
        OsSettlement h = require(id);
        if (!OsSettlement.ST_DRAFT.equals(h.getStatus())) {
            throw new ServiceException(422, "仅草稿可提交审批，当前 " + h.getStatus());
        }
        Long lines = lineDao.selectCount(new LambdaQueryWrapper<OsSettlementLine>()
                .eq(OsSettlementLine::getOsrId, id));
        if (lines == null || lines == 0) {
            throw new ServiceException(422, "对账单无明细行，不可提交");
        }
        var inst = approvalEngine.submit(OsSettlement.BIZ_TYPE, id,
                "外协对账：" + h.getOsrNo() + " PO " + h.getPoNo()
                        + " 金额 " + h.getTotalAmt().toPlainString(), null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PLAN_MGR", "计划主管"))));
        h.setStatus(OsSettlement.ST_CONFIRMED);
        h.setApprNo(inst.getApprNo());
        h.setUpdateBy(SecurityUtils.getCurrentUserId());
        osrDao.updateById(h);
        log.info("外协对账单 {} submitted appr={}", h.getOsrNo(), inst.getApprNo());
        return h;
    }

    @Override
    @Transactional
    public OsSettlement approve(String id, String apprNo) {
        OsSettlement h = osrDao.selectById(id);
        if (h == null) {
            log.warn("OsSettlement approved but record missing: {}", id);
            return null;
        }
        if (OsSettlement.ST_POSTED.equals(h.getStatus())) {
            return h; // 幂等
        }
        if (!OsSettlement.ST_CONFIRMED.equals(h.getStatus())) {
            throw new ServiceException(422, "对账单非审批中状态，无法挂账：" + h.getStatus());
        }
        // 生成 OPEN 挂账（OSR_NO 唯一键 = 幂等防线）
        OsAp ap = new OsAp();
        ap.setApNo(nextApNo());
        ap.setOsrNo(h.getOsrNo());
        ap.setSupplierCode(h.getSupplierCode());
        ap.setSupplierName(h.getSupplierName());
        ap.setAmt(h.getTotalAmt());
        ap.setStatus(OsAp.ST_OPEN);
        ap.setPostDate(LocalDateTime.now());
        ap.setCreateBy(SecurityUtils.getCurrentUserId());
        apDao.insert(ap);
        h.setStatus(OsSettlement.ST_POSTED);
        h.setPostBy(SecurityUtils.getCurrentUserId());
        h.setPostDate(LocalDateTime.now());
        h.setUpdateBy(SecurityUtils.getCurrentUserId());
        osrDao.updateById(h);
        log.info("外协对账单 {} POSTED，挂账 {} amt={}", h.getOsrNo(), ap.getApNo(), h.getTotalAmt());
        return h;
    }

    @Override
    @Transactional
    public OsSettlement reject(String id, String apprNo, String reason) {
        OsSettlement h = osrDao.selectById(id);
        if (h == null) {
            log.warn("OsSettlement rejected but record missing: {}", id);
            return null;
        }
        if (!OsSettlement.ST_CONFIRMED.equals(h.getStatus())) {
            return h; // 幂等/已回草稿
        }
        // spec「驳回回 DRAFT 可改」：行随草稿保留（引用从审批锁释放回可编辑态）；
        // 彻底释放走 delete 草稿。
        h.setStatus(OsSettlement.ST_DRAFT);
        h.setRejectReason(hasText(reason) ? reason : "审批驳回");
        h.setApprNo(apprNo);
        h.setUpdateBy(SecurityUtils.getCurrentUserId());
        osrDao.updateById(h);
        log.info("外协对账单 {} REJECTED → DRAFT：{}", h.getOsrNo(), h.getRejectReason());
        return h;
    }

    @Override
    public List<OsAp> listAp(String supplierCode, String status) {
        requireRead();
        return apDao.selectList(new LambdaQueryWrapper<OsAp>()
                .eq(hasText(supplierCode), OsAp::getSupplierCode, trim(supplierCode))
                .eq(hasText(status), OsAp::getStatus, trim(status))
                .orderByDesc(OsAp::getCreateDate));
    }

    // ---------- helpers ----------

    private OsSettlement require(String id) {
        OsSettlement h = osrDao.selectById(id);
        if (h == null) {
            throw new ServiceException(404, "外协对账单不存在：" + id);
        }
        return h;
    }

    private String nextOsrNo() {
        String prefix = "OSR" + LocalDateTime.now().format(DAY);
        Integer max = osrDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private String nextApNo() {
        String prefix = "OSA" + LocalDateTime.now().format(DAY);
        Integer max = apDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 写操作权限：PLANNER/PLAN_MGR/PURCHASER/ADMIN（spec 菜单权限 5.10.4） */
    private void requireRole(String action) {
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
        for (String want : new String[]{"ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_PURCHASER"}) {
            for (String r : userRoles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    /** 读操作：同写权限线（5.10.4 叶子可见角色） */
    private void requireRead() {
        requireRole("查看外协对账");
    }
}
