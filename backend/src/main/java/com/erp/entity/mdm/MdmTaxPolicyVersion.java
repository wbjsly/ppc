package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 政策台账版本快照（1.6.2 增强，add-tax-policy-workbench D1，与税码版本表同构）。
 * OP_TYPE: CREATE / UPDATE；政策无必填变更原因 → CHANGE_REASON 置空。
 */
@Data
@TableName("erp_mdm_tax_policy_version")
public class MdmTaxPolicyVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String entityId;

    private Integer versionNo;

    private String snapshotJson;

    private String diffSummary;

    private String opType;

    private String changeReason;

    private String createBy;

    private LocalDateTime createDate;
}
