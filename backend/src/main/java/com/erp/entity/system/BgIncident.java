package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 故障工单（add-break-glass-console design D5，BR-5.6-05 载体）。
 * 状态机极简：处理中 → 已解决/已关闭；Break-Glass 只消费"处理中"判定。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_ops_incident")
public class BgIncident extends BaseEntity {

    public static final String ST_PROCESSING = "PROCESSING";
    public static final String ST_RESOLVED = "RESOLVED";
    public static final String ST_CLOSED = "CLOSED";

    @TableField("INC_NO")
    private String incNo;

    @TableField("TITLE")
    private String title;

    @TableField("STATUS")
    private String status;

    @TableField("HANDLER")
    private String handler;
}
