package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamSafetyLockDao;
import com.erp.dao.eam.EamWorkOrderDao;
import com.erp.dao.eam.EamWorkOrderTimeDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamWorkOrder;
import com.erp.service.eam.EamMaintainStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备履历与维修分析（add-asset-maintenance，design D2/D7）。
 * MTBF 口径（design 风险项默认）：相邻故障报修时间间隔天数均值；
 * MTTR：报修到验收通过小时均值。
 */
@Slf4j
@Service
public class EamMaintainStatsServiceImpl implements EamMaintainStatsService {

    private final EamWorkOrderDao woDao;
    private final EamWorkOrderTimeDao timeDao;
    private final EamSafetyLockDao lockDao;
    private final EamAssetCardDao cardDao;
    private final JdbcTemplate jdbc;

    public EamMaintainStatsServiceImpl(EamWorkOrderDao woDao,
                                       EamWorkOrderTimeDao timeDao,
                                       EamSafetyLockDao lockDao,
                                       EamAssetCardDao cardDao,
                                       JdbcTemplate jdbc) {
        this.woDao = woDao;
        this.timeDao = timeDao;
        this.lockDao = lockDao;
        this.cardDao = cardDao;
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Object> maintenanceHistory(String cardId) {
        requireCard(cardId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardId", cardId);
        out.put("workOrders", woDao.selectList(new LambdaQueryWrapper<EamWorkOrder>()
                .eq(EamWorkOrder::getCardId, cardId)
                .orderByDesc(EamWorkOrder::getCreateDate)));
        out.put("safetyLocks", lockDao.selectList(new LambdaQueryWrapper<com.erp.entity.eam.EamSafetyLock>()
                .eq(com.erp.entity.eam.EamSafetyLock::getCardId, cardId)
                .orderByDesc(com.erp.entity.eam.EamSafetyLock::getLockTime)));
        // 工时按卡片工单聚合
        out.put("times", jdbc.queryForList(
                "SELECT t.WO_NO, t.WORKER, t.POST_TYPE, t.WORK_HOURS, t.WORK_DATE "
                        + "FROM erp_eam_work_order_time t "
                        + "JOIN erp_eam_work_order w ON w.WO_NO = t.WO_NO "
                        + "WHERE w.CARD_ID = ? AND t.DEL_FLAG = '0' ORDER BY t.WORK_DATE DESC", cardId));
        // 提示留痕（大修评估锚工单号、资本化提示锚卡片号）
        EamAssetCard card = cardDao.selectById(cardId);
        String cardNo = card == null ? null : card.getCardNo();
        out.put("notices", jdbc.queryForList(
                "SELECT TITLE, CONTENT, BIZ_TYPE, BIZ_ID, CREATE_DATE FROM erp_sys_notice "
                        + "WHERE (BIZ_TYPE IN ('AssetOverhaul', 'MaintBudgetDeviation') "
                        + "AND (BIZ_ID = ? OR BIZ_ID IN (SELECT WO_NO FROM erp_eam_work_order WHERE CARD_ID = ?))) "
                        + "ORDER BY CREATE_DATE DESC", cardNo, cardId));
        return out;
    }

    @Override
    public Map<String, Object> stats(String cardId, int months) {
        requireCard(cardId);
        if (months <= 0) {
            months = 12;
        }
        // 故障报修时间序列（验收通过的 REPAIR 工单）
        List<LocalDateTime> faults = jdbc.query(
                "SELECT FAULT_TIME FROM erp_eam_work_order WHERE CARD_ID = ? AND WO_TYPE = 'REPAIR' "
                        + "AND FAULT_TIME >= DATE_SUB(NOW(), INTERVAL ? MONTH) "
                        + "AND DEL_FLAG = '0' ORDER BY FAULT_TIME",
                (rs, i) -> rs.getTimestamp("FAULT_TIME").toLocalDateTime(), cardId, months);

        // MTBF：相邻间隔天数均值
        BigDecimal mtbf = null;
        if (faults.size() >= 2) {
            BigDecimal gapSum = BigDecimal.ZERO;
            int gaps = 0;
            for (int i = 1; i < faults.size(); i++) {
                long hours = java.time.Duration.between(faults.get(i - 1), faults.get(i)).toHours();
                gapSum = gapSum.add(BigDecimal.valueOf(hours).divide(BigDecimal.valueOf(24), 2,
                        RoundingMode.HALF_UP));
                gaps++;
            }
            mtbf = gapSum.divide(BigDecimal.valueOf(gaps), 2, RoundingMode.HALF_UP);
        }

        // MTTR：报修→验收通过小时均值
        BigDecimal mttr = jdbc.queryForObject(
                "SELECT AVG(TIMESTAMPDIFF(HOUR, FAULT_TIME, ACCEPT_DATE)) FROM erp_eam_work_order "
                        + "WHERE CARD_ID = ? AND WO_TYPE = 'REPAIR' AND ACCEPT_DATE IS NOT NULL "
                        + "AND FAULT_TIME >= DATE_SUB(NOW(), INTERVAL ? MONTH) AND DEL_FLAG = '0'",
                BigDecimal.class, cardId, months);

        // 维修费用按月趋势（已关闭工单成本）
        List<Map<String, Object>> trend = jdbc.queryForList(
                "SELECT DATE_FORMAT(CLOSE_DATE, '%Y-%m') AS month, "
                        + "COALESCE(SUM(TOTAL_COST), 0) AS cost, COUNT(*) AS woCount "
                        + "FROM erp_eam_work_order WHERE CARD_ID = ? AND STATUS = 'CLOSED' "
                        + "AND CLOSE_DATE >= DATE_SUB(NOW(), INTERVAL ? MONTH) AND DEL_FLAG = '0' "
                        + "GROUP BY DATE_FORMAT(CLOSE_DATE, '%Y-%m') ORDER BY month", cardId, months);

        // 环比异常偏高 L4 预警（末月 > 前一月 ×1.5）
        boolean costSpike = false;
        if (trend.size() >= 2) {
            BigDecimal last = new BigDecimal(String.valueOf(trend.get(trend.size() - 1).get("cost")));
            BigDecimal prev = new BigDecimal(String.valueOf(trend.get(trend.size() - 2).get("cost")));
            costSpike = prev.signum() > 0
                    && last.compareTo(prev.multiply(new BigDecimal("1.5"))) > 0;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardId", cardId);
        out.put("months", months);
        out.put("faultCount", faults.size());
        out.put("mtbfDays", mtbf);
        out.put("mttrHours", mttr == null ? null : mttr.setScale(2, RoundingMode.HALF_UP));
        out.put("costTrend", trend);
        out.put("costSpike", costSpike);
        return out;
    }

    private void requireCard(String cardId) {
        EamAssetCard card = cardDao.selectById(cardId);
        if (card == null) {
            throw new ServiceException(422, "资产卡片不存在：" + cardId);
        }
    }
}
