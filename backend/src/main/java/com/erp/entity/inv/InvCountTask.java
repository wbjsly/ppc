package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 盘点任务头（4.11，spec count-management 任务状态机；迁移 114）。
 * CYCLE 周期盘点（4.11.1 手工选范围）/ FULL 全面盘点（4.11.2 一键全仓）共用状态机：
 * COUNTING（生成即锁仓，BOOK_QTY 定格）→ ADJUSTING（差异单挂审批）→ DONE（报告物化）。
 */
@Getter
@Setter
@TableName("erp_inv_count_task")
public class InvCountTask extends BaseEntity {

    /** 周期盘点（4.11.1 手工筛选范围） */
    public static final String T_CYCLE = "CYCLE";
    /** 全面盘点（4.11.2 一键全仓） */
    public static final String T_FULL = "FULL";

    /** 生成即进入（首期无 DRAFT 停留）——行 BOOK_QTY 定格 + 仓位锁生效 */
    public static final String ST_COUNTING = "COUNTING";
    /** 超容差差异单挂审批中 */
    public static final String ST_ADJUSTING = "ADJUSTING";
    /** 全部行闭环（ADJUSTED），报告已物化 */
    public static final String ST_DONE = "DONE";

    /** 任务号 COUNT+yyMMdd+4位日流水 */
    private String taskNo;

    /** CYCLE / FULL */
    private String taskType;

    private String warehouseCode;

    private String status;

    private Integer totalLines;

    private Integer countedLines;

    private Integer adjustedLines;

    /** DONE 时物化的报告 JSON（design D8） */
    private String reportJson;

    private String remark;
}
