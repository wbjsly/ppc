package com.erp.service.mdm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmCustomerGroupVersion;
import com.erp.entity.mdm.MdmCustomerView;
import com.erp.entity.mdm.MdmCustomerViewVersion;

import java.util.List;
import java.util.Map;

/**
 * 客户准入业务能力契约（基础数据-客户管理 1.3.1，流程六 FR-4.1-6-1/6-2）。
 */
public interface MdmCustomerService {

    // ---------- 集团视图（FR-4.1-6-1） ----------

    /** 分页：keyword 匹配编码/名称/税号，status 精确（可空） */
    Page<MdmCustomerGroup> groupPage(long current, long size, String keyword, String status);

    MdmCustomerGroup getGroup(String id);

    /**
     * 建档：CUST-NNNN 自动编码（创建后不可改）；
     * 税号被其它集团视图占用 → 422 引导合并（BR-4.1-30）；
     * 名称编辑距离 ≤3 → 409+最近3条，forceCreate+dupNote 放行。
     */
    MdmCustomerGroup createGroup(MdmCustomerGroup group, boolean forceCreate);

    /** 变更：编码锁定 422，原因必填，UPDATE 快照 */
    MdmCustomerGroup updateGroup(MdmCustomerGroup group);

    List<MdmCustomerGroupVersion> groupVersions(String entityId);

    Map<String, Object> groupDiff(String entityId, int from, int to);

    // ---------- 法人视图（FR-4.1-6-2） ----------

    List<MdmCustomerView> viewsByGroup(String groupId);

    /**
     * 挂载/变更法人视图：法人主体参照启用校验、同集团同法人唯一（422）、
     * Σ额度×ratio ≤ 集团总额度（BR-4.1-31，总额度空放行+提示）
     */
    MdmCustomerView saveView(MdmCustomerView view);

    List<MdmCustomerViewVersion> viewVersions(String entityId);

    // ---------- 状态机（BR-4.1-34 冻结级联；已合并终态） ----------

    /** 停用/启用（软删语义，影响面为下游桩 downstreamStub） */
    void changeGroupStatus(String id, String toStatus, String reason);

    /** 冻结/解冻：级联法人视图（冻结 1→2，解冻 2→1，不覆盖停用） */
    void freezeGroup(String id, String reason);

    void unfreezeGroup(String id, String reason);

    /** 影响分析：法人视图数 + 下游 SO 引用桩（downstreamStub 明示） */
    Map<String, Object> impact(String id);

    // ---------- 合并（C-4.1-08 降级：原因+影响面，无复核流） ----------

    /** 合并候选（税号相同或名称编辑距离 ≤3，排除自身与已合并） */
    List<MdmCustomerGroup> mergeCandidates(String keyword, String excludeId);

    /**
     * 合并：单事务 = 法人视图改挂 → 源 STATUS='3'+MERGED_TO → 双方 MERGE 快照。
     * 源限 1/0/2（已合并 422），原因必填；已合并为终态禁回退（C-4.1-10）。
     */
    void merge(String sourceId, String targetId, String reason);
}
