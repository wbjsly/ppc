package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.scm.ScmScorecardAppealDao;
import com.erp.dao.scm.ScmScorecardModelDao;
import com.erp.dao.scm.ScmScorecardRectifyDao;
import com.erp.dao.scm.ScmScorecardResultDao;
import com.erp.dao.scm.ScorecardCollectDao;
import com.erp.entity.scm.ScmScorecardAppeal;
import com.erp.entity.scm.ScmScorecardModel;
import com.erp.entity.scm.ScmScorecardRectify;
import com.erp.entity.scm.ScmScorecardResult;
import com.erp.security.IntfGuard;
import com.erp.service.bi.MetricGuard;
import com.erp.service.scm.ScorecardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记分卡实现（spec supplier-scorecard，4.9 流程七）。
 * 采集不可手工修改（仅 collectMonth 写入，同月幂等跳过）；等级阈值取自模型；
 * D 级/连 2 月 C → 整改 + 冻结新单；审核→公示→申诉→修正版全链状态机。
 */
@Slf4j
@Service
public class ScorecardServiceImpl implements ScorecardService {

    private static final DateTimeFormatter MT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter NO = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ScmScorecardModelDao modelDao;
    private final ScmScorecardResultDao resultDao;
    private final ScmScorecardRectifyDao rectifyDao;
    private final ScmScorecardAppealDao appealDao;
    private final ScorecardCollectDao collectDao;
    private final MetricGuard metricGuard;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Value("${app.scorecard.appeal-workdays:7}")
    private int appealWorkdays;
    @Value("${app.scorecard.rectify-days:60}")
    private int rectifyDays;
    @Value("${app.scorecard.frozen-c-consecutive:2}")
    private int frozenConsecutiveC;

    public ScorecardServiceImpl(ScmScorecardModelDao modelDao, ScmScorecardResultDao resultDao,
                                ScmScorecardRectifyDao rectifyDao, ScmScorecardAppealDao appealDao,
                                ScorecardCollectDao collectDao, MetricGuard metricGuard) {
        this.modelDao = modelDao;
        this.resultDao = resultDao;
        this.rectifyDao = rectifyDao;
        this.appealDao = appealDao;
        this.collectDao = collectDao;
        this.metricGuard = metricGuard;
    }

    // ================================================================ 模型（6.1）

    @Override
    public Map<String, Object> saveModel(Map<String, Object> p) {
        BigDecimal wq = dec(p.get("wQuality"), new BigDecimal("30"));
        BigDecimal wd = dec(p.get("wDelivery"), new BigDecimal("30"));
        BigDecimal wc = dec(p.get("wCost"), new BigDecimal("20"));
        BigDecimal wr = dec(p.get("wResponse"), new BigDecimal("20"));
        BigDecimal sum = wq.add(wd).add(wc).add(wr);
        if (sum.compareTo(BigDecimal.valueOf(100)) != 0) {
            // FR-4.9-7-1：权重合计 ≠ 100% → L1 硬阻断
            throw new ServiceException(422, "权重合计须为 100%，当前 " + sum + "%");
        }
        // 维度指标须已注册口径（联动 C-4.10-01）
        metricGuard.requireAll("SC_QUALITY_PASS", "SC_DELIVERY_OTD", "SC_COST_SCORE",
                "SC_RESPONSE_PO_48H", "SC_TOTAL");

        String name = str(p.get("name"));
        if (name == null) {
            throw new ServiceException(400, "模型名称必填");
        }
        boolean incompatible = p.get("version") != null;   // 显式传 version 视为变更 → 走版本
        ScmScorecardModel m = new ScmScorecardModel();
        if (p.get("id") != null) {
            ScmScorecardModel old = modelDao.selectById(String.valueOf(p.get("id")));
            if (old == null) {
                throw new ServiceException(404, "模型不存在");
            }
            if (incompatible) {
                // 变更须审批留版本（FR-4.9-7-1）
                old.setApprovalStatus("PENDING");
                old.setStatus("DRAFT");
                modelDao.updateById(old);
                m.setVersion((old.getVersion() == null ? 1 : old.getVersion()) + 1);
                m.setRemark("模型变更待审批（新版本，原版本保留）");
            } else {
                throw new ServiceException(422, "修改已登记模型须携带 version 走版本变更（留版本+审批）");
            }
        } else {
            m.setVersion(1);
        }
        m.setModelCode(str(p.get("modelCode")) == null
                ? "SCM" + LocalDateTime.now().format(NO) : str(p.get("modelCode")));
        m.setName(name);
        m.setCategoryCode(str(p.get("categoryCode")));
        m.setWQuality(wq);
        m.setWDelivery(wd);
        m.setWCost(wc);
        m.setWResponse(wr);
        m.setCostIndexWeight(dec(p.get("costIndexWeight"), new BigDecimal("60")));
        m.setThreshA(dec(p.get("threshA"), new BigDecimal("90")));
        m.setThreshB(dec(p.get("threshB"), new BigDecimal("75")));
        m.setThreshC(dec(p.get("threshC"), new BigDecimal("60")));
        m.setStatus(ScmScorecardModel.ST_ACTIVE);
        m.setApprovalStatus("APPROVED");   // 首版创建即生效（基线：无审批立即生效）；变更版为 PENDING
        m.setEffectiveAt(LocalDateTime.now());
        modelDao.insert(m);
        return Map.of("id", m.getId(), "modelCode", m.getModelCode(), "version", m.getVersion(),
                "weightSum", sum, "status", m.getStatus());
    }

