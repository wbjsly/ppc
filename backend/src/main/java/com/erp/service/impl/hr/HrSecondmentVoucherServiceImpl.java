package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrPayAccountMappingDao;
import com.erp.dao.hr.HrPayrollRunDao;
import com.erp.dao.hr.HrPayrollRunItemDao;
import com.erp.dao.hr.HrSecondmentDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrPayAccountMapping;
import com.erp.entity.hr.HrPayrollRun;
import com.erp.entity.hr.HrPayrollRunItem;
import com.erp.entity.hr.HrSecondment;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.hr.HrSecondmentVoucherService;
import com.erp.service.hr.HrSocialInsuranceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 借调成本月度分摊凭证（仿 payroll 计提引擎）：SOURCE_TYPE='SECONDMENT'，
 * sourceDocNo=单号@yyyyMM 成对锚点；人力成本=已审批批次 GROSS+单位社保，无批次基数估算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrSecondmentVoucherServiceImpl implements HrSecondmentVoucherService {

    private final HrSecondmentDao secDao;
    private final HrEmployeeDao empDao;
    private final HrPayrollRunDao runDao;
    private final HrPayrollRunItemDao itemDao;
    private final HrPayAccountMappingDao mappingDao;
    private final HrSocialInsuranceService socialService;
    private final GlVoucherService voucherService;

    public static final String SRC = "SECONDMENT";

    /** 有在途分摊价值的状态（EXPIRED_ALERT 显式排除——L1 阻断分摊） */
    private static final List<String> ALLOCABLE_STATES = List.of(
            HrSecondment.ST_EFFECTED, HrSecondment.ST_EXTENDING,
            HrSecondment.ST_RETURNING, HrSecondment.ST_RETURNED);

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> monthlyGenerate(String ymParam) {
        YearMonth ym = ymParam == null || ymParam.isBlank()
                ? YearMonth.now().minusMonths(1) : YearMonth.parse(ymParam.substring(0, 4) + "-" + ymParam.substring(4, 6));
        List<Map<String, Object>> details = new ArrayList<>();
        int generated = 0;
        int failed = 0;
        // 过期告警单单独计数（L1 阻断分摊，C-4.7-13→BR-4.7-05）
        Long expiredCnt = secDao.selectCount(new LambdaQueryWrapper<HrSecondment>()
                .eq(HrSecondment::getDelFlag, "0")
                .eq(HrSecondment::getChgType, HrSecondment.TYPE_SECOND)
                .eq(HrSecondment::getStatus, HrSecondment.ST_EXPIRED_ALERT));
        int skippedExpired = expiredCnt == null ? 0 : expiredCnt.intValue();
        for (HrSecondment s : secDao.selectList(new LambdaQueryWrapper<HrSecondment>()
                .eq(HrSecondment::getDelFlag, "0")
                .eq(HrSecondment::getChgType, HrSecondment.TYPE_SECOND)
                .in(HrSecondment::getStatus, ALLOCABLE_STATES))) {
            try {
                Map<String, Object> one = generateOne(s, ym);
                if (one != null) {
                    details.add(one);
                    generated++;
                }
            } catch (Exception ex) {
                log.warn("secondment allocation failed {}: {}", s.getSecNo(), ex.getMessage());
                failed++;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("secNo", s.getSecNo());
                d.put("error", ex.getMessage());
                details.add(d);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ym", ym.toString().replace("-", ""));
        out.put("generated", generated);
        out.put("skippedExpired", skippedExpired);
        out.put("failed", failed);
        out.put("details", details);
        return out;
    }

    @Override
    public List<Map<String, Object>> preview(String ymParam) {
        YearMonth ym = ymParam == null || ymParam.isBlank()
                ? YearMonth.now().minusMonths(1) : YearMonth.parse(ymParam.substring(0, 4) + "-" + ymParam.substring(4, 6));
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrSecondment s : secDao.selectList(new LambdaQueryWrapper<HrSecondment>()
                .eq(HrSecondment::getDelFlag, "0")
                .eq(HrSecondment::getChgType, HrSecondment.TYPE_SECOND)
                .in(HrSecondment::getStatus, ALLOCABLE_STATES))) {
            LocalDate[] overlap = overlapOf(s, ym);
            if (overlap == null) {
                continue;
            }
            Map<String, Object> row = allocRow(s, ym, overlap);
            if (row != null) {
                row.put("anchor", s.getSecNo() + "@" + ym.toString().replace("-", ""));
                out.add(row);
            }
        }
        return out;
    }

    // ================================================================
    // internals
    // ================================================================

    /** 生成单据的当月成对凭证；无需生成返回 null */
    private Map<String, Object> generateOne(HrSecondment s, YearMonth ym) {
        LocalDate[] overlap = overlapOf(s, ym);
        if (overlap == null) {
            return null;
        }
        String anchor = s.getSecNo() + "@" + ym.toString().replace("-", "");
        // 幂等：同锚点已有凭证跳过（含冲销对）
        if (!voucherService.listBySource(SRC, anchor).isEmpty()) {
            return null;
        }
        Map<String, Object> row = allocRow(s, ym, overlap);
        if (row == null) {
            return null;
        }
        BigDecimal alloc = (BigDecimal) row.get("allocAmount");
        if (alloc.signum() <= 0) {
            return null;
        }
        String note = row.get("estimateNote") == null ? "" : "（" + row.get("estimateNote") + "）";
        LocalDate periodDate = ym.atEndOfMonth();
        // 调出方（劳动关系主体）：借 其他应收款-用工主体 / 贷 应付职工薪酬
        List<GlVoucherService.FinVoucherLineSpec> outLines = List.of(
                GlVoucherService.FinVoucherLineSpec.of(fixedAccount("SEC_RECEIVABLE"),
                        "Dr", alloc, "借调分摊-调出方 " + s.getSecNo() + " " + ym + note),
                GlVoucherService.FinVoucherLineSpec.of(fixedAccount("SALARY_PAYABLE"),
                        "Cr", alloc, "借调分摊-调出方 " + s.getSecNo() + " " + ym + note));
        voucherService.create("ACC", periodDate, "借调分摊-调出方 " + s.getSecNo() + " " + ym,
                SRC, anchor, null, outLines);
        // 调入方（用工主体）：借 应付职工薪酬-成本分摊 / 贷 其他应付款-调出方
        List<GlVoucherService.FinVoucherLineSpec> inLines = List.of(
                GlVoucherService.FinVoucherLineSpec.of(fixedAccount("SEC_ALLOC_EXPENSE"),
                        "Dr", alloc, "借调分摊-调入方 " + s.getSecNo() + " " + ym + note),
                GlVoucherService.FinVoucherLineSpec.of(fixedAccount("SEC_PAYABLE_OTHER"),
                        "Cr", alloc, "借调分摊-调入方 " + s.getSecNo() + " " + ym + note));
        voucherService.create("ACC", periodDate, "借调分摊-调入方 " + s.getSecNo() + " " + ym,
                SRC, anchor, null, inLines);
        row.put("anchor", anchor);
        return row;
    }

    /** 在途与目标月的重叠区间（含端点）；无重叠返回 null */
    private LocalDate[] overlapOf(HrSecondment s, YearMonth ym) {
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        LocalDate effEnd = s.getEndDate();
        if (HrSecondment.ST_RETURNED.equals(s.getStatus()) && s.getReturnAt() != null) {
            LocalDate ret = s.getReturnAt().toLocalDate();
            if (ret.isBefore(effEnd)) {
                effEnd = ret; // 返岗当月折算至返岗日
            }
        }
        LocalDate start = s.getStartDate().isAfter(monthStart) ? s.getStartDate() : monthStart;
        LocalDate end = effEnd.isBefore(monthEnd) ? effEnd : monthEnd;
        if (start.isAfter(end)) {
            return null;
        }
        return new LocalDate[]{start, end};
    }

    /** 分摊行计算（预览与生成共用）；成本为 0 时返回 null */
    private Map<String, Object> allocRow(HrSecondment s, YearMonth ym, LocalDate[] overlap) {
        if (s.getCostSharePct() == null || s.getCostSharePct().signum() <= 0) {
            return null;
        }
        HrEmployee e = empDao.selectById(s.getEmpId());
        if (e == null || e.getBaseSalary() == null) {
            return null;
        }
        // 月人力成本：已审批批次 GROSS + 单位社保；无批次 → 月薪基数估算
        BigDecimal gross = null;
        HrPayrollRunItem item = itemDao.selectOne(new LambdaQueryWrapper<HrPayrollRunItem>()
                .eq(HrPayrollRunItem::getEmpId, s.getEmpId())
                .eq(HrPayrollRunItem::getPeriod, ym.toString().replace("-", ""))
                .last("LIMIT 1"));
        if (item != null) {
            HrPayrollRun run = runDao.selectById(item.getRunId());
            if (run != null && !HrPayrollRun.ST_DRAFT.equals(run.getStatus())
                    && !HrPayrollRun.ST_REJECTED.equals(run.getStatus())
                    && !HrPayrollRun.ST_VOID.equals(run.getStatus())) {
                gross = item.getGross();
            }
        }
        String estimateNote = null;
        if (gross == null) {
            gross = e.getBaseSalary();
            estimateNote = "基数估算";
        }
        BigDecimal employerSocial = BigDecimal.ZERO;
        try {
            Map<String, Object> social = socialService.calc(e.getId(), ym.getYear());
            employerSocial = (BigDecimal) social.get("empTotal");
        } catch (ServiceException ex) {
            estimateNote = (estimateNote == null ? "" : estimateNote + "，") + "社保未匹配：" + ex.getMessage();
        }
        BigDecimal monthCost = gross.add(employerSocial);
        long inDays = ChronoUnit.DAYS.between(overlap[0], overlap[1]) + 1;
        long monthDays = ym.lengthOfMonth();
        BigDecimal alloc = monthCost
                .multiply(s.getCostSharePct()).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(inDays)).divide(BigDecimal.valueOf(monthDays), 2, RoundingMode.HALF_UP);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("secNo", s.getSecNo());
        row.put("empId", s.getEmpId());
        row.put("empName", s.getEmpName());
        row.put("targetLe", s.getTargetLe());
        row.put("sharePct", s.getCostSharePct());
        row.put("inDays", inDays);
        row.put("monthDays", monthDays);
        row.put("monthCost", monthCost.setScale(2, RoundingMode.HALF_UP));
        row.put("allocAmount", alloc);
        row.put("estimateNote", estimateNote);
        return row;
    }

    private String fixedAccount(String key) {
        HrPayAccountMapping m = mappingDao.selectOne(new LambdaQueryWrapper<HrPayAccountMapping>()
                .eq(HrPayAccountMapping::getMappingType, HrPayAccountMapping.TYPE_FIXED)
                .eq(HrPayAccountMapping::getMappingKey, key)
                .eq(HrPayAccountMapping::getDelFlag, "0"));
        if (m == null) {
            throw new ServiceException(500, "分摊科目映射缺失：" + key + "（迁移 164 未执行？）");
        }
        return m.getAccountCode();
    }
}
