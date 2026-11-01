package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 日报阅读回执（add-daily-report-subscription，spec bi-daily-report）。
 * 「阅读」=打开日报详情页；UNIQUE PUSH_LOG_ID；OPEN_CNT 累计打开次数。
 */
@Getter
@Setter
@TableName("erp_bi_report_receipt")
public class ReportReceipt extends BaseEntity {

    private String pushLogId;
    private Integer openCnt;
    private LocalDateTime openAt;
}
