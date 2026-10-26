package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrPayDiffDao;
import com.erp.dao.hr.HrPayDisbursementDao;
import com.erp.dao.hr.HrPayExecutionDao;
import com.erp.dao.hr.HrPayReceiptDao;
import com.erp.dao.hr.HrPayrollRunDao;
import com.erp.dao.hr.HrPayrollRunItemDao;
import com.erp.dao.hr.PayAuditLogDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrPayDiff;
import com.erp.entity.hr.HrPayDisbursement;
import com.erp.entity.hr.HrPayExecution;
import com.erp.entity.hr.HrPayReceipt;
import com.erp.entity.hr.HrPayrollRun;
import com.erp.entity.hr.HrPayrollRunItem;
import com.erp.entity.hr.PayAuditLog;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrPayDisbursementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 薪酬发放与对账实现（代发 CSV/登记核对/回单逐笔匹配/差异 5 日/归档）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrPayDisbursementServiceImpl implements HrPayDisbursementService {

    private final HrPayDisbursementDao disbDao;
    private final HrPayExecutionDao execDao;
    private final HrPayReceiptDao receiptDao;
    private final HrPayDiffDao diffDao;
    private final HrPayrollRunDao runDao;
    private final HrPayrollRunItemDao itemDao;
    private final HrEmployeeDao empDao;
    private final PayAuditLogDao auditDao;
    private final SysParamService sysParamService;
    private final com.erp.service.hr.HrPayVoucherService payVoucherService;

    // ================================================================
    // 代发文件
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayDisbursement generateFile(String runId, Map<String, Object> payload) {
        HrPayrollRun run = requireRun(runId);
        if (!HrPayrollRun.ST_APPROVED.equals(run.getStatus())) {
            throw new ServiceException(422, "仅审批通过批次可生成代发文件");
        }
        // 重复生成须作废旧文件
        Long active = disbDao.selectCount(new LambdaQueryWrapper<HrPayDisbursement>()
                .eq(HrPayDisbursement::getRunId, runId)
                .eq(HrPayDisbursement::getStatus, HrPayDisbursement.ST_ACTIVE)
                .eq(HrPayDisbursement::getDelFlag, "0"));
        if (active != null && active > 0) {
            throw new ServiceException(422, "已存在有效代发文件，须先作废后重新生成");
        }
        List<HrPayrollRunItem> items = runItems(runId);
        StringBuilder csv = new StringBuilder("姓名,银行账号,实发金额\n"); // 表头行（模板可配留桩）
        int lines = 0;
        List<String> missingCard = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (HrPayrollRunItem item : items) {
            HrEmployee e = empDao.selectById(item.getEmpId());
            String card = e == null ? null : e.getBankCardNo();
            if (card == null || card.isBlank()) {
                missingCard.add(item.getEmpName() + "(" + item.getEmpNo() + ")");
                continue;
            }
            csv.append(item.getEmpName()).append(',').append(card).append(',')
                    .append(item.getNet().setScale(2, RoundingMode.HALF_UP)).append('\n');
            total = total.add(item.getNet());
            lines++;
        }
        if (lines == 0) {
            throw new ServiceException(422, "无可入文件员工（全部缺银行卡号）");
        }
        // 合计=批次实发总额校验（缺卡阻断后不平属预期，须 HR 补录后重生成——此处按实发小计落库并提示）
        if (missingCard.isEmpty() && total.setScale(2, RoundingMode.HALF_UP)
                .compareTo(run.getNetTotal()) != 0) {
            throw new ServiceException(422, "代发合计 " + total + " ≠ 批次实发总额 " + run.getNetTotal());
        }
        HrPayDisbursement d = new HrPayDisbursement();
        d.setId(uuid());
        d.setFileNo(nextFileNo());
        d.setRunId(runId);
        d.setPeriod(run.getPeriod());
        d.setLineCount(lines);
        d.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        String format = payload != null && payload.get("format") != null
                ? String.valueOf(payload.get("format")) : "CSV";
        d.setFormat(format);
        d.setContent(csv.toString());
        d.setStatus(HrPayDisbursement.ST_ACTIVE);
        d.setCreateBy(currentUser());
        d.setCreateDate(LocalDateTime.now());
        disbDao.insert(d);
        audit("GENERATE_FILE", "PAY_DISB", d.getId(), run.getPeriod(),
                "代发文件 " + d.getFileNo() + " " + lines + " 笔 合计 " + d.getTotalAmount()
                        + (missingCard.isEmpty() ? "" : "；缺卡阻断 " + missingCard));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("disb", d);
        if (!missingCard.isEmpty()) {
            out.put("missingCard", missingCard);
        }
        return d;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayDisbursement voidFile(String disbId, String reason) {
        HrPayDisbursement d = disbDao.selectById(disbId);
        if (d == null) {
            throw new ServiceException(404, "代发文件不存在");
        }
        if (!HrPayDisbursement.ST_ACTIVE.equals(d.getStatus())) {
            throw new ServiceException(422, "仅 ACTIVE 可作废");
        }
        require(reason, "作废原因");
        d.setStatus(HrPayDisbursement.ST_VOID);
        d.setVoidReason(reason);
        d.setUpdateBy(currentUser());
        d.setUpdateDate(LocalDateTime.now());
        disbDao.updateById(d);
        audit("VOID_FILE", "PAY_DISB", disbId, d.getPeriod(), "作废：" + reason);
        return d;
    }

    @Override
    public List<HrPayDisbursement> fileList(String period, String runId) {
        LambdaQueryWrapper<HrPayDisbursement> qw = new LambdaQueryWrapper<HrPayDisbursement>()
                .eq(HrPayDisbursement::getDelFlag, "0")
                .orderByDesc(HrPayDisbursement::getCreateDate);
        if (notBlank(period)) {
            qw.eq(HrPayDisbursement::getPeriod, period);
        }
        if (notBlank(runId)) {
            qw.eq(HrPayDisbursement::getRunId, runId);
        }
        return disbDao.selectList(qw);
    }

    // ================================================================
    // 发放执行登记
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayExecution registerExecution(Map<String, Object> payload) {
        String disbId = str(payload.get("disbId"));
        HrPayDisbursement d = disbId == null ? null : disbDao.selectById(disbId);
        if (d == null || !HrPayDisbursement.ST_ACTIVE.equals(d.getStatus())) {
            throw new ServiceException(422, "代发文件无效");
        }
        HrPayrollRun run = requireRun(d.getRunId());
        if (!HrPayrollRun.ST_APPROVED.equals(run.getStatus())) {
            throw new ServiceException(422, "批次状态不可登记发放（当前：" + run.getStatus() + "）");
        }
        BigDecimal paidTotal = dec(payload.get("paidTotal"));
        if (paidTotal == null) {
            throw new ServiceException(422, "实付合计必填");
        }
        if (paidTotal.compareTo(d.getTotalAmount()) != 0) {
            // 不平须逐笔核对后强制备注
            if (!notBlank(str(payload.get("mismatchNote")))) {
                throw new ServiceException(422, "实付合计与代发文件不一致，须逐笔核对并备注原因");
            }
        }
        int success = intOr(payload.get("successCount"), d.getLineCount());
        int fail = intOr(payload.get("failCount"), 0);
        String failDetail = str(payload.get("failDetail"));
        HrPayExecution x = new HrPayExecution();
        x.setId(uuid());
        x.setRunId(d.getRunId());
        x.setDisbId(disbId);
        x.setPeriod(d.getPeriod());
        x.setPayDate(date(payload.get("payDate")));
        x.setPayAccount(str(payload.get("payAccount")));
        x.setPaidTotal(paidTotal);
        x.setBankSerial(str(payload.get("bankSerial")));
        x.setSuccessCount(success);
        x.setFailCount(fail);
        x.setFailDetail(failDetail);
        x.setStatus(fail > 0 ? HrPayExecution.ST_PARTIAL : HrPayExecution.ST_PAID);
        x.setCreateBy(currentUser());
        x.setCreateDate(LocalDateTime.now());
        execDao.insert(x);
        // 批次状态跃迁 + 付款凭证钩子（group 6 同事务外由凭证服务监听——本期直接标记）
        run.setStatus(fail > 0 ? HrPayrollRun.ST_PARTIAL : HrPayrollRun.ST_PAID);
        run.setUpdateBy(currentUser());
        run.setUpdateDate(LocalDateTime.now());
        runDao.updateById(run);
        // 失败笔进差异清单
        if (fail > 0 && notBlank(failDetail)) {
            for (String line : failDetail.split("[;；]")) {
                String[] parts = line.split("[,/，]");
                if (parts.length >= 2) {
                    saveDiff(run, parts[0].trim(), HrPayDiff.TYPE_FAILED,
                            new BigDecimal(parts[1].trim()), null, "付款失败：" + line);
                }
            }
        }
        audit("EXECUTE", "PAY_EXEC", x.getId(), d.getPeriod(),
                "发放登记 " + x.getStatus() + " 实付 " + paidTotal + " 失败 " + fail + " 笔");
        // 发放登记 → 付款凭证（借应付工资/贷银行+代扣；失败不阻断登记，仅告警）
        try {
            payVoucherService.payment(d.getRunId());
        } catch (Exception e) {
            log.error("工资付款凭证生成失败 run={}：{}", d.getRunId(), e.getMessage());
        }
        return x;
    }

    @Override
    public List<HrPayExecution> executionList(String runId) {
        return execDao.selectList(new LambdaQueryWrapper<HrPayExecution>()
                .eq(HrPayExecution::getRunId, runId)
                .eq(HrPayExecution::getDelFlag, "0")
                .orderByDesc(HrPayExecution::getCreateDate));
    }

    // ================================================================
    // 回单与对账
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayReceipt registerReceipt(Map<String, Object> payload) {
        String receiptNo = str(payload.get("receiptNo"));
        require(receiptNo, "回单号");
        if (receiptDao.selectCount(new LambdaQueryWrapper<HrPayReceipt>()
                .eq(HrPayReceipt::getReceiptNo, receiptNo)
                .eq(HrPayReceipt::getDelFlag, "0")) > 0) {
            throw new ServiceException(409, "回单号已登记：" + receiptNo);
        }
        BigDecimal amount = dec(payload.get("amount"));
        if (amount == null) {
            throw new ServiceException(422, "回单金额必填");
        }
        HrPayReceipt r = new HrPayReceipt();
        r.setId(uuid());
        r.setReceiptNo(receiptNo);
        r.setRunId(str(payload.get("runId")));
        r.setDisbId(str(payload.get("disbId")));
        r.setReceiptDate(date(payload.get("receiptDate")));
        r.setAmount(amount);
        r.setSummary(str(payload.get("summary")));
        r.setMatchStatus(HrPayReceipt.ST_UNMATCHED);
        r.setCreateBy(currentUser());
        r.setCreateDate(LocalDateTime.now());
        receiptDao.insert(r);
        audit("RECEIPT", "PAY_RECEIPT", r.getId(), null, "回单登记 " + receiptNo + " 金额 " + amount);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reconcile(String runId) {
        HrPayrollRun run = requireRun(runId);
        List<HrPayDisbursement> activeDisb = disbDao.selectList(new LambdaQueryWrapper<HrPayDisbursement>()
                .eq(HrPayDisbursement::getRunId, runId)
                .eq(HrPayDisbursement::getStatus, HrPayDisbursement.ST_ACTIVE)
                .eq(HrPayDisbursement::getDelFlag, "0"));
        if (activeDisb.isEmpty()) {
            throw new ServiceException(422, "无有效代发文件");
        }
        HrPayDisbursement d = activeDisb.get(0);
        List<HrPayReceipt> receipts = receiptDao.selectList(new LambdaQueryWrapper<HrPayReceipt>()
                .eq(HrPayReceipt::getDelFlag, "0")
                .eq(HrPayReceipt::getMatchStatus, HrPayReceipt.ST_UNMATCHED)
                .eq(notBlank(d.getRunId()), HrPayReceipt::getRunId, runId));
        BigDecimal tolerance = decOr(sysParamService.getValue("PAY_RECON_TOLERANCE"), new BigDecimal("0.01"));
        List<HrPayrollRunItem> items = runItems(runId);
        int matched = 0;
        List<Map<String, Object>> unmatched = new ArrayList<>();
        // 金额精确匹配优先（账号在明细行无独立回单字段时以金额+顺序兜底）
        Set<String> matchedItemIds = new HashSet<>();
        for (HrPayReceipt r : receipts) {
            HrPayrollRunItem hit = null;
            for (HrPayrollRunItem item : items) {
                if (matchedItemIds.contains(item.getId())) {
                    continue; // 已被其他回单匹配（同额员工不重复占用）
                }
                if (item.getNet().setScale(2, RoundingMode.HALF_UP)
                        .subtract(r.getAmount()).abs().compareTo(tolerance) <= 0) {
                    hit = item;
                    break;
                }
            }
            if (hit != null) {
                matchedItemIds.add(hit.getId());
                r.setMatchStatus(HrPayReceipt.ST_MATCHED);
                r.setMatchItemId(hit.getId());
                r.setRunId(runId);
                r.setUpdateBy(currentUser());
                r.setUpdateDate(LocalDateTime.now());
                receiptDao.updateById(r);
                matched++;
            } else {
                unmatched.add(Map.of("receiptNo", r.getReceiptNo(), "amount", r.getAmount()));
            }
        }
        // 未匹配回单 → 差异清单（多收/账号不符）
        for (HrPayReceipt r : receipts) {
            if (HrPayReceipt.ST_UNMATCHED.equals(r.getMatchStatus())) {
                saveDiff(run, null, HrPayDiff.TYPE_UNMATCHED, null, r.getAmount(),
                        "回单未匹配：" + r.getReceiptNo() + " 金额 " + r.getAmount());
            }
        }
        // 无回单员工 → 差异清单（缺回单/退汇少收），否则状态卡 PARTIAL 无法归档
        for (HrPayrollRunItem item : items) {
            if (!matchedItemIds.contains(item.getId())) {
                saveDiff(run, item.getEmpId(), HrPayDiff.TYPE_UNMATCHED,
                        item.getNet(), null, "员工无对应回单（缺回单/退汇）：" + item.getEmpName());
            }
        }
        // 逐笔全部匹配且合计一致 → RECONCILED
        BigDecimal receiptSum = receiptDao.selectList(new LambdaQueryWrapper<HrPayReceipt>()
                        .eq(HrPayReceipt::getRunId, runId)
                        .eq(HrPayReceipt::getMatchStatus, HrPayReceipt.ST_MATCHED)
                        .eq(HrPayReceipt::getDelFlag, "0"))
                .stream().map(HrPayReceipt::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean allMatched = unmatched.isEmpty()
                && matchedItemIds.size() == items.size()
                && receiptSum.setScale(2, RoundingMode.HALF_UP).compareTo(d.getTotalAmount()) == 0;
        if (allMatched) {
            run.setStatus(HrPayrollRun.ST_RECONCILED);
            run.setUpdateBy(currentUser());
            run.setUpdateDate(LocalDateTime.now());
            runDao.updateById(run);
        }
        audit("RECONCILE", "PAYROLL_RUN", runId, run.getPeriod(),
                "对账：匹配 " + matched + " 笔，未匹配 " + unmatched.size() + "，"
                        + (allMatched ? "对账完成" : "存在差异"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matched", matched);
        out.put("unmatched", unmatched);
        out.put("receiptsTotal", matched);
        out.put("reconciled", allMatched);
        return out;
    }

    @Override
    public List<HrPayDiff> diffList(String runId, String status) {
        LambdaQueryWrapper<HrPayDiff> qw = new LambdaQueryWrapper<HrPayDiff>()
                .eq(HrPayDiff::getDelFlag, "0")
                .orderByDesc(HrPayDiff::getCreateDate);
        if (notBlank(runId)) {
            qw.eq(HrPayDiff::getRunId, runId);
        }
        if (notBlank(status)) {
            qw.eq(HrPayDiff::getStatus, status);
        }
        return diffDao.selectList(qw);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayDiff resolveDiff(String diffId, Map<String, Object> payload) {
        HrPayDiff diff = diffDao.selectById(diffId);
        if (diff == null) {
            throw new ServiceException(404, "差异单不存在");
        }
        if (!HrPayDiff.ST_OPEN.equals(diff.getStatus())) {
            throw new ServiceException(422, "仅 OPEN 可处理");
        }
        String plan = str(payload.get("resolvePlan"));
        if (!List.of(HrPayDiff.PLAN_RESEND, HrPayDiff.PLAN_NEXT_MONTH, HrPayDiff.PLAN_MANUAL).contains(plan)) {
            throw new ServiceException(422, "方案仅支持 RESEND/NEXT_MONTH/MANUAL");
        }
        require(str(payload.get("resolveNote")), "处理说明");
        diff.setResolvePlan(plan);
        diff.setResolveNote(str(payload.get("resolveNote")));
        // L2 按方案判断：RESEND=原额重发不涉金额调整直接关闭；NEXT_MONTH/MANUAL 涉金额调整须 HR 经理审批
        boolean needL2 = !HrPayDiff.PLAN_RESEND.equals(plan);
        if (needL2) {
            diff.setStatus(HrPayDiff.ST_OPEN); // 待 HR 经理审批（apprBy 空）
            diff.setResolveNote(diff.getResolveNote() + "（待 L2 审批）");
        } else {
            diff.setStatus(HrPayDiff.ST_CLOSED);
        }
        diff.setUpdateBy(currentUser());
        diff.setUpdateDate(LocalDateTime.now());
        diffDao.updateById(diff);
        audit("DIFF_RESOLVE", "PAY_DIFF", diffId, diff.getPeriod(),
                "差异处理方案 " + plan + (needL2 ? "（待 L2）" : "（关闭）"));
        return diff;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayDiff approveDiff(String diffId, Map<String, Object> payload) {
        HrPayDiff diff = diffDao.selectById(diffId);
        if (diff == null) {
            throw new ServiceException(404, "差异单不存在");
        }
        if (diff.getResolvePlan() == null) {
            throw new ServiceException(422, "须先录处理方案");
        }
        boolean approve = !"false".equals(String.valueOf(payload.getOrDefault("approve", "true")));
        if (approve) {
            diff.setStatus(HrPayDiff.ST_CLOSED);
        } else {
            diff.setStatus(HrPayDiff.ST_OPEN);
            diff.setResolvePlan(null);
        }
        diff.setApprBy(currentUser());
        diff.setApprAt(LocalDateTime.now());
        diff.setUpdateBy(currentUser());
        diff.setUpdateDate(LocalDateTime.now());
        diffDao.updateById(diff);
        audit("DIFF_APPROVE", "PAY_DIFF", diffId, diff.getPeriod(), approve ? "L2 通过关闭" : "L2 驳回");
        return diff;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> archive(String runId) {
        HrPayrollRun run = requireRun(runId);
        if (!HrPayrollRun.ST_RECONCILED.equals(run.getStatus())) {
            throw new ServiceException(422, "仅对账完成（RECONCILED）可归档");
        }
        long openDiffs = diffDao.selectCount(new LambdaQueryWrapper<HrPayDiff>()
                .eq(HrPayDiff::getRunId, runId)
                .eq(HrPayDiff::getStatus, HrPayDiff.ST_OPEN)
                .eq(HrPayDiff::getDelFlag, "0"));
        if (openDiffs > 0) {
            throw new ServiceException(422, "存在未处理差异（" + openDiffs + " 条），处理完毕后方可归档");
        }
        run.setStatus(HrPayrollRun.ST_ARCHIVED);
        run.setUpdateBy(currentUser());
        run.setUpdateDate(LocalDateTime.now());
        runDao.updateById(run);
        // 凭证包快照（代发文件+回单+对账+差异留痕——以 runId 归集，全部已落库）
        audit("ARCHIVE", "PAYROLL_RUN", runId, run.getPeriod(), "发放归档（凭证包锁定）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("archived", true);
        out.put("bundle", Map.of(
                "runId", runId,
                "disbFiles", fileList(run.getPeriod(), runId).size(),
                "receipts", receiptDao.selectCount(new LambdaQueryWrapper<HrPayReceipt>()
                        .eq(HrPayReceipt::getRunId, runId)
                        .eq(HrPayReceipt::getDelFlag, "0")),
                "diffs", diffDao.selectCount(new LambdaQueryWrapper<HrPayDiff>()
                        .eq(HrPayDiff::getRunId, runId)
                        .eq(HrPayDiff::getDelFlag, "0"))));
        return out;
    }

    @Override
    public Map<String, Object> reconBoard(String runId) {
        HrPayrollRun run = requireRun(runId);
        List<HrPayReceipt> receipts = receiptDao.selectList(new LambdaQueryWrapper<HrPayReceipt>()
                .eq(HrPayReceipt::getRunId, runId)
                .eq(HrPayReceipt::getDelFlag, "0"));
        long matched = receipts.stream().filter(r -> HrPayReceipt.ST_MATCHED.equals(r.getMatchStatus())).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runStatus", run.getStatus());
        out.put("receiptCount", receipts.size());
        out.put("matched", matched);
        out.put("matchRate", receipts.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(matched).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(receipts.size()), 2, RoundingMode.HALF_UP));
        out.put("openDiffs", diffDao.selectCount(new LambdaQueryWrapper<HrPayDiff>()
                .eq(HrPayDiff::getRunId, runId)
                .eq(HrPayDiff::getStatus, HrPayDiff.ST_OPEN)
                .eq(HrPayDiff::getDelFlag, "0")));
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    private void saveDiff(HrPayrollRun run, String empId, String type,
                          BigDecimal expect, BigDecimal actual, String note) {
        HrPayDiff diff = new HrPayDiff();
        diff.setId(uuid());
        diff.setDiffNo(nextDiffNo());
        diff.setRunId(run.getId());
        diff.setPeriod(run.getPeriod());
        diff.setEmpId(empId == null ? "-" : empId);
        diff.setDiffType(type);
        diff.setExpectAmount(expect == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : expect);
        diff.setActualAmount(actual);
        diff.setResolveNote(note);
        diff.setStatus(HrPayDiff.ST_OPEN);
        int days = intOr(sysParamService.getValue("PAY_DIFF_DEADLINE_DAYS"), 5);
        diff.setDeadline(addWorkDays(LocalDate.now(), days));
        diff.setCreateBy(currentUser());
        diff.setCreateDate(LocalDateTime.now());
        diffDao.insert(diff);
    }

    /** 加 N 个工作日（跳周末）。 */
    static LocalDate addWorkDays(LocalDate from, int n) {
        LocalDate d = from;
        int added = 0;
        while (added < n) {
            d = d.plusDays(1);
            if (d.getDayOfWeek().getValue() < 6) {
                added++;
            }
        }
        return d;
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

    private String nextFileNo() {
        String prefix = "DF" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        int max = disbDao.selectList(new LambdaQueryWrapper<HrPayDisbursement>()
                        .likeRight(HrPayDisbursement::getFileNo, prefix)
                        .select(HrPayDisbursement::getFileNo))
                .stream().map(HrPayDisbursement::getFileNo)
                .map(s -> parseSeq(s)).max(Integer::compareTo).orElse(0);
        return prefix + String.format("%04d", max + 1);
    }

    private String nextDiffNo() {
        String prefix = "PD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        int max = diffDao.selectList(new LambdaQueryWrapper<HrPayDiff>()
                        .likeRight(HrPayDiff::getDiffNo, prefix)
                        .select(HrPayDiff::getDiffNo))
                .stream().map(HrPayDiff::getDiffNo)
                .map(s -> parseSeq(s)).max(Integer::compareTo).orElse(0);
        return prefix + String.format("%04d", max + 1);
    }

    private static int parseSeq(String no) {
        try {
            return Integer.parseInt(no.substring(no.lastIndexOf('-') + 1));
        } catch (Exception e) {
            return 0;
        }
    }

    private void audit(String action, String targetType, String targetId, String period, String detail) {
        try {
            PayAuditLog l = new PayAuditLog();
            l.setId(uuid());
            l.setActor(currentUser());
            l.setAction(action);
            l.setTargetType(targetType);
            l.setTargetId(targetId);
            l.setPeriod(period);
            l.setDetail(detail);
            l.setOpAt(LocalDateTime.now());
            l.setCreateBy(currentUser());
            l.setCreateDate(LocalDateTime.now());
            auditDao.insert(l);
        } catch (Exception e) {
            log.warn("pay audit write failed: {}", e.getMessage());
        }
    }

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "数值格式非法：" + o);
        }
    }

    private static BigDecimal decOr(String v, BigDecimal def) {
        try {
            return v == null || v.isBlank() ? def : new BigDecimal(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int intOr(Object o, int def) {
        try {
            return o == null || String.valueOf(o).isBlank() ? def
                    : new BigDecimal(String.valueOf(o).trim()).intValue();
        } catch (Exception e) {
            return def;
        }
    }

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "日期格式非法：" + o);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String currentUser() {
        try {
            return org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
