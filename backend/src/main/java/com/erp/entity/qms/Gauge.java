package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

/**
 * 计量器具台账（spec gauge-calibration）：CTQ 用器具周期 ≤12 个月；
 * 状态 VALID/LIMITED/EXPIRED/DISABLED；停用触发可疑批次反向追溯（BR-4.12-06）。
 */
@Getter
@Setter
@TableName("erp_qms_gauge")
public class Gauge extends BaseEntity {
    /** 器具编码（唯一，创建后不可改） */
    @TableField("GAUGE_CODE")
    private String gaugeCode;

    /** 器具名称 */
    @TableField("NAME")
    private String name;

    /** 类别 */
    @TableField("CATEGORY")
    private String category;

    /** 精度等级 */
    @TableField("ACCURACY_LEVEL")
    private String accuracyLevel;

    /** 校准周期（月） */
    @TableField("CAL_CYCLE_MONTHS")
    private Integer calCycleMonths;

    /** 1=用于 CTQ 项检验 */
    @TableField("CTQ_FLAG")
    private String ctqFlag;

    /** 责任部门 */
    @TableField("DEPT")
    private String dept;

    /** 存放位置 */
    @TableField("LOCATION")
    private String location;

    /** 责任人 */
    @TableField("OWNER_NAME")
    private String ownerName;

    /** VALID / LIMITED / EXPIRED / DISABLED */
    @TableField("STATUS")
    private String status;

    /** 上次校准日 */
    @TableField("LAST_CAL_DATE")
    private LocalDate lastCalDate;

    /** 下次校准日 */
    @TableField("NEXT_CAL_DATE")
    private LocalDate nextCalDate;

    /** 限用范围（如仅非 CTQ 项） */
    @TableField("LIMIT_SCOPE")
    private String limitScope;

}
