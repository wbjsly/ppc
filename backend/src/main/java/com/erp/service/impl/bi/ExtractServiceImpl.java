package com.erp.service.impl.bi;

import com.erp.common.ServiceException;
import com.erp.dao.bi.BiCostSnapshotDao;
import com.erp.dao.bi.BiExtractTaskDao;
import com.erp.dao.bi.BiPriceAlertDao;
import com.erp.entity.bi.BiExtractTask;
import com.erp.service.bi.ExtractService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽取实现（spec bi-data-pipeline）。
 * 聚合窗口：近 3 个自然月（当月暂估中 + 上月/上上月完整月，供异动环比与趋势）。
 * 异动判定：批次日的上一个完整月（幂等 upsert，不触碰处置状态）。
 */
@Slf4j
@Service
public class ExtractServiceImpl implements ExtractService {

    private static final DateTimeFormatter MT = DateTimeFormatter.ofPattern("yyyyMM");

    private final BiExtractTaskDao taskDao;
    private final BiCostSnapshotDao snapshotDao;
    private final BiPriceAlertDao alertDao;
    private com.erp.service.bi.PriceMonitorService priceMonitorService;

    @Value("${app.bi.extract-fluctuation-x:10}")
    private int fluctuationX;
    @Value("${app.bi.extract-retry-max:3}")
    private int retryMax;
    @Value("${app.bi.price-trend-alert-pct:10}")
    private BigDecimal alertThreshold;

    public ExtractServiceImpl(BiExtractTaskDao taskDao, BiCostSnapshotDao snapshotDao,
                              BiPriceAlertDao alertDao,
                              com.erp.service.bi.PriceMonitorService priceMonitorService) {
        this.taskDao = taskDao;
        this.snapshotDao = snapshotDao;
        this.alertDao = alertDao;
        this.priceMonitorService = priceMonitorService;
    }

    /** 阈值在线优先（监测页调整后对新判定生效） */
    private BigDecimal alertThresholdNow() {
        return priceMonitorService.thresholdValue("PRICE_TREND_ALERT_PCT", alertThreshold);
    }

    @Override
    public Map<String, Object> runBatch(LocalDate date, boolean manual) {
        List<BiExtractTask> tasks = ensureTasks(date);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchDate", date.toString());
        out.put("manual", manual);

        int executed = 0;
        boolean blocked = false;
        for (BiExtractTask t : tasks) {
            if (BiExtractTask.ST_READY.equals(t.getStatus())) {
                continue;
            }
            if (BiExtractTask.ST_PAUSED.equals(t.getStatus()) && !manual) {
                blocked = true;                       // 波动暂停：等人工确认
                out.put("pausedAt", t.getTaskType());
                break;
            }
            if (BiExtractTask.ST_FAILED.equals(t.getStatus()) && t.getRetry() != null
                    && t.getRetry() >= retryMax && !manual) {
                blocked = true;
                out.put("failedAt", t.getTaskType());
                break;
            }
            if (blocked) {
                break;
            }
            // DAG 依赖：任一上游非 READY 即挂起（design D9）
            if (upstreamNotReady(tasks, t)) {
                out.put("waitingUpstream", t.getTaskType());
                break;
            }
            boolean ok = execute(t, manual);
            executed++;
            if (!ok) {
                break;                                // 失败：RETRY 已自增，下一步等重试
            }
        }
        out.put("executed", executed);
        out.put("tasks", taskDao.selectBatch(date));
        return out;
    }

    private boolean upstreamNotReady(List<BiExtractTask> tasks, BiExtractTask t) {
        for (BiExtractTask u : tasks) {
            if (u.getSeq() != null && t.getSeq() != null && u.getSeq() < t.getSeq()
                    && !BiExtractTask.ST_READY.equals(u.getStatus())) {
                return true;
            }
        }
        return false;
    }

