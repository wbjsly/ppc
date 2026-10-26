package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 证书类别字典（qualification-certification，IS_KEY 定门禁范围，编码创建后不可改）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_cert_category")
public class HrCertCategory extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_DISABLED = "DISABLED";

    /** CATE+流水，创建后不可改 */
    private String cateCode;
    private String cateName;
    /** 1=关键岗位资格认证（BR-4.7-06 排班门禁范围） */
    private String isKey;
    /** 对应技能编码（技能矩阵 CERT 来源） */
    private String skillCode;
    /** 默认技能等级 */
    private String skillLevel;
    /** ACTIVE/DISABLED（停用不可被新证书引用） */
    private String status;
}
