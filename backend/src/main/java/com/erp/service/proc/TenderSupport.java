package com.erp.service.proc;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.proc.TenderDao;
import com.erp.entity.proc.Tender;
import com.erp.ops.OutboxPublisher;
import com.erp.procurement.TenderStateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 招标状态迁移与事件单点支撑（add-tender-bidding design D1，仿 RfqSupport）：
 * transition = 状态机校验 + 乐观锁写（verNo+1）+ 日志；事件键 TND单号:HEAD:v(verNo+1)。
 */
@Slf4j
@Component
public class TenderSupport {

    private final TenderDao tenderDao;
    private final OutboxPublisher outbox;

    public TenderSupport(TenderDao tenderDao, OutboxPublisher outbox) {
        this.tenderDao = tenderDao;
        this.outbox = outbox;
    }

    /** 状态迁移（单点）：状态机校验 + 乐观锁递增 + 日志 */
    public Tender transition(Tender tender, String to, String reason) {
        TenderStateMachine.require(tender.getStatus(), to);
        LambdaUpdateWrapper<Tender> uw = new LambdaUpdateWrapper<>();
        uw.eq(Tender::getId, tender.getId())
                .eq(Tender::getVerNo, tender.getVerNo())
                .set(Tender::getStatus, to)
                .set(Tender::getVerNo, tender.getVerNo() + 1);
        if (tenderDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        tender.setStatus(to);
        tender.setVerNo(tender.getVerNo() + 1);
        log.info("TENDER {} status -> {} reason={}", tender.getTenderNo(), to,
                reason == null ? "-" : reason);
        return tender;
    }

    /** 持久化非状态字段（截止/权重/定标/公示/标记/留痕列），乐观锁同口径 */
    public void persist(Tender tender) {
        LambdaUpdateWrapper<Tender> uw = new LambdaUpdateWrapper<>();
        uw.eq(Tender::getId, tender.getId())
                .eq(Tender::getVerNo, tender.getVerNo())
                .set(Tender::getTitle, tender.getTitle())
                .set(Tender::getTenderType, tender.getTenderType())
                .set(Tender::getEvalMethod, tender.getEvalMethod())
                .set(Tender::getCategoryCode, tender.getCategoryCode())
                .set(Tender::getCategoryName, tender.getCategoryName())
                .set(Tender::getEstAnnualQty, tender.getEstAnnualQty())
                .set(Tender::getTechSpec, tender.getTechSpec())
                .set(Tender::getQualifyReq, tender.getQualifyReq())
                .set(Tender::getRegDeadline, tender.getRegDeadline())
                .set(Tender::getQuoteDeadline, tender.getQuoteDeadline())
                .set(Tender::getInvitePending, tender.getInvitePending())
                .set(Tender::getWeightPrice, tender.getWeightPrice())
                .set(Tender::getWeightDelivery, tender.getWeightDelivery())
                .set(Tender::getWeightQuality, tender.getWeightQuality())
                .set(Tender::getWeightCooperation, tender.getWeightCooperation())
                .set(Tender::getAwardSupplierId, tender.getAwardSupplierId())
                .set(Tender::getAwardPrice, tender.getAwardPrice())
                .set(Tender::getAwardScore, tender.getAwardScore())
                .set(Tender::getPublicityStart, tender.getPublicityStart())
                .set(Tender::getPublicityEnd, tender.getPublicityEnd())
                .set(Tender::getObjectionFlag, tender.getObjectionFlag())
                .set(Tender::getAnomalyFlag, tender.getAnomalyFlag())
                .set(Tender::getAnomalyNote, tender.getAnomalyNote())
                .set(Tender::getQualifiedCount, tender.getQualifiedCount())
                .set(Tender::getAwardApprovalStatus, tender.getAwardApprovalStatus())
                .set(Tender::getAwardApprovedBy, tender.getAwardApprovedBy())
                .set(Tender::getAwardApprovedDate, tender.getAwardApprovedDate())
                .set(Tender::getAwardApprovalNote, tender.getAwardApprovalNote())
                .set(Tender::getAbortReason, tender.getAbortReason())
                .set(Tender::getCloseReason, tender.getCloseReason())
                .set(Tender::getVerNo, tender.getVerNo() + 1);
        if (tenderDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        tender.setVerNo(tender.getVerNo() + 1);
    }

    /** 头事件：v = verNo+1（创建 verNo=0 → v1） */
    public void publishHead(Tender tender, String eventType, String diff) {
        outbox.publish(eventType, tender.getTenderNo() + ":HEAD", tender.getVerNo() + 1, null, diff);
    }

    /** 招标编号 TND-YYYYMMDD-NNN，MAX 序号忽略软删空洞，唯一索引兜底并发 */
    public String nextTenderNo() {
        String prefix = "TND-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = tenderDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    public Tender requireTender(String id) {
        Tender t = tenderDao.selectById(id);
        if (t == null) {
            throw new ServiceException(404, "招标项目不存在");
        }
        return t;
    }
}
