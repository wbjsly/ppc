package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrPayAccountMappingDao;
import com.erp.dao.hr.HrPayrollRunDao;
import com.erp.dao.hr.HrPayrollRunItemDao;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.hr.HrPayAccountMapping;
import com.erp.entity.hr.HrPayrollRun;
import com.erp.entity.hr.HrPayrollRunItem;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.hr.HrPayVoucherService;
import com.erp.service.hr.HrSocialInsuranceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工资凭证联动（仿转资凭证引擎，spec payroll-disbursement 组 6）：
 * SOURCE_TYPE='PAYROLL'，sourceDocNo=批次号，listBySource 幂等锚点；
 * 借方按 COST_CENTER 映射分摊（缺配置用 DEFAULT_EXPENSE 并 L4 留痕，不阻断发薪）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrPayVoucherServiceImpl implements HrPayVoucherService {

    private static final String SRC = "PAYROLL";

    private final HrPayrollRunDao runDao;
    private final HrPayrollRunItemDao itemDao;
    private final HrPayAccountMappingDao mappingDao;
    private final HrSocialInsuranceService socialService;
    private final GlVoucherService voucherService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> accrual(String runId) {
        HrPayrollRun run = requireRun(runId);
        if (!HrPayrollRun.ST_APPROVED.equals(run.getStatus())) {
            throw new ServiceException(422, "仅审批通过批次计提");
        }
        // 幂等：同 sourceDocNo 已有 PAYROLL 凭证则跳过
        if (!voucherService.listBySource(SRC, run.getRunNo()).isEmpty()) {
            return Map.of("created", false, "note", "凭证已存在（幂等跳过）");
        }
        List<HrPayrollRunItem> items = runItems(runId);
        boolean correction = HrPayrollRun.TYPE_CORRECTION.equals(run.getRunType());
        BigDecimal empSocialTotal = BigDecimal.ZERO;
        // 借方按成本中心分摊（工资部分：基本+绩效+补贴+加班）
        Map<String, BigDecimal> expenseByCc = new LinkedHashMap<>();
        BigDecimal wageTotal = BigDecimal.ZERO;
        BigDecimal deductTotal = BigDecimal.ZERO;
        for (HrPayrollRunItem item : items) {
            BigDecimal wage = item.getBaseSalary().add(item.getPerfAmount())
                    .add(item.getAllowance()).add(item.getOvertimeWage());
            // 更正批次：净额差额单列（gross=delta），wage 即 delta
            String cc = item.getCostCenter() == null || item.getCostCenter().isBlank()
                    ? "-" : item.getCostCenter();
            expenseByCc.merge(cc, wage, BigDecimal::add);
            wageTotal = wageTotal.add(wage);
            if (!correction) {
                deductTotal = deductTotal.add(item.getSocialEe()).add(item.getProvidentEe())
                        .add(item.getIit()).add(item.getOtherDeduct());
                // 单位部分（重算）：L1 阻断的员工不在 items 里，此处应全员可算；
                // 减员口径与核算一致（DONE 减员单且生效日 ≤ 核算月末 → 归零，social-insurance-ops）
                try {
                    int runYear = Integer.parseInt(run.getPeriod().substring(0, 4));
                    int runMonth = Integer.parseInt(run.getPeriod().substring(4, 6));
                    Map<String, Object> social = socialService.calc(item.getEmpId(), runYear,
                            java.time.YearMonth.of(runYear, runMonth).atEndOfMonth());
                    empSocialTotal = empSocialTotal.add((BigDecimal) social.get("empTotal"));
                } catch (ServiceException ex) {
                    log.warn("计提单位社保重算失败 emp={}：{}", item.getEmpId(), ex.getMessage());
                }
            }
        }
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        List<String> fallbackNotes = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> en : expenseByCc.entrySet()) {
            String account = expenseAccount(en.getKey());
            if (account == null) {
                account = fixedAccount(HrPayAccountMapping.KEY_DEFAULT_EXPENSE);
                fallbackNotes.add("成本中心 " + en.getKey() + " 无科目映射，用 DEFAULT_EXPENSE（L4）");
            }
            // 更正批次差额方向：正差=正常计提，负差=反向
            boolean negative = en.getValue().signum() < 0;
            BigDecimal amt = en.getValue().abs().setScale(2, RoundingMode.HALF_UP);
            lines.add(GlVoucherService.FinVoucherLineSpec.of(account,
                    negative ? "Cr" : "Dr", amt, run.getRunNo() + " 工资计提（成本中心 " + en.getKey() + "）"));
        }
        if (!correction && empSocialTotal.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of("660104",
                    "Dr", empSocialTotal.setScale(2, RoundingMode.HALF_UP),
                    run.getRunNo() + " 社保公积金计提（单位部分）"));
        }
        // 贷方：应付工资（更正批次=差额；正常批次=全额）
        lines.add(GlVoucherService.FinVoucherLineSpec.of(
                fixedAccount(HrPayAccountMapping.KEY_SALARY_PAYABLE), "Cr",
                wageTotal.setScale(2, RoundingMode.HALF_UP), run.getRunNo() + " 应付工资"));
        if (!correction && empSocialTotal.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(
                    fixedAccount(HrPayAccountMapping.KEY_SOCIAL_PAYABLE), "Cr",
                    empSocialTotal.setScale(2, RoundingMode.HALF_UP), run.getRunNo() + " 应付社保公积金（单位）"));
        }
        FinVoucher v = voucherService.create("ACC", periodDate(run.getPeriod()),
                "工资计提 " + run.getRunNo(), SRC, run.getRunNo(), null, lines);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", true);
        out.put("voucherId", v.getId());
        out.put("voucherNo", v.getVoucherNo());
        out.put("fallbacks", fallbackNotes); // L4 留痕（映射缺失）
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> payment(String runId) {
        HrPayrollRun run = requireRun(runId);
        if (!HrPayrollRun.ST_PAID.equals(run.getStatus())
                && !HrPayrollRun.ST_PARTIAL.equals(run.getStatus())
                && !HrPayrollRun.ST_RECONCILED.equals(run.getStatus())
                && !HrPayrollRun.ST_ARCHIVED.equals(run.getStatus())) {
            throw new ServiceException(422, "仅发放登记后生成付款凭证（当前：" + run.getStatus() + "）");
        }
        String anchor = run.getRunNo() + "-PAY";
        if (!voucherService.listBySource(SRC, anchor).isEmpty()) {
            return Map.of("created", false, "note", "付款凭证已存在（幂等跳过）");
        }
        List<HrPayrollRunItem> items = runItems(runId);
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal iit = BigDecimal.ZERO;
        BigDecimal socialEe = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;
        for (HrPayrollRunItem item : items) {
            net = net.add(item.getNet());
            iit = iit.add(item.getIit());
            socialEe = socialEe.add(item.getSocialEe()).add(item.getProvidentEe());
            other = other.add(item.getOtherDeduct());
        }
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        lines.add(GlVoucherService.FinVoucherLineSpec.of(
                fixedAccount(HrPayAccountMapping.KEY_SALARY_PAYABLE), "Dr",
                run.getGrossTotal(), run.getRunNo() + " 工资发放"));
        lines.add(GlVoucherService.FinVoucherLineSpec.of(
                fixedAccount(HrPayAccountMapping.KEY_BANK), "Cr",
                net.setScale(2, RoundingMode.HALF_UP), run.getRunNo() + " 银行实付"));
        if (iit.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(
                    fixedAccount(HrPayAccountMapping.KEY_IIT_PAYABLE), "Cr",
                    iit.setScale(2, RoundingMode.HALF_UP), run.getRunNo() + " 代扣个税"));
        }
        if (socialEe.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(
                    fixedAccount(HrPayAccountMapping.KEY_SOCIAL_PAYABLE), "Cr",
                    socialEe.setScale(2, RoundingMode.HALF_UP), run.getRunNo() + " 代扣社保公积金（个人）"));
        }
        if (other.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(
                    fixedAccount("OTHER_DEDUCT_PAYABLE"), "Cr",
                    other.setScale(2, RoundingMode.HALF_UP), run.getRunNo() + " 其他代扣款"));
        }
        FinVoucher v = voucherService.create("ACC", periodDate(run.getPeriod()),
                "工资发放 " + run.getRunNo(), SRC, anchor, null, lines);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", true);
        out.put("voucherId", v.getId());
        out.put("voucherNo", v.getVoucherNo());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reverseByRun(String runId, String reason) {
        HrPayrollRun run = requireRun(runId);
        int reversed = 0;
        for (Map<String, Object> v : voucherService.listBySource(SRC, run.getRunNo())) {
            String vid = String.valueOf(v.get("id"));
            voucherService.reverse(vid, reason, run.getRunNo());
            reversed++;
        }
        for (Map<String, Object> v : voucherService.listBySource(SRC, run.getRunNo() + "-PAY")) {
            String vid = String.valueOf(v.get("id"));
            voucherService.reverse(vid, reason, run.getRunNo() + "-PAY");
            reversed++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reversed", reversed);
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 成本中心→费用科目映射（缺配置返回 null，调用方回退 DEFAULT_EXPENSE 并 L4）。 */
    private String expenseAccount(String costCenter) {
        HrPayAccountMapping m = mappingDao.selectOne(new LambdaQueryWrapper<HrPayAccountMapping>()
                .eq(HrPayAccountMapping::getMappingType, HrPayAccountMapping.TYPE_COST_CENTER)
                .eq(HrPayAccountMapping::getMappingKey, costCenter)
                .eq(HrPayAccountMapping::getDelFlag, "0"));
        return m == null ? null : m.getAccountCode();
    }

    private String fixedAccount(String key) {
        HrPayAccountMapping m = mappingDao.selectOne(new LambdaQueryWrapper<HrPayAccountMapping>()
                .eq(HrPayAccountMapping::getMappingType, HrPayAccountMapping.TYPE_FIXED)
                .eq(HrPayAccountMapping::getMappingKey, key)
                .eq(HrPayAccountMapping::getDelFlag, "0"));
        if (m == null) {
            throw new ServiceException(500, "科目映射缺失：" + key + "（迁移 162 未执行？）");
        }
        return m.getAccountCode();
    }

    private LocalDate periodDate(String period) {
        return LocalDate.of(Integer.parseInt(period.substring(0, 4)),
                Integer.parseInt(period.substring(4)), 1);
    }

    private List<HrPayrollRunItem> runItems(String runId) {
        return itemDao.selectList(new LambdaQueryWrapper<HrPayrollRunItem>()
                .eq(HrPayrollRunItem::getRunId, runId)
                .eq(HrPayrollRunItem::getDelFlag, "0"));
    }

    private HrPayrollRun requireRun(String id) {
        HrPayrollRun run = runDao.selectById(id);
        if (run == null) {
            throw new ServiceException(404, "PayrollRun 不存在：" + id);
        }
        return run;
    }
}
