package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 抽取批次执行记录（add-data-extract-console design D1/D2，spec bi-extract-console）。
 * 字段语义对齐 erp_bi_extract_task（机制借鉴）：波动对照 / 断点水位 / 重试上限。
 */
@Getter
@Setter
@TableName("erp_bi_extract_run")
public class BiExtractRun extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_READY = "READY";
    /** 波动 >10 倍暂停待人工确认（BR-4.10-12） */
    public static final String ST_PAUSED = "PAUSED";
    public static final String ST_FAILED = "FAILED";

    private LocalDate batchDate;
    private String batchNo;
    private String configId;
    private Integer seq;
    private String status;
    private Long rowCount;
    private Long prevRowCount;
    private String watermark;
    private Integer retry;
    private String errorMsg;
    private Boolean manualConfirm;
    private Long elapsedMs;
    private LocalDateTime finishedAt;
}
