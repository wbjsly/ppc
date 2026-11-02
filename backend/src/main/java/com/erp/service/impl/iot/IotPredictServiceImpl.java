package com.erp.service.impl.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamWorkOrderDao;
import com.erp.dao.iot.IotAlarmEventDao;
import com.erp.entity.eam.EamWorkOrder;
import com.erp.entity.iot.IotAlarmEvent;
import com.erp.service.iot.IotPredictService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预测维护实现（add-iot-mobile 13.8，FR-4.11-8-x / BR-4.11-48~53）：
 * 趋势告警消费评估 → 幂等建单（LINK_WO_ID=告警事件 ID，C-4.11-10 防重复）→
 * 误报率统计（30 天窗口，超 20% 提示复核阈值）→ 工单完成回写闭环。
 */
@Slf4j
@Service
public class IotPredictServiceImpl implements IotPredictService {

    private final IotAlarmEventDao alarmDao;
    private final EamWorkOrderDao workOrderDao;
    private final JdbcTemplate jdbc;

    public IotPredictServiceImpl(IotAlarmEventDao alarmDao, EamWorkOrderDao workOrderDao,
                                 JdbcTemplate jdbc) {
        this.alarmDao = alarmDao;
        this.workOrderDao = workOrderDao;
        this.jdbc = jdbc;
    }

    @Override
    public List<Map<String, Object>> trendCandidates() {
        // 待评估趋势告警：TREND 类且未建单
        List<IotAlarmEvent> events = alarmDao.selectList(new LambdaQueryWrapper<IotAlarmEvent>()
                .eq(IotAlarmEvent::getAlertType, IotAlarmEvent.TYPE_TREND)
                .isNull(IotAlarmEvent::getLinkWoId)
                .orderByDesc(IotAlarmEvent::getOccurredAt));
        List<Map<String, Object>> out = new ArrayList<>();
        for (IotAlarmEvent ev : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventNo", ev.getEventNo());
            m.put("deviceNo", ev.getDeviceNo());
            m.put("ruleSnap", ev.getRuleSnap());
            m.put("occurredAt", ev.getOccurredAt());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createWoIdempotent(String eventNo) {
        IotAlarmEvent ev = alarmDao.selectOne(new LambdaQueryWrapper<IotAlarmEvent>()
                .eq(IotAlarmEvent::getEventNo, eventNo));
        if (ev == null) {
            throw new ServiceException(404, "告警事件不存在：" + eventNo);
        }
        // 幂等（BR-4.11-53 / C-4.11-10）：事件已建单 → 返回既有单 409 幂等语义
        if (ev.getLinkWoId() != null && !ev.getLinkWoId().isBlank()) {
            Map<String, Object> dup = new LinkedHashMap<>();
            dup.put("duplicate", true);
            dup.put("woNo", ev.getLinkWoId());
            dup.put("eventNo", eventNo);
            return dup;
        }
        EamWorkOrder wo = new EamWorkOrder();
        wo.setWoNo("WO-PM-" + System.currentTimeMillis());
        wo.setWoType("PREVENTIVE");
        wo.setSrcType("ALARM");
        // 幂等键 = 告警事件 ID（重复触发不重复建单）
        wo.setIdempotentKey("IOT-ALARM:" + ev.getId());
        String cardNo = jdbc.query(
                "SELECT CARD_NO FROM erp_eam_asset_card WHERE DEL_FLAG='0' ORDER BY CREATE_DATE DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null);
        String cardId = cardNo == null ? "CARD-UNBOUND" : jdbc.query(
                "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO=? AND DEL_FLAG='0' LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, cardNo);
        wo.setCardId(cardId == null ? "CARD-UNBOUND" : cardId);
        wo.setCardNo(cardNo == null ? "UNBOUND" : cardNo);
        wo.setPlanDesc("预测维护派工：" + ev.getDeviceNo()
                + "（趋势告警 " + ev.getEventNo() + "）");
        wo.setStatus("PENDING");
        wo.setCreateBy(SecurityUtils.getCurrentUserId());
        workOrderDao.insert(wo);
        // 回写 LINK_WO_ID（闭环锚点）
        ev.setLinkWoId(wo.getWoNo());
        alarmDao.updateById(ev);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("duplicate", false);
        out.put("woNo", wo.getWoNo());
        out.put("eventNo", eventNo);
        return out;
    }

    @Override
    public Map<String, Object> falsePositiveRate() {
        // 30 天窗口：TREND 告警转工单后判定非故障比例（BR-4.11-52）
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) total, " +
                        "COALESCE(SUM(CASE WHEN FALSE_POSITIVE='1' THEN 1 ELSE 0 END),0) fp " +
                        "FROM erp_iot_alarm_event WHERE ALERT_TYPE='TREND' AND DEL_FLAG='0' " +
                        "AND OCCURRED_AT >= DATE_SUB(NOW(), INTERVAL 30 DAY)");
        long total = ((Number) row.get("total")).longValue();
        long fp = ((Number) row.get("fp")).longValue();
        BigDecimal rate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(fp).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        boolean overThreshold = rate.compareTo(new BigDecimal("20")) > 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", 30);
        out.put("totalWo", total);
        out.put("falsePositive", fp);
        out.put("ratePct", rate);
        out.put("overThreshold", overThreshold);
        if (overThreshold) {
            out.put("hint", "误报率 " + rate + "% 超 20%，建议复核趋势阈值规则");
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> markFalsePositive(String eventNo, boolean fp) {
        IotAlarmEvent ev = alarmDao.selectOne(new LambdaQueryWrapper<IotAlarmEvent>()
                .eq(IotAlarmEvent::getEventNo, eventNo));
        if (ev == null) {
            throw new ServiceException(404, "告警事件不存在");
        }
        ev.setFalsePositive(fp ? "1" : "0");
        alarmDao.updateById(ev);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventNo", eventNo);
        out.put("falsePositive", fp);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> onWoClosed(String woNo) {
        // 工单完成 → 告警事件闭环回写（BR-4.11-53）
        Integer updated = jdbc.update(
                "UPDATE erp_iot_alarm_event SET STATUS='CLOSED', CLOSE_BY=?, CLOSE_AT=NOW() " +
                        "WHERE LINK_WO_ID=? AND STATUS<>'CLOSED' AND DEL_FLAG='0'",
                SecurityUtils.getCurrentUserId(), woNo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("woNo", woNo);
        out.put("closedAlarms", updated);
        return out;
    }
}
