package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/** 移动/IoT SLA 报告订阅（13.9.2 归档自动推送） */
@Data
@TableName("erp_iot_sla_sub")
public class IotSlaSub implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 订阅人账号 */
    private String subscriber;

    /** 报告类型（预留多类型，默认 IOT） */
    private String reportType;

    private String active;

    /** 最近推送期（防重复） */
    private String lastPushPeriod;

    private String remark;

    private String createBy;
    private java.time.LocalDateTime createDate;
    private String updateBy;
    private java.time.LocalDateTime updateDate;
}
