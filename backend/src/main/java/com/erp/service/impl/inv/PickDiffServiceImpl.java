package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvCheckDiffDao;
import com.erp.dao.inv.PickTaskDao;
import com.erp.dao.inv.PickTaskLineDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.dao.sd.ShipmentLineDao;
import com.erp.entity.inv.InvCheckDiff;
import com.erp.entity.inv.PickTask;
import com.erp.entity.inv.PickTaskLine;
import com.erp.entity.sd.ShipmentLine;
import com.erp.service.inv.PickDiffService;
import com.erp.service.inv.PickTaskService;
import com.erp.service.sd.ReservationService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拣货差异实现（4.7.4，spec picking-review；design D5）：
 * registerDiff 统一三触发点（短少/扫码不符/复核差异），销售单按差额释放 ATP 预留（偏差 D4 空操作）；
 * closeDiff 全部 RESOLVED 后按未复核行回迁任务（门闩随 PENDING 清零自动解除）。
 */
@Slf4j
@Service
public class PickDiffServiceImpl implements PickDiffService {

    /** 允许进入 DIFF_PENDING 的任务状态（其余：分支态幂等、终态/CREATED 422） */
    private static final java.util.Set<String> ENTERABLE = java.util.Set.of(
            PickTask.ST_PICKING, PickTask.ST_PICKED, PickTask.ST_REVIEWING);

    private final InvCheckDiffDao diffDao;
    private final PickTaskDao taskDao;
    private final PickTaskLineDao lineDao;
    private final ShipmentLineDao shipmentLineDao;
    private final ReservationDao reservationDao;
    private final ReservationService reservationService;
    private final PickTaskService pickTaskService;

    public PickDiffServiceImpl(InvCheckDiffDao diffDao, PickTaskDao taskDao,
                               PickTaskLineDao lineDao, ShipmentLineDao shipmentLineDao,
                               ReservationDao reservationDao,
                               ReservationService reservationService,
                               PickTaskService pickTaskService) {
        this.diffDao = diffDao;
        this.taskDao = taskDao;
        this.lineDao = lineDao;
        this.shipmentLineDao = shipmentLineDao;
        this.reservationDao = reservationDao;
        this.reservationService = reservationService;
        this.pickTaskService = pickTaskService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvCheckDiff registerDiff(String taskId, Integer lineNo, String kind,
                                     BigDecimal expectQty, BigDecimal actualQty, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new ServiceException(422, "差异原因必填");
        }
        if (kind == null || !validKind(kind)) {
            throw new ServiceException(422, "差异类型仅支持 QTY/BATCH/SERIAL/QUALITY");
        }
        PickTask task = taskDao.selectById(taskId);
        if (task == null) {
            throw new ServiceException(404, "拣货任务不存在：" + taskId);
        }

        // 幂等：同任务+行+类型已有 PENDING → 返回已有（重复扫码/复核不重复落账）
        InvCheckDiff exists = diffDao.selectOne(new LambdaQueryWrapper<InvCheckDiff>()
                .eq(InvCheckDiff::getSrcTaskId, taskId)
                .eq(InvCheckDiff::getLineNo, lineNo)
                .eq(InvCheckDiff::getDiffKind, kind)
                .eq(InvCheckDiff::getStatus, InvCheckDiff.ST_PENDING)
                .last("LIMIT 1"));
        if (exists != null) {
            return exists;
        }

        // 任务分支态：ENTERABLE → DIFF_PENDING；已在分支态幂等；其余 422
        String st = task.getStatus();
        if (PickTask.ST_DIFF_PENDING.equals(st) || PickTask.ST_QUALITY_PENDING.equals(st)) {
            // 分支态已挂：仅落差异行，不改任务状态（QUALITY_PENDING 不降级）
        } else if (ENTERABLE.contains(st)) {
            pickTaskService.transition(taskId, st, PickTask.ST_DIFF_PENDING);
        } else {
            throw new ServiceException(422, "当前任务状态不可登记拣货差异：" + st);
        }

        // 差异行（行上下文：物料/仓/批次取任务行）
        PickTaskLine line = lineDao.selectOne(new LambdaQueryWrapper<PickTaskLine>()
                .eq(PickTaskLine::getTaskId, taskId)
                .eq(PickTaskLine::getLineNo, lineNo)
                .last("LIMIT 1"));
        if (line == null) {
            throw new ServiceException(404, "任务行不存在：" + lineNo);
        }

        InvCheckDiff d = new InvCheckDiff();
        d.setDiffType(InvCheckDiff.T_PICK);
        d.setSrcTaskId(taskId);
        d.setSrcDocType(task.getSrcType());
        d.setSrcDocNo(task.getSrcDocNo());
        d.setLineNo(lineNo);
        d.setItemCode(line.getItemCode());
        d.setWarehouseCode(line.getWarehouseCode());
        d.setBatchNo(line.getBatchNo());
        d.setDiffKind(kind);
        d.setExpectQty(expectQty);
        d.setActualQty(actualQty);
        d.setDeltaQty(expectQty == null || actualQty == null ? null
                : actualQty.subtract(expectQty));
        d.setStatus(InvCheckDiff.ST_PENDING);
        d.setDiffNote(reason.trim());
        d.setCreateBy(SecurityUtils.getCurrentUserId());
        diffDao.insert(d);

        // 销售单：按差额释放该行 ATP 预留（偏差 D4：其他类型/无预留 = 空操作）
        releaseSalesReservation(task, line, expectQty, actualQty, reason.trim());

        log.info("pick diff registered: task={} line={} kind={} expect={} actual={} by={}",
                task.getTaskNo(), lineNo, kind, expectQty, actualQty,
                SecurityUtils.getCurrentUserId());
        return d;
    }

