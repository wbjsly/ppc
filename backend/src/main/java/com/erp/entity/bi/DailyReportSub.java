package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日报订阅关系（add-daily-report-subscription，spec bi-daily-report）。
 * CHANNELS_JSON=["MAIL","WECOM","NOTICE"]；退订=STATUS 置 DISABLED 留痕；
 * LAST_PROMPT_AT 用于 30 天未阅读 L4 提示去重。
 */
@Getter
@Setter
@TableName("erp_bi_daily_report_sub")
public class DailyReportSub extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_DISABLED = "DISABLED";

    private String reportId;
    private String userName;
    private String channelsJson;
    private String status;
    private LocalDate lastPushDate;
    private LocalDateTime lastReadAt;
    private LocalDateTime lastPromptAt;
}
