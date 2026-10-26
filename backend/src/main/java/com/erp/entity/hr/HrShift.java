package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** HR 班次（M10-3-1，独立于生产班次 erp_admin_shift）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_shift")
public class HrShift extends BaseEntity {
    /** 班次编码 SHIFT+4 位流水 */
    private String shiftNo;
    private String name;
    /** 上班时间 HH:mm */
    private String startTime;
    /** 下班时间 HH:mm（可跨天夜班） */
    private String endTime;
    private BigDecimal hours;
    /** 跨天班次 1=是 */
    private String crossDay;
    /** 计薪班次（可产生加班） */
    private String overtimeEligible;
    /** 弹性容差分钟 */
    private Integer toleranceMin;
    /** 1 启用 / 0 停用 */
    private String status;
}
