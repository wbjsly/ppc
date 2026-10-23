package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamTransferOrder;

import java.util.List;
import java.util.Map;

/**
 * 资产调拨（add-asset-transfer-lease，spec asset-transfer-lease）。
 * DEPT 部门间 / CROSS_LE 跨法人：BR-21 前置校验 → 四节点审批 → 交接双确认 →
 * 完成即改卡片（BR-22 当月归属回溯）；跨法人加内部交易双凭证 + C-10 卡控（BR-4.13-23）。
 */
public interface EamTransferService {

    Page<EamTransferOrder> page(long current, long size, String transferType,
                                String status, String cardNo);

    Map<String, Object> detail(String id);

    /** 创建调拨申请（FROM/TO 快照落单 + BR-21 四前置聚合校验） */
    Map<String, Object> create(EamTransferOrder order);

    /** 提交审批：DEPT 三节点 / CROSS_LE 四节点（追加财务经理） */
    Map<String, Object> submit(String id);

    /** 审批通过（回调入口）：置 APPROVED 待交接 */
    Map<String, Object> onApprovalPassed(String id);

    /** 审批驳回（回调入口）：回 DRAFT 留因 */
    Map<String, Object> onApprovalRejected(String id, String reason);

    /** 交接确认（direction=OUT/IN，各留确认人时间；双齐置 HANDOVERED） */
    Map<String, Object> handoverConfirm(String id, String direction);

    /** 完成调拨：交接双确认校验 → 即改卡片 → CROSS_LE 内部交易（同事务） */
    Map<String, Object> completeFromApproval(String id);

    /** C-4.13-10 月结卡控：CROSS_LE 完成但单据/双凭证不齐 → 422 列清单 */
    Map<String, Object> assertIntercoComplete();

    /** 闲置共享池（BR-4.13-05/C-4.13-11）：12 月入池可调拨 / 24 月处置评估待办 */
    List<Map<String, Object>> idlePool();
}
