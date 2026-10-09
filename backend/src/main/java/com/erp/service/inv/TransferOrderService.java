package com.erp.service.inv;

import com.erp.entity.inv.InvTransferOrder;
import com.erp.entity.inv.InvTransferOrderLine;

import java.util.List;
import java.util.Map;

/**
 * 调拨单（4.12.1 仓间调拨 / 4.12.2 在途跟踪 / 4.5.3 调拨出库 / 4.4.4 调拨入库共享实体，
 * spec transfer-order）。三入口共享同一后端动作（design P1/D3）。
 */
public interface TransferOrderService {

    /** 创建（DRAFT）：调出≠调入、行内部转移价必填 >0、LE 解析与 CROSS_LE 标记 */
    Map<String, Object> create(InvTransferOrder head, List<InvTransferOrderLine> lines);

    /** 编辑（仅 DRAFT，整单行重建） */
    Map<String, Object> update(String id, InvTransferOrder head, List<InvTransferOrderLine> lines);

    /** 作废（仅 DRAFT，原因必填） */
    void cancel(String id, String reason);

    /** 出库段过账：DRAFT → OUT_POSTED（引擎 TRANSFER_OUT；跨法人核算由 3.3 接入） */
    Map<String, Object> postOut(String id);

    /** 入库段过账：OUT_POSTED → IN_POSTED（引擎 TRANSFER_IN，按 ALLOC_JSON 批次入账） */
    Map<String, Object> postIn(String id);

    /** 关闭：IN_POSTED → CLOSED（跨法人先过核销校验，422 返回未核销清单） */
    Map<String, Object> close(String id);

    /** 详情（头 + 行 + 分配明细） */
    Map<String, Object> detail(String id);

    /** 4.12.1 调拨单列表（status/keyword 过滤，终态含 CANCELLED） */
    Map<String, Object> page(long current, long size, String status, String keyword);

    /** 4.12.2 在途列表（仅 OUT_POSTED，含在途天数与挂起标记） */
    Map<String, Object> intransitPage(long current, long size, String keyword);

    /** 在途超期扫描（C-4.4-09）：超 TRANSIT_ALERT_DAYS 未入库 → 置挂起 + 通知双方主管，幂等 */
    int sweepTransitOverdue();
}
