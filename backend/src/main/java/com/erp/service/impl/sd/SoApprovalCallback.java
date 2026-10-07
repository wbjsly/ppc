package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.dao.sd.SoChangeDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.dao.sd.SoVersionDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoChange;
import com.erp.entity.sd.SoLine;
import com.erp.entity.sd.SoVersion;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SO 审批回调（BIZ_TYPE = So，spec sales-order FR-4.3-4-5/4-6）。
 * 通过 → 执行确认（锁批次预留 + SO.CONFIRMED 事件，锁不足 → 抛 422 使审批一并回滚）；
 * 驳回 → 退回 DRAFT 可改重提。只注入 DAO + SoConfirmSupport（不依赖 SoService，避免循环）。
 */
@Slf4j
@Component
public class SoApprovalCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "So";

    private final SoDao soDao;
    private final SoLineDao lineDao;
    private final SoChangeDao changeDao;
    private final SoVersionDao versionDao;
    private final ApprovalTaskDao taskDao;
    private final SoConfirmSupport confirmSupport;

    public SoApprovalCallback(SoDao soDao,
                              SoLineDao lineDao,
                              SoChangeDao changeDao,
                              SoVersionDao versionDao,
                              ApprovalTaskDao taskDao,
                              SoConfirmSupport confirmSupport) {
        this.soDao = soDao;
        this.lineDao = lineDao;
        this.changeDao = changeDao;
        this.versionDao = versionDao;
        this.taskDao = taskDao;
        this.confirmSupport = confirmSupport;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        So so = soDao.selectById(instance.getBizId());
        if (so == null) {
            log.warn("SO approved but record missing: {}", instance.getBizId());
            return;
        }
        // 主链「价格变更复核」已随全链通过 → 解锁全部锁行（BR-4.3-26：审批通过前禁止确认）
        lineDao.update(null, new LambdaUpdateWrapper<SoLine>()
                .eq(SoLine::getSoId, so.getId())
                .eq(SoLine::getPriceLocked, "1")
                .set(SoLine::getPriceLocked, "0")
                .set(SoLine::getPriceApprovalId, null));
        // 确认（预留 + 事件 + 置 CONFIRMED）。锁批次不足 → 422 → 审批事务回滚，SO 保持 PENDING
        confirmSupport.confirm(so.getId(), signerOf(instance.getId(), "PASSED"));
        record(so.getId(), "CONFIRM", "PENDING", So.ST_CONFIRMED,
                "审批通过执行确认（BR-4.3-29 同事务生成预留）");
        saveVersion(so.getId(), SoVersion.OP_CONFIRM, "审批通过 → 已确认");
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        So so = soDao.selectById(instance.getBizId());
        if (so == null) {
            log.warn("SO rejected but record missing: {}", instance.getBizId());
            return;
        }
        // 退回修改（业务逻辑：驳回 → 退回修改或取消）
        so.setStatus(So.ST_DRAFT);
        soDao.updateById(so);
        soDao.update(null, new LambdaUpdateWrapper<So>()
                .eq(So::getId, so.getId())
                .set(So::getApprovalId, null));
        record(so.getId(), "APPROVAL_REJECT", So.ST_PENDING, So.ST_DRAFT,
                "审批驳回：" + opinionOf(instance.getId()));
        log.info("SO {} rejected: {}", so.getSoNo(), opinionOf(instance.getId()));
    }

    // ---------- helpers（仅依赖 DAO） ----------

    private void record(String soId, String field, String oldV, String newV, String reason) {
        SoChange c = new SoChange();
        c.setId(uuid());
        c.setSoId(soId);
        c.setFieldName(field);
        c.setOldValue(oldV);
        c.setNewValue(newV);
        c.setReason(reason);
        c.setOperatorId("approval");
        c.setOperateAt(LocalDateTime.now());
        changeDao.insert(c);
    }

    private void saveVersion(String soId, String opType, String remark) {
        SoVersion v = new SoVersion();
        v.setId(uuid());
        v.setSoId(soId);
        long max = versionDao.selectCount(new LambdaQueryWrapper<SoVersion>()
                .eq(SoVersion::getSoId, soId));
        v.setVersionNo((int) max + 1);
        v.setOpType(opType);
        v.setSnapshotJson("{}");
        v.setOperatorId("approval");
        v.setOperateAt(LocalDateTime.now());
        v.setRemark(remark);
        versionDao.insert(v);
    }

    private String signerOf(String instanceId, String status) {
        ApprovalTask t = task(instanceId, status);
        return t == null || t.getSigner() == null ? "system" : t.getSigner();
    }

    private String opinionOf(String instanceId) {
        ApprovalTask t = task(instanceId, "REJECTED");
        return t == null ? null : t.getOpinion();
    }

    private ApprovalTask task(String instanceId, String status) {
        List<ApprovalTask> list = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instanceId)
                .eq(ApprovalTask::getStatus, status));
        return list.isEmpty() ? null : list.get(0);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
