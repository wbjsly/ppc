package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.qms.CharacteristicDao;
import com.erp.dao.qms.InspectionStandardDao;
import com.erp.dao.qms.SamplingPlanDao;
import com.erp.dao.qms.StandardApplicabilityDao;
import com.erp.dao.qms.StandardVersionDao;
import com.erp.entity.qms.Characteristic;
import com.erp.entity.qms.InspectionStandard;
import com.erp.entity.qms.SamplingPlan;
import com.erp.entity.qms.StandardApplicability;
import com.erp.entity.qms.StandardVersion;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.qms.InspectionStandardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 检验标准库实现（spec inspection-standard）。
 * 发布审批 BIZ_TYPE = StandardPublish，回调由 {@link StandardPublishCallback} 承接。
 */
@Slf4j
@Service
public class InspectionStandardServiceImpl implements InspectionStandardService {

    private final InspectionStandardDao standardDao;
    private final StandardVersionDao versionDao;
    private final CharacteristicDao characteristicDao;
    private final StandardApplicabilityDao applicabilityDao;
    private final SamplingPlanDao samplingPlanDao;
    private final ApprovalEngine approvalEngine;

    public InspectionStandardServiceImpl(InspectionStandardDao standardDao,
                                         StandardVersionDao versionDao,
                                         CharacteristicDao characteristicDao,
                                         StandardApplicabilityDao applicabilityDao,
                                         SamplingPlanDao samplingPlanDao,
                                         ApprovalEngine approvalEngine) {
        this.standardDao = standardDao;
        this.versionDao = versionDao;
        this.characteristicDao = characteristicDao;
        this.applicabilityDao = applicabilityDao;
        this.samplingPlanDao = samplingPlanDao;
        this.approvalEngine = approvalEngine;
    }

    // ---------- 标准头 ----------

    @Override
    public Map<String, Object> page(long current, long size, String keyword, String status) {
        LambdaQueryWrapper<InspectionStandard> qw = new LambdaQueryWrapper<>();
        if (hasText(keyword)) {
            // 嵌套括号，避免 OR 与后续 AND 优先级错乱
            qw.and(w -> w.like(InspectionStandard::getStandardCode, keyword)
                    .or()
                    .like(InspectionStandard::getName, keyword));
        }
        if (hasText(status)) {
            qw.eq(InspectionStandard::getStatus, status);
        }
        qw.orderByDesc(InspectionStandard::getCreateDate);
        long total = standardDao.selectCount(qw);
        List<InspectionStandard> rows = standardDao.selectList(qw
                .last("LIMIT " + ((current - 1) * size) + "," + size));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("records", rows);
        return out;
    }

    @Override
    @Transactional
    public InspectionStandard create(InspectionStandard standard) {
        if (!hasText(standard.getStandardCode())) {
            throw new ServiceException(422, "标准编码必填");
        }
        if (!hasText(standard.getName())) {
            throw new ServiceException(422, "标准名称必填");
        }
        long dup = standardDao.selectCount(new LambdaQueryWrapper<InspectionStandard>()
                .eq(InspectionStandard::getStandardCode, standard.getStandardCode()));
        if (dup > 0) {
            throw new ServiceException(422, "标准编码已存在");
        }
        standard.setId(null);
        standard.setStatus("DRAFT");
        standard.setCurrentVersion(0);
        if (!hasText(standard.getRiskLevel())) {
            standard.setRiskLevel("C");
        }
        standardDao.insert(standard);
        return standard;
    }

    @Override
    @Transactional
    public InspectionStandard update(String id, InspectionStandard body) {
        InspectionStandard s = requireStandard(id);
        // 编码创建后不可改（spec inspection-standard）
        if (body.getStandardCode() != null && !body.getStandardCode().equals(s.getStandardCode())) {
            throw new ServiceException(422, "标准编码创建后不可修改");
        }
        if (body.getName() != null) {
            s.setName(body.getName());
        }
        if (body.getRiskLevel() != null) {
            s.setRiskLevel(body.getRiskLevel());
        }
        if (body.getScopeType() != null) {
            s.setScopeType(body.getScopeType());
        }
        if (body.getDescription() != null) {
            s.setDescription(body.getDescription());
        }
        if (body.getOwnerName() != null) {
            s.setOwnerName(body.getOwnerName());
            s.setOwnerId(body.getOwnerId());
        }
        if (standardDao.updateById(s) == 0) {
            throw new ServiceException(422, "更新冲突，请刷新重试");
        }
        return s;
    }

