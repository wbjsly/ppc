package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 移动端首件检验记录（BR-4.11-10 离线例外 / BR-4.11-54 联网复核） */
@Data
@TableName("erp_iot_first_article")
public class IotFirstArticle implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String ST_PENDING = "PENDING";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_REJECTED = "REJECTED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 派工单（一单一首件） */
    private String dispatchId;

    private String moNo;
    private Integer opSeq;
    private String itemCode;

    /** CTQ 检验数据 JSON */
    private String ctqJson;

    private String photoUrl;

    private String status;

    /** 离线「首件待确认」条件放行标记 */
    private String offlineFlag;

    /** 联网复核 PASS/FAIL */
    private String recheckResult;
    private String recheckBy;
    private LocalDateTime recheckAt;

    /** 复核不合格整批转差异单指向 */
    private String diffConflictId;

    private String submitBy;
    private LocalDateTime submitAt;
    private String confirmBy;
    private LocalDateTime confirmAt;

    private String remark;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
}
