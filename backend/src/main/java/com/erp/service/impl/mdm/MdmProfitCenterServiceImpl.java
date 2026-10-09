package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmCostCenterDao;
import com.erp.dao.mdm.MdmProfitCenterDao;
import com.erp.dao.mdm.MdmProfitCenterVersionDao;
import com.erp.entity.mdm.MdmCostCenter;
import com.erp.entity.mdm.MdmProfitCenter;
import com.erp.entity.mdm.MdmProfitCenterVersion;
import com.erp.service.mdm.MdmProfitCenterService;
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
public class MdmProfitCenterServiceImpl implements MdmProfitCenterService {

    private static final String SNAPSHOT_FIELDS = "pcCode,pcName,legalEntityId,ownerName,remark,status";

    private final MdmProfitCenterDao centerDao;
    private final MdmProfitCenterVersionDao versionDao;
    private final MdmCostCenterDao costCenterDao;
    private final ObjectMapper objectMapper;

    public MdmProfitCenterServiceImpl(MdmProfitCenterDao centerDao,
                                      MdmProfitCenterVersionDao versionDao,
                                      MdmCostCenterDao costCenterDao,
                                      ObjectMapper objectMapper) {
        this.centerDao = centerDao;
        this.versionDao = versionDao;
        this.costCenterDao = costCenterDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public Page<MdmProfitCenter> page(long current, long size, String keyword, String legalEntityId, String status) {
        LambdaQueryWrapper<MdmProfitCenter> qw = new LambdaQueryWrapper<MdmProfitCenter>()
                .eq(isNotBlank(legalEntityId), MdmProfitCenter::getLegalEntityId, legalEntityId)
                .eq(isNotBlank(status), MdmProfitCenter::getStatus, status)
                .and(isNotBlank(keyword), w -> w
                        .like(MdmProfitCenter::getPcCode, keyword)
                        .or()
                        .like(MdmProfitCenter::getPcName, keyword))
                .orderByAsc(MdmProfitCenter::getPcCode);
        return centerDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public MdmProfitCenter getById(String id) {
        MdmProfitCenter center = centerDao.selectById(id);
        if (center == null) {
            throw new ServiceException(404, "利润中心不存在");
        }
        return center;
    }

    @Override
    @Transactional
    public MdmProfitCenter create(MdmProfitCenter center) {
        requireFields(center);
        center.setId(null);
        center.setPcCode(null);
        center.setStatus("1");
        center.setVerNo(0);
        insertWithGeneratedCode(center);
        saveSnapshot(center, "CREATE", null);
        return center;
    }

    @Override
    @Transactional
    public MdmProfitCenter update(MdmProfitCenter center) {
        MdmProfitCenter stored = centerDao.selectById(center.getId());
        if (stored == null) {
            throw new ServiceException(404, "利润中心不存在");
        }
        // C-4.1-01 编码创建后不可修改
        if (center.getPcCode() != null && !center.getPcCode().equals(stored.getPcCode())) {
            throw new ServiceException(422, "利润中心编码创建后不可修改");
        }
        requireFields(center);
        String diff = buildDiff(stored, center);
        center.setPcCode(stored.getPcCode());
        center.setVerNo(center.getVerNo() == null ? stored.getVerNo() : center.getVerNo());
        // 乐观锁：verNo 不匹配时影响行数为 0
        if (centerDao.updateById(center) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        saveSnapshot(centerDao.selectById(center.getId()), "UPDATE", diff);
        return centerDao.selectById(center.getId());
    }

    @Override
    @Transactional
    public void disable(String id) {
        MdmProfitCenter stored = centerDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "利润中心不存在");
        }
        if ("0".equals(stored.getStatus())) {
            throw new ServiceException(422, "该利润中心已处于停用状态");
        }
        // 名下仍有启用中的成本中心归属 → 阻断并列出
        List<MdmCostCenter> activeCostCenters = costCenterDao.selectList(
                new LambdaQueryWrapper<MdmCostCenter>()
                        .eq(MdmCostCenter::getProfitCenterId, id)
                        .eq(MdmCostCenter::getStatus, "1")
                        .orderByAsc(MdmCostCenter::getCcCode));
        if (!activeCostCenters.isEmpty()) {
            throw new ServiceException(409, "该利润中心名下仍有启用中的成本中心，请先调整归属或停用：" + summarize(activeCostCenters));
        }
        // C-4.1-03 下游引用校验桩：凭证行（FR-4.6-1-3）落地时回补 profit_center_id 引用查询
        List<String> references = queryDownstreamReferences(id);
        if (!references.isEmpty()) {
            throw new ServiceException(409, "该利润中心仍被下游业务数据引用，禁止停用：" + String.join("；", references));
        }
        MdmProfitCenter patch = new MdmProfitCenter();
        patch.setId(id);
        patch.setStatus("0");
        patch.setVerNo(stored.getVerNo());
        if (centerDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        saveSnapshot(centerDao.selectById(id), "DISABLE", "status: 1 → 0");
    }

    @Override
    public List<Map<String, String>> options(String legalEntityId) {
        List<MdmProfitCenter> list = centerDao.selectList(
                new LambdaQueryWrapper<MdmProfitCenter>()
                        .eq(MdmProfitCenter::getStatus, "1")
                        .eq(isNotBlank(legalEntityId), MdmProfitCenter::getLegalEntityId, legalEntityId)
                        .orderByAsc(MdmProfitCenter::getPcCode));
        List<Map<String, String>> options = new ArrayList<>();
        for (MdmProfitCenter c : list) {
            Map<String, String> o = new LinkedHashMap<>();
            o.put("id", c.getId());
            o.put("code", c.getPcCode());
            o.put("name", c.getPcName());
            options.add(o);
        }
        return options;
    }

    @Override
    public List<MdmProfitCenterVersion> versions(String entityId) {
        return versionDao.selectList(
                new LambdaQueryWrapper<MdmProfitCenterVersion>()
                        .eq(MdmProfitCenterVersion::getEntityId, entityId)
                        .orderByDesc(MdmProfitCenterVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> diff(String entityId, int from, int to) {
        MdmProfitCenterVersion vFrom = findVersion(entityId, from);
        MdmProfitCenterVersion vTo = findVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", versionMeta(vFrom));
        result.put("to", versionMeta(vTo));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    // ---------- private ----------

    private void insertWithGeneratedCode(MdmProfitCenter center) {
        String maxCode = centerDao.selectMaxCode();
        int seq = 0;
        if (maxCode != null && maxCode.matches("PC-\\d{4}")) {
            seq = Integer.parseInt(maxCode.substring(3));
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            center.setPcCode(String.format("PC-%04d", seq + 1 + attempt));
            try {
                centerDao.insert(center);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                String current = centerDao.selectMaxCode();
                if (current != null && current.matches("PC-\\d{4}")) {
                    seq = Integer.parseInt(current.substring(3));
                }
            }
        }
        throw new ServiceException(500, "利润中心编码生成失败，请重试");
    }

    private void saveSnapshot(MdmProfitCenter source, String opType, String diffSummary) {
        int next = versionDao.selectCount(
                new LambdaQueryWrapper<MdmProfitCenterVersion>()
                        .eq(MdmProfitCenterVersion::getEntityId, source.getId())).intValue() + 1;
        MdmProfitCenterVersion v = new MdmProfitCenterVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source));
        v.setDiffSummary(diffSummary);
        v.setOpType(opType);
        versionDao.insert(v);
    }

    private MdmProfitCenterVersion findVersion(String entityId, int versionNo) {
        MdmProfitCenterVersion v = versionDao.selectOne(
                new LambdaQueryWrapper<MdmProfitCenterVersion>()
                        .eq(MdmProfitCenterVersion::getEntityId, entityId)
                        .eq(MdmProfitCenterVersion::getVersionNo, versionNo));
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

    private String buildDiff(MdmProfitCenter oldRow, MdmProfitCenter newRow) {
        StringBuilder sb = new StringBuilder();
        appendDiff(sb, "pcName", oldRow.getPcName(), newRow.getPcName());
        appendDiff(sb, "legalEntityId", oldRow.getLegalEntityId(), newRow.getLegalEntityId());
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

    private void requireFields(MdmProfitCenter center) {
        if (!isNotBlank(center.getPcName())) {
            throw new ServiceException(422, "利润中心名称不能为空");
        }
        if (!isNotBlank(center.getLegalEntityId())) {
            throw new ServiceException(422, "所属法人主体不能为空");
        }
    }

    /** TODO(凭证行 4.6 落地时回补)：查询 journal_entry 对 profit_center_id 的真实引用 */
    private List<String> queryDownstreamReferences(String id) {
        return new ArrayList<>();
    }

    private Map<String, Object> versionMeta(MdmProfitCenterVersion v) {
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

    private String toJson(MdmProfitCenter c) {
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

    private Object readField(MdmProfitCenter c, String field) {
        switch (field) {
            case "pcCode": return c.getPcCode();
            case "pcName": return c.getPcName();
            case "legalEntityId": return c.getLegalEntityId();
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

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
