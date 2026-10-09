package com.erp.service.vmi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/**
 * 领料出库（4.5.2 + 2.8.1 寄售领用共用内核，spec material-issue，design D5/D6）。
 * 领料单 DRAFT → POSTED | CANCELLED 两步流；OWN 分支扣自有可用量，
 * VMI 分支执行物权转移（FIFO 明细 / 领用时点协议价 / 转自有凭证 / createFromVmiTransfer 暂估）。
 */
public interface MaterialIssueService {

    /** FIFO 配批预检（创建对话框预览，不落库）：自有按 AVAILABLE、寄售按 vmi_stock.QTY */
    Map<String, Object> preview(Map<String, Object> payload);

    /** 创建领料单（FIFO 配批固化 + 可用量预检，不足 422；状态 DRAFT） */
    Map<String, Object> create(Map<String, Object> payload);

    /** 分页（类型/状态/工单号/关键字筛选） */
    Page<Map<String, Object>> page(long current, long size, String issueType, String status,
                                   String workOrderNo, String keyword);

    /** 详情（头 + 行） */
    Map<String, Object> detail(String id);

    /** 过账（单事务：逐批复核 → OWN 扣减 / VMI 物权转移 → POSTED；失败整体回滚） */
    Map<String, Object> post(String id);

    /** 作废（仅 DRAFT，原因必填；库存无变动） */
    void cancel(String id, String reason);
}
