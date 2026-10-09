package com.erp.service.impl.mdm;

import com.erp.common.IntervalRules;
import com.erp.common.ServiceException;
import com.erp.common.TaxCodeRules;
import com.erp.dao.mdm.MdmTaxCodeDao;
import com.erp.dao.mdm.MdmTaxPolicyDao;
import com.erp.entity.mdm.MdmTaxCode;
import com.erp.entity.mdm.MdmTaxPolicy;
import com.erp.service.mdm.MdmTaxCodeService;
import com.erp.service.mdm.MdmTaxWorkbenchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 政策驱动税码批量变更工作台实现（design add-tax-policy-workbench D3/D4）：
 * preview/submit 共用 planRow 核心（submit 复验重算，不复用 preview 返回值）；
 * submit 故意不加 @Transactional —— 每个动作走 taxService 既有 @Transactional（行级/动作级独立提交）。
 */
@Slf4j
@Service
public class MdmTaxWorkbenchServiceImpl implements MdmTaxWorkbenchService {

    private static final int MAX_ROWS = 500;

    private final MdmTaxCodeDao taxDao;
    private final MdmTaxPolicyDao policyDao;
    private final MdmTaxCodeService taxService;

    public MdmTaxWorkbenchServiceImpl(MdmTaxCodeDao taxDao,
                                      MdmTaxPolicyDao policyDao,
                                      MdmTaxCodeService taxService) {
        this.taxDao = taxDao;
        this.policyDao = policyDao;
        this.taxService = taxService;
    }

    // ---------- 候选 ----------

