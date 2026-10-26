package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mrp.CostLineDao;
import com.erp.dao.mrp.CostSummaryDao;
import com.erp.dao.mrp.MrpDispatchDao;
import com.erp.dao.mrp.MrpFeedDao;
import com.erp.dao.mrp.MrpMoBomDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.MrpOpWcStandardDao;
import com.erp.dao.mrp.MrpReportingDao;
import com.erp.dao.mrp.MrpRoutingOpDao;
import com.erp.dao.mrp.OverheadPoolDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.CostLine;
import com.erp.entity.mrp.CostSummary;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpFeed;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpReporting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.OverheadPool;
import com.erp.service.SysParamService;
import com.erp.service.mrp.CapacityLoadService;
import com.erp.service.mrp.CostAggregationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 量产工单成本归集实现（change add-cost-aggregation，spec production-cost-aggregation）。
 * 重建式（design D1）：按 MO 删 summary/lines 重插；料工费取数口径与试产/返工
 * aggregateCost 同族（投料×单价 / 报工工时×费率 / 池×分摊比例）。
 */
@Slf4j
@Service
public class CostAggregationServiceImpl implements CostAggregationService {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String MO_TYPE_TRIAL = "TRIAL";
    /** 产能近似负荷钳制上限（design D6：999 占位与超载按 1.5 计） */
    private static final BigDecimal MAX_UTIL = new BigDecimal("1.5");

    private final MrpMoDao moDao;
    private final MrpFeedDao feedDao;
    private final MrpReportingDao reportingDao;
    private final CostSummaryDao summaryDao;
    private final CostLineDao lineDao;
    private final OverheadPoolDao poolDao;
    private final MdmItemDao mdmItemDao;
    private final MrpMoBomDao moBomDao;
    private final MrpRoutingOpDao routingOpDao;
    private final MrpOpWcStandardDao opWcStandardDao;
    private final MrpDispatchDao dispatchDao;
    private final PurchaseOrderLineDao poLineDao;
    private final SysParamService sysParam;
    private final CapacityLoadService capacityLoadService;

    public CostAggregationServiceImpl(MrpMoDao moDao,
                                      MrpFeedDao feedDao,
                                      MrpReportingDao reportingDao,
                                      CostSummaryDao summaryDao,
                                      CostLineDao lineDao,
                                      OverheadPoolDao poolDao,
                                      MdmItemDao mdmItemDao,
                                      MrpMoBomDao moBomDao,
                                      MrpRoutingOpDao routingOpDao,
                                      MrpOpWcStandardDao opWcStandardDao,
                                      MrpDispatchDao dispatchDao,
                                      PurchaseOrderLineDao poLineDao,
                                      SysParamService sysParam,
                                      CapacityLoadService capacityLoadService) {
        this.moDao = moDao;
        this.feedDao = feedDao;
        this.reportingDao = reportingDao;
        this.summaryDao = summaryDao;
        this.lineDao = lineDao;
        this.poolDao = poolDao;
        this.mdmItemDao = mdmItemDao;
        this.moBomDao = moBomDao;
        this.routingOpDao = routingOpDao;
        this.opWcStandardDao = opWcStandardDao;
        this.dispatchDao = dispatchDao;
        this.poLineDao = poLineDao;
        this.sysParam = sysParam;
        this.capacityLoadService = capacityLoadService;
    }

    // ---------- 归集（重建式） ----------

