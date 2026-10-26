package com.erp.service.mrp;

import com.erp.entity.mrp.MrpWorkCenter;

import java.util.List;
import java.util.Map;

/**
 * 工作中心台账（change add-routing-management，spec routing-management「工作中心台账」；菜单 5.2.2）。
 * 外协类型必须维护供应商（C-4.5-05 数据源头 L1）；日历工时 > 0、设备可用率/人员出勤率 0~100；
 * 停用不可选入新路线行（FR-4.5-3-4）。产能三要素供 5.6 负荷率分母（公式 4）。
 * 角色：ROLE_PROCESS_ENG / ROLE_PROCESS_MGR 维护，写操作放行 ADMIN。
 */
public interface WorkCenterService {

    /** 列表：关键字（编码/名称）与状态筛选，编码升序 */
    List<Map<String, Object>> query(String keyword, String status);

    /** 新增：编码/名称必填、编码全局唯一；外协缺供应商/非法产能值 422 */
    MrpWorkCenter create(MrpWorkCenter in);

    /** 修改：编码不可改（改码 422）；校验同 create */
    MrpWorkCenter update(MrpWorkCenter in);

    /** 启用/停用切换（'1'/'0'） */
    MrpWorkCenter setStatus(String id, String status);
}
