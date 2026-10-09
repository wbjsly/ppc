package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvCountLineDao;
import com.erp.dao.inv.InvCountTaskDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.entity.inv.InvCountLine;
import com.erp.entity.inv.InvCountTask;
import com.erp.entity.inv.InvStock;
import com.erp.service.inv.CountTaskService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 盘点任务实现（4.11，spec count-management 任务状态机；design D3 头行两表）。
 * 生成即 COUNTING：物化行 BOOK_QTY（库存行按 仓位×物料×批次 聚合，定格快照）——
 * 引擎锁仓按 COUNTING 任务的行 BIN_CODE 集合消费（'' 未分配位不入范围不锁）。
 */
@Slf4j
@Service
public class CountTaskServiceImpl implements CountTaskService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final InvCountTaskDao taskDao;
    private final InvCountLineDao lineDao;
    private final InvStockDao stockDao;

    public CountTaskServiceImpl(InvCountTaskDao taskDao, InvCountLineDao lineDao,
                                InvStockDao stockDao) {
        this.taskDao = taskDao;
        this.lineDao = lineDao;
        this.stockDao = stockDao;
    }

    @Override
    @Transactional
    public Map<String, Object> createCycle(String warehouseCode, List<String> binCodes,
                                           String itemCode, String remark) {
        requireWarehouse("创建周期盘点任务");
        if (isBlank(warehouseCode)) {
            throw new ServiceException(422, "盘点仓库必填");
        }
        return createTask(InvCountTask.T_CYCLE, warehouseCode.trim(),
                binCodes, itemCode, remark);
    }

    @Override
    @Transactional
    public Map<String, Object> createFull(String warehouseCode, String remark) {
        requireWarehouse("创建全面盘点任务");
        if (isBlank(warehouseCode)) {
            throw new ServiceException(422, "盘点仓库必填");
        }
        return createTask(InvCountTask.T_FULL, warehouseCode.trim(), null, null, remark);
    }

    /** 生成任务头 + 物化行（BOOK_QTY 定格，'' 未分配位不入范围） */
    private Map<String, Object> createTask(String type, String warehouseCode,
                                           List<String> binCodes, String itemCode,
                                           String remark) {
        // 拉取该仓有货库存行（按 仓位×物料×批次 聚合 BOOK_QTY）
        LambdaQueryWrapper<InvStock> qw = new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, warehouseCode)
                .ne(InvStock::getBinCode, "");
        if (!isBlank(itemCode)) {
            qw.eq(InvStock::getItemCode, itemCode.trim());
        }
        Set<String> binFilter = null;
        if (binCodes != null && !binCodes.isEmpty()) {
            binFilter = new LinkedHashSet<>(binCodes);
            qw.in(InvStock::getBinCode, binFilter);
        }
        // 未分配库存行（''）不入盘点范围（锁不锁未分配位，spec 仓位盘点锁）
        Map<String, InvStock> agg = new LinkedHashMap<>();
        for (InvStock s : stockDao.selectList(qw)) {
            if (isBlank(s.getBinCode())) {
                continue;
            }
            if (binFilter != null && !binFilter.contains(s.getBinCode())) {
                continue;
            }
            String key = s.getBinCode() + "|" + s.getItemCode() + "|" + str(s.getBatchNo());
            InvStock a = agg.get(key);
            if (a == null) {
                InvStock copy = new InvStock();
                copy.setBinCode(s.getBinCode());
                copy.setItemCode(s.getItemCode());
                copy.setItemName(s.getItemName());
                copy.setBatchNo(str(s.getBatchNo()));
                copy.setQty(BigDecimal.ZERO);
                agg.put(key, copy);
                a = copy;
            }
            a.setQty(a.getQty().add(nvl(s.getQty())));
        }
        if (agg.isEmpty()) {
            throw new ServiceException(422, "盘点范围内无有货库存维度"
                    + (binFilter != null ? "（所选仓位）" : ""));
        }

        InvCountTask task = new InvCountTask();
        task.setTaskNo(nextTaskNo());
        task.setTaskType(type);
        task.setWarehouseCode(warehouseCode);
        task.setStatus(InvCountTask.ST_COUNTING);
        task.setTotalLines(agg.size());
        task.setCountedLines(0);
        task.setAdjustedLines(0);
        task.setRemark(remark);
        task.setCreateBy(SecurityUtils.getCurrentUserId());
        taskDao.insert(task);

        List<InvCountLine> lines = new ArrayList<>(agg.size());
        for (InvStock a : agg.values()) {
            InvCountLine l = new InvCountLine();
            l.setTaskId(task.getId());
            l.setBinCode(a.getBinCode());
            l.setItemCode(a.getItemCode());
            l.setItemName(a.getItemName());
            l.setBatchNo(str(a.getBatchNo()));
            l.setBookQty(nvl(a.getQty()));
            l.setCountStatus(InvCountLine.ST_PENDING);
            l.setAutoAdjust("0");
            l.setCreateBy(SecurityUtils.getCurrentUserId());
            lines.add(l);
        }
        for (InvCountLine l : lines) {
            lineDao.insert(l);
        }
        log.info("count task {} created ({}): type={} wh={} lines={}",
                task.getTaskNo(), task.getId(), type, warehouseCode, lines.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", task.getId());
        out.put("taskNo", task.getTaskNo());
        out.put("taskType", task.getTaskType());
        out.put("status", task.getStatus());
        out.put("totalLines", task.getTotalLines());
        return out;
    }

    @Override
    public Map<String, Object> page(String status, String taskType, String warehouseCode,
                                    long current, long size) {
        Page<InvCountTask> p = taskDao.selectPage(new Page<>(Math.max(current, 1),
                Math.max(size, 1)), new LambdaQueryWrapper<InvCountTask>()
                .eq(!isBlank(status), InvCountTask::getStatus, trimOrNull(status))
                .eq(!isBlank(taskType), InvCountTask::getTaskType, trimOrNull(taskType))
                .eq(!isBlank(warehouseCode), InvCountTask::getWarehouseCode, trimOrNull(warehouseCode))
                .orderByDesc(InvCountTask::getCreateDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (InvCountTask t : p.getRecords()) {
            out.add(taskMap(t));
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", out);
        page.put("total", p.getTotal());
        return page;
    }

    @Override
    public List<Map<String, Object>> lines(String taskId, String countStatus, String keyword) {
        if (isBlank(taskId)) {
            throw new ServiceException(422, "任务必填");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (InvCountLine l : lineDao.selectList(new LambdaQueryWrapper<InvCountLine>()
                .eq(InvCountLine::getTaskId, taskId.trim())
                .eq(!isBlank(countStatus), InvCountLine::getCountStatus, trimOrNull(countStatus))
                .and(!isBlank(keyword), w -> w.like(InvCountLine::getItemCode, keyword)
                        .or().like(InvCountLine::getBatchNo, keyword)
                        .or().like(InvCountLine::getBinCode, keyword))
                .orderByAsc(InvCountLine::getBinCode)
                .orderByAsc(InvCountLine::getItemCode))) {
            out.add(lineMap(l));
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> cancel(String taskId, String reason) {
        requireWarehouse("取消盘点任务");
        if (isBlank(reason)) {
            throw new ServiceException(422, "取消原因必填");
        }
        InvCountTask t = requireTask(taskId);
        if (!InvCountTask.ST_COUNTING.equals(t.getStatus())) {
            throw new ServiceException(422, "仅盘点中任务可取消（当前 " + t.getStatus() + "）");
        }
        Long entered = lineDao.selectCount(new LambdaQueryWrapper<InvCountLine>()
                .eq(InvCountLine::getTaskId, t.getId())
                .isNotNull(InvCountLine::getActualQty));
        if (entered != null && entered > 0) {
            throw new ServiceException(422, "已录入 " + entered + " 行实盘，不可取消（可关闭任务或继续盘点）");
        }
        // 删除行（零录入无留痕价值）→ 任务置 DONE（解锁由状态非 COUNTING 天然生效）
        lineDao.delete(new LambdaQueryWrapper<InvCountLine>()
                .eq(InvCountLine::getTaskId, t.getId()));
        t.setStatus(InvCountTask.ST_DONE);
        t.setRemark(concatRemark(t.getRemark(), "取消：" + reason));
        if (taskDao.updateById(t) == 0) {
            throw new ServiceException(409, "任务状态冲突，请刷新重试");
        }
        log.info("count task {} cancelled: {}", t.getTaskNo(), reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("status", t.getStatus());
        return out;
    }

    @Override
    public Map<String, Object> detail(String taskId) {
        InvCountTask t = requireTask(taskId);
        Map<String, Object> out = taskMap(t);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (InvCountLine l : lineDao.selectList(new LambdaQueryWrapper<InvCountLine>()
                .eq(InvCountLine::getTaskId, t.getId()))) {
            byStatus.merge(str(l.getCountStatus()), 1L, Long::sum);
        }
        out.put("linesByStatus", byStatus);
        out.put("reportJson", t.getReportJson());   // 7.1 报告查询（DONE 后非空）
        return out;
    }

    @Override
    @Transactional
    public void markAdjusting(String taskId) {
        InvCountTask t = requireTask(taskId);
        if (InvCountTask.ST_COUNTING.equals(t.getStatus())) {
            t.setStatus(InvCountTask.ST_ADJUSTING);
            taskDao.updateById(t);
        }
    }

    @Override
    @Transactional
    public boolean tryComplete(String taskId) {
        InvCountTask t = requireTask(taskId);
        if (InvCountTask.ST_DONE.equals(t.getStatus())) {
            return false;   // 幂等
        }
        Long pending = lineDao.selectCount(new LambdaQueryWrapper<InvCountLine>()
                .eq(InvCountLine::getTaskId, t.getId())
                .in(InvCountLine::getCountStatus,
                        InvCountLine.ST_PENDING, InvCountLine.ST_RECOUNT));
        if (pending != null && pending > 0) {
            return false;   // 还有未盘/待复盘行
        }
        Long unadjusted = lineDao.selectCount(new LambdaQueryWrapper<InvCountLine>()
                .eq(InvCountLine::getTaskId, t.getId())
                .ne(InvCountLine::getCountStatus, InvCountLine.ST_ADJUSTED));
        if (unadjusted != null && unadjusted > 0) {
            return false;   // 有已录但未调整行（超容差差异单审批中）
        }
        // 物化报告（design D8）+ 统计回填
        List<InvCountLine> all = lineDao.selectList(new LambdaQueryWrapper<InvCountLine>()
                .eq(InvCountLine::getTaskId, t.getId()));
        t.setStatus(InvCountTask.ST_DONE);
        t.setCountedLines(all.size());
        t.setAdjustedLines(all.size());
        t.setReportJson(buildReport(t, all));
        if (taskDao.updateById(t) == 0) {
            throw new ServiceException(409, "任务状态冲突，请刷新重试");
        }
        log.info("count task {} DONE: {} lines, report materialized", t.getTaskNo(), all.size());
        return true;
    }

    /** 盘点报告物化（spec 盘点报告：范围/差异汇总/调整笔数/监控仓位清单） */
    private String buildReport(InvCountTask t, List<InvCountLine> all) {
        int diffLines = 0;
        int autoCnt = 0;
        int apprCnt = 0;
        java.math.BigDecimal totalDiffAmount = java.math.BigDecimal.ZERO;
        Set<String> bins = new LinkedHashSet<>();
        Set<String> items = new LinkedHashSet<>();
        // 监控仓位（BR-4.4-41 首期：本次超容差仓位即入清单，频次自动上调待 ABC 落地）
        Set<String> watchBins = new LinkedHashSet<>();
        for (InvCountLine l : all) {
            bins.add(l.getBinCode());
            items.add(l.getItemCode());
            if (l.getDiffQty() != null && l.getDiffQty().signum() != 0) {
                diffLines++;
                if (l.getDiffAmount() != null) {
                    totalDiffAmount = totalDiffAmount.add(l.getDiffAmount());
                }
                if ("1".equals(l.getAutoAdjust())) {
                    autoCnt++;
                } else if (l.getDiffId() != null) {
                    apprCnt++;
                }
                if (l.getDiffRate() != null
                        && l.getDiffRate().compareTo(new java.math.BigDecimal("0.005")) > 0) {
                    watchBins.add(l.getBinCode());
                }
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("taskType", t.getTaskType());
        range.put("warehouseCode", t.getWarehouseCode());
        range.put("binCount", bins.size());
        range.put("itemCount", items.size());
        range.put("lineCount", all.size());
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("diffLines", diffLines);
        diff.put("totalDiffAmount", totalDiffAmount);
        diff.put("autoAdjusted", autoCnt);
        diff.put("apprAdjusted", apprCnt);
        r.put("range", range);
        r.put("diff", diff);
        r.put("watchBins", new ArrayList<>(watchBins));
        r.put("generatedAt", java.time.LocalDateTime.now().toString());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(r);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(422, "报告序列化失败：" + e.getMessage());
        }
    }

    // ---------- helpers ----------

    private InvCountTask requireTask(String id) {
        InvCountTask t = isBlank(id) ? null : taskDao.selectById(id);
        if (t == null) {
            throw new ServiceException(422, "盘点任务不存在");
        }
        return t;
    }

    private Map<String, Object> taskMap(InvCountTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("taskNo", t.getTaskNo());
        m.put("taskType", t.getTaskType());
        m.put("warehouseCode", t.getWarehouseCode());
        m.put("status", t.getStatus());
        m.put("totalLines", t.getTotalLines());
        m.put("countedLines", t.getCountedLines());
        m.put("adjustedLines", t.getAdjustedLines());
        m.put("remark", t.getRemark());
        m.put("createBy", t.getCreateBy());
        m.put("createDate", t.getCreateDate());
        return m;
    }

    /** 行映射（**不携带 BOOK_QTY/差异列**——录入遮蔽在契约层，spec 录入遮蔽；回显走 CountInputService） */
    private Map<String, Object> lineMap(InvCountLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("binCode", l.getBinCode());
        m.put("itemCode", l.getItemCode());
        m.put("itemName", l.getItemName());
        m.put("batchNo", l.getBatchNo());
        m.put("countStatus", l.getCountStatus());
        m.put("abnormalFlag", l.getAbnormalFlag());
        m.put("countedAt", l.getCountedAt());
        // 未录入行（PENDING/RECOUNT 待复盘）遮蔽账面与差异（spec 录入遮蔽）；已录行可回显
        if (InvCountLine.ST_PENDING.equals(l.getCountStatus())
                || InvCountLine.ST_RECOUNT.equals(l.getCountStatus())) {
            return m;
        }
        m.put("bookQty", l.getBookQty());
        m.put("actualQty", l.getActualQty());
        m.put("diffQty", l.getDiffQty());
        m.put("diffRate", l.getDiffRate());
        m.put("diffAmount", l.getDiffAmount());
        m.put("autoAdjust", l.getAutoAdjust());
        return m;
    }

    /** COUNT+yyMMdd+4位日流水（唯一键冲突重试） */
    private String nextTaskNo() {
        String prefix = "COUNT" + LocalDate.now().format(NO_DAY) + "-";
        for (int attempt = 0; attempt < 5; attempt++) {
            Long cnt = taskDao.selectCount(new LambdaQueryWrapper<InvCountTask>()
                    .likeRight(InvCountTask::getTaskNo, prefix));
            String no = prefix + String.format("%04d", (cnt == null ? 0 : cnt) + 1 + attempt);
            Long dup = taskDao.selectCount(new LambdaQueryWrapper<InvCountTask>()
                    .eq(InvCountTask::getTaskNo, no));
            if (dup == null || dup == 0) {
                return no;
            }
        }
        return prefix + System.nanoTime() % 100000;
    }

    private void requireWarehouse(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r)
                || "ROLE_WAREHOUSE".equalsIgnoreCase(r))) {
            return;
        }
        throw new ServiceException(403, action + "：需要仓库主管或管理员角色");
    }

    private static String concatRemark(String old, String add) {
        if (old == null || old.isEmpty()) {
            return add;
        }
        return old + "；" + add;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String trimOrNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
