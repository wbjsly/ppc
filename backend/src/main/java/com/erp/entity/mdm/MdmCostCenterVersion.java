package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 成本中心版本快照：每次变更/停用后写入该版本整行状态，支持版本对比。
 */
@Data
@TableName("erp_mdm_cost_center_version")
public class MdmCostCenterVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String entityId;

    private Integer versionNo;

    /** 该版本状态整行 JSON 快照 */
    private String snapshotJson;

    /** 逐字段差异摘要，如 "ccName: 旧值 → 新值" */
    private String diffSummary;

    /** CREATE / UPDATE / DISABLE / SET_DEFAULT */
    private String opType;

    private String createBy;

    private LocalDateTime createDate;
}
