package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.ExpiryEvalDao;
import com.erp.dao.inv.ExpiryLockLogDao;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.inv.ScrapOrderDao;
import com.erp.entity.inv.ExpiryEval;
import com.erp.entity.inv.ExpiryLockLog;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 效期评估审批回调（spec expiry-management 需求④，design D4；与签署同事务）。
 * 通过：SCRAP→评估 CLOSED（报废单留 DRAFT 走 4.5.4 后续）；
 *       RELEASE→写豁免三件套+清锁+锁 log（EVAL_RELEASE）+评估 CLOSED。
 * 驳回：评估回 PENDING_EVAL 记意见；SCRAP 分支作废关联报废单（绕 WAREHOUSE 校验直更）。
 * FREEZE 分支提交即 CLOSED 不挂审批，不进本回调。
 */
@Slf4j
@Component
public class ExpiryEvalCallback implements ApprovalCallback {

    private final ExpiryEvalDao evalDao;
    private final InvBatchDao batchDao;
    private final ExpiryLockLogDao lockLogDao;
    private final ScrapOrderDao scrapDao;

    public ExpiryEvalCallback(ExpiryEvalDao evalDao, InvBatchDao batchDao,
                              ExpiryLockLogDao lockLogDao, ScrapOrderDao scrapDao) {
        this.evalDao = evalDao;
        this.batchDao = batchDao;
        this.lockLogDao = lockLogDao;
        this.scrapDao = scrapDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(ExpiryEval.BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        ExpiryEval e = evalDao.selectById(instance.getBizId());
        if (e == null) {
            log.warn("expiry eval approved but record missing: {}", instance.getBizId());
            return;
        }
        if (ExpiryEval.ST_CLOSED.equals(e.getStatus())) {
            return;   // 幂等
        }
        if (ExpiryEval.C_RELEASE.equals(e.getConclusion())) {
            writeExemption(e);
        }
        // SCRAP：报废单保持 DRAFT，由仓库在 4.5.4 走批准/过账（职责分工）
        e.setStatus(ExpiryEval.ST_CLOSED);
        e.setCloseAt(LocalDateTime.now());
        if (evalDao.updateById(e) == 0) {
            throw new ServiceException(409, "评估单状态更新冲突");
        }
        log.info("expiry eval {} CLOSED ({}{}", e.getEvalNo(),
                e.getConclusion(), e.getConclusion() != null && ExpiryEval.C_RELEASE.equals(e.getConclusion())
                        ? " until " + e.getReleaseUntil() : "");
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        ExpiryEval e = evalDao.selectById(instance.getBizId());
        if (e == null) {
            log.warn("expiry eval rejected but record missing: {}", instance.getBizId());
            return;
        }
        // SCRap 分支作废关联报废单（该单仍 DRAFT；作废走 DAO 绕 WAREHOUSE 校验——
        // 签署人是质量经理，报废单作废权限在仓库，此处由评估链自动回收）
        if (ExpiryEval.C_SCRAP.equals(e.getConclusion()) && e.getScrapDocNo() != null) {
            scrapDao.update(null, new LambdaUpdateWrapper<InvScrapOrder>()
                    .eq(InvScrapOrder::getScrapNo, e.getScrapDocNo())
                    .eq(InvScrapOrder::getStatus, InvScrapOrder.ST_DRAFT)
                    .set(InvScrapOrder::getStatus, InvScrapOrder.ST_CANCELLED)
                    .set(InvScrapOrder::getCancelReason,
                            "效期评估 " + e.getEvalNo() + " 审批驳回自动作废")
                    .setSql("VER_NO = VER_NO + 1"));
        }
        e.setStatus(ExpiryEval.ST_PENDING_EVAL);
        e.setApprOpinion(trimOpinion(e.getApprOpinion()));
        e.setApprId(null);
        evalDao.updateById(e);
        log.info("expiry eval {} rejected → PENDING_EVAL", e.getEvalNo());
    }

    /**
     * 让步放行写豁免（需求⑤）：EVAL_EXEMPT_UNTIL/ID + 清锁位 + LOCK_SOURCE 回 AUTO
     * + 变更历史（EVAL_RELEASE）。扫描遇未过期豁免强制不锁，引擎④实时放行。
     */
    private void writeExemption(ExpiryEval e) {
        if (e.getReleaseUntil() == null) {
            throw new ServiceException(422, "放行评估单缺少放行有效期，豁免写入阻断");
        }
        int rows = batchDao.update(null, new LambdaUpdateWrapper<InvBatch>()
                .eq(InvBatch::getItemCode, e.getItemCode())
                .eq(InvBatch::getBatchNo, e.getBatchNo())
                .set(InvBatch::getEvalExemptUntil, e.getReleaseUntil())
                .set(InvBatch::getEvalExemptId, e.getEvalNo())
                .set(InvBatch::getExpiryLockFlag, "0")
                .set(InvBatch::getLockSource, InvBatch.SRC_AUTO)
                .setSql("VER_NO = VER_NO + 1"));
        if (rows == 0) {
            throw new ServiceException(422, "批次台账不存在，放行豁免写入阻断："
                    + e.getItemCode() + "/" + e.getBatchNo());
        }
        // 变更历史（仅原已锁才记 flag 变化；放行本身总留痕）
        InvBatch b = batchDao.selectOne(new LambdaQueryWrapper<InvBatch>()
                .eq(InvBatch::getItemCode, e.getItemCode())
                .eq(InvBatch::getBatchNo, e.getBatchNo())
                .last("LIMIT 1"));
        if (b != null) {
            lockLogDao.insert(ExpiryLockLog.of(b, "1", "0",
                    ExpiryLockLog.SRC_EVAL_RELEASE, e.getEvalBy(),
                    "让步放行至 " + e.getReleaseUntil() + "（" + e.getEvalNo() + "）"));
        }
        log.info("exemption written: {}/{} until {} ({})",
                e.getItemCode(), e.getBatchNo(), e.getReleaseUntil(), e.getEvalNo());
    }

    private String trimOpinion(String existing) {
        String add = "审批驳回，回待判定";
        if (existing == null || existing.isEmpty()) {
            return add;
        }
        return existing.contains(add) ? existing : existing + "；" + add;
    }
}
