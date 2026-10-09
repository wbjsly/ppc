package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * IPQC 巡检计划（task 5.10，偏差 D5）：到点自动生成 IPQC 检验批（sourceType=PLAN）。
 * 首件/工序报工触发为 5.x 工单域桩，本期以手工 + 计划触发落地。
 */
@Getter
@Setter
@TableName("erp_qms_patrol_plan")
public class PatrolPlan extends BaseEntity {

    /** PAT + yyyyMMdd + - + 6 位流水 */
    private String planCode;
    private String name;
    /** IPQC */
    private String lotType;
    private String itemCode;
    private String itemName;
    private String processId;
    private String processName;
    /** 触发间隔（分钟） */
    private Integer intervalMinutes;
    /** 每次生成批量 */
    private BigDecimal lotQty;
    private LocalDateTime nextRunTime;
    /** ACTIVE / PAUSED / DONE */
    private String status;
    private LocalDateTime lastRunTime;
    private String lastLotNo;
    private String remark;
}
