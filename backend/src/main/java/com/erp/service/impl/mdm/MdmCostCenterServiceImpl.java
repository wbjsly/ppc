package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmCostCenterDao;
import com.erp.dao.mdm.MdmCostCenterVersionDao;
import com.erp.dao.mdm.MdmProfitCenterDao;
import com.erp.entity.mdm.MdmCostCenter;
import com.erp.entity.mdm.MdmCostCenterVersion;
import com.erp.entity.mdm.MdmProfitCenter;
import com.erp.service.mdm.MdmCostCenterService;
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
public class MdmCostCenterServiceImpl implements MdmCostCenterService {

    private static final int MAX_DEPTH = 3;

    private static final Map<String, String> TYPE_CODES = Map.of(
            "PROD", "PROD",
            "SALES", "SALES",
            "ADMIN", "ADMIN",
            "RD", "RD"
    );

    private static final String SNAPSHOT_FIELDS =
            "ccCode,ccName,legalEntityId,profitCenterId,parentId,treeLevel,costType,isDefault,ownerName,remark,status";

    private final MdmCostCenterDao centerDao;
    private final MdmCostCenterVersionDao versionDao;
    private final MdmProfitCenterDao profitCenterDao;
    private final ObjectMapper objectMapper;

    public MdmCostCenterServiceImpl(MdmCostCenterDao centerDao,
                                    MdmCostCenterVersionDao versionDao,
                                    MdmProfitCenterDao profitCenterDao,
                                    ObjectMapper objectMapper) {
        this.centerDao = centerDao;
        this.versionDao = versionDao;
        this.profitCenterDao = profitCenterDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<MdmCostCenter> tree(String legalEntityId, String keyword, String status) {
        LambdaQueryWrapper<MdmCostCenter> qw = new LambdaQueryWrapper<MdmCostCenter>()
                .eq(isNotBlank(legalEntityId), MdmCostCenter::getLegalEntityId, legalEntityId)
                .eq(isNotBlank(status), MdmCostCenter::getStatus, status)
                .orderByAsc(MdmCostCenter::getCcCode);
        List<MdmCostCenter> all = centerDao.selectList(qw);
        List<MdmCostCenter> filtered = isNotBlank(keyword)
                ? filterWithAncestors(all, keyword)
                : all;
        return buildTree(filtered);
    }

    @Override
    @Transactional
    public MdmCostCenter create(MdmCostCenter center) {
        requireFields(center);
        requireProfitCenterInSameEntity(center.getProfitCenterId(), center.getLegalEntityId());
        String typeCode = typeCodeOf(center.getCostType());
        checkDuplicateCode(center.getLegalEntityId(), typeCode, "");
        center.setId(null);
        center.setCcCode(null);
        center.setStatus("1");
        center.setVerNo(0);
        if (center.getParentId() == null || center.getParentId().isEmpty()) {
            center.setParentId("");
            center.setTreeLevel(1);
        } else {
            MdmCostCenter parent = centerDao.selectById(center.getParentId());
            if (parent == null) {
                throw new ServiceException(422, "父级成本中心不存在");
            }
            if (!parent.getLegalEntityId().equals(center.getLegalEntityId())) {
                throw new ServiceException(422, "父级成本中心必须与本节点隶属同一法人主体");
            }
            center.setTreeLevel(parent.getTreeLevel() + 1);
            if (center.getTreeLevel() > MAX_DEPTH) {
                throw new ServiceException(422, "成本中心层级最多 " + MAX_DEPTH + " 级");
            }
        }
        center.setIsDefault(center.getIsDefault() == null || !"1".equals(center.getIsDefault()) ? "0" : "1");
        insertWithGeneratedCode(center, typeCode);
        if ("1".equals(center.getIsDefault())) {
            applyDefault(center);
        }
        saveSnapshot(center, "CREATE", null);
        return center;
    }

    @Override
    @Transactional
    public MdmCostCenter update(MdmCostCenter center) {
        MdmCostCenter stored = centerDao.selectById(center.getId());
        if (stored == null) {
            throw new ServiceException(404, "成本中心不存在");
        }
        // C-4.1-01 编码创建后不可修改
        if (center.getCcCode() != null && !center.getCcCode().equals(stored.getCcCode())) {
            throw new ServiceException(422, "成本中心编码创建后不可修改");
        }
        requireFields(center);
        checkDuplicateCode(center.getLegalEntityId(), typeCodeOf(center.getCostType()), center.getId());
        resolveParentAndLevel(center, stored);
        // 所属主体变更时，原利润中心若不属新主体 → 自动清空归属（design Decision 3）
        boolean entityChanged = !Objects.equals(center.getLegalEntityId(), stored.getLegalEntityId());
        if (entityChanged && isNotBlank(center.getProfitCenterId())) {
            MdmProfitCenter pc = profitCenterDao.selectById(center.getProfitCenterId());
            if (pc == null || !pc.getLegalEntityId().equals(center.getLegalEntityId())) {
                center.setProfitCenterId(null);
            }
        }
        requireProfitCenterInSameEntity(center.getProfitCenterId(), center.getLegalEntityId());
        center.setCcCode(stored.getCcCode());
        center.setVerNo(center.getVerNo() == null ? stored.getVerNo() : center.getVerNo());
        String diff = buildDiff(stored, center);
        // 同主体唯一默认（FR-4.6-5-4）
        boolean wantDefault = "1".equals(center.getIsDefault());
        center.setIsDefault(wantDefault ? "1" : "0");
        // MyBatis-Plus updateById 忽略 null 字段，显式清空归属须用 UpdateWrapper
        boolean clearProfitCenter = center.getProfitCenterId() == null
                && isNotBlank(stored.getProfitCenterId());
        if (clearProfitCenter) {
            center.setProfitCenterId(stored.getProfitCenterId());
        }
        if (centerDao.updateById(center) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        if (clearProfitCenter) {
            centerDao.update(null, new LambdaUpdateWrapper<MdmCostCenter>()
                    .eq(MdmCostCenter::getId, center.getId())
                    .set(MdmCostCenter::getProfitCenterId, null));
        }
        if (wantDefault) {
            applyDefault(center);
        } else if ("1".equals(stored.getIsDefault())) {
            clearDefault(center.getLegalEntityId());
        }
        saveSnapshot(centerDao.selectById(center.getId()), "UPDATE", diff);
        return centerDao.selectById(center.getId());
    }

    @Override
    @Transactional
    public void disable(String id) {
        MdmCostCenter stored = centerDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "成本中心不存在");
        }
        if ("0".equals(stored.getStatus())) {
            throw new ServiceException(422, "该成本中心已处于停用状态");
        }
        // 停用父节点须先停用全部子孙，避免树断链
        List<MdmCostCenter> activeChildren = new ArrayList<>();
        collectActiveDescendants(id, activeChildren);
        if (!activeChildren.isEmpty()) {
            throw new ServiceException(409, "存在未停用的子孙成本中心，请先停用：" + summarize(activeChildren));
        }
        // C-4.1-03 下游引用校验桩：首个下游模块（凭证行/请购/资产携带 cost_center_id）落地时回补
        List<String> references = queryDownstreamReferences(id);
        if (!references.isEmpty()) {
            throw new ServiceException(409, "该成本中心仍被下游业务数据引用，禁止停用：" + String.join("；", references));
        }
        MdmCostCenter patch = new MdmCostCenter();
        patch.setId(id);
        patch.setStatus("0");
        patch.setVerNo(stored.getVerNo());
        if (centerDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        if ("1".equals(stored.getIsDefault())) {
            clearDefault(stored.getLegalEntityId());
        }
        MdmCostCenter after = centerDao.selectById(id);
        saveSnapshot(after, "DISABLE", "status: 1 → 0");
    }

    @Override
    @Transactional
    public void setDefault(String id) {
        MdmCostCenter stored = centerDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "成本中心不存在");
        }
        if ("0".equals(stored.getStatus())) {
            throw new ServiceException(422, "停用的成本中心不能设为默认");
        }
        if ("1".equals(stored.getIsDefault())) {
            return;
        }
        MdmCostCenter patch = new MdmCostCenter();
        patch.setId(id);
        patch.setVerNo(stored.getVerNo());
        patch.setIsDefault("1");
        if (centerDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        applyDefault(centerDao.selectById(id));
        saveSnapshot(centerDao.selectById(id), "SET_DEFAULT", "isDefault: 0 → 1");
    }

