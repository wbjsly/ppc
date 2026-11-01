package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.AesUtil;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiCostSnapshotDao;
import com.erp.dao.bi.BiExportTaskDao;
import com.erp.dao.bi.BiPriceAlertDao;
import com.erp.entity.bi.BiCostSnapshot;
import com.erp.entity.bi.BiExportTask;
import com.erp.entity.bi.BiPriceAlert;
import com.erp.security.IntfGuard;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.bi.CsvFileWriter;
import com.erp.service.bi.ExportFileWriter;
import com.erp.service.bi.ExportService;
import com.erp.service.bi.PdfFileWriter;
import com.erp.service.bi.ReportTemplateService;
import com.erp.service.bi.XlsxFileWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 后台导出实现（add-export-console，spec bi-export-lifecycle /
 * bi-export-overlimit-approval）：
 * 模板/数据集双源 → 三格式水印文件 → AES-256 加密落持久目录；
 * 超量走 ApprovalEngine 分级单签（≤5万经理 / >5万总监）；
 * 优先级队列消费、30min 超时终止、7 天生命周期（删除+24h 提醒）。
 */
@Slf4j
@Service
public class ExportServiceImpl implements ExportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final BiExportTaskDao exportTaskDao;
    private final BiCostSnapshotDao snapshotDao;
    private final BiPriceAlertDao alertDao;
    /** inv-report 数据集（change add-inventory-reports，spec 报表双路径导出） */
    private final com.erp.service.inv.InvReportService reportService;
    /** 模板驱动导出（ObjectProvider 懒取防环：ReportTemplateServiceImpl → QueryGovernance 同族） */
    private final ObjectProvider<ReportTemplateService> templateServiceProvider;
    private final ApprovalEngine approvalEngine;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Value("${app.bi.export-row-limit:10000}")
    private long exportRowLimit;
    @Value("${app.bi.export-parallel-max:3}")
    private int exportParallelMax;
    @Value("${app.bi.export-retain-days:7}")
    private int exportRetainDays;
    @Value("${app.bi.export-dir:./data/export}")
    private String exportDir;
    @Value("${app.bi.conn-secret:erp-export-default-secret}")
    private String secret;

    public ExportServiceImpl(BiExportTaskDao exportTaskDao, BiCostSnapshotDao snapshotDao,
                             BiPriceAlertDao alertDao,
                             com.erp.service.inv.InvReportService reportService,
                             ObjectProvider<ReportTemplateService> templateServiceProvider,
                             ApprovalEngine approvalEngine, JdbcTemplate jdbc) {
        this.exportTaskDao = exportTaskDao;
        this.snapshotDao = snapshotDao;
        this.alertDao = alertDao;
        this.reportService = reportService;
        this.templateServiceProvider = templateServiceProvider;
        this.approvalEngine = approvalEngine;
        this.jdbc = jdbc;
    }

    // ---------- 提交（超量分级审批 + 优先级入队） ----------

    @Override
    @Transactional
    public Map<String, Object> submit(String dataset, Map<String, Object> params, long estRows) {
        String user = IntfGuard.currentUser();
        if (user == null) {
            throw new ServiceException(401, "未登录");
        }
        int active = exportTaskDao.countActiveExports(user);
        if (active >= exportParallelMax) {
            throw new ServiceException(422, "并行后台任务已达上限 " + exportParallelMax
                    + " 个，请等待任一任务完成");
        }
        boolean overLimit = estRows > exportRowLimit;
        Map<String, Object> p = params == null ? Map.of() : params;
        String format = normFormat(p.get("format"));
        String purpose = p.get("purpose") == null ? null : String.valueOf(p.get("purpose"));

        // 超量：用途说明 ≥10 字强制（FR-4.10-4-2）
        if (overLimit && (purpose == null || purpose.trim().length() < 10)) {
            throw new ServiceException(422, "超量导出须填写用途说明（至少 10 字）");
        }

        BiExportTask t = new BiExportTask();
        t.setTaskType(BiExportTask.TYPE_EXPORT);
        t.setTaskNo("EX" + LocalDateTime.now().format(DAY)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
        t.setApiPath("/api/bi/export");
        try {
            Map<String, Object> full = new LinkedHashMap<>(p);
            full.put("dataset", dataset);
            full.put("format", format);
            t.setParamsJson(mapper.writeValueAsString(full));
        } catch (Exception e) {
            t.setParamsJson("{}");
        }
        t.setUserName(user);
        t.setEstRows(estRows);
        t.setStatus(BiExportTask.ST_QUEUED);
        t.setApprovalStatus(overLimit ? "PENDING" : null);
        t.setPurpose(purpose);
        t.setPriority(priorityOf(estRows));   // SMALL/NORMAL/LOW（FR-4.10-4-3）
        t.setReqId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        t.setNotify(false);
        exportTaskDao.insert(t);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("taskNo", t.getTaskNo());
        out.put("status", t.getStatus());
        out.put("approvalStatus", t.getApprovalStatus());
        out.put("priority", t.getPriority());
        out.put("overLimit", overLimit);
        if (overLimit) {
            // 分级单签：≤5万行部门经理 / >5万行部门总监（FR-4.10-4-2，design D3）
            String role = estRows <= 50000 ? "ROLE_DEPT_MANAGER" : "ROLE_DEPT_DIRECTOR";
            String roleName = estRows <= 50000 ? "部门经理" : "部门总监";
            List<List<ApprovalNodeSpec>> chain = List.of(
                    List.of(ApprovalNodeSpec.sign(role, roleName + "-导出超量审批")));
            var inst = approvalEngine.submit("BiExportOverLimit", t.getId(),
                    "超量导出审批：" + t.getTaskNo() + "（预估 " + estRows + " 行）",
                    role, chain);
            out.put("approvalInstanceId", inst.getId());
            out.put("approverRole", role);
            out.put("hint", "超过 " + exportRowLimit + " 行，待 " + roleName + " 审批后执行");
        } else {
            out.put("hint", "已入后台队列");
        }
        return out;
    }

    /**
     * 旧审批入口（design D3：内部改走审批引擎——ADMIN 直批语义改为引擎提交；
     * ok=false 直接驳回语义保留给兼容调用）。
     */
    @Override
    @Transactional
    public Map<String, Object> approve(String id, boolean ok, String reason) {
        BiExportTask t = exportTaskDao.selectById(id);
        if (t == null) {
            throw new ServiceException(404, "任务不存在");
        }
        if (!"PENDING".equals(t.getApprovalStatus())) {
            throw new ServiceException(422, "任务非待审批状态");
        }
        if (ok) {
            // 兼容路径：ADMIN 直批 → 置 APPROVED + 频次告警（与回调同语义）
            t.setApprovalStatus("APPROVED");
            exportTaskDao.updateById(t);
            checkOverlimitFrequency(t.getUserName());
        } else {
            onOverlimitRejectedInternal(t, reason == null ? "审批拒绝" : reason);
        }
        return Map.of("id", t.getId(), "approvalStatus", t.getApprovalStatus(),
                "status", t.getStatus());
    }

    @Override
    public void onOverlimitApproved(String taskId) {
        BiExportTask t = exportTaskDao.selectById(taskId);
        if (t == null) {
            return;
        }
        t.setApprovalStatus("APPROVED");
        exportTaskDao.updateById(t);
        checkOverlimitFrequency(t.getUserName());   // 月度 ≥5 次告警 ROLE_DPO（C-4.10-03）
        log.info("超量导出审批通过 {} by callback", t.getTaskNo());
    }

    @Override
    public void onOverlimitRejected(String taskId) {
        BiExportTask t = exportTaskDao.selectById(taskId);
        if (t == null) {
            return;
        }
        onOverlimitRejectedInternal(t, "审批驳回");
    }

    private void onOverlimitRejectedInternal(BiExportTask t, String reason) {
        t.setApprovalStatus("REJECTED");
        t.setStatus(BiExportTask.ST_FAILED);
        t.setErrorMsg("超量导出审批拒绝：" + reason);
        exportTaskDao.updateById(t);
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'ROLE_BI_DESIGNER', 'BiExportRejected', ?, ?, ?, "
                            + "'0', 'system', NOW(), '0', 0)",
                    uuid(), t.getId(), "超量导出被驳回：" + t.getTaskNo(), reason);
        } catch (Exception e) {
            log.warn("export reject notify failed: {}", e.getMessage());
        }
    }

    /** 月度超量频次监控（FR-4.10-4-2③，C-4.10-03）：当月 APPROVED ≥5 次 → 告警 ROLE_DPO */
    private void checkOverlimitFrequency(String userName) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_bi_export_task WHERE USER_NAME = ? "
                            + "AND TASK_TYPE = 'EXPORT' AND APPROVAL_STATUS = 'APPROVED' "
                            + "AND CREATE_DATE >= DATE_FORMAT(NOW(), '%Y-%m-01')",
                    Integer.class, userName);
            if (n != null && n >= 5) {
                jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                                + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                + "VALUES (?, 'ROLE_DPO', 'BiExportOverlimitFreq', ?, ?, ?, "
                                + "'0', 'system', NOW(), '0', 0)",
                        uuid(), userName, "超量导出频次告警：" + userName,
                        "本月超量导出审批通过 " + n + " 次（≥5 次），请信息安全专员关注");
            }
        } catch (Exception e) {
            log.warn("overlimit frequency check failed: {}", e.getMessage());
        }
    }

    // ---------- 查询 ----------

    @Override
    public List<BiExportTask> myTasks() {
        return exportTaskDao.selectUserExports(IntfGuard.currentUser());
    }

    @Override
    public List<BiExportTask> pendingApproval() {
        return exportTaskDao.selectPendingApproval();
    }

    // ---------- worker（优先级消费 + 模板/数据集双源 + 三格式 + 加密落盘） ----------

    @Override
    public int drainOnce() {
        int n = 0;
        for (BiExportTask t : exportTaskDao.selectDueExports()) {
            t.setStatus(BiExportTask.ST_RUNNING);
            exportTaskDao.updateById(t);
            try {
                long rows = writeFile(t);
                t.setStatus(BiExportTask.ST_DONE);
                t.setRowCount(rows);
                t.setNotify(true);
                t.setExpireAt(LocalDateTime.now().plusDays(exportRetainDays));
                t.setWatermark(t.getUserName() + "|" + LocalDateTime.now().format(TS) + "|"
                        + t.getReqId());
            } catch (Exception e) {
                t.setStatus(BiExportTask.ST_FAILED);
                t.setErrorMsg(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                log.warn("export task {} failed: {}", t.getTaskNo(), e.getMessage());
            }
            exportTaskDao.updateById(t);
            n++;
        }
        return n;
    }

    /** 生成文件：数据收集（模板/数据集）→ 三格式写出 → AES 加密落持久目录 */
    private long writeFile(BiExportTask t) throws Exception {
        Map<String, Object> p = mapper.readValue(
                t.getParamsJson() == null ? "{}" : t.getParamsJson(), Map.class);
        String dataset = String.valueOf(p.getOrDefault("dataset", "cost"));
        String format = normFormat(p.get("format"));
        // 导出前权限再校验：行级空集 → FAILED（FR-4.10-4-1③）
        List<String> allow = allowFrom(p);
        if (allow != null && allow.isEmpty()) {
            throw new ServiceException(403, "行级权限不足：导出前复核未通过，任务终止");
        }

        // 数据收集
        List<String> headers;
        List<List<Object>> rows;
        if ("template".equals(dataset)) {
            String templateId = String.valueOf(p.getOrDefault("templateId", ""));
            int maxRows = (int) Math.min(exportRowLimit + 1, Integer.MAX_VALUE);
            // worker 线程无 SecurityContext——按任务属主做行级校验（FR-4.10-4-1③）
            @SuppressWarnings("unchecked")
            Map<String, Object> data = templateServiceProvider.getObject()
                    .executeGovernedSelect(templateId, maxRows, t.getUserName());
            headers = castList(data.get("headers"));
            rows = castRows(data.get("rows"));
        } else if ("inv-report".equals(dataset)) {
            String rtype = String.valueOf(p.getOrDefault("type", "realtime"));
            Map<String, Object> rp = new LinkedHashMap<>(p);
            rp.put("maxRows", exportRowLimit + 1);
            Map<String, Object> data = reportService.exportRows(rtype, rp);
            headers = castList(data.get("headers"));
            rows = castRows(data.get("rows"));
        } else if ("alerts".equals(dataset)) {
            headers = List.of("月份", "物料", "品类", "环比%", "阈值%", "状态", "处置人");
            rows = new java.util.ArrayList<>();
            for (BiPriceAlert a : alertDao.selectForManage(
                    str(p.get("monthTag")), str(p.get("status")), str(p.get("itemCode")))) {
                rows.add(List.of(a.getMonthTag(), a.getItemCode(), a.getCategoryCode(),
                        a.getPct(), a.getThresholdPct(), a.getStatus(), nvl(a.getHandleBy())));
            }
        } else {
            // cost：成本快照
            headers = List.of("月份", "法人", "品类", "物料", "供应商", "采购员",
                    "PO数量", "PO承诺", "发票实付", "差异", "暂估");
            rows = new java.util.ArrayList<>();
            LambdaQueryWrapper<BiCostSnapshot> qw = new LambdaQueryWrapper<>();
            qw.eq(p.get("monthTag") != null, BiCostSnapshot::getMonthTag, str(p.get("monthTag")));
            qw.eq(p.get("itemCode") != null, BiCostSnapshot::getItemCode, str(p.get("itemCode")));
            qw.eq(p.get("supplierId") != null, BiCostSnapshot::getSupplierId, str(p.get("supplierId")));
            if (allow != null && !allow.isEmpty()) {
                qw.in(BiCostSnapshot::getLegalEntityId, allow);
            }
            qw.last("LIMIT " + (exportRowLimit + 1));
            for (BiCostSnapshot s : snapshotDao.selectList(qw)) {
                rows.add(List.of(s.getMonthTag(), s.getLegalEntityId(), s.getCategoryCode(),
                        s.getItemCode(), s.getSupplierId(), nvl(s.getBuyer()), s.getPoQty(),
                        s.getPoAmt(), nvl(s.getInvAmt()), nvl(s.getDiffAmt()),
                        Boolean.TRUE.equals(s.getEstimating()) ? "Y" : "N"));
            }
        }

        // 防绕过：实际行数超预估 2 倍 → 截断并提示重新申请（design 风险项）
        if (t.getEstRows() != null && rows.size() > t.getEstRows() * 2 + 10) {
            rows = rows.subList(0, (int) Math.min(t.getEstRows() * 2 + 10, rows.size()));
            throw new ServiceException(422,
                    "实际数据量远超预估（" + rows.size() + " 行），已截断，请重新评估行数后申请");
        }

        // 三格式写出（水印失败 → 异常 → FAILED，FR-4.10-4-4①）
        String watermark = t.getUserName() + "|" + LocalDateTime.now().format(TS) + "|"
                + t.getReqId();
        ExportFileWriter writer = writerOf(format);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        writer.write(headers, rows, watermark, bos);

        // AES-256 加密落持久目录（FR-4.10-4-5，明文不落盘）
        Path dir = Path.of(exportDir);
        Files.createDirectories(dir);
        Path cipher = dir.resolve(t.getTaskNo() + ".bin");
        AesUtil.encryptToFile(bos.toByteArray(), cipher, secret);
        t.setFilePath(cipher.toString());

        // 文件命名（FR-4.10-4-5②）：{报表名}_{日期}_{流水号}.{ext}——存 fileName 便于下载
        String reportName = reportNameOf(p, dataset);
        String fileName = reportName + "_" + LocalDateTime.now().format(DAY) + "_"
                + t.getReqId() + "." + writer.extension();
        try {
            Map<String, Object> full = mapper.readValue(t.getParamsJson(), Map.class);
            full.put("_fileName", fileName);
            full.put("_contentType", contentTypeOf(writer.extension()));
            t.setParamsJson(mapper.writeValueAsString(full));
        } catch (Exception ignored) {
            // 命名元数据失败不阻断主流程
        }
        return rows.size();
    }

    // ---------- 下载（FR-4.10-4-5：属主/ADMIN、7 天有效、解密流出） ----------

    @Override
    public Map<String, Object> download(String taskId) {
        BiExportTask t = exportTaskDao.selectById(taskId);
        if (t == null) {
            throw new ServiceException(404, "任务不存在");
        }
        String me = IntfGuard.currentUser();
        boolean isAdmin = me != null && isAdmin();
        if (me == null || (!me.equals(t.getUserName()) && !isAdmin)) {
            throw new ServiceException(403, "仅任务属主或管理员可下载");
        }
        if (!BiExportTask.ST_DONE.equals(t.getStatus())) {
            throw new ServiceException(422, "任务未完成（当前状态：" + t.getStatus() + "）");
        }
        if (t.getExpireAt() != null && t.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new ServiceException(422, "下载链接已过期（7 天有效期），请重新导出");
        }
        if (t.getFilePath() == null || !Files.exists(Path.of(t.getFilePath()))) {
            throw new ServiceException(422, "导出文件不存在或已被清理，请重新导出");
        }
        byte[] plain = AesUtil.decryptFile(Path.of(t.getFilePath()), secret);
        Map<String, Object> p = parseParams(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileName", str(p.getOrDefault("_fileName", t.getTaskNo() + ".bin")));
        out.put("contentType", str(p.getOrDefault("_contentType", "application/octet-stream")));
        out.put("bytes", plain);
        return out;
    }

    // ---------- 超时终止与生命周期（FR-4.10-4-3/6） ----------

    @Override
    public int sweepTimeouts() {
        List<BiExportTask> stuck = exportTaskDao.selectList(
                new LambdaQueryWrapper<BiExportTask>()
                        .eq(BiExportTask::getTaskType, BiExportTask.TYPE_EXPORT)
                        .eq(BiExportTask::getStatus, BiExportTask.ST_RUNNING)
                        .lt(BiExportTask::getUpdateDate, LocalDateTime.now().minusMinutes(30)));
        for (BiExportTask t : stuck) {
            t.setStatus(BiExportTask.ST_FAILED);
            t.setErrorMsg("执行超时自动终止（>30 分钟）");
            exportTaskDao.updateById(t);
            log.warn("export task {} timeout terminated", t.getTaskNo());
        }
        return stuck.size();
    }

    @Override
    @Transactional
    public int[] sweepLifecycle() {
        int cleaned = 0;
        int reminded = 0;
        // 到期删除（FR-4.10-4-6①）
        List<BiExportTask> expired = exportTaskDao.selectList(
                new LambdaQueryWrapper<BiExportTask>()
                        .eq(BiExportTask::getTaskType, BiExportTask.TYPE_EXPORT)
                        .eq(BiExportTask::getStatus, BiExportTask.ST_DONE)
                        .isNotNull(BiExportTask::getExpireAt)
                        .lt(BiExportTask::getExpireAt, LocalDateTime.now())
                        .isNull(BiExportTask::getCleanAt));
        for (BiExportTask t : expired) {
            try {
                if (t.getFilePath() != null) {
                    Files.deleteIfExists(Path.of(t.getFilePath()));
                }
                t.setCleanAt(LocalDateTime.now());   // 清理日志语义（≥180 天可查）
                t.setUpdateBy("system");
                exportTaskDao.updateById(t);
                cleaned++;
            } catch (Exception e) {
                log.warn("export file clean failed {}: {}", t.getTaskNo(), e.getMessage());
            }
        }
        // 删除前 24h 提醒（FR-4.10-4-6①）
        List<BiExportTask> expiring = exportTaskDao.selectList(
                new LambdaQueryWrapper<BiExportTask>()
                        .eq(BiExportTask::getTaskType, BiExportTask.TYPE_EXPORT)
                        .eq(BiExportTask::getStatus, BiExportTask.ST_DONE)
                        .isNotNull(BiExportTask::getExpireAt)
                        .gt(BiExportTask::getExpireAt, LocalDateTime.now())
                        .lt(BiExportTask::getExpireAt, LocalDateTime.now().plusHours(24))
                        .isNull(BiExportTask::getRemindAt));
        for (BiExportTask t : expiring) {
            try {
                jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                                + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                + "VALUES (?, 'ROLE_BI_DESIGNER', 'BiExportExpiring', ?, ?, ?, "
                                + "'0', 'system', NOW(), '0', 0)",
                        uuid(), t.getId(), "导出文件将到期：" + t.getTaskNo(),
                        "文件将在 24 小时后自动删除，如需保留请尽快下载");
                t.setRemindAt(LocalDateTime.now());
                t.setUpdateBy("system");
                exportTaskDao.updateById(t);
                reminded++;
            } catch (Exception e) {
                log.warn("export expire remind failed {}: {}", t.getTaskNo(), e.getMessage());
            }
        }
        return new int[]{cleaned, reminded};
    }

    // ---------- helpers ----------

    private String priorityOf(long estRows) {
        if (estRows <= 1000) {
            return "SMALL";
        }
        return estRows <= exportRowLimit ? "NORMAL" : "LOW";
    }

    private ExportFileWriter writerOf(String format) {
        switch (format) {
            case "xlsx":
                return new XlsxFileWriter();
            case "pdf":
                return new PdfFileWriter();
            default:
                return new CsvFileWriter();
        }
    }

    private String normFormat(Object f) {
        String v = f == null ? "csv" : String.valueOf(f).toLowerCase();
        return v.equals("xlsx") || v.equals("pdf") ? v : "csv";
    }

    private String contentTypeOf(String ext) {
        switch (ext) {
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pdf":
                return "application/pdf";
            default:
                return "text/csv";
        }
    }

    private String reportNameOf(Map<String, Object> p, String dataset) {
        Object n = p.get("reportName");
        if (n != null && !String.valueOf(n).isBlank()) {
            return String.valueOf(n).replaceAll("[\\\\/:*?\"<>|]", "_");
        }
        return "报表导出-" + dataset;
    }

    private boolean isAdmin() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private Map<String, Object> parseParams(BiExportTask t) {
        try {
            return mapper.readValue(t.getParamsJson() == null ? "{}" : t.getParamsJson(), Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 行级条件：参数 _allow（null=通配全量；[]=拒绝） */
    @SuppressWarnings("unchecked")
    private List<String> allowFrom(Map<String, Object> p) {
        Object a = p.get("_allow");
        if (a == null) {
            return null;
        }
        return (List<String>) a;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return o == null ? List.of() : (List<String>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> castRows(Object o) {
        return o == null ? List.of() : (List<List<Object>>) o;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nvl(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
