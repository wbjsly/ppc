package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.scm.CpfrAdjustLogDao;
import com.erp.dao.scm.CpfrAgreementDao;
import com.erp.dao.scm.CpfrForecastHeadDao;
import com.erp.dao.scm.CpfrForecastLineDao;
import com.erp.dao.scm.CpfrPlanDao;
import com.erp.dao.scm.CpfrPlanLineDao;
import com.erp.entity.scm.CpfrAdjustLog;
import com.erp.entity.scm.CpfrAgreement;
import com.erp.entity.scm.CpfrForecastHead;
import com.erp.entity.scm.CpfrForecastLine;
import com.erp.entity.scm.CpfrPlan;
import com.erp.entity.scm.CpfrPlanLine;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.scm.CpfrClockService;
import com.erp.service.scm.CpfrPlanService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPFR 联合补货计划（spec cpfr-collaboration R5/R6）：
 * 双方账号确认留痕；未达成例外阻断；ZONE 判定（design D3 周偏移口径）；
 * 冻结区/超弹性调整走 L2 审批（bizType=CpfrPlanAdjust，PMGR 节点，C-4.9-08）。
 */
@Service
public class CpfrPlanServiceImpl implements CpfrPlanService {

    /** 冻结期调整审批业务类型（回调 CpfrAdjustApprovalCallback 消费） */
    public static final String BIZ_CPFR_ADJUST = "CpfrPlanAdjust";

    private final CpfrPlanDao planDao;
    private final CpfrPlanLineDao lineDao;
    private final CpfrAgreementDao agreementDao;
    private final CpfrAdjustLogDao adjustLogDao;
    private final CpfrForecastHeadDao headDao;
    private final CpfrForecastLineDao forecastLineDao;
    private final CpfrExceptionServiceImpl exceptionService;
    private final ApprovalEngine approvalEngine;
    private final CpfrClockService clockService;

