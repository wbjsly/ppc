package com.erp.service.qms;

import com.erp.entity.qms.InspectionStandard;
import com.erp.entity.qms.SamplingPlan;
import com.erp.entity.qms.StandardVersion;

import java.util.List;
import java.util.Map;

/**
 * 检验标准库（6.1 标准管理，spec inspection-standard，4.12.2.1 五对象）。
 * 编码创建后不可改；已发布版本只读（BR-4.12-57）；发布走审批（CTQ 双签）；
 * 退役仅阻止新任务（BR-4.12-58）；任务生成时经 #resolveFor 固化快照（BR-4.12-56）。
 */
public interface InspectionStandardService {

    // ---------- 标准头 ----------

    Map<String, Object> page(long current, long size, String keyword, String status);

    InspectionStandard create(InspectionStandard standard);

    InspectionStandard update(String id, InspectionStandard standard);

    Map<String, Object> detail(String id);

    /** 标准退役（停用不删，仅阻止新任务引用） */
    InspectionStandard retire(String id);

    // ---------- 版本 / 特性 / 适用范围 ----------

    /** 新建草稿版本（版本号 = 现行 +1；同时带检验特性与适用范围） */
    Map<String, Object> createVersion(String standardId, Map<String, Object> body);

    /** 更新草稿版本（特性与适用范围整体覆盖） */
    Map<String, Object> updateVersion(String versionId, Map<String, Object> body);

    /** 提交发布：结构校验 + 生效区间冲突检测 → 进入审批（CTQ 双签） */
    Map<String, Object> submitPublish(String versionId);

    List<Map<String, Object>> characteristics(String versionId);

    /**
     * 适用范围命中：优先级 + 生效日期唯一确定已发布版本。
     * 命中返回 {version, applicabilityBasis}；无命中返回空 Map（调用方按 BR-4.12-07 阻断）。
     */
    Map<String, Object> resolveFor(String materialCode, String categoryCode, String supplierId, String customerId, String processId);

    // ---------- 抽样方案（6.1.4） ----------

    List<SamplingPlan> samplingPlans();

    SamplingPlan saveSamplingPlan(SamplingPlan plan);
}
