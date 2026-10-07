package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 抽取任务批次（spec bi-data-pipeline，design D9：DAG 层序 + 水位 + READY）。 */
@Getter
@Setter
@TableName("erp_bi_extract_task")
public class BiExtractTask extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_READY = "READY";
    /** 波动 >10 倍暂停待人工确认 */
    public static final String ST_PAUSED = "PAUSED";
    public static final String ST_FAILED = "FAILED";

    public static final String TYPE_PO = "PO";
    public static final String TYPE_GR = "GR";
    public static final String TYPE_AP = "AP";
    public static final String TYPE_COST = "COST_SNAPSHOT";
    public static final String TYPE_ALERT = "PRICE_ALERT";
    public static final String TYPE_SAVE = "COST_SAVE";

    private String batchNo;
    private LocalDate batchDate;
    private String taskType;
    private Integer seq;
    private String status;
    private Long rowCount;
    private Long prevRowCount;
    private String watermark;
    private Integer retry;
    private String errorMsg;
    private Boolean manualConfirm;
    private LocalDateTime finishedAt;
    private String remark;
}
