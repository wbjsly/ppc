package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmLegalEntityDao;
import com.erp.dao.mdm.MdmLegalEntityVersionDao;
import com.erp.entity.mdm.MdmLegalEntity;
import com.erp.entity.mdm.MdmLegalEntityVersion;
import com.erp.service.mdm.MdmLegalEntityService;
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
public class MdmLegalEntityServiceImpl implements MdmLegalEntityService {

    private static final String SNAPSHOT_FIELDS =
            "leCode,leName,uscc,status,bookkeepingCurrency,regPlace,fiscalCalendarType," +
            "defaultTimezone,currencyDecimals,l10nPack,localTaxNo,legalRepresentative," +
            "registeredCapital,regAddress,bankName";

    private final MdmLegalEntityDao entityDao;
    private final MdmLegalEntityVersionDao versionDao;
    private final ObjectMapper objectMapper;

    public MdmLegalEntityServiceImpl(MdmLegalEntityDao entityDao,
                                     MdmLegalEntityVersionDao versionDao,
                                     ObjectMapper objectMapper) {
        this.entityDao = entityDao;
        this.versionDao = versionDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public Page<MdmLegalEntity> page(long current, long size, String keyword, String status) {
        LambdaQueryWrapper<MdmLegalEntity> qw = new LambdaQueryWrapper<MdmLegalEntity>()
                .eq(isNotBlank(status), MdmLegalEntity::getStatus, status)
                .and(isNotBlank(keyword), w -> w
                        .like(MdmLegalEntity::getLeCode, keyword)
                        .or()
                        .like(MdmLegalEntity::getLeName, keyword))
                .orderByAsc(MdmLegalEntity::getLeCode);
        return entityDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public MdmLegalEntity getById(String id) {
        MdmLegalEntity entity = entityDao.selectById(id);
        if (entity == null) {
            throw new ServiceException(404, "法人主体不存在");
        }
        return entity;
    }

    @Override
    @Transactional
    public MdmLegalEntity create(MdmLegalEntity entity) {
        requireFields(entity);
        // C-4.1-07 税号查重，命中则展示近 3 条相似项
        List<MdmLegalEntity> sameUscc = entityDao.findByUsccExcludingSelf(entity.getUscc(), "");
        if (!sameUscc.isEmpty()) {
            throw new ServiceException(409, "统一社会信用代码已存在，相似记录：" + summarize(sameUscc));
        }
        entity.setId(null);
        entity.setLeCode(null);
        entity.setStatus("1");
        entity.setVerNo(0);
        insertWithGeneratedCode(entity);
        saveSnapshot(entity, "CREATE", null);
        return entity;
    }

    @Override
    @Transactional
    public MdmLegalEntity update(MdmLegalEntity entity) {
        MdmLegalEntity stored = entityDao.selectById(entity.getId());
        if (stored == null) {
            throw new ServiceException(404, "法人主体不存在");
        }
        // C-4.1-01 编码创建后不可修改
        if (entity.getLeCode() != null && !entity.getLeCode().equals(stored.getLeCode())) {
            throw new ServiceException(422, "主体编码创建后不可修改，如需更正请停用后新建");
        }
        requireFields(entity);
        List<MdmLegalEntity> sameUscc = entityDao.findByUsccExcludingSelf(entity.getUscc(), entity.getId());
        if (!sameUscc.isEmpty()) {
            throw new ServiceException(409, "统一社会信用代码已存在，相似记录：" + summarize(sameUscc));
        }
        String diff = buildDiff(stored, entity);
        entity.setLeCode(stored.getLeCode());
        entity.setVerNo(entity.getVerNo() == null ? stored.getVerNo() : entity.getVerNo());
        // 乐观锁：verNo 不匹配时影响行数为 0（C-4.1-05 并发仲裁）
        int rows = entityDao.updateById(entity);
        if (rows == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        // 快照记该版本状态（变更后），使 Vn 与 V(n-1) 对比有实际差异
        saveSnapshot(entityDao.selectById(entity.getId()), "UPDATE", diff);
        return entityDao.selectById(entity.getId());
    }

    @Override
    @Transactional
    public void disable(String id) {
        MdmLegalEntity stored = entityDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "法人主体不存在");
        }
        if ("0".equals(stored.getStatus())) {
            throw new ServiceException(422, "该主体已处于停用状态");
        }
        // C-4.1-03 下游引用校验：当前下游业务模块均为占位页、尚无引用表，
        // 首个下游模块（交易单据携带 legal_entity_id）落地时须在此回补真实引用查询。
        List<String> references = queryDownstreamReferences(id);
        if (!references.isEmpty()) {
            throw new ServiceException(409, "该主体仍被下游业务数据引用，禁止停用：" + String.join("；", references));
        }
        MdmLegalEntity patch = new MdmLegalEntity();
        patch.setId(id);
        patch.setStatus("0");
        patch.setVerNo(stored.getVerNo());
        if (entityDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        saveSnapshot(entityDao.selectById(id), "DISABLE", "status: 1 → 0");
    }

    @Override
    public List<Map<String, String>> options() {
        List<MdmLegalEntity> list = entityDao.selectList(
                new LambdaQueryWrapper<MdmLegalEntity>()
                        .eq(MdmLegalEntity::getStatus, "1")
                        .orderByAsc(MdmLegalEntity::getLeCode));
        List<Map<String, String>> options = new ArrayList<>();
        for (MdmLegalEntity e : list) {
            Map<String, String> o = new LinkedHashMap<>();
            o.put("id", e.getId());
            o.put("code", e.getLeCode());
            o.put("name", e.getLeName());
            options.add(o);
        }
        return options;
    }

    @Override
    public List<MdmLegalEntityVersion> versions(String entityId) {
        return versionDao.selectList(
                new LambdaQueryWrapper<MdmLegalEntityVersion>()
                        .eq(MdmLegalEntityVersion::getEntityId, entityId)
                        .orderByDesc(MdmLegalEntityVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> diff(String entityId, int from, int to) {
        MdmLegalEntityVersion vFrom = findVersion(entityId, from);
        MdmLegalEntityVersion vTo = findVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", versionMeta(vFrom));
        result.put("to", versionMeta(vTo));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    // ---------- private ----------

    private void insertWithGeneratedCode(MdmLegalEntity entity) {
        int max = 0;
        String maxCode = entityDao.selectMaxCode();
        if (maxCode != null && maxCode.matches("LE-\\d{4}")) {
            max = Integer.parseInt(maxCode.substring(3));
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            String code = String.format("LE-%04d", max + 1 + attempt);
            entity.setLeCode(code);
            try {
                entityDao.insert(entity);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 唯一索引冲突（并发生成），重取最大值重试
                String current = entityDao.selectMaxCode();
                if (current != null && current.matches("LE-\\d{4}")) {
                    max = Integer.parseInt(current.substring(3));
                }
            }
        }
        throw new ServiceException(500, "主体编码生成失败，请重试");
    }

    private void saveSnapshot(MdmLegalEntity source, String opType, String diffSummary) {
        int next = versionDao.selectCount(
                new LambdaQueryWrapper<MdmLegalEntityVersion>()
                        .eq(MdmLegalEntityVersion::getEntityId, source.getId())).intValue() + 1;
        MdmLegalEntityVersion v = new MdmLegalEntityVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source));
        v.setDiffSummary(diffSummary);
        v.setOpType(opType);
        versionDao.insert(v);
    }

    private MdmLegalEntityVersion findVersion(String entityId, int versionNo) {
        MdmLegalEntityVersion v = versionDao.selectOne(
                new LambdaQueryWrapper<MdmLegalEntityVersion>()
                        .eq(MdmLegalEntityVersion::getEntityId, entityId)
                        .eq(MdmLegalEntityVersion::getVersionNo, versionNo));
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

    private String buildDiff(MdmLegalEntity oldRow, MdmLegalEntity newRow) {
        StringBuilder sb = new StringBuilder();
        appendDiff(sb, "leName", oldRow.getLeName(), newRow.getLeName());
        appendDiff(sb, "uscc", oldRow.getUscc(), newRow.getUscc());
        appendDiff(sb, "bookkeepingCurrency", oldRow.getBookkeepingCurrency(), newRow.getBookkeepingCurrency());
        appendDiff(sb, "regPlace", oldRow.getRegPlace(), newRow.getRegPlace());
        appendDiff(sb, "fiscalCalendarType", oldRow.getFiscalCalendarType(), newRow.getFiscalCalendarType());
        appendDiff(sb, "defaultTimezone", oldRow.getDefaultTimezone(), newRow.getDefaultTimezone());
        appendDiff(sb, "currencyDecimals", str(oldRow.getCurrencyDecimals()), str(newRow.getCurrencyDecimals()));
        appendDiff(sb, "l10nPack", oldRow.getL10nPack(), newRow.getL10nPack());
        appendDiff(sb, "localTaxNo", oldRow.getLocalTaxNo(), newRow.getLocalTaxNo());
        appendDiff(sb, "legalRepresentative", oldRow.getLegalRepresentative(), newRow.getLegalRepresentative());
        appendDiff(sb, "registeredCapital", str(oldRow.getRegisteredCapital()), str(newRow.getRegisteredCapital()));
        appendDiff(sb, "regAddress", oldRow.getRegAddress(), newRow.getRegAddress());
        appendDiff(sb, "bankName", oldRow.getBankName(), newRow.getBankName());
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

    private void requireFields(MdmLegalEntity entity) {
        if (isNotBlank(entity.getLeName()) && entity.getLeName().contains("<")) {
            throw new ServiceException(422, "名称含非法字符，请检查");
        }
        if (!isNotBlank(entity.getLeName())) {
            throw new ServiceException(422, "主体名称不能为空");
        }
        if (!isNotBlank(entity.getUscc())) {
            throw new ServiceException(422, "统一社会信用代码不能为空");
        }
        if (!isNotBlank(entity.getBookkeepingCurrency())) {
            throw new ServiceException(422, "记账本位币不能为空");
        }
        if (!isNotBlank(entity.getRegPlace())) {
            throw new ServiceException(422, "注册地不能为空");
        }
        if (entity.getCurrencyDecimals() == null || entity.getCurrencyDecimals() < 0 || entity.getCurrencyDecimals() > 4) {
            throw new ServiceException(422, "币种小数位须在 0-4 之间");
        }
    }

    /** TODO(首个下游模块落地时回补)：查询交易单据等对 legal_entity_id 的真实引用 */
    private List<String> queryDownstreamReferences(String entityId) {
        return new ArrayList<>();
    }

    private Map<String, Object> versionMeta(MdmLegalEntityVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionNo", v.getVersionNo());
        m.put("opType", v.getOpType());
        m.put("createBy", v.getCreateBy());
        m.put("createDate", v.getCreateDate());
        return m;
    }

    private String summarize(List<MdmLegalEntity> list) {
        StringBuilder sb = new StringBuilder();
        for (MdmLegalEntity e : list) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(e.getLeCode()).append(" ").append(e.getLeName());
        }
        return sb.toString();
    }

    private String toJson(MdmLegalEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String field : SNAPSHOT_FIELDS.split(",")) {
            map.put(field, readField(e, field.trim()));
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            throw new ServiceException(500, "版本快照序列化失败");
        }
    }

    private Object readField(MdmLegalEntity e, String field) {
        switch (field) {
            case "leCode": return e.getLeCode();
            case "leName": return e.getLeName();
            case "uscc": return e.getUscc();
            case "status": return e.getStatus();
            case "bookkeepingCurrency": return e.getBookkeepingCurrency();
            case "regPlace": return e.getRegPlace();
            case "fiscalCalendarType": return e.getFiscalCalendarType();
            case "defaultTimezone": return e.getDefaultTimezone();
            case "currencyDecimals": return e.getCurrencyDecimals();
            case "l10nPack": return e.getL10nPack();
            case "localTaxNo": return e.getLocalTaxNo();
            case "legalRepresentative": return e.getLegalRepresentative();
            case "registeredCapital": return e.getRegisteredCapital();
            case "regAddress": return e.getRegAddress();
            case "bankName": return e.getBankName();
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
