package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报工投料行（change add-reporting-management，spec shop-floor-reporting「投料记录与即扣」，
 * design D3，迁移 123）。FLOW_TYPE 区分：HAND 手工投料（保存即扣 AVAILABLE_QTY）/
 * BFLUSH 完工倒冲补投（差额防御公式产物，冲销反向恢复的判断依据，design D8）。
 */
@Getter
@Setter
@TableName("erp_mrp_feed")
public class MrpFeed extends BaseEntity {

    public static final String FLOW_HAND = "HAND";
    public static final String FLOW_BFLUSH = "BFLUSH";

    private String rptId;
    private String moId;
    /** 工序序号（NULL=工单级投料，FR-4.5-5-2） */
    private Integer opSeq;
    private String itemCode;
    private String itemName;
    /** 批次（扫码批号文本命中，偏差 D3） */
    private String batchCode;
    /** 投料数量（>0，保存即扣） */
    private BigDecimal qty;
    private String flowType;
    /** 倒冲差额参照的 POSTED 领料单号（防御公式审计） */
    private String sourceIssueNo;
    /** 冲销已回加 1（reverseFeeds 幂等锚点，防双退） */
    private String returnedFlag;
    private LocalDateTime feedAt;
}
