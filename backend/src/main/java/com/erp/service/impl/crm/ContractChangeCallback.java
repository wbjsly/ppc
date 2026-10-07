package com.erp.service.impl.crm;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.dao.crm.ContractDao;
import com.erp.dao.crm.ContractVersionDao;
import com.erp.entity.crm.Contract;
import com.erp.entity.crm.ContractVersion;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

/**
 * 合同变更审批回调（BIZ_TYPE = ContractChange，bizId = 版本行，tasks 14.6）。
 * 通过 → 快照写回合同（金额/交期/标题）、差异率重校验、版本号 +1、变更次数 +1、
 *       合同回 SIGNED、版本置 APPROVED；驳回 → 版本置 REJECTED、合同回 SIGNED 可重新发起。
 * 仅注入 DAO；清 approvalId 显式 set(null)（MP updateById 忽略 null 老坑）。
 */
@Slf4j
@Component
public class ContractChangeCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "ContractChange";

    private final ContractDao contractDao;
    private final ContractVersionDao versionDao;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContractChangeCallback(ContractDao contractDao, ContractVersionDao versionDao) {
        this.contractDao = contractDao;
        this.versionDao = versionDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        ContractVersion v = versionDao.selectById(instance.getBizId());
        if (v == null) {
            log.warn("contract change approved but version missing: {}", instance.getBizId());
            return;
        }
        Contract c = contractDao.selectById(v.getContractId());
        if (c == null) {
            log.warn("contract missing for change version {}", v.getId());
            return;
        }
        Map<String, Object> snap = read(v.getSnapshotJson());
        Contract apply = new Contract();
        apply.setId(c.getId());
        if (snap.get("title") != null) {
            apply.setTitle(String.valueOf(snap.get("title")));
        }
        if (snap.get("amount") != null) {
            apply.setAmount(new BigDecimal(String.valueOf(snap.get("amount"))));
        }
        if (isBlankStr(snap.get("startDate"))) {
            // 保持原值
        } else {
            apply.setStartDate(java.time.LocalDate.parse(String.valueOf(snap.get("startDate"))));
        }
        if (isBlankStr(snap.get("endDate"))) {
            // 保持原值
        } else {
            apply.setEndDate(java.time.LocalDate.parse(String.valueOf(snap.get("endDate"))));
        }
        // 差异率重算（14.6 金额差异重校验）
        BigDecimal diff = null;
        if (apply.getAmount() != null && c.getOppAmount() != null
                && c.getOppAmount().signum() > 0) {
            diff = apply.getAmount().subtract(c.getOppAmount())
                    .divide(c.getOppAmount(), 4, RoundingMode.HALF_UP);
        }
        int changeCount = (c.getChangeCount() == null ? 0 : c.getChangeCount()) + 1;
        contractDao.update(null, new LambdaUpdateWrapper<Contract>()
                .eq(Contract::getId, c.getId())
                .set(apply.getTitle() != null, Contract::getTitle, apply.getTitle())
                .set(apply.getAmount() != null, Contract::getAmount, apply.getAmount())
                .set(apply.getStartDate() != null, Contract::getStartDate, apply.getStartDate())
                .set(apply.getEndDate() != null, Contract::getEndDate, apply.getEndDate())
                .set(diff != null, Contract::getDiffRate, diff)
                .set(Contract::getVersionNo, v.getVersionNo())
                .set(Contract::getChangeCount, changeCount)
                .set(Contract::getStatus, Contract.ST_SIGNED)
                .set(Contract::getApprovalId, null));
        versionDao.update(null, new LambdaUpdateWrapper<ContractVersion>()
                .eq(ContractVersion::getId, v.getId())
                .set(ContractVersion::getStatus, "APPROVED"));
        log.info("contract {} change v{} applied (amount→{}, diff={})",
                c.getContractNo(), v.getVersionNo(), strip(apply.getAmount()),
                diff == null ? "-" : diff.toPlainString());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        ContractVersion v = versionDao.selectById(instance.getBizId());
        if (v == null) {
            return;
        }
        Contract c = contractDao.selectById(v.getContractId());
        versionDao.update(null, new LambdaUpdateWrapper<ContractVersion>()
                .eq(ContractVersion::getId, v.getId())
                .set(ContractVersion::getStatus, "REJECTED"));
        if (c != null) {
            contractDao.update(null, new LambdaUpdateWrapper<Contract>()
                    .eq(Contract::getId, c.getId())
                    .set(Contract::getStatus, Contract.ST_SIGNED)
                    .set(Contract::getApprovalId, null));
        }
        log.info("contract {} change v{} rejected", c == null ? "?" : c.getContractNo(),
                v.getVersionNo());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean isBlankStr(Object o) {
        return o == null || String.valueOf(o).isBlank() || "null".equals(String.valueOf(o));
    }

    private static String strip(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
