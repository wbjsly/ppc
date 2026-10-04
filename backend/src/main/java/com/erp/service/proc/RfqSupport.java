package com.erp.service.proc;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.proc.RfqDao;
import com.erp.entity.proc.Rfq;
import com.erp.ops.OutboxPublisher;
import com.erp.procurement.RfqStateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * RFQ 状态迁移与事件单点支撑（design D2，仿 ProcRequisitionSupport）：
 * transition = 状态机校验 + 乐观锁写（verNo+1）+ 日志；事件键 RFQ单号:HEAD:v(verNo+1)。
 */
@Slf4j
@Component
public class RfqSupport {

    private final RfqDao rfqDao;
    private final OutboxPublisher outbox;

    public RfqSupport(RfqDao rfqDao, OutboxPublisher outbox) {
        this.rfqDao = rfqDao;
        this.outbox = outbox;
    }

    /** 状态迁移（单点）：校验 + 乐观锁递增 + 日志 */
    public Rfq transition(Rfq rfq, String to, String reason) {
        RfqStateMachine.require(rfq.getStatus(), to);
        LambdaUpdateWrapper<Rfq> uw = new LambdaUpdateWrapper<>();
        uw.eq(Rfq::getId, rfq.getId())
                .eq(Rfq::getVerNo, rfq.getVerNo())
                .set(Rfq::getStatus, to)
                .set(Rfq::getVerNo, rfq.getVerNo() + 1);
        if (rfqDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        rfq.setStatus(to);
        rfq.setVerNo(rfq.getVerNo() + 1);
        log.info("RFQ {} status -> {} reason={}", rfq.getRfqNo(), to,
                reason == null ? "-" : reason);
        return rfq;
    }

    /** 持久化非状态字段（截止日/标记/定标/关闭留痕列），乐观锁同口径 */
    public void persist(Rfq rfq) {
        LambdaUpdateWrapper<Rfq> uw = new LambdaUpdateWrapper<>();
        uw.eq(Rfq::getId, rfq.getId())
                .eq(Rfq::getVerNo, rfq.getVerNo())
                .set(Rfq::getQuoteDeadline, rfq.getQuoteDeadline())
                .set(Rfq::getSendMode, rfq.getSendMode())
                .set(Rfq::getSendStatus, rfq.getSendStatus())
                .set(Rfq::getSentDate, rfq.getSentDate())
                .set(Rfq::getTaxCodeNo, rfq.getTaxCodeNo())
                .set(Rfq::getTechNote, rfq.getTechNote())
                .set(Rfq::getEmergencyFlag, rfq.getEmergencyFlag())
                .set(Rfq::getInsufficientFlag, rfq.getInsufficientFlag())
                .set(Rfq::getWeightPrice, rfq.getWeightPrice())
                .set(Rfq::getWeightDelivery, rfq.getWeightDelivery())
                .set(Rfq::getAwardSupplierId, rfq.getAwardSupplierId())
                .set(Rfq::getAwardPrice, rfq.getAwardPrice())
                .set(Rfq::getAnalysisNo, rfq.getAnalysisNo())
                .set(Rfq::getAnalysisConclusion, rfq.getAnalysisConclusion())
                .set(Rfq::getCloseReason, rfq.getCloseReason())
                .set(Rfq::getVerNo, rfq.getVerNo() + 1);
        if (rfqDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        rfq.setVerNo(rfq.getVerNo() + 1);
    }

    /** 头事件：v = verNo+1（创建 verNo=0 → v1；每次动作序号天然唯一） */
    public void publishHead(Rfq rfq, String eventType, String diff) {
        outbox.publish(eventType, rfq.getRfqNo() + ":HEAD", rfq.getVerNo() + 1, null, diff);
    }

    /** RFQ 单号 RFQ-YYYYMMDD-NNN（MAX 序号忽略软删空洞，唯一索引兜底并发） */
    public String nextRfqNo() {
        String prefix = "RFQ-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = rfqDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    public Rfq requireRfq(String id) {
        Rfq rfq = rfqDao.selectById(id);
        if (rfq == null) {
            throw new ServiceException(404, "询价单不存在");
        }
        return rfq;
    }
}
