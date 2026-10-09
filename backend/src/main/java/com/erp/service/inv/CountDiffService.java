package com.erp.service.inv;

import java.util.List;
import java.util.Map;

/**
 * 盘点差异台账与监控清单（4.11.3，spec count-management 盘点菜单权限 / 报告监控仓位）。
 * DIFF_TYPE=COUNT 视图（与 PICK 视图隔离——picking-review MODIFIED 边界）。
 */
public interface CountDiffService {

    /** COUNT 差异台账分页（状态/任务号/物料筛选） */
    Map<String, Object> page(String status, String taskNo, String itemCode,
                             long current, long size);

    /**
     * 监控仓位清单（BR-4.4-41 首期口径）：跨任务聚合——同仓位出现 ≥2 张超容差 COUNT 差异
     * （对应"连续两次超容差"）即入清单并通知仓库主管（通知由调用方在生成时触发）。
     */
    List<Map<String, Object>> watchList();
}
