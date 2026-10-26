package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 试产数据采集表（change add-trial-production-management，spec trial-execution
 * FR-4.5-8-5 逐工序采集；迁移 125）。UK(MO_ID, OP_SEQ)。
 * 结案完整性卡控数据源：全部 DONE 工序缺采集行 → 关单 L1 阻断。
 */
@Getter
@Setter
@TableName("erp_mrp_trial_data")
public class TrialData extends BaseEntity {

    /** 试产工单 ID */
    private String moId;
    /** 试产工单号 TP-（冗余查询） */
    private String moNo;
    /** 工序序号（工单工序快照行） */
    private Integer opSeq;
    /** 工序编码（冗余展示） */
    private String opCode;
    /** 工序名称（冗余展示） */
    private String opName;
    /** 工艺参数（JSON 键值数组：参数名/标准值/实测值） */
    private String params;
    /** 不良模式（多行文本，每行一条） */
    private String defectModes;
    /** 实测工时（缺省带出 5.7 CONFIRMED 报工工时可改，偏差 D7） */
    private BigDecimal actualHours;
    private String remark;
}
