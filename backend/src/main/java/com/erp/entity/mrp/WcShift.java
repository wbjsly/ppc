package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 工作中心班次绑定（change add-dispatch-management，design D1：独立表避免 ALTER 归档的
 * 118 工作中心表；迁移 122）。UNIQUE(WC_CODE, SHIFT_CODE)；排程时段展开按绑定模板逐班次展开。
 */
@Getter
@Setter
@TableName("erp_mrp_wc_shift")
public class WcShift extends BaseEntity {

    private String wcCode;
    private String shiftCode;
}
