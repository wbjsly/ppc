package com.erp.entity.i18n;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 本地合规字段包（BR-5.7-13 规则集中配置禁硬编码；字段挂既有列） */
@Data
@TableName("erp_i18n_compliance_field")
public class I18nComplianceField implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** JP_INVOICE/EU_VAT/CN_TAX */
    private String packCode;

    /** 法人主体注册地映射 JP/EU/CN */
    private String regPlace;

    private String fieldName;

    private String columnName;

    private String formatRegex;

    private String required;

    private String errorHint;

    private Integer sortNo;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;
}
