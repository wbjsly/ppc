package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.qms.SpcAlertDao;
import com.erp.dao.qms.SpcSampleDao;
import com.erp.entity.qms.SpcAlert;
import com.erp.entity.qms.SpcSample;
import com.erp.service.qms.InspectionStandardService;
import com.erp.service.qms.SpcService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SPC 实现（spec spc-monitoring，tasks 10.8）。
 * ≥25 组 X̄±3σ（NORMAL）；不足 25 组按规格折算（USL/LSL 作控制限）标 PRE_CONTROL「预控制」；
 * 三规则：R1 超控制限 / R2 连续 7 点同侧 / R3 连续 7 点单调；同特性同规则 OPEN 幂等。
 */
@Slf4j
@Service
public class SpcServiceImpl implements SpcService {

    /** 三规则 */
    private static final String R1 = "R1";
    private static final String R2 = "R2";
    private static final String R3 = "R3";
    private static final String[] RULE_NAMES = {
            "R1 超控制限", "R2 连续 7 点同侧", "R3 连续 7 点上升/下降"
    };
    /** 预控制模式下的规格折算系数（上下各 0.2T 内为「预控制绿区」概念的限值近似） */
    private static final int MIN_NORMAL_GROUPS = 25;
    private static final int WINDOW = 7;

    private final SpcSampleDao sampleDao;
    private final SpcAlertDao alertDao;
    private final InspectionStandardService standardService;

    public SpcServiceImpl(SpcSampleDao sampleDao, SpcAlertDao alertDao,
                          InspectionStandardService standardService) {
        this.sampleDao = sampleDao;
        this.alertDao = alertDao;
        this.standardService = standardService;
    }

    // ================= 10.8 采样录入 + 控制限 + 告警 =================

