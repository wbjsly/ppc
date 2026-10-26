package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 工作中心台账（change add-routing-management，spec routing-management / 00-erp-spec L2357/L2714；迁移 118）。
 * 产能三要素（CAL_HOURS×EQUIP_AVAIL×LABOR_AVAIL）= 派工负荷率分母（公式 4，5.6 消费）；
 * 外协类型必须维护供应商（C-4.5-05 数据源头 L1）；停用不可选入新路线行（FR-4.5-3-4）。
 * 工时费率不入本表（财务 4.6，偏差 D7）。
 */
@Getter
@Setter
@TableName("erp_mrp_work_center")
public class MrpWorkCenter extends BaseEntity {

    public static final String ST_ACTIVE = "1";
    public static final String ST_INACTIVE = "0";
    public static final String TYPE_INTERNAL = "INTERNAL";
    public static final String TYPE_OUTSOURCED = "OUTSOURCED";

    /** 工作中心编码（创建后不可修改） */
    private String wcCode;
    private String wcName;
    /** 类型 INTERNAL内部/OUTSOURCED外协 */
    private String wcType;
    /** 外协供应商编码（OUTSOURCED 必填） */
    private String supplierCode;
    /** 日历工时（小时/日，须 > 0） */
    private BigDecimal calHours;
    /** 设备可用率%（0~100） */
    private BigDecimal equipAvail;
    /** 人员出勤率%（0~100） */
    private BigDecimal laborAvail;
    /** 状态 1启用/0停用 */
    private String status;
}
