package com.erp.service.impl.crm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.crm.OppFollowupDao;
import com.erp.dao.crm.OppStageLogDao;
import com.erp.dao.crm.OpportunityDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.crm.OppFollowup;
import com.erp.entity.crm.OppStageLog;
import com.erp.entity.crm.Opportunity;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 商机阶段推进审批回调（BIZ_TYPE = OppStage，spec opportunity-management FR-4.8-1-6）。
 * 通过 → 商机阶段跃迁（阶段概率/下一步/日期一并落定；推进到「合同签订」置赢单）；
 * 驳回 → 日志 REJECTED、清除在途审批，阶段保持原样可改重提。回调与审批同事务。
 *
 * <p>只注入 DAO（与既有回调同一范式）：ApprovalServiceImpl 构造注入 List&lt;ApprovalCallback&gt;，
 * 若回调注入 OpportunityService（其依赖 ApprovalEngine）会形成构造期循环依赖。
 */
@Slf4j
@Component
public class OppStageCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "OppStage";

    private final OppStageLogDao stageLogDao;
    private final OpportunityDao oppDao;
    private final OppFollowupDao followupDao;
    private final ApprovalTaskDao taskDao;

    public OppStageCallback(OppStageLogDao stageLogDao,
                            OpportunityDao oppDao,
                            OppFollowupDao followupDao,
                            ApprovalTaskDao taskDao) {
        this.stageLogDao = stageLogDao;
        this.oppDao = oppDao;
        this.followupDao = followupDao;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        OppStageLog row = stageLogDao.selectById(instance.getBizId());
        if (row == null) {
            log.warn("opp stage approved but log missing: {}", instance.getBizId());
            return;
        }
        if (OppStageLog.ST_APPROVED.equals(row.getStatus())) {
            return; // 幂等
        }
        if (!OppStageLog.ST_PENDING.equals(row.getStatus())) {
            throw new ServiceException(422, "阶段日志状态不可批准：" + row.getStatus());
        }
        Opportunity opp = oppDao.selectById(row.getOppId());
        if (opp == null) {
            throw new ServiceException(422, "阶段审批通过但商机不存在：" + row.getOppId());
        }
        // 并发推进防护：当前阶段必须仍是申请时的起点
        if (!opp.getStage().equals(row.getFromStage())) {
            throw new ServiceException(422, "商机阶段已被其他推进变更，请刷新后重试");
        }

        LocalDateTime now = LocalDateTime.now();
        opp.setStage(row.getToStage());
        opp.setStageEnteredAt(now);
        opp.setStageOverdue("0");
        opp.setStageProbability(row.getProbability());
        opp.setNextAction(row.getNextAction());
        opp.setNextActionDate(row.getNextActionDate());
        opp.setApprovalId(null);
        if (Opportunity.ST_CONTRACT.equals(row.getToStage())) {
            // 推进至「合同签订」= 赢单（FR-4.8-1-7 的合同草稿由合同域在商机到达该阶段后生成）
            opp.setStatus(Opportunity.ST_WON);
            opp.setCloseAt(now);
        }
        if (oppDao.updateById(opp) == 0) {
            throw new ServiceException(422, "商机阶段更新冲突");
        }
        // MP updateById 忽略 null 字段：在途审批 ID 必须显式置 NULL（否则残留挡住后续推进）
        oppDao.update(null, new LambdaUpdateWrapper<Opportunity>()
                .eq(Opportunity::getId, opp.getId())
                .set(Opportunity::getApprovalId, null));

        row.setStatus(OppStageLog.ST_APPROVED);
        row.setDecideBy(deciderOf(instance.getId(), "PASSED"));
        row.setDecideAt(now);
        stageLogDao.updateById(row);

        addSystemFollowup(opp.getId(), "阶段推进审批通过：" + stageNameOf(row.getFromStage())
                + " → " + stageNameOf(row.getToStage()));
        log.info("opportunity {} advanced to {}（审批 {}）", opp.getOppNo(), row.getToStage(), instance.getId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        OppStageLog row = stageLogDao.selectById(instance.getBizId());
        if (row == null) {
            log.warn("opp stage rejected but log missing: {}", instance.getBizId());
            return;
        }
        if (OppStageLog.ST_REJECTED.equals(row.getStatus())) {
            return; // 幂等
        }
        if (!OppStageLog.ST_PENDING.equals(row.getStatus())) {
            throw new ServiceException(422, "阶段日志状态不可驳回：" + row.getStatus());
        }
        row.setStatus(OppStageLog.ST_REJECTED);
        row.setDecideBy(deciderOf(instance.getId(), "REJECTED"));
        row.setDecideAt(LocalDateTime.now());
        row.setOpinion(opinionOf(instance.getId()));
        stageLogDao.updateById(row);

        Opportunity opp = oppDao.selectById(row.getOppId());
        if (opp != null && instance.getId().equals(opp.getApprovalId())) {
            // MP updateById 忽略 null 字段：显式置 NULL 清除在途审批
            oppDao.update(null, new LambdaUpdateWrapper<Opportunity>()
                    .eq(Opportunity::getId, opp.getId())
                    .set(Opportunity::getApprovalId, null));
            addSystemFollowup(opp.getId(), "阶段推进被驳回："
                    + (row.getOpinion() == null ? "" : row.getOpinion()));
        }
        log.info("opportunity stage advance rejected: log={} opinion={}", row.getId(), row.getOpinion());
    }

    // ---------- helpers ----------

    /** 取该实例下指定状态节点的签署人（通过/驳回节点） */
    private String deciderOf(String instanceId, String status) {
        List<ApprovalTask> tasks = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instanceId)
                .eq(ApprovalTask::getStatus, status));
        return tasks.isEmpty() ? null : tasks.get(0).getSigner();
    }

    private String opinionOf(String instanceId) {
        List<ApprovalTask> tasks = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instanceId)
                .eq(ApprovalTask::getStatus, "REJECTED"));
        return tasks.isEmpty() ? null : tasks.get(0).getOpinion();
    }

    private void addSystemFollowup(String oppId, String content) {
        OppFollowup f = new OppFollowup();
        f.setOppId(oppId);
        f.setFollowType("SYSTEM");
        f.setContent(content);
        f.setOperatorId("system");
        f.setOperatorName("system");
        f.setFollowAt(LocalDateTime.now());
        followupDao.insert(f);
    }

    private static String stageNameOf(String stage) {
        switch (stage == null ? "" : stage) {
            case Opportunity.ST_REQUIREMENT: return "需求确认";
            case Opportunity.ST_DEMO: return "方案演示";
            case Opportunity.ST_NEGOTIATION: return "商务谈判";
            case Opportunity.ST_QUOTE: return "报价";
            case Opportunity.ST_CONTRACT: return "合同签订";
            default: return stage;
        }
    }
}
