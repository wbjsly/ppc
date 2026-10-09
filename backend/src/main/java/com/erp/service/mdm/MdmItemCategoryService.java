package com.erp.service.mdm;

import com.erp.entity.mdm.MdmItemCategory;
import com.erp.entity.mdm.MdmItemCategoryVersion;

import java.util.List;
import java.util.Map;

/**
 * 物料分类维护业务能力契约（基础数据-物料管理 1.2.3，3 级树 + 受限轻量重构）。
 */
public interface MdmItemCategoryService {

    /** 管理页树形查询（全部状态，含 children） */
    List<MdmItemCategory> tree();

    /** 新建分类（4 位码全局唯一、前缀 FG/RM/WIP、深度 ≤3、成环校验） */
    MdmItemCategory create(MdmItemCategory category);

    /**
     * 变更：名称/父节点/责任人/备注可改；ITEM_PREFIX 与 CATEGORY_CODE 锁定（422）；
     * 原因必填；父节点变更过成环+深度；V(N+1) 快照 + 乐观锁。
     */
    MdmItemCategory update(MdmItemCategory category);

    /** 停用双重阻断：启用子孙清单 / 启用物料清单（前10+总数） */
    void disable(String id);

    /**
     * 合并（受限）：仅同 ITEM_PREFIX；子分类与存量物料批量改挂目标（物料逐条版本快照）；
     * 源分类停用；单事务；原因必填；受影响物料 >5000 需 confirmLarge。
     */
    void merge(String sourceId, String targetId, String reason, boolean confirmLarge);

    /** 合并影响面：{itemCount, childCount, samePrefix}（执行前提示） */
    Map<String, Object> mergeImpact(String sourceId, String targetId);

    /** 迁移：改挂父节点（成环/深度），物料与编码不动，原因必填，MOVE 快照 */
    void move(String id, String targetParentId, String reason);

    List<MdmItemCategoryVersion> versions(String entityId);

    Map<String, Object> diff(String entityId, int from, int to);
}