    /**
     * 参与调用方事务（完工钩子同事务读取未提交的 QUALIFIED_QTY——REQUIRES_NEW 曾导致
     * new 事务读不到完工事务刚设置的合格产出，goodQty=0 连锁错误，已修）。
     * 校验前置：所有 422（缺价等）在任何写入之前抛出 → 无写入即无需回滚，
     * 完工钩子捕获告警后完工照常提交（补价后 5.11 重跑，关单 C-4.5-09 兜底）。
     */
    @Override
    public Map<String, Object> aggregate(String moId) {
        MrpMo mo = requireMo(moId);

        // 先取旧 summary（审阅状态在重跑间防丢——超阈值且已 REVIEWED 则保持）
        CostSummary old = summaryDao.selectOne(new LambdaQueryWrapper<CostSummary>()
                .eq(CostSummary::getMoId, moId).last("LIMIT 1"));

        BigDecimal goodQty = nvl(mo.getQualifiedQty());
        String period = LocalDate.now().format(PERIOD_FMT);

        // 1) 直接材料（FR-4.5-6-1）：投料（未回退）× 单价；标准成本 → 最近采购价兜底 → 双缺 422
        List<MrpFeed> feeds = feedDao.selectList(new LambdaQueryWrapper<MrpFeed>()
                .eq(MrpFeed::getMoId, moId)
                .eq(MrpFeed::getReturnedFlag, "0"));
        Map<String, BigDecimal> feedQty = new LinkedHashMap<>();
        Map<String, String> itemName = new LinkedHashMap<>();
        for (MrpFeed f : feeds) {
            if (f.getItemCode() == null) {
                continue;
            }
            feedQty.merge(f.getItemCode(), nvl(f.getQty()), BigDecimal::add);
            itemName.putIfAbsent(f.getItemCode(), f.getItemName());
        }
        Map<String, BigDecimal> price = resolvePrices(feedQty.keySet());
        List<String> noPrice = feedQty.keySet().stream()
                .filter(c -> price.get(c) == null)
                .collect(Collectors.toList());
        if (!noPrice.isEmpty()) {
            throw new ServiceException(422, "材料单价缺失（标准成本与最近采购价均无），归集阻断："
                    + String.join("、", noPrice) + "（FR-4.5-6-1）");
        }
        // ===== 校验到此全部通过，开始重建写入（此前零写入）=====
        summaryDao.hardDeleteByMoId(moId);
        lineDao.hardDeleteByMoId(moId);

        BigDecimal material = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : feedQty.entrySet()) {
            BigDecimal p = price.get(e.getKey());
            BigDecimal amt = e.getValue().multiply(p);
            material = material.add(amt);
            insertLine(moId, CostLine.T_MATERIAL, e.getKey(),
                    itemName.get(e.getKey()), e.getValue(), p, amt, null);
        }

        // 2) 直接人工（FR-4.5-6-2）：已确认报工（准备+运行）× STD_LABOR_RATE（零工时不计入）
        BigDecimal laborRate = sysParam.getAmount("STD_LABOR_RATE", new BigDecimal("50"));
        List<MrpReporting> reportings = reportingDao.selectList(
                new LambdaQueryWrapper<MrpReporting>()
                        .eq(MrpReporting::getMoId, moId)
                        .eq(MrpReporting::getStatus, MrpReporting.ST_CONFIRMED));
        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal actualHours = BigDecimal.ZERO;
        for (MrpReporting r : reportings) {
            BigDecimal hours = nvl(r.getSetupHours()).add(nvl(r.getRunHours()));
            if (hours.signum() <= 0) {
                continue; // 工时为零不计入
            }
            actualHours = actualHours.add(hours);
            labor = labor.add(hours.multiply(laborRate));
            insertLine(moId, CostLine.T_LABOR, r.getRptNo(), r.getMoNo(),
                    hours, laborRate, hours.multiply(laborRate), null);
        }

