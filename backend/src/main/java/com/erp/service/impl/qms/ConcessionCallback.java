package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.qms.ConcessionDao;
import com.erp.dao.qms.NcrDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.system.ApprovalTask;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.qms.Concession;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.qms.NcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 让步接收双签审批回调（BIZ_TYPE = Concession，spec concession-acceptance，tasks 7.2）。
 * 通过 → 让步单 APPROVED + GR 行 QC_STATUS=CONCESSION（过账放行；让步量入 QC_QTY +
 *       CONCESSION_LIMIT 快照由 post() 完成）；
 * 驳回 → 让步单 REJECTED + NCR 回评审（BR-4.12-01 双签任一驳回整单驳回）。
 * 回调与审批同事务（ApprovalEngine 契约），异常整体回滚。
 */
@Slf4j
@Component
public class ConcessionCallback implements ApprovalCallback {

    private final ConcessionDao concessionDao;
    private final GoodsReceiptLineDao lineDao;
    private final NcrDao ncrDao;
    private final ApprovalTaskDao taskDao;
    private final NcrService ncrService;

    public ConcessionCallback(ConcessionDao concessionDao,
                              GoodsReceiptLineDao lineDao,
                              NcrDao ncrDao,
                              ApprovalTaskDao taskDao,
                              @Lazy NcrService ncrService) {
        this.concessionDao = concessionDao;
        this.lineDao = lineDao;
        this.ncrDao = ncrDao;
        this.taskDao = taskDao;
        this.ncrService = ncrService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("Concession");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        Concession c = concessionDao.selectById(instance.getBizId());
        if (c == null) {
            log.warn("Concession approved but record missing: {}", instance.getBizId());
            return;
        }
        if ("APPROVED".equals(c.getStatus())) {
            return; // 幂等
        }
        if (!"PENDING_APPROVE".equals(c.getStatus())) {
            throw new ServiceException(422, "让步单状态不可批准：" + c.getStatus());
        }
        c.setStatus("APPROVED");
        c.setApprovalId(instance.getId());
        if (concessionDao.updateById(c) == 0) {
            throw new ServiceException(422, "让步单状态更新冲突");
        }
        // GR 行置 CONCESSION → 过账闸口放行（spec goods-receipt / receipt-posting）
        if (c.getGrLineId() != null) {
            GoodsReceiptLine line = lineDao.selectById(c.getGrLineId());
            if (line != null && !"FROZEN".equals(line.getQcStatus())) {
                throw new ServiceException(422, "收货行状态异常，无法让步放行：" + line.getQcStatus());
            }
            if (line != null) {
                line.setQcStatus("CONCESSION");
                lineDao.updateById(line);
            }
        }
        log.info("concession {} approved (double sign), GR line -> CONCESSION", c.getConcessionNo());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        Concession c = concessionDao.selectById(instance.getBizId());
        if (c == null || "APPROVED".equals(c.getStatus())) {
            return;
        }
        // 驳回意见取被拒节点的签署意见（ApprovalTask.opinion）
        String reason = taskDao.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED")
                .last("LIMIT 1")).getOpinion();
        c.setStatus("REJECTED");
        c.setApprovalId(instance.getId());
        c.setRejectReason(hasText(reason) ? reason : "双签驳回");
        concessionDao.updateById(c);
        // NCR 回评审（tasks 7.2：驳回 → NCR 回评审可改选处置）
        if (c.getNcrId() != null) {
            ncrService.reopenForReview(c.getNcrId(), "让步接收被驳回：" + c.getRejectReason());
        }
        log.info("concession {} rejected, NCR reopened for review", c.getConcessionNo());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
