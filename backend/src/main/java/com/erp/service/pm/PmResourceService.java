package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 资源负载与产能（project-initiation，FR-4.8-2-5；11.3.2 页内 Tab）。 */
public interface PmResourceService {

    /**
     * 人员负载视图（现算）：按人×日汇总已分配任务日均工时；超产能/跨项目重叠 → warning。
     * @param from 起始日期（含）
     * @param to   结束日期（含），跨度上限 366 天
     */
    List<Map<String, Object>> loadView(String from, String to);

    /** 产能日历行清单（按用户与日期区间） */
    List<Map<String, Object>> capacityList(String userId, String from, String to);

    /** 保存/覆盖产能行（PMO/ADMIN 门禁在控制器层） */
    Map<String, Object> saveCapacity(Map<String, Object> payload);
}