    @Override
    public List<Map<String, String>> options(String legalEntityId) {
        List<MdmCostCenter> list = centerDao.selectList(
                new LambdaQueryWrapper<MdmCostCenter>()
                        .eq(MdmCostCenter::getStatus, "1")
                        .eq(isNotBlank(legalEntityId), MdmCostCenter::getLegalEntityId, legalEntityId)
                        .orderByAsc(MdmCostCenter::getCcCode));
        List<Map<String, String>> options = new ArrayList<>();
        for (MdmCostCenter c : list) {
            Map<String, String> o = new LinkedHashMap<>();
            o.put("id", c.getId());
            o.put("code", c.getCcCode());
            o.put("name", c.getCcName());
            o.put("level", String.valueOf(c.getTreeLevel()));
            o.put("profitCenterId", c.getProfitCenterId() == null ? "" : c.getProfitCenterId());
            options.add(o);
        }
        return options;
    }

    @Override
    public List<MdmCostCenterVersion> versions(String entityId) {
        return versionDao.selectList(
                new LambdaQueryWrapper<MdmCostCenterVersion>()
                        .eq(MdmCostCenterVersion::getEntityId, entityId)
                        .orderByDesc(MdmCostCenterVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> diff(String entityId, int from, int to) {
        MdmCostCenterVersion vFrom = findVersion(entityId, from);
        MdmCostCenterVersion vTo = findVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", versionMeta(vFrom));
        result.put("to", versionMeta(vTo));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    // ---------- private ----------

    private void resolveParentAndLevel(MdmCostCenter center, MdmCostCenter stored) {
        String newParent = center.getParentId() == null ? stored.getParentId() : center.getParentId();
        newParent = newParent == null ? "" : newParent;
        if (newParent.isEmpty()) {
            center.setParentId("");
            center.setTreeLevel(1);
            return;
        }
        if (newParent.equals(center.getId())) {
            throw new ServiceException(422, "父级成本中心不能是自身（层级不得成环）");
        }
        // DC-06 成环：回溯新父链，出现自身即成环；顺带计算深度
        int level = 1;
        String cursor = newParent;
        while (cursor != null && !cursor.isEmpty()) {
            if (cursor.equals(center.getId())) {
                throw new ServiceException(422, "父级成本中心不能是其子孙节点（层级不得成环）");
            }
            MdmCostCenter node = centerDao.selectById(cursor);
            if (node == null) {
                throw new ServiceException(422, "父级成本中心不存在");
            }
            if (!node.getLegalEntityId().equals(center.getLegalEntityId())) {
                throw new ServiceException(422, "父级成本中心必须与本节点隶属同一法人主体");
            }
            level++;
            cursor = node.getParentId();
        }
        if (level > MAX_DEPTH) {
            throw new ServiceException(422, "成本中心层级最多 " + MAX_DEPTH + " 级");
        }
        center.setParentId(newParent);
        center.setTreeLevel(level);
    }

    private void insertWithGeneratedCode(MdmCostCenter center, String typeCode) {
        // 前缀形如 "CC-PROD-"，长度 = 3(CC-) + 类型码 + 1(-)
        int prefixLen = 3 + typeCode.length() + 1;
        String maxCode = centerDao.selectMaxCodeByType(center.getCostType());
        int seq = 0;
        if (maxCode != null && maxCode.matches("CC-" + typeCode + "-\\d{2}")) {
            seq = Integer.parseInt(maxCode.substring(prefixLen));
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            String code = String.format("CC-%s-%02d", typeCode, seq + 1 + attempt);
            center.setCcCode(code);
            try {
                centerDao.insert(center);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                String current = centerDao.selectMaxCodeByType(center.getCostType());
                if (current != null && current.matches("CC-" + typeCode + "-\\d{2}")) {
                    seq = Integer.parseInt(current.substring(prefixLen));
                }
            }
        }
        throw new ServiceException(500, "成本中心编码生成失败，请重试");
    }

    /** 置默认：清除同主体旧默认，本节点已在事务内更新 */
    private void applyDefault(MdmCostCenter center) {
        clearDefault(center.getLegalEntityId(), center.getId());
    }

    private void clearDefault(String legalEntityId) {
        clearDefault(legalEntityId, null);
    }

    private void clearDefault(String legalEntityId, String exceptId) {
        LambdaQueryWrapper<MdmCostCenter> qw = new LambdaQueryWrapper<MdmCostCenter>()
                .eq(MdmCostCenter::getLegalEntityId, legalEntityId)
                .eq(MdmCostCenter::getIsDefault, "1")
                .ne(exceptId != null, MdmCostCenter::getId, exceptId);
        List<MdmCostCenter> olds = centerDao.selectList(qw);
        for (MdmCostCenter old : olds) {
            MdmCostCenter patch = new MdmCostCenter();
            patch.setId(old.getId());
            patch.setVerNo(old.getVerNo());
            patch.setIsDefault("0");
            centerDao.updateById(patch);
            saveSnapshot(centerDao.selectById(old.getId()), "SET_DEFAULT", "isDefault: 1 → 0");
        }
    }

    private void collectActiveDescendants(String parentId, List<MdmCostCenter> out) {
        for (MdmCostCenter child : centerDao.selectChildren(parentId)) {
            if ("1".equals(child.getStatus())) {
                out.add(child);
            }
            collectActiveDescendants(child.getId(), out);
        }
    }

    private void checkDuplicateCode(String legalEntityId, String typeCode, String selfId) {
        List<MdmCostCenter> same = centerDao.findSimilarByCode(legalEntityId, "CC-" + typeCode);
        boolean hasOther = same.stream().anyMatch(c -> !c.getId().equals(selfId));
        if (hasOther && selfId == null) {
            throw new ServiceException(409, "该主体下同类型成本中心编码相似，请确认：" + summarize(same));
        }
    }

    private void saveSnapshot(MdmCostCenter source, String opType, String diffSummary) {
        int next = versionDao.selectCount(
                new LambdaQueryWrapper<MdmCostCenterVersion>()
                        .eq(MdmCostCenterVersion::getEntityId, source.getId())).intValue() + 1;
        MdmCostCenterVersion v = new MdmCostCenterVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source));
        v.setDiffSummary(diffSummary);
        v.setOpType(opType);
        versionDao.insert(v);
    }

    private MdmCostCenterVersion findVersion(String entityId, int versionNo) {
        MdmCostCenterVersion v = versionDao.selectOne(
                new LambdaQueryWrapper<MdmCostCenterVersion>()
                        .eq(MdmCostCenterVersion::getEntityId, entityId)
                        .eq(MdmCostCenterVersion::getVersionNo, versionNo));
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

    private String buildDiff(MdmCostCenter oldRow, MdmCostCenter newRow) {
        StringBuilder sb = new StringBuilder();
        appendDiff(sb, "ccName", oldRow.getCcName(), newRow.getCcName());
        appendDiff(sb, "legalEntityId", oldRow.getLegalEntityId(), newRow.getLegalEntityId());
        appendDiff(sb, "profitCenterId", oldRow.getProfitCenterId(), newRow.getProfitCenterId());
        appendDiff(sb, "parentId", oldRow.getParentId(), newRow.getParentId());
        appendDiff(sb, "treeLevel", str(oldRow.getTreeLevel()), str(newRow.getTreeLevel()));
        appendDiff(sb, "costType", oldRow.getCostType(), newRow.getCostType());
        appendDiff(sb, "isDefault", oldRow.getIsDefault(), newRow.getIsDefault());
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

    private List<MdmCostCenter> filterWithAncestors(List<MdmCostCenter> all, String keyword) {
        String lower = keyword.toLowerCase();
        Map<String, MdmCostCenter> byId = new LinkedHashMap<>();
        for (MdmCostCenter c : all) {
            byId.put(c.getId(), c);
        }
        Map<String, Boolean> keep = new LinkedHashMap<>();
        for (MdmCostCenter c : all) {
            if (c.getCcCode().toLowerCase().contains(lower) || c.getCcName().toLowerCase().contains(lower)) {
                String cursor = c.getId();
                while (cursor != null && !cursor.isEmpty()) {
                    if (keep.put(cursor, Boolean.TRUE) != null) {
                        break;
                    }
                    MdmCostCenter node = byId.get(cursor);
                    cursor = node == null ? null : node.getParentId();
                }
            }
        }
        List<MdmCostCenter> result = new ArrayList<>();
        for (MdmCostCenter c : all) {
            if (keep.containsKey(c.getId())) {
                result.add(c);
            }
        }
        return result;
    }

    private List<MdmCostCenter> buildTree(List<MdmCostCenter> all) {
        Map<String, MdmCostCenter> byId = new LinkedHashMap<>();
        for (MdmCostCenter c : all) {
            c.setChildren(null);
            byId.put(c.getId(), c);
        }
        List<MdmCostCenter> roots = new ArrayList<>();
        for (MdmCostCenter c : all) {
            MdmCostCenter parent = c.getParentId() == null ? null : byId.get(c.getParentId());
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

    private void requireFields(MdmCostCenter center) {
        if (!isNotBlank(center.getCcName())) {
            throw new ServiceException(422, "成本中心名称不能为空");
        }
        if (!isNotBlank(center.getLegalEntityId())) {
            throw new ServiceException(422, "所属法人主体不能为空");
        }
        if (!isNotBlank(center.getCostType()) || !TYPE_CODES.containsKey(center.getCostType())) {
            throw new ServiceException(422, "成本类型须为 PROD/SALES/ADMIN/RD 之一");
        }
    }

    /** 同主体校验（spec：跨主体归属 L1 阻断）；归属可空 */
    private void requireProfitCenterInSameEntity(String profitCenterId, String legalEntityId) {
        if (!isNotBlank(profitCenterId)) {
            return;
        }
        MdmProfitCenter pc = profitCenterDao.selectById(profitCenterId);
        if (pc == null) {
            throw new ServiceException(422, "所选利润中心不存在");
        }
        if (!pc.getLegalEntityId().equals(legalEntityId)) {
            throw new ServiceException(422, "利润中心与成本中心须隶属同一法人主体");
        }
    }

    /** TODO(首个下游模块落地时回补)：查询凭证行/请购/资产等对 cost_center_id 的真实引用 */
    private List<String> queryDownstreamReferences(String id) {
        return new ArrayList<>();
    }

    private String typeCodeOf(String costType) {
        String code = TYPE_CODES.get(costType);
        if (code == null) {
            throw new ServiceException(422, "成本类型须为 PROD/SALES/ADMIN/RD 之一");
        }
        return code;
    }

    private Map<String, Object> versionMeta(MdmCostCenterVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionNo", v.getVersionNo());
        m.put("opType", v.getOpType());
        m.put("createBy", v.getCreateBy());
        m.put("createDate", v.getCreateDate());
        return m;
    }

    private String summarize(List<MdmCostCenter> list) {
        StringBuilder sb = new StringBuilder();
        for (MdmCostCenter c : list) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(c.getCcCode()).append(" ").append(c.getCcName());
        }
        return sb.length() > 200 ? sb.substring(0, 200) + "…" : sb.toString();
    }

    private String toJson(MdmCostCenter c) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String field : SNAPSHOT_FIELDS.split(",")) {
            map.put(field, readField(c, field.trim()));
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            throw new ServiceException(500, "版本快照序列化失败");
        }
    }

    private Object readField(MdmCostCenter c, String field) {
        switch (field) {
            case "ccCode": return c.getCcCode();
            case "ccName": return c.getCcName();
            case "legalEntityId": return c.getLegalEntityId();
            case "profitCenterId": return c.getProfitCenterId();
            case "parentId": return c.getParentId();
            case "treeLevel": return c.getTreeLevel();
            case "costType": return c.getCostType();
            case "isDefault": return c.getIsDefault();
            case "ownerName": return c.getOwnerName();
            case "remark": return c.getRemark();
            case "status": return c.getStatus();
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