    @Override
    public Map<String, Object> candidates(String policyId, String keyword) {
        requirePolicy(policyId);
        String kw = isNotBlank(keyword) ? keyword.trim().toUpperCase() : null;
        List<MdmTaxCode> all = taxDao.selectList(null);
        LocalDate today = LocalDate.now();
        Map<String, List<MdmTaxCode>> groups = new LinkedHashMap<>();
        for (MdmTaxCode t : all) {
            if (kw != null && !t.getTaxCode().contains(kw)) {
                continue;
            }
            groups.computeIfAbsent(t.getTaxCode(), k -> new ArrayList<>()).add(t);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, List<MdmTaxCode>> en : groups.entrySet()) {
            List<MdmTaxCode> chain = en.getValue();
            chain.sort((a, c) -> a.getEffectiveDate().compareTo(c.getEffectiveDate()));
            MdmTaxCode tail = chain.get(chain.size() - 1);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taxCode", en.getKey());
            item.put("segments", chain.size());
            item.put("tailRate", tail.getTaxRate());
            item.put("tailEffectiveDate", tail.getEffectiveDate());
            item.put("tailExpireDate", tail.getExpireDate());
            item.put("lifecycle", lifecycleOf(tail, today));
            item.put("suggestedSwitchDate", tail.getExpireDate().plusDays(1));
            item.put("currentPolicyNo", tail.getPolicyNo());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidates", items);
        result.put("total", items.size());
        if (items.isEmpty()) {
            result.put("hint", "暂无可选税码（请先在税码维护登记税码记录）");
        }
        return result;
    }

    // ---------- 预检（dry-run） ----------

    @Override
    public Map<String, Object> preview(String policyId, List<Map<String, Object>> rows,
                                       String switchDate, String expireDate) {
        MdmTaxPolicy policy = requirePolicy(policyId);
        List<RowPlan> plans = planAll(rows, switchDate, expireDate, policy);
        List<Map<String, Object>> results = new ArrayList<>();
        for (RowPlan p : plans) {
            results.add(p.toResult());
        }
        long invalid = results.stream().filter(r -> !Boolean.TRUE.equals(r.get("valid"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", results);
        result.put("total", results.size());
        result.put("invalid", invalid);
        return result;
    }

    // ---------- 行级提交 ----------

    @Override
    public Map<String, Object> submit(String policyId, List<Map<String, Object>> rows,
                                      String switchDate, String expireDate) {
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException(422, "提交行不能为空");
        }
        if (rows.size() > MAX_ROWS) {
            throw new ServiceException(422, "单批上限 " + MAX_ROWS + " 行，当前 " + rows.size()
                    + " 行，请拆批执行");
        }
        MdmTaxPolicy policy = requirePolicy(policyId);
        // 复验重算（preview 与 submit 之间链可能变化，design D4）
        List<RowPlan> plans = planAll(rows, switchDate, expireDate, policy);
        List<Map<String, Object>> details = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        for (RowPlan p : plans) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("rowNo", p.rowNo);
            d.put("taxCode", p.taxCode);
            List<Map<String, String>> actions = new ArrayList<>();
            if (p.reason != null) {
                d.put("result", "FAILED");
                d.put("reason", p.reason);
                d.put("actions", actions);
                failed++;
                details.add(d);
                continue;
            }
            boolean shortened = false;
            String err = null;
            for (PlanStep step : p.steps) {
                Map<String, String> a = new LinkedHashMap<>();
                a.put("action", step.action());
                a.put("summary", step.summary());
                try {
                    if ("SHORTEN".equals(step.action())) {
                        // 专用缩短能力（D4）：不跑区间复验，整计划已由预检保证
                        taxService.shortenSegment(step.shortenId(), step.shortenNewExpire(),
                                "政策切换缩短，依据 " + policy.getPolicyNo());
                        shortened = true;
                    } else {
                        taxService.createSegment(step.newSeg());
                    }
                    a.put("result", "OK");
                } catch (ServiceException e) {
                    a.put("result", "FAILED");
                    err = "[" + e.getCode() + "] " + e.getMessage();
                    actions.add(a);
                    break;
                } catch (Exception e) {
                    a.put("result", "FAILED");
                    err = "系统异常：" + e.getMessage();
                    actions.add(a);
                    break;
                }
                actions.add(a);
            }
            d.put("actions", actions);
            if (err == null) {
                d.put("result", "SUCCESS");
                d.put("reason", null);
                succeeded++;
            } else if (shortened) {
                // PARTIAL：缩短已完成（链仍紧凑无断档），新建失败——计划重算可降级为纯 CREATE 重试
                d.put("result", "PARTIAL");
                d.put("reason", "缩短已完成，新建失败：" + err + "（可直接重试本行，计划将自动降级）");
                failed++;
            } else {
                d.put("result", "FAILED");
                d.put("reason", err);
                failed++;
            }
            details.add(d);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", plans.size());
        result.put("succeeded", succeeded);
        result.put("failed", failed);
        result.put("details", details);
        log.info("tax policy workbench done: policy={} total={} ok={} fail={}",
                policy.getPolicyNo(), plans.size(), succeeded, failed);
        return result;
    }

    // ---------- 计划核心（preview/submit 共用） ----------

    /** 动作步：SHORTEN（缩短既有段）/ CREATE（新建段） */
    private record PlanStep(String action, String summary, MdmTaxCode newSeg,
                            String shortenId, LocalDate shortenNewExpire, MdmTaxCode shortenRec) {
    }

    private static class RowPlan {
        int rowNo;
        String taxCode;
        String reason;
        LocalDate appliedExpire;
        List<PlanStep> steps = new ArrayList<>();

        Map<String, Object> toResult() {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rowNo", rowNo);
            r.put("taxCode", taxCode);
            r.put("valid", reason == null);
            r.put("reason", reason);
            List<Map<String, String>> plan = new ArrayList<>();
            for (PlanStep s : steps) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("action", s.action());
                m.put("summary", s.summary());
                plan.add(m);
            }
            r.put("plan", plan);
            r.put("appliedExpire", appliedExpire); // ④类自动失效日回显（spec）
            return r;
        }
    }

    private List<RowPlan> planAll(List<Map<String, Object>> rows, String switchDate,
                                  String expireDate, MdmTaxPolicy policy) {
        List<RowPlan> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(planRow(row, switchDate, expireDate, policy));
        }
        return out;
    }

    /**
     * 五类计划判定（design D3 顺序）：
     * ① 链空 → CREATE 首段
     * ② date == 尾+1 → CREATE 衔接段
     * ③ date ∈ 未失效尾段内部 → SHORTEN 尾段 + CREATE（expire=行内）
     * ④ date ∈ 中间未失效段 → SHORTEN + CREATE（expire 自动=原段失效日，即后续段-1）
     * ⑤ 其他 → 阻断（早于今天 / 已失效历史 / 早于链首 / 断档空洞 / 段生效日当天等）
     */
    private RowPlan planRow(Map<String, Object> row, String switchDateParam,
                            String expireDateParam, MdmTaxPolicy policy) {
        RowPlan p = new RowPlan();
        p.rowNo = intOf(row.get("rowNo"), 0);
        p.taxCode = str(row.get("taxCode")) == null ? null : str(row.get("taxCode")).trim().toUpperCase();

        if (!isNotBlank(p.taxCode)) {
            p.reason = "税码编号必填";
            return p;
        }
        LocalDate switchDate = date(str(row.get("effectiveDate")));
        if (switchDate == null) {
            switchDate = date(switchDateParam);
        }
        LocalDate expire = date(str(row.get("expireDate")));
        if (expire == null) {
            expire = date(expireDateParam);
        }
        if (switchDate == null) {
            p.reason = "切换生效日必填（统一值或行内值）";
            return p;
        }
        LocalDate today = LocalDate.now();
        if (switchDate.isBefore(today)) {
            p.reason = "切换生效日不得早于今天（" + today + "，BR-4.1-18 防追溯改写历史核算）";
            return p;
        }
        Object rateVal = row.get("newRate");
        if (rateVal == null || String.valueOf(rateVal).isBlank()) {
            p.reason = "新税率必填";
            return p;
        }
        BigDecimal newRate;
        try {
            newRate = new BigDecimal(String.valueOf(rateVal).trim())
                    .setScale(TaxCodeRules.RATE_SCALE, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            p.reason = "新税率格式非法";
            return p;
        }

        List<MdmTaxCode> chain = taxDao.selectSequence(p.taxCode, null);
        chain.sort((a, c) -> a.getEffectiveDate().compareTo(c.getEffectiveDate()));

        // ① 链空 → 首段
        if (chain.isEmpty()) {
            if (expire == null) {
                p.reason = "新段失效日必填（该税码尚无区间）";
                return p;
            }
            MdmTaxCode seg = buildSeg(p.taxCode, newRate, switchDate, expire, null, policy);
            String fieldErr = TaxCodeRules.validateFields(seg);
            if (fieldErr != null) {
                p.reason = fieldErr;
                return p;
            }
            p.steps.add(new PlanStep("CREATE", "新建首段 [" + switchDate + ", " + expire + "]", seg,
                    null, null, null));
            p.appliedExpire = expire;
            return p;
        }

        MdmTaxCode tail = chain.get(chain.size() - 1);
        // 覆盖段定位
        MdmTaxCode covering = null;
        for (MdmTaxCode s : chain) {
            if (!switchDate.isBefore(s.getEffectiveDate()) && !switchDate.isAfter(s.getExpireDate())) {
                covering = s;
                break;
            }
        }

        // ⑤ 阻断类 / 补洞续接
        if (covering == null) {
            if (switchDate.isBefore(chain.get(0).getEffectiveDate())) {
                p.reason = "切换日早于该税码链首（" + chain.get(0).getEffectiveDate()
                        + "），首个可切换点为 " + chain.get(0).getEffectiveDate() + " 或链尾+1";
                return p;
            }
            if (switchDate.isAfter(tail.getExpireDate())) {
                if (!switchDate.equals(tail.getExpireDate().plusDays(1))) {
                    p.reason = "切换日与链尾存在断档（BR-4.1-17），须 = 链尾失效日 + 1（"
                            + tail.getExpireDate().plusDays(1) + "）或落在未失效段内部";
                    return p;
                }
                // ② 衔接段
                if (expire == null) {
                    p.reason = "新段失效日必填";
                    return p;
                }
                MdmTaxCode seg = buildSeg(p.taxCode, newRate, switchDate, expire, tail, policy);
                String err = TaxCodeRules.validateFields(seg);
                if (err == null) {
                    err = IntervalRules.checkAdjacent(toSegs(chain, null, null), switchDate, expire);
                }
                if (err != null) {
                    p.reason = err;
                    return p;
                }
                p.steps.add(new PlanStep("CREATE",
                        "新建衔接段 [" + switchDate + ", " + expire + "]（接链尾 " + tail.getExpireDate() + "）",
                        seg, null, null, null));
                p.appliedExpire = expire;
                return p;
            }
            // 空洞起点补洞（PARTIAL 重试路径 / 删除遗留空洞恰从起点切入）：
            // 前段末尾+1 == 切换日 且存在后续段 → 纯 CREATE 补洞，失效日自动=后续段生效日-1
            MdmTaxCode prev = null;
            MdmTaxCode nextAfter = null;
            for (MdmTaxCode seg : chain) {
                if (!seg.getExpireDate().isAfter(switchDate.minusDays(1)) && !seg.getExpireDate().isBefore(switchDate.minusDays(1))) {
                    prev = seg; // prev.expire + 1 == switchDate
                }
                if (seg.getEffectiveDate().isAfter(switchDate) && (nextAfter == null
                        || seg.getEffectiveDate().isBefore(nextAfter.getEffectiveDate()))) {
                    nextAfter = seg;
                }
            }
            if (prev != null && nextAfter != null) {
                LocalDate holeExpire = nextAfter.getEffectiveDate().minusDays(1);
                MdmTaxCode seg = buildSeg(p.taxCode, newRate, switchDate, holeExpire, prev, policy);
                String err = TaxCodeRules.validateFields(seg);
                if (err == null) {
                    err = IntervalRules.checkAdjacent(toSegs(chain, null, null), switchDate, holeExpire);
                }
                if (err != null) {
                    p.reason = err;
                    return p;
                }
                p.steps.add(new PlanStep("CREATE",
                        "补洞新建 [" + switchDate + ", " + holeExpire + "]（失效日自动=后续段生效日-1）",
                        seg, null, null, null));
                p.appliedExpire = holeExpire;
                return p;
            }
            p.reason = "切换日落在既有区间空洞（断档，BR-4.1-17），请改用 尾+1（"
                    + tail.getExpireDate().plusDays(1) + "）或未失效段内部切换";
            return p;
        }

        if (covering.getExpireDate().isBefore(today)) {
            p.reason = "切换日落入已失效历史区间 [" + covering.getEffectiveDate() + ", "
                    + covering.getExpireDate() + "]（BR-4.1-18 不可篡改），建议 尾+1（"
                    + tail.getExpireDate().plusDays(1) + "）或未失效段内部";
            return p;
        }
        boolean isTail = covering.getId().equals(tail.getId());
        if (switchDate.equals(covering.getEffectiveDate())) {
            p.reason = "切换日恰为该段生效日（" + covering.getEffectiveDate()
                    + "），请直接在税码维护变更该段税率，或选择其他切换日";
            return p;
        }
        // switchDate 此时必在 (covering.eff, covering.ex] 内部
        if (expire != null && expire.isBefore(switchDate)) {
            p.reason = "新段失效日不得早于切换日";
            return p;
        }
        LocalDate newExpire;
        String summary;
        if (isTail) {
            // ③ 未失效尾段内部
            if (expire == null) {
                p.reason = "新段失效日必填（尾段切换）";
                return p;
            }
            newExpire = expire;
            summary = "缩短尾段至 " + switchDate.minusDays(1) + " + 新建 [" + switchDate + ", " + newExpire + "]";
        } else {
            // ④ 中间段：新段失效日自动 = 原段失效日（= 后续段生效日-1，链贴合保证）
            newExpire = covering.getExpireDate();
            p.appliedExpire = newExpire;
            summary = "缩短 [" + covering.getEffectiveDate() + ", " + covering.getExpireDate() + "] 至 "
                    + switchDate.minusDays(1) + " + 新建 [" + switchDate + ", " + newExpire
                    + "]（失效日自动=后续段生效日-1）";
        }
        MdmTaxCode newSeg = buildSeg(p.taxCode, newRate, switchDate, newExpire, covering, policy);
        String err = TaxCodeRules.validateFields(newSeg);
        if (err == null) {
            // 对「缩短后链」判定（模拟 covering 收缩至切换日前一天）
            err = IntervalRules.checkAdjacent(toSegs(chain, covering.getId(), switchDate.minusDays(1)),
                    switchDate, newExpire);
        }
        if (err != null) {
            p.reason = err;
            return p;
        }
        p.steps.add(new PlanStep("SHORTEN",
                "缩短段 [" + covering.getEffectiveDate() + ", " + covering.getExpireDate() + "] 至 "
                        + switchDate.minusDays(1), null, covering.getId(),
                switchDate.minusDays(1), covering));
        p.steps.add(new PlanStep("CREATE", summary, newSeg, null, null, null));
        p.appliedExpire = newExpire;
        return p;
    }

    /** 组装新段：scope/calc/rateKind 继承被选段（链存在时链优先），政策文号=所选政策 */
    private MdmTaxCode buildSeg(String taxCode, BigDecimal rate, LocalDate eff, LocalDate exp,
                                MdmTaxCode inheritFrom, MdmTaxPolicy policy) {
        MdmTaxCode t = new MdmTaxCode();
        t.setTaxCode(taxCode);
        t.setTaxRate(rate);
        t.setEffectiveDate(eff);
        t.setExpireDate(exp);
        t.setScope(inheritFrom == null ? "DOMESTIC" : inheritFrom.getScope());
        t.setCalcType(inheritFrom == null ? "GENERAL" : inheritFrom.getCalcType());
        t.setRateKind(inheritFrom == null ? "STANDARD" : inheritFrom.getRateKind());
        t.setPolicyNo(policy.getPolicyNo());
        return t;
    }

    private List<IntervalRules.IntervalSeg> toSegs(List<MdmTaxCode> chain, String shortenId,
                                                   LocalDate shortenTo) {
        List<IntervalRules.IntervalSeg> segs = new ArrayList<>();
        for (MdmTaxCode s : chain) {
            if (shortenId != null && shortenId.equals(s.getId())) {
                segs.add(new IntervalRules.IntervalSeg(s.getEffectiveDate(), shortenTo));
            } else {
                segs.add(new IntervalRules.IntervalSeg(s.getEffectiveDate(), s.getExpireDate()));
            }
        }
        return segs;
    }

    // ---------- 私有 ----------

    private MdmTaxPolicy requirePolicy(String policyId) {
        if (!isNotBlank(policyId)) {
            throw new ServiceException(422, "政策 ID 必填");
        }
        MdmTaxPolicy p = policyDao.selectById(policyId);
        if (p == null) {
            throw new ServiceException(404, "政策记录不存在");
        }
        return p;
    }

    private String lifecycleOf(MdmTaxCode t, LocalDate today) {
        if (t.getEffectiveDate().isAfter(today)) return "NOT_EFFECTIVE";
        if (t.getExpireDate().isBefore(today)) return "EXPIRED";
        return "EFFECTIVE";
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private int intOf(Object o, int def) {
        if (o == null) return def;
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException(422, "日期格式须为 yyyy-MM-dd：" + s);
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