    @Override
    public Map<String, Object> detail(String id) {
        InspectionStandard s = requireStandard(id);
        List<StandardVersion> versions = versionDao.selectList(new LambdaQueryWrapper<StandardVersion>()
                .eq(StandardVersion::getStandardId, id)
                .orderByDesc(StandardVersion::getVersionNo));
        List<Map<String, Object>> appl = new ArrayList<>();
        if (!versions.isEmpty()) {
            List<String> vids = versions.stream().map(StandardVersion::getId).toList();
            List<StandardApplicability> rows = applicabilityDao.selectList(new LambdaQueryWrapper<StandardApplicability>()
                    .in(StandardApplicability::getVersionId, vids));
            for (StandardApplicability a : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("versionId", a.getVersionId());
                m.put("materialCode", a.getMaterialCode());
                m.put("categoryCode", a.getCategoryCode());
                m.put("supplierId", a.getSupplierId());
                m.put("customerId", a.getCustomerId());
                m.put("processId", a.getProcessId());
                m.put("priority", a.getPriority());
                appl.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("standard", s);
        out.put("versions", versions);
        out.put("applicabilities", appl);
        return out;
    }

    @Override
    @Transactional
    public InspectionStandard retire(String id) {
        InspectionStandard s = requireStandard(id);
        // 停用仅阻止新任务引用，历史追溯不变（BR-4.12-58）
        s.setStatus("RETIRED");
        if (standardDao.updateById(s) == 0) {
            throw new ServiceException(422, "更新冲突，请刷新重试");
        }
        return s;
    }

    // ---------- 版本 ----------

    @Override
    @Transactional
    public Map<String, Object> createVersion(String standardId, Map<String, Object> body) {
        InspectionStandard s = requireStandard(standardId);
        long open = versionDao.selectCount(new LambdaQueryWrapper<StandardVersion>()
                .eq(StandardVersion::getStandardId, standardId)
                .in(StandardVersion::getStatus, Arrays.asList("DRAFT", "PENDING_APPROVE")));
        if (open > 0) {
            throw new ServiceException(422, "已存在草稿或审批中的版本，请先处理");
        }
        StandardVersion v = new StandardVersion();
        v.setStandardId(standardId);
        v.setVersionNo(s.getCurrentVersion() + 1);
        v.setStatus("DRAFT");
        applyVersionFields(v, body);
        if (!hasText(v.getChangeReason())) {
            throw new ServiceException(422, "变更原因必填");
        }
        versionDao.insert(v);
        saveCharacteristics(v.getId(), body);
        saveApplicabilities(v.getId(), body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", v);
        out.put("characteristics", characteristics(v.getId()));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> updateVersion(String versionId, Map<String, Object> body) {
        StandardVersion v = requireVersion(versionId);
        if (!"DRAFT".equals(v.getStatus())) {
            // 已发布版本只读（BR-4.12-57）；审批中也不可改
            throw new ServiceException(422, "已发布版本不可修改，请创建新版本");
        }
        applyVersionFields(v, body);
        if (versionDao.updateById(v) == 0) {
            throw new ServiceException(422, "更新冲突，请刷新重试");
        }
        saveCharacteristics(versionId, body);
        saveApplicabilities(versionId, body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", v);
        out.put("characteristics", characteristics(versionId));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> submitPublish(String versionId) {
        StandardVersion v = requireVersion(versionId);
        InspectionStandard s = requireStandard(v.getStandardId());

        // 重复提交优先判（spec scenario：同一业务重复提交 422「审批中，不可重复提交」）
        var existing = approvalEngine.findByBiz("StandardPublish", versionId);
        if (existing != null && "PENDING".equals(existing.getStatus())) {
            throw new ServiceException(422, "该版本审批中，不可重复提交");
        }
        if (!"DRAFT".equals(v.getStatus())) {
            throw new ServiceException(422, "仅草稿版本可提交发布");
        }

        // C-4.12-18：必填结构校验
        List<String> missing = new ArrayList<>();
        if (v.getEffectiveFrom() == null) missing.add("生效日期");
        if (!hasText(v.getSamplingPlanId()) && !hasText(v.getAqlLevel())) missing.add("抽样方案/AQL 等级");
        List<Characteristic> chars = characteristicDao.selectList(new LambdaQueryWrapper<Characteristic>()
                .eq(Characteristic::getVersionId, versionId));
        if (chars.isEmpty()) missing.add("检验特性");
        if (chars.stream().noneMatch(c -> "1".equals(c.getCtqFlag()))) missing.add("CTQ 特性（至少一条）");
        for (Characteristic c : chars) {
            if (!hasText(c.getCharacteristicName())) missing.add("检验特性名称");
            if ("NUMERIC".equals(c.getSpecType())) {
                if (c.getLowerLimit() == null || c.getUpperLimit() == null) {
                    missing.add("计量型特性上下限：" + c.getCharacteristicName());
                } else if (c.getLowerLimit().compareTo(c.getUpperLimit()) >= 0) {
                    missing.add("下限须小于上限：" + c.getCharacteristicName());
                }
            } else if (c.getLowerLimit() != null || c.getUpperLimit() != null) {
                missing.add("非计量型特性不可填上下限：" + c.getCharacteristicName());
            }
        }
        List<StandardApplicability> appl = applicabilityDao.selectList(new LambdaQueryWrapper<StandardApplicability>()
                .eq(StandardApplicability::getVersionId, versionId));
        if (appl.isEmpty()) missing.add("适用范围");
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "发布校验未通过，缺失：" + String.join("、", missing));
        }

        // 生效区间不重叠（同标准内已发布版本）：
        // 新版本生效日必须晚于每个已发布版本的生效起；若其生效止已定，还必须落在其之后
        if (v.getEffectiveFrom() != null) {
            List<StandardVersion> released = versionDao.selectList(new LambdaQueryWrapper<StandardVersion>()
                    .eq(StandardVersion::getStandardId, v.getStandardId())
                    .eq(StandardVersion::getStatus, "RELEASED"));
            for (StandardVersion r : released) {
                if (r.getEffectiveFrom() != null && !v.getEffectiveFrom().isAfter(r.getEffectiveFrom())) {
                    throw new ServiceException(422, "生效区间与已发布版本 V" + r.getVersionNo() + " 重叠（冲突）");
                }
                if (r.getEffectiveTo() != null && !v.getEffectiveFrom().isAfter(r.getEffectiveTo())) {
                    throw new ServiceException(422, "生效区间与已发布版本 V" + r.getVersionNo() + " 重叠（冲突）");
                }
            }
        }

        // 已有 PENDING 审批 → 422（重复提交，上方已判）；此处仅保留节点构造前的状态确认
        boolean needDouble = chars.stream().anyMatch(c -> "1".equals(c.getCtqFlag()) || "1".equals(c.getRegulatoryFlag()));
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        List<ApprovalNodeSpec> first = new ArrayList<>();
        first.add(ApprovalNodeSpec.sign("ROLE_QUALITY_MGR", "质量经理审批"));
        if (needDouble) {
            first.add(ApprovalNodeSpec.sign("ROLE_TECH_OWNER", "技术负责人会签"));
        }
        chain.add(first);

        v.setStatus("PENDING_APPROVE");
        if (versionDao.updateById(v) == 0) {
            throw new ServiceException(422, "更新冲突，请刷新重试");
        }
        var inst = approvalEngine.submit("StandardPublish", versionId,
                "检验标准发布：" + s.getStandardCode() + " V" + v.getVersionNo(),
                "ROLE_QUALITY_DIRECTOR", chain);
        v.setApprovalId(inst.getId());
        versionDao.updateById(v);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", v);
        out.put("approvalId", inst.getId());
        out.put("apprNo", inst.getApprNo());
        out.put("doubleSign", needDouble);
        return out;
    }

    @Override
    public List<Map<String, Object>> characteristics(String versionId) {
        List<Characteristic> rows = characteristicDao.selectList(new LambdaQueryWrapper<Characteristic>()
                .eq(Characteristic::getVersionId, versionId)
                .orderByAsc(Characteristic::getSequenceNo));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Characteristic c : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("sequenceNo", c.getSequenceNo());
            m.put("characteristicName", c.getCharacteristicName());
            m.put("ctqFlag", c.getCtqFlag());
            m.put("regulatoryFlag", c.getRegulatoryFlag());
            m.put("specType", c.getSpecType());
            m.put("lowerLimit", c.getLowerLimit());
            m.put("upperLimit", c.getUpperLimit());
            m.put("targetValue", c.getTargetValue());
            m.put("unit", c.getUnit());
            m.put("methodName", c.getMethodName());
            m.put("instrumentType", c.getInstrumentType());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> resolveFor(String materialCode, String categoryCode,
                                          String supplierId, String customerId, String processId) {
        // 适用范围命中：行内所有非空维度都必须匹配（AND），跨行取优先级最高者（数值小更具体）
        List<StandardApplicability> all = applicabilityDao.selectList(null);
        if (all.isEmpty()) {
            return Map.of();
        }
        LocalDate today = LocalDate.now();
        Map<String, StandardVersion> versions = new HashMap<>();
        Set<String> versionIds = new HashSet<>();
        for (StandardApplicability a : all) {
            versionIds.add(a.getVersionId());
        }
        if (!versionIds.isEmpty()) {
            versionDao.selectBatchIds(versionIds).forEach(v -> versions.put(v.getId(), v));
        }

        List<StandardApplicability> hit = new ArrayList<>();
        for (StandardApplicability a : all) {
            StandardVersion v = versions.get(a.getVersionId());
            if (v == null || !"RELEASED".equals(v.getStatus())) continue;
            if (v.getEffectiveFrom() != null && v.getEffectiveFrom().isAfter(today)) continue;
            if (v.getEffectiveTo() != null && v.getEffectiveTo().isBefore(today)) continue;
            InspectionStandard s = standardDao.selectById(v.getStandardId());
            if (s != null && "RETIRED".equals(s.getStatus())) continue; // 停用不给新任务（BR-4.12-58）
            if (!rowMatches(a, materialCode, categoryCode, supplierId, customerId, processId)) continue;
            hit.add(a);
        }
        if (hit.isEmpty()) {
            return Map.of();
        }
        hit.sort(Comparator.comparingInt(StandardApplicability::getPriority));
        StandardApplicability best = hit.get(0);
        StandardVersion v = versions.get(best.getVersionId());
        InspectionStandard s = standardDao.selectById(v.getStandardId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", v);
        out.put("standard", s);
        out.put("applicabilityBasis", "优先级 " + best.getPriority()
                + (hasText(best.getMaterialCode()) ? " / 物料 " + best.getMaterialCode() : "")
                + (hasText(best.getCategoryCode()) ? " / 品类 " + best.getCategoryCode() : "")
                + (hasText(best.getSupplierId()) ? " / 供方 " + best.getSupplierId() : ""));
        out.put("characteristics", characteristics(v.getId()));
        return out;
    }

    /** 行维度匹配：至少一个维度有值，且所有非空维度都等于请求值（空 = 不限） */
    private static boolean rowMatches(StandardApplicability a, String materialCode, String categoryCode,
                                      String supplierId, String customerId, String processId) {
        boolean anyDim = false;
        if (hasText(a.getMaterialCode())) {
            anyDim = true;
            if (!a.getMaterialCode().equals(materialCode)) return false;
        }
        if (hasText(a.getCategoryCode())) {
            anyDim = true;
            if (!a.getCategoryCode().equals(categoryCode)) return false;
        }
        if (hasText(a.getSupplierId())) {
            anyDim = true;
            if (!a.getSupplierId().equals(supplierId)) return false;
        }
        if (hasText(a.getCustomerId())) {
            anyDim = true;
            if (!a.getCustomerId().equals(customerId)) return false;
        }
        if (hasText(a.getProcessId())) {
            anyDim = true;
            if (!a.getProcessId().equals(processId)) return false;
        }
        return anyDim;
    }

    // ---------- 抽样方案 ----------

    @Override
    public List<SamplingPlan> samplingPlans() {
        return samplingPlanDao.selectList(new LambdaQueryWrapper<SamplingPlan>()
                .orderByDesc(SamplingPlan::getCreateDate));
    }

    @Override
    @Transactional
    public SamplingPlan saveSamplingPlan(SamplingPlan plan) {
        if (!hasText(plan.getPlanCode()) || !hasText(plan.getName())) {
            throw new ServiceException(422, "方案编码与名称必填");
        }
        long dup = samplingPlanDao.selectCount(new LambdaQueryWrapper<SamplingPlan>()
                .eq(SamplingPlan::getPlanCode, plan.getPlanCode())
                .ne(plan.getId() != null, SamplingPlan::getId, plan.getId()));
        if (dup > 0) {
            throw new ServiceException(422, "方案编码已存在");
        }
        if (plan.getId() == null) {
            plan.setId(null);
            samplingPlanDao.insert(plan);
        } else {
            if (samplingPlanDao.updateById(plan) == 0) {
                throw new ServiceException(422, "更新冲突，请刷新重试");
            }
        }
        return plan;
    }

    // ---------- 内部 ----------

    private void applyVersionFields(StandardVersion v, Map<String, Object> body) {
        if (body.containsKey("samplingPlanId")) {
            v.setSamplingPlanId(str(body.get("samplingPlanId")));
        }
        if (body.containsKey("aqlLevel") && hasText(str(body.get("aqlLevel")))) {
            v.setAqlLevel(str(body.get("aqlLevel")));
        }
        if (body.containsKey("inspectionLevel") && hasText(str(body.get("inspectionLevel")))) {
            v.setInspectionLevel(str(body.get("inspectionLevel")));
        }
        if (body.containsKey("effectiveFrom") && hasText(str(body.get("effectiveFrom")))) {
            v.setEffectiveFrom(LocalDate.parse(str(body.get("effectiveFrom"))));
        }
        if (body.containsKey("effectiveTo") && hasText(str(body.get("effectiveTo")))) {
            v.setEffectiveTo(LocalDate.parse(str(body.get("effectiveTo"))));
        }
        if (body.containsKey("changeReason")) {
            v.setChangeReason(str(body.get("changeReason")));
        }
    }

    @SuppressWarnings("unchecked")
    private void saveCharacteristics(String versionId, Map<String, Object> body) {
        if (!body.containsKey("characteristics")) {
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("characteristics");
        // 整体覆盖：清旧插新（草稿期编辑）
        List<Characteristic> old = characteristicDao.selectList(new LambdaQueryWrapper<Characteristic>()
                .eq(Characteristic::getVersionId, versionId));
        for (Characteristic c : old) {
            characteristicDao.deleteById(c.getId());
        }
        int seq = 1;
        for (Map<String, Object> r : rows) {
            Characteristic c = new Characteristic();
            c.setVersionId(versionId);
            c.setSequenceNo(r.get("sequenceNo") != null ? Integer.parseInt(String.valueOf(r.get("sequenceNo"))) : seq);
            c.setCharacteristicName(str(r.get("characteristicName")));
            c.setCtqFlag(fallback(str(r.get("ctqFlag")), "0"));
            c.setRegulatoryFlag(fallback(str(r.get("regulatoryFlag")), "0"));
            c.setSpecType(fallback(str(r.get("specType")), "NUMERIC"));
            c.setLowerLimit(dec(r.get("lowerLimit")));
            c.setUpperLimit(dec(r.get("upperLimit")));
            c.setTargetValue(dec(r.get("targetValue")));
            c.setUnit(str(r.get("unit")));
            c.setMethodName(str(r.get("methodName")));
            c.setInstrumentType(str(r.get("instrumentType")));
            characteristicDao.insert(c);
            seq++;
        }
    }

    @SuppressWarnings("unchecked")
    private void saveApplicabilities(String versionId, Map<String, Object> body) {
        if (!body.containsKey("applicabilities")) {
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("applicabilities");
        List<StandardApplicability> old = applicabilityDao.selectList(new LambdaQueryWrapper<StandardApplicability>()
                .eq(StandardApplicability::getVersionId, versionId));
        for (StandardApplicability a : old) {
            applicabilityDao.deleteById(a.getId());
        }
        for (Map<String, Object> r : rows) {
            StandardApplicability a = new StandardApplicability();
            a.setVersionId(versionId);
            a.setMaterialId(str(r.get("materialId")));
            a.setMaterialCode(str(r.get("materialCode")));
            a.setCategoryCode(str(r.get("categoryCode")));
            a.setProcessId(str(r.get("processId")));
            a.setSupplierId(str(r.get("supplierId")));
            a.setCustomerId(str(r.get("customerId")));
            a.setPriority(r.get("priority") != null ? Integer.parseInt(String.valueOf(r.get("priority"))) : 50);
            if (!hasText(a.getMaterialCode()) && !hasText(a.getCategoryCode())
                    && !hasText(a.getSupplierId()) && !hasText(a.getCustomerId()) && !hasText(a.getProcessId())) {
                throw new ServiceException(422, "适用范围维度不可全空");
            }
            applicabilityDao.insert(a);
        }
    }

    private InspectionStandard requireStandard(String id) {
        InspectionStandard s = standardDao.selectById(id);
        if (s == null) {
            throw new ServiceException(404, "标准不存在");
        }
        return s;
    }

    private StandardVersion requireVersion(String id) {
        StandardVersion v = versionDao.selectById(id);
        if (v == null) {
            throw new ServiceException(404, "标准版本不存在");
        }
        return v;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String fallback(String v, String def) {
        return hasText(v) ? v : def;
    }

    private static BigDecimal dec(Object o) {
        if (o == null || !hasText(String.valueOf(o))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "数值格式错误：" + o);
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
