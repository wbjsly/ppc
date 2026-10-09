package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 8D 步骤（D1~D8）：按序推进，当前步未完成时下一步 422。
 */
@Getter
@Setter
@TableName("erp_qms_capa_step")
public class CapaStep extends BaseEntity {
    /** CAPA ID */
    @TableField("CAPA_ID")
    private String capaId;

    /** D1~D8 */
    @TableField("STEP_CODE")
    private String stepCode;

    /** 步骤名称 */
    @TableField("STEP_NAME")
    private String stepName;

    /** PENDING / DONE */
    @TableField("STATUS")
    private String status;

    /** 步骤负责人 */
    @TableField("OWNER_ID")
    private String ownerId;

    /** 负责人姓名 */
    @TableField("OWNER_NAME")
    private String ownerName;

    /** 产出物 */
    @TableField("OUTPUT_DESC")
    private String outputDesc;

    /** 完成时间 */
    @TableField("DONE_TIME")
    private LocalDateTime doneTime;

}
