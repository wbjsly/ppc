package com.erp.service.mrp;

import com.erp.entity.mrp.MrpMo;

/**
 * 工单关闭前置校验钩子（change add-work-order-management，design D6 / spec「完工确认与工单关闭」）。
 * 关闭时遍历 Spring 容器内全部实现；不满足 → 抛 ServiceException(422) 阻断关闭。
 * 本期注册空通过占位实现（D3 偏差：未完成工序 5.7、成本归集 4.5-6、在制余额 1.3 核实无数据源），
 * 下游模块落地时注册各自 bean 即可接入，关闭主流程不需改动。
 */
public interface MoCloseCheck {

    /** 校验名（展示于关闭预检结果） */
    String name();

    /** 不满足时抛 ServiceException(422)；满足则正常返回 */
    void validate(MrpMo mo);
}
