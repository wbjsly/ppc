package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 设备巡检执行记录（13.3 扫码签到/逐项点检/异常标记/拍照留证） */
@Data
@TableName("erp_iot_inspect_record")
public class IotInspectRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String ST_OPEN = "OPEN";
    public static final String ST_SUBMITTED = "SUBMITTED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 来源 QMS 巡检计划编码 */
    private String planCode;

    /** 被检设备（扫码签到） */
    private String deviceNo;

    private LocalDateTime checkinAt;
    private String checkinBy;

    /** 逐点检项 JSON [{item, value, unit, abnormal, remark}] */
    private String itemsJson;

    private String abnormalFlag;

    /** 异常标记关联 erp_iot_exception */
    private String exceptionId;

    /** 拍照留证 JSON 数组（缺图提交校验提示补拍） */
    private String photoUrls;

    private String status;

    /** 超时漏检提醒 */
    private String overdueFlag;

    private LocalDateTime dueAt;
    private LocalDateTime submitAt;

    private String remark;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
}