    @Override
    @Transactional
    public SpcSample recordSample(Map<String, Object> body) {
        requireRole("SPC 采样录入", "ROLE_INSPECTOR", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        String charCode = str(body.get("charCode"));
        if (!hasText(charCode)) {
            throw new ServiceException(422, "特性编码必填");
        }
        BigDecimal mean;
        try {
            mean = new BigDecimal(String.valueOf(body.get("meanValue")));
        } catch (Exception e) {
            throw new ServiceException(422, "均值必须为数字");
        }
        if (mean == null) {
            throw new ServiceException(422, "均值必填");
        }
        Integer groupNo = body.get("groupNo") == null ? null : Integer.parseInt(String.valueOf(body.get("groupNo")));
        if (groupNo == null) {
            // 默认取该特性最大组号 +1
            SpcSample last = sampleDao.selectOne(new LambdaQueryWrapper<SpcSample>()
                    .eq(SpcSample::getCharCode, charCode)
                    .orderByDesc(SpcSample::getGroupNo)
                    .last("LIMIT 1"));
            groupNo = (last == null || last.getGroupNo() == null ? 0 : last.getGroupNo()) + 1;
        }

        SpcSample s = new SpcSample();
        s.setCharCode(charCode);
        s.setCharName(str(body.get("charName")));
        s.setMaterialCode(str(body.get("materialCode")));
        s.setProcessId(str(body.get("processId")));
        s.setGroupNo(groupNo);
        int sampleQty = body.get("sampleQty") == null ? 5 : Integer.parseInt(String.valueOf(body.get("sampleQty")));
        s.setSampleQty(sampleQty);
        s.setMeanValue(mean);
        s.setMinValue(dec(body.get("minValue")));
        s.setMaxValue(dec(body.get("maxValue")));
        s.setSampleTime(LocalDateTime.now());
        s.setOperatorName(str(body.get("operatorName")));
        s.setRemark(str(body.get("remark")));
        s.setCreateBy(SecurityUtils.getCurrentUserId());

        // ---- 规格：body 优先，缺省按物料现行标准首个计量特性带出 ----
        BigDecimal specLower = dec(body.get("specLower"));
        BigDecimal specUpper = dec(body.get("specUpper"));
        BigDecimal specTarget = dec(body.get("specTarget"));
        if ((specLower == null || specUpper == null) && hasText(s.getMaterialCode())) {
            Map<String, Object> hit = standardService.resolveFor(s.getMaterialCode(), null, null, null, null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chars = (List<Map<String, Object>>) hit.get("characteristics");
            if (chars != null) {
                for (Map<String, Object> c : chars) {
                    if (!"NUMERIC".equals(String.valueOf(c.get("specType")))) {
                        continue;
                    }
                    if (specLower == null && c.get("lowerLimit") != null) {
                        specLower = new BigDecimal(String.valueOf(c.get("lowerLimit")));
                    }
                    if (specUpper == null && c.get("upperLimit") != null) {
                        specUpper = new BigDecimal(String.valueOf(c.get("upperLimit")));
                    }
                    if (specTarget == null && c.get("targetValue") != null) {
                        specTarget = new BigDecimal(String.valueOf(c.get("targetValue")));
                    }
                    break;
                }
            }
        }
        s.setSpecLower(specLower);
        s.setSpecUpper(specUpper);
        s.setSpecTarget(specTarget);

        // ---- 控制限计算（tasks 10.8）----
        long total = sampleDao.selectCount(new LambdaQueryWrapper<SpcSample>()
                .eq(SpcSample::getCharCode, charCode));
        BigDecimal center;
        BigDecimal ucl;
        BigDecimal lcl;
        String mode;
        if (total + 1 >= MIN_NORMAL_GROUPS) {
            // ≥25 组：X̄ ± 3σ（组均值的标准差）
            List<SpcSample> recent = sampleDao.selectList(new LambdaQueryWrapper<SpcSample>()
                    .eq(SpcSample::getCharCode, charCode)
                    .orderByDesc(SpcSample::getGroupNo)
                    .last("LIMIT 100"));
            List<BigDecimal> means = new ArrayList<>();
            for (SpcSample x : recent) {
                if (x.getMeanValue() != null) {
                    means.add(x.getMeanValue());
                }
            }
            means.add(mean);
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal v : means) {
                sum = sum.add(v);
            }
            center = sum.divide(BigDecimal.valueOf(means.size()), 4, RoundingMode.HALF_UP);
            BigDecimal varSum = BigDecimal.ZERO;
            for (BigDecimal v : means) {
                varSum = varSum.add(v.subtract(center).pow(2));
            }
            BigDecimal sigma = means.size() <= 1 ? BigDecimal.ZERO
                    : varSum.divide(BigDecimal.valueOf(means.size()), 6, RoundingMode.HALF_UP)
                            .sqrt(java.math.MathContext.DECIMAL64);
            BigDecimal threeSigma = sigma.multiply(BigDecimal.valueOf(3)).setScale(4, RoundingMode.HALF_UP);
            ucl = center.add(threeSigma);
            lcl = center.subtract(threeSigma);
            mode = "NORMAL";
        } else {
            // 不足 25 组：规格折算 + 标「预控制」
            center = specTarget != null ? specTarget
                    : (specLower != null && specUpper != null
                            ? specLower.add(specUpper).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP)
                            : mean);
            ucl = specUpper != null ? specUpper : mean.multiply(new BigDecimal("1.05"));
            lcl = specLower != null ? specLower : mean.multiply(new BigDecimal("0.95"));
            mode = "PRE_CONTROL";
        }
        s.setLimitMode(mode);
        s.setCenterValue(center);
        s.setUcl(ucl);
        s.setLcl(lcl);
        sampleDao.insert(s);

        // ---- 三规则评估 ----
        evaluateRules(s, charCode, mean, center, ucl, lcl);
        log.info("SPC sample {} #{} mean={} mode={} [{}, {}]", charCode, groupNo, mean, mode, lcl, ucl);
        return s;
    }

