package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvCheckDiffDao;
import com.erp.entity.inv.InvCheckDiff;
import com.erp.service.inv.PickTaskGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 过账门闩实现（spec picking-review；design D2 单一查询四入口消费）：
 * PENDING 拣货差异存在 → 422，报文带未闭环数量便于前端引导去 4.7.4。
 */
@Slf4j
@Component
public class PickTaskGateImpl implements PickTaskGate {

    private final InvCheckDiffDao diffDao;

    public PickTaskGateImpl(InvCheckDiffDao diffDao) {
        this.diffDao = diffDao;
    }

    @Override
    public void assertClear(String srcType, String srcDocNo) {
        if (srcDocNo == null || srcDocNo.isEmpty()) {
            return;
        }
        // 拣货差异（PICK）+ 分播差异（WAVE_SORT，srcDocNo=订单号）同锁
        // （spec wave-management：WAVE_SORT 闭环同样解锁过账门闩，BR-4.4-29/45 口径）
        Long pending = diffDao.selectCount(new LambdaQueryWrapper<InvCheckDiff>()
                .in(InvCheckDiff::getDiffType,
                        java.util.List.of(InvCheckDiff.T_PICK, InvCheckDiff.T_WAVE_SORT))
                .eq(InvCheckDiff::getStatus, InvCheckDiff.ST_PENDING)
                .eq(InvCheckDiff::getSrcDocType, srcType)
                .eq(InvCheckDiff::getSrcDocNo, srcDocNo));
        if (pending != null && pending > 0) {
            throw new ServiceException(422, "存在未闭环拣货差异（含分播差异，" + pending
                    + " 条），禁止出库过账，请先在 4.7.4 差异处理闭环（BR-4.4-29）");
        }
    }
}
