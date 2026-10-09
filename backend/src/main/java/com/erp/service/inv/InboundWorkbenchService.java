package com.erp.service.inv;

import java.util.Map;

/**
 * 入库作业台（spec inbound-workbench，4.4.1~4.4.4 类型参数化）。
 * 视图 + 动作入口：按类型聚合来源单据头（不建独立入库单，design D7 防双真身）；
 * PURCHASE_IN 读收货单（检验批状态联动），RETURN_IN 读销售退货单（镜像，流程留既有页面），
 * 未建域类型返回空态（WIP_IN/TRANSFER_IN 等，偏差 D5 骨架）。
 */
public interface InboundWorkbenchService {

    /**
     * 按类型取入库任务。
     * 返回 {type:{code,name,direction,enabled}, rows, total, asOf}；
     * 类型不存在 422；类型停用 → enabled=false + rows 空（页面隐藏发起入口）。
     */
    Map<String, Object> tasks(String typeCode, String keyword, String status,
                              long current, long size);
}
