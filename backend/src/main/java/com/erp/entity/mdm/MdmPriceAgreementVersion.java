package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 价格协议版本快照（沿 MDM 版本基线）。
 */
@Data
@TableName("erp_mdm_price_agreement_version")
public class MdmPriceAgreementVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String entityId;

    private Integer versionNo;

    private String snapshotJson;

    private String diffSummary;

    /** CREATE / UPDATE / STOP / RESTORE / SWEEP_EXPIRE */
    private String opType;

    private String changeReason;

    private String createBy;

    private LocalDateTime createDate;
}
