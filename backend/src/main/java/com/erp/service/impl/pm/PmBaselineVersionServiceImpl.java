package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmBaselineVersionDao;
import com.erp.entity.pm.PmBaselineVersion;
import com.erp.service.pm.PmBaselineVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 基线版本查询实现（add-project-change，BR-4.8-31 只读）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmBaselineVersionServiceImpl implements PmBaselineVersionService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final PmBaselineVersionDao versionDao;

    @Override
    public List<Map<String, Object>> list(String projectId) {
        if (!hasText(projectId)) {
            throw new ServiceException(422, "项目 ID 必填");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmBaselineVersion v : versionDao.selectList(new LambdaQueryWrapper<PmBaselineVersion>()
                .eq(PmBaselineVersion::getProjectId, projectId)
                .eq(PmBaselineVersion::getDelFlag, "0")
                .orderByAsc(PmBaselineVersion::getMajorVer)
                .orderByAsc(PmBaselineVersion::getMinorVer))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", v.getId());
            row.put("verNoLabel", v.getVerNoLabel());
            row.put("majorVer", v.getMajorVer());
            row.put("minorVer", v.getMinorVer());
            row.put("baselineAmount", v.getBaselineAmount());
            row.put("currentFlag", v.getCurrentFlag());
            row.put("changeId", v.getChangeId());
            row.put("createDate", v.getCreateDate());
            out.add(row);
        }
        return out;
    }

    @Override
    public Map<String, Object> snapshot(String projectId, String verNoLabel) {
        PmBaselineVersion v = requireVersion(projectId, verNoLabel);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verNoLabel", v.getVerNoLabel());
        out.put("baselineAmount", v.getBaselineAmount());
        out.put("currentFlag", v.getCurrentFlag());
        out.put("changeId", v.getChangeId());
        out.put("createDate", v.getCreateDate());
        // 解析全量快照（只读，历史版永久保留，BR-4.8-31）
        List<Map<String, Object>> lines = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(v.getSnapshotJson());
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("wbsId", n.path("wbsId").asText(null));
                    row.put("labor", n.path("labor").decimalValue());
                    row.put("material", n.path("material").decimalValue());
                    row.put("subcontract", n.path("subcontract").decimalValue());
                    row.put("travel", n.path("travel").decimalValue());
                    row.put("other", n.path("other").decimalValue());
                    row.put("indirect", n.path("indirect").decimalValue());
                    lines.add(row);
                }
            }
        } catch (Exception e) {
            log.warn("baseline snapshot parse failed: {} {}", v.getVerNoLabel(), e.getMessage());
        }
        out.put("lines", lines);
        return out;
    }

    @Override
    public Map<String, Object> current(String projectId) {
        PmBaselineVersion v = versionDao.selectOne(new LambdaQueryWrapper<PmBaselineVersion>()
                .eq(PmBaselineVersion::getProjectId, projectId)
                .eq(PmBaselineVersion::getCurrentFlag, "1")
                .eq(PmBaselineVersion::getDelFlag, "0")
                .last("LIMIT 1"));
        if (v == null) {
            throw new ServiceException(404, "项目无当前生效基线版本");
        }
        return snapshot(projectId, v.getVerNoLabel());
    }

    private PmBaselineVersion requireVersion(String projectId, String verNoLabel) {
        if (!hasText(projectId) || !hasText(verNoLabel)) {
            throw new ServiceException(422, "项目 ID 与版本标签必填");
        }
        PmBaselineVersion v = versionDao.selectOne(new LambdaQueryWrapper<PmBaselineVersion>()
                .eq(PmBaselineVersion::getProjectId, projectId)
                .eq(PmBaselineVersion::getVerNoLabel, verNoLabel)
                .eq(PmBaselineVersion::getDelFlag, "0")
                .last("LIMIT 1"));
        if (v == null) {
            throw new ServiceException(404, "基线版本不存在：" + verNoLabel);
        }
        return v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
