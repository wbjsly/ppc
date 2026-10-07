package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiCostSnapshotDao;
import com.erp.dao.bi.BiExportTaskDao;
import com.erp.entity.bi.BiCostSnapshot;
import com.erp.entity.bi.BiExportTask;
import com.erp.entity.bi.BiPriceAlert;
import com.erp.dao.bi.BiPriceAlertDao;
import com.erp.security.IntfGuard;
import com.erp.service.bi.ExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 后台导出实现（spec bi-query-governance C-4.10-05，design D9 worker）。
 * 数据集：cost（成本快照）/ alerts（价格异动）；CSV 文件落本地目录，文件头写水印；
 * 超量任务 APPROVAL_STATUS=PENDING 由 worker 跳过直至审批通过。
 */
@Slf4j
@Service
public class ExportServiceImpl implements ExportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final BiExportTaskDao exportTaskDao;
    private final BiCostSnapshotDao snapshotDao;
    private final BiPriceAlertDao alertDao;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Value("${app.bi.export-row-limit:10000}")
    private long exportRowLimit;
    @Value("${app.bi.export-parallel-max:3}")
    private int exportParallelMax;
    @Value("${app.bi.export-retain-days:7}")
    private int exportRetainDays;
    @Value("${app.bi.query-timeout-seconds:30}")
    private int queryTimeoutSeconds;

    public ExportServiceImpl(BiExportTaskDao exportTaskDao, BiCostSnapshotDao snapshotDao,
                             BiPriceAlertDao alertDao) {
        this.exportTaskDao = exportTaskDao;
        this.snapshotDao = snapshotDao;
        this.alertDao = alertDao;
    }

    @Override
    public Map<String, Object> submit(String dataset, Map<String, Object> params, long estRows) {
        String user = IntfGuard.currentUser();
        if (user == null) {
            throw new ServiceException(401, "未登录");
        }
        int active = exportTaskDao.countActiveExports(user);
        if (active >= exportParallelMax) {
            throw new ServiceException(422, "并行后台任务已达上限 " + exportParallelMax + " 个，请等待任一任务完成");
        }
        boolean overLimit = estRows > exportRowLimit;

        BiExportTask t = new BiExportTask();
        t.setTaskType(BiExportTask.TYPE_EXPORT);
        t.setTaskNo("EX" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
        t.setApiPath("/api/bi/export");
        try {
            Map<String, Object> p = new LinkedHashMap<>(params == null ? Map.of() : params);
            p.put("dataset", dataset);
            t.setParamsJson(mapper.writeValueAsString(p));
        } catch (Exception e) {
            t.setParamsJson("{}");
        }
        t.setUserName(user);
        t.setEstRows(estRows);
        t.setStatus(BiExportTask.ST_QUEUED);
        t.setApprovalStatus(overLimit ? "PENDING" : null);   // >1 万行超量须审批（C-4.10-05）
        t.setReqId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        t.setNotify(false);
        exportTaskDao.insert(t);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("taskNo", t.getTaskNo());
        out.put("status", t.getStatus());
        out.put("approvalStatus", t.getApprovalStatus());
        out.put("overLimit", overLimit);
        out.put("hint", overLimit ? "超过 " + exportRowLimit + " 行，待审批后执行" : "已入后台队列");
        return out;
    }

    @Override
    public Map<String, Object> approve(String id, boolean ok, String reason) {
        BiExportTask t = exportTaskDao.selectById(id);
        if (t == null) {
            throw new ServiceException(404, "任务不存在");
        }
        if (!"PENDING".equals(t.getApprovalStatus())) {
            throw new ServiceException(422, "任务非待审批状态");
        }
        t.setApprovalStatus(ok ? "APPROVED" : "REJECTED");
        if (!ok) {
            t.setStatus(BiExportTask.ST_FAILED);
            t.setErrorMsg("超量导出审批拒绝：" + (reason == null ? "" : reason));
        }
        exportTaskDao.updateById(t);
        return Map.of("id", t.getId(), "approvalStatus", t.getApprovalStatus(), "status", t.getStatus());
    }

    @Override
    public List<BiExportTask> myTasks() {
        return exportTaskDao.selectUserExports(IntfGuard.currentUser());
    }

    @Override
    public List<BiExportTask> pendingApproval() {
        return exportTaskDao.selectPendingApproval();
    }

    /** worker：执行队列（每 10 秒由调度器调用） */
    @Override
    public int drainOnce() {
        int n = 0;
        for (BiExportTask t : exportTaskDao.selectDueExports()) {
            t.setStatus(BiExportTask.ST_RUNNING);
            exportTaskDao.updateById(t);
            try {
                long rows = writeCsv(t);
                t.setStatus(BiExportTask.ST_DONE);
                t.setRowCount(rows);
                t.setNotify(true);   // 站内通知：任务完成标志（前端任务列表即通知面）
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

    /** 按数据集生成 CSV（文件头写水印；行数为真实返回行） */
    private long writeCsv(BiExportTask t) throws Exception {
        Map<String, Object> p = mapper.readValue(t.getParamsJson() == null ? "{}" : t.getParamsJson(),
                Map.class);
        String dataset = String.valueOf(p.getOrDefault("dataset", "cost"));
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "erp-export");
        Files.createDirectories(dir);
        Path file = dir.resolve(t.getTaskNo() + ".csv");
        String watermark = "user=" + t.getUserName() + ",time=" + LocalDateTime.now().format(TS)
                + ",seq=" + t.getReqId();

        long rows = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("# watermark: " + watermark);   // 水印行（C-4.10-05）
            if ("alerts".equals(dataset)) {
                w.println("月份,物料,品类,环比%,阈值%,状态,处置人");
                for (BiPriceAlert a : alertDao.selectForManage(
                        p.get("monthTag") == null ? null : String.valueOf(p.get("monthTag")),
                        p.get("status") == null ? null : String.valueOf(p.get("status")),
                        p.get("itemCode") == null ? null : String.valueOf(p.get("itemCode")))) {
                    w.println(a.getMonthTag() + "," + a.getItemCode() + "," + a.getCategoryCode()
                            + "," + a.getPct() + "," + a.getThresholdPct() + "," + a.getStatus()
                            + "," + nvl(a.getHandleBy()));
                    rows++;
                }
            } else {
                // cost：成本快照（行级法人条件在查询服务侧治理，导出走同一 allow 集合参数）
                List<String> allow = allowFrom(p);
                LambdaQueryWrapper<BiCostSnapshot> qw = new LambdaQueryWrapper<>();
                qw.eq(p.get("monthTag") != null, BiCostSnapshot::getMonthTag,
                        String.valueOf(p.get("monthTag")));
                qw.eq(p.get("itemCode") != null, BiCostSnapshot::getItemCode,
                        String.valueOf(p.get("itemCode")));
                qw.eq(p.get("supplierId") != null, BiCostSnapshot::getSupplierId,
                        String.valueOf(p.get("supplierId")));
                if (allow != null && !allow.isEmpty()) {
                    qw.in(BiCostSnapshot::getLegalEntityId, allow);
                } else if (allow != null) {
                    // null = 全量（通配绑定）
                } else {
                    w.println("无行级权限（未绑定法人主体），0 行");
                    return 0;
                }
                qw.last("LIMIT " + (exportRowLimit + 1));
                w.println("月份,法人,品类,物料,供应商,采购员,PO数量,PO承诺,发票实付,差异,暂估");
                for (BiCostSnapshot s : snapshotDao.selectList(qw)) {
                    w.println(s.getMonthTag() + "," + s.getLegalEntityId() + ","
                            + s.getCategoryCode() + "," + s.getItemCode() + ","
                            + s.getSupplierId() + "," + nvl(s.getBuyer()) + ","
                            + s.getPoQty() + "," + s.getPoAmt() + ","
                            + nvl(s.getInvAmt()) + "," + nvl(s.getDiffAmt()) + ","
                            + (Boolean.TRUE.equals(s.getEstimating()) ? "Y" : "N"));
                    rows++;
                }
            }
        }
        t.setFilePath(file.toString());
        return rows;
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

    private static String nvl(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
