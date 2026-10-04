package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmOrgUnitDao;
import com.erp.dao.mdm.MdmOrgUnitVersionDao;
import com.erp.entity.mdm.MdmOrgUnit;
import com.erp.entity.mdm.MdmOrgUnitVersion;
import com.erp.service.mdm.MdmOrgUnitService;
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
public class MdmOrgUnitServiceImpl implements MdmOrgUnitService {

    private static final int MAX_DEPTH = 3;

    /** 六类类型码（类型不锁层级，用户 2026-10-01 确认） */
    private static final Map<String, String> TYPE_CODES = Map.of(
            "FACTORY", "FACTORY",
            "WAREHOUSE", "WAREHOUSE",
            "PROC", "PROC",
            "SALE", "SALE",
            "STORE", "STORE",
            "RD", "RD"
    );

    private static final String SNAPSHOT_FIELDS =
            "ouCode,ouName,legalEntityId,parentId,treeLevel,ouType,ownerName,remark,status";

    private final MdmOrgUnitDao unitDao;
    private final MdmOrgUnitVersionDao versionDao;
    private final ObjectMapper objectMapper;

    public MdmOrgUnitServiceImpl(MdmOrgUnitDao unitDao,
                                 MdmOrgUnitVersionDao versionDao,
                                 ObjectMapper objectMapper) {
        this.unitDao = unitDao;
        this.versionDao = versionDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<MdmOrgUnit> tree(String legalEntityId, String ouType, String keyword, String status) {
        LambdaQueryWrapper<MdmOrgUnit> qw = new LambdaQueryWrapper<MdmOrgUnit>()
                .eq(isNotBlank(legalEntityId), MdmOrgUnit::getLegalEntityId, legalEntityId)
                .eq(isNotBlank(ouType), MdmOrgUnit::getOuType, ouType)
                .eq(isNotBlank(status), MdmOrgUnit::getStatus, status)
                .orderByAsc(MdmOrgUnit::getOuCode);
        List<MdmOrgUnit> all = unitDao.selectList(qw);
        List<MdmOrgUnit> filtered = isNotBlank(keyword) ? filterWithAncestors(all, keyword) : all;
        return buildTree(filtered);
    }

    @Override
    public MdmOrgUnit getById(String id) {
        MdmOrgUnit unit = unitDao.selectById(id);
        if (unit == null) {
            throw new ServiceException(404, "组织单元不存在");
        }
        return unit;
    }

    @Override
    @Transactional
    public MdmOrgUnit create(MdmOrgUnit unit) {
        requireFields(unit);
        String typeCode = typeCodeOf(unit.getOuType());
        unit.setId(null);
        unit.setOuCode(null);
        unit.setStatus("1");
        unit.setVerNo(0);
        if (unit.getParentId() == null || unit.getParentId().isEmpty()) {
            unit.setParentId("");
            unit.setTreeLevel(1);
        } else {
            MdmOrgUnit parent = unitDao.selectById(unit.getParentId());
            if (parent == null) {
                throw new ServiceException(422, "父级组织单元不存在");
            }
            if (!parent.getLegalEntityId().equals(unit.getLegalEntityId())) {
                throw new ServiceException(422, "父级组织单元必须与本节点隶属同一法人主体");
            }
            unit.setTreeLevel(parent.getTreeLevel() + 1);
            if (unit.getTreeLevel() > MAX_DEPTH) {
                throw new ServiceException(422, "组织单元层级最多 " + MAX_DEPTH + " 级");
            }
        }
        insertWithGeneratedCode(unit, typeCode);
        saveSnapshot(unit, "CREATE", null);
        return unit;
    }

    @Override
    @Transactional
    public MdmOrgUnit update(MdmOrgUnit unit) {
        MdmOrgUnit stored = unitDao.selectById(unit.getId());
        if (stored == null) {
            throw new ServiceException(404, "组织单元不存在");
        }
        // C-4.1-01 编码创建后不可修改
        if (unit.getOuCode() != null && !unit.getOuCode().equals(stored.getOuCode())) {
            throw new ServiceException(422, "组织单元编码创建后不可修改");
        }
        requireFields(unit);
        // 类型与编码前缀一致（编码不可改 → 变更类型必被此校验拦下，前端编辑态已禁用类型字段）
        String typeCode = typeCodeOf(unit.getOuType());
        if (stored.getOuCode() != null && !stored.getOuCode().startsWith("OU-" + typeCode + "-")) {
            throw new ServiceException(422, "组织单元类型与既有编码前缀不一致，编码创建后不可修改");
        }
        resolveParentAndLevel(unit, stored);
        String diff = buildDiff(stored, unit);
        unit.setOuCode(stored.getOuCode());
        unit.setVerNo(unit.getVerNo() == null ? stored.getVerNo() : unit.getVerNo());
        // 乐观锁：verNo 不匹配时影响行数为 0
        if (unitDao.updateById(unit) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        saveSnapshot(unitDao.selectById(unit.getId()), "UPDATE", diff);
        return unitDao.selectById(unit.getId());
    }

    @Override
    @Transactional
    public void disable(String id) {
        MdmOrgUnit stored = unitDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "组织单元不存在");
        }
        if ("0".equals(stored.getStatus())) {
            throw new ServiceException(422, "该组织单元已处于停用状态");
        }
        // 停用父节点须先停用全部子孙，避免树断链
        List<MdmOrgUnit> activeChildren = new ArrayList<>();
        collectActiveDescendants(id, activeChildren);
        if (!activeChildren.isEmpty()) {
            throw new ServiceException(409, "存在未停用的子孙组织单元，请先停用：" + summarize(activeChildren));
        }
        // C-0-08 下游引用校验桩：交易单据（org_unit_id 四要素）落地时回补
        List<String> references = queryDownstreamReferences(id);
        if (!references.isEmpty()) {
            throw new ServiceException(409, "该组织单元仍被下游业务数据引用，禁止停用：" + String.join("；", references));
        }
        MdmOrgUnit patch = new MdmOrgUnit();
        patch.setId(id);
        patch.setStatus("0");
        patch.setVerNo(stored.getVerNo());
        if (unitDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        saveSnapshot(unitDao.selectById(id), "DISABLE", "status: 1 → 0");
    }

    @Override
    public List<Map<String, String>> options(String legalEntityId) {
        List<MdmOrgUnit> list = unitDao.selectList(
                new LambdaQueryWrapper<MdmOrgUnit>()
                        .eq(MdmOrgUnit::getStatus, "1")
                        .eq(isNotBlank(legalEntityId), MdmOrgUnit::getLegalEntityId, legalEntityId)
                        .orderByAsc(MdmOrgUnit::getOuCode));
        List<Map<String, String>> options = new ArrayList<>();
        for (MdmOrgUnit u : list) {
            Map<String, String> o = new LinkedHashMap<>();
            o.put("id", u.getId());
            o.put("code", u.getOuCode());
            o.put("name", u.getOuName());
            o.put("level", String.valueOf(u.getTreeLevel()));
            o.put("type", u.getOuType());
            options.add(o);
        }
        return options;
    }

    @Override
    public List<MdmOrgUnitVersion> versions(String entityId) {
        return versionDao.selectList(
                new LambdaQueryWrapper<MdmOrgUnitVersion>()
                        .eq(MdmOrgUnitVersion::getEntityId, entityId)
                        .orderByDesc(MdmOrgUnitVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> diff(String entityId, int from, int to) {
        MdmOrgUnitVersion vFrom = findVersion(entityId, from);
        MdmOrgUnitVersion vTo = findVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", versionMeta(vFrom));
        result.put("to", versionMeta(vTo));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    // ---------- private ----------

    private void resolveParentAndLevel(MdmOrgUnit unit, MdmOrgUnit stored) {
        String newParent = unit.getParentId() == null ? stored.getParentId() : unit.getParentId();
        newParent = newParent == null ? "" : newParent;
        if (newParent.isEmpty()) {
            unit.setParentId("");
            unit.setTreeLevel(1);
            return;
        }
        if (newParent.equals(unit.getId())) {
            throw new ServiceException(422, "父级组织单元不能是自身（层级不得成环）");
        }
        // DC-06 成环：回溯新父链，出现自身即成环；顺带计算深度与同主体
        int level = 1;
        String cursor = newParent;
        while (cursor != null && !cursor.isEmpty()) {
            if (cursor.equals(unit.getId())) {
                throw new ServiceException(422, "父级组织单元不能是其子孙节点（层级不得成环）");
            }
            MdmOrgUnit node = unitDao.selectById(cursor);
            if (node == null) {
                throw new ServiceException(422, "父级组织单元不存在");
            }
            if (!node.getLegalEntityId().equals(unit.getLegalEntityId())) {
                throw new ServiceException(422, "父级组织单元必须与本节点隶属同一法人主体");
            }
            level++;
            cursor = node.getParentId();
        }
        if (level > MAX_DEPTH) {
            throw new ServiceException(422, "组织单元层级最多 " + MAX_DEPTH + " 级");
        }
        unit.setParentId(newParent);
        unit.setTreeLevel(level);
    }

    private void insertWithGeneratedCode(MdmOrgUnit unit, String typeCode) {
        // 前缀形如 "OU-FACTORY-"，长度 = 3(OU-) + 类型码 + 1(-)
        int prefixLen = 3 + typeCode.length() + 1;
        String maxCode = unitDao.selectMaxCodeByType(unit.getOuType());
        int seq = 0;
        if (maxCode != null && maxCode.matches("OU-" + typeCode + "-\\d{2}")) {
            seq = Integer.parseInt(maxCode.substring(prefixLen));
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            unit.setOuCode(String.format("OU-%s-%02d", typeCode, seq + 1 + attempt));
            try {
                unitDao.insert(unit);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                String current = unitDao.selectMaxCodeByType(unit.getOuType());
                if (current != null && current.matches("OU-" + typeCode + "-\\d{2}")) {
                    seq = Integer.parseInt(current.substring(prefixLen));
                }
            }
        }
        throw new ServiceException(500, "组织单元编码生成失败，请重试");
    }

    private void collectActiveDescendants(String parentId, List<MdmOrgUnit> out) {
        for (MdmOrgUnit child : unitDao.selectChildren(parentId)) {
            if ("1".equals(child.getStatus())) {
                out.add(child);
            }
            collectActiveDescendants(child.getId(), out);
        }
    }

    private void saveSnapshot(MdmOrgUnit source, String opType, String diffSummary) {
        int next = versionDao.selectCount(
                new LambdaQueryWrapper<MdmOrgUnitVersion>()
                        .eq(MdmOrgUnitVersion::getEntityId, source.getId())).intValue() + 1;
        MdmOrgUnitVersion v = new MdmOrgUnitVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source));
        v.setDiffSummary(diffSummary);
        v.setOpType(opType);
        versionDao.insert(v);
    }

    private MdmOrgUnitVersion findVersion(String entityId, int versionNo) {
        MdmOrgUnitVersion v = versionDao.selectOne(
                new LambdaQueryWrapper<MdmOrgUnitVersion>()
                        .eq(MdmOrgUnitVersion::getEntityId, entityId)
                        .eq(MdmOrgUnitVersion::getVersionNo, versionNo));
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

    private String buildDiff(MdmOrgUnit oldRow, MdmOrgUnit newRow) {
        StringBuilder sb = new StringBuilder();
        appendDiff(sb, "ouName", oldRow.getOuName(), newRow.getOuName());
        appendDiff(sb, "legalEntityId", oldRow.getLegalEntityId(), newRow.getLegalEntityId());
        appendDiff(sb, "parentId", oldRow.getParentId(), newRow.getParentId());
        appendDiff(sb, "treeLevel", str(oldRow.getTreeLevel()), str(newRow.getTreeLevel()));
        appendDiff(sb, "ouType", oldRow.getOuType(), newRow.getOuType());
        appendDiff(sb, "ownerName", oldRow.getOwnerName(), newRow.getOwnerName());
        appendDiff(sb, "remark", oldRow.getRemark(), newRow.getRemark());
        String s = sb.toString();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private void appendDiff(StringBuilder sb, String field, String a, String b) {
        String oldVal = a == null ? "" : a;
        String newVal = b == null ? "" : b;
        if (!Objects.equals(oldVal, newVal)) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(field).append(": ").append(oldVal).append(" → ").append(newVal);
        }
    }

    private List<MdmOrgUnit> filterWithAncestors(List<MdmOrgUnit> all, String keyword) {
        String lower = keyword.toLowerCase();
        Map<String, MdmOrgUnit> byId = new LinkedHashMap<>();
        for (MdmOrgUnit u : all) {
            byId.put(u.getId(), u);
        }
        Map<String, Boolean> keep = new LinkedHashMap<>();
        for (MdmOrgUnit u : all) {
            if (u.getOuCode().toLowerCase().contains(lower) || u.getOuName().toLowerCase().contains(lower)) {
                String cursor = u.getId();
                while (cursor != null && !cursor.isEmpty()) {
                    if (keep.put(cursor, Boolean.TRUE) != null) {
                        break;
                    }
                    MdmOrgUnit node = byId.get(cursor);
                    cursor = node == null ? null : node.getParentId();
                }
            }
        }
        List<MdmOrgUnit> result = new ArrayList<>();
        for (MdmOrgUnit u : all) {
            if (keep.containsKey(u.getId())) {
                result.add(u);
            }
        }
        return result;
    }

    private List<MdmOrgUnit> buildTree(List<MdmOrgUnit> all) {
        Map<String, MdmOrgUnit> byId = new LinkedHashMap<>();
        for (MdmOrgUnit u : all) {
            u.setChildren(null);
            byId.put(u.getId(), u);
        }
        List<MdmOrgUnit> roots = new ArrayList<>();
        for (MdmOrgUnit u : all) {
            MdmOrgUnit parent = u.getParentId() == null ? null : byId.get(u.getParentId());
            if (parent == null) {
                roots.add(u);
            } else {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(u);
            }
        }
        return roots;
    }

    private void requireFields(MdmOrgUnit unit) {
        if (!isNotBlank(unit.getOuName())) {
            throw new ServiceException(422, "组织单元名称不能为空");
        }
        if (!isNotBlank(unit.getLegalEntityId())) {
            throw new ServiceException(422, "所属法人主体不能为空");
        }
        if (!isNotBlank(unit.getOuType()) || !TYPE_CODES.containsKey(unit.getOuType())) {
            throw new ServiceException(422, "类型须为 FACTORY/WAREHOUSE/PROC/SALE/STORE/RD 之一");
        }
    }

    /** TODO(C-0-08 交易单据落地时回补)：查询单据对 org_unit_id 的真实引用 */
    private List<String> queryDownstreamReferences(String id) {
        return new ArrayList<>();
    }

    private String typeCodeOf(String ouType) {
        String code = TYPE_CODES.get(ouType);
        if (code == null) {
            throw new ServiceException(422, "类型须为 FACTORY/WAREHOUSE/PROC/SALE/STORE/RD 之一");
        }
        return code;
    }

    private Map<String, Object> versionMeta(MdmOrgUnitVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionNo", v.getVersionNo());
        m.put("opType", v.getOpType());
        m.put("createBy", v.getCreateBy());
        m.put("createDate", v.getCreateDate());
        return m;
    }

    private String summarize(List<MdmOrgUnit> list) {
        StringBuilder sb = new StringBuilder();
        for (MdmOrgUnit u : list) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(u.getOuCode()).append(" ").append(u.getOuName());
        }
        return sb.length() > 200 ? sb.substring(0, 200) + "…" : sb.toString();
    }

    private String toJson(MdmOrgUnit u) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String field : SNAPSHOT_FIELDS.split(",")) {
            map.put(field, readField(u, field.trim()));
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            throw new ServiceException(500, "版本快照序列化失败");
        }
    }

    private Object readField(MdmOrgUnit u, String field) {
        switch (field) {
            case "ouCode": return u.getOuCode();
            case "ouName": return u.getOuName();
            case "legalEntityId": return u.getLegalEntityId();
            case "parentId": return u.getParentId();
            case "treeLevel": return u.getTreeLevel();
            case "ouType": return u.getOuType();
            case "ownerName": return u.getOwnerName();
            case "remark": return u.getRemark();
            case "status": return u.getStatus();
            default: return null;
        }
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

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
