package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * EDI 处理回执留存（spec edi-message-processing 回执留存要求，design D1/D4）。
 * 每次处理结论（成功/失败/部分成功/重复跳过）各留一条回执全文快照；
 * SENT_FLAG 预留送达标记（异步回执发送器立项前恒 0）。只 INSERT。
 */
@Getter
@Setter
@TableName("erp_intf_msg_receipt")
public class IntfMsgReceipt extends BaseEntity {

    /** 报文台账 ID */
    private String msgId;
    /** 报文编号（冗余便于按号查询） */
    private String msgNo;
    /** 处理结论 PROCESSED/PARTIAL/FAILED/SKIPPED 等 */
    private String conclusion;
    /** 回执全文快照（与台账同源组装） */
    private String receiptJson;
    /** 回执生成时间 */
    private LocalDateTime receiptAt;
    /** 送达标记（预留：恒 0） */
    private String sentFlag;
}
