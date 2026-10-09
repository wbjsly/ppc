package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 配送线路（4.8.1，spec wave-management 配送线路主数据）：
 * 波次聚类第一优先级键（BR-4.4-42 配送线路 > 承运商 > 客户）。
 * C-0-05：停用退役不物理删除；停用后不可被新发货单挂线，存量快照不受影响。
 */
@Getter
@Setter
@TableName("erp_inv_route")
public class InvRoute extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_INACTIVE = "INACTIVE";

    /** 线路编码（唯一） */
    private String routeCode;

    private String routeName;

    /** ACTIVE 启用 / INACTIVE 停用 */
    private String status;

    private String remark;
}
