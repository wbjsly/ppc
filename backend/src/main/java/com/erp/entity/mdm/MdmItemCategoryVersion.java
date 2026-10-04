package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 物料分类版本快照（1.2.3 分类维护；OP_TYPE: CREATE/UPDATE/DISABLE/MERGE/MOVE）。
 */
@Data
@TableName("erp_mdm_item_category_version")
public class MdmItemCategoryVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String entityId;

    private Integer versionNo;

    /** 该版本状态整行 JSON 快照 */
    private String snapshotJson;

    private String diffSummary;

    /** CREATE / UPDATE / DISABLE / MERGE / MOVE */
    private String opType;

    /** 变更/合并/迁移原因（必填于该三类操作） */
    private String changeReason;

    private String createBy;

    private LocalDateTime createDate;
}
