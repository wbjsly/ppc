package com.erp.service.mrp;

import com.erp.entity.mrp.MrpOperation;

import java.util.List;
import java.util.Map;

/**
 * 工序字典（change add-routing-management，spec routing-management「工序字典维护」；菜单 5.2.1）。
 * 全局复用：编码创建后不可修改、禁止硬删除（仅停用）；停用工序不可选入新路线行。
 * 角色：ROLE_PROCESS_ENG / ROLE_PROCESS_MGR 维护，写操作放行 ADMIN。
 */
public interface OperationService {

    /** 列表：关键字（编码/名称）与状态筛选，编码升序 */
    List<Map<String, Object>> query(String keyword, String status);

    /** 新增：编码/名称必填、编码全局唯一（重复 422） */
    MrpOperation create(MrpOperation in);

    /** 修改：编码不可改（改码 422）；名称/技能要求/状态可改 */
    MrpOperation update(MrpOperation in);

    /** 启用/停用切换（'1'/'0'） */
    MrpOperation setStatus(String id, String status);
}
