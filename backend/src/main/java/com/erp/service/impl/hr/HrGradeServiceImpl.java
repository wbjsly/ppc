package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrDeptDao;
import com.erp.dao.hr.HrGradeDao;
import com.erp.dao.hr.HrPositionDao;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrGrade;
import com.erp.entity.hr.HrPosition;
import com.erp.service.hr.HrGradeService;
import com.erp.service.hr.HrOrgChangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HR 职级（M10-2-4）：带宽挂职级，岗位经 GRADE_ID 间接获得；带宽变更走 GRADE_BAND 调整单审批。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrGradeServiceImpl implements HrGradeService {

    private final HrGradeDao gradeDao;
    private final HrPositionDao positionDao;
    private final HrDeptDao deptDao;
    private final HrOrgChangeService orgChangeService;

    // ================================================================
    // CRUD
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrGrade create(Map<String, Object> payload) {
        String name = str(payload.get("name"));
        require(name, "职级名称");
        String series = str(payload.get("series"));
        if (!HrGrade.SERIES_P.equals(series) && !HrGrade.SERIES_M.equals(series)) {
            throw new ServiceException(422, "序列仅支持 P（管理）/M（技术）");
        }
        BigDecimal min = dec(payload.get("salMin"));
        BigDecimal max = dec(payload.get("salMax"));
        validateBand(min, max);

        HrGrade g = new HrGrade();
        g.setId(uuid());
        g.setGradeNo(nextNo());
        g.setName(name);
        g.setSeries(series);
        g.setSalMin(min);
        g.setSalMax(max);
        g.setStatus(HrGrade.ST_ACTIVE);
        g.setCreateBy(currentUser());
        g.setCreateDate(LocalDateTime.now());
        gradeDao.insert(g);
        log.info("grade {} created: {} band {}-{}", g.getGradeNo(), name, strip(min), strip(max));
        return g;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrGrade update(String gradeId, Map<String, Object> payload) {
        HrGrade g = require(gradeId);
        String name = str(payload.get("name"));
        String series = str(payload.get("series"));
        BigDecimal min = payload.containsKey("salMin") ? dec(payload.get("salMin")) : null;
        BigDecimal max = payload.containsKey("salMax") ? dec(payload.get("salMax")) : null;

        boolean bandChanged = false;
        if (min != null || max != null) {
            BigDecimal effMin = min != null ? min : g.getSalMin();
            BigDecimal effMax = max != null ? max : g.getSalMax();
            validateBand(effMin, effMax);
            bandChanged = (g.getSalMin() == null && effMin != null)
                    || (g.getSalMin() != null && effMin != null && g.getSalMin().compareTo(effMin) != 0)
                    || (g.getSalMax() == null && effMax != null)
                    || (g.getSalMax() != null && effMax != null && g.getSalMax().compareTo(effMax) != 0);
        }

        if (bandChanged) {
            // 薪酬带宽变更 → GRADE_BAND 调整单走审批留痕（C-4.7-06 同口径，D3）
            Map<String, Object> after = new LinkedHashMap<>();
            if (min != null) {
                after.put("salMin", strip(min));
            }
            if (max != null) {
                after.put("salMax", strip(max));
            }
            Map<String, Object> created = orgChangeService.createBandChange(gradeId, after);
            log.info("grade {} band change submitted as {}", g.getGradeNo(), created.get("chgNo"));
            // 非带宽字段直接更新；带宽待审批通过后由回调应用
        }
        if (name != null) {
            g.setName(name);
        }
        if (series != null) {
            if (!HrGrade.SERIES_P.equals(series) && !HrGrade.SERIES_M.equals(series)) {
                throw new ServiceException(422, "序列仅支持 P（管理）/M（技术）");
            }
            g.setSeries(series);
        }
        g.setUpdateBy(currentUser());
        g.setUpdateDate(LocalDateTime.now());
        gradeDao.updateById(g);
        return g;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrGrade toggleStatus(String gradeId, String status) {
        HrGrade g = require(gradeId);
        if (HrGrade.ST_INACTIVE.equals(status)) {
            long used = positionDao.selectCount(new LambdaQueryWrapper<HrPosition>()
                    .eq(HrPosition::getGradeId, gradeId)
                    .eq(HrPosition::getDelFlag, "0"));
            if (used > 0) {
                throw new ServiceException(422, "职级被 " + used + " 个岗位引用，不可停用（先解除关联）");
            }
        }
        g.setStatus(status);
        g.setUpdateBy(currentUser());
        g.setUpdateDate(LocalDateTime.now());
        gradeDao.updateById(g);
        return g;
    }

    @Override
    public List<HrGrade> list() {
        return gradeDao.selectList(new LambdaQueryWrapper<HrGrade>()
                .eq(HrGrade::getDelFlag, "0")
                .orderByAsc(HrGrade::getGradeNo));
    }

    // ================================================================
    // D4 带宽校验链：position→grade→band
    // ================================================================

    @Override
    public Map<String, Object> resolveBand(String posId) {
        Map<String, Object> out = new LinkedHashMap<>();
        HrPosition pos = posId == null ? null : positionDao.selectById(posId);
        if (pos == null) {
            out.put("resolved", false);
            out.put("reason", "岗位不存在");
            return out;
        }
        if (pos.getGradeId() == null || pos.getGradeId().isBlank()) {
            out.put("resolved", false);
            out.put("reason", "岗位未关联职级（带宽跳过）");
            return out;
        }
        HrGrade g = gradeDao.selectById(pos.getGradeId());
        if (g == null) {
            out.put("resolved", false);
            out.put("reason", "关联职级不存在");
            return out;
        }
        out.put("resolved", true);
        out.put("gradeNo", g.getGradeNo());
        out.put("gradeName", g.getName());
        out.put("salMin", g.getSalMin());
        out.put("salMax", g.getSalMax());
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 审批回调应用带宽（GRADE_BAND 通过后调用） */
    @Transactional(rollbackFor = Exception.class)
    public void applyBand(String gradeId, BigDecimal min, BigDecimal max) {
        HrGrade g = gradeDao.selectById(gradeId);
        if (g == null) {
            return;
        }
        if (min != null) {
            g.setSalMin(min);
        }
        if (max != null) {
            g.setSalMax(max);
        }
        g.setUpdateBy("approval");
        g.setUpdateDate(LocalDateTime.now());
        gradeDao.updateById(g);
        log.info("grade {} band applied: {}-{}", g.getGradeNo(), strip(g.getSalMin()), strip(g.getSalMax()));
    }

    private HrGrade require(String gradeId) {
        HrGrade g = gradeDao.selectById(gradeId);
        if (g == null) {
            throw new ServiceException(404, "职级不存在：" + gradeId);
        }
        return g;
    }

    private void validateBand(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return;
        }
        if (min != null && max != null && min.compareTo(max) >= 0) {
            throw new ServiceException(422, "带宽下限须小于上限");
        }
    }

    private String nextNo() {
        List<String> existing = gradeDao.selectNosByPrefix("G%");
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.replaceAll("[^0-9]", "")));
            } catch (Exception ignored) {
                // 非数字跳过
            }
        }
        return String.format("G%03d", max + 1);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
            throw new ServiceException(422, label + "必填");
        }
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
            throw new ServiceException(422, "金额格式非法：" + o);
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
