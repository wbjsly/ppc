package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.entity.pm.PmBudgetLine;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmWbs;
import com.erp.service.pm.PmWbsService;
import com.erp.util.SecurityUtils;
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
import java.util.stream.Collectors;

/** WBS 单表三层树（project-initiation，FR-4.8-2-2；11.3.2）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmWbsServiceImpl implements PmWbsService {

    private final PmWbsDao wbsDao;
    private final PmBudgetLineDao budgetLineDao;
    private final PmProjectDao projectDao;

    @Override
    public List<Map<String, Object>> tree(String projectId) {
        requireProject(projectId);
        List<PmWbs> all = wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getProjectId, projectId)
                .eq(PmWbs::getDelFlag, "0")
                .orderByAsc(PmWbs::getSortNo)
                .orderByAsc(PmWbs::getWbsCode));
        // 递归建树（根 = level1）
        Map<String, List<PmWbs>> byParent = all.stream()
                .filter(w -> w.getParentId() != null)
                .collect(Collectors.groupingBy(PmWbs::getParentId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmWbs w : all) {
            if (w.getParentId() == null) {
                out.add(toNode(w, byParent));
            }
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String projectId = str(payload.get("projectId"));
        requireProject(projectId);
        PmWbs node = new PmWbs();
        node.setId(uuid());
        node.setProjectId(projectId);
        Integer level = intOf(payload.get("level"));
        String parentId = str(payload.get("parentId"));
        applyNode(node, payload, level, parentId, true);
        node.setSortNo(intOf(payload.get("sortNo")) == null ? 0 : intOf(payload.get("sortNo")));
        node.setCreateBy(SecurityUtils.getCurrentUserId());
        node.setCreateDate(LocalDateTime.now());
        wbsDao.insert(node);
        // 任务级级联建空预算行
        if (node.getLevel() != null && node.getLevel() == PmWbs.LV_TASK) {
            ensureBudgetLine(node);
        }
        return toFlat(node);
    }

    @Override
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> payload) {
        PmWbs node = require(id);
        // 层级不可变
        Integer level = intOf(payload.get("level"));
        if (level != null && !level.equals(node.getLevel())) {
            throw new ServiceException(422, "层级不可修改");
        }
        // 父节点不可改（改父等于重组树，本期不支持）
        if (payload.get("parentId") != null
                && !str(payload.get("parentId")).equals(node.getParentId() == null ? "" : node.getParentId())) {
            throw new ServiceException(422, "父节点不可修改");
        }
        applyNode(node, payload, node.getLevel(), node.getParentId(), false);
        node.setUpdateBy(SecurityUtils.getCurrentUserId());
        node.setUpdateDate(LocalDateTime.now());
        wbsDao.updateById(node);
        // 工时变化 → 重算人工成本（预算行联动）
        if (payload.containsKey("estHours") && node.getLevel() != null && node.getLevel() == PmWbs.LV_TASK) {
            PmBudgetLine line = budgetLineDao.selectOne(new LambdaQueryWrapper<PmBudgetLine>()
                    .eq(PmBudgetLine::getWbsId, id).eq(PmBudgetLine::getDelFlag, "0").last("LIMIT 1"));
            if (line != null) {
                line.setUpdateBy(SecurityUtils.getCurrentUserId());
                line.setUpdateDate(LocalDateTime.now());
                budgetLineDao.updateById(line);
            }
        }
        return toFlat(node);
    }

    @Override
    @Transactional
    public void delete(String id) {
        PmWbs node = require(id);
        // 级联删子节点
        List<PmWbs> children = wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getParentId, id).eq(PmWbs::getDelFlag, "0"));
        for (PmWbs c : children) {
            delete(c.getId());
        }
        // @TableLogic 须走 deleteById（setDelFlag+updateById 会被 MP 忽略）
        wbsDao.deleteById(node.getId());
        // 级联删预算行
        PmBudgetLine line = budgetLineDao.selectOne(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getWbsId, id).eq(PmBudgetLine::getDelFlag, "0").last("LIMIT 1"));
        if (line != null) {
            budgetLineDao.deleteById(line.getId());
        }
    }

    @Override
    public List<String> validateForBaseline(String projectId) {
        List<String> errors = new ArrayList<>();
        List<PmWbs> all = wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getProjectId, projectId)
                .eq(PmWbs::getDelFlag, "0"));
        if (all.isEmpty()) {
            errors.add("WBS 为空，不可立项（BR-4.8-10）");
            return errors;
        }
        Map<String, PmWbs> byId = all.stream().collect(Collectors.toMap(PmWbs::getId, w -> w));
        List<PmWbs> phases = all.stream().filter(w -> w.getLevel() != null && w.getLevel() == PmWbs.LV_PHASE).toList();
        if (phases.isEmpty()) {
            errors.add("无阶段节点（level1）");
            return errors;
        }
        for (PmWbs phase : phases) {
            List<PmWbs> packages = all.stream()
                    .filter(w -> phase.getId().equals(w.getParentId()))
                    .toList();
            if (packages.isEmpty()) {
                errors.add("阶段「" + phase.getName() + "」下无工作包，未分解至任务级");
                continue;
            }
            for (PmWbs pkg : packages) {
                List<PmWbs> tasks = all.stream()
                        .filter(w -> pkg.getId().equals(w.getParentId()))
                        .toList();
                if (tasks.isEmpty()) {
                    errors.add("工作包「" + pkg.getName() + "」下无任务，未分解至任务级（BR-4.8-10）");
                }
            }
        }
        // 存在非任务级叶子（level3 禁子，叶子必为任务级；此处防 level 异常数据）
        for (PmWbs w : all) {
            boolean hasChild = all.stream().anyMatch(x -> w.getId().equals(x.getParentId()));
            if (!hasChild && (w.getLevel() == null || w.getLevel() != PmWbs.LV_TASK)) {
                errors.add("节点「" + w.getName() + "」未分解至任务级");
            }
        }
        return errors;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 应用节点字段并做层级规则校验（create/update 共用） */
    private void applyNode(PmWbs node, Map<String, Object> payload, Integer level,
                           String parentId, boolean isCreate) {
        String name = str(payload.get("name"));
        if (!hasText(name)) {
            throw new ServiceException(422, "节点名称必填");
        }
        node.setName(name);
        if (isCreate) {
            if (level == null || level < PmWbs.LV_PHASE || level > PmWbs.LV_TASK) {
                throw new ServiceException(422, "层级必须为 1/2/3");
            }
            node.setLevel(level);
            // 层级规则：level1 无父；level2 父必须 level1；level3 父必须 level2
            if (level == PmWbs.LV_PHASE) {
                if (hasText(parentId)) {
                    throw new ServiceException(422, "阶段节点不可有父节点");
                }
                node.setParentId(null);
                node.setWbsCode(nextPhaseCode(node.getProjectId()));
            } else {
                if (!hasText(parentId)) {
                    throw new ServiceException(422, "工作包/任务必须挂载父节点");
                }
                PmWbs parent = require(parentId);
                if (!node.getProjectId().equals(parent.getProjectId())) {
                    throw new ServiceException(422, "父节点不属于同一项目");
                }
                int parentLevel = parent.getLevel() == null ? 0 : parent.getLevel();
                if (level == PmWbs.LV_PACKAGE && parentLevel != PmWbs.LV_PHASE) {
                    throw new ServiceException(422, "工作包的父节点必须是阶段");
                }
                if (level == PmWbs.LV_TASK && parentLevel != PmWbs.LV_PACKAGE) {
                    throw new ServiceException(422, "任务的父节点必须是工作包");
                }
                if (level == PmWbs.LV_TASK && parentLevel == PmWbs.LV_TASK) {
                    throw new ServiceException(422, "任务级不可再分解");
                }
                node.setParentId(parentId);
                node.setWbsCode(nextChildCode(parent));
            }
        }
        // 工作包交付物必填
        if (node.getLevel() != null && node.getLevel() == PmWbs.LV_PACKAGE) {
            String deliverable = str(payload.get("deliverable"));
            if (!hasText(deliverable)) {
                throw new ServiceException(422, "工作包须填写交付物定义");
            }
            node.setDeliverable(deliverable);
        } else if (payload.containsKey("deliverable")) {
            node.setDeliverable(str(payload.get("deliverable")));
        }
        if (payload.containsKey("ownerUserId")) {
            node.setOwnerUserId(str(payload.get("ownerUserId")));
        }
        if (payload.containsKey("ownerName")) {
            node.setOwnerName(str(payload.get("ownerName")));
        }
        if (payload.containsKey("ownerGrade")) {
            node.setOwnerGrade(str(payload.get("ownerGrade")));
        }
        if (payload.containsKey("planStart") && hasText(str(payload.get("planStart")))) {
            node.setPlanStart(java.time.LocalDate.parse(str(payload.get("planStart"))));
        }
        if (payload.containsKey("planEnd") && hasText(str(payload.get("planEnd")))) {
            node.setPlanEnd(java.time.LocalDate.parse(str(payload.get("planEnd"))));
        }
        if (payload.containsKey("estHours") && hasText(str(payload.get("estHours")))) {
            node.setEstHours(new BigDecimal(str(payload.get("estHours"))));
        }
        if (payload.containsKey("estCost") && hasText(str(payload.get("estCost")))) {
            node.setEstCost(new BigDecimal(str(payload.get("estCost"))));
        }
        if (payload.containsKey("sortNo") && intOf(payload.get("sortNo")) != null) {
            node.setSortNo(intOf(payload.get("sortNo")));
        }
    }

    /** 任务级确保有空预算行（幂等） */
    private void ensureBudgetLine(PmWbs task) {
        Long exists = budgetLineDao.selectCount(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getWbsId, task.getId())
                .eq(PmBudgetLine::getDelFlag, "0"));
        if (exists != null && exists > 0) {
            return;
        }
        PmBudgetLine line = new PmBudgetLine();
        line.setId(uuid());
        line.setProjectId(task.getProjectId());
        line.setWbsId(task.getId());
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

    /** 阶段级编码：项目内 max+1 */
    private String nextPhaseCode(String projectId) {
        long max = wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                        .eq(PmWbs::getProjectId, projectId)
                        .eq(PmWbs::getLevel, PmWbs.LV_PHASE)
                        .eq(PmWbs::getDelFlag, "0")).stream()
                .map(PmWbs::getWbsCode)
                .filter(c -> c != null && c.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.'))
                .mapToInt(c -> {
                    try {
                        return Integer.parseInt(c);
                    } catch (Exception e) {
                        return 0;
                    }
                }).max().orElse(0);
        return String.valueOf(max + 1);
    }

    /** 子级编码：父编码 + . + 父内 max+1 */
    private String nextChildCode(PmWbs parent) {
        long max = wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                        .eq(PmWbs::getParentId, parent.getId())
                        .eq(PmWbs::getDelFlag, "0")).stream()
                .map(PmWbs::getWbsCode)
                .mapToInt(c -> {
                    try {
                        return Integer.parseInt(c.substring(c.lastIndexOf('.') + 1));
                    } catch (Exception e) {
                        return 0;
                    }
                }).max().orElse(0);
        return parent.getWbsCode() + "." + (max + 1);
    }

    private Map<String, Object> toNode(PmWbs w, Map<String, List<PmWbs>> byParent) {
        Map<String, Object> m = toFlat(w);
        List<Map<String, Object>> children = new ArrayList<>();
        for (PmWbs c : byParent.getOrDefault(w.getId(), List.of())) {
            children.add(toNode(c, byParent));
        }
        m.put("children", children);
        return m;
    }

    private Map<String, Object> toFlat(PmWbs w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("projectId", w.getProjectId());
        m.put("wbsCode", w.getWbsCode());
        m.put("parentId", w.getParentId());
        m.put("level", w.getLevel());
        m.put("name", w.getName());
        m.put("ownerUserId", w.getOwnerUserId());
        m.put("ownerName", w.getOwnerName());
        m.put("ownerGrade", w.getOwnerGrade());
        m.put("planStart", w.getPlanStart());
        m.put("planEnd", w.getPlanEnd());
        m.put("estHours", w.getEstHours());
        m.put("estCost", w.getEstCost());
        m.put("deliverable", w.getDeliverable());
        m.put("sortNo", w.getSortNo());
        return m;
    }

    private PmProject requireProject(String projectId) {
        if (!hasText(projectId)) {
            throw new ServiceException(422, "项目 ID 必填");
        }
        PmProject p = projectDao.selectById(projectId);
        if (p == null || "1".equals(p.getDelFlag())) {
            throw new ServiceException(404, "项目不存在");
        }
        // 基线锁定后 WBS 只读
        if ("1".equals(p.getBaselineLocked())) {
            throw new ServiceException(422, "基线已锁定，WBS 不可修改（变更请走预算变更申请）");
        }
        return p;
    }

    private PmWbs require(String id) {
        if (!hasText(id)) {
            throw new ServiceException(422, "节点 ID 必填");
        }
        PmWbs w = wbsDao.selectById(id);
        if (w == null || "1".equals(w.getDelFlag())) {
            throw new ServiceException(404, "节点不存在");
        }
        return w;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer intOf(Object o) {
        if (o == null || !hasText(String.valueOf(o))) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
