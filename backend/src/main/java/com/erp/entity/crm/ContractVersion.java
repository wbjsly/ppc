package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 合同版本历史（task 14.6 变更留痕，历史只读；spec sales-contract 版本场景）。
 * 独立表（仅创建字段，无软删/乐观锁——版本是不可变快照）。
 */
@Data
@TableName("erp_crm_contract_version")
public class ContractVersion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String contractId;
    private Integer versionNo;
    private String snapshotJson;
    private String diffSummary;
    /** CREATE / UPDATE / TERMINATE */
    private String opType;
    private String changeReason;
    /** 变更审批状态：APPROVING / APPROVED / REJECTED（14.6，057 动态列） */
    private String status;
    private String approvalId;
    private String createBy;
    private LocalDateTime createDate;
}
