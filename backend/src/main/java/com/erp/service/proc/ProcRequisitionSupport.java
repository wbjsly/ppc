package com.erp.service.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.proc.ProcRequisitionDao;
import com.erp.entity.proc.ProcRequisition;
import com.erp.ops.OutboxPublisher;
import com.erp.procurement.RequisitionStateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * PR 状态迁移与事件的单点支撑（design D2/D3）：
 * transition = 状态机校验 + 乐观锁写（verNo+1）+ 操作日志；事件幂等键
 *   头：PR单号:HEAD:v(verNo+1)，行：PR单号:L行号:v(verNo+1) —— 含记录身份（tax-code D3 口径）。
 */
@Slf4j
@Component
public class ProcRequisitionSupport {

    private final ProcRequisitionDao prDao;
    private final OutboxPublisher outbox;

    public ProcRequisitionSupport(ProcRequisitionDao prDao, OutboxPublisher outbox) {
        this.prDao = prDao;
        this.outbox = outbox;
    }

    /** 头状态迁移（单点）：校验 + 乐观锁递增 + 日志 */
    public ProcRequisition transition(ProcRequisition pr, String to, String reason) {
        RequisitionStateMachine.require(pr.getStatus(), to);
        LambdaUpdateWrapper<ProcRequisition> uw = new LambdaUpdateWrapper<>();
        uw.eq(ProcRequisition::getId, pr.getId())
                .eq(ProcRequisition::getVerNo, pr.getVerNo())
                .set(ProcRequisition::getStatus, to)
                .set(ProcRequisition::getVerNo, pr.getVerNo() + 1);
        if (prDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        pr.setStatus(to);
        pr.setVerNo(pr.getVerNo() + 1);
        log.info("PR {} status {} -> {} reason={}", pr.getPrNo(), pr.getStatus(), to,
                reason == null ? "-" : reason);
        return pr;
    }

    /** 持久化非状态字段（确认/关闭/预算等留痕列），乐观锁同口径 */
    public void persist(ProcRequisition pr) {
        LambdaUpdateWrapper<ProcRequisition> uw = new LambdaUpdateWrapper<>();
        uw.eq(ProcRequisition::getId, pr.getId())
                .eq(ProcRequisition::getVerNo, pr.getVerNo())
                .set(ProcRequisition::getBudgetSubject, pr.getBudgetSubject())
                .set(ProcRequisition::getBudgetCostCenterId, pr.getBudgetCostCenterId())
                .set(ProcRequisition::getBudgetInternalOrderNo, pr.getBudgetInternalOrderNo())
                .set(ProcRequisition::getRejectReason, pr.getRejectReason())
                .set(ProcRequisition::getConfirmBy, pr.getConfirmBy())
                .set(ProcRequisition::getConfirmDate, pr.getConfirmDate())
                .set(ProcRequisition::getCloseReason, pr.getCloseReason())
                .set(ProcRequisition::getCloseBy, pr.getCloseBy())
                .set(ProcRequisition::getCloseDate, pr.getCloseDate())
                .set(ProcRequisition::getRemindFlag, pr.getRemindFlag())
                .set(ProcRequisition::getEscalateFlag, pr.getEscalateFlag())
                .set(ProcRequisition::getScanDate, pr.getScanDate())
                .set(ProcRequisition::getApprovalAmount, pr.getApprovalAmount())
                .set(ProcRequisition::getVerNo, pr.getVerNo() + 1);
        if (prDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        pr.setVerNo(pr.getVerNo() + 1);
    }

    /** 头事件：v = verNo+1（create 时 verNo=0 → v1；每次 transition 后递增，动作序号天然唯一） */
    public void publishHead(ProcRequisition pr, String eventType, String diff) {
        outbox.publish(eventType, pr.getPrNo() + ":HEAD", pr.getVerNo() + 1, null, diff);
    }

    /** 行事件（CREATED）：同头 verNo 下以行号区分 */
    public void publishLine(ProcRequisition pr, int lineNo, String eventType, String diff) {
        outbox.publish(eventType, pr.getPrNo() + ":L" + lineNo, pr.getVerNo() + 1, null, diff);
    }

    /** PR 单号 PR-YYYYMMDD-NNN 按日流水（MAX 序号忽略软删空洞，唯一索引兜底并发） */
    public String nextPrNo() {
        String prefix = "PR-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = prDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    public ProcRequisition requirePr(String id) {
        ProcRequisition pr = prDao.selectById(id);
        if (pr == null) {
            throw new ServiceException(404, "请购单不存在");
        }
        return pr;
    }

    public ProcRequisition insertWithRetry(ProcRequisition pr) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                prDao.insert(pr);
                return pr;
            } catch (DuplicateKeyException ex) {
                pr.setId(null);
                pr.setPrNo(nextPrNo());
            }
        }
        throw new ServiceException(409, "请购单号生成冲突，请重试");
    }
}
