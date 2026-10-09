package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.PickTaskDao;
import com.erp.dao.inv.PickTaskLineDao;
import com.erp.entity.inv.InvCheckDiff;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.PickTask;
import com.erp.entity.inv.PickTaskLine;
import com.erp.service.inv.FreezeService;
import com.erp.service.inv.PickDiffService;
import com.erp.service.inv.PickReviewService;
import com.erp.service.inv.PickTaskService;
import com.erp.service.sd.ReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 出库复核实现（4.7.3，spec picking-review；design D4 外观异常自动发起冻结）。
 * 三分支：PASS（全行 → DONE）/ DIFF（registerDiff → DIFF_PENDING 锁过账）/
 * QUALITY（applyFromReview 冻结申请 PENDING + 释放预留 + QUALITY 差异行 → QUALITY_PENDING）。
 */
@Slf4j
@Service
public class PickReviewServiceImpl implements PickReviewService {

    public static final String R_PASS = "PASS";
    public static final String R_DIFF = "DIFF";
    public static final String R_QUALITY = "QUALITY";

    private final PickTaskDao taskDao;
    private final PickTaskLineDao lineDao;
    private final PickTaskService pickTaskService;
    private final PickDiffService pickDiffService;
    private final FreezeService freezeService;
    private final ReservationService reservationService;