    public CpfrPlanServiceImpl(CpfrPlanDao planDao,
                               CpfrPlanLineDao lineDao,
                               CpfrAgreementDao agreementDao,
                               CpfrAdjustLogDao adjustLogDao,
                               CpfrForecastHeadDao headDao,
                               CpfrForecastLineDao forecastLineDao,
                               CpfrExceptionServiceImpl exceptionService,
                               ApprovalEngine approvalEngine,
                               CpfrClockService clockService) {
        this.planDao = planDao;
        this.lineDao = lineDao;
        this.agreementDao = agreementDao;
        this.adjustLogDao = adjustLogDao;
        this.headDao = headDao;
        this.forecastLineDao = forecastLineDao;
        this.exceptionService = exceptionService;
        this.approvalEngine = approvalEngine;
        this.clockService = clockService;
    }

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String agreementId = CpfrAgreementServiceImpl.str(payload.get("agreementId"));
        String periodTag = CpfrAgreementServiceImpl.str(payload.get("periodTag"));
        if (!StringUtils.hasText(agreementId) || !StringUtils.hasText(periodTag)) {
            throw new ServiceException(422, "agreementId/periodTag 必填");
        }
        CpfrAgreement agr = agreementDao.selectById(agreementId);
        if (agr == null) {
            throw new ServiceException(404, "CPFR 协议不存在");
        }
        if (CpfrAgreement.ST_REVIEW.equals(agr.getStatus())) {
            throw new ServiceException(422, "协议待复盘（准确率异常），暂停自动生成预测订单");
        }
        long dup = planDao.selectCount(new LambdaQueryWrapper<CpfrPlan>()
                .eq(CpfrPlan::getAgreementId, agreementId)
                .eq(CpfrPlan::getPeriodTag, periodTag));
        if (dup > 0) {
            throw new ServiceException(422, "该协议该周期已存在联合补货计划");
        }
        CpfrForecastHead head = headDao.selectOne(new LambdaQueryWrapper<CpfrForecastHead>()
                .eq(CpfrForecastHead::getPeriodTag, periodTag));
        if (head == null || !CpfrForecastHead.ST_PUSHED.equals(head.getStatus())) {
            throw new ServiceException(422, "该周期预测数据包未推送，无法生成联合补货计划");
        }
        List<CpfrForecastLine> fLines = forecastLineDao.selectList(new LambdaQueryWrapper<CpfrForecastLine>()
                .eq(CpfrForecastLine::getHeadId, head.getId())
                .eq(CpfrForecastLine::getAgreementId, agreementId));
        if (fLines.isEmpty()) {
            throw new ServiceException(422, "该协议在该周期无预测行");
        }
        CpfrPlan plan = new CpfrPlan();
        plan.setAgreementId(agreementId);
        plan.setPeriodTag(periodTag);
        plan.setStatus(CpfrPlan.ST_DRAFT);
        long open = exceptionService.openCount(agreementId, periodTag);
        plan.setOpenExceptionCount((int) open);
        planDao.insert(plan);
        for (CpfrForecastLine fl : fLines) {
            CpfrPlanLine pl = new CpfrPlanLine();
            pl.setPlanId(plan.getId());
            pl.setAgreementId(agreementId);
            pl.setPeriodTag(periodTag);
            pl.setItemCode(fl.getItemCode());
            pl.setItemName(fl.getItemName());
            pl.setWeekOffset(fl.getWeekOffset());
            pl.setQty(fl.getForecastQty() == null ? BigDecimal.ZERO : fl.getForecastQty());
            pl.setZone(zoneOf(fl.getWeekOffset(), agr));
            lineDao.insert(pl);
        }
        Map<String, Object> m = toMap(plan);
        m.put("lines", lines(plan.getId()));
        return m;
    }

    @Override
    public List<Map<String, Object>> list(String agreementId, String periodTag) {
        List<CpfrPlan> plans = planDao.selectList(new LambdaQueryWrapper<CpfrPlan>()
                .eq(StringUtils.hasText(agreementId), CpfrPlan::getAgreementId, agreementId)
                .eq(StringUtils.hasText(periodTag), CpfrPlan::getPeriodTag, periodTag)
                .orderByDesc(CpfrPlan::getCreateDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpfrPlan p : plans) {
            Map<String, Object> m = toMap(p);
            m.put("lines", lines(p.getId()));
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> confirm(String planId, String side, String operator) {
        CpfrPlan plan = require(planId);
        // 未达成例外阻断（spec R5）
        long open = exceptionService.openCount(plan.getAgreementId(), plan.getPeriodTag());
        if (open > 0) {
            throw new ServiceException(422, "存在未达成例外项（" + open + " 条），禁止确认联合补货计划");
        }
        boolean supplier = "SUPPLIER".equalsIgnoreCase(side);
        if (supplier && plan.getSupplierConfirmAt() == null) {
            plan.setSupplierConfirmBy(operator);
            plan.setSupplierConfirmAt(clockService.now());
        } else if (!supplier && plan.getInternalConfirmAt() == null) {
            plan.setInternalConfirmBy(operator);
            plan.setInternalConfirmAt(clockService.now());
        } else {
            throw new ServiceException(422, "该方已确认，无需重复");
        }
        // 单方/双方状态机
        boolean both = plan.getSupplierConfirmAt() != null && plan.getInternalConfirmAt() != null;
        plan.setStatus(both ? CpfrPlan.ST_BOTH : CpfrPlan.ST_SINGLE);
        if (both) {
            plan.setStatus(CpfrPlan.ST_ORDERED);
            // 生成时冻结标注已随行落库（ZONE），此处补 CONVERT 前置状态即可
        }
        planDao.updateById(plan);
        // 确认留痕（C-4.9-11）
        CpfrAdjustLog log = new CpfrAdjustLog();
        log.setTargetType("PLAN");
        log.setTargetId(plan.getId());
        log.setAgreementId(plan.getAgreementId());
        log.setPeriodTag(plan.getPeriodTag());
        log.setAction(CpfrAdjustLog.ACT_PLAN_CONFIRM);
        log.setOperator(operator);
        log.setOperatorRole(supplier ? "SUPPLIER" : "INTERNAL");
        log.setRemark(supplier ? "供应商确认联合补货计划" : "企业方确认联合补货计划");
        log.setOperatedAt(clockService.now());
        adjustLogDao.insert(log);
        Map<String, Object> m = toMap(plan);
        m.put("lines", lines(plan.getId()));
        return m;
    }

    @Override
    @Transactional
    public Map<String, Object> adjustLine(String lineId, Map<String, Object> payload) {
        CpfrPlanLine line = lineDao.selectById(lineId);
        if (line == null) {
            throw new ServiceException(404, "预测订单行不存在");
        }
        CpfrAgreement agr = agreementDao.selectById(line.getAgreementId());
        if (agr == null) {
            throw new ServiceException(404, "CPFR 协议不存在");
        }
        if (CpfrPlanLine.ADJ_PENDING.equals(line.getAdjustStatus())) {
            throw new ServiceException(422, "该行存在审批中的调整，不可重复提交");
        }
        BigDecimal newQty = CpfrAgreementServiceImpl.dec(payload.get("qty"));
        String remark = CpfrAgreementServiceImpl.str(payload.get("remark"));
        if (newQty == null || newQty.signum() < 0) {
            throw new ServiceException(422, "调整数量必填且不为负");
        }
        BigDecimal oldQty = line.getQty();
        // ZONE 实时判定（design D3：以行生成时的周偏移 + 距今天数推移）
        String zone = liveZone(line, agr);
        boolean needApproval = CpfrPlanLine.ZONE_FREEZE.equals(zone);
        if (!needApproval && CpfrPlanLine.ZONE_ELASTIC.equals(zone)
                && oldQty != null && oldQty.signum() > 0) {
            BigDecimal pct = newQty.subtract(oldQty).abs()
                    .divide(oldQty, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            BigDecimal limit = agr.getElasticPct() == null ? new BigDecimal("20") : agr.getElasticPct();
            needApproval = pct.compareTo(limit) > 0; // 超约定比例升级 L2
        }
        if (needApproval) {
            var inst = approvalEngine.submit(BIZ_CPFR_ADJUST, line.getId(),
                    "CPFR 冻结期/超弹性调整：" + line.getItemCode() + " W" + line.getWeekOffset()
                            + " " + oldQty.stripTrailingZeros().toPlainString()
                            + " → " + newQty.stripTrailingZeros().toPlainString()
                            + "（ZONE=" + zone + "）",
                    "ROLE_PMGR",
                    List.of(List.of(ApprovalNodeSpec.sign("ROLE_PMGR", "采购经理审批"))));
            line.setAdjustStatus(CpfrPlanLine.ADJ_PENDING);
            lineDao.updateById(line);
            writePlanLog(line, CpfrAdjustLog.ACT_ADJUST_SUBMIT, oldQty, newQty,
                    "提交 L2 审批（" + zone + "），审批实例 " + inst.getId() + "；" + remark);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineId", line.getId());
            m.put("zone", zone);
            m.put("approvalId", inst.getId());
            m.put("adjustStatus", CpfrPlanLine.ADJ_PENDING);
            m.put("message", "冻结区/超弹性调整须采购经理（PMGR）审批，原数量保持锁定");
            return m;
        }
        // 免审批：直接生效 + 留痕（弹性区内未超比例 / 自由区）
        line.setQty(newQty);
        lineDao.updateById(line);
        writePlanLog(line, CpfrAdjustLog.ACT_ADJUST_DIRECT, oldQty, newQty,
                "免审批直接生效（ZONE=" + zone + "）；" + remark);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", line.getId());
        m.put("zone", zone);
        m.put("qty", newQty);
        m.put("adjustStatus", null);
        return m;
    }

    @Override
    @Transactional
    public Map<String, Object> convert(String lineId, Map<String, Object> payload) {
        CpfrPlanLine line = lineDao.selectById(lineId);
        if (line == null) {
            throw new ServiceException(404, "预测订单行不存在");
        }
        CpfrPlan plan = planDao.selectById(line.getPlanId());
        if (plan == null || (!CpfrPlan.ST_ORDERED.equals(plan.getStatus())
                && !CpfrPlan.ST_BOTH.equals(plan.getStatus()))) {
            throw new ServiceException(422, "计划未双方确认，不可登记转化");
        }
        BigDecimal actual = CpfrAgreementServiceImpl.dec(payload.get("actualQty"));
        if (actual == null || actual.signum() < 0) {
            throw new ServiceException(422, "实际到货量必填且不为负");
        }
        line.setPoConversionQty(actual);
        line.setConvertedAt(clockService.now());
        line.setConvertRemark(CpfrAgreementServiceImpl.str(payload.get("remark")));
        lineDao.updateById(line);
        writePlanLog(line, CpfrAdjustLog.ACT_CONVERT, line.getQty(), actual, "转化登记实际到货量");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", line.getId());
        m.put("poConversionQty", actual);
        m.put("convertedAt", line.getConvertedAt());
        return m;
    }

    @Override
    public Map<String, Object> poJumpParams(String lineId) {
        CpfrPlanLine line = lineDao.selectById(lineId);
        if (line == null) {
            throw new ServiceException(404, "预测订单行不存在");
        }
        CpfrAgreement agr = agreementDao.selectById(line.getAgreementId());
        CpfrPlan plan = planDao.selectById(line.getPlanId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supplierId", agr == null ? null : agr.getSupplierId());
        m.put("supplierName", agr == null ? null : agr.getSupplierName());
        m.put("itemCode", line.getItemCode());
        m.put("itemName", line.getItemName());
        m.put("qty", line.getQty());
        m.put("periodTag", line.getPeriodTag());
        m.put("sourceRef", "CPFR:" + (plan == null ? "" : plan.getPeriodTag()) + ":W" + line.getWeekOffset());
        m.put("jumpPath", "/m/12.5.1");
        return m;
    }

    /** 行落库时的静态 ZONE 标注（生成订单行时调用） */
    public static String zoneOf(Integer weekOffset, CpfrAgreement agr) {
        if (weekOffset == null) {
            return CpfrPlanLine.ZONE_FREE;
        }
        if (agr.getFreezeWeeks() != null && weekOffset <= agr.getFreezeWeeks()) {
            return CpfrPlanLine.ZONE_FREEZE;
        }
        if (agr.getElasticEndWeek() != null && weekOffset <= agr.getElasticEndWeek()) {
            return CpfrPlanLine.ZONE_ELASTIC;
        }
        return CpfrPlanLine.ZONE_FREE;
    }

    /**
     * 实时 ZONE（design D3）：行存储的 weekOffset 相对生成周；
     * 随周推移，实际偏移 = weekOffset − 已过周数（行生成周 → 当前周一）。
     */
    String liveZone(CpfrPlanLine line, CpfrAgreement agr) {
        LocalDate lineMonday = line.getCreateDate() == null
                ? clockService.currentMonday()
                : line.getCreateDate().toLocalDate().with(java.time.DayOfWeek.MONDAY);
        long weeksPassed = Math.max(0, ChronoUnit.WEEKS.between(lineMonday, clockService.currentMonday()));
        int effective = line.getWeekOffset() - (int) weeksPassed;
        if (effective <= 0) {
            return CpfrPlanLine.ZONE_FREEZE; // 已进入执行周，按冻结处理
        }
        return zoneOf(effective, agr);
    }

    CpfrPlan require(String id) {
        CpfrPlan p = planDao.selectById(id);
        if (p == null) {
            throw new ServiceException(404, "联合补货计划不存在");
        }
        return p;
    }

    List<Map<String, Object>> lines(String planId) {
        List<CpfrPlanLine> list = lineDao.selectList(new LambdaQueryWrapper<CpfrPlanLine>()
                .eq(CpfrPlanLine::getPlanId, planId)
                .orderByAsc(CpfrPlanLine::getWeekOffset));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpfrPlanLine l : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("itemCode", l.getItemCode());
            m.put("itemName", l.getItemName());
            m.put("weekOffset", l.getWeekOffset());
            m.put("qty", l.getQty());
            m.put("zone", l.getZone());
            m.put("poConversionQty", l.getPoConversionQty());
            m.put("convertedAt", l.getConvertedAt());
            m.put("adjustStatus", l.getAdjustStatus());
            out.add(m);
        }
        return out;
    }

    void writePlanLog(CpfrPlanLine line, String action, BigDecimal oldQty, BigDecimal newQty, String remark) {
        CpfrAdjustLog log = new CpfrAdjustLog();
        log.setTargetType(CpfrAdjustLog.TT_PLAN_LINE);
        log.setTargetId(line.getId());
        log.setAgreementId(line.getAgreementId());
        log.setPeriodTag(line.getPeriodTag());
        log.setAction(action);
        log.setOldQty(oldQty);
        log.setNewQty(newQty);
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        log.setOperator(auth == null ? null : auth.getName());
        log.setRemark(remark);
        log.setOperatedAt(clockService.now());
        adjustLogDao.insert(log);
    }

    static Map<String, Object> toMap(CpfrPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("agreementId", p.getAgreementId());
        m.put("periodTag", p.getPeriodTag());
        m.put("status", p.getStatus());
        m.put("supplierConfirmBy", p.getSupplierConfirmBy());
        m.put("supplierConfirmAt", p.getSupplierConfirmAt());
        m.put("internalConfirmBy", p.getInternalConfirmBy());
        m.put("internalConfirmAt", p.getInternalConfirmAt());
        m.put("openExceptionCount", p.getOpenExceptionCount());
        m.put("accuracyPct", p.getAccuracyPct());
        m.put("accuracyMonth", p.getAccuracyMonth());
        return m;
    }
}
