package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 税码版本快照（沿 MDM 版本基线，与汇率版本表同构）。OP_TYPE: CREATE / UPDATE。
 */
@Data
@TableName("erp_mdm_tax_code_version")
public class MdmTaxCodeVersion implements Serializable {

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
