package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.inv.InvWaveDao;
import com.erp.dao.inv.InvWaveLineDao;
import com.erp.dao.inv.PickTaskDao;
import com.erp.dao.inv.PickTaskLineDao;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvWave;
import com.erp.entity.inv.InvWaveLine;
import com.erp.entity.inv.PickTask;
import com.erp.entity.inv.PickTaskLine;
import com.erp.service.inv.FreezePauseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 冻结挂起实现（spec freeze-management ADDED 需求 ①，design D1）：
 * 维度经任务行/波次行反查（行含 warehouse+item+batch），头部状态白名单内批量
 * UPDATE ... WHERE status IN（幂等，并发安全）；与冻结执行同事务。
 * resume：来源冻结单 RELEASED 方可恢复，CAS 回 PAUSE_FROM_STATUS。
 */
@Slf4j
@Service
public class FreezePauseServiceImpl implements FreezePauseService {

    /** 拣货任务可挂起状态（PICKED 起不挂——货已在拣货员手上） */
    private static final Set<String> PICK_PAUSABLE = Set.of(
            PickTask.ST_CREATED, PickTask.ST_PICKING);
    /** 波次可挂起状态（STAGING/SHIPPING 不挂——过账引擎批次预算兜底） */
    private static final Set<String> WAVE_PAUSABLE = Set.of(
            InvWave.ST_CREATED, InvWave.ST_ALLOCATED,
            InvWave.ST_PICKING, InvWave.ST_SORTING);

    private final PickTaskDao pickTaskDao;
    private final PickTaskLineDao pickTaskLineDao;
    private final InvWaveDao waveDao;
    private final InvWaveLineDao waveLineDao;
    private final InvFreezeDao freezeDao;

    public FreezePauseServiceImpl(PickTaskDao pickTaskDao, PickTaskLineDao pickTaskLineDao,
                                  InvWaveDao waveDao, InvWaveLineDao waveLineDao,
                                  InvFreezeDao freezeDao) {
        this.pickTaskDao = pickTaskDao;
        this.pickTaskLineDao = pickTaskLineDao;
        this.waveDao = waveDao;
        this.waveLineDao = waveLineDao;
        this.freezeDao = freezeDao;
    }

    @Override
    @Transactional
    public void pauseOutbound(InvFreeze freeze) {
        String batchNo = InvFreeze.SCOPE_ALL.equals(freeze.getScope())
                ? null : (freeze.getBatchNo() == null ? "" : freeze.getBatchNo());

        // 1) 拣货任务：任务行反查 → 头部白名单批量挂起
        List<String> taskIds = pickTaskLineDao.selectList(new LambdaQueryWrapper<PickTaskLine>()
                        .eq(PickTaskLine::getWarehouseCode, freeze.getWarehouseCode())
                        .eq(PickTaskLine::getItemCode, freeze.getItemCode())
                        .eq(batchNo != null, PickTaskLine::getBatchNo, batchNo))
                .stream().map(PickTaskLine::getTaskId).distinct().collect(Collectors.toList());
        int pausedTasks = 0;
        if (!taskIds.isEmpty()) {
            // setSql 必须在 set(status) 之前：MySQL SET 从左到右求值，
            // PAUSE_FROM_STATUS = STATUS 须读到挂起前的旧状态
            pausedTasks = pickTaskDao.update(null, new LambdaUpdateWrapper<PickTask>()
                    .in(PickTask::getId, taskIds)
                    .in(PickTask::getStatus, PICK_PAUSABLE)
                    .setSql("PAUSE_FROM_STATUS = STATUS")
                    .set(PickTask::getStatus, PickTask.ST_PAUSED)
                    .set(PickTask::getPauseFreezeNo, freeze.getFreezeNo()));
        }

        // 2) 波次：波次行反查 → 头部白名单批量挂起
        List<String> waveIds = waveLineDao.selectList(new LambdaQueryWrapper<InvWaveLine>()
                        .eq(InvWaveLine::getWarehouseCode, freeze.getWarehouseCode())
                        .eq(InvWaveLine::getItemCode, freeze.getItemCode())
                        .eq(batchNo != null, InvWaveLine::getBatchNo, batchNo))
                .stream().map(InvWaveLine::getWaveId).distinct().collect(Collectors.toList());
        int pausedWaves = 0;
        if (!waveIds.isEmpty()) {
            pausedWaves = waveDao.update(null, new LambdaUpdateWrapper<InvWave>()
                    .in(InvWave::getId, waveIds)
                    .in(InvWave::getStatus, WAVE_PAUSABLE)
                    .setSql("PAUSE_FROM_STATUS = STATUS")
                    .set(InvWave::getStatus, InvWave.ST_PAUSED)
                    .set(InvWave::getPauseFreezeNo, freeze.getFreezeNo()));
        }
        if (pausedTasks > 0 || pausedWaves > 0) {
            log.info("freeze {} paused outbound: tasks={} waves={}",
                    freeze.getFreezeNo(), pausedTasks, pausedWaves);
        }
    }

