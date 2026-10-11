package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.service.mrp.MoCloseCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 关闭前置校验（change add-work-order-management design D6 钩子；
 * add-reporting-management work-order-management MODIFIED「未完成工序检查」真实现兑现）。
 *
 *  1) 未完成工序检查（FR-4.5-6-8「仍有未完成工序 → 阻断关闭」）——真实现：
 *     存在 OP_STATUS ≠ DONE 的工序 → 422 列出清单（5.7 完工接管后工序状态由报工推进）；
 *  2) 成本归集完成检查（C-4.5-09 L1）——依赖 4.5-6 成本归集模块，仍空通过占位；
 *  3) 在制库存余额 = 0（BR-4.4-16）——库存域无独立在制表（1.3 核实结论），空通过占位。
 *
 * 硬校验「状态 = COMPLETED」由 MoServiceImpl.precheckClose/close 自身执行，不经本钩子。
 */
@Slf4j
@Component
public class PendingCloseChecks implements MoCloseCheck {

    private final MrpMoOpDao moOpDao;

    public PendingCloseChecks(MrpMoOpDao moOpDao) {
        this.moOpDao = moOpDao;
    }

    @Override
    public String name() {
        return "未完成工序检查（成本归集/在制清零占位通过，待 4.5-6 接入）";
    }

    @Override
    public void validate(MrpMo mo) {
        // 1) 未完成工序（work-order-management MODIFIED 真实现——422 列清单）
        List<MrpMoOp> undone = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId())
                .ne(MrpMoOp::getOpStatus, "DONE"));
        if (!undone.isEmpty()) {
            String detail = undone.stream()
                    .map(o -> "工序 " + o.getOpSeq() + " " + o.getOpName()
                            + "（" + o.getOpStatus() + "）")
                    .collect(Collectors.joining("、"));
            throw new ServiceException(422, "存在未完成工序，阻断关闭：" + detail);
        }
        // 2)/3) 占位空通过（成本归集 4.5-6、在制余额无数据源——见类注释）
        log.debug("工单关闭校验通过：{}（无未完成工序）", mo.getMoNo());
    }
}
