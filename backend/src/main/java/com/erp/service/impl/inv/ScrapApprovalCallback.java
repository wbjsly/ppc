package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.dao.inv.ScrapOrderDao;
import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 呆滞报废三方会签回调（BIZ_TYPE=Scrap，spec scrap-order / C-4.4-14，design D5）。
 * 全部会签节点 PASSED → 实例 APPROVED → 报废单 DRAFT→APPROVED（同事务回调，异常整单回滚）；
 * 任一方驳回 → 实例 REJECTED → 报废单回 DRAFT 可改重提。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScrapApprovalCallback implements ApprovalCallback {

    private final ScrapOrderDao scrapDao;

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("Scrap");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onApproved(ApprovalInstance instance) {
        int n = scrapDao.update(null, new LambdaUpdateWrapper<InvScrapOrder>()
                .eq(InvScrapOrder::getId, instance.getBizId())
                .eq(InvScrapOrder::getStatus, InvScrapOrder.ST_DRAFT)
                .set(InvScrapOrder::getStatus, InvScrapOrder.ST_APPROVED)
                .setSql("VER_NO = VER_NO + 1"));
        log.info("scrap joint approval {} -> scrap {} approved (rows={})",
                instance.getId(), instance.getBizId(), n);
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        // 驳回时单据状态本就停留在 DRAFT（会签不改状态，通过才置 APPROVED），仅留痕
        log.info("scrap joint approval {} rejected -> scrap {} remains DRAFT",
                instance.getId(), instance.getBizId());
    }
}
