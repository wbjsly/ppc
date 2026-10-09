package com.erp.service.proc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.proc.ReturnOrder;
import com.erp.entity.qms.Ncr;

import java.util.List;
import java.util.Map;

/**
 * 退货单（spec quality-return，tasks 8.1~8.5，2.6.1 质量退货）。
 *
 * 链路（质量退货，BR-4.2-32）：NCR 评审选退货 → 自动带出草稿（PO/收货单/数量/PO 单价）
 *   → NCR 处置确认（退货单存在）→ 采购经理审批 → 退货出库（库存扣减锁定优先 / 未入库置行 REJECTED
 *   + 红字入库凭证 RV + 应付冲减 TODO-NOTIFY 桩 D4）→ 30 天补发/退款跟踪 → 闭环。
 * 手工退货（MANUAL）：已入库须关联原入库单与原入库单价（缺失 422），不打绩效扣分标记（D4）。
 */
public interface ReturnService {

    /** NCR 退货处置自动带出（tasks 8.1）：同事务建 DRAFT，幂等（已有则返回既有） */
    ReturnOrder createFromNcr(Ncr ncr);

    /** 手工发起（已入库须关联原入库单 ORIGIN_DOC_NO + 原入库单价，缺失 422） */
    /** 非质量退货创建（spec other-return）：PO 关联 + 后端反查计价 + 结构化原因 */
    ReturnOrder createManual(Map<String, Object> body);

    /** PO 可退入库带出：POSTED 收货行聚合（入库凭证/批次/原入库单价/可退库存量），空 422 */
    java.util.List<Map<String, Object>> poReturnables(String poId);

    /**
     * 红字凭证台账（2.6.3，spec red-receipt-voucher）：只读聚合 OUT_DONE 退货的红字入库凭证，
     * 过滤凭证号/退货单号/供应商/来源类型/出库日期区间；行内嵌退货行明细与 PO/入库凭证/NCR 关联链。
     */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<Map<String, Object>> redVouchers(
            long current, long size, String redDocNo, String returnNo, String supplierId,
            String sourceType, String dateFrom, String dateTo);

    /** 提交采购经理审批（底座 Return，单签 ROLE_PM）；驳回 → DRAFT 可重提 */
    ReturnOrder submit(String id);

    /** 作废（仅 DRAFT/REJECTED；OUT_DONE 只读归档禁作废） */
    ReturnOrder cancel(String id, String reason);

    /**
     * 退货出库（tasks 8.3，单事务）：仅 APPROVED；
     * 库存扣减（QC 锁定量优先、不足 422）/ 未入库批次置 GR 行 REJECTED；
     * 生成红字入库凭证 RV 号 + 应付冲减 TODO-NOTIFY 桩 + NCR 处置推进 + 30 天跟踪起算。
     */
    ReturnOrder posting(String id);

    /** 跟踪闭环（补发/退款完成，留痕关闭） */
    ReturnOrder closeTrack(String id, String remark);

    /** 跟踪逾期扫描（tasks 8.4：到期未闭环 → OVERDUE 标记 + 提醒日志，幂等） */
    int sweepTrackOverdue();

    Page<Map<String, Object>> page(long current, long size, String keyword, String status, String sourceType);

    /** 详情：退货单 + 行 + 审批 + NCR + 跟踪 */
    Map<String, Object> detail(String id);

    /** NCR 关联退货单号（表级直查，供 NcrService 校验用） */
    List<String> nosByNcr(String ncrId);
}
