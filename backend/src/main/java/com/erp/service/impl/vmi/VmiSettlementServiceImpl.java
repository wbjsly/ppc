package com.erp.service.impl.vmi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.vmi.MaterialIssueDao;
import com.erp.dao.vmi.MaterialIssueLineDao;
import com.erp.dao.vmi.VmiSettlementDao;
import com.erp.dao.vmi.VmiSettlementLineDao;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.vmi.MaterialIssue;
import com.erp.entity.vmi.MaterialIssueLine;
import com.erp.entity.vmi.VmiSettlement;
import com.erp.entity.vmi.VmiSettlementLine;
import com.erp.service.vmi.VmiAgreementService;
import com.erp.service.vmi.VmiAlertService;
import com.erp.service.vmi.VmiSettlementService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** VMI 结算实现（spec vmi-consignment，design D7/D8）。 */
@Slf4j
@Service
public class VmiSettlementServiceImpl implements VmiSettlementService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final VmiSettlementDao settleDao;
    private final VmiSettlementLineDao lineDao;
    private final MaterialIssueDao issueDao;
    private final MaterialIssueLineDao issueLineDao;
    private final com.erp.dao.vmi.VmiAgreementDao agreeDao;
    private final MdmSupplierDao supplierDao;
    private final VmiAgreementService agreementService;
    private final VmiAlertService alertService;
    /** 结算推送事件（spec vmi-portal-sync：生成/双确认发起推送门户） */
    private final com.erp.service.portal.PortalEventService portalEventService;

    /** 结算价差容差（TOLERANCE_DEFAULT，BR-4.2-38 判据，与收货容差同源） */
    @Value("${app.proc.receipt-tolerance:0.005}")
    private BigDecimal tolerance;

    public VmiSettlementServiceImpl(VmiSettlementDao settleDao,
                                    VmiSettlementLineDao lineDao,
                                    MaterialIssueDao issueDao,
                                    MaterialIssueLineDao issueLineDao,
                                    com.erp.dao.vmi.VmiAgreementDao agreeDao,
                                    MdmSupplierDao supplierDao,
                                    VmiAgreementService agreementService,
                                    VmiAlertService alertService,
                                    com.erp.service.portal.PortalEventService portalEventService) {
        this.settleDao = settleDao;
        this.lineDao = lineDao;
        this.issueDao = issueDao;
        this.issueLineDao = issueLineDao;
        this.agreeDao = agreeDao;
        this.supplierDao = supplierDao;
        this.agreementService = agreementService;
        this.alertService = alertService;
        this.portalEventService = portalEventService;
    }

    // ---------- 生成 ----------

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String supplierId = str(payload.get("supplierId"));
        if (!hasText(supplierId)) {
            throw new ServiceException(422, "supplierId 必填");
        }
        MdmSupplier sup = supplierDao.selectById(supplierId.trim());
        if (sup == null) {
            throw new ServiceException(404, "供应商不存在：" + supplierId);
        }
        LocalDate start = parseDate(payload.get("periodStart"), "periodStart");
        LocalDate end = parseDate(payload.get("periodEnd"), "periodEnd");
        if (start.isAfter(end)) {
            throw new ServiceException(422, "结算期间起日不得晚于止日");
        }
        // 期间内该供应商 POSTED 的 VMI 领料单
        List<MaterialIssue> issues = issueDao.selectList(new LambdaQueryWrapper<MaterialIssue>()
                .eq(MaterialIssue::getIssueType, MaterialIssue.TYPE_VMI)
                .eq(MaterialIssue::getStatus, MaterialIssue.ST_POSTED)
                .eq(MaterialIssue::getSupplierId, supplierId.trim())
                .isNotNull(MaterialIssue::getPostDate)
                .ge(MaterialIssue::getPostDate, start.atStartOfDay())
                .lt(MaterialIssue::getPostDate, end.plusDays(1).atStartOfDay()));
        if (issues.isEmpty()) {
            throw new ServiceException(422, "该期间无寄售领用明细，无法生成结算单");
        }
        List<String> issueIds = issues.stream().map(MaterialIssue::getId).toList();
        Map<String, MaterialIssue> issueById = new HashMap<>();
        for (MaterialIssue i : issues) {
            issueById.put(i.getId(), i);
        }
        List<MaterialIssueLine> lines = issueLineDao.selectList(
                new LambdaQueryWrapper<MaterialIssueLine>()
                        .in(MaterialIssueLine::getIssueId, issueIds));
        if (lines.isEmpty()) {
            throw new ServiceException(422, "该期间无寄售领用明细行");
        }
        // 一领用行仅归属一张结算单（design D8，UK_VMI_SETTLE_L_IL 兜底）
        List<String> lineIds = lines.stream().map(MaterialIssueLine::getId).toList();
        Long used = lineDao.selectCount(new LambdaQueryWrapper<VmiSettlementLine>()
                .in(VmiSettlementLine::getIssueLineId, lineIds));
        if (used != null && used > 0) {
            throw new ServiceException(422, "所选期间的领用明细已生成结算单（一结算单一发票，design D8），"
                    + "请调整结算期间");
        }

        VmiSettlement s = new VmiSettlement();
        s.setSettleNo(nextSettleNo());
        s.setSupplierId(supplierId.trim());
        s.setSupplierName(sup.getSupplierName());
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setSettleCycle(cycleOf(supplierId.trim()));

        BigDecimal calc = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal trial = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        int no = 1;
        List<VmiSettlementLine> rows = new ArrayList<>();
        for (MaterialIssueLine l : lines) {
            BigDecimal amt = l.getAmount() != null ? l.getAmount()
                    : l.getQty().multiply(nvl(l.getUnitPrice())).setScale(2, RoundingMode.HALF_UP);
            calc = calc.add(amt);
            // 期末价试算：期间末生效协议价（缺失回退领用时点价）
            var tpl = agreementService.priceLineOn(supplierId.trim(), l.getItemCode(), end);
            BigDecimal trialPrice = tpl != null ? tpl.getUnitPrice() : nvl(l.getUnitPrice());
            trial = trial.add(l.getQty().multiply(trialPrice).setScale(2, RoundingMode.HALF_UP));

            MaterialIssue head = issueById.get(l.getIssueId());
            VmiSettlementLine sl = new VmiSettlementLine();
            sl.setLineNo(no++);
            sl.setIssueId(l.getIssueId());
            sl.setIssueLineId(l.getId());
            sl.setIssueNo(head == null ? null : head.getIssueNo());
            sl.setWorkOrderNo(head == null ? null : head.getWorkOrderNo());
            sl.setItemCode(l.getItemCode());
            sl.setItemName(l.getItemName());
            sl.setBatchNo(l.getBatchNo());
            sl.setQty(l.getQty());
            sl.setUnitPrice(nvl(l.getUnitPrice()));
            sl.setAmount(amt);
            rows.add(sl);
        }
        s.setCalcAmount(calc);
        s.setTrialAmount(trial);
        s.setDiffAmount(calc.subtract(trial));
        s.setDiffRate(diffRate(calc, trial));
        boolean over = s.getDiffRate().compareTo(tolerance) > 0;
        s.setStatus(over ? VmiSettlement.ST_ON_HOLD : VmiSettlement.ST_DRAFT);
        settleDao.insert(s);
        for (VmiSettlementLine sl : rows) {
            sl.setSettleId(s.getId());
            lineDao.insert(sl);
        }
        // 结算生成时同步补货建议扫描（design D4）
        alertService.scanReplenish();
        // 结算单推送门户（spec vmi-portal-sync：供应商待确认列表可见）
        try {
            portalEventService.settlePushed(s.getSettleNo(), 1, s.getSupplierId(), s.getCalcAmount(),
                    s.getPeriodStart() + "~" + s.getPeriodEnd());
        } catch (ServiceException e) {
            log.warn("结算单 {} 推送事件跳过: {}", s.getSettleNo(), e.getMessage());
        }
        log.info("VMI 结算单 {} created calc={} trial={} rate={} status={}", s.getSettleNo(),
                calc, trial, s.getDiffRate(), s.getStatus());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settlement", s);
        out.put("lineCount", rows.size());
        return out;
    }

    // ---------- 查询 ----------

    @Override
    public Page<Map<String, Object>> page(long current, long size, String supplierId, String status) {
        Page<VmiSettlement> p = settleDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<VmiSettlement>()
                        .eq(hasText(supplierId), VmiSettlement::getSupplierId, supplierId)
                        .eq(hasText(status), VmiSettlement::getStatus, status)
                        .orderByDesc(VmiSettlement::getPeriodEnd));
        Page<Map<String, Object>> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VmiSettlement s : p.getRecords()) {
            rows.add(headRow(s));
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        VmiSettlement s = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settlement", s);
        out.put("lines", lineDao.selectList(new LambdaQueryWrapper<VmiSettlementLine>()
                .eq(VmiSettlementLine::getSettleId, s.getId())
                .orderByAsc(VmiSettlementLine::getLineNo)));
        return out;
    }

    // ---------- 双确认（BR-4.2-38，design D7） ----------

    @Override
    @Transactional
    public Map<String, Object> sign(String id, Map<String, Object> body) {
        VmiSettlement s = require(id);
        if (VmiSettlement.ST_CONFIRMED.equals(s.getStatus())
                || VmiSettlement.ST_INVOICED.equals(s.getStatus())) {
            throw new ServiceException(422, "结算单已确认，当前 " + s.getStatus());
        }
        String side = str(body.get("side"));
        if ("pm".equalsIgnoreCase(side)) {
            if (s.getPmConfirmAt() != null) {
                throw new ServiceException(422, "采购员已确认");
            }
            s.setPmConfirmBy(SecurityUtils.getCurrentUserId());
            s.setPmConfirmAt(LocalDateTime.now());
        } else if ("supplier".equalsIgnoreCase(side)) {
            if (s.getSupplierConfirmAt() != null) {
                throw new ServiceException(422, "供应商确认已登记");
            }
            // MODIFIED vmi-consignment：优先门户账号确认（source=PORTAL，记录绑定账号），
            // 无账号时保留线下代录（OFFLINE + 线下确认人，design D12 兜底）
            String source = str(body.get("source"));
            if (!hasText(source)) {
                source = "OFFLINE";
            }
            String by = str(body.get("supplierConfirmBy"));
            if (!hasText(by)) {
                if ("PORTAL".equals(source)) {
                    by = SecurityUtils.getCurrentUserId();
                } else {
                    throw new ServiceException(422, "供应商确认人必填（线下确认登记，design D7）");
                }
            }
            s.setSupplierConfirmBy(by.trim());
            s.setSupplierConfirmAt(LocalDateTime.now());
            String way = str(body.get("way"));
            s.setSupplierConfirmWay(hasText(way) ? way : source);
        } else {
            throw new ServiceException(422, "side 须为 pm 或 supplier");
        }
        boolean pmDone = s.getPmConfirmAt() != null;
        boolean supDone = s.getSupplierConfirmAt() != null;
        boolean needBoth = s.getDiffRate() != null && s.getDiffRate().compareTo(tolerance) > 0;
        if (needBoth ? (pmDone && supDone) : pmDone) {
            s.setStatus(VmiSettlement.ST_CONFIRMED);
        } else if (needBoth && !VmiSettlement.ST_ON_HOLD.equals(s.getStatus())) {
            s.setStatus(VmiSettlement.ST_ON_HOLD);
            // 挂起双确认发起 → 再次推送门户（seq=2）
            try {
                portalEventService.settlePushed(s.getSettleNo(), 2, s.getSupplierId(),
                        s.getCalcAmount(), s.getPeriodStart() + "~" + s.getPeriodEnd());
            } catch (ServiceException e) {
                log.warn("结算单 {} 挂起推送事件跳过: {}", s.getSettleNo(), e.getMessage());
            }
        }
        s.setUpdateBy(SecurityUtils.getCurrentUserId());
        settleDao.updateById(s);
        log.info("VMI 结算单 {} sign side={} status={} pm={} supplier={}", s.getSettleNo(),
                side, s.getStatus(), pmDone, supDone);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settlement", s);
        out.put("confirmed", VmiSettlement.ST_CONFIRMED.equals(s.getStatus()));
        return out;
    }

    @Override
    @Transactional
    public void markInvoiced(String settleId, String supplierId) {
        VmiSettlement s = require(settleId);
        if (hasText(supplierId) && !supplierId.equals(s.getSupplierId())) {
            throw new ServiceException(422, "结算单供应商与发票供应商不一致："
                    + s.getSupplierName());
        }
        if (!VmiSettlement.ST_CONFIRMED.equals(s.getStatus())) {
            throw new ServiceException(422, "结算单未确认，不可开票登记（当前 " + s.getStatus()
                    + "，请先完成双确认）");
        }
        s.setStatus(VmiSettlement.ST_INVOICED);
        settleDao.updateById(s);
        log.info("VMI 结算单 {} invoiced", s.getSettleNo());
    }

    // ---------- 私有 ----------

    private Map<String, Object> headRow(VmiSettlement s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.getId());
        row.put("settleNo", s.getSettleNo());
        row.put("supplierId", s.getSupplierId());
        row.put("supplierName", s.getSupplierName());
        row.put("periodStart", s.getPeriodStart());
        row.put("periodEnd", s.getPeriodEnd());
        row.put("settleCycle", s.getSettleCycle());
        row.put("calcAmount", s.getCalcAmount());
        row.put("trialAmount", s.getTrialAmount());
        row.put("diffAmount", s.getDiffAmount());
        row.put("diffRate", s.getDiffRate());
        row.put("status", s.getStatus());
        row.put("pmConfirmBy", s.getPmConfirmBy());
        row.put("pmConfirmAt", s.getPmConfirmAt());
        row.put("supplierConfirmBy", s.getSupplierConfirmBy());
        row.put("supplierConfirmAt", s.getSupplierConfirmAt());
        row.put("supplierConfirmWay", s.getSupplierConfirmWay());
        row.put("createDate", s.getCreateDate());
        return row;
    }

    private String cycleOf(String supplierId) {
        List<com.erp.entity.vmi.VmiAgreement> list = agreeDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.vmi.VmiAgreement>()
                        .eq(com.erp.entity.vmi.VmiAgreement::getSupplierId, supplierId)
                        .eq(com.erp.entity.vmi.VmiAgreement::getStatus,
                                com.erp.entity.vmi.VmiAgreement.ST_EFFECTIVE)
                        .orderByDesc(com.erp.entity.vmi.VmiAgreement::getCreateDate)
                        .last("LIMIT 1"));
        return list.isEmpty() ? com.erp.entity.vmi.VmiAgreement.CYCLE_MONTH
                : list.get(0).getSettleCycle();
    }

    private static BigDecimal diffRate(BigDecimal calc, BigDecimal trial) {
        if (trial == null || trial.signum() == 0) {
            return calc != null && calc.signum() != 0
                    ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        return calc.subtract(trial).abs()
                .divide(trial, 6, RoundingMode.HALF_UP);
    }

    private VmiSettlement require(String id) {
        VmiSettlement s = settleDao.selectById(id);
        if (s == null) {
            throw new ServiceException(404, "结算单不存在：" + id);
        }
        return s;
    }

    private String nextSettleNo() {
        String prefix = "VS" + LocalDateTime.now().format(MONTH) + "-";
        Integer max = settleDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static LocalDate parseDate(Object o, String field) {
        if (o == null || !hasText(String.valueOf(o))) {
            throw new ServiceException(422, field + " 必填");
        }
        try {
            return LocalDate.parse(String.valueOf(o).trim());
        } catch (RuntimeException e) {
            throw new ServiceException(422, field + " 日期格式非法（yyyy-MM-dd）：" + o);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
