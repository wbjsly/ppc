package com.erp.entity.i18n;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 翻译缺口台账（BR-5.7-05 i18n.missing 批量回传 UPSERT 载体） */
@Data
@TableName("erp_i18n_gap")
public class I18nGap implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String ST_OPEN = "OPEN";
    public static final String ST_TRANSLATING = "TRANSLATING";
    public static final String ST_CLOSED = "CLOSED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String keyName;

    private String module;

    private String priority;

    private LocalDateTime firstHitAt;

    private Integer hitCount;

    private String status;

    private LocalDateTime closedAt;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;
}