        // 3) 制造费用（FR-4.5-6-3）：池 × 分摊比例（缺池 → 0 + 标记，不阻断料工）
        String ohRule = overheadRule(period);
        BigDecimal poolAmt = poolAmount(period);
        BigDecimal overhead = BigDecimal.ZERO;
        String overheadMark = null;
        if (poolAmt == null || poolAmt.signum() <= 0) {
            overheadMark = "制造费用未归集（当期费用池未维护）";
            insertLine(moId, CostLine.T_OH, period, "期次池未维护",
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, CostLine.MARK_NO_POOL);
        } else {
            BigDecimal num = baseline(mo, ohRule, reportings);
            BigDecimal den = totalBaseline(ohRule);
            if (den.signum() <= 0) {
                // 池已维护但当期无任何基准（本单也无工时）→ 无可分摊，记 0 不标缺失
                insertLine(moId, CostLine.T_OH, period, "期次池分摊",
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
            } else {
                BigDecimal unitRate = poolAmt.divide(den, 6, RoundingMode.HALF_UP);
                overhead = num.multiply(unitRate).setScale(4, RoundingMode.HALF_UP);
                insertLine(moId, CostLine.T_OH, period, "期次池分摊",
                        num, unitRate, overhead, null);
            }
        }

        // 4) 汇总 + 标准成本法四分解差异（FR-4.5-6-4/5）
        BigDecimal total = material.add(labor).add(overhead);
        BigDecimal unitCost = goodQty.signum() > 0
                ? total.divide(goodQty, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        MdmItem product = mdmItemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, mo.getProductCode()).last("LIMIT 1"));
        BigDecimal stdUnit = product == null || product.getStandardCost() == null
                ? BigDecimal.ZERO : product.getStandardCost();
        BigDecimal stdTotal = stdUnit.multiply(goodQty);
        BigDecimal varTotal = total.subtract(stdTotal);
        BigDecimal varRate = stdTotal.signum() > 0
                ? varTotal.abs().divide(stdTotal, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal varVolume = volumeVariance(mo, feedQty, price, goodQty);
        BigDecimal varEfficiency = efficiencyVariance(mo, actualHours, goodQty);
        BigDecimal varCapacity = capacityVariance(mo, period, overhead);

        BigDecimal threshold = sysParam.getRate("COST_VARIANCE_RATE", new BigDecimal("0.05"));
        // 审阅状态防丢：重跑后仍超阈值 → 保持已审阅；未超 → 复位 NONE
        String reportStatus;
        if (varRate.compareTo(threshold) > 0) {
            reportStatus = old != null && CostSummary.REPORT_REVIEWED.equals(old.getReportStatus())
                    ? CostSummary.REPORT_REVIEWED : CostSummary.REPORT_RAISED;
        } else {
            reportStatus = CostSummary.REPORT_NONE;
        }

        CostSummary s = new CostSummary();
        s.setMoId(moId);
        s.setMoNo(mo.getMoNo());
        s.setPeriod(period);
        s.setMaterialCost(material);
        s.setLaborCost(labor);
        s.setOverheadCost(overhead);
        s.setTotalCost(total);
        s.setGoodQty(goodQty);
        s.setUnitCost(unitCost);
        s.setStdTotalCost(stdTotal);
        s.setVarTotal(varTotal);
        s.setVarPrice(BigDecimal.ZERO); // 价格差异：生产侧按标准价领用恒 0（采购 1406 已确认）
        s.setVarVolume(varVolume);
        s.setVarEfficiency(varEfficiency);
        s.setVarCapacity(varCapacity);
        s.setVarRate(varRate);
        s.setReportStatus(reportStatus);
        s.setMark(overheadMark);
        summaryDao.insert(s);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", s);
        out.put("material", material);
        out.put("labor", labor);
        out.put("overhead", overhead);
        out.put("total", total);
        log.info("成本归集 {}：料 {} 工 {} 费 {} 总 {} 差异率 {}",
                mo.getMoNo(), material, labor, overhead, total, varRate);
        return out;
    }

    // ---------- 查询 ----------

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Map<String, Object> rerun(String moId) {
        requireWriter("成本重跑");
        return aggregate(moId);
    }

