package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 日报推送日志（逐通道，add-daily-report-subscription，spec bi-daily-report）。
 * SEND_STATUS=SENT/FAILED/DEGRADED（DEGRADED=双通道失败降级站内信送达）；
 * UNIQUE SUB_ID+PUSH_DATE+CHANNEL。
 */
@Getter
@Setter
@TableName("erp_bi_report_push_log")
public class ReportPushLog extends BaseEntity {

    public static final String CH_MAIL = "MAIL";
    public static final String CH_WECOM = "WECOM";
    public static final String CH_NOTICE = "NOTICE";

    public static final String ST_SENT = "SENT";
    public static final String ST_FAILED = "FAILED";
    public static final String ST_DEGRADED = "DEGRADED";

    private String subId;
    private String reportCode;
    private LocalDate pushDate;
    private String receiver;
    private String channel;
    private String sendStatus;
    private Integer retryCnt;
    private String degradedFlag;
    private LocalDate dataCutoff;
    private String qualityFlag;
    private String pdfRef;
    private String errorMsg;
}
