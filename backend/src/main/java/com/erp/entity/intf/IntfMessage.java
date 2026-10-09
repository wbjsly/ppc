package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** EDI 报文台账（spec edi-message-processing，design D4：RECEIVED 独立事务先落，业务回滚不抹留痕）。 */
@Getter
@Setter
@TableName("erp_intf_message")
public class IntfMessage extends BaseEntity {

    public static final String ST_RECEIVED = "RECEIVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_PROCESSED = "PROCESSED";
    public static final String ST_PARTIAL = "PARTIAL";
    public static final String ST_FAILED = "FAILED";
    /** 重试耗尽待人工（C-4.9-02） */
    public static final String ST_MANUAL = "MANUAL";

    public static final String TYPE_ORDERS = "ORDERS";
    public static final String TYPE_DESADV = "DESADV";
    public static final String TYPE_INVOIC = "INVOIC";

    private String msgNo;
    private String partnerCode;
    private String msgType;
    private String msgVersion;
    /** 调用方+类型+业务单号+版本（唯一） */
    private String idempotencyKey;
    private String rawPayload;
    private String payloadHash;
    /** OPEN 开放入口 / UPLOAD 上传 / SIMULATE 模拟推送 */
    private String source;
    private String status;
    private String syntaxResult;
    private String mapResult;
    private String bizResult;
    private String bizType;
    private String bizNo;
    private Integer lineTotal;
    private Integer lineOk;
    private Integer lineFail;
    private Integer retryCount;
    private LocalDateTime receivedAt;
    private LocalDateTime finishedAt;
    private Boolean receiptSent;
    private LocalDateTime receiptSentAt;
    private String ticketId;
    private String remark;
}
