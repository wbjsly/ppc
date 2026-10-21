package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 工时记录（add-asset-maintenance，design D1/D6）：
 * 人工成本 = Σ(工时 × SysParam 岗位费率 ENGR/TECH/DEFAULT)。
 */
@Getter
@Setter
@TableName("erp_eam_work_order_time")
public class EamWorkOrderTime extends BaseEntity {

    @TableField("WO_NO")
    private String woNo;

    @TableField("WORKER")
    private String worker;

    /** ENGR / TECH / OTHER（费率匹配键） */
    @TableField("POST_TYPE")
    private String postType;

    @TableField("WORK_HOURS")
    private BigDecimal workHours;

    @TableField("WORK_DATE")
    private LocalDate workDate;

    @TableField("REMARK")
    private String remark;
}
