package com.erp.service.inv;

import java.util.Map;

/**
 * 效期优先（4.6.2，spec outbound-strategy）：黄/橙/红三级预警清单与锁定标识。
 * 效态判定 = 每日扫描落位 + 当日实时计算兜底（不回写），处置任务归 4.10（不在本能力范围）。
 */
public interface ExpiryPriorityService {

    /**
     * 效期预警清单（FR-4.4-3-6）：剩余 ≤90 黄 / ≤60 橙 / ≤30 红；
     * 已过锁定线的批次带 locked 标识；非批次管理物料与无有效期批次不返回。
     * 筛选：物料 / 仓库（有库存的仓库）/ 预警等级 / 是否锁定；分页。
     */
    Map<String, Object> warnings(String itemCode, String warehouseCode, String level,
                                 Boolean locked, long current, long size);
}
