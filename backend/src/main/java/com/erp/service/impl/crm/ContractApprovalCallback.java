package com.erp.service.impl.crm;

import com.erp.dao.crm.ContractDao;
import com.erp.entity.crm.Contract;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 合同签订审批回调（BIZ_TYPE = Contract，FR-4.8-1-7 差异 >10% L2）。
 * 通过 → 进入法务审核占位（LEGAL_REVIEW）；驳回 → REJECTED 退回修改。
 * 仅注入 DAO；清 approvalId 必须显式 set(null)（MP updateById 忽略 null）。
 */
@Slf4j
@Component
public class ContractApprovalCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "Contract";

    private final ContractDao contractDao;

    public ContractApprovalCallback(ContractDao contractDao) {
        this.contractDao = contractDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        Contract c = contractDao.selectById(instance.getBizId());
        if (c == null) {
            log.warn("contract approved but record missing: {}", instance.getBizId());
            return;
        }
        contractDao.update(null, new com.baomidou.mybatisplus.core.conditions.update
                .LambdaUpdateWrapper<Contract>()
                .eq(Contract::getId, c.getId())
                .set(Contract::getStatus, Contract.ST_LEGAL_REVIEW)
                .set(Contract::getApprovalId, null));
        log.info("contract {} diff approval passed → legal review", c.getContractNo());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        Contract c = contractDao.selectById(instance.getBizId());
        if (c == null) {
            log.warn("contract rejected but record missing: {}", instance.getBizId());
            return;
        }
        contractDao.update(null, new com.baomidou.mybatisplus.core.conditions.update
                .LambdaUpdateWrapper<Contract>()
                .eq(Contract::getId, c.getId())
                .set(Contract::getStatus, Contract.ST_REJECTED)
                .set(Contract::getApprovalId, null));
        log.info("contract {} diff approval rejected", c.getContractNo());
    }
}