    /** 三规则告警生成（同特性同规则 OPEN 幂等） */
    private void evaluateRules(SpcSample s, String charCode, BigDecimal mean,
                               BigDecimal center, BigDecimal ucl, BigDecimal lcl) {
        List<String> hits = new ArrayList<>();
        // R1 超控制限
        if ((ucl != null && mean.compareTo(ucl) > 0) || (lcl != null && mean.compareTo(lcl) < 0)) {
            hits.add(R1);
        }
        // 最近窗口序列（含当前点）
        List<SpcSample> recent = sampleDao.selectList(new LambdaQueryWrapper<SpcSample>()
                .eq(SpcSample::getCharCode, charCode)
                .orderByDesc(SpcSample::getGroupNo)
                .last("LIMIT " + WINDOW));
        if (recent.size() >= WINDOW) {
            boolean allAbove = true;
            boolean allBelow = true;
            boolean ascending = true;
            boolean descending = true;
            for (int i = 0; i < recent.size() - 1; i++) {
                BigDecimal cur = recent.get(i).getMeanValue();
                BigDecimal next = recent.get(i + 1).getMeanValue();
                if (cur == null || next == null) {
                    continue;
                }
                if (center != null) {
                    if (cur.compareTo(center) <= 0) {
                        allAbove = false;
                    }
                    if (cur.compareTo(center) >= 0) {
                        allBelow = false;
                    }
                }
                // recent[0] 最新：升序判定按组号从小到大 → i+1 是更旧的点
                if (next.compareTo(cur) >= 0) {
                    ascending = false; // 新点 < 旧点 → 整体非升
                }
                if (next.compareTo(cur) <= 0) {
                    descending = false;
                }
            }
            boolean lastAbove = center != null
                    && recent.get(0).getMeanValue() != null
                    && recent.get(0).getMeanValue().compareTo(center) > 0;
            boolean lastBelow = center != null
                    && recent.get(0).getMeanValue() != null
                    && recent.get(0).getMeanValue().compareTo(center) < 0;
            if ((allAbove && lastAbove) || (allBelow && lastBelow)) {
                hits.add(R2);
            }
            // 最新点相对 7 点整体：单调性判定（组号升序方向）
            if (isMonotonic(recent)) {
                hits.add(R3);
            }
        }
        for (String rule : hits) {
            openAlert(s, charCode, rule, mean);
        }
    }

    /** recent 按 groupNo 降序：检查按组号升序方向是否单调（全升或全降） */
    private boolean isMonotonic(List<SpcSample> recentDesc) {
        List<SpcSample> asc = new ArrayList<>(recentDesc);
        java.util.Collections.reverse(asc);
        boolean up = true;
        boolean down = true;
        for (int i = 0; i < asc.size() - 1; i++) {
            BigDecimal a = asc.get(i).getMeanValue();
            BigDecimal b = asc.get(i + 1).getMeanValue();
            if (a == null || b == null) {
                return false;
            }
            if (b.compareTo(a) <= 0) {
                up = false;
            }
            if (b.compareTo(a) >= 0) {
                down = false;
            }
        }
        return up || down;
    }

    private void openAlert(SpcSample s, String charCode, String rule, BigDecimal hitValue) {
        long open = alertDao.selectCount(new LambdaQueryWrapper<SpcAlert>()
                .eq(SpcAlert::getCharCode, charCode)
                .eq(SpcAlert::getRuleCode, rule)
                .eq(SpcAlert::getStatus, "OPEN"));
        if (open > 0) {
            return; // 幂等：同特性同规则只开一条
        }
        SpcAlert a = new SpcAlert();
        a.setAlertNo(nextAlertNo());
        a.setCharCode(charCode);
        a.setCharName(s.getCharName());
        a.setRuleCode(rule);
        a.setRuleName(RULE_NAMES[0].equals(rule + " x") ? rule : ruleName(rule));
        a.setHitValue(hitValue);
        a.setDataSnapshot("{\"groupNo\":" + s.getGroupNo() + ",\"mean\":" + hitValue
                + ",\"ucl\":" + s.getUcl() + ",\"lcl\":" + s.getLcl()
                + ",\"center\":" + s.getCenterValue() + ",\"mode\":\"" + s.getLimitMode() + "\"}");
        a.setStatus("OPEN");
        a.setPushTo("ROLE_QUALITY_ENG,ROLE_INSPECTOR");
        a.setOverdueFlag("0");
        a.setCreateBy(SecurityUtils.getCurrentUserId());
        alertDao.insert(a);
        log.warn("SPC alert {} opened: {} on {}", a.getAlertNo(), ruleName(rule), charCode);
    }

