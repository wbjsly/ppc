package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 供应商版本快照（沿 MDM 版本基线）。
 * OP_TYPE: CREATE / UPDATE / REVIEW / REJECTED / FROZEN / UNFROZEN / DISABLED /
 *          ENABLED / CERT_EXPIRED / CERT_RENEWED
 */
@Data
@TableName("erp_mdm_supplier_version")
public class MdmSupplierVersion implements Serializable {

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
