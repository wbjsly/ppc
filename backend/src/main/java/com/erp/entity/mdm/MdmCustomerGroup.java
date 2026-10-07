package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 客户集团视图（客户管理 1.3.1，表 erp_mdm_customer_group，流程六 FR-4.1-6-1）。
 * 编码规则：CUST-NNNN（4 位流水），创建后不可修改。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_customer_group")
public class MdmCustomerGroup extends BaseEntity {

    private String customerCode;

    private String customerName;

    /** 税号：跨集团视图唯一硬阻断（BR-4.1-30） */
    private String taxNo;

    /** 统一社会信用代码 */
    private String uscc;

    /** 营业执照/资质证照有效期（BR-4.3-08 过期阻断报价；空 = 不校验）。053 动态列 */
    private java.time.LocalDate licenseExpire;

    /** 一般纳税人资格（C-4.3-06 税务资质校验：GENERAL 一般 / SMALL 小规模 / 空未维护）。056 动态列 */
    private String taxpayerType;

    /** 一般纳税人资格有效期（空 = 不校验；过期阻断开专票）。056 动态列 */
    private java.time.LocalDate taxQualExpire;

    /** 销项税码（开票税率来源之一，与物料税码二选一优先物料）。056 动态列 */
    private String taxCode;

    /** 集团信用评级（外部征信 + 内部履约，每半年复评——本期人工维护） */
    private String creditRating;

    /** 集团信用总额度（BR-4.1-31 求和校验基准；为空 = 未配置，校验放行） */
    private BigDecimal creditLimitTotal;

    /** 1 启用 / 0 停用 / 2 冻结（级联法人视图，BR-4.1-34）/ 3 已合并（终态，编码锁定 C-4.1-10） */
    private String status;

    /** 合并指向的目标客户编码（STATUS='3' 时非空） */
    private String mergedTo;

    /** 名称查重「确认非重复」差异说明 */
    private String dupNote;

    /** 变更原因（UPDATE/状态操作时必填），非持久化，写入版本记录 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private transient String changeReason;
}
