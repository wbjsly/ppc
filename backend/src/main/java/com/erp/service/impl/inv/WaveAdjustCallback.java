package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvWaveAdjustDao;
import com.erp.dao.inv.InvWaveLineDao;
import com.erp.dao.sd.ShipmentLineDao;
import com.erp.entity.inv.InvWaveAdjust;
import com.erp.entity.inv.InvWaveLine;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 波次改批审批回调（BIZ_TYPE=WaveAdjust，spec wave-management C-4.4-08，design D4）。
 * 通过 → 调整记录 APPROVED、分配行按后值生效 + 双写发货行（首段）、行解锁（UNLOCKED）；
 * 驳回 → REJECTED、行解锁但值保持原推荐值；全程留痕（前值/后值/原因）。
 * 波次已作废/关闭 → 幂等跳过业务动作（调整记录已 CANCELLED，只留审批痕迹）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaveAdjustCallback implements ApprovalCallback {

    private final InvWaveAdjustDao adjustDao;
    private final InvWaveLineDao lineDao;
    private final ShipmentLineDao shipmentLineDao;

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("WaveAdjust");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onApproved(ApprovalInstance instance) {
        InvWaveAdjust adj = adjustDao.selectById(instance.getBizId());
        if (adj == null) {
            log.warn("wave adjust {} not found for approval {}", instance.getBizId(),
                    instance.getApprNo());
            return;
        }
        if (!InvWaveAdjust.ST_PENDING.equals(adj.getStatus())) {
            // 波次作废时已 CANCELLED → 不改业务（spec：作废关闭未决审批留痕）
            log.info("wave adjust {} not PENDING ({}), skip apply", adj.getId(), adj.getStatus());
            return;
        }
        InvWaveLine line = lineDao.selectById(adj.getLineId());
        if (line == null) {
            throw new ServiceException(409, "分配行已不存在，改批审批无法生效");
        }
        // 后值生效
        InvWaveLine patch = new InvWaveLine();
        patch.setId(line.getId());
        if (InvWaveLine.FIELD_BATCH.equals(adj.getField())) {
            patch.setBatchNo(adj.getNewValue());
        } else {
            patch.setBinCode(adj.getNewValue());
        }
        patch.setLockFlag("0");
        patch.setVerNo(line.getVerNo());
        if (lineDao.updateById(patch) == 0) {
            throw new ServiceException(409, "分配行更新冲突（并发），审批回滚重试");
        }
        mark(adj.getId(), InvWaveAdjust.ST_APPROVED);
        // 双写发货行（首段口径：该发货行分配段的第一条 = 回写展示值）
        writeBackIfFirstSegment(line);
        log.info("wave adjust {} approved: {} {} -> {}", adj.getId(), adj.getField(),
                adj.getOldValue(), adj.getNewValue());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onRejected(ApprovalInstance instance) {
        InvWaveAdjust adj = adjustDao.selectById(instance.getBizId());
        if (adj == null) {
            return;
        }
        if (!InvWaveAdjust.ST_PENDING.equals(adj.getStatus())) {
            return;
        }
        InvWaveLine line = lineDao.selectById(adj.getLineId());
        if (line != null && "1".equals(line.getLockFlag())) {
            // 解锁但值保持原推荐值（驳回不改值）
            InvWaveLine patch = new InvWaveLine();
            patch.setId(line.getId());
            patch.setLockFlag("0");
            patch.setVerNo(line.getVerNo());
            if (lineDao.updateById(patch) == 0) {
                throw new ServiceException(409, "分配行解锁冲突（并发），审批回滚重试");
            }
        }
        mark(adj.getId(), InvWaveAdjust.ST_REJECTED);
        log.info("wave adjust {} rejected, line keeps {}", adj.getId(),
                line == null ? "?" : (InvWaveLine.FIELD_BATCH.equals(adj.getField())
                        ? line.getBatchNo() : line.getBinCode()));
    }

    private void mark(String adjustId, String status) {
        adjustDao.update(null, new LambdaUpdateWrapper<InvWaveAdjust>()
                .eq(InvWaveAdjust::getId, adjustId)
                .eq(InvWaveAdjust::getStatus, InvWaveAdjust.ST_PENDING)
                .set(InvWaveAdjust::getStatus, status)
                .setSql("VER_NO = VER_NO + 1"));
    }

    /** 改批行恰为该发货行首段时同步回写发货行（首段口径与 allocate 一致） */
    private void writeBackIfFirstSegment(InvWaveLine changed) {
        List<InvWaveLine> segs = lineDao.selectList(new LambdaQueryWrapper<InvWaveLine>()
                .eq(InvWaveLine::getShipLineId, changed.getShipLineId())
                .orderByAsc(InvWaveLine::getCreateDate)
                .orderByAsc(InvWaveLine::getId));
        if (segs.isEmpty() || !segs.get(0).getId().equals(changed.getId())) {
            return;   // 非首段不回写（发货行展示首段，过账按分配段展开不受影响）
        }
        com.erp.entity.sd.ShipmentLine sl = shipmentLineDao.selectById(changed.getShipLineId());
        if (sl == null) {
            return;
        }
        sl.setBatchNo(changed.getBatchNo());
        sl.setBinCode(changed.getBinCode());
        if (shipmentLineDao.updateById(sl) == 0) {
            throw new ServiceException(409, "发货行回写冲突，审批回滚重试");
        }
    }
}
