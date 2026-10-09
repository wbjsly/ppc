package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoLine;
import com.erp.ops.OutboxPublisher;
import com.erp.service.sd.ReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SO 确认组件（tasks 7.7，spec sales-order BR-4.3-29）。
 *
 * 与 {@code QuotePublisher} 同范式：审批回调（SoApprovalCallback）与 SoService（自动确认档）
 * 共用同一确认动作，避免回调注入 Service 形成与审批引擎的构造循环依赖。
 * 同事务三件事：① 按行锁批次生成预留（锁不足 → 422 整体回滚，SO 保持待确认）
 * ② SO 置「已确认」+ 清在途审批（显式 UPDATE，MP updateById 忽略 null）
 * ③ 发布 {@code SO.CONFIRMED} 事件入 outbox 供库存域订阅。
 */
@Slf4j
@Component
public class SoConfirmSupport {

    private final SoDao soDao;
    private final SoLineDao soLineDao;
    private final ReservationService reservationService;
    private final OutboxPublisher outboxPublisher;

    public SoConfirmSupport(SoDao soDao,
                            SoLineDao soLineDao,
                            ReservationService reservationService,
                            OutboxPublisher outboxPublisher) {
        this.soDao = soDao;
        this.soLineDao = soLineDao;
        this.reservationService = reservationService;
        this.outboxPublisher = outboxPublisher;
    }

    /**
     * 执行确认。幂等：已 CONFIRMED 直接返回。
     *
     * @param operator 审批通过时 = 审批人；自动档 = 提交人
     */
    @Transactional
    public So confirm(String soId, String operator) {
        So so = soDao.selectById(soId);
        if (so == null) {
            throw new ServiceException(404, "销售订单不存在：" + soId);
        }
        if (So.ST_CONFIRMED.equals(so.getStatus())) {
            return so; // 幂等（自动档与回调竞争）
        }
        if (So.ST_CLOSED.equals(so.getStatus()) || So.ST_CANCELLED.equals(so.getStatus())) {
            throw new ServiceException(422, "订单已" + statusName(so.getStatus()) + "，不可确认");
        }
        if (So.ST_CREDIT_FREEZE.equals(so.getStatus())) {
            throw new ServiceException(422, "订单处于信用冻结挂起态，解冻后方可确认");
        }
        List<SoLine> lines = soLineDao.selectList(new LambdaQueryWrapper<SoLine>()
                .eq(SoLine::getSoId, soId)
                .orderByAsc(SoLine::getLineNo));
        if (lines.isEmpty()) {
            throw new ServiceException(422, "订单无明细行，不可确认");
        }
        // 7.5：存在锁行且价格变更审批未通过 → 禁止确认（BR-4.3-26）
        long locked = lines.stream().filter(l -> "1".equals(l.getPriceLocked())).count();
        if (locked > 0) {
            throw new ServiceException(422, "存在 " + locked
                    + " 行手动改价待价格变更审批，审批通过前禁止确认（BR-4.3-26）");
        }
        // ① 同事务锁批次生成预留（锁不足 → 抛 422 → 本事务回滚，SO 停在原状态）
        reservationService.reserveForSo(so, lines);

        // ② 置已确认 + 清在途审批（显式 UPDATE；MP updateById 忽略 null）
        // 挂起态（变更中）解除 → 回前一稳定状态；常规路径 DRAFT/PENDING → CONFIRMED（业务逻辑 6）
        String back = so.getPrevStatus();
        so.setStatus(back == null || back.isEmpty() ? So.ST_CONFIRMED : back);
        so.setPrevStatus(null);
        soDao.updateById(so);
        soDao.update(null, new LambdaUpdateWrapper<So>()
                .eq(So::getId, soId)
                .set(So::getApprovalId, null));
        // ③ 发布 SO.CONFIRMED 事件（recordVersion = EVENT_VERSION+1，幂等键 SO_NO:vN）
        so.setEventVersion(so.getEventVersion() == null ? 1 : so.getEventVersion() + 1);
        soDao.update(null, new LambdaUpdateWrapper<So>()
                .eq(So::getId, soId)
                .set(So::getEventVersion, so.getEventVersion()));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("orderType", so.getOrderType());
        extra.put("totalAmount", so.getTotalAmount());
        extra.put("lineCount", lines.size());
        extra.put("confirmedBy", operator);
        outboxPublisher.publishSourced("SO.CONFIRMED", so.getSoNo(),
                so.getEventVersion(), null, "SO 确认：生成批次预留并生效",
                extra, "sd-service");
        log.info("SO {} confirmed by {} ({} reservations effective)",
                so.getSoNo(), operator, lines.size());
        return so;
    }

    private static String statusName(String s) {
        switch (s == null ? "" : s) {
            case So.ST_CLOSED:
                return "关闭";
            case So.ST_CANCELLED:
                return "取消";
            default:
                return s;
        }
    }
}
