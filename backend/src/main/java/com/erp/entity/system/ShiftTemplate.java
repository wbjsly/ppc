package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 班次模板（change add-dispatch-management，spec capacity-load「班次模板与工作中心绑定」，迁移 122）。
 * 16.11.2 维护：HOURS 必须 > 0；被工作中心引用的模板禁止删除（spec 被引用禁删）。
 * 班次编码创建后不可改（主数据治理基线）。
 */
@Getter
@Setter
@TableName("erp_admin_shift")
public class ShiftTemplate extends BaseEntity {

    private String shiftCode;
    private String shiftName;
    /** HH:mm */
    private String startTime;
    /** HH:mm */
    private String endTime;
    /** 班次工时（> 0，扣午休后净值） */
    private BigDecimal hours;
    /** 1 启用 / 0 停用 */
    private String status;
}
