package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * SO 版本快照（tasks 7.1，spec sales-order）。创建/审批/确认/变更/关闭全留痕。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_so_version")
public class SoVersion extends BaseEntity {

    public static final String OP_CREATE = "CREATE";
    public static final String OP_APPROVE = "APPROVE";
    public static final String OP_CONFIRM = "CONFIRM";
    public static final String OP_CHANGE = "CHANGE";
    public static final String OP_CLOSE = "CLOSE";

    private String soId;
    private Integer versionNo;
    private String opType;
    private String snapshotJson;
    private String operatorId;
    private LocalDateTime operateAt;
    private String remark;
}
