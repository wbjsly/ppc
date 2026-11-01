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
    /**
     * 掩码样式（add-data-extract-console design D3）：
     * PHONE 前3后4 / ID_CARD 前3后4 / SALARY *** / BANK 尾4 / GENERIC 首尾保留
     */
    private String maskStyle;
    /**
     * 行级受控范围表达式（add-perm-control-console design D1）：
     * SELF 仅本人所属 / SELF_SUBTREE 本部门及下级 / LIST 显式清单；NULL=存量直配语义
     */
    private String scopeExpr;
    /** 组织部门锚（SELF_SUBTREE 的根 / LIST 的逐条） */
    private String orgId;
    /** 数据密级上限（NULL=不限） */
    private String secLevel;
    /** LIST 模式组织 ID 清单 JSON */
    private String scopeList;
    private String status;
    private String remark;
}
