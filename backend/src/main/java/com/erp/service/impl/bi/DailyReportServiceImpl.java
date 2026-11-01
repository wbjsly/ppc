package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.erp.common.AesUtil;
import com.erp.dao.bi.BiDailyReportDefDao;
import com.erp.dao.bi.BiDailyReportSubDao;
import com.erp.dao.bi.BiReportPushLogDao;
import com.erp.dao.bi.BiReportReceiptDao;
import com.erp.entity.bi.DailyReportDef;
import com.erp.entity.bi.DailyReportSub;
import com.erp.entity.bi.ReportPushLog;
import com.erp.entity.bi.ReportReceipt;
import com.erp.security.IntfGuard;
import com.erp.service.SysParamService;
import com.erp.common.ServiceException;
import com.erp.service.bi.ChannelSender;
import com.erp.service.bi.DailyReportService;
import com.erp.service.bi.PdfFileWriter;
import com.erp.service.bi.QueryGovernance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 日报订阅（add-daily-report-subscription，spec bi-daily-report）。
 * 数据源 = 14.7 KPI 快照（落库即就绪信号）；差异化生成 = 行级过滤 + PdfFileWriter 水印 PDF
 * + AES 加密落盘；三通道推送（NOTICE 真实 / MAIL·WECOM 模拟）+ 独立重试 + 双通道失败降级；
 * 打开日报详情页写回执；连续未阅读 L4 提示；触达率挂月度 SLA。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReportServiceImpl implements DailyReportService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter PERIOD_T = DateTimeFormatter.ofPattern("yyyy-MM");

    private final BiDailyReportDefDao defDao;
    private final BiDailyReportSubDao subDao;
    private final BiReportPushLogDao pushLogDao;
    private final BiReportReceiptDao receiptDao;
    private final JdbcTemplate jdbc;
    private final SysParamService sysParam;
    private final QueryGovernance governance;
    private final List<ChannelSender> senders;

    /** PDF 生成器（沿 14.5 ExportServiceImpl 先例：new 实例，无状态） */
    private final PdfFileWriter pdfWriter = new PdfFileWriter();

    @Value("${app.bi.daily-report-dir:./data/daily-report}")
    private String reportDir;

    @Value("${app.bi.conn-secret:erp-export-default-secret}")
    private String secret;

    // ---------------- 日报定义 ----------------

    @Override
    public List<Map<String, Object>> listDefs() {
        return jdbc.queryForList(
                "SELECT d.ID AS id, d.CODE AS code, d.NAME AS name, d.METRIC_KEYS_JSON AS metricKeysJson, "
                        + "d.PUSH_TIME AS pushTime, d.STATUS AS status, d.REMARK AS remark, "
                        + "(SELECT COUNT(*) FROM erp_bi_daily_report_sub s WHERE s.REPORT_ID=d.ID "
                        + " AND s.STATUS='ACTIVE' AND s.DEL_FLAG='0') AS subCount "
                        + "FROM erp_bi_daily_report_def d WHERE d.DEL_FLAG='0' ORDER BY d.CREATE_DATE DESC");
    }

    @Override
    public Map<String, Object> getDef(String id) {
        Map<String, Object> d = jdbc.queryForMap(
                "SELECT ID AS id, CODE AS code, NAME AS name, METRIC_KEYS_JSON AS metricKeysJson, "
                        + "PUSH_TIME AS pushTime, STATUS AS status, REMARK AS remark "
                        + "FROM erp_bi_daily_report_def WHERE ID=? AND DEL_FLAG='0'", id);
        return d;
    }

    @Override
    public Map<String, Object> saveDef(Map<String, Object> payload) {
        String code = str(payload.get("code"));
        String name = str(payload.get("name"));
        String metricKeysJson = str(payload.get("metricKeysJson"));
        String pushTime = str(payload.get("pushTime"));
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new ServiceException(422, "日报编码与名称必填");
        }
        if (metricKeysJson == null || parseMetrics(metricKeysJson).isEmpty()) {
            throw new ServiceException(422, "指标清单不能为空");
        }
        if (pushTime == null || pushTime.isBlank()) {
            pushTime = "07:30";
        }
        try {
            HM.parse(pushTime);
        } catch (Exception e) {
            throw new ServiceException(422, "推送时间格式须为 HH:mm");
        }
        // 指标清单校验：kpiKey 须存在于 14.7 KPI 定义（422 提示先注册）
        List<Map<String, Object>> metrics = parseMetrics(metricKeysJson);
        for (Map<String, Object> m : metrics) {
            String kpiKey = str(m.get("kpiKey"));
            if (kpiKey == null || kpiKey.isBlank()) {
                throw new ServiceException(422, "指标清单缺少 kpiKey");
            }
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_bi_kpi_def WHERE KPI_KEY=? AND DEL_FLAG='0'",
                    Integer.class, kpiKey);
            if (cnt == null || cnt == 0) {
                throw new ServiceException(422, "指标 [" + kpiKey + "] 未在经营看板 KPI 定义中注册");
            }
        }
        String id = str(payload.get("id"));
        DailyReportDef d = new DailyReportDef();
        if (id == null || id.isBlank()) {
            d.setId(uuid());
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_bi_daily_report_def WHERE CODE=? AND DEL_FLAG='0'",
                    Integer.class, code);
            if (dup != null && dup > 0) {
                throw new ServiceException(422, "日报编码已存在");
            }
            d.setCode(code);
            d.setMetricKeysJson(metricKeysJson);
            d.setPushTime(pushTime);
            d.setStatus(DailyReportDef.ST_ENABLED);
            d.setRemark(str(payload.get("remark")));
            d.setName(name);
            defDao.insert(d);
        } else {
            d = defDao.selectById(id);
            if (d == null) {
                throw new ServiceException(404, "日报定义不存在");
            }
            d.setName(name);
            d.setMetricKeysJson(metricKeysJson);
            d.setPushTime(pushTime);
            d.setRemark(str(payload.get("remark")));
            defDao.updateById(d);
        }
        return Map.of("id", d.getId());
    }

    @Override
    public void changeDefStatus(String id, String status) {
        DailyReportDef d = defDao.selectById(id);
        if (d == null) {
            throw new ServiceException(404, "日报定义不存在");
        }
        if (!DailyReportDef.ST_ENABLED.equals(status) && !DailyReportDef.ST_DISABLED.equals(status)) {
            throw new ServiceException(422, "状态非法");
        }
        d.setStatus(status);
        defDao.updateById(d);
    }

    @Override
    public void deleteDef(String id) {
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_daily_report_sub WHERE REPORT_ID=? AND STATUS='ACTIVE' "
                        + "AND DEL_FLAG='0'", Integer.class, id);
        if (active != null && active > 0) {
            throw new ServiceException(422, "存在 ACTIVE 订阅，请先停用订阅再删除日报定义");
        }
        defDao.deleteById(id);
    }

    // ---------------- 订阅（本人维度） ----------------

    @Override
    public List<Map<String, Object>> mySubscriptions() {
        String user = IntfGuard.currentUser();
        return jdbc.queryForList(
                "SELECT s.ID AS id, s.REPORT_ID AS reportId, d.NAME AS reportName, d.CODE AS reportCode, "
                        + "s.CHANNELS_JSON AS channelsJson, s.STATUS AS status, s.LAST_PUSH_DATE AS lastPushDate, "
                        + "s.LAST_READ_AT AS lastReadAt, s.LAST_PROMPT_AT AS lastPromptAt "
                        + "FROM erp_bi_daily_report_sub s JOIN erp_bi_daily_report_def d ON d.ID=s.REPORT_ID "
                        + "WHERE s.USER_NAME=? AND s.DEL_FLAG='0' AND d.DEL_FLAG='0' ORDER BY s.CREATE_DATE DESC",
                user);
    }

    @Override
    public Map<String, Object> saveSubscription(Map<String, Object> payload) {
        String user = IntfGuard.currentUser();
        String reportId = str(payload.get("reportId"));
        DailyReportDef def = defDao.selectById(reportId);
        if (def == null || !DailyReportDef.ST_ENABLED.equals(def.getStatus())) {
            throw new ServiceException(404, "日报定义不存在或未启用");
        }
        List<String> channels = parseChannels(payload.get("channelsJson"));
        // BR-4.10-35：指标权限校验——订阅人行级权限为空集 → 全部指标自动剔除 + L4 提示（不整单失败）
        List<Map<String, Object>> metrics = parseMetrics(def.getMetricKeysJson());
        List<String> allowed = governance.allowedEntities(user);
        List<Map<String, Object>> excluded = new ArrayList<>();
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> m : metrics) {
            if (allowed == null || allowed.isEmpty()) {
                excluded.add(m);
            } else {
                kept.add(m);
            }
        }
        DailyReportSub sub = new DailyReportSub();
        sub.setId(uuid());
        sub.setReportId(reportId);
        sub.setUserName(user);
        sub.setChannelsJson(toJson(channels));
        sub.setStatus(DailyReportSub.ST_ACTIVE);
        subDao.insert(sub);
        if (!excluded.isEmpty()) {
            // L4 提示清单（去重提示：不阻断订阅）
            StringBuilder sb = new StringBuilder("以下指标超出您的数据权限，已自动剔除：");
            for (Map<String, Object> m : excluded) {
                sb.append(str(m.get("title"))).append("（").append(str(m.get("kpiKey"))).append("） ");
            }
            sb.append("；其余 ").append(kept.size()).append(" 项指标正常订阅。");
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, ?, 'DailyReportSubExcl', ?, ?, ?, '0', 'system', NOW(), '0', 0)",
                    uuid(), user, sub.getId(), "日报订阅指标剔除提示", sb.toString());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", sub.getId());
        out.put("excludedKpis", excluded.stream().map(m -> str(m.get("kpiKey"))).collect(Collectors.toList()));
        return out;
    }

    @Override
    public void updateSubscription(String id, List<String> channels) {
        DailyReportSub sub = subDao.selectById(id);
        if (sub == null) {
            throw new ServiceException(404, "订阅不存在");
        }
        checkSubOwner(sub);
        sub.setChannelsJson(toJson(normalizeChannels(channels)));
        subDao.updateById(sub);
    }

    @Override
    public void unsubscribe(String id) {
        DailyReportSub sub = subDao.selectById(id);
        if (sub == null) {
            throw new ServiceException(404, "订阅不存在");
        }
        checkSubOwner(sub);
        sub.setStatus(DailyReportSub.ST_DISABLED);
        subDao.updateById(sub);
    }

    // ---------------- 推送与触达 ----------------

    @Override
    public Map<String, Object> pagePushLogs(String receiver, String status, int page, int size) {
        int p = Math.max(1, page);
        int sz = Math.min(200, Math.max(1, size));
        StringBuilder where = new StringBuilder(" WHERE DEL_FLAG='0'");
        List<Object> args = new ArrayList<>();
        if (receiver != null && !receiver.isBlank()) {
            where.append(" AND RECEIVER=?");
            args.add(receiver);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND SEND_STATUS=?");
            args.add(status);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM erp_bi_report_push_log" + where,
                Long.class, args.toArray());
        args.add((p - 1) * sz);
        args.add(sz);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ID AS id, SUB_ID AS subId, REPORT_CODE AS reportCode, PUSH_DATE AS pushDate, "
                        + "RECEIVER AS receiver, CHANNEL AS channel, SEND_STATUS AS sendStatus, "
                        + "RETRY_CNT AS retryCnt, DEGRADED_FLAG AS degradedFlag, DATA_CUTOFF AS dataCutoff, "
                        + "QUALITY_FLAG AS qualityFlag, ERROR_MSG AS errorMsg, CREATE_DATE AS createDate "
                        + "FROM erp_bi_report_push_log" + where + " ORDER BY PUSH_DATE DESC, CREATE_DATE DESC "
                        + "LIMIT ?, ?", args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("rows", rows);
        return out;
    }

    @Override
    public Map<String, Object> stats() {
        String period = LocalDate.now().format(PERIOD_T) + "-01";
        Map<String, Object> s = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, "
                        + "SUM(CASE WHEN SEND_STATUS IN ('SENT','DEGRADED') THEN 1 ELSE 0 END) AS sent, "
                        + "SUM(CASE WHEN SEND_STATUS='FAILED' THEN 1 ELSE 0 END) AS failed, "
                        + "SUM(CASE WHEN DEGRADED_FLAG='Y' THEN 1 ELSE 0 END) AS degraded "
                        + "FROM erp_bi_report_push_log WHERE DEL_FLAG='0' AND PUSH_DATE >= ?", period);
        long total = num(s.get("total"));
        long sent = num(s.get("sent"));
        long degraded = num(s.get("degraded"));
        Long read = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT l.RECEIVER) FROM erp_bi_report_receipt r "
                        + "JOIN erp_bi_report_push_log l ON l.ID=r.PUSH_LOG_ID "
                        + "WHERE r.DEL_FLAG='0' AND l.DEL_FLAG='0' AND l.PUSH_DATE >= ?",
                Long.class, period);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period.substring(0, 7));
        out.put("total", total);
        out.put("sent", sent);
        out.put("failed", num(s.get("failed")));
        out.put("degraded", degraded);
        out.put("sendRate", total == 0 ? null
                : BigDecimal2(sent * 100.0 / total));
        out.put("readReceivers", read == null ? 0 : read);
        out.put("readRate", sent == 0 ? null : BigDecimal2((read == null ? 0 : read) * 100.0 / sent));
        return out;
    }

    @Override
    public void markRead(String pushLogId) {
        Map<String, Object> logRow = jdbc.queryForMap(
                "SELECT ID AS id, RECEIVER AS receiver, SUB_ID AS subId FROM erp_bi_report_push_log "
                        + "WHERE ID=? AND DEL_FLAG='0'", pushLogId);
        String receiver = str(logRow.get("receiver"));
        String user = IntfGuard.currentUser();
        if (!user.equals(receiver) && !IntfGuard.isAdmin()) {
            throw new ServiceException(403, "仅接收人本人可记录阅读回执");
        }
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT ID AS id, OPEN_CNT AS openCnt FROM erp_bi_report_receipt "
                        + "WHERE PUSH_LOG_ID=? AND DEL_FLAG='0'", pushLogId);
        LocalDateTime now = LocalDateTime.now();
        if (existing.isEmpty()) {
            ReportReceipt r = new ReportReceipt();
            r.setId(uuid());
            r.setPushLogId(pushLogId);
            r.setOpenCnt(1);
            r.setOpenAt(now);
            receiptDao.insert(r);
        } else {
            ReportReceipt r = new ReportReceipt();
            r.setId(str(existing.get(0).get("id")));
            r.setPushLogId(pushLogId);
            r.setOpenCnt(num(existing.get(0).get("openCnt")) + 1 > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : (int) (num(existing.get(0).get("openCnt")) + 1));
            r.setOpenAt(now);
            receiptDao.updateById(r);
        }
        DailyReportSub sub = subDao.selectById(str(logRow.get("subId")));
        if (sub != null) {
            sub.setLastReadAt(now);
            subDao.updateById(sub);
        }
    }

    @Override
    public Map<String, Object> pushNow(String reportId) {
        DailyReportDef def = defDao.selectById(reportId);
        if (def == null) {
            throw new ServiceException(404, "日报定义不存在");
        }
        // 手动试推走完整就绪流程（与 07:00 cron 同语义：未就绪延迟等待+超时通知升级）
        return pushFlow(List.of(def), LocalDate.now(), true);
    }

    // ---------------- 调度入口 ----------------

    @Override
    public Map<String, Object> pushAll(LocalDate pushDate) {
        List<DailyReportDef> defs = defDao.selectList(new QueryWrapper<DailyReportDef>()
                .eq("STATUS", DailyReportDef.ST_ENABLED).eq("DEL_FLAG", "0"));
        return pushFlow(defs, pushDate, true);
    }

    /** 就绪检查（未就绪延迟重查上限 REPORT_READY_WAIT_MIN，超时通知订阅人+升级运维工单）→ 逐定义推送 */
    private Map<String, Object> pushFlow(List<DailyReportDef> defs, LocalDate pushDate,
                                         boolean markPushDate) {
        Map<String, Object> out = new LinkedHashMap<>();
        int waitMin = sysParam.getInt("REPORT_READY_WAIT_MIN", 120);
        int waited = 0;
        while (countSnapshot(pushDate) == 0) {
            if (waited >= waitMin) {
                // 超时：通知全部 ACTIVE 订阅人 + 运维工单（ADMIN），不生成当日日报
                List<Map<String, Object>> subs = jdbc.queryForList(
                        "SELECT ID, USER_NAME FROM erp_bi_daily_report_sub WHERE STATUS='ACTIVE' AND DEL_FLAG='0'");
                for (Map<String, Object> s : subs) {
                    jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, BIZ_TYPE, BIZ_ID, TITLE, "
                                    + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                    + "VALUES (?, ?, 'DailyReportNotReady', ?, ?, ?, '0', 'system', NOW(), '0', 0)",
                            uuid(), str(s.get("USER_NAME")), str(s.get("ID")),
                            "日报数据未就绪通知",
                            "截至 " + LocalDateTime.now().format(TS) + " 经营数据仍未就绪，今日日报未生成，请关注数据刷新。");
                }
                jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                                + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                + "VALUES (?, 'ROLE_ADMIN', 'DailyReportEscalate', ?, ?, ?, "
                                + "'0', 'system', NOW(), '0', 0)",
                        uuid(), String.valueOf(pushDate), "运维工单：日报数据超时未就绪",
                        "业务日 " + pushDate + " 的 KPI 快照超过 " + waitMin + " 分钟仍未就绪，日报推送已跳过，需 IT 排查数据刷新链路。");
                out.put("ready", false);
                out.put("timedOut", true);
                return out;
            }
            sleepMinutes(Math.min(10, waitMin - waited));
            waited += Math.min(10, waitMin - waited);
        }
        out.put("ready", true);
        out.put("dataCutoff", pushDate.toString());
        out.put("quality", hasQualityIssue(pushDate));
        int generated = 0;
        int sent = 0;
        int degraded = 0;
        int failed = 0;
        for (DailyReportDef def : defs) {
            Map<String, Object> r = pushDef(def, pushDate, markPushDate);
            generated += ((Number) r.get("generated")).intValue();
            sent += ((Number) r.get("sent")).intValue();
            degraded += ((Number) r.get("degraded")).intValue();
            failed += ((Number) r.get("failed")).intValue();
        }
        out.put("generated", generated);
        out.put("sent", sent);
        out.put("degraded", degraded);
        out.put("failed", failed);
        return out;
    }

    @Override
    public int scanUnreadAndPrompt() {
        int days = sysParam.getInt("REPORT_UNREAD_DAYS", 30);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ID, USER_NAME FROM erp_bi_daily_report_sub "
                        + "WHERE STATUS='ACTIVE' AND DEL_FLAG='0' AND LAST_PUSH_DATE IS NOT NULL "
                        + "AND LAST_PUSH_DATE <= DATE_SUB(CURDATE(), INTERVAL ? DAY) "
                        + "AND (LAST_READ_AT IS NULL OR LAST_READ_AT < LAST_PUSH_DATE) "
                        + "AND (LAST_PROMPT_AT IS NULL OR LAST_PROMPT_AT < DATE_SUB(NOW(), INTERVAL ? DAY))",
                days, days);
        for (Map<String, Object> r : rows) {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, ?, 'DailyReportUnread', ?, ?, ?, '0', 'system', NOW(), '0', 0)",
                    uuid(), str(r.get("USER_NAME")), str(r.get("ID")),
                    "日报订阅确认提示（L4）",
                    "您订阅的日报已连续 " + days + " 天未阅读，请确认是否继续订阅（如不再需要可在订阅配置中退订）。");
            jdbc.update("UPDATE erp_bi_daily_report_sub SET LAST_PROMPT_AT=NOW(), UPDATE_DATE=NOW() WHERE ID=?",
                    str(r.get("ID")));
        }
        return rows.size();
    }

    // ---------------- 内部：单定义推送 ----------------

    private Map<String, Object> pushDef(DailyReportDef def, LocalDate pushDate, boolean markPushDate) {
        List<Map<String, Object>> subs = jdbc.queryForList(
                "SELECT * FROM erp_bi_daily_report_sub WHERE REPORT_ID=? AND STATUS='ACTIVE' AND DEL_FLAG='0'",
                def.getId());
        int retryMax = sysParam.getInt("REPORT_PUSH_RETRY_MAX", 3);
        boolean quality = hasQualityIssue(pushDate);
        int generated = 0;
        int sent = 0;
        int degraded = 0;
        int failed = 0;
        for (Map<String, Object> s : subs) {
            DailyReportSub sub = mapSub(s);
            String receiver = sub.getUserName();
            String logId = uuid();
            // ① 差异化生成（行级过滤 + 水印 PDF + AES 落盘）
            String pdfRef;
            try {
                pdfRef = generatePdf(def, sub, pushDate, logId, quality);
                generated++;
            } catch (Exception e) {
                log.warn("daily report generate failed: sub={}: {}", sub.getId(), e.getMessage());
                // 水印失败/生成失败 → 拒发该份 + 留痕，不影响他人（BR-4.10-37）
                insertLog(logId, sub, def.getCode(), pushDate, receiver, ReportPushLog.CH_NOTICE,
                        ReportPushLog.ST_FAILED, 0, "N", quality, null, "生成失败：" + e.getMessage());
                failed++;
                continue;
            }
            // ② 三通道推送（独立重试互不影响）
            List<String> channels = parseChannels(sub.getChannelsJson());
            boolean anyNonNoticeFail = false;
            boolean noticeSent = false;
            for (String ch : channels) {
                ChannelSender sender = senderOf(ch);
                if (sender == null) {
                    continue;
                }
                boolean ok = false;
                int attempts = 0;
                for (int i = 0; i < retryMax; i++) {
                    attempts++;
                    String bizId = logId + ":" + ch;
                    String title = "经营日报（" + pushDate + "）：" + def.getName();
                    String content = "数据截止 " + pushDate + (quality ? "；⚠️ 数据质量异常" : "")
                            + "；打开日报详情页查看。";
                    if (sender.send(receiver, title, content, bizId)) {
                        ok = true;
                        break;
                    }
                }
                if (ok) {
                    insertLog(uuid(), sub, def.getCode(), pushDate, receiver, ch,
                            ReportPushLog.ST_SENT, attempts, "N", quality, pdfRef, null);
                    sent++;
                    if (ReportPushLog.CH_NOTICE.equals(ch)) {
                        noticeSent = true;
                    }
                } else {
                    insertLog(uuid(), sub, def.getCode(), pushDate, receiver, ch,
                            ReportPushLog.ST_FAILED, attempts, "N", quality, pdfRef, "通道推送失败");
                    failed++;
                    if (!ReportPushLog.CH_NOTICE.equals(ch)) {
                        anyNonNoticeFail = true;
                    }
                }
            }
            // ③ 双通道失败 → 降级站内信 + 运维工单（BR-4.10-38；NOTICE 已在清单且成功则不重复）
            boolean hasNonNotice = channels.stream().anyMatch(c -> !ReportPushLog.CH_NOTICE.equals(c));
            if (anyNonNoticeFail && hasNonNotice && !noticeSent) {
                boolean ok = senderOf(ReportPushLog.CH_NOTICE).send(receiver,
                        "经营日报降级送达（" + pushDate + "）：" + def.getName(),
                        "邮件/企业微信通道推送失败，已降级站内信送达。", logId + ":DEGRADED");
                insertLog(uuid(), sub, def.getCode(), pushDate, receiver, ReportPushLog.CH_NOTICE,
                        ok ? ReportPushLog.ST_DEGRADED : ReportPushLog.ST_FAILED, retryMax, "Y",
                        quality, pdfRef, ok ? null : "降级站内信亦失败");
                if (ok) {
                    degraded++;
                } else {
                    failed++;
                }
                jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                                + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                + "VALUES (?, 'ROLE_ADMIN', 'DailyReportChannelFail', ?, ?, ?, "
                                + "'0', 'system', NOW(), '0', 0)",
                        uuid(), logId, "运维工单：日报双通道推送失败",
                        "接收人 " + receiver + " 的日报（" + def.getCode() + "，" + pushDate
                                + "）邮件/企微均失败，已降级站内信，需检查通道配置。");
            }
            if (markPushDate) {
                sub.setLastPushDate(pushDate);
                subDao.updateById(sub);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", generated);
        out.put("sent", sent);
        out.put("degraded", degraded);
        out.put("failed", failed);
        return out;
    }

    /** 差异化生成：按订阅人行级过滤 KPI 快照 → 水印 PDF → AES 加密落盘 */
    private String generatePdf(DailyReportDef def, DailyReportSub sub, LocalDate pushDate,
                               String serial, boolean quality) throws Exception {
        String receiver = sub.getUserName();
        List<String> allowed = governance.allowedEntities(receiver);
        boolean wildcard = allowed != null && allowed.contains("*");
        List<Map<String, Object>> metrics = parseMetrics(def.getMetricKeysJson());
        List<String> headers = List.of("指标", "维度值", "实际值", "目标值", "达成率", "状态");
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> m : metrics) {
            String kpiKey = str(m.get("kpiKey"));
            String title = str(m.get("title"));
            List<Map<String, Object>> snaps = jdbc.queryForList(
                    "SELECT KPI_KEY AS k, DIM_VALUE AS dim, ACTUAL AS a, TARGET AS t, RATIO AS r, "
                            + "STATUS AS st FROM erp_bi_kpi_snapshot "
                            + "WHERE KPI_KEY=? AND SNAP_DATE=? AND DEL_FLAG='0'", kpiKey, pushDate);
            for (Map<String, Object> sn : snaps) {
                String dim = str(sn.get("dim"));
                // 行级过滤（C-0-07 延伸）：空集 → 空数据版；通配 → 全量；
                // 具体集合 → 全局行（dim 空）保留，维度行按集合过滤
                if (!wildcard) {
                    if (allowed == null || allowed.isEmpty()) {
                        continue;
                    }
                    if (dim != null && !dim.isEmpty() && !allowed.contains(dim)) {
                        continue;
                    }
                }
                List<Object> row = new ArrayList<>();
                row.add(title == null ? kpiKey : title);
                row.add(dim == null || dim.isEmpty() ? "-" : dim);
                row.add(numStr(sn.get("a")));
                row.add(numStr(sn.get("t")));
                row.add(numStr(sn.get("r")));
                row.add(statusText(str(sn.get("st"))));
                rows.add(row);
            }
        }
        // 头部：数据截止时间 + 质量异常标注（BR-4.10-36）
        List<Object> meta = new ArrayList<>();
        meta.add("数据截止：" + pushDate);
        meta.add(quality ? "⚠️ 数据质量异常（部分指标取数失败）" : "数据质量正常");
        meta.add(rows.isEmpty() ? "（您无权限范围内的数据或指标暂无快照）" : "");
        rows.add(0, meta);
        String watermark = receiver + "|" + LocalDateTime.now().format(TS) + "|" + serial;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        pdfWriter.write(headers, rows, watermark, bos);
        Path dir = Path.of(reportDir);
        Files.createDirectories(dir);
        Path cipher = dir.resolve(serial + ".bin");
        AesUtil.encryptToFile(bos.toByteArray(), cipher, secret);
        return cipher.toString();
    }

    private void insertLog(String id, DailyReportSub sub, String code, LocalDate pushDate,
                           String receiver, String channel, String status, int retryCnt,
                           String degraded, boolean quality, String pdfRef, String error) {
        // 幂等 upsert（UNIQUE SUB_ID+PUSH_DATE+CHANNEL）：同日重推更新而非撞唯一键
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_report_push_log WHERE SUB_ID=? AND PUSH_DATE=? AND CHANNEL=?",
                Integer.class, sub.getId(), pushDate, channel);
        if (existing != null && existing > 0) {
            jdbc.update("UPDATE erp_bi_report_push_log SET SEND_STATUS=?, RETRY_CNT=?, DEGRADED_FLAG=?, "
                            + "QUALITY_FLAG=?, PDF_REF=?, ERROR_MSG=?, UPDATE_BY=?, UPDATE_DATE=NOW() "
                            + "WHERE SUB_ID=? AND PUSH_DATE=? AND CHANNEL=?",
                    status, retryCnt, degraded, quality ? "Y" : "N", pdfRef, error,
                    "daily-report", sub.getId(), pushDate, channel);
            return;
        }
        ReportPushLog l = new ReportPushLog();
        l.setId(id);
        l.setSubId(sub.getId());
        l.setReportCode(code);
        l.setPushDate(pushDate);
        l.setReceiver(receiver);
        l.setChannel(channel);
        l.setSendStatus(status);
        l.setRetryCnt(retryCnt);
        l.setDegradedFlag(degraded);
        l.setDataCutoff(pushDate);
        l.setQualityFlag(quality ? "Y" : "N");
        l.setPdfRef(pdfRef);
        l.setErrorMsg(error);
        pushLogDao.insert(l);
    }

    // ---------------- 工具 ----------------

    private long countSnapshot(LocalDate d) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_kpi_snapshot WHERE SNAP_DATE=? AND DEL_FLAG='0'",
                Long.class, d);
        return n == null ? 0 : n;
    }

    private boolean hasQualityIssue(LocalDate d) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_kpi_snapshot WHERE SNAP_DATE=? AND DEL_FLAG='0' "
                        + "AND ERROR_MSG IS NOT NULL AND ERROR_MSG <> ''", Long.class, d);
        return n != null && n > 0;
    }

    private ChannelSender senderOf(String channel) {
        return senders.stream().filter(s -> s.channel().equals(channel)).findFirst().orElse(null);
    }

    private void checkSubOwner(DailyReportSub sub) {
        if (!sub.getUserName().equals(IntfGuard.currentUser()) && !IntfGuard.isAdmin()) {
            throw new ServiceException(403, "仅订阅人本人可修改订阅");
        }
    }

    private List<String> parseChannels(Object json) {
        if (json == null || str(json) == null || str(json).isBlank()) {
            return new ArrayList<>(List.of(ReportPushLog.CH_NOTICE));
        }
        try {
            List<String> raw = JSON.readValue(String.valueOf(json), new TypeReference<List<String>>() {
            });
            List<String> norm = normalizeChannels(raw);
            return norm.isEmpty() ? new ArrayList<>(List.of(ReportPushLog.CH_NOTICE)) : norm;
        } catch (Exception e) {
            return new ArrayList<>(List.of(ReportPushLog.CH_NOTICE));
        }
    }

    private List<String> normalizeChannels(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String c : raw) {
                String u = c == null ? "" : c.trim().toUpperCase();
                if (ReportPushLog.CH_MAIL.equals(u) || ReportPushLog.CH_WECOM.equals(u)
                        || ReportPushLog.CH_NOTICE.equals(u)) {
                    if (!out.contains(u)) {
                        out.add(u);
                    }
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> parseMetrics(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new ServiceException(422, "指标清单 JSON 格式非法");
        }
    }

    private String statusText(String st) {
        if ("ACHIEVED".equals(st)) {
            return "达标";
        }
        if ("WARN".equals(st)) {
            return "预警";
        }
        if ("BELOW".equals(st)) {
            return "未达标";
        }
        return st == null ? "-" : st;
    }

    private String numStr(Object v) {
        return v == null ? "-" : String.valueOf(v);
    }

    private Object BigDecimal2(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private long num(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String toJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(422, "JSON 序列化失败");
        }
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void sleepMinutes(int min) {
        if (min <= 0) {
            return;
        }
        try {
            Thread.sleep(min * 60_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private DailyReportSub mapSub(Map<String, Object> row) {
        DailyReportSub s = new DailyReportSub();
        s.setId(str(row.get("ID")));
        s.setReportId(str(row.get("REPORT_ID")));
        s.setUserName(str(row.get("USER_NAME")));
        s.setChannelsJson(str(row.get("CHANNELS_JSON")));
        s.setStatus(str(row.get("STATUS")));
        return s;
    }
}
