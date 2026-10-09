package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 行列权限规则位（spec bi-query-governance，design D6 查询层显式注入）。 */
@Getter
@Setter
@TableName("erp_bi_perm_rule")
public class BiPermRule extends BaseEntity {

    public static final String TYPE_ROW_LE = "ROW_LE";
    public static final String TYPE_COL_MASK = "COL_MASK";
    public static final String TYPE_COL_SENSITIVE = "COL_SENSITIVE";

    private String ruleType;
    /** 对象（字段名 / 通配 *） */
    private String targetKey;
    private String userName;
    private String entityId;
    private String roleCode;
    /** PASS / MASK / DENY */
    private String action;
    private String status;
    private String remark;
}
