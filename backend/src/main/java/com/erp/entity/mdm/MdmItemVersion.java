package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 物料版本快照：每次变更/停用后写入该版本整行状态（C-4.1-06）。
 */
@Data
@TableName("erp_mdm_item_version")
public class MdmItemVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String entityId;

    private Integer versionNo;

    private String snapshotJson;

    private String diffSummary;

    /** CREATE / UPDATE / DISABLE */
    private String opType;

    /** 变更原因（流程二 FR-4.1-2-1，变更起必填，CREATE 为空） */
    private String changeReason;

    /** 变更分类（FR-4.1-2-2）：CRITICAL 关键属性 / GENERAL 一般属性 */
    private String changeType;

    private String createBy;

    private LocalDateTime createDate;
}
