package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 客户法人视图版本快照（C-4.1-06 同款）。
 */
@Data
@TableName("erp_mdm_customer_view_version")
public class MdmCustomerViewVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String entityId;

    private Integer versionNo;

    private String snapshotJson;

    private String diffSummary;

    /** CREATE / UPDATE / DISABLE / FREEZE / MERGE */
    private String opType;

    /** 操作原因（变更/状态操作时必填，CREATE 为空） */
    private String changeReason;

    private String createBy;

    private LocalDateTime createDate;
}
