package com.erp.service.proc;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.proc.EmergencyRequestDao;
import com.erp.entity.proc.EmergencyRequest;
import com.erp.ops.OutboxPublisher;
import com.erp.procurement.EmergencyStateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * EA 状态迁移与事件单点支撑（design D3，仿 ProcRequisitionSupport）：
 * transition = 状态机校验 + 乐观锁写（verNo+1）+ 日志；事件键 EA单号:HEAD:v(verNo+1)。
 */
@Slf4j
@Component
public class EmergencySupport {

    private final EmergencyRequestDao eaDao;
    private final OutboxPublisher outbox;

    public EmergencySupport(EmergencyRequestDao eaDao, OutboxPublisher outbox) {
        this.eaDao = eaDao;
        this.outbox = outbox;
    }

    /** 状态迁移（单点）：校验 + 乐观锁递增 + 日志 */
    public EmergencyRequest transition(EmergencyRequest ea, String to, String reason) {
        EmergencyStateMachine.require(ea.getStatus(), to);
        LambdaUpdateWrapper<EmergencyRequest> uw = new LambdaUpdateWrapper<>();
        uw.eq(EmergencyRequest::getId, ea.getId())
                .eq(EmergencyRequest::getVerNo, ea.getVerNo())
                .set(EmergencyRequest::getStatus, to)
                .set(EmergencyRequest::getVerNo, ea.getVerNo() + 1);
        if (eaDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        ea.setStatus(to);
        ea.setVerNo(ea.getVerNo() + 1);
        log.info("EA {} status -> {} reason={}", ea.getEaNo(), to,
                reason == null ? "-" : reason);
        return ea;
    }

    /** 持久化非状态字段（特批/补齐/标记/关闭留痕列），乐观锁同口径 */
    public void persist(EmergencyRequest ea) {
        LambdaUpdateWrapper<EmergencyRequest> uw = new LambdaUpdateWrapper<>();
        uw.eq(EmergencyRequest::getId, ea.getId())
                .eq(EmergencyRequest::getVerNo, ea.getVerNo())
                .set(EmergencyRequest::getSpecialApproveBy, ea.getSpecialApproveBy())
                .set(EmergencyRequest::getSpecialApproveDate, ea.getSpecialApproveDate())
                .set(EmergencyRequest::getSpecialReason, ea.getSpecialReason())
                .set(EmergencyRequest::getRejectReason, ea.getRejectReason())
                .set(EmergencyRequest::getClearanceDueDate, ea.getClearanceDueDate())
                .set(EmergencyRequest::getQuoteCount, ea.getQuoteCount())
                .set(EmergencyRequest::getCompareDocNo, ea.getCompareDocNo())
                .set(EmergencyRequest::getFillNote, ea.getFillNote())
                .set(EmergencyRequest::getFillDate, ea.getFillDate())
                .set(EmergencyRequest::getRemindFlag, ea.getRemindFlag())
                .set(EmergencyRequest::getEscalateFlag, ea.getEscalateFlag())
                .set(EmergencyRequest::getExceptionFlag, ea.getExceptionFlag())
                .set(EmergencyRequest::getScanDate, ea.getScanDate())
                .set(EmergencyRequest::getCloseReason, ea.getCloseReason())
                .set(EmergencyRequest::getVerNo, ea.getVerNo() + 1);
        if (eaDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        ea.setVerNo(ea.getVerNo() + 1);
    }

    /** 头事件：v = verNo+1（创建 verNo=0 → v1；每次动作序号天然唯一） */
    public void publishHead(EmergencyRequest ea, String eventType, String diff) {
        outbox.publish(eventType, ea.getEaNo() + ":HEAD", ea.getVerNo() + 1, null, diff);
    }

    /** EA 单号 EA-YYYYMMDD-NNN（MAX 序号忽略软删空洞，唯一索引兜底并发） */
    public String nextEaNo() {
        String prefix = "EA-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = eaDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    public EmergencyRequest requireEa(String id) {
        EmergencyRequest ea = eaDao.selectById(id);
        if (ea == null) {
            throw new ServiceException(404, "紧急采购申请单不存在");
        }
        return ea;
    }
}
