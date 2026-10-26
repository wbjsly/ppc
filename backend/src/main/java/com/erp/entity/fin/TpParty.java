package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 关联方登记（transfer-pricing）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_tp_party")
public class TpParty extends BaseEntity {

    public static final String REL_PARENT = "PARENT";
    public static final String REL_COMMON_CONTROL = "COMMON_CONTROL";
    public static final String REL_OTHER = "OTHER";

    /** 关联方编号 TPP+流水 */
    private String tpNo;
    private String name;
    /** 纳税人识别号/统一社会信用代码 */
    private String taxNo;
    /** PARENT 母子 / COMMON_CONTROL 同受控制 / OTHER */
    private String relType;
    /** 是否境外 1=境外 */
    private String overseasFlag;
    private String note;
}
