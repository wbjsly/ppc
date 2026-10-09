package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 价格协议头（客户管理 1.3.3，表 erp_mdm_price_agreement，FR-4.1-6-2 缺口 + FR-4.3-1-4 载体）。
 * 编码 PA-NNNN 创建后不可改；挂靠集团/法人二选一（两级共享）；
 * 状态 0 未生效 / 1 生效中 / 2 过期（懒回写）/ 3 停用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_price_agreement")
public class MdmPriceAgreement extends BaseEntity {

    private String paCode;

    private String paName;

    /** EXCLUSIVE 客户专属价 / LADDER 量价阶梯 / TIME 时间价 */
    private String agreementType;

    /** 集团级挂靠（与 CUSTOMER_VIEW_ID 二选一） */
    private String customerGroupId;

    /** 法人视图级挂靠（优先级高于集团级同类型） */
    private String customerViewId;

    private LocalDate effectiveDate;

    /** 空 = 不限期 */
    private LocalDate expireDate;

    /** 0 未生效 / 1 生效中 / 2 过期 / 3 停用 */
    private String status;

    /** 停用原因（停用/恢复动作记录） */
    private String stopReason;

    /** 变更原因（编辑/状态动作必填），非持久化，写入版本记录 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private transient String changeReason;

    /** 价格行（详情返回，编辑回显用），非持久化 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private transient java.util.List<MdmPriceAgreementLine> lines;
}
