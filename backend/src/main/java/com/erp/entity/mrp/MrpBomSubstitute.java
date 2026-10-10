package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * BOM 行级替代料（change add-bom-management，spec bom-management FR-4.5-1-3；迁移 117）。
 * 1:N：主料可由替代料按 RATIO 替代（如 1 单位主料 = 1.2 单位替代料）；
 * PRIORITY 数值小者优先（MRP 按优先级分配 FR-4.5-2-4，分配逻辑属 5.3）。
 * 主料与替代料单位不一致由服务层 L1 阻断（偏差 D2：本期无单位换算模块）。
 */
@Getter
@Setter
@TableName("erp_mrp_bom_substitute")
public class MrpBomSubstitute extends BaseEntity {

    private String bomItemId;
    private String substituteItemCode;
    private String substituteItemName;
    /** 替代比例（1 单位主料 = RATIO 单位替代料，必须 > 0） */
    private BigDecimal ratio;
    /** 优先级（数值小者优先，最小 1） */
    private Integer priority;
}
