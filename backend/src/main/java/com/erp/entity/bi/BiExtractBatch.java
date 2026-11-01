package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 抽取批次（add-data-extract-console design D1，spec bi-extract-console）。
 * 数据就绪标志载体：全部 run READY 且无 SEVERE 质量阻断 → READY（依赖方前置）。
 */
@Getter
@Setter
@TableName("erp_bi_extract_batch")
public class BiExtractBatch extends BaseEntity {

    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_READY = "READY";
    /** SEVERE 级质量校验失败阻断就绪（BR-4.10-14） */
    public static final String ST_QUALITY_BLOCKED = "QUALITY_BLOCKED";
    public static final String ST_FAILED = "FAILED";

    private String batchNo;
    private LocalDate batchDate;
    private String status;
    private LocalDateTime readyAt;
    private String remark;
}
