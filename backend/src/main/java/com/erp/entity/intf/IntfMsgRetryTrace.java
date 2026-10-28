package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * EDI 语法层重试轨迹（spec edi-message-processing 留痕要求，design D1）。
 * 逐次留痕不可改删：每次语法校验失败（对方重发触发重处理）各插一行，
 * 不随下次重发覆盖；服务层只 INSERT，不提供修改/删除。
 */
@Getter
@Setter
@TableName("erp_intf_msg_retry_trace")
public class IntfMsgRetryTrace extends BaseEntity {

    /** 报文台账 ID（erp_intf_message.ID） */
    private String msgId;
    /** 尝试序号（第 N 次，自 1 起，= 该次失败后的 retryCount） */
    private Integer attemptNo;
    /** 本次语法校验失败时间 */
    private LocalDateTime failAt;
    /** 错误位置清单快照（行号+段号+字段号数组 JSON） */
    private String errorsJson;
}
