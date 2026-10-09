package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemCategoryDao;
import com.erp.dao.mdm.MdmItemCategoryVersionDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmItemVersionDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmItemCategory;
import com.erp.entity.mdm.MdmItemCategoryVersion;
import com.erp.entity.mdm.MdmItemVersion;
import com.erp.service.mdm.MdmItemCategoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MdmItemCategoryServiceImpl implements MdmItemCategoryService {

    private static final int MAX_DEPTH = 3;
    /** 合并批量改挂规模上限：超过需 confirmLarge（分批执行留桩，design Decision 1） */
    private static final int MERGE_CONFIRM_THRESHOLD = 5000;
    private static final String[] VALID_PREFIXES = {"FG", "RM", "WIP"};

    private static final String SNAPSHOT_FIELDS =
            "categoryCode,categoryName,itemPrefix,parentId,level,status";

    private final MdmItemCategoryDao categoryDao;
    private final MdmItemCategoryVersionDao versionDao;
    private final MdmItemDao itemDao;
    private final MdmItemVersionDao itemVersionDao;
    private final ObjectMapper objectMapper;

    public MdmItemCategoryServiceImpl(MdmItemCategoryDao categoryDao,
                                      MdmItemCategoryVersionDao versionDao,
                                      MdmItemDao itemDao,
                                      MdmItemVersionDao itemVersionDao,
                                      ObjectMapper objectMapper) {
        this.categoryDao = categoryDao;
        this.versionDao = versionDao;
        this.itemDao = itemDao;
        this.itemVersionDao = itemVersionDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<MdmItemCategory> tree() {
        List<MdmItemCategory> all = categoryDao.selectList(
                new LambdaQueryWrapper<MdmItemCategory>()
                        .orderByAsc(MdmItemCategory::getCategoryCode));
        Map<String, MdmItemCategory> byId = new LinkedHashMap<>();
        for (MdmItemCategory c : all) {
            c.setChildren(null);
            byId.put(c.getId(), c);
        }
        List<MdmItemCategory> roots = new ArrayList<>();
        for (MdmItemCategory c : all) {
            MdmItemCategory parent = c.getParentId() == null || c.getParentId().isEmpty()
                    ? null : byId.get(c.getParentId());
            if (parent == null) {
                roots.add(c);
            } else {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(c);
            }
        }
        return roots;
    }

    @Override
    @Transactional
    public MdmItemCategory create(MdmItemCategory category) {
        requireFields(category);
        String code = category.getCategoryCode();
        if (code == null || !code.matches("\\d{4}")) {
            throw new ServiceException(422, "分类码须为 4 位数字");
        }
        if (categoryDao.selectCount(new LambdaQueryWrapper<MdmItemCategory>()
                .eq(MdmItemCategory::getCategoryCode, code)) > 0) {
            throw new ServiceException(409, "分类码已存在：" + code);
        }
        if (category.getParentId() == null || category.getParentId().isEmpty()) {
            category.setParentId("");
            category.setLevel(1);
        } else {
            MdmItemCategory parent = categoryDao.selectById(category.getParentId());
            if (parent == null) {
                throw new ServiceException(422, "父级分类不存在");
            }
            int level = parent.getLevel() + 1;
            if (level > MAX_DEPTH) {
                throw new ServiceException(422, "分类层级最多 " + MAX_DEPTH + " 级");
            }
            category.setLevel(level);
        }
        category.setId(null);
        category.setStatus("1");
        category.setVerNo(0);
        categoryDao.insert(category);
        saveSnapshot(category, "CREATE", null, null);
        return category;
    }

    @Override
    @Transactional
    public MdmItemCategory update(MdmItemCategory category) {
        MdmItemCategory stored = categoryDao.selectById(category.getId());
        if (stored == null) {
            throw new ServiceException(404, "分类不存在");
        }
        // 编码规则字段锁定（防存量物料前缀校验漂移，BR-4.1-07）
        if (category.getItemPrefix() != null && !category.getItemPrefix().equals(stored.getItemPrefix())) {
            throw new ServiceException(422, "编码前缀创建后不可修改");
        }
        if (category.getCategoryCode() != null && !category.getCategoryCode().equals(stored.getCategoryCode())) {
            throw new ServiceException(422, "分类码创建后不可修改");
        }
        requireReason(category.getChangeReason(), "变更");
        resolveParentAndLevel(category, stored);
        String diff = buildDiff(stored, category);
        if (diff.isEmpty()) {
            throw new ServiceException(422, "无变更内容");
        }
        category.setCategoryCode(stored.getCategoryCode());
        category.setItemPrefix(stored.getItemPrefix());
        category.setVerNo(category.getVerNo() == null ? stored.getVerNo() : category.getVerNo());
        if (categoryDao.updateById(category) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        saveSnapshot(categoryDao.selectById(category.getId()), "UPDATE", diff, category.getChangeReason().trim());
        return categoryDao.selectById(category.getId());
    }

    @Override
    @Transactional
    public void disable(String id) {
        MdmItemCategory stored = categoryDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "分类不存在");
        }
        if ("0".equals(stored.getStatus())) {
            throw new ServiceException(422, "该分类已处于停用状态");
        }
        // 双重阻断 1：启用子孙
        List<MdmItemCategory> activeChildren = new ArrayList<>();
        collectActiveDescendants(id, activeChildren);
        if (!activeChildren.isEmpty()) {
            throw new ServiceException(409, "存在未停用的子孙分类，请先停用：" + summarize(activeChildren));
        }
        // 双重阻断 2：启用物料引用
        List<String> itemCodes = categoryDao.selectActiveItemCodes(stored.getCategoryCode());
        if (!itemCodes.isEmpty()) {
            int total = categoryDao.countActiveItems(stored.getCategoryCode());
            throw new ServiceException(409, "该分类仍被 " + total + " 个启用物料引用，禁止停用（前10条）：" + String.join("、", itemCodes));
        }
        MdmItemCategory patch = new MdmItemCategory();
        patch.setId(id);
        patch.setStatus("0");
        patch.setVerNo(stored.getVerNo());
        if (categoryDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        saveSnapshot(categoryDao.selectById(id), "DISABLE", "status: 1 → 0", null);
    }

    @Override
    public Map<String, Object> mergeImpact(String sourceId, String targetId) {
        MdmItemCategory source = requireCategory(sourceId);
        MdmItemCategory target = requireCategory(targetId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemCount", categoryDao.countAllItems(source.getCategoryCode()));
        m.put("childCount", countAllChildren(sourceId));
        m.put("samePrefix", source.getItemPrefix().equals(target.getItemPrefix()));
        m.put("targetCode", target.getCategoryCode());
        return m;
    }

    @Override
    @Transactional
    public void merge(String sourceId, String targetId, String reason, boolean confirmLarge) {
        if (sourceId.equals(targetId)) {
            throw new ServiceException(422, "源分类与目标分类不能相同");
        }
        requireReason(reason, "合并");
        MdmItemCategory source = requireCategory(sourceId);
        MdmItemCategory target = requireCategory(targetId);
        // 同前缀硬校验（跨前缀合并会破坏存量物料编码与分类码一致性，BR-4.1-07）
        if (!source.getItemPrefix().equals(target.getItemPrefix())) {
            throw new ServiceException(422, "仅允许同一编码前缀的分类合并（源 " + source.getItemPrefix()
                    + " ≠ 目标 " + target.getItemPrefix() + "）");
        }
        int affected = categoryDao.countAllItems(source.getCategoryCode());
        if (affected > MERGE_CONFIRM_THRESHOLD && !confirmLarge) {
            throw new ServiceException(409, "受影响物料 " + affected + " 条超过 " + MERGE_CONFIRM_THRESHOLD
                    + "，请确认后重试（confirmLarge=true）");
        }
        // 1) 子分类改挂目标（校验目标层级可容纳）
        List<MdmItemCategory> children = categoryDao.selectList(
                new LambdaQueryWrapper<MdmItemCategory>().eq(MdmItemCategory::getParentId, sourceId));
        int newChildLevel = target.getLevel() + 1;
        if (!children.isEmpty() && newChildLevel > MAX_DEPTH) {
            throw new ServiceException(422, "目标分类层级下无法容纳源分类的子分类（将超过 " + MAX_DEPTH + " 级）");
        }
        for (MdmItemCategory child : children) {
            MdmItemCategory patch = new MdmItemCategory();
            patch.setId(child.getId());
            patch.setParentId(targetId);
            patch.setLevel(newChildLevel);
            patch.setVerNo(child.getVerNo());
            categoryDao.updateById(patch);
            saveSnapshot(categoryDao.selectById(child.getId()), "MOVE",
                    "parentId: " + sourceId + " → " + targetId, reason);
        }
        // 2) 存量物料批量改挂 + 逐条版本快照（DC-03 固化语义）
        List<MdmItem> items = itemDao.selectList(
                new LambdaQueryWrapper<MdmItem>().eq(MdmItem::getCategoryCode, source.getCategoryCode()));
        for (MdmItem item : items) {
            MdmItem patch = new MdmItem();
            patch.setId(item.getId());
            patch.setCategoryCode(target.getCategoryCode());
            patch.setVerNo(item.getVerNo());
            if (itemDao.updateById(patch) == 0) {
                throw new ServiceException(409, "物料并发变更冲突：" + item.getItemCode() + "，合并已回滚");
            }
            saveItemSnapshot(itemDao.selectById(item.getId()), "categoryCode: " + source.getCategoryCode()
                    + " → " + target.getCategoryCode() + "（分类合并）");
        }
        // 3) 源分类停用（软）
        MdmItemCategory srcPatch = new MdmItemCategory();
        srcPatch.setId(sourceId);
        srcPatch.setStatus("0");
        srcPatch.setVerNo(source.getVerNo());
        if (categoryDao.updateById(srcPatch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        // 4) 分类版本记录（源 MERGE、目标 MERGE）
        saveSnapshot(categoryDao.selectById(sourceId), "MERGE",
                "mergedTo: " + target.getCategoryCode() + "（影响物料 " + affected + " 条）", reason);
        saveSnapshot(categoryDao.selectById(targetId), "MERGE",
                "mergedFrom: " + source.getCategoryCode() + "（影响物料 " + affected + " 条）", reason);
    }

    @Override
    @Transactional
    public void move(String id, String targetParentId, String reason) {
        MdmItemCategory stored = categoryDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "分类不存在");
        }
        requireReason(reason, "迁移");
        MdmItemCategory probe = new MdmItemCategory();
        probe.setId(id);
        probe.setParentId(targetParentId == null ? "" : targetParentId);
        resolveParentAndLevel(probe, stored);
        String diff = "parentId: " + stored.getParentId() + " → " + probe.getParentId()
                + "; level: " + stored.getLevel() + " → " + probe.getLevel();
        MdmItemCategory patch = new MdmItemCategory();
        patch.setId(id);
        patch.setParentId(probe.getParentId());
        patch.setLevel(probe.getLevel());
        patch.setVerNo(stored.getVerNo());
        if (categoryDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        saveSnapshot(categoryDao.selectById(id), "MOVE", diff, reason);
        // 物料与编码不受影响（迁移仅动节点位置）
    }

    @Override
    public List<MdmItemCategoryVersion> versions(String entityId) {
        return versionDao.selectList(
                new LambdaQueryWrapper<MdmItemCategoryVersion>()
                        .eq(MdmItemCategoryVersion::getEntityId, entityId)
                        .orderByDesc(MdmItemCategoryVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> diff(String entityId, int from, int to) {
        MdmItemCategoryVersion vFrom = findVersion(entityId, from);
        MdmItemCategoryVersion vTo = findVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", versionMeta(vFrom));
        result.put("to", versionMeta(vTo));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    // ---------- private ----------

    private MdmItemCategory requireCategory(String id) {
        MdmItemCategory c = categoryDao.selectById(id);
        if (c == null) {
            throw new ServiceException(404, "分类不存在");
        }
        return c;
    }

    /** 变更/合并/迁移原因必填（≥2字） */
    private void requireReason(String reason, String op) {
        if (reason == null || reason.trim().length() < 2) {
            throw new ServiceException(422, op + "原因必填（至少 2 个字）");
        }
    }

    /** 成环 + 深度校验（DC-06），复用组织单元模式 */
    private void resolveParentAndLevel(MdmItemCategory category, MdmItemCategory stored) {
        String newParent = category.getParentId() == null ? stored.getParentId() : category.getParentId();
        newParent = newParent == null ? "" : newParent;
        if (newParent.isEmpty()) {
            category.setParentId("");
            category.setLevel(1);
            return;
        }
        if (newParent.equals(category.getId())) {
            throw new ServiceException(422, "父级分类不能是自身（层级不得成环）");
        }
        int level = 1;
        String cursor = newParent;
        while (cursor != null && !cursor.isEmpty()) {
            if (cursor.equals(category.getId())) {
                throw new ServiceException(422, "父级分类不能是其子孙节点（层级不得成环）");
            }
            MdmItemCategory node = categoryDao.selectById(cursor);
            if (node == null) {
                throw new ServiceException(422, "父级分类不存在");
            }
            level++;
            cursor = node.getParentId();
        }
        if (level > MAX_DEPTH) {
            throw new ServiceException(422, "分类层级最多 " + MAX_DEPTH + " 级");
        }
        category.setParentId(newParent);
        category.setLevel(level);
    }

    private void collectActiveDescendants(String parentId, List<MdmItemCategory> out) {
        for (MdmItemCategory child : categoryDao.selectList(
                new LambdaQueryWrapper<MdmItemCategory>().eq(MdmItemCategory::getParentId, parentId))) {
            if ("1".equals(child.getStatus())) {
                out.add(child);
            }
            collectActiveDescendants(child.getId(), out);
        }
    }

    private int countAllChildren(String parentId) {
        int n = 0;
        for (MdmItemCategory child : categoryDao.selectList(
                new LambdaQueryWrapper<MdmItemCategory>().eq(MdmItemCategory::getParentId, parentId))) {
            n += 1 + countAllChildren(child.getId());
        }
        return n;
    }

    private void saveSnapshot(MdmItemCategory source, String opType, String diffSummary, String reason) {
        int next = versionDao.selectCount(
                new LambdaQueryWrapper<MdmItemCategoryVersion>()
                        .eq(MdmItemCategoryVersion::getEntityId, source.getId())).intValue() + 1;
        MdmItemCategoryVersion v = new MdmItemCategoryVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source));
        v.setDiffSummary(diffSummary);
        v.setOpType(opType);
        v.setChangeReason(reason);
        versionDao.insert(v);
    }

    /** 分类合并对存量物料写版本快照（改挂后状态 + 差异摘记） */
    private void saveItemSnapshot(MdmItem after, String diffSummary) {
        int next = itemVersionDao.selectCount(
                new LambdaQueryWrapper<MdmItemVersion>()
                        .eq(MdmItemVersion::getEntityId, after.getId())).intValue() + 1;
        MdmItemVersion v = new MdmItemVersion();
        v.setEntityId(after.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(MdmItemServiceImpl.snapshotJson(after, objectMapper));
        v.setDiffSummary(diffSummary);
        v.setOpType("UPDATE");
        itemVersionDao.insert(v);
    }

    private MdmItemCategoryVersion findVersion(String entityId, int versionNo) {
        MdmItemCategoryVersion v = versionDao.selectOne(
                new LambdaQueryWrapper<MdmItemCategoryVersion>()
                        .eq(MdmItemCategoryVersion::getEntityId, entityId)
                        .eq(MdmItemCategoryVersion::getVersionNo, versionNo));
        if (v == null) {
            throw new ServiceException(404, "版本不存在：" + entityId + " V" + versionNo);
        }
        return v;
    }

    private List<Map<String, String>> compareSnapshots(String fromJson, String toJson) {
        JsonNode from = readTree(fromJson);
        JsonNode to = readTree(toJson);
        List<Map<String, String>> fields = new ArrayList<>();
        for (String field : SNAPSHOT_FIELDS.split(",")) {
            String a = from.has(field) && !from.get(field).isNull() ? from.get(field).asText() : "";
            String b = to.has(field) && !to.get(field).isNull() ? to.get(field).asText() : "";
            if (!Objects.equals(a, b)) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("field", field);
                row.put("from", a);
                row.put("to", b);
                fields.add(row);
            }
        }
        return fields;
    }

    private String buildDiff(MdmItemCategory oldRow, MdmItemCategory newRow) {
        StringBuilder sb = new StringBuilder();
        // null = 调用方未提交该字段（无变更意图），跳过，避免 status:null 之类污染 diff 导致「无差异」拦截失效
        appendDiff(sb, "categoryName", oldRow.getCategoryName(), newRow.getCategoryName());
        appendDiff(sb, "parentId", oldRow.getParentId(), newRow.getParentId());
        appendDiff(sb, "level", str(oldRow.getLevel()), str(newRow.getLevel()));
        appendDiff(sb, "status", oldRow.getStatus(), newRow.getStatus());
        String s = sb.toString();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private void appendDiff(StringBuilder sb, String field, String a, String b) {
        if (b == null) {
            return;
        }
        String oldVal = a == null ? "" : a;
        if (!Objects.equals(oldVal, b)) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(field).append(": ").append(oldVal).append(" → ").append(b);
        }
    }

    private void requireFields(MdmItemCategory category) {
        if (category.getCategoryName() == null || category.getCategoryName().trim().isEmpty()) {
            throw new ServiceException(422, "分类名称不能为空");
        }
        if (category.getItemPrefix() == null || category.getItemPrefix().trim().isEmpty()) {
            throw new ServiceException(422, "编码前缀不能为空");
        }
        String prefix = category.getItemPrefix().trim();
        for (String p : VALID_PREFIXES) {
            if (p.equals(prefix)) {
                return;
            }
        }
        throw new ServiceException(422, "编码前缀须为 FG/RM/WIP 之一");
    }

    private String toJson(MdmItemCategory c) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String field : SNAPSHOT_FIELDS.split(",")) {
            map.put(field.trim(), readField(c, field.trim()));
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            throw new ServiceException(500, "版本快照序列化失败");
        }
    }

    private Object readField(MdmItemCategory c, String field) {
        switch (field) {
            case "categoryCode": return c.getCategoryCode();
            case "categoryName": return c.getCategoryName();
            case "itemPrefix": return c.getItemPrefix();
            case "parentId": return c.getParentId();
            case "level": return c.getLevel();
            case "status": return c.getStatus();
            default: return null;
        }
    }

    private Map<String, Object> versionMeta(MdmItemCategoryVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionNo", v.getVersionNo());
        m.put("opType", v.getOpType());
        m.put("changeReason", v.getChangeReason());
        m.put("createBy", v.getCreateBy());
        m.put("createDate", v.getCreateDate());
        return m;
    }

    private String summarize(List<MdmItemCategory> list) {
        StringBuilder sb = new StringBuilder();
        for (MdmItemCategory c : list) {
            if (sb.length() > 0) sb.append("、");
            sb.append(c.getCategoryCode()).append(" ").append(c.getCategoryName());
        }
        return sb.length() > 200 ? sb.substring(0, 200) + "…" : sb.toString();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