    @Override
    @Transactional
    public void resume(String entityType, String id) {
        requireWarehouse("冻结挂起恢复");
        boolean isPick = "PICK_TASK".equalsIgnoreCase(entityType);
        boolean isWave = "WAVE".equalsIgnoreCase(entityType);
        if (!isPick && !isWave) {
            throw new ServiceException(422, "类型仅支持 PICK_TASK/WAVE");
        }
        String currentStatus;
        String pauseFreezeNo;
        String pauseFromStatus;
        if (isPick) {
            PickTask t = pickTaskDao.selectById(id);
            if (t == null) {
                throw new ServiceException(422, "拣货任务不存在");
            }
            if (!PickTask.ST_PAUSED.equals(t.getStatus())) {
                throw new ServiceException(422, "任务非冻结挂起状态：" + t.getStatus());
            }
            currentStatus = t.getStatus();
            pauseFreezeNo = t.getPauseFreezeNo();
            pauseFromStatus = t.getPauseFromStatus();
        } else {
            InvWave w = waveDao.selectById(id);
            if (w == null) {
                throw new ServiceException(422, "波次不存在");
            }
            if (!InvWave.ST_PAUSED.equals(w.getStatus())) {
                throw new ServiceException(422, "波次非冻结挂起状态：" + w.getStatus());
            }
            currentStatus = w.getStatus();
            pauseFreezeNo = w.getPauseFreezeNo();
            pauseFromStatus = w.getPauseFromStatus();
        }
        if (pauseFreezeNo == null || pauseFreezeNo.isBlank()) {
            throw new ServiceException(422, "无来源冻结单，不可恢复");
        }
        // 来源冻结单必须已解冻（spec 需求①：解冻不自动恢复，但恢复前提是冻结已解除）
        InvFreeze src = freezeDao.selectOne(new LambdaQueryWrapper<InvFreeze>()
                .eq(InvFreeze::getFreezeNo, pauseFreezeNo));
        if (src != null && !InvFreeze.ST_RELEASED.equals(src.getStatus())) {
            throw new ServiceException(422, "来源冻结单 " + pauseFreezeNo
                    + " 尚未解冻（当前 " + src.getStatus() + "），不可恢复");
        }
        String to = (pauseFromStatus == null || pauseFromStatus.isBlank())
                ? (isPick ? PickTask.ST_CREATED : InvWave.ST_CREATED) : pauseFromStatus;

        int rows;
        if (isPick) {
            rows = pickTaskDao.update(null, new LambdaUpdateWrapper<PickTask>()
                    .eq(PickTask::getId, id)
                    .eq(PickTask::getStatus, currentStatus)
                    .set(PickTask::getStatus, to)
                    .set(PickTask::getPauseFreezeNo, null)
                    .set(PickTask::getPauseFromStatus, null));
        } else {
            rows = waveDao.update(null, new LambdaUpdateWrapper<InvWave>()
                    .eq(InvWave::getId, id)
                    .eq(InvWave::getStatus, currentStatus)
                    .set(InvWave::getStatus, to)
                    .set(InvWave::getPauseFreezeNo, null)
                    .set(InvWave::getPauseFromStatus, null));
        }
        if (rows == 0) {
            throw new ServiceException(409, "恢复并发冲突，请刷新后重试");
        }
        log.info("resume {} {} -> {} (source freeze {})", entityType, id, to, pauseFreezeNo);
    }

    private void requireWarehouse(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new java.util.ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r)
                || "ROLE_WAREHOUSE".equalsIgnoreCase(r))) {
            return;
        }
        throw new ServiceException(403, action + "：需要仓库主管或管理员角色");
    }
}