    public PickReviewServiceImpl(PickTaskDao taskDao, PickTaskLineDao lineDao,
                                 PickTaskService pickTaskService, PickDiffService pickDiffService,
                                 FreezeService freezeService,
                                 ReservationService reservationService) {
        this.taskDao = taskDao;
        this.lineDao = lineDao;
        this.pickTaskService = pickTaskService;
        this.pickDiffService = pickDiffService;
        this.freezeService = freezeService;
        this.reservationService = reservationService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> review(String taskId, Integer lineNo, String result, String kind,
                                      String reason, BigDecimal expectQty, BigDecimal actualQty) {
        PickTask task = requireTask(taskId);
        // 首次复核：PICKED → REVIEWING
        if (PickTask.ST_PICKED.equals(task.getStatus())) {
            pickTaskService.transition(taskId, PickTask.ST_PICKED, PickTask.ST_REVIEWING);
            task = requireTask(taskId);
        }
        if (!PickTask.ST_REVIEWING.equals(task.getStatus())) {
            throw new ServiceException(422, "任务不在复核中（当前 " + task.getStatus() + "）");
        }
        PickTaskLine line = requireLine(taskId, lineNo);
        if (line.getReviewResult() != null) {
            throw new ServiceException(422, "该行已有复核结论：" + line.getReviewResult());
        }
        if (result == null || !(R_PASS.equals(result) || R_DIFF.equals(result)
                || R_QUALITY.equals(result))) {
            throw new ServiceException(422, "复核结论仅支持 PASS/DIFF/QUALITY");
        }

        switch (result) {
            case R_PASS -> reviewPass(taskId, line);
            case R_DIFF -> reviewDiff(taskId, line, kind, reason, expectQty, actualQty);
            case R_QUALITY -> reviewQuality(taskId, task, line, reason);
            default -> throw new ServiceException(422, "非法复核结论");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", pickTaskService.detail(taskId).get("task"));
        out.put("line", lineDao.selectById(line.getId()));
        return out;
    }

    /** ① 通过：全行 PASS → 任务 DONE */
    private void reviewPass(String taskId, PickTaskLine line) {
        setReview(line, PickTaskLine.RV_PASS);
        Long unp = lineDao.selectCount(new LambdaQueryWrapper<PickTaskLine>()
                .eq(PickTaskLine::getTaskId, taskId)
                .ne(PickTaskLine::getId, line.getId())
                .and(w -> w.isNull(PickTaskLine::getReviewResult)
                        .or().ne(PickTaskLine::getReviewResult, PickTaskLine.RV_PASS)));
        if (unp == null || unp == 0) {
            pickTaskService.transition(taskId, PickTask.ST_REVIEWING, PickTask.ST_DONE);
        }
    }

    /** ② 数量/品种差异（BR-4.4-29）：DIFF_PENDING 锁过账；可由 returnToPick 退回补拣 */
    private void reviewDiff(String taskId, PickTaskLine line, String kind, String reason,
                            BigDecimal expectQty, BigDecimal actualQty) {
        String k = kind == null ? InvCheckDiff.K_QTY : kind;
        if (!InvCheckDiff.K_QTY.equals(k) && !InvCheckDiff.K_BATCH.equals(k)) {
            throw new ServiceException(422, "复核差异类型仅支持 QTY/BATCH");
        }
        pickDiffService.registerDiff(taskId, line.getLineNo(), k, expectQty, actualQty,
                reason == null || reason.trim().isEmpty()
                        ? "复核发现" + (InvCheckDiff.K_QTY.equals(k) ? "数量" : "品种") + "差异"
                        : reason.trim());
        setReview(line, PickTaskLine.RV_DIFF);
    }

    /** ③ 外观异常（BR-4.4-30，L1 系统自动）：冻结申请 PENDING + 释放预留 + 质量差异 → QUALITY_PENDING */
    private void reviewQuality(String taskId, PickTask task, PickTaskLine line, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new ServiceException(422, "外观异常须填写异常说明");
        }
        // 先挂 QUALITY_PENDING（再 registerDiff 时其识别为分支态、不降级）
        pickTaskService.transition(taskId, PickTask.ST_REVIEWING, PickTask.ST_QUALITY_PENDING);

        // 自动发起质量冻结申请（design D4：跳过发起角色、审批照挂 QUALITY_MGR）
        InvFreeze req = new InvFreeze();
        req.setFreezeType(InvFreeze.T_QUALITY);
        req.setWarehouseCode(line.getWarehouseCode());
        req.setItemCode(line.getItemCode());
        req.setItemName(line.getItemName());
        req.setBatchNo(line.getBatchNo());
        req.setQty(line.getQty() == null ? BigDecimal.ONE : line.getQty());
        req.setScope(InvFreeze.SCOPE_BATCH);
        req.setReason("拣货复核外观异常（任务行 " + line.getLineNo() + "）：" + reason.trim());
        InvFreeze f = freezeService.applyFromReview(req);

        // 释放该批次预留（无预留 = 空操作）
        int released = reservationService.releaseByBatch(line.getWarehouseCode(),
                line.getItemCode(), line.getBatchNo() == null ? "" : line.getBatchNo(),
                "复核外观异常释放：" + reason.trim());

        // 质量差异行（此时任务已 QUALITY_PENDING → registerDiff 仅落行不改状态）
        pickDiffService.registerDiff(taskId, line.getLineNo(), InvCheckDiff.K_QUALITY,
                line.getQty(), null, "外观异常：" + reason.trim());
        setReview(line, PickTaskLine.RV_QUALITY);

        log.info("review quality anomaly: task={} line={} freeze={} released={}",
                taskId, line.getLineNo(), f.getFreezeNo(), released);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> returnToPick(String taskId, Integer lineNo) {
        PickTask task = requireTask(taskId);
        if (!PickTask.ST_DIFF_PENDING.equals(task.getStatus())) {
            throw new ServiceException(422, "仅差异待处理任务可退回补拣（当前 "
                    + task.getStatus() + "）");
        }
        PickTaskLine line = requireLine(taskId, lineNo);
        pickTaskService.transition(taskId, PickTask.ST_DIFF_PENDING, PickTask.ST_PICKING);
        // 行回待拣（清实拣/放行/复核结论）——null 必须显式 set（updateById 忽略 null 字段），
        // 差异行保持 PENDING 至闭环
        int rows = lineDao.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                        com.erp.entity.inv.PickTaskLine>()
                        .eq(com.erp.entity.inv.PickTaskLine::getId, line.getId())
                        .set(com.erp.entity.inv.PickTaskLine::getLineStatus,
                                PickTaskLine.LS_PENDING)
                        .set(com.erp.entity.inv.PickTaskLine::getPickedQty, null)
                        .set(com.erp.entity.inv.PickTaskLine::getScanOkFlag, "0")
                        .set(com.erp.entity.inv.PickTaskLine::getReviewResult, null)
                        .setSql("VER_NO = VER_NO + 1"));
        if (rows == 0) {
            throw new ServiceException(409, "行更新冲突，请刷新重试");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", pickTaskService.detail(taskId).get("task"));
        out.put("line", lineDao.selectById(line.getId()));
        return out;
    }

    // ---------- helpers ----------

    private void setReview(PickTaskLine line, String reviewResult) {
        line.setReviewResult(reviewResult);
        line.setVerNo(line.getVerNo());
        if (lineDao.updateById(line) == 0) {
            throw new ServiceException(409, "行更新冲突，请刷新重试");
        }
    }

    private PickTask requireTask(String taskId) {
        PickTask t = taskDao.selectById(taskId);
        if (t == null) {
            throw new ServiceException(404, "拣货任务不存在：" + taskId);
        }
        return t;
    }

    private PickTaskLine requireLine(String taskId, Integer lineNo) {
        PickTaskLine l = lineDao.selectOne(new LambdaQueryWrapper<PickTaskLine>()
                .eq(PickTaskLine::getTaskId, taskId)
                .eq(PickTaskLine::getLineNo, lineNo)
                .last("LIMIT 1"));
        if (l == null) {
            throw new ServiceException(404, "任务行不存在：" + lineNo);
        }
        return l;
    }
}
