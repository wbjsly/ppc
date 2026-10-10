package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mrp.MrpRunDao;
import com.erp.dao.mrp.MrpSuggestionDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpRun;
import com.erp.entity.mrp.MrpSuggestion;
import com.erp.service.SysParamService;
import com.erp.service.mrp.MrpDataSource;
import com.erp.service.mrp.MrpPlanService;
import com.erp.service.proc.ProcMrpService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 需求计划实现（change add-mrp-demand-planning，spec mrp-demand-planning / 00-erp-spec 4.5-2）。
 *
 * 引擎（design D2 七步管线，单期快照）：
 * 1) 范围物料集（FULL/CATEGORY/GROUP，proposal D5）；
 * 2) 结构发现：递归收集 MAKE 件已发布 BOM（含启用替代序列）形成闭环（子件可超出范围并入）；
 * 3) 批量取数（MrpDataSource：SO 确认量 / Σ可用库存 / APPROVED PO 在途；在制恒 0 桩 D4）；
 *    ROP 补货：初始可用 < ROP_QTY 时以目标水位 ROP 计入需求（结算后净 = ROP − 可用 = 缺口）；
 * 4) 拓扑序求值（父先于子）：净 = 总需求 − 可用（不饱和，负值 → EXCESS）→ 建议/EXCESS →
 *    爆炸（用量×(1+损耗)，深度 ≤ bom-max-nesting-depth）；
 * 5) 替代分配：候选 [主料, sub1...] 按剩余池封顶分配，缺量给首个启用替代（比例换算，
 *    spec FR-4.5-2-4 唯一具体场景口径）+ 溯源备注；同物料多父件毛需求聚合一次结算；
 * 6) 逐层倒排：子件需求日 = 父件开工日（需求日 − 路线 ΣLEAD_TIME，缺路线兜底 leadTimeDays+行内提示）；
 *    采购件下单日 = 需求日 − leadTimeDays；下单日 < 今日 → OVERDUE；
 * 7) 建议量规则（FR-4.5-2-6）：采购净需求 &lt; minOrderQty 上调并备注；可用+建议量 &gt; MAX_STOCK 行内提示。
 *
 * 落库：RUNNING 行 autocommit 先落（互斥位）→ 纯读计算 → TransactionTemplate 事务内
 * 取代旧活跃建议 + 批量插入 + Run 置 DONE；异常 → 事务回滚（建议不落）+ Run 置 FAILED。
 */
@Slf4j
@Service
public class MrpPlanServiceImpl implements MrpPlanService {