    /** 销售单差额预留释放：差额 = expect − actual（短少数），keep = 现有 ACTIVE − 差额 */
    private void releaseSalesReservation(PickTask task, PickTaskLine line,
                                         BigDecimal expectQty, BigDecimal actualQty,
                                         String reason) {
        if (!"SALES_OUT".equals(task.getSrcType()) || expectQty == null || actualQty == null) {
            return;
        }
        BigDecimal delta = expectQty.subtract(actualQty);
        if (delta.signum() <= 0) {
            return;   // 无短少（批次/质量类差异无数量语义）
        }
        ShipmentLine sl = shipmentLineDao.selectById(line.getSrcLineId());
        if (sl == null || sl.getSoLineId() == null || sl.getSoLineId().isEmpty()) {
            return;   // 换货/框架发货不挂 SO 预留 → 空操作
        }
        BigDecimal active = reservationDao.sumActiveByLine(sl.getSoLineId());
        if (active == null || active.signum() <= 0) {
            return;
        }
        BigDecimal keep = active.subtract(delta).max(BigDecimal.ZERO);
        int released = reservationService.releaseByLine(sl.getSoLineId(), keep,
                "拣货短少差额释放：" + reason);
        log.info("pick diff released reservation: soLine={} keep={} released={}",
                sl.getSoLineId(), keep, released);
    }

    private static boolean validKind(String kind) {
        return InvCheckDiff.K_QTY.equals(kind) || InvCheckDiff.K_BATCH.equals(kind)
                || InvCheckDiff.K_SERIAL.equals(kind) || InvCheckDiff.K_QUALITY.equals(kind);
    }

    @Override
    public Map<String, Object> page(String status, String kind, String docNo, String itemCode,
                                    long current, long size) {
        Page<InvCheckDiff> p = diffDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<InvCheckDiff>()
                        .eq(InvCheckDiff::getDiffType, InvCheckDiff.T_PICK)
                        .eq(notBlank(status), InvCheckDiff::getStatus, trimOrNull(status))
                        .eq(notBlank(kind), InvCheckDiff::getDiffKind, trimOrNull(kind))
                        .like(notBlank(docNo), InvCheckDiff::getSrcDocNo, trimOrNull(docNo))
                        .like(notBlank(itemCode), InvCheckDiff::getItemCode, trimOrNull(itemCode))
                        .orderByDesc(InvCheckDiff::getCreateDate));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", p.getRecords());
        out.put("total", p.getTotal());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> close(String diffId, String note) {
        InvCheckDiff d = diffDao.selectById(diffId);
        if (d == null) {
            throw new ServiceException(404, "差异单不存在：" + diffId);
        }
        if (InvCheckDiff.T_COUNT.equals(d.getDiffType())) {
            throw new ServiceException(403, "盘点差异由 4.11 盘点组处理");
        }
        if (!InvCheckDiff.ST_PENDING.equals(d.getStatus())) {
            throw new ServiceException(422, "差异已闭环（当前 " + d.getStatus() + "）");
        }
        if (note == null || note.trim().isEmpty()) {
            throw new ServiceException(422, "处理说明必填");
        }
        d.setStatus(InvCheckDiff.ST_RESOLVED);
        d.setResolveNote(note.trim());
        d.setResolveBy(SecurityUtils.getCurrentUserId());
        d.setResolveAt(LocalDateTime.now());
        d.setVerNo(d.getVerNo());
        if (diffDao.updateById(d) == 0) {
            throw new ServiceException(409, "差异闭环并发冲突，请刷新重试");
        }

        // 该任务 PENDING 清零 → 门闩解除 + 回迁（按未复核行判定）
        Long pending = diffDao.selectCount(new LambdaQueryWrapper<InvCheckDiff>()
                .eq(InvCheckDiff::getSrcTaskId, d.getSrcTaskId())
                .eq(InvCheckDiff::getStatus, InvCheckDiff.ST_PENDING));
        if (pending != null && pending == 0 && d.getSrcTaskId() != null) {
            migrateAfterClose(d.getSrcTaskId());
        }
        log.info("pick diff closed: {} note={} by={}", d.getSrcDocNo(), note.trim(),
                SecurityUtils.getCurrentUserId());
        return Map.of("diff", diffDao.selectById(diffId));
    }

    /** 闭环回迁：DIFF_PENDING/QUALITY_PENDING → 有未复核行回 PICKING，否则进 DONE */
    private void migrateAfterClose(String taskId) {
        PickTask task = taskDao.selectById(taskId);
        if (task == null) {
            return;
        }
        String st = task.getStatus();
        if (!PickTask.ST_DIFF_PENDING.equals(st)
                && !PickTask.ST_QUALITY_PENDING.equals(st)) {
            return;
        }
        Long unreviewed = lineDao.selectCount(new LambdaQueryWrapper<PickTaskLine>()
                .eq(PickTaskLine::getTaskId, taskId)
                .and(w -> w.isNull(PickTaskLine::getReviewResult)
                        .or().ne(PickTaskLine::getReviewResult, PickTaskLine.RV_PASS)));
        String to = (unreviewed != null && unreviewed > 0)
                ? PickTask.ST_PICKING : PickTask.ST_DONE;
        try {
            pickTaskService.transition(taskId, st, to);
        } catch (ServiceException e) {
            // 并发下已被其他闭环迁移 → 容忍
            log.warn("pick task {} migrate after close skipped: {}", taskId, e.getMessage());
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.trim().isEmpty();
    }

    private static String trimOrNull(String v) {
        return v == null ? null : v.trim();
    }
}
