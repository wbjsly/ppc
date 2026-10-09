package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SPC 告警（S-4.12-02 规则版）：OVER_LIMIT 超控制限 / SAME_SIDE 连续 7 点同侧 /
 * TREND 连续 7 点上升下降；未处置在看板持续标红。
 */
@Getter
@Setter
@TableName("erp_qms_spc_alert")
public class SpcAlert extends BaseEntity {
    /** SA + yyyyMMdd + - + 6 位流水 */
    @TableField("ALERT_NO")
    private String alertNo;

    /** 特性编码 */
    @TableField("CHAR_CODE")
    private String charCode;

    /** 特性名称 */
    @TableField("CHAR_NAME")
    private String charName;

    /** OVER_LIMIT / SAME_SIDE / TREND */
    @TableField("RULE_CODE")
    private String ruleCode;

    /** 规则名称 */
    @TableField("RULE_NAME")
    private String ruleName;

    /** 命中值 */
    @TableField("HIT_VALUE")
    private java.math.BigDecimal hitValue;

    /** 命中数据快照 */
    @TableField("DATA_SNAPSHOT")
    private String dataSnapshot;

    /** OPEN / CLOSED */
    @TableField("STATUS")
    private String status;

    /** 推送对象 */
    @TableField("PUSH_TO")
    private String pushTo;

    /** 处置人 */
    @TableField("HANDLE_BY")
    private String handleBy;

    /** 处置时间 */
    @TableField("HANDLE_TIME")
    private LocalDateTime handleTime;

    /** 处置记录 */
    @TableField("HANDLE_RESULT")
    private String handleResult;

    /** 1=超期未处置 */
    @TableField("OVERDUE_FLAG")
    private String overdueFlag;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}