    @Override
    public Map<String, Object> detail(String moId) {
        requireReader("归集详情查询");
        CostSummary s = summaryDao.selectOne(new LambdaQueryWrapper<CostSummary>()
                .eq(CostSummary::getMoId, moId).last("LIMIT 1"));
        List<CostLine> lines = lineDao.selectList(new LambdaQueryWrapper<CostLine>()
                .eq(CostLine::getMoId, moId).orderByAsc(CostLine::getCostType)
                .orderByAsc(CostLine::getDimKey));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", s);
        out.put("lines", lines);
        return out;
    }

    @Override
    public List<Map<String, Object>> list(String keyword, String period) {
        requireReader("归集列表查询");
        LambdaQueryWrapper<CostSummary> qw = new LambdaQueryWrapper<CostSummary>()
                .eq(hasText(period), CostSummary::getPeriod, trim(period))
                .orderByDesc(CostSummary::getPeriod).orderByDesc(CostSummary::getCreateDate);
        List<CostSummary> rows = summaryDao.selectList(qw);
        if (hasText(keyword)) {
            String k = keyword.trim();
            rows = rows.stream().filter(r ->
                    (r.getMoNo() != null && r.getMoNo().contains(k))
                            || (r.getMark() != null && r.getMark().contains(k)))
                    .collect(Collectors.toList());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (CostSummary r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("summary", r);
            // 关键词若为物料编码，命中的明细行一并回带（5.11.1 页面下钻预筛）
            if (hasText(keyword)) {
                List<CostLine> lines = lineDao.selectList(new LambdaQueryWrapper<CostLine>()
                        .eq(CostLine::getMoId, r.getMoId())
                        .eq(CostLine::getDimKey, keyword.trim()));
                m.put("matchedLines", lines);
            }
            out.add(m);
        }
        return out;
    }

    // ---------- 费用池（task 2.2） ----------

    @Override
    @Transactional
    public Map<String, Object> savePool(Map<String, Object> body) {
        requireWriter("费用池录入");
        String period = str(body.get("period"));
        if (period == null || !period.matches("\\d{6}")) {
            throw new ServiceException(422, "费用池期次须为 yyyyMM 格式");
        }
        BigDecimal amt = dec(body.get("poolAmt"));
        if (amt == null || amt.signum() < 0) {
            throw new ServiceException(422, "池总额不得为负");
        }
        String rule = str(body.get("rule"));
        rule = hasText(rule) ? rule.trim().toUpperCase() : OverheadPool.RULE_TIME;
        if (!OverheadPool.RULE_TIME.equals(rule) && !OverheadPool.RULE_MACHINE.equals(rule)
                && !OverheadPool.RULE_QTY.equals(rule)) {
            throw new ServiceException(422, "分摊规则仅支持 TIME/MACHINE/QTY：" + rule);
        }
        OverheadPool p = poolDao.selectOne(new LambdaQueryWrapper<OverheadPool>()
                .eq(OverheadPool::getPeriod, period).last("LIMIT 1"));
        boolean created = p == null;
        if (p == null) {
            p = new OverheadPool();
            p.setPeriod(period);
        }
        p.setPoolAmt(amt);
        p.setRule(rule);
        p.setRemark(str(body.get("remark")));
        if (created) {
            poolDao.insert(p);
        } else {
            poolDao.updateById(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pool", p);
        out.put("created", created);
        return out;
    }

    @Override
    public List<OverheadPool> listPools() {
        requireReader("费用池查询");
        return poolDao.selectList(new LambdaQueryWrapper<OverheadPool>()
                .orderByDesc(OverheadPool::getPeriod));
    }

    // ---------- 差异审阅（task 2.3） ----------

    @Override
    @Transactional
    public void reviewVariance(String moId) {
        // 主管审阅（spec「提交主管审阅」）：PLAN_MGR/ADMIN；change add-cost-accounting
        // 扩 ROLE_FINANCE_MGR——向角色表 2912「成本会计/财务审阅」归位（design D2 留痕）
        requireAnyRole("差异报告审阅", "ROLE_PLAN_MGR", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        CostSummary s = summaryDao.selectOne(new LambdaQueryWrapper<CostSummary>()
                .eq(CostSummary::getMoId, moId).last("LIMIT 1"));
        if (s == null) {
            throw new ServiceException(422, "该工单尚未归集，无差异报告");
        }
        if (!CostSummary.REPORT_RAISED.equals(s.getReportStatus())) {
            throw new ServiceException(422, "差异报告状态为 " + s.getReportStatus()
                    + "，无需审阅（仅 RAISED 可审阅）");
        }
        s.setReportStatus(CostSummary.REPORT_REVIEWED);
        summaryDao.updateById(s);
    }

    // ---------- 关单校验（task 3.1，C-4.5-09） ----------

    @Override
    public void checkForClose(String moId) {
        // 由工单关闭链服务端调用（关单人是计划线，不设成本角色门槛）
        CostSummary s = summaryDao.selectOne(new LambdaQueryWrapper<CostSummary>()
                .eq(CostSummary::getMoId, moId).last("LIMIT 1"));
        if (s == null) {
            throw new ServiceException(422,
                    "成本归集未完成，请先在 5.11 成本归集页完成归集（C-4.5-09）");
        }
        // 要素缺失（缺池等 MARK 挂在明细行）
        List<CostLine> marked = lineDao.selectList(new LambdaQueryWrapper<CostLine>()
                .eq(CostLine::getMoId, moId)
                .isNotNull(CostLine::getMark));
        if (!marked.isEmpty()) {
            List<String> hints = marked.stream().map(CostLine::getMark)
                    .distinct().collect(Collectors.toList());
            throw new ServiceException(422,
                    "成本归集不完整（" + String.join("、", hints)
                            + "），请维护后重新归集再关闭（C-4.5-09）");
        }
        // 未处理差异（FR-4.5-6-8：超阈值须审阅后再关闭）
        BigDecimal threshold = sysParam.getRate("COST_VARIANCE_RATE", new BigDecimal("0.05"));
        if (s.getVarRate() != null && s.getVarRate().compareTo(threshold) > 0
                && !CostSummary.REPORT_REVIEWED.equals(s.getReportStatus())) {
            throw new ServiceException(422, "存在未处理差异（差异率 "
                    + s.getVarRate().multiply(new BigDecimal("100")).setScale(2,
                            RoundingMode.HALF_UP) + "%，阈值 "
                    + threshold.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                    + "%），请在 5.11 处理差异后再关闭（FR-4.5-6-8）");
        }
    }

    // ---------- 内部：取数与口径 ----------

    /** 单价解析：标准成本 → 最近一次 APPROVED PO 采购价（缺失置 null 由调用方阻断） */
    private Map<String, BigDecimal> resolvePrices(Set<String> codes) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (codes.isEmpty()) {
            return out;
        }
        Map<String, BigDecimal> std = new LinkedHashMap<>();
        for (MdmItem it : mdmItemDao.selectList(new LambdaQueryWrapper<MdmItem>()
                .in(MdmItem::getItemCode, codes))) {
            if (it.getStandardCost() != null) {
                std.put(it.getItemCode(), it.getStandardCost());
            }
        }
        for (String c : codes) {
            BigDecimal p = std.get(c);
            if (p == null) {
                p = poLineDao.selectLatestPrice(c); // 兜底：最近采购价
            }
            out.put(c, p);
        }
        return out;
    }

    /** 期次池总额（无池返回 null） */
    private BigDecimal poolAmount(String period) {
        OverheadPool p = poolDao.selectOne(new LambdaQueryWrapper<OverheadPool>()
                .eq(OverheadPool::getPeriod, period).last("LIMIT 1"));
        return p == null ? null : p.getPoolAmt();
    }

    /** 分摊规则（无池/未配置 → TIME 默认） */
    private String overheadRule(String period) {
        OverheadPool p = poolDao.selectOne(new LambdaQueryWrapper<OverheadPool>()
                .eq(OverheadPool::getPeriod, period).last("LIMIT 1"));
        return p == null || !hasText(p.getRule()) ? OverheadPool.RULE_TIME : p.getRule();
    }

    /** 工单分摊基准（TIME=确认报工工时 / MACHINE=机时 / QTY=合格产出） */
    private BigDecimal baseline(MrpMo mo, String rule, List<MrpReporting> confirmed) {
        if (OverheadPool.RULE_QTY.equals(rule)) {
            return nvl(mo.getQualifiedQty());
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (MrpReporting r : confirmed) {
            sum = sum.add(OverheadPool.RULE_MACHINE.equals(rule)
                    ? nvl(r.getMachineHours())
                    : nvl(r.getSetupHours()).add(nvl(r.getRunHours())));
        }
        return sum;
    }

    /** 当期总基准：全部非试产工单之和（design D2「工单基准 / 当期总基准」） */
    private BigDecimal totalBaseline(String rule) {
        List<MrpMo> mos = moDao.selectList(null).stream()
                .filter(m -> !MO_TYPE_TRIAL.equals(m.getMoType()))
                .collect(Collectors.toList());
        if (OverheadPool.RULE_QTY.equals(rule)) {
            return mos.stream().map(MrpMo::getQualifiedQty).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        List<MrpReporting> all = reportingDao.selectList(new LambdaQueryWrapper<MrpReporting>()
                .eq(MrpReporting::getStatus, MrpReporting.ST_CONFIRMED));
        Set<String> moIds = mos.stream().map(MrpMo::getId).collect(Collectors.toSet());
        BigDecimal sum = BigDecimal.ZERO;
        for (MrpReporting r : all) {
            if (!moIds.contains(r.getMoId())) {
                continue;
            }
            sum = sum.add(OverheadPool.RULE_MACHINE.equals(rule)
                    ? nvl(r.getMachineHours())
                    : nvl(r.getSetupHours()).add(nvl(r.getRunHours())));
        }
        return sum;
    }

    /** 用量差异 = Σ(实际投料 − BOM 标准用量×合格产出) × 标准单价（FR-4.5-6-5 实算） */
    private BigDecimal volumeVariance(MrpMo mo, Map<String, BigDecimal> feedQty,
                                      Map<String, BigDecimal> price, BigDecimal goodQty) {
        Map<String, BigDecimal> bomQty = new LinkedHashMap<>();
        for (MrpMoBom b : moBomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                .eq(MrpMoBom::getMoId, mo.getId()))) {
            if (b.getItemCode() == null) {
                continue;
            }
            BigDecimal unit = nvl(b.getUnitQty())
                    .multiply(BigDecimal.ONE.add(nvl(b.getLossRate())));
            bomQty.merge(b.getItemCode(), unit, BigDecimal::add);
        }
        BigDecimal var = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : feedQty.entrySet()) {
            BigDecimal std = bomQty.getOrDefault(e.getKey(), BigDecimal.ZERO)
                    .multiply(goodQty);
            BigDecimal p = price.get(e.getKey());
            if (p == null) {
                continue; // 单价缺失的行在归集阶段已阻断，此处防御
            }
            var = var.add(e.getValue().subtract(std).multiply(p));
        }
        return var;
    }

    /** 效率差异 = (实际工时 − 标准工时) × STD_LABOR_RATE（标准工时=Σ工序定额×合格产出，无路线数据记 0） */
    private BigDecimal efficiencyVariance(MrpMo mo, BigDecimal actualHours, BigDecimal goodQty) {
        if (mo.getSourceRoutingId() == null || !hasText(mo.getSourceRoutingId())) {
            return BigDecimal.ZERO;
        }
        List<MrpRoutingOp> ops = routingOpDao.selectList(new LambdaQueryWrapper<MrpRoutingOp>()
                .eq(MrpRoutingOp::getRoutingId, mo.getSourceRoutingId()));
        if (ops.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal stdHours = BigDecimal.ZERO;
        for (MrpRoutingOp op : ops) {
            MrpOpWcStandard st = opWcStandardDao.selectOne(
                    new LambdaQueryWrapper<MrpOpWcStandard>()
                            .eq(MrpOpWcStandard::getOpCode, op.getOpCode())
                            .eq(MrpOpWcStandard::getWcCode, op.getWcCode())
                            .last("LIMIT 1"));
            if (st != null) {
                stdHours = stdHours.add(nvl(st.getSetupHours()).add(nvl(st.getRunHours())));
            }
        }
        stdHours = stdHours.multiply(goodQty);
        BigDecimal laborRate = sysParam.getAmount("STD_LABOR_RATE", new BigDecimal("50"));
        return actualHours.subtract(stdHours).multiply(laborRate);
    }

    /** 产能差异（近似）= OH × (1 − 当期负荷率)（design D6：负荷钳 [0,1.5]，999 占位按 1.5；无派工 → 负荷 0 → 差异 = OH） */
    private BigDecimal capacityVariance(MrpMo mo, String period, BigDecimal overhead) {
        List<MrpDispatch> ds = dispatchDao.selectList(new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getMoId, mo.getId()));
        Set<String> wcs = ds.stream().map(MrpDispatch::getWcCode)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (wcs.isEmpty()) {
            return overhead;
        }
        LocalDate from = LocalDate.of(Integer.parseInt(period.substring(0, 4)),
                Integer.parseInt(period.substring(4)), 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        BigDecimal rateSum = BigDecimal.ZERO;
        int cnt = 0;
        for (String wc : wcs) {
            for (CapacityLoadService.LoadRow row : capacityLoadService.loads(from, to, wc)) {
                if (row.loadRate() == null) {
                    continue;
                }
                BigDecimal u = row.loadRate().divide(new BigDecimal("100"), 4,
                        RoundingMode.HALF_UP);
                if (u.signum() < 0) {
                    u = BigDecimal.ZERO;
                }
                if (u.compareTo(MAX_UTIL) > 0) {
                    u = MAX_UTIL;
                }
                rateSum = rateSum.add(u);
                cnt++;
            }
        }
        if (cnt == 0) {
            return overhead;
        }
        BigDecimal load = rateSum.divide(new BigDecimal(cnt), 4, RoundingMode.HALF_UP);
        return overhead.multiply(BigDecimal.ONE.subtract(load)).setScale(4, RoundingMode.HALF_UP);
    }

    // ---------- helpers ----------

    private void insertLine(String moId, String costType, String dimKey, String dimName,
                            BigDecimal qty, BigDecimal rate, BigDecimal amount, String mark) {
        CostLine l = new CostLine();
        l.setMoId(moId);
        l.setCostType(costType);
        l.setDimKey(dimKey == null ? "" : dimKey);
        l.setDimName(dimName);
        l.setQty(qty == null ? BigDecimal.ZERO : qty);
        l.setRate(rate == null ? BigDecimal.ZERO : rate);
        l.setAmount(amount == null ? BigDecimal.ZERO : amount);
        l.setMark(mark);
        l.setDelFlag("0");
        lineDao.insert(l);
    }

    private MrpMo requireMo(String moId) {
        MrpMo mo = moId == null ? null : moDao.selectById(moId);
        if (mo == null) {
            throw new ServiceException(422, "工单不存在：" + moId);
        }
        return mo;
    }

    private void requireWriter(String action) {
        requireAnyRole(action, "ROLE_COST_ACCOUNTANT", "ROLE_ADMIN");
    }

    private void requireReader(String action) {
        requireAnyRole(action, "ROLE_COST_ACCOUNTANT", "ROLE_PLANNER",
                "ROLE_PLAN_MGR", "ROLE_ADMIN");
    }

    private void requireAnyRole(String action, String... allowed) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String want : allowed) {
            if (auth.getAuthorities().contains(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(want))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action + "（需 " + String.join("/", allowed) + "）");
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
