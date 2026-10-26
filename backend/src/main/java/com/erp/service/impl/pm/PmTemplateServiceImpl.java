package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.dao.pm.PmWbsTemplateDao;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmWbs;
import com.erp.entity.pm.PmWbsTemplate;
import com.erp.service.pm.PmTemplateService;
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

/** WBS 模板（project-initiation，BR-4.8-13）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmTemplateServiceImpl implements PmTemplateService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final PmWbsTemplateDao templateDao;
    private final PmWbsDao wbsDao;
    private final PmProjectDao projectDao;
    private final PmBudgetLineDao budgetLineDao;

    @Override
    public List<Map<String, Object>> list(String status) {
        LambdaQueryWrapper<PmWbsTemplate> qw = new LambdaQueryWrapper<PmWbsTemplate>()
                .eq(PmWbsTemplate::getDelFlag, "0")
                .orderByAsc(PmWbsTemplate::getTemplateNo);
        if (status != null && !status.isBlank()) {
            qw.eq(PmWbsTemplate::getStatus, status.trim());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmWbsTemplate t : templateDao.selectList(qw)) {
            out.add(toRow(t, false));
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        PmWbsTemplate t = new PmWbsTemplate();
        t.setId(uuid());
        applyPayload(t, payload);
        t.setCreateBy(SecurityUtils.getCurrentUserId());
        t.setCreateDate(LocalDateTime.now());
        templateDao.insert(t);
        return toRow(t, true);
    }

    @Override
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> payload) {
        PmWbsTemplate t = require(id);
        applyPayload(t, payload);
        t.setUpdateBy(SecurityUtils.getCurrentUserId());
        t.setUpdateDate(LocalDateTime.now());
        templateDao.updateById(t);
        return toRow(t, true);
    }

    @Override
    @Transactional
    public void toggle(String id, String status) {
        PmWbsTemplate t = require(id);
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException(422, "状态取值非法");
        }
        t.setStatus(status);
        t.setUpdateBy(SecurityUtils.getCurrentUserId());
        t.setUpdateDate(LocalDateTime.now());
        templateDao.updateById(t);
    }

    @Override
    @Transactional
    public void delete(String id) {
        PmWbsTemplate t = require(id);
        t.setDelFlag("1");
        t.setUpdateBy(SecurityUtils.getCurrentUserId());
        t.setUpdateDate(LocalDateTime.now());
        templateDao.updateById(t);
    }

    @Override
    @Transactional
    public Map<String, Object> generate(String projectId, String templateId, boolean confirm) {
        PmProject project = projectDao.selectById(projectId);
        if (project == null || "1".equals(project.getDelFlag())) {
            throw new ServiceException(404, "项目不存在");
        }
        if ("1".equals(project.getBaselineLocked())) {
            throw new ServiceException(422, "基线已锁定，不可重新生成 WBS");
        }
        PmWbsTemplate tpl = templateDao.selectById(templateId);
        if (tpl == null || "1".equals(tpl.getDelFlag())) {
            throw new ServiceException(404, "模板不存在");
        }
        if (!"1".equals(tpl.getStatus())) {
            throw new ServiceException(422, "模板已停用，不可选用");
        }
        // 已有 WBS → 须二次确认
        long existing = wbsDao.selectCount(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getProjectId, projectId)
                .eq(PmWbs::getDelFlag, "0"));
        if (existing > 0 && !confirm) {
            throw new ServiceException(422, "项目已有 WBS，覆盖生成须二次确认（confirm=true）");
        }
        if (existing > 0) {
            // 整体覆盖：旧节点与预算行逻辑删
            // @TableLogic 须走 deleteById（setDelFlag+updateById 会被 MP 忽略）
            for (PmWbs old : wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                    .eq(PmWbs::getProjectId, projectId).eq(PmWbs::getDelFlag, "0"))) {
                wbsDao.deleteById(old.getId());
            }
            for (com.erp.entity.pm.PmBudgetLine old : budgetLineDao.selectList(
                    new LambdaQueryWrapper<com.erp.entity.pm.PmBudgetLine>()
                            .eq(com.erp.entity.pm.PmBudgetLine::getProjectId, projectId)
                            .eq(com.erp.entity.pm.PmBudgetLine::getDelFlag, "0"))) {
                budgetLineDao.deleteById(old.getId());
            }
        }
        // 深拷贝模板层级
        int created = copyTemplate(tpl.getTemplateJson(), projectId, null, "0");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("templateId", templateId);
        out.put("created", created);
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 递归复制模板节点，返回创建数 */
    private int copyTemplate(String json, String projectId, String parentId, String parentCode) {
        JsonNode arr;
        try {
            arr = mapper.readTree(json);
        } catch (Exception e) {
            throw new ServiceException(422, "模板 JSON 解析失败：" + e.getMessage());
        }
        if (!arr.isArray()) {
            throw new ServiceException(422, "模板 JSON 须为数组");
        }
        int count = 0;
        for (int i = 0; i < arr.size(); i++) {
            JsonNode n = arr.get(i);
            PmWbs node = new PmWbs();
            node.setId(uuid());
            node.setProjectId(projectId);
            node.setParentId(parentId);
            node.setLevel(n.has("l") ? n.get("l").asInt() : 1);
            node.setName(n.has("n") ? n.get("n").asText() : "未命名");
            node.setWbsCode(parentCode == null || parentCode.isEmpty()
                    ? String.valueOf(i + 1)
                    : parentCode + "." + (i + 1));
            if (n.has("d") && !n.get("d").isNull()) {
                node.setDeliverable(n.get("d").asText());
            }
            if (n.has("h") && !n.get("h").isNull()) {
                node.setEstHours(BigDecimal.valueOf(n.get("h").asDouble()));
            }
            node.setSortNo(i + 1);
            node.setCreateBy(SecurityUtils.getCurrentUserId());
            node.setCreateDate(LocalDateTime.now());
            wbsDao.insert(node);
            count++;
            if (n.has("c") && n.get("c").isArray()) {
                count += copyTemplate(n.get("c").toString(), projectId, node.getId(), node.getWbsCode());
            }
            // 任务级建空预算行
            if (node.getLevel() != null && node.getLevel() == PmWbs.LV_TASK) {
                com.erp.entity.pm.PmBudgetLine line = new com.erp.entity.pm.PmBudgetLine();
                line.setId(uuid());
                line.setProjectId(projectId);
                line.setWbsId(node.getId());
                line.setLabor(BigDecimal.ZERO);
                line.setMaterial(BigDecimal.ZERO);
                line.setSubcontract(BigDecimal.ZERO);
                line.setTravel(BigDecimal.ZERO);
                line.setOther(BigDecimal.ZERO);
                line.setIndirect(BigDecimal.ZERO);
                line.setCreateBy(SecurityUtils.getCurrentUserId());
                line.setCreateDate(LocalDateTime.now());
                budgetLineDao.insert(line);
            }
        }
        return count;
    }

    private void applyPayload(PmWbsTemplate t, Map<String, Object> payload) {
        String name = str(payload.get("name"));
        if (!hasText(name)) {
            throw new ServiceException(422, "模板名称必填");
        }
        t.setName(name);
        String projType = str(payload.get("projType"));
        if (!hasText(projType)) {
            throw new ServiceException(422, "适用项目类型必填");
        }
        t.setProjType(projType);
        String json = str(payload.get("templateJson"));
        if (!hasText(json)) {
            throw new ServiceException(422, "模板层级 JSON 必填");
        }
        try {
            JsonNode arr = mapper.readTree(json);
            if (!arr.isArray() || arr.isEmpty()) {
                throw new ServiceException(422, "模板 JSON 须为非空数组");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(422, "模板 JSON 格式非法");
        }
        t.setTemplateJson(json);
        if (payload.get("status") != null) {
            t.setStatus(str(payload.get("status")));
        } else if (t.getStatus() == null) {
            t.setStatus("1");
        }
        if (t.getTemplateNo() == null) {
            t.setTemplateNo("TPL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        }
    }

    private Map<String, Object> toRow(PmWbsTemplate t, boolean withJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("templateNo", t.getTemplateNo());
        m.put("name", t.getName());
        m.put("projType", t.getProjType());
        m.put("status", t.getStatus());
        if (withJson) {
            m.put("templateJson", t.getTemplateJson());
        }
        return m;
    }

    private PmWbsTemplate require(String id) {
        if (!hasText(id)) {
            throw new ServiceException(422, "模板 ID 必填");
        }
        PmWbsTemplate t = templateDao.selectById(id);
        if (t == null || "1".equals(t.getDelFlag())) {
            throw new ServiceException(404, "模板不存在");
        }
        return t;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