    private String ruleName(String rule) {
        for (String n : RULE_NAMES) {
            if (n.startsWith(rule + " ")) {
                return n;
            }
        }
        return rule;
    }

    // ================= 查询与闭环 =================

    @Override
    public List<SpcSample> samples(String charCode, int limit) {
        if (!hasText(charCode)) {
            throw new ServiceException(422, "特性编码必填");
        }
        return sampleDao.selectList(new LambdaQueryWrapper<SpcSample>()
                .eq(SpcSample::getCharCode, charCode)
                .orderByAsc(SpcSample::getGroupNo)
                .last("LIMIT " + (limit <= 0 ? 200 : limit)));
    }

    @Override
    public Page<SpcAlert> alerts(long current, long size, String charCode, String status) {
        return alertDao.selectPage(new Page<>(current, size), new LambdaQueryWrapper<SpcAlert>()
                .eq(hasText(charCode), SpcAlert::getCharCode, charCode)
                .eq(hasText(status), SpcAlert::getStatus, status)
                .orderByDesc(SpcAlert::getCreateDate));
    }

    @Override
    @Transactional
    public SpcAlert handleAlert(String id, String result) {
        requireRole("SPC 告警处置", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR", "ROLE_INSPECTOR");
        SpcAlert a = alertDao.selectById(id);
        if (a == null) {
            throw new ServiceException(404, "告警不存在");
        }
        if (!"OPEN".equals(a.getStatus())) {
            throw new ServiceException(422, "告警已处置：" + a.getStatus());
        }
        if (!hasText(result) || result.trim().length() < 2) {
            throw new ServiceException(422, "处置结论必填（至少 2 字）");
        }
        a.setStatus("HANDLED");
        a.setHandleBy(SecurityUtils.getCurrentUserId());
        a.setHandleTime(LocalDateTime.now());
        a.setHandleResult(result);
        if (alertDao.updateById(a) == 0) {
            throw new ServiceException(409, "告警更新冲突");
        }
        return a;
    }

    @Override
    public List<Map<String, Object>> trends() {
        List<SpcSample> latest = sampleDao.selectList(new LambdaQueryWrapper<SpcSample>()
                .last("LIMIT 500"));
        Map<String, SpcSample> lastByChar = new LinkedHashMap<>();
        Map<String, Integer> openAlerts = new LinkedHashMap<>();
        for (SpcSample s : latest) {
            SpcSample cur = lastByChar.get(s.getCharCode());
            if (cur == null || (s.getGroupNo() != null && cur.getGroupNo() != null
                    && s.getGroupNo() > cur.getGroupNo())) {
                lastByChar.put(s.getCharCode(), s);
            }
        }
        for (SpcAlert a : alertDao.selectList(new LambdaQueryWrapper<SpcAlert>()
                .eq(SpcAlert::getStatus, "OPEN"))) {
            openAlerts.merge(a.getCharCode(), 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        lastByChar.forEach((code, s) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("charCode", code);
            m.put("charName", s.getCharName());
            m.put("materialCode", s.getMaterialCode());
            m.put("groupNo", s.getGroupNo());
            m.put("meanValue", s.getMeanValue());
            m.put("centerValue", s.getCenterValue());
            m.put("ucl", s.getUcl());
            m.put("lcl", s.getLcl());
            m.put("specLower", s.getSpecLower());
            m.put("specUpper", s.getSpecUpper());
            m.put("limitMode", s.getLimitMode());
            m.put("openAlerts", openAlerts.getOrDefault(code, 0));
            out.add(m);
        });
        return out;
    }

    // ================= 内部 =================

    private String nextAlertNo() {
        String prefix = "AL" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = alertDao.selectMaxSeq(prefix);
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

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
