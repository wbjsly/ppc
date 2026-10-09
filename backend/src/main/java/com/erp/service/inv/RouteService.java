package com.erp.service.inv;

import com.erp.entity.inv.InvRoute;

import java.util.List;
import java.util.Map;

/**
 * 配送线路主数据（4.8.1，spec wave-management 配送线路主数据）：
 * 分页查询、编码判重、新增/编辑、停用退役（C-0-05 不物理删除）。
 * 写限 ADMIN/WAREHOUSE（服务层二次校验）。
 */
public interface RouteService {

    /** 分页：关键词（编码/名称模糊）+ 状态 */
    Map<String, Object> page(String keyword, String status, long current, long size);

    /** 全量（启用优先）：客户档案上拉选与聚类展示用 */
    List<InvRoute> listActive();

    /** 新增（编码唯一，重复 409；状态默认 ACTIVE） */
    Map<String, Object> create(InvRoute route);

    /** 编辑（编码不可改；仅名称/状态/备注） */
    Map<String, Object> update(String id, InvRoute route);

    /** 停用/启用（INACTIVE=退役语义，已被客户挂线的存量不受影响） */
    Map<String, Object> changeStatus(String id, String status);
}