    private static final DateTimeFormatter RUN_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MrpRunDao runDao;
    private final MrpSuggestionDao suggestionDao;
    private final MdmItemDao mdmItemDao;
    private final MrpDataSource dataSource;
    private final ProcMrpService procMrpService;
    private final SysParamService sysParamService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public MrpPlanServiceImpl(MrpRunDao runDao, MrpSuggestionDao suggestionDao,
                              MdmItemDao mdmItemDao, MrpDataSource dataSource,
                              ProcMrpService procMrpService, SysParamService sysParamService,
                              ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.runDao = runDao;
        this.suggestionDao = suggestionDao;
        this.mdmItemDao = mdmItemDao;
        this.dataSource = dataSource;
        this.procMrpService = procMrpService;
        this.sysParamService = sysParamService;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ---------- 运行 ----------

    @Override
    public Map<String, Object> run(String scopeType, String scopeValue) {
        requireAny("运行 MRP");
        String scope = isNotBlank(scopeType) ? scopeType.trim() : MrpRun.SCOPE_FULL;
        if (!MrpRun.SCOPE_FULL.equals(scope) && !MrpRun.SCOPE_CATEGORY.equals(scope)
                && !MrpRun.SCOPE_GROUP.equals(scope)) {
            throw new ServiceException(422, "运行范围取值非法（FULL/CATEGORY/GROUP）");
        }
        if (!MrpRun.SCOPE_FULL.equals(scope) && !isNotBlank(scopeValue)) {
            throw new ServiceException(422, "按分类/物料组运行须指定范围值");
        }
        // 互斥（design D8）：已有 RUNNING → 422
        long running = runDao.selectCount(new LambdaQueryWrapper<MrpRun>()
                .eq(MrpRun::getRunStatus, MrpRun.ST_RUNNING));
        if (running > 0) {
            throw new ServiceException(422, "已有 MRP 运行进行中，请等待完成后再触发");
        }

        MrpRun run = insertRunResolvingConflict(scope, scopeValue);
        try {
            CalcResult calc = calc(scope, scopeValue);
            txTemplate.executeWithoutResult(sts -> {
                // 重跑取代（task 5.2 同事务）：同物料同类型未终态旧建议 → SUPERSEDED
                for (MrpSuggestion s : calc.suggestions) {
                    List<MrpSuggestion> old = suggestionDao.selectList(new LambdaQueryWrapper<MrpSuggestion>()
                            .eq(MrpSuggestion::getItemCode, s.getItemCode())
                            .eq(MrpSuggestion::getType, s.getType())
                            .in(MrpSuggestion::getStatus, MrpSuggestion.ST_PENDING, MrpSuggestion.ST_CONFIRMED));
                    for (MrpSuggestion o : old) {
                        o.setStatus(MrpSuggestion.ST_SUPERSEDED);
                        o.setSupersededByRun(run.getRunNo());
                        suggestionDao.updateById(o);
                    }
                    s.setRunId(run.getId());
                    suggestionDao.insert(s);
                }
                MrpRun done = runDao.selectById(run.getId());
                done.setRunStatus(MrpRun.ST_DONE);
                done.setStatScanned(calc.scanned);
                done.setStatSuggested(calc.suggested);
                done.setStatException(calc.exceptions);
                done.setFinishAt(LocalDateTime.now());
                runDao.updateById(done);
            });
            log.info("MRP 运行完成：{} 范围={} 扫描={} 建议={} 异常={}",
                    run.getRunNo(), scope, calc.scanned, calc.suggested, calc.exceptions);
            Map<String, Object> out = toMap(runDao.selectById(run.getId()));
            out.put("suggested", calc.suggested);
            out.put("exceptions", calc.exceptions);
            return out;
        } catch (Throwable e) {
            // 事务已回滚（建议不落、DONE 未写），留 FAILED 痕并释放互斥位
            MrpRun failed = runDao.selectById(run.getId());
            if (failed != null) {
                failed.setRunStatus(MrpRun.ST_FAILED);
                failed.setFailMsg(truncate(e.getMessage(), 500));
                failed.setFinishAt(LocalDateTime.now());
                runDao.updateById(failed);
            }
            log.error("MRP 运行失败：{}", run.getRunNo(), e);
            if (e instanceof ServiceException se) {
                throw se;
            }
            throw new ServiceException(500, "MRP 运行失败：" + e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> runs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpRun r : runDao.selectList(new LambdaQueryWrapper<MrpRun>()
                .orderByDesc(MrpRun::getCreateDate).last("LIMIT 200"))) {
            out.add(toMap(r));
        }
        return out;
    }

    // ---------- 建议查询（三菜单共用） ----------

    @Override
    public List<Map<String, Object>> suggestions(String type, String status, String runId,
                                                 String keyword, boolean exceptionsOnly) {
        LambdaQueryWrapper<MrpSuggestion> w = new LambdaQueryWrapper<>();
        if (exceptionsOnly) {
            // 5.3.3：EXCESS 行 + OVERDUE 标记行，仅活跃态（PENDING/CONFIRMED）——
            // 被取代/已取消/已转换属历史，不作为待处置异常展示（proposal D7 视图口径）
            w.and(q -> q.eq(MrpSuggestion::getType, MrpSuggestion.TYPE_EXCESS)
                    .or().eq(MrpSuggestion::getOverdueFlag, "1"));
            w.in(MrpSuggestion::getStatus, MrpSuggestion.ST_PENDING, MrpSuggestion.ST_CONFIRMED);
        } else {
            w.eq(isNotBlank(type), MrpSuggestion::getType, type);
        }
        w.eq(isNotBlank(status), MrpSuggestion::getStatus, status);
        w.eq(isNotBlank(runId), MrpSuggestion::getRunId, runId);
        if (isNotBlank(keyword)) {
            String like = "%" + keyword.trim() + "%";
            w.and(q -> q.like(MrpSuggestion::getItemCode, like)
                    .or().like(MrpSuggestion::getItemName, like));
        }
        w.orderByDesc(MrpSuggestion::getCreateDate).last("LIMIT 500");
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpSuggestion s : suggestionDao.selectList(w)) {
            out.add(toMap(s));
        }
        return out;
    }

    // ---------- 审核生命周期（task 6.1，CAS） ----------

    @Override
    public Map<String, Object> confirm(String id, BigDecimal confirmQty, LocalDate confirmDate) {
        requireAny("确认建议");
        MrpSuggestion s = requireSuggestion(id);
        if (MrpSuggestion.TYPE_EXCESS.equals(s.getType())) {
            throw new ServiceException(422, "过量供给行不可确认（仅异常处置）");
        }
        if (!MrpSuggestion.ST_PENDING.equals(s.getStatus())) {
            throw new ServiceException(422, "仅待审核建议可确认（当前状态：" + s.getStatus() + "）");
        }
        BigDecimal qty = confirmQty != null ? confirmQty : s.getSuggestQty();
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "确认量必须大于 0");
        }
        s.setOrigSuggestQty(s.getSuggestQty());
        s.setConfirmQty(qty);
        s.setConfirmDate(confirmDate != null ? confirmDate : s.getReqDate());
        s.setConfirmBy(currentOperator());
        s.setConfirmAt(LocalDateTime.now());
        casStatus(s, MrpSuggestion.ST_CONFIRMED);
        log.info("建议确认：{} {} 确认量 {}（原 {}）", s.getItemCode(), s.getType(), qty, s.getSuggestQty());
        return toMap(s);
    }

    @Override
    public void cancel(String id, String reason) {
        requireAny("取消建议");
        MrpSuggestion s = requireSuggestion(id);
        if (!isNotBlank(reason)) {
            throw new ServiceException(422, "取消原因必填（FR-4.5-2-7）");
        }
        if (!MrpSuggestion.ST_PENDING.equals(s.getStatus())
                && !MrpSuggestion.ST_CONFIRMED.equals(s.getStatus())) {
            throw new ServiceException(422, "仅待审核/已确认建议可取消（当前状态：" + s.getStatus() + "）");
        }
        s.setCancelReason(reason.trim());
        s.setCancelBy(currentOperator());
        s.setCancelAt(LocalDateTime.now());
        casStatus(s, MrpSuggestion.ST_CANCELLED);
        log.info("建议取消：{} {} 原因={}", s.getItemCode(), s.getType(), s.getCancelReason());
    }

    // ---------- 转正（task 6.2/6.3） ----------

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> convertPr(List<String> ids) {
        requireAny("生成请购单");
        List<MrpSuggestion> rows = requireConfirmed(ids, MrpSuggestion.TYPE_PURCHASE);
        // 组装四值：使重算 netReq = 确认量（PR 行数量取 netReq，createFromMrp 契约）
        List<Map<String, Object>> input = new ArrayList<>();
        int no = 1;
        for (MrpSuggestion s : rows) {
            BigDecimal target = s.getConfirmQty() != null ? s.getConfirmQty() : s.getSuggestQty();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rowNo", no++);
            r.put("itemCode", s.getItemCode());
            r.put("onHand", nz(s.getOnHandQty()));
            r.put("inProcess", nz(s.getInProcessQty()));
            r.put("inTransit", nz(s.getInTransitQty()));
            r.put("demand", nz(target).add(nz(s.getOnHandQty())).add(nz(s.getInProcessQty()))
                    .add(nz(s.getInTransitQty())).setScale(4, RoundingMode.HALF_UP));
            r.put("reqDate", (s.getConfirmDate() != null ? s.getConfirmDate() : s.getReqDate()).toString());
            r.put("sourceEnum", "计划订单");
            r.put("sourceDocNo", runNoOf(s));
            input.add(r);
        }
        // 预检（复用链路）：任一行 invalid/generatable=false → 整批 422 透传（spec 转正需求）
        Map<String, Object> preview = procMrpService.preview(input);
        List<Map<String, Object>> results = (List<Map<String, Object>>) preview.get("results");
        List<String> bads = new ArrayList<>();
        for (Map<String, Object> r : results) {
            if (!Boolean.TRUE.equals(r.get("valid")) || !Boolean.TRUE.equals(r.get("generatable"))) {
                bads.add(r.get("itemCode") + "：" + r.get("reason"));
            }
        }
        if (!bads.isEmpty()) {
            throw new ServiceException(422, "整批阻断（复用请购链路预检）：" + String.join("；", bads));
        }
        Map<String, Object> gen = procMrpService.generate(input);
        Object prNo = gen.get("prNo");
        Integer failed = (Integer) gen.get("failed");
        if (prNo == null || (failed != null && failed > 0)) {
            throw new ServiceException(422, "请购单生成失败：" + gen.get("details"));
        }
        for (MrpSuggestion s : rows) {
            s.setTargetNo(String.valueOf(prNo));
            s.setConvertAt(LocalDateTime.now());
            casStatus(s, MrpSuggestion.ST_CONVERTED);
        }
        log.info("采购建议转正：{} 行 → PR {}", rows.size(), prNo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("targetNo", prNo);
        out.put("converted", rows.size());
        return out;
    }

    @Override
    public Map<String, Object> convertMo(List<String> ids) {
        requireAny("转计划工单");
        List<MrpSuggestion> rows = requireConfirmed(ids, MrpSuggestion.TYPE_PRODUCTION);
        // PMO 占位单号（proposal D6）：按日流水，计数竞争 + 递增重试（TARGET_NO 无唯一索引——
        // PR 批量转正多行共享同一 PR 号，无法建唯一键；以计数查询近似，窗口可接受）
        String pmo = nextPmo();
        for (MrpSuggestion s : rows) {
            s.setTargetNo(pmo);
            s.setConvertAt(LocalDateTime.now());
            casStatus(s, MrpSuggestion.ST_CONVERTED);
        }
        log.info("生产建议转正：{} 行 → {}", rows.size(), pmo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("targetNo", pmo);
        out.put("converted", rows.size());
        return out;
    }

    // ---------- 异常处置（task 6.4） ----------

    @Override
    public void handle(String id, String note) {
        requireAny("处置异常");
        MrpSuggestion s = requireSuggestion(id);
        boolean isExcess = MrpSuggestion.TYPE_EXCESS.equals(s.getType());
        boolean isOverdue = "1".equals(s.getOverdueFlag());
        if (!isExcess && !isOverdue) {
            throw new ServiceException(422, "仅过量供给/逾期需求行可标记已处理");
        }
        if (!isNotBlank(note)) {
            throw new ServiceException(422, "处理备注必填");
        }
        s.setHandledFlag("1");
        s.setHandledNote(note.trim());
        s.setHandledBy(currentOperator());
        s.setHandledAt(LocalDateTime.now());
        suggestionDao.updateById(s);
        log.info("异常处置：{} {} 备注={}", s.getItemCode(), s.getType(), s.getHandledNote());
    }

    // ---------- 计算引擎（design D2） ----------

    private static class CalcResult {
        final List<MrpSuggestion> suggestions = new ArrayList<>();
        int scanned;
        int suggested;
        int exceptions;
    }

    private CalcResult calc(String scopeType, String scopeValue) {
        CalcResult out = new CalcResult();
        int maxDepth = sysParamService.getInt("BOM_MAX_NESTING_DEPTH", 8);

        // 1) 范围
        List<MdmItem> roots = dataSource.scopeItems(scopeType, scopeValue);
        if (roots.isEmpty()) {
            throw new ServiceException(422, "运行范围内无物料（检查范围值）");
        }
        out.scanned = roots.size();
        Map<String, MdmItem> master = dataSource.itemIndex(roots);
        Set<String> closure = new LinkedHashSet<>(master.keySet());

        // 2) 结构发现：MAKE 件已发布 BOM 递归（子件并入闭环；BOM 保存已卡控无环）
        Map<String, List<MrpDataSource.BomLine>> bomGraph = new LinkedHashMap<>();
        Set<String> structSeen = new HashSet<>();
        Queue<String> work = new ArrayDeque<>(closure);
        while (!work.isEmpty()) {
            String code = work.poll();
            if (!structSeen.add(code)) {
                continue;
            }
            MdmItem item = master.get(code);
            if (item == null || !"MAKE".equals(item.getPurchaseType())) {
                continue;
            }
            List<MrpDataSource.BomLine> lines = dataSource.publishedBomLines(code);
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            bomGraph.put(code, lines);
            for (MrpDataSource.BomLine line : lines) {
                for (String[] cand : line.candidates) {
                    String child = cand[0];
                    if (master.containsKey(child)) {
                        continue;
                    }
                    MdmItem childItem = mdmItemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                            .eq(MdmItem::getItemCode, child));
                    if (childItem != null) {
                        master.put(child, childItem);
                        closure.add(child);
                        work.add(child);
                    }
                }
            }
        }

        // 3) 批量取数（闭环）
        Map<String, MrpDataSource.SoDemand> soDemand = dataSource.soDemands(closure);
        Map<String, BigDecimal> onHand = dataSource.onHand(closure);
        Map<String, BigDecimal> inTransit = dataSource.inTransit(closure);
        Map<String, BigDecimal> pool = new HashMap<>();
        closure.forEach(c -> pool.put(c, onHand.getOrDefault(c, BigDecimal.ZERO)
                .add(inTransit.getOrDefault(c, BigDecimal.ZERO))));

        // 4) 拓扑序（Kahn）
        List<String> topo = topoSort(closure, bomGraph);

        // 5) 求值（父先于子；净 = 总需求 − 可用，不饱和）
        Map<String, BigDecimal> gross = new HashMap<>();
        Map<String, BigDecimal> allocated = new HashMap<>();
        Map<String, LocalDate> dateHint = new HashMap<>();
        Map<String, String> remark = new HashMap<>();
        Map<String, Integer> evalDepth = new HashMap<>();
        closure.forEach(c -> evalDepth.put(c, 0));

        for (String code : topo) {
            MdmItem item = master.get(code);
            if (item == null) {
                continue;
            }
            BigDecimal g = gross.getOrDefault(code, BigDecimal.ZERO);
            MrpDataSource.SoDemand so = soDemand.get(code);
            if (so != null) {
                g = g.add(so.qty);
            }
            // ROP 补货（BR-4.5-14）：初始可用 < ROP → 目标水位 ROP 计入需求（结算净 = 缺口）
            BigDecimal initAvail = pool.getOrDefault(code, BigDecimal.ZERO);
            BigDecimal rop = item.getRopQty();
            if (rop != null && rop.signum() > 0 && initAvail.compareTo(rop) < 0) {
                g = g.add(rop);
            }
            BigDecimal p = pool.getOrDefault(code, BigDecimal.ZERO);
            BigDecimal net = g.subtract(p); // 不饱和：负值 → EXCESS（spec L2755）
            gross.put(code, BigDecimal.ZERO);

            int d = evalDepth.getOrDefault(code, 0);
            LocalDate soDate = so != null ? so.earliestDate : null;
            LocalDate reqDate = soDate != null ? soDate
                    : dateHint.getOrDefault(code, LocalDate.now());

            if (net.signum() > 0) {
                out.suggestions.add(buildSuggestion(item, net, g,
                        onHand.getOrDefault(code, BigDecimal.ZERO),
                        inTransit.getOrDefault(code, BigDecimal.ZERO),
                        reqDate, remark.get(code), false));
                out.suggested++;
                // 爆炸（MAKE + 已发布 BOM + 深度内）
                if ("MAKE".equals(item.getPurchaseType()) && d < maxDepth) {
                    List<MrpDataSource.BomLine> lines = bomGraph.get(code);
                    if (lines != null) {
                        LocalDate start = backscheduleStart(item, reqDate);
                        for (MrpDataSource.BomLine line : lines) {
                            explode(line, net, pool, gross, allocated, remark);
                            for (String[] cand : line.candidates) {
                                String child = cand[0];
                                LocalDate prev = dateHint.get(child);
                                if (prev == null || start.isBefore(prev)) {
                                    dateHint.put(child, start);
                                }
                                evalDepth.merge(child, d + 1, Math::max);
                            }
                        }
                    }
                }
            } else if (net.signum() < 0 && g.signum() > 0) {
                // 过量供给：有需求但供给过剩（spec 异常与边界 L2755）
                out.suggestions.add(buildSuggestion(item, net, g,
                        onHand.getOrDefault(code, BigDecimal.ZERO),
                        inTransit.getOrDefault(code, BigDecimal.ZERO),
                        reqDate,
                        append(remark.get(code), "过量供给：提示计划员评估是否取消/推迟在途订单"),
                        true));
                out.exceptions++;
            }
        }

        for (MrpSuggestion s : out.suggestions) {
            if ("1".equals(s.getOverdueFlag())) {
                out.exceptions++;
            }
        }
        return out;
    }

    /** 替代分配：候选池封顶分配 + 缺量给首个启用替代（spec FR-4.5-2-4 唯一具体场景口径） */
    private void explode(MrpDataSource.BomLine line, BigDecimal parentNet,
                         Map<String, BigDecimal> pool, Map<String, BigDecimal> gross,
                         Map<String, BigDecimal> allocated, Map<String, String> remark) {
        BigDecimal loss = line.lossRate == null ? BigDecimal.ZERO : line.lossRate;
        BigDecimal lineDemand = parentNet.multiply(line.usage)
                .multiply(BigDecimal.ONE.add(loss.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)));
        BigDecimal remaining = lineDemand;
        List<String[]> cands = line.candidates;
        for (int i = 0; i < cands.size() && remaining.signum() > 0; i++) {
            String item = cands.get(i)[0];
            BigDecimal ratio = new BigDecimal(cands.get(i)[1]);
            BigDecimal p = pool.getOrDefault(item, BigDecimal.ZERO);
            BigDecimal alloc = allocated.getOrDefault(item, BigDecimal.ZERO);
            BigDecimal freeMainUnits = p.subtract(alloc).max(BigDecimal.ZERO)
                    .divide(ratio, 6, RoundingMode.HALF_UP);
            BigDecimal giveMain = remaining.min(freeMainUnits).max(BigDecimal.ZERO);
            if (giveMain.signum() <= 0) {
                continue;
            }
            BigDecimal give = giveMain.multiply(ratio);
            gross.merge(item, give, BigDecimal::add);
            allocated.merge(item, give, BigDecimal::add);
            remaining = remaining.subtract(giveMain);
        }
        if (remaining.signum() > 0) {
            // 缺量：有替代 → 首个启用替代承接（×比例）；无替代 → 主料欠单
            String[] target = cands.size() > 1 ? cands.get(1) : cands.get(0);
            BigDecimal ratio = new BigDecimal(target[1]);
            gross.merge(target[0], remaining.multiply(ratio), BigDecimal::add);
            if (!target[0].equals(cands.get(0)[0])) {
                int prio = 1;
                for (int i = 1; i < cands.size(); i++) {
                    if (cands.get(i)[0].equals(target[0])) {
                        prio = i;
                        break;
                    }
                }
                remark.merge(target[0],
                        "替代自 " + cands.get(0)[0] + "（优先级 " + prio + "）",
                        (a, b) -> a.contains(b) ? a : a + "；" + b);
            }
        }
    }

    /** 建议行组装：类型分派 / 五值留痕 / MOQ 上调 / 超最大库存提示 / 逐层倒排 + OVERDUE */
    private MrpSuggestion buildSuggestion(MdmItem item, BigDecimal net, BigDecimal demand,
                                          BigDecimal onHand, BigDecimal inTransit,
                                          LocalDate reqDate, String remarkIn, boolean excess) {
        MrpSuggestion s = new MrpSuggestion();
        s.setItemCode(item.getItemCode());
        s.setItemName(item.getItemName());
        s.setPurchaseType(item.getPurchaseType());
        // 五值留痕（spec「单期净算与中间量留痕」）
        s.setDemandQty(demand.setScale(4, RoundingMode.HALF_UP));
        s.setOnHandQty(onHand.setScale(4, RoundingMode.HALF_UP));
        s.setInProcessQty(BigDecimal.ZERO);
        s.setInTransitQty(inTransit.setScale(4, RoundingMode.HALF_UP));
        s.setNetReq(net.setScale(4, RoundingMode.HALF_UP));
        s.setStatus(MrpSuggestion.ST_PENDING);
        s.setOverdueFlag("0");
        s.setHandledFlag("0");
        s.setReqDate(reqDate);

        boolean buy = !"MAKE".equals(item.getPurchaseType());
        s.setType(excess ? MrpSuggestion.TYPE_EXCESS
                : (buy ? MrpSuggestion.TYPE_PURCHASE : MrpSuggestion.TYPE_PRODUCTION));

        if (excess) {
            s.setRemark(truncate(remarkIn, 500));
            return s;
        }

        String rmk = remarkIn;
        BigDecimal suggest = net;
        if (buy && item.getMinOrderQty() != null && item.getMinOrderQty().signum() > 0
                && net.compareTo(item.getMinOrderQty()) < 0) {
            suggest = item.getMinOrderQty();
            rmk = append(rmk, "按最小订购量调整（MOQ " + item.getMinOrderQty().toPlainString() + "）");
        }
        s.setSuggestQty(suggest.setScale(4, RoundingMode.HALF_UP));

        // 倒排（FR-4.5-2-5）：需求日 − 提前期；缺路线兜底 leadTimeDays + 行内提示
        if (buy) {
            long lead = item.getLeadTimeDays() == null ? 0 : Math.max(0, item.getLeadTimeDays());
            s.setOrderDate(reqDate.minusDays(lead));
        } else {
            BigDecimal route = dataSource.publishedRouteLeadDays(item.getItemCode());
            long lead;
            if (route != null) {
                lead = Math.max(0, route.longValue());
            } else {
                lead = item.getLeadTimeDays() == null ? 0 : Math.max(0, item.getLeadTimeDays());
                rmk = append(rmk, "无已发布工艺路线，按物料提前期兜底倒排");
            }
            s.setOrderDate(reqDate.minusDays(lead));
        }
        if (s.getOrderDate() != null && s.getOrderDate().isBefore(LocalDate.now())) {
            s.setOverdueFlag("1");
        }
        // 超最大库存提示（FR-4.5-2-6，L4 不阻断）
        if (item.getMaxStock() != null && item.getMaxStock().signum() > 0) {
            BigDecimal after = onHand.add(inTransit).add(suggest);
            if (after.compareTo(item.getMaxStock()) > 0) {
                rmk = append(rmk, "超最大库存（可用+建议 " + after.toPlainString()
                        + " > " + item.getMaxStock().toPlainString() + "），提示计划员确认");
            }
        }
        s.setRemark(truncate(rmk, 500));
        return s;
    }

    /** 父件开工日 = 需求日 − 路线 ΣLT（缺路线兜底 leadTimeDays；行内提示由父件建议行自带） */
    private LocalDate backscheduleStart(MdmItem item, LocalDate reqDate) {
        BigDecimal route = dataSource.publishedRouteLeadDays(item.getItemCode());
        long lead = route != null ? Math.max(0, route.longValue())
                : (item.getLeadTimeDays() == null ? 0 : Math.max(0, item.getLeadTimeDays()));
        return reqDate.minusDays(lead);
    }

    private List<String> topoSort(Set<String> nodes, Map<String, List<MrpDataSource.BomLine>> graph) {
        Map<String, Integer> indeg = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        nodes.forEach(n -> indeg.put(n, 0));
        graph.forEach((parent, lines) -> {
            for (MrpDataSource.BomLine line : lines) {
                for (String[] cand : line.candidates) {
                    String child = cand[0];
                    if (!nodes.contains(child)) {
                        continue;
                    }
                    children.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
                    indeg.merge(child, 1, Integer::sum);
                }
            }
        });
        Queue<String> q = new ArrayDeque<>();
        for (String n : nodes) {
            if (indeg.getOrDefault(n, 0) == 0) {
                q.add(n);
            }
        }
        List<String> out = new ArrayList<>();
        while (!q.isEmpty()) {
            String n = q.poll();
            out.add(n);
            for (String c : children.getOrDefault(n, List.of())) {
                if (indeg.merge(c, -1, Integer::sum) == 0) {
                    q.add(c);
                }
            }
        }
        if (out.size() < nodes.size()) {
            // BOM 保存已卡控循环；残余兜底序 + 结构发现无深度环风险（爆炸深度另有上限）
            log.warn("MRP 拓扑发现残余节点 {} 个（疑似 BOM 环，按原序兜底）", nodes.size() - out.size());
            for (String n : nodes) {
                if (!out.contains(n)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    // ---------- 私有辅助 ----------

    private List<MrpSuggestion> requireConfirmed(List<String> ids, String expectedType) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException(422, "请选择要转正的建议行");
        }
        List<MrpSuggestion> rows = new ArrayList<>();
        for (String id : ids) {
            MrpSuggestion s = requireSuggestion(id);
            if (!expectedType.equals(s.getType())) {
                throw new ServiceException(422, "建议类型不符（需要 " + expectedType
                        + "，实际 " + s.getType() + "）");
            }
            if (!MrpSuggestion.ST_CONFIRMED.equals(s.getStatus())) {
                throw new ServiceException(422, "仅已确认建议可转正（" + s.getItemCode()
                        + " 当前 " + s.getStatus() + "）");
            }
            rows.add(s);
        }
        return rows;
    }

    private MrpSuggestion requireSuggestion(String id) {
        MrpSuggestion s = suggestionDao.selectById(id);
        if (s == null) {
            throw new ServiceException(404, "建议行不存在");
        }
        return s;
    }

    /**
     * 白名单 + CAS（id + status + ver_no），影响行数 0 → 422 并发冲突。
     * 先 updateById 持久化实体上的业务留痕字段（targetNo/confirmQty/cancelReason/时间戳等），
     * 再以旧状态做 CAS 状态迁移——两步同事务，输者整体回滚。
     */
    private void casStatus(MrpSuggestion s, String to) {
        suggestionDao.updateById(s);
        int rows = suggestionDao.update(null, new LambdaUpdateWrapper<MrpSuggestion>()
                .eq(MrpSuggestion::getId, s.getId())
                .eq(MrpSuggestion::getStatus, s.getStatus())
                .eq(MrpSuggestion::getVerNo, s.getVerNo())
                .set(MrpSuggestion::getStatus, to)
                .set(MrpSuggestion::getVerNo, s.getVerNo() + 1));
        if (rows == 0) {
            throw new ServiceException(422,
                    "状态并发冲突（" + s.getStatus() + " → " + to + "），请刷新后重试");
        }
        s.setStatus(to);
        s.setVerNo(s.getVerNo() + 1);
    }

    /** RUNNING 行 autocommit 先落（互斥位）；RUN_NO 唯一冲突递增重试 */
    private MrpRun insertRunResolvingConflict(String scopeType, String scopeValue) {
        String day = LocalDate.now().format(RUN_DAY);
        String prefix = "RUN-" + day + "-";
        long today = runDao.selectCount(new LambdaQueryWrapper<MrpRun>()
                .likeRight(MrpRun::getRunNo, prefix));
        for (int attempt = 0; attempt < 99; attempt++) {
            MrpRun run = new MrpRun();
            run.setRunNo(prefix + String.format("%03d", today + 1 + attempt));
            run.setScopeType(scopeType);
            run.setScopeValue(isNotBlank(scopeValue) ? scopeValue.trim() : null);
            run.setRunStatus(MrpRun.ST_RUNNING);
            run.setRunBy(currentOperator());
            run.setRunAt(LocalDateTime.now());
            try {
                runDao.insert(run);
                return run;
            } catch (DuplicateKeyException e) {
                today++;
            }
        }
        throw new ServiceException(422, "运行流水号分配冲突超限，请稍后重试");
    }

    /** PMO 占位单号按日流水（计数 + 递增重试，见 convertMo 注释） */
    private String nextPmo() {
        String day = LocalDate.now().format(RUN_DAY);
        String prefix = PMO_PREFIX + day + "-";
        long today = suggestionDao.selectCount(new LambdaQueryWrapper<MrpSuggestion>()
                .likeRight(MrpSuggestion::getTargetNo, prefix));
        for (int attempt = 0; attempt < 99; attempt++) {
            String candidate = prefix + String.format("%03d", today + 1 + attempt);
            long clash = suggestionDao.selectCount(new LambdaQueryWrapper<MrpSuggestion>()
                    .eq(MrpSuggestion::getTargetNo, candidate));
            if (clash == 0) {
                return candidate;
            }
        }
        throw new ServiceException(422, "计划工单占位号分配冲突超限，请稍后重试");
    }

    private String runNoOf(MrpSuggestion s) {
        MrpRun r = runDao.selectById(s.getRunId());
        return r == null ? null : r.getRunNo();
    }

    private void requireAny(String action) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String r : new String[]{"ROLE_PLANNER", "ROLE_ADMIN"}) {
            if (auth.getAuthorities().contains(new SimpleGrantedAuthority(r))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action);
    }

    private String currentOperator() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return auth == null || auth.getName() == null ? "system" : auth.getName();
        } catch (Exception e) {
            return "system";
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String append(String base, String add) {
        if (base == null || base.isBlank()) {
            return add;
        }
        return base.contains(add) ? base : base + "；" + add;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private Map<String, Object> toMap(Object bean) {
        return objectMapper.convertValue(bean, new TypeReference<Map<String, Object>>() {
        });
    }
}
