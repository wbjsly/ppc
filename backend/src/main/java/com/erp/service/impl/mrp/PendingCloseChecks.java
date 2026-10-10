package com.erp.service.impl.mrp;

import com.erp.entity.mrp.MrpMo;
import com.erp.service.mrp.MoCloseCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 关闭前置校验占位（change add-work-order-management，proposal D3 偏差 + design D6 + 任务 1.3 核实结论）。
 *
 * 本期为「空通过」实现，聚合三项待接入校验（下游落地时各自注册真实 bean 替代本占位）：
 *  1) 未完成工序检查（FR-4.5-6-8「仍有未完成工序 → 阻断」）——依赖 5.7 报工工序完成记录；
 *  2) 成本归集完成检查（C-4.5-09 L1）——依赖 4.5-6 成本归集模块；
 *  3) 在制库存余额 = 0（BR-4.4-16）——任务 1.3 核实：库存域无独立在制表
 *     （WipStockController 恒空待接入、领料单有 WORK_ORDER_NO 但完工入库无数据源），
 *     按 design Risks 降级为空通过，5.7 完工入库落地后实现。
 *
 * 硬校验「状态 = COMPLETED」由 MoServiceImpl.precheckClose/close 自身执行，不经本钩子。
 */
@Slf4j
@Component
public class PendingCloseChecks implements MoCloseCheck {

    @Override
    public String name() {
        return "下游前置校验（工序完成/成本归集/在制清零）——占位通过，待 5.7/4.5-6 接入";
    }

    @Override
    public void validate(MrpMo mo) {
        // 占位空通过（proposal D3）：不阻断；真实实现接入时在此抛 ServiceException(422)
        log.debug("工单关闭占位校验通过：{}（占位项待 5.7/4.5-6 接入）", mo.getMoNo());
    }
}
