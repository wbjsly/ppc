package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmRateDao;
import com.erp.entity.pm.PmRate;
import com.erp.service.pm.PmRateService;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 岗位费率（project-initiation）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmRateServiceImpl implements PmRateService {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final BigDecimal DEFAULT_RATE = new BigDecimal("100");

    private final PmRateDao rateDao;

    @Override
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmRate r : rateDao.selectList(new LambdaQueryWrapper<PmRate>()
                .eq(PmRate::getDelFlag, "0")
                .orderByAsc(PmRate::getGrade))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("grade", r.getGrade());
            m.put("gradeName", r.getGradeName());
            m.put("ratePerHour", r.getRatePerHour());
            m.put("status", r.getStatus());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload) {
        String grade = payload.get("grade") == null ? null : String.valueOf(payload.get("grade")).trim();
        if (grade == null || grade.isBlank()) {
            throw new ServiceException(422, "职级必填");
        }
        String rateStr = payload.get("ratePerHour") == null ? null : String.valueOf(payload.get("ratePerHour"));
        if (rateStr == null || rateStr.isBlank()) {
            throw new ServiceException(422, "费率必填");
        }
        BigDecimal rate;
        try {
            rate = new BigDecimal(rateStr.trim());
        } catch (Exception e) {
            throw new ServiceException(422, "费率格式非法");
        }
        if (rate.signum() <= 0) {
            throw new ServiceException(422, "费率须大于 0");
        }
        PmRate row = rateDao.selectOne(new LambdaQueryWrapper<PmRate>()
                .eq(PmRate::getGrade, grade).eq(PmRate::getDelFlag, "0").last("LIMIT 1"));
        boolean isNew = row == null;
        if (isNew) {
            row = new PmRate();
            row.setId(uuid());
            row.setGrade(grade);
            row.setCreateBy(SecurityUtils.getCurrentUserId());
            row.setCreateDate(LocalDateTime.now());
        }
        row.setRatePerHour(rate);
        row.setGradeName(payload.get("gradeName") == null ? null : String.valueOf(payload.get("gradeName")));
        row.setStatus(payload.get("status") == null ? "1" : String.valueOf(payload.get("status")));
        row.setUpdateBy(SecurityUtils.getCurrentUserId());
        row.setUpdateDate(LocalDateTime.now());
        if (isNew) {
            rateDao.insert(row);
        } else {
            rateDao.updateById(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("grade", row.getGrade());
        m.put("ratePerHour", row.getRatePerHour());
        return m;
    }

    @Override
    public BigDecimal rateFor(String grade, String projectRateOverrideJson) {
        // 项目覆盖优先
        if (grade != null && projectRateOverrideJson != null && !projectRateOverrideJson.isBlank()) {
            try {
                JsonNode node = mapper.readTree(projectRateOverrideJson);
                if (node.has(grade) && !node.get(grade).isNull()) {
                    return new BigDecimal(node.get(grade).asText());
                }
            } catch (Exception e) {
                log.warn("rate override json parse failed: {}", e.getMessage());
            }
        }
        // 费率表
        if (grade != null && !grade.isBlank()) {
            PmRate r = rateDao.selectOne(new LambdaQueryWrapper<PmRate>()
                    .eq(PmRate::getGrade, grade)
                    .eq(PmRate::getStatus, "1")
                    .eq(PmRate::getDelFlag, "0")
                    .last("LIMIT 1"));
            if (r != null && r.getRatePerHour() != null) {
                return r.getRatePerHour();
            }
        }
        return DEFAULT_RATE;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