    /** 执行单个步骤；返回是否成功 */
    private boolean execute(BiExtractTask t, boolean manual) {
        t.setStatus(BiExtractTask.ST_RUNNING);
        taskDao.updateById(t);
        try {
            long rows;
            switch (t.getTaskType()) {
                case BiExtractTask.TYPE_PO:
                    rows = nz(taskDao.countPo(monthAgo(0), monthAgo(1)));
                    break;
                case BiExtractTask.TYPE_GR:
                    rows = nz(taskDao.countGr(monthAgo(0), monthAgo(1)));
                    break;
                case BiExtractTask.TYPE_AP:
                    rows = nz(taskDao.countAp(monthAgo(0), monthAgo(1)));
                    break;
                case BiExtractTask.TYPE_COST:
                    rows = aggregateThreeMonths(t.getBatchNo());
                    break;
                case BiExtractTask.TYPE_ALERT: {
                    YearMonth last = YearMonth.from(t.getBatchDate()).minusMonths(1);
                    int n = alertDao.detectMonth(last.format(MT), alertThresholdNow(), "EXTRACT");
                    // ALERT_NO 首插为 000000 后缀问题已在 DAO 修正；此处补刷单号
                    rows = n;
                    break;
                }
                case BiExtractTask.TYPE_SAVE: {
                    // 降本为派生口径（design D5/D2）：近两月快照 READY 即就绪，无数值落库
                    BiExtractTask cur = taskDao.selectBatchType(t.getBatchDate(), BiExtractTask.TYPE_COST);
                    BiExtractTask prev = taskDao.selectBatchType(t.getBatchDate().minusDays(1),
                            BiExtractTask.TYPE_COST);
                    if (cur == null || !BiExtractTask.ST_READY.equals(cur.getStatus())) {
                        t.setStatus(BiExtractTask.ST_PENDING);
                        taskDao.updateById(t);
                        return false;
                    }
                    rows = prev == null ? 0L : 1L;
                    break;
                }
                default:
                    rows = 0;
            }

            // 波动判定（spec：>前日 N 倍暂停）
            Long prevRows = taskDao.selectPrevRowCount(t.getBatchDate().minusDays(1), t.getTaskType());
            t.setPrevRowCount(prevRows);
            t.setRowCount(rows);
            if (prevRows != null && prevRows > 0 && rows > prevRows * fluctuationX) {
                t.setStatus(BiExtractTask.ST_PAUSED);
                t.setErrorMsg("数据量波动超 " + fluctuationX + " 倍（前日 " + prevRows + " → 本批 " + rows + "），已暂停待确认");
                t.setManualConfirm(manual);
                taskDao.updateById(t);
                log.warn("bi extract paused by fluctuation: {} {}", t.getTaskType(), t.getErrorMsg());
                return false;
            }

            t.setStatus(BiExtractTask.ST_READY);
            t.setFinishedAt(LocalDateTime.now());
            t.setErrorMsg(null);
            t.setWatermark(String.valueOf(rows));
            taskDao.updateById(t);
            return true;
        } catch (Exception e) {
            int retry = (t.getRetry() == null ? 0 : t.getRetry()) + 1;
            t.setRetry(retry);
            t.setErrorMsg(e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage().substring(0, Math.min(480, e.getMessage().length())));
            t.setStatus(retry >= retryMax ? BiExtractTask.ST_FAILED : BiExtractTask.ST_PENDING);
            taskDao.updateById(t);
            log.warn("bi extract step failed {} (retry {}/{}): {}", t.getTaskType(), retry, retryMax,
                    e.getMessage());
            return false;
        }
    }

    /** 近 3 个自然月聚合（当月 + 上月 + 上上月），返回受影响快照行数估算 */
    private long aggregateThreeMonths(String batchNo) {
        long total = 0;
        YearMonth now = YearMonth.now();
        for (int i = 0; i < 3; i++) {
            YearMonth ym = now.minusMonths(i);
            String m = ym.format(MT);
            String start = ym.atDay(1).toString();
            String next = ym.plusMonths(1).atDay(1).toString();
            total += snapshotDao.upsertFromPo(batchNo, start, next);
            total += snapshotDao.upsertFromInvoice(batchNo, start, next);
            total += snapshotDao.upsertFromReturn(batchNo, start, next);
            snapshotDao.finalizeMonth(m);
        }
        return total;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private String monthAgo(int n) {
        return YearMonth.now().minusMonths(n).atDay(1).toString();
    }

    private List<BiExtractTask> ensureTasks(LocalDate date) {
        List<BiExtractTask> existing = taskDao.selectBatch(date);
        if (!existing.isEmpty()) {
            return existing;
        }
        String[][] defs = {
                {BiExtractTask.TYPE_PO, "10"},
                {BiExtractTask.TYPE_GR, "20"},
                {BiExtractTask.TYPE_AP, "30"},
                {BiExtractTask.TYPE_COST, "40"},
                {BiExtractTask.TYPE_ALERT, "50"},
                {BiExtractTask.TYPE_SAVE, "60"}
        };
        String batchNo = date.toString().replace("-", "");
        for (String[] d : defs) {
            BiExtractTask t = new BiExtractTask();
            t.setBatchNo(batchNo);
            t.setBatchDate(date);
            t.setTaskType(d[0]);
            t.setSeq(Integer.parseInt(d[1]));
            t.setStatus(BiExtractTask.ST_PENDING);
            t.setRetry(0);
            t.setManualConfirm(false);
            taskDao.insert(t);
        }
        return taskDao.selectBatch(date);
    }

    @Override
    public List<BiExtractTask> batchTasks(LocalDate date) {
        List<BiExtractTask> list = taskDao.selectBatch(date);
        if (list.isEmpty()) {
            list = ensureTasks(date);
        }
        return list;
    }

    @Override
    public Map<String, Object> readySnapshot() {
        BiExtractTask t = taskDao.selectLatestReadySnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        if (t == null) {
            out.put("ready", false);
            out.put("hint", "数据待就绪");
            return out;
        }
        out.put("ready", true);
        out.put("batchNo", t.getBatchNo());
        out.put("batchDate", t.getBatchDate());
        out.put("finishedAt", t.getFinishedAt());
        // 今日批次是否已 READY（区分 T-1 与当批）
        BiExtractTask today = taskDao.selectBatchType(LocalDate.now(), BiExtractTask.TYPE_COST);
        out.put("todayReady", today != null && BiExtractTask.ST_READY.equals(today.getStatus()));
        return out;
    }

    @Override
    public Map<String, Object> confirmPause(String taskId) {
        BiExtractTask t = taskDao.selectById(taskId);
        if (t == null) {
            throw new ServiceException(404, "批次任务不存在");
        }
        if (!BiExtractTask.ST_PAUSED.equals(t.getStatus())) {
            throw new ServiceException(422, "任务非暂停状态：" + t.getStatus());
        }
        t.setManualConfirm(true);
        t.setStatus(BiExtractTask.ST_PENDING);
        t.setErrorMsg((t.getErrorMsg() == null ? "" : t.getErrorMsg()) + "（已人工确认继续）");
        taskDao.updateById(t);
        return Map.of("id", t.getId(), "status", t.getStatus());
    }
}