    @Override
    public Page<ScmScorecardModel> modelPage(long current, long size, String status) {
        LambdaQueryWrapper<ScmScorecardModel> qw = new LambdaQueryWrapper<>();
        qw.eq(status != null && !status.isEmpty(), ScmScorecardModel::getStatus, status);
        qw.orderByDesc(ScmScorecardModel::getCreateDate);
        return modelDao.selectPage(new Page<>(current, size), qw);
    }

    // ================================================================ 采集与计算（6.2/6.3）

    @Override
    public Map<String, Object> collectMonth(String monthTag) {
        metricGuard.requireAll("SC_QUALITY_PASS", "SC_DELIVERY_OTD", "SC_DELIVERY_ASN_ACC",
                "SC_COST_SCORE", "SC_RESPONSE_PO_48H", "SC_RESPONSE_CPFR", "SC_TOTAL",
                "PRICE_COMP_INDEX");
        String month = monthTag == null || monthTag.trim().isEmpty()
                ? YearMonth.now().minusMonths(1).format(MT) : monthTag.trim();
        YearMonth ym = YearMonth.parse(month, MT);
        String start = ym.atDay(1).toString();
        String next = ym.plusMonths(1).atDay(1).toString();

        List<Map<String, Object>> suppliers = collectDao.activeSuppliers(start, next);
        Map<String, Map<String, Object>> quality = index(collectDao.quality(start, next));
        Map<String, Map<String, Object>> delivery = index(collectDao.delivery(start, next));
        Map<String, Map<String, Object>> asn = index(collectDao.asnAccuracy(start, next));
        Map<String, Map<String, Object>> po48 = index(collectDao.poConfirm(start, next));
        Map<String, Map<String, Object>> cpfr = index(collectDao.replenishTimely(start, next));
        List<Map<String, Object>> currBasis = collectDao.costBasis(month);
        List<Map<String, Object>> prevBasis = collectDao.costBasis(prevMonth(month));
        Map<String, Map<String, Object>> costRows = costIndex(currBasis, prevBasis, month);

        int created = 0;
        int skipped = 0;
        for (Map<String, Object> sup : suppliers) {
            String sid = str(sup.get("supplierId"));
            if (sid == null) {
                continue;
            }
            // 同月幂等（采集不可变：v1 已存在即跳过，无修改入口）
            if (!resultDao.selectSupplierMonth(month, sid).isEmpty()) {
                skipped++;
                continue;
            }
            ScmScorecardModel model = modelDao.selectEffective(null);
            if (model == null) {
                throw new ServiceException(422, "无生效记分卡模型，请先配置（FR-4.9-7-1）");
            }

            Map<String, Object> src = new LinkedHashMap<>();
            src.put("monthTag", month);

            // 质量维度
            BigDecimal qScore = null;
            Map<String, Object> q = quality.get(sid);
            if (q != null && num(q.get("totalLots")) > 0) {
                BigDecimal passRate = BigDecimal.valueOf(num(q.get("passLots")))
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(num(q.get("totalLots"))), 2, RoundingMode.HALF_UP);
                qScore = passRate;
                src.put("qualityLots", q.get("totalLots"));
                src.put("qualityPassRate", passRate);
            }

            // 交付维度 = 准时率×70% + ASN 准确率×30%（缺失子项按可用项计）
            BigDecimal dScore = null;
            Map<String, Object> d = delivery.get(sid);
            Map<String, Object> a = asn.get(sid);
            BigDecimal otd = null;
            BigDecimal asnAcc = null;
            if (d != null && num(d.get("totalLots")) > 0) {
                otd = BigDecimal.valueOf(num(d.get("onTimeLots")))
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(num(d.get("totalLots"))), 2, RoundingMode.HALF_UP);
            }
            if (a != null && num(a.get("totalLines")) > 0) {
                asnAcc = BigDecimal.valueOf(num(a.get("accLines")))
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(num(a.get("totalLines"))), 2, RoundingMode.HALF_UP);
            }
            if (otd != null && asnAcc != null) {
                dScore = otd.multiply(new BigDecimal("0.7")).add(asnAcc.multiply(new BigDecimal("0.3")))
                        .setScale(2, RoundingMode.HALF_UP);
            } else if (otd != null) {
                dScore = otd;
                src.put("missing", append(src.get("missing"), "SC_DELIVERY_ASN_ACC"));
            } else if (asnAcc != null) {
                dScore = asnAcc;
                src.put("missing", append(src.get("missing"), "SC_DELIVERY_OTD"));
            }
            src.put("deliveryOtd", otd);
            src.put("deliveryAsnAcc", asnAcc);

            // 成本维度 = 指数×idxW + 降本分×(100-idxW)（design D7）
            BigDecimal cScore = null;
            BigDecimal compIndex = null;
            Map<String, Object> cs = costRows.get(sid);
            if (cs != null) {
                compIndex = (BigDecimal) cs.get("index");
                BigDecimal idxW = model.getCostIndexWeight();
                BigDecimal saveScore = (BigDecimal) cs.get("saveScore");
                cScore = compIndex.multiply(idxW)
                        .add(saveScore.multiply(BigDecimal.valueOf(100).subtract(idxW)))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                src.put("compIndex", compIndex);
                src.put("saveScore", saveScore);
            } else {
                src.put("missing", append(src.get("missing"), "PRICE_COMP_INDEX"));
            }

            // 响应维度 = PO48h×50% + 补货及时×50%（缺失子项按可用项计）
            BigDecimal rScore = null;
            Map<String, Object> pc = po48.get(sid);
            Map<String, Object> rp = cpfr.get(sid);
            BigDecimal poRate = null;
            BigDecimal rpRate = null;
            if (pc != null && num(pc.get("totalPo")) > 0) {
                poRate = BigDecimal.valueOf(num(pc.get("confirmed48h")))
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(num(pc.get("totalPo"))), 2, RoundingMode.HALF_UP);
            }
            if (rp != null && num(rp.get("totalCnt")) > 0) {
                rpRate = BigDecimal.valueOf(num(rp.get("timelyCnt")))
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(num(rp.get("totalCnt"))), 2, RoundingMode.HALF_UP);
            }
            if (poRate != null && rpRate != null) {
                rScore = poRate.add(rpRate).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            } else if (poRate != null) {
                rScore = poRate;
                src.put("missing", append(src.get("missing"), "SC_RESPONSE_CPFR"));
            } else if (rpRate != null) {
                rScore = rpRate;
                src.put("missing", append(src.get("missing"), "SC_RESPONSE_PO_48H"));
            }
            src.put("po48hRate", poRate);
            src.put("cpfrRate", rpRate);

            // 数据状态：整维缺失 → INCOMPLETE 暂缓发布；其余 OK（子缺失记 MISSING_DIM）
            boolean qMissing = qScore == null;
            boolean dMissing = dScore == null;
            boolean cMissing = cScore == null;
            boolean rMissing = rScore == null;
            int missingDims = (qMissing ? 1 : 0) + (dMissing ? 1 : 0) + (cMissing ? 1 : 0)
                    + (rMissing ? 1 : 0);
            String dataStatus = ScmScorecardResult.DATA_OK;
            if (missingDims >= 3) {
                dataStatus = ScmScorecardResult.DATA_INCOMPLETE;
            } else if (missingDims >= 1) {
                dataStatus = ScmScorecardResult.DATA_MISSING;
            }

            // 总分与分级（除零/异常 → ABNORMAL 不输出等级）
            BigDecimal total = null;
            String grade = null;
            try {
                if (dataStatus.equals(ScmScorecardResult.DATA_INCOMPLETE)) {
                    // 暂缓：不算总分
                } else {
                    BigDecimal qq = qScore == null ? BigDecimal.ZERO : qScore;
                    BigDecimal dd = dScore == null ? BigDecimal.ZERO : dScore;
                    BigDecimal cc = cScore == null ? BigDecimal.ZERO : cScore;
                    BigDecimal rr = rScore == null ? BigDecimal.ZERO : rScore;
                    total = qq.multiply(model.getWQuality())
                            .add(dd.multiply(model.getWDelivery()))
                            .add(cc.multiply(model.getWCost()))
                            .add(rr.multiply(model.getWResponse()))
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    grade = gradeOf(total, model);
                }
            } catch (Exception e) {
                dataStatus = ScmScorecardResult.DATA_ABNORMAL;
                total = null;
                grade = null;
                src.put("calcError", e.getMessage());
            }

            ScmScorecardResult r = new ScmScorecardResult();
            r.setMonthTag(month);
            r.setSupplierId(sid);
            r.setModelId(model.getId());
            r.setModelVersion(model.getVersion());
            r.setQValue(qScore);
            r.setQScore(qScore);
            r.setDValue(dScore);
            r.setDScore(dScore);
            r.setCValue(compIndex);
            r.setCScore(cScore);
            r.setRValue(poRate);
            r.setRScore(rScore);
            r.setTotalScore(total);
            r.setGrade(grade);
            r.setDataStatus(dataStatus);
            r.setVersionTag("v1");
            r.setStatus(dataStatus.equals(ScmScorecardResult.DATA_INCOMPLETE)
                    ? ScmScorecardResult.ST_SUSPENDED : ScmScorecardResult.ST_GENERATED);
            try {
                r.setSourceJson(mapper.writeValueAsString(src));
            } catch (Exception e) {
                r.setSourceJson("{}");
            }
            r.setCalcAt(LocalDateTime.now());
            resultDao.insert(r);
            created++;

            // 整改与冻结触发（FR-4.9-7-3 / BR-4.9-05）
            triggerRectify(r, month);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("monthTag", month);
        out.put("created", created);
        out.put("skipped", skipped);
        out.put("suppliers", suppliers.size());
        return out;
    }

    /** D 级或连续 2 月 C → 整改行 + 冻结 */
    private void triggerRectify(ScmScorecardResult r, String month) {
        if (r.getGrade() == null) {
            return;
        }
        String trigger = null;
        if ("D".equals(r.getGrade())) {
            trigger = "GRADE_D";
        } else if ("C".equals(r.getGrade())) {
            ScmScorecardResult prev = resultDao.selectPrevMonth(r.getSupplierId(), month);
            if (prev != null && "C".equals(prev.getGrade())
                    && prev.getMonthTag().equals(prevMonth(month))
                    && countMonthsC(r.getSupplierId(), month) >= frozenConsecutiveC) {
                trigger = "C_TWO_MONTHS";
            }
        }
        if (trigger == null) {
            return;
        }
        // 已有 OPEN 整改不重复建
        if (rectifyDao.selectCount(new LambdaQueryWrapper<ScmScorecardRectify>()
                .eq(ScmScorecardRectify::getSupplierId, r.getSupplierId())
                .eq(ScmScorecardRectify::getStatus, ScmScorecardRectify.ST_OPEN)) > 0) {
            return;
        }
        ScmScorecardRectify rect = new ScmScorecardRectify();
        rect.setRectifyNo(nextNo(rectifyDao.selectMaxSeq("SR"), "SR"));
        rect.setResultId(r.getId());
        rect.setMonthTag(month);
        rect.setSupplierId(r.getSupplierId());
        rect.setTriggerType(trigger);
        rect.setStatus(ScmScorecardRectify.ST_OPEN);
        rect.setFrozen("GRADE_D".equals(trigger));   // D 级冻结新单；连续 C 仅进整改名单
        rect.setItemsJson("[\"限期整改交付质量问题\",\"提交纠正预防措施\"]");
        rect.setDueAt(LocalDateTime.now().plusDays(rectifyDays));
        rect.setRemark("BR-4.9-05 自动触发：" + trigger);
        rectifyDao.insert(rect);
        log.info("scorecard rectify triggered: supplier={} trigger={} frozen={}",
                r.getSupplierId(), trigger, rect.getFrozen());
    }

    private long countMonthsC(String supplierId, String monthTag) {
        long n = 0;
        String m = monthTag;
        for (int i = 0; i < 12; i++) {
            ScmScorecardResult r = resultDao.selectSupplierMonth(m, supplierId).stream()
                    .filter(x -> !"v1修正".equals(x.getVersionTag()) || true)
                    .findFirst().orElse(null);
            if (r == null || !"C".equals(r.getGrade())) {
                break;
            }
            n++;
            m = prevMonth(m);
        }
        return n;
    }

    private String gradeOf(BigDecimal total, ScmScorecardModel m) {
        if (total.compareTo(m.getThreshA()) >= 0) {
            return "A";
        }
        if (total.compareTo(m.getThreshB()) >= 0) {
            return "B";
        }
        if (total.compareTo(m.getThreshC()) >= 0) {
            return "C";
        }
        return "D";
    }

    /**
     * 价格竞争力指数（design D7）：品类内 100×(1−本均价/品类均价)，按采购量加权到供应商；
     * 降本分：环比均价下降→100，持平→50，上涨→0（口径见字典 COST_SAVE_MOM）。
     */
    private Map<String, Map<String, Object>> costIndex(List<Map<String, Object>> currBasis,
                                                       List<Map<String, Object>> prevBasis,
                                                       String monthTag) {
        // item 级：品类合计与供应商分布
        Map<String, BigDecimal> itemTotalAmt = new LinkedHashMap<>();    // cat|item -> amt
        Map<String, BigDecimal> itemTotalQty = new LinkedHashMap<>();    // cat|item -> qty
        Map<String, BigDecimal> supItemAmt = new LinkedHashMap<>();      // cat|item|sup -> amt
        Map<String, BigDecimal> supItemQty = new LinkedHashMap<>();      // cat|item|sup -> qty
        for (Map<String, Object> r : currBasis) {
            String cat = str(r.get("categoryCode"));
            String item = str(r.get("itemCode"));
            String sup = str(r.get("supplierId"));
            BigDecimal amt = bd(r.get("amt"));
            BigDecimal qty = bd(r.get("qty"));
            if (item == null || sup == null || qty.signum() <= 0) {
                continue;
            }
            String ck = (cat == null ? "-" : cat) + "|" + item;
            itemTotalAmt.merge(ck, amt, BigDecimal::add);
            itemTotalQty.merge(ck, qty, BigDecimal::add);
            supItemAmt.merge(ck + "|" + sup, amt, BigDecimal::add);
            supItemQty.merge(ck + "|" + sup, qty, BigDecimal::add);
        }
        Map<String, BigDecimal> supWeighted = new LinkedHashMap<>();   // sup -> Σ(index×qty)
        Map<String, BigDecimal> supQty = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : supItemAmt.entrySet()) {
            String[] parts = e.getKey().split("\\|", -1);
            String ck = parts[0] + "|" + parts[1];
            String sup = parts[2];
            BigDecimal totalAmt = itemTotalAmt.get(ck);
            BigDecimal totalQty = itemTotalQty.get(ck);
            BigDecimal supQtyV = supItemQty.get(e.getKey());
            if (totalQty == null || totalQty.signum() == 0 || totalAmt == null
                    || supQtyV == null || supQtyV.signum() == 0) {
                continue;
            }
            BigDecimal catAvgPrice = totalAmt.divide(totalQty, 4, RoundingMode.HALF_UP);
            if (catAvgPrice.signum() == 0) {
                continue;
            }
            BigDecimal supAvg = e.getValue().divide(supQtyV, 4, RoundingMode.HALF_UP);
            BigDecimal idx = BigDecimal.valueOf(100).multiply(
                    BigDecimal.ONE.subtract(supAvg.divide(catAvgPrice, 6, RoundingMode.HALF_UP)));
            if (idx.signum() < 0) {
                idx = BigDecimal.ZERO;
            }
            supWeighted.merge(sup, idx.multiply(supQtyV), BigDecimal::add);
            supQty.merge(sup, supQtyV, BigDecimal::add);
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : supWeighted.entrySet()) {
            BigDecimal qty = supQty.get(e.getKey());
            if (qty == null || qty.signum() == 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", e.getValue().divide(qty, 2, RoundingMode.HALF_UP));
            m.put("saveScore", saveScore(e.getKey(), currBasis, prevBasis));
            out.put(e.getKey(), m);
        }
        return out;
    }

    /** 降本分：供应商整体环比均价下降→100；持平→50；上涨→0；无对比数据→50 */
    private BigDecimal saveScore(String supplierId, List<Map<String, Object>> currBasis,
                                 List<Map<String, Object>> prevBasis) {
        BigDecimal cAmt = BigDecimal.ZERO;
        BigDecimal cQty = BigDecimal.ZERO;
        BigDecimal pAmt = BigDecimal.ZERO;
        BigDecimal pQty = BigDecimal.ZERO;
        for (Map<String, Object> r : currBasis) {
            if (supplierId.equals(str(r.get("supplierId")))) {
                cAmt = cAmt.add(bd(r.get("amt")));
                cQty = cQty.add(bd(r.get("qty")));
            }
        }
        for (Map<String, Object> r : prevBasis) {
            if (supplierId.equals(str(r.get("supplierId")))) {
                pAmt = pAmt.add(bd(r.get("amt")));
                pQty = pQty.add(bd(r.get("qty")));
            }
        }
        if (cQty.signum() == 0 || pQty.signum() == 0) {
            return new BigDecimal("50");
        }
        int cmp = cAmt.divide(cQty, 4, RoundingMode.HALF_UP)
                .compareTo(pAmt.divide(pQty, 4, RoundingMode.HALF_UP));
        return cmp < 0 ? BigDecimal.valueOf(100) : (cmp == 0 ? new BigDecimal("50") : BigDecimal.ZERO);
    }

    // ================================================================ 查询

    @Override
    public Page<ScmScorecardResult> resultPage(long current, long size, String monthTag, String grade,
                                               String status, String supplierId) {
        LambdaQueryWrapper<ScmScorecardResult> qw = new LambdaQueryWrapper<>();
        qw.eq(monthTag != null && !monthTag.isEmpty(), ScmScorecardResult::getMonthTag, monthTag);
        qw.eq(grade != null && !grade.isEmpty(), ScmScorecardResult::getGrade, grade);
        qw.eq(status != null && !status.isEmpty(), ScmScorecardResult::getStatus, status);
        qw.eq(supplierId != null && !supplierId.isEmpty(), ScmScorecardResult::getSupplierId,
                supplierId);
        qw.orderByDesc(ScmScorecardResult::getMonthTag).orderByAsc(ScmScorecardResult::getSupplierId);
        return resultDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String resultId) {
        ScmScorecardResult r = resultDao.selectById(resultId);
        if (r == null) {
            throw new ServiceException(404, "记分卡不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", r);
        out.put("source", parseQuietly(r.getSourceJson()));
        // 环比/同比趋势
        ScmScorecardResult prev = resultDao.selectPrevMonth(r.getSupplierId(), r.getMonthTag());
        out.put("prevTotal", prev == null ? null : prev.getTotalScore());
        out.put("prevGrade", prev == null ? null : prev.getGrade());
        // 申诉记录（内部页「申诉记录」弹窗数据源）
        ScmScorecardAppeal ap = appealDao.selectByResult(resultId);
        out.put("appeals", ap == null ? List.of() : List.of(ap));
        return out;
    }

    // ================================================================ 审核公示（6.5）

    @Override
    public Map<String, Object> review(String resultId, String opinion) {
        ScmScorecardResult r = requireResult(resultId);
        if (opinion == null || opinion.trim().length() < 2) {
            throw new ServiceException(400, "审核意见必填，不可留空");
        }
        if (ScmScorecardResult.ST_SUSPENDED.equals(r.getStatus())) {
            throw new ServiceException(422, "数据不完整暂缓发布，不得审核");
        }
        if (!ScmScorecardResult.ST_GENERATED.equals(r.getStatus())) {
            throw new ServiceException(422, "仅 GENERATED 状态可审核，当前 " + r.getStatus());
        }
        r.setStatus(ScmScorecardResult.ST_REVIEWED);
        r.setReviewBy(IntfGuard.currentUser());
        r.setReviewAt(LocalDateTime.now());
        r.setRemark("审核意见：" + opinion);
        resultDao.updateById(r);
        return Map.of("id", r.getId(), "status", r.getStatus());
    }

    @Override
    public Map<String, Object> publish(String resultId) {
        ScmScorecardResult r = requireResult(resultId);
        if (!ScmScorecardResult.ST_REVIEWED.equals(r.getStatus())) {
            // C-4.9-09 / C-0-01：未审核不得公示 + 留痕违规尝试
            log.warn("publish blocked (not reviewed): {} status={} by {}", r.getId(), r.getStatus(),
                    IntfGuard.currentUser());
            throw new ServiceException(422, "记分卡未审核，不可对外公示（C-4.9-09）");
        }
        r.setStatus(ScmScorecardResult.ST_PUBLISHED);
        r.setPublishAt(LocalDateTime.now());
        r.setAppealDeadline(LocalDateTime.now().plusDays(appealWorkdays * 2L / 2));   // 自然日近似 7 天
        resultDao.updateById(r);
        return Map.of("id", r.getId(), "status", r.getStatus(), "publishAt", r.getPublishAt(),
                "appealDeadline", r.getAppealDeadline());
    }

    // ================================================================ 申诉（6.6）

    @Override
    public Map<String, Object> appeal(Map<String, Object> p) {
        String resultId = str(p.get("resultId"));
        String reason = str(p.get("reason"));
        if (resultId == null || reason == null) {
            throw new ServiceException(400, "resultId 与 reason 必填");
        }
        ScmScorecardResult r = requireResult(resultId);
        if (!ScmScorecardResult.ST_PUBLISHED.equals(r.getStatus())) {
            throw new ServiceException(422, "仅公示中的记分卡可申诉");
        }
        if (r.getPublishAt() != null && LocalDate.now()
                .isAfter(r.getPublishAt().toLocalDate().plusDays(appealWorkdays * 2L))) {
            throw new ServiceException(422, "申诉期已过（公示后 " + appealWorkdays + " 个工作日内）");
        }
        if (appealDao.selectByResult(resultId) != null) {
            throw new ServiceException(422, "该记分卡已有申诉记录");
        }
        ScmScorecardAppeal a = new ScmScorecardAppeal();
        a.setAppealNo(nextNo(appealDao.selectMaxSeq("SA"), "SA"));
        a.setResultId(resultId);
        a.setSupplierId(r.getSupplierId());
        a.setMonthTag(r.getMonthTag());
        a.setReason(reason);
        a.setEvidence(str(p.get("evidence")));
        a.setStatus(ScmScorecardAppeal.ST_SUBMITTED);
        appealDao.insert(a);
        return Map.of("id", a.getId(), "appealNo", a.getAppealNo(), "status", a.getStatus());
    }

    @Override
    public Map<String, Object> reviewAppeal(String appealId, boolean confirmed, String conclusion) {
        ScmScorecardAppeal a = appealDao.selectById(appealId);
        if (a == null) {
            throw new ServiceException(404, "申诉不存在");
        }
        if (!ScmScorecardAppeal.ST_SUBMITTED.equals(a.getStatus())) {
            throw new ServiceException(422, "申诉已处理");
        }
        if (conclusion == null || conclusion.trim().length() < 2) {
            throw new ServiceException(400, "复核结论必填");
        }
        a.setStatus(confirmed ? ScmScorecardAppeal.ST_CONFIRMED : ScmScorecardAppeal.ST_REJECTED);
        a.setReviewer(IntfGuard.currentUser());
        a.setReviewAt(LocalDateTime.now());
        a.setConclusion(conclusion);
        if (confirmed) {
            // 修正版：新版本行 + 标注修正版，原版保留（BR-4.9-07）
            ScmScorecardResult orig = requireResult(a.getResultId());
            List<ScmScorecardResult> existing = resultDao.selectSupplierMonth(orig.getMonthTag(),
                    orig.getSupplierId());
            String nextTag = "v" + (existing.size() + 1) + "修正版";
            ScmScorecardResult fixed = new ScmScorecardResult();
            fixed.setMonthTag(orig.getMonthTag());
            fixed.setSupplierId(orig.getSupplierId());
            fixed.setModelId(orig.getModelId());
            fixed.setModelVersion(orig.getModelVersion());
            fixed.setQValue(orig.getQValue());
            fixed.setQScore(orig.getQScore());
            fixed.setDValue(orig.getDValue());
            fixed.setDScore(orig.getDScore());
            fixed.setCValue(orig.getCValue());
            fixed.setCScore(orig.getCScore());
            fixed.setRValue(orig.getRValue());
            fixed.setRScore(orig.getRScore());
            fixed.setTotalScore(orig.getTotalScore());
            fixed.setGrade(orig.getGrade());
            fixed.setDataStatus(orig.getDataStatus());
            fixed.setVersionTag(nextTag);
            fixed.setOriginId(orig.getId());
            fixed.setStatus(ScmScorecardResult.ST_PUBLISHED);   // 修正版重新公示
            fixed.setSourceJson(orig.getSourceJson());
            fixed.setCalcAt(LocalDateTime.now());
            fixed.setReviewBy(orig.getReviewBy());
            fixed.setReviewAt(orig.getReviewAt());
            fixed.setPublishAt(LocalDateTime.now());
            fixed.setAppealDeadline(orig.getAppealDeadline());
            fixed.setRemark("申诉成立修正版（原版保留备查）：" + conclusion);
            resultDao.insert(fixed);
            // 原版标注已被修正（不删除，保留备查）
            orig.setRemark((orig.getRemark() == null ? "" : orig.getRemark() + "；")
                    + "已由申诉修正版 " + nextTag + " 替代");
            resultDao.updateById(orig);
            a.setNewResultId(fixed.getId());
        }
        appealDao.updateById(a);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.getId());
        out.put("status", a.getStatus());
        out.put("newResultId", a.getNewResultId());
        return out;
    }

    // ================================================================ 整改与冻结（6.4）

    @Override
    public Page<ScmScorecardRectify> rectifyPage(long current, long size, String status) {
        LambdaQueryWrapper<ScmScorecardRectify> qw = new LambdaQueryWrapper<>();
        qw.eq(status != null && !status.isEmpty(), ScmScorecardRectify::getStatus, status);
        qw.orderByDesc(ScmScorecardRectify::getCreateDate);
        return rectifyDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> closeRectify(String rectifyId, String note) {
        ScmScorecardRectify rect = requireRectify(rectifyId);
        if (!ScmScorecardRectify.ST_OPEN.equals(rect.getStatus())) {
            throw new ServiceException(422, "整改单已关闭");
        }
        rect.setStatus(ScmScorecardRectify.ST_CLOSED);
        rect.setCloseBy(IntfGuard.currentUser());
        rect.setCloseAt(LocalDateTime.now());
        rect.setRemark((rect.getRemark() == null ? "" : rect.getRemark() + "；") + "整改关闭：" + note);
        rectifyDao.updateById(rect);
        return Map.of("id", rect.getId(), "status", rect.getStatus(), "frozen",
                Boolean.TRUE.equals(rect.getFrozen()), "hint",
                Boolean.TRUE.equals(rect.getFrozen()) ? "已关闭，仍需审批解冻" : "已关闭");
    }

    @Override
    public Map<String, Object> unfreeze(String rectifyId) {
        ScmScorecardRectify rect = requireRectify(rectifyId);
        if (!Boolean.TRUE.equals(rect.getFrozen())) {
            throw new ServiceException(422, "该整改单未冻结");
        }
        if (!ScmScorecardRectify.ST_CLOSED.equals(rect.getStatus())) {
            throw new ServiceException(422, "须先关闭整改项方可解冻");
        }
        String approver = IntfGuard.currentUser();
        if (approver != null && approver.equals(rect.getCloseBy())) {
            // C-0-03：解冻审批人 ≠ 整改关闭人（双人）
            throw new ServiceException(422, "解冻审批人不得与整改关闭人相同（双人复核）");
        }
        rect.setFrozen(false);
        rect.setUnfreezeApproval(approver);
        rect.setUnfreezeAt(LocalDateTime.now());
        rectifyDao.updateById(rect);
        return Map.of("id", rect.getId(), "frozen", false, "unfreezeApproval", approver);
    }

    @Override
    public void assertNotFrozen(String supplierId) {
        if (supplierId == null) {
            return;
        }
        if (rectifyDao.countFrozen(supplierId) > 0) {
            ScmScorecardRectify rect = rectifyDao.selectFrozenOpen(supplierId);
            throw new ServiceException(422, "供应商 " + supplierId + " 因记分卡 D 级被冻结（BR-4.9-05），"
                    + "禁止创建新采购订单；整改单 " + (rect == null ? "" : rect.getRectifyNo())
                    + " 关闭并解冻后恢复");
        }
    }

    // ================================================================ 门户（C-4.9-06）

    @Override
    public List<ScmScorecardResult> portalList(String supplierId) {
        if (supplierId == null) {
            throw new ServiceException(403, "门户上下文缺失供应商");
        }
        return resultDao.selectList(new LambdaQueryWrapper<ScmScorecardResult>()
                .eq(ScmScorecardResult::getSupplierId, supplierId)
                .eq(ScmScorecardResult::getStatus, ScmScorecardResult.ST_PUBLISHED)
                .orderByDesc(ScmScorecardResult::getMonthTag));
    }

    @Override
    public Map<String, Object> portalDetail(String resultId, String supplierId) {
        ScmScorecardResult r = requireResult(resultId);
        if (supplierId == null || !supplierId.equals(r.getSupplierId())) {
            // 跨伙伴可见性一律拒绝（C-4.9-06 / C-4.9-07）
            throw new ServiceException(404, "记分卡不存在");
        }
        if (!ScmScorecardResult.ST_PUBLISHED.equals(r.getStatus())) {
            throw new ServiceException(404, "记分卡不存在（未公示）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", r);
        out.put("source", parseQuietly(r.getSourceJson()));
        out.put("appealable", true);
        return out;
    }

    // ================================================================ helpers

    private ScmScorecardResult requireResult(String id) {
        ScmScorecardResult r = resultDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "记分卡不存在");
        }
        return r;
    }

    private ScmScorecardRectify requireRectify(String id) {
        ScmScorecardRectify rect = rectifyDao.selectById(id);
        if (rect == null) {
            throw new ServiceException(404, "整改单不存在");
        }
        return rect;
    }

    private Map<String, Map<String, Object>> index(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String sid = str(r.get("supplierId"));
            if (sid != null) {
                out.put(sid, r);
            }
        }
        return out;
    }

    private String nextNo(Integer max, String prefix) {
        return prefix + LocalDateTime.now().format(NO)
                + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private static String prevMonth(String monthTag) {
        return YearMonth.parse(monthTag, MT).minusMonths(1).format(MT);
    }

    private static BigDecimal dec(Object v, BigDecimal def) {
        if (v == null) {
            return def;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static BigDecimal bd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static double num(Object v) {
        if (v == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Object append(Object arr, String v) {
        List<String> list = arr instanceof List ? (List<String>) arr : new ArrayList<>();
        list.add(v);
        return list;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private Map<String, Object> parseQuietly(String json) {
        if (json == null) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
