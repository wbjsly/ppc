package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 合同收款计划分期（task 10.8 计划达成对比 / 11.11.2，design D10）。
 * 计划不参与记账；与应收事实（erp_fin_ar_invoice）经 合同 → SO → 应收 链路对照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_crm_contract_plan")
public class ContractPlan extends BaseEntity {

    private String contractId;
    private String contractNo;
    private String customerId;
    private Integer periodNo;
    private BigDecimal planAmount;
    private LocalDate dueDate;
    /** 实际已核销（关联核销记录汇总，服务层回填） */
    private BigDecimal paidAmount;
    private String remark;
}
