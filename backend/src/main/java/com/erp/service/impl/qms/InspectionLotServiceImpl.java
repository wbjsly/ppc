package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.qms.ExemptDao;
import com.erp.dao.qms.InspectionLotDao;
import com.erp.dao.qms.LotItemDao;
import com.erp.dao.qms.ScarDao;
import com.erp.dao.qms.StrictnessDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.qms.Exempt;
import com.erp.entity.qms.InspectionLot;
import com.erp.entity.qms.LotItem;
import com.erp.entity.qms.Scar;
import com.erp.entity.qms.StandardVersion;
import com.erp.entity.qms.Strictness;
import com.erp.service.qms.InspectionLotService;
import com.erp.service.qms.InspectionStandardService;
import com.erp.util.AqlTables;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 检验批实现（spec inspection-lot / design D2/D3）。
 * 取严：sample = Max(风险等级比例, AQL 查表)，A 类强制全检；批量不足方案样本量时转全检并留痕（BR-4.12-13）。
 */
@Slf4j
@Service
public class InspectionLotServiceImpl implements InspectionLotService {

    private static final String ST_BLOCKED = "BLOCKED";
    private static final String ST_PENDING = "PENDING";
    private static final String ST_SKIPPED = "SKIPPED";

    @Value("${app.qms.sample-rate-a:1.0}")
    private double rateA;
    @Value("${app.qms.sample-rate-b:0.5}")
    private double rateB;
    @Value("${app.qms.sample-rate-c:0.2}")
    private double rateC;
    @Value("${app.proc.qc-hours-a:24}")
    private int hoursA;
    @Value("${app.proc.qc-hours-b:48}")
    private int hoursB;
    @Value("${app.proc.qc-hours-c:72}")
    private int hoursC;

    private final InspectionLotDao lotDao;
    private final LotItemDao lotItemDao;
    private final ExemptDao exemptDao;
    private final StrictnessDao strictnessDao;
    private final ScarDao scarDao;
    private final GoodsReceiptLineDao lineDao;
    private final MdmItemDao itemDao;
    private final InspectionStandardService standardService;
    private final com.erp.dao.qms.SuspectLotDao suspectLotDao;
    private final com.erp.dao.qms.NcrDao ncrDao;
    private final com.erp.dao.qms.GaugeDao gaugeDao;
    private final com.erp.dao.qms.PatrolPlanDao patrolPlanDao;
    private final com.erp.service.qms.NcrService ncrService;

    public InspectionLotServiceImpl(InspectionLotDao lotDao,
                                    LotItemDao lotItemDao,
                                    ExemptDao exemptDao,
                                    StrictnessDao strictnessDao,
                                    ScarDao scarDao,
                                    GoodsReceiptLineDao lineDao,
                                    MdmItemDao itemDao,
                                    InspectionStandardService standardService,
                                    com.erp.dao.qms.SuspectLotDao suspectLotDao,
                                    com.erp.dao.qms.NcrDao ncrDao,
                                    com.erp.dao.qms.GaugeDao gaugeDao,
                                    com.erp.dao.qms.PatrolPlanDao patrolPlanDao,
                                    @Lazy com.erp.service.qms.NcrService ncrService) {
        this.lotDao = lotDao;
        this.lotItemDao = lotItemDao;
        this.exemptDao = exemptDao;
        this.strictnessDao = strictnessDao;
        this.scarDao = scarDao;
        this.lineDao = lineDao;
        this.itemDao = itemDao;
        this.standardService = standardService;
        this.suspectLotDao = suspectLotDao;
        this.ncrDao = ncrDao;
        this.gaugeDao = gaugeDao;
        this.patrolPlanDao = patrolPlanDao;
        this.ncrService = ncrService;
    }

    // ================= 生成（GR 登记同事务） =================

    @Override
    @Transactional
    public List<InspectionLot> generateForGr(GoodsReceipt gr, List<GoodsReceiptLine> lines) {
        List<InspectionLot> out = new ArrayList<>();
        for (GoodsReceiptLine line : lines) {
            InspectionLot lot = new InspectionLot();
            lot.setLotNo(nextLotNo());
            lot.setLotType("IQC");
            lot.setSourceType("GR");
            lot.setGrId(gr.getId());
            lot.setGrLineId(line.getId());
            lot.setItemCode(line.getItemCode());
            lot.setItemName(line.getItemName());
            lot.setSupplierId(gr.getSupplierId());
            lot.setSupplierName(gr.getSupplierName());
            lot.setBatchNo(gr.getBatchNo());
            lot.setLotQty(line.getReceivedQty() == null ? BigDecimal.ZERO : line.getReceivedQty());
            lot.setRiskGrade(riskGradeOf(line.getItemCode()));
            // D1：检验时限自 GR 登记提交起算
            lot.setDueTime(dueTimeOf(gr, lot.getRiskGrade()));
            lot.setStatus(ST_PENDING);
            lot.setResult("PENDING");
            lot.setStrictness("NORMAL");
            lot.setPriority("NORMAL");

            // 1) 免检命中（BR-4.12-12）→ SKIPPED，直接可过账
            Map<String, Object> exempt = hitExempt(line.getItemCode(), gr.getSupplierId());
            if (!exempt.isEmpty()) {
                lot.setStatus(ST_SKIPPED);
                lot.setExemptFlag("1");
                lot.setExemptId(String.valueOf(exempt.get("id")));
                lot.setSampleBasis("免检命中（免检单 " + exempt.get("id") + "），跳过检验");
                lotDao.insert(lot);
                line.setQcStatus("SKIPPED");
                lineDao.updateById(line);
                out.add(lot);
                continue;
            }

            // 2) 标准命中 → 快照 + 抽样取严；无标准 → BLOCKED 占位（BR-4.12-07）
            Map<String, Object> hit = standardService.resolveFor(line.getItemCode(), null,
                    gr.getSupplierId(), null, null);
            if (hit.isEmpty()) {
                lot.setStatus(ST_BLOCKED);
                lot.setSampleBasis("无生效检验标准（BR-4.12-07）：请先维护检验标准后激活");
                lotDao.insert(lot);
                line.setQcStatus("PENDING");
                lineDao.updateById(line);
                out.add(lot);
                continue;
            }
            fillFromStandard(lot, hit);
            lotDao.insert(lot);
            snapshotItems(lot, (List<Map<String, Object>>) hit.get("characteristics"));
            line.setQcStatus("PENDING");
            lineDao.updateById(line);
            out.add(lot);
        }
        return out;
    }

    /** 标准命中 → 固化快照与抽样方案（BR-4.12-56 / BL-4.12-02） */
    private void fillFromStandard(InspectionLot lot, Map<String, Object> hit) {
        StandardVersion v = (StandardVersion) hit.get("version");
        com.erp.entity.qms.InspectionStandard s =
                (com.erp.entity.qms.InspectionStandard) hit.get("standard");
        lot.setStandardId(v.getStandardId());
        lot.setStandardCode(s == null ? null : s.getStandardCode());
        lot.setStandardVersion(v.getVersionNo());
        lot.setApplicabilityBasis(String.valueOf(hit.get("applicabilityBasis")));
        lot.setInspectionLevel(v.getInspectionLevel());
        lot.setAqlLevel(v.getAqlLevel());

        String strictness = currentStrictness(lot.getItemCode(), lot.getSupplierId());
        lot.setStrictness(strictness);

        long qty = lot.getLotQty() == null ? 0L : lot.getLotQty().longValue();
        double baseRate = rateFor(lot.getRiskGrade());
        double rate = baseRate;
        if ("TIGHTENED".equals(strictness)) {
            rate = tighten(rate); // SCAR / 连 2 批不合格 → 上调一档
        }

        int sampleA;
        String riskPart;
        if ("A".equals(lot.getRiskGrade())) {
            sampleA = (int) qty; // BR-4.12-09 A 类强制全检，禁止抽检
            riskPart = "风险等级 A → 100% 全检（禁抽检）";
        } else {
            sampleA = (int) Math.ceil(qty * rate);
            riskPart = "风险比例 " + rate + " → " + sampleA;
        }
        int sampleB = AqlTables.sampleSizeByLot(qty);
        String code = AqlTables.codeLetter(qty);
        int sample = Math.max(sampleA, sampleB);
        boolean full = false;
        if (sample > qty) { // 批量不足方案样本量 → 转全检（BR-4.12-13 默认出口）
            sample = (int) qty;
            full = true;
        }
        double aql = AqlTables.parseAql(v.getAqlLevel());
        lot.setSampleQty(BigDecimal.valueOf(sample));
        lot.setAcValue(AqlTables.acceptNumber(sample, aql));
        lot.setReValue(AqlTables.rejectNumber(sample, aql));
        lot.setSampleBasis(riskPart + "；AQL " + aql + " 查表 字码 " + code + " → " + sampleB
                + "；取严 → " + sample + (full ? "（批量不足，转全检）" : "")
                + "；严格度 " + strictness);
    }

    private void snapshotItems(InspectionLot lot, List<Map<String, Object>> chars) {
        if (chars == null) {
            return;
        }
        int seq = 1;
        for (Map<String, Object> c : chars) {
            LotItem li = new LotItem();
            li.setLotId(lot.getId());
            li.setSequenceNo(c.get("sequenceNo") == null ? seq
                    : Integer.parseInt(String.valueOf(c.get("sequenceNo"))));
            li.setCharacteristicName(str(c.get("characteristicName")));
            li.setCtqFlag(fallback(str(c.get("ctqFlag")), "0"));
            li.setRegulatoryFlag(fallback(str(c.get("regulatoryFlag")), "0"));
            li.setSpecType(fallback(str(c.get("specType")), "NUMERIC"));
            li.setLowerLimit(dec(c.get("lowerLimit")));
            li.setUpperLimit(dec(c.get("upperLimit")));
            li.setTargetValue(dec(c.get("targetValue")));
            li.setUnit(str(c.get("unit")));
            li.setMethodName(str(c.get("methodName")));
            li.setInstrumentType(str(c.get("instrumentType")));
            li.setJudge("PENDING");
            lotItemDao.insert(li);
            seq++;
        }
    }

    // ================= BLOCKED 激活 =================

    @Override
    @Transactional
    public InspectionLot activate(String lotId) {
        InspectionLot lot = requireLot(lotId);
        if (!ST_BLOCKED.equals(lot.getStatus())) {
            throw new ServiceException(422, "仅无标准阻断（BLOCKED）批次可激活");
        }
        return activateLot(lot, null);
    }

    @Override
    @Transactional
    public int activateForStandard(String standardId) {
        List<InspectionLot> blocked = lotDao.selectList(new LambdaQueryWrapper<InspectionLot>()
                .eq(InspectionLot::getStatus, ST_BLOCKED)
                .last("LIMIT 500"));
        int n = 0;
        for (InspectionLot lot : blocked) {
            try {
                if (activateLot(lot, standardId) != null) {
                    n++;
                }
            } catch (Exception e) {
                log.debug("skip activate lot {}: {}", lot.getLotNo(), e.getMessage());
            }
        }
        return n;
    }

    private InspectionLot activateLot(InspectionLot lot, String onlyStandardId) {
        Map<String, Object> hit = standardService.resolveFor(lot.getItemCode(), null,
                lot.getSupplierId(), null, null);
        if (hit.isEmpty()) {
            if (onlyStandardId != null) {
                return null; // 该批次未命中此标准
            }
            throw new ServiceException(422, "请先维护检验标准");
        }
        StandardVersion v = (StandardVersion) hit.get("version");
        if (onlyStandardId != null && !onlyStandardId.equals(v.getStandardId())) {
            return null;
        }
        fillFromStandard(lot, hit);
        lot.setStatus(ST_PENDING);
        if (lotDao.updateById(lot) == 0) {
            throw new ServiceException(422, "批次状态更新冲突，请刷新重试");
        }
        snapshotItems(lot, (List<Map<String, Object>>) hit.get("characteristics"));
        // 同步行状态
        if (lot.getGrLineId() != null) {
            GoodsReceiptLine line = lineDao.selectById(lot.getGrLineId());
            if (line != null) {
                line.setQcStatus("PENDING");
                lineDao.updateById(line);
            }
        }
        log.info("BLOCKED lot {} activated -> {} (standard {} V{})", lot.getLotNo(), ST_PENDING,
                lot.getStandardCode(), lot.getStandardVersion());
        return lot;
    }

    // ================= 免检（BR-4.12-12） =================

    @Override
    public Map<String, Object> hitExempt(String materialCode, String supplierId) {
        if (materialCode == null) {
            return Map.of();
        }
        List<Exempt> rows = exemptDao.selectList(new LambdaQueryWrapper<Exempt>()
                .eq(Exempt::getMaterialCode, materialCode)
                .eq(Exempt::getStatus, "ACTIVE"));
        for (Exempt e : rows) {
            boolean supplierMatch = !hasText(e.getSupplierId()) || e.getSupplierId().equals(supplierId);
            if (supplierMatch) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", e.getId());
                out.put("status", e.getStatus());
                out.put("supplierId", e.getSupplierId());
                return out;
            }
        }
        return Map.of();
    }

    @Override
    @Transactional
    public void disableExempt(String materialCode, String supplierId, String reason) {
        LambdaQueryWrapper<Exempt> qw = new LambdaQueryWrapper<Exempt>()
                .eq(Exempt::getMaterialCode, materialCode)
                .eq(Exempt::getStatus, "ACTIVE");
        if (hasText(supplierId)) {
            qw.and(w -> w.isNull(Exempt::getSupplierId).or().eq(Exempt::getSupplierId, supplierId));
        }
        for (Exempt e : exemptDao.selectList(qw)) {
            e.setStatus("DISABLED");
            e.setDisableReason(reason);
            e.setDisabledDate(LocalDateTime.now());
            exemptDao.updateById(e);
            log.info("exempt auto disabled: {} ({})", materialCode, reason);
        }
    }

    // ================= 严格度（BR-4.12-02 / C-4.12-10） =================

    @Override
    public String currentStrictness(String materialCode, String supplierId) {
        // SCAR 发出期间强制加严
        if (hasText(supplierId)) {
            long activeScar = scarDao.selectCount(new LambdaQueryWrapper<Scar>()
                    .eq(Scar::getSupplierId, supplierId)
                    .in(Scar::getStatus, Arrays.asList("SENT", "REPLYING", "VERIFYING")));
            if (activeScar > 0) {
                return "TIGHTENED";
            }
        }
        Strictness s = activeStrictness(materialCode, supplierId);
        return s == null ? "NORMAL" : s.getStrictness();
    }

    @Override
    @Transactional
    public void onLotJudged(InspectionLot lot, String result) {
        String material = lot.getItemCode();
        String supplier = lot.getSupplierId();
        if ("FAIL".equals(result)) {
            // 连续 2 批不合格 → 转加严（已有活动加严则保持并复位连续合格计数）
            int consecutiveFails = countConsecutiveFails(material, supplier, lot.getId());
            Strictness active = activeStrictness(material, supplier);
            if (consecutiveFails >= 2) {
                if (active == null) {
                    Strictness s = new Strictness();
                    s.setMaterialCode(material);
                    s.setSupplierId(supplier);
                    s.setStrictness("TIGHTENED");
                    s.setTriggerType("2_FAIL");
                    s.setTriggerRef(lot.getId());
                    s.setStatus("ACTIVE");
                    s.setActiveDate(LocalDateTime.now());
                    s.setConsecPass(0);
                    strictnessDao.insert(s);
                    log.info("strictness -> TIGHTENED ({} / {}): 2 consecutive fails", material, supplier);
                } else {
                    active.setConsecPass(0);
                    strictnessDao.updateById(active);
                }
            } else if (active != null) {
                active.setConsecPass(0);
                strictnessDao.updateById(active);
            }
            return;
        }
        // PASS：加严下连续 3 批合格 → 解除
        Strictness active = activeStrictness(material, supplier);
        if (active != null && "TIGHTENED".equals(active.getStrictness())) {
            int pass = (active.getConsecPass() == null ? 0 : active.getConsecPass()) + 1;
            active.setConsecPass(pass);
            if (pass >= 3) {
                active.setStatus("RELEASED");
                active.setReleaseDate(LocalDateTime.now());
                active.setReleaseBasis("连续 3 批合格：" + lot.getLotNo());
                log.info("strictness released ({} / {}): 3 consecutive passes", material, supplier);
            }
            strictnessDao.updateById(active);
        }
    }

    private Strictness activeStrictness(String materialCode, String supplierId) {
        LambdaQueryWrapper<Strictness> qw = new LambdaQueryWrapper<Strictness>()
                .eq(Strictness::getMaterialCode, materialCode)
                .eq(Strictness::getStatus, "ACTIVE")
                .orderByDesc(Strictness::getActiveDate)
                .last("LIMIT 1");
        if (hasText(supplierId)) {
            qw.eq(Strictness::getSupplierId, supplierId);
        } else {
            qw.isNull(Strictness::getSupplierId);
        }
        return strictnessDao.selectOne(qw);
    }

    /** 以最近一次判定为端点回数连续不合格批次（含本批） */
    private int countConsecutiveFails(String materialCode, String supplierId, String currentLotId) {
        LambdaQueryWrapper<InspectionLot> qw = new LambdaQueryWrapper<InspectionLot>()
                .eq(InspectionLot::getItemCode, materialCode)
                .in(InspectionLot::getResult, Arrays.asList("PASS", "FAIL"))
                .ne(InspectionLot::getId, currentLotId)
                .orderByDesc(InspectionLot::getUpdateDate)
                .last("LIMIT 5");
        if (hasText(supplierId)) {
            qw.eq(InspectionLot::getSupplierId, supplierId);
        }
        int n = 1; // 本批 FAIL
        for (InspectionLot l : lotDao.selectList(qw)) {
            if ("FAIL".equals(l.getResult())) {
                n++;
            } else {
                break;
            }
        }
        return n;
    }

    // ================= 复检批（tasks 6.3） =================

    @Override
    @Transactional
    public InspectionLot createRecheck(com.erp.entity.qms.Ncr ncr) {
        // 幂等：同 NCR 已有未放行复检批 → 返回既有
        InspectionLot existed = lotDao.selectOne(new LambdaQueryWrapper<InspectionLot>()
                .eq(InspectionLot::getRefType, "NCR")
                .eq(InspectionLot::getRefId, ncr.getId())
                .eq(InspectionLot::getSourceType, "RECHECK")
                .notIn(InspectionLot::getStatus, "RELEASED", "CANCELLED")
                .orderByDesc(InspectionLot::getCreateDate)
                .last("LIMIT 1"));
        if (existed != null) {
            return existed;
        }
        InspectionLot lot = new InspectionLot();
        lot.setLotNo(nextLotNo());
        lot.setLotType("IQC");
        lot.setSourceType("RECHECK");
        lot.setRefType("NCR");
        lot.setRefId(ncr.getId());
        lot.setGrId(ncr.getGrId());
        lot.setGrLineId(ncr.getGrLineId());
        lot.setItemCode(ncr.getItemCode());
        lot.setItemName(ncr.getItemName());
        lot.setSupplierId(ncr.getSupplierId());
        lot.setSupplierName(ncr.getSupplierName());
        lot.setBatchNo(ncr.getBatchNo());
        lot.setLotQty(ncr.getQty());
        lot.setRiskGrade(riskGradeOf(ncr.getItemCode()));
        lot.setDueTime(LocalDateTime.now().plusHours(hoursOf(lot.getRiskGrade())));
        lot.setStatus(ST_PENDING);
        lot.setResult("PENDING");
        lot.setStrictness("NORMAL");
        lot.setPriority("HIGH");
        lot.setSampleBasis("NCR " + ncr.getNcrNo() + " 复检（挑选/返工后全数复验，不豁免免检）");

        Map<String, Object> hit = standardService.resolveFor(ncr.getItemCode(), null,
                ncr.getSupplierId(), null, null);
        if (hit.isEmpty()) {
            lot.setStatus(ST_BLOCKED);
            lot.setSampleBasis("NCR 复检：无生效检验标准（BR-4.12-07），请先维护标准后激活");
            lotDao.insert(lot);
            return lot;
        }
        fillFromStandard(lot, hit);
        lotDao.insert(lot);
        snapshotItems(lot, (List<Map<String, Object>>) hit.get("characteristics"));
        log.info("recheck lot {} created for NCR {}", lot.getLotNo(), ncr.getNcrNo());
        return lot;
    }

    // ================= IPQC / OQC 手工建批（偏差 D5） =================

    @Override
    @Transactional
    public InspectionLot createManual(Map<String, Object> body) {
        String lotType = str(body.get("lotType"));
        if (!"IPQC".equals(lotType) && !"OQC".equals(lotType)) {
            throw new ServiceException(422, "手工建批仅支持 IPQC / OQC");
        }
        String itemCode = str(body.get("itemCode"));
        if (!hasText(itemCode)) {
            throw new ServiceException(422, "物料编码必填");
        }
        BigDecimal qty = dec(body.get("qty"));
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "数量必须大于 0");
        }
        InspectionLot lot = new InspectionLot();
        lot.setLotNo(nextLotNo());
        lot.setLotType(lotType);
        lot.setSourceType(hasText(str(body.get("refType"))) ? "PLAN" : "MANUAL");
        lot.setRefType(str(body.get("refType")));
        lot.setRefId(str(body.get("refId")));
        lot.setItemCode(itemCode);
        lot.setItemName(str(body.get("itemName")));
        lot.setSupplierId(str(body.get("supplierId")));
        lot.setSupplierName(str(body.get("supplierName")));
        lot.setBatchNo(str(body.get("batchNo")));
        lot.setLotQty(qty);
        lot.setRiskGrade(riskGradeOf(itemCode));
        lot.setDueTime(LocalDateTime.now().plusHours(hoursOf(lot.getRiskGrade())));
        lot.setStatus(ST_PENDING);
        lot.setResult("PENDING");
        lot.setStrictness("NORMAL");
        lot.setPriority(hasText(str(body.get("priority"))) ? str(body.get("priority")) : "NORMAL");

        if (!hitExempt(itemCode, lot.getSupplierId()).isEmpty()) {
            throw new ServiceException(422, "该物料/供方为免检，无需创建检验批");
        }
        Map<String, Object> hit = standardService.resolveFor(itemCode, null,
                lot.getSupplierId(), null, str(body.get("processId")));
        if (hit.isEmpty()) {
            lot.setStatus(ST_BLOCKED);
            lot.setSampleBasis("无生效检验标准（BR-4.12-07）：请先维护检验标准后激活");
            lotDao.insert(lot);
            return lot;
        }
        fillFromStandard(lot, hit);
        lotDao.insert(lot);
        snapshotItems(lot, (List<Map<String, Object>>) hit.get("characteristics"));
        log.info("manual lot created: {} type={} item={}", lot.getLotNo(), lotType, itemCode);
        return lot;
    }

    @Override
    public List<Strictness> strictnessList(String materialCode, String supplierId) {
        LambdaQueryWrapper<Strictness> qw = new LambdaQueryWrapper<Strictness>()
                .eq(hasText(materialCode), Strictness::getMaterialCode, materialCode)
                .eq(hasText(supplierId), Strictness::getSupplierId, supplierId)
                .orderByDesc(Strictness::getActiveDate);
        return strictnessDao.selectList(qw);
    }

    // ================= IPQC 巡检计划（5.10） =================

    @Override
    public List<com.erp.entity.qms.PatrolPlan> patrolPlans() {
        return patrolPlanDao.selectList(new LambdaQueryWrapper<com.erp.entity.qms.PatrolPlan>()
                .orderByAsc(com.erp.entity.qms.PatrolPlan::getNextRunTime));
    }

    @Override
    @Transactional
    public com.erp.entity.qms.PatrolPlan createPatrolPlan(Map<String, Object> body) {
        String itemCode = str(body.get("itemCode"));
        if (!hasText(itemCode)) {
            throw new ServiceException(422, "物料编码必填");
        }
        if (!hasText(str(body.get("name")))) {
            throw new ServiceException(422, "计划名称必填");
        }
        int interval = body.get("intervalMinutes") == null ? 120
                : Integer.parseInt(String.valueOf(body.get("intervalMinutes")));
        if (interval < 1) {
            throw new ServiceException(422, "触发间隔必须 ≥1 分钟");
        }
        com.erp.entity.qms.PatrolPlan plan = new com.erp.entity.qms.PatrolPlan();
        plan.setPlanCode(nextPlanCode());
        plan.setName(str(body.get("name")));
        plan.setLotType("IPQC");
        plan.setItemCode(itemCode);
        plan.setItemName(str(body.get("itemName")));
        plan.setProcessId(str(body.get("processId")));
        plan.setProcessName(str(body.get("processName")));
        plan.setIntervalMinutes(interval);
        BigDecimal qty = dec(body.get("lotQty"));
        plan.setLotQty(qty == null ? BigDecimal.ONE : qty);
        plan.setStatus("ACTIVE");
        plan.setNextRunTime(LocalDateTime.now().plusMinutes(interval));
        plan.setRemark(str(body.get("remark")));
        patrolPlanDao.insert(plan);
        return plan;
    }

    @Override
    @Transactional
    public com.erp.entity.qms.PatrolPlan togglePatrolPlan(String id) {
        var plan = patrolPlanDao.selectById(id);
        if (plan == null) {
            throw new ServiceException(404, "巡检计划不存在");
        }
        if ("PAUSED".equals(plan.getStatus())) {
            plan.setStatus("ACTIVE");
            plan.setNextRunTime(LocalDateTime.now().plusMinutes(
                    plan.getIntervalMinutes() == null ? 120 : plan.getIntervalMinutes()));
        } else {
            plan.setStatus("PAUSED");
        }
        if (patrolPlanDao.updateById(plan) == 0) {
            throw new ServiceException(422, "计划状态更新冲突");
        }
        return plan;
    }

    @Override
    @Transactional
    public int runPatrolPlans() {
        List<com.erp.entity.qms.PatrolPlan> due = patrolPlanDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.qms.PatrolPlan>()
                        .eq(com.erp.entity.qms.PatrolPlan::getStatus, "ACTIVE")
                        .le(com.erp.entity.qms.PatrolPlan::getNextRunTime, LocalDateTime.now())
                        .last("LIMIT 50"));
        int n = 0;
        for (com.erp.entity.qms.PatrolPlan plan : due) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("lotType", "IPQC");
                body.put("refType", "PLAN");
                body.put("refId", plan.getId());
                body.put("itemCode", plan.getItemCode());
                body.put("itemName", plan.getItemName());
                body.put("processId", plan.getProcessId());
                body.put("qty", plan.getLotQty());
                InspectionLot lot = createManual(body);
                plan.setLastRunTime(LocalDateTime.now());
                plan.setLastLotNo(lot.getLotNo());
                int step = plan.getIntervalMinutes() == null ? 120 : plan.getIntervalMinutes();
                plan.setNextRunTime(LocalDateTime.now().plusMinutes(step));
                patrolPlanDao.updateById(plan);
                n++;
            } catch (Exception e) {
                // 单个计划失败不阻断其余计划（下轮重试）
                log.warn("patrol plan {} failed: {}", plan.getPlanCode(), e.getMessage());
            }
        }
        return n;
    }

    private String nextPlanCode() {
        String prefix = "PAT" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = patrolPlanDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    // ================= 工具 =================

    // ================= 录入 / 判定 / 放行（group 5） =================

    @Override
    @Transactional
    public Map<String, Object> inputItems(String lotId, List<Map<String, Object>> rows,
                                          boolean abnormalConfirm) {
        requireRole("录入检验数据", "ROLE_INSPECTOR", "ROLE_QUALITY_ENG");
        InspectionLot lot = requireLot(lotId);
        if (ST_BLOCKED.equals(lot.getStatus())) {
            throw new ServiceException(422, "该批次无生效检验标准（BLOCKED），请先维护检验标准");
        }
        if (ST_SKIPPED.equals(lot.getStatus())) {
            throw new ServiceException(422, "免检批次无需录入检验数据");
        }
        if ("RELEASED".equals(lot.getStatus())) {
            throw new ServiceException(422, "已放行批次不可修改检验数据");
        }
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException(422, "检验项数据必填");
        }
        List<String> abnormal = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String itemId = str(r.get("itemId"));
            if (!hasText(itemId)) {
                throw new ServiceException(422, "检验项 ID 必填");
            }
            LotItem li = lotItemDao.selectById(itemId);
            if (li == null || !lotId.equals(li.getLotId())) {
                throw new ServiceException(422, "检验项不属于该批次：" + itemId);
            }
            boolean isCtq = "1".equals(li.getCtqFlag());
            BigDecimal measured = dec(r.get("measuredValue"));

            // CTQ 必录实测值（BR-4.12-14，L1）
            if (isCtq && measured == null && "NUMERIC".equals(li.getSpecType())) {
                throw new ServiceException(422, "CTQ 项必须记录实测值：" + li.getCharacteristicName());
            }
            // 器具校准状态卡控（C-4.12-08 / BR-4.12-10）
            String instrumentCode = str(r.get("instrumentCode"));
            if (hasText(instrumentCode)) {
                var gauge = gaugeDao.selectOne(new LambdaQueryWrapper<com.erp.entity.qms.Gauge>()
                        .eq(com.erp.entity.qms.Gauge::getGaugeCode, instrumentCode)
                        .last("LIMIT 1"));
                if (gauge != null) {
                    if (!"VALID".equals(gauge.getStatus())) {
                        throw new ServiceException(422, "器具校准过期/停用，不可录入（" + instrumentCode
                                + "，状态 " + gauge.getStatus() + "）");
                    }
                    if ("LIMITED".equals(gauge.getStatus()) && isCtq) {
                        throw new ServiceException(422, "限用器具超出使用范围（CTQ 项）：" + instrumentCode);
                    }
                }
                li.setInstrumentCode(instrumentCode);
                li.setInstrumentId(gaugeOf(instrumentCode));
            }

            String judge = str(r.get("judge"));
            if (measured != null && "NUMERIC".equals(li.getSpecType())
                    && li.getLowerLimit() != null && li.getUpperLimit() != null) {
                boolean pass = measured.compareTo(li.getLowerLimit()) >= 0
                        && measured.compareTo(li.getUpperLimit()) <= 0;
                li.setJudge(pass ? "PASS" : "FAIL");
                // 数据异常二次确认（BR-4.12-15：偏离 ≥10×公差，L4 提示）
                BigDecimal tol = li.getUpperLimit().subtract(li.getLowerLimit())
                        .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
                BigDecimal center = li.getTargetValue() != null ? li.getTargetValue()
                        : li.getLowerLimit().add(li.getUpperLimit()).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
                if (tol.signum() > 0 && measured.subtract(center).abs()
                        .compareTo(tol.multiply(BigDecimal.TEN)) >= 0) {
                    li.setAbnormalConfirm(abnormalConfirm ? "1" : "0");
                    li.setAbnormalOriginal(measured);
                    if (!abnormalConfirm) {
                        abnormal.add(li.getCharacteristicName());
                    }
                }
            } else if ("COUNT".equals(li.getSpecType())) {
                if (measured == null) {
                    throw new ServiceException(422, "计数型检验项必须录入数值：" + li.getCharacteristicName());
                }
                li.setJudge(measured.signum() == 0 ? "PASS" : "FAIL");
            } else if ("LOOK".equals(li.getSpecType())) {
                if (!hasText(judge) || !("PASS".equals(judge) || "FAIL".equals(judge))) {
                    throw new ServiceException(422, "外观类检验项须给出 PASS/FAIL 判定："
                            + li.getCharacteristicName());
                }
                li.setJudge(judge);
            } else {
                if (measured == null && !hasText(judge)) {
                    throw new ServiceException(422, "检验项缺少实测值或判定：" + li.getCharacteristicName());
                }
                li.setJudge(hasText(judge) ? judge : (measured != null ? "PASS" : "PENDING"));
            }
            li.setMeasuredValue(measured);
            if (hasText(str(r.get("remark")))) {
                li.setRemark(str(r.get("remark")));
            }
            li.setInspectBy(com.erp.util.SecurityUtils.getCurrentUserId());
            li.setInspectTime(LocalDateTime.now());
            if (lotItemDao.updateById(li) == 0) {
                throw new ServiceException(422, "检验项更新冲突，请刷新重试");
            }
        }
        if (!"INPUTTING".equals(lot.getStatus())) {
            lot.setStatus("INPUTTING");
            lot.setInspectorId(com.erp.util.SecurityUtils.getCurrentUserId());
            lot.setInspectorName(com.erp.util.SecurityUtils.getCurrentUserId());
            if (lotDao.updateById(lot) == 0) {
                throw new ServiceException(422, "批次状态更新冲突，请刷新重试");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lot", lot);
        out.put("items", lotItemDao.selectList(new LambdaQueryWrapper<LotItem>()
                .eq(LotItem::getLotId, lotId).orderByAsc(LotItem::getSequenceNo)));
        out.put("abnormal", abnormal);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> judge(String lotId) {
        requireRole("执行检验判定", "ROLE_INSPECTOR", "ROLE_QUALITY_ENG");
        InspectionLot lot = requireLot(lotId);
        if (!ST_PENDING.equals(lot.getStatus()) && !"INPUTTING".equals(lot.getStatus())) {
            throw new ServiceException(422, "当前批次状态不可判定：" + lot.getStatus());
        }
        List<LotItem> items = lotItemDao.selectList(new LambdaQueryWrapper<LotItem>()
                .eq(LotItem::getLotId, lotId));
        if (items.isEmpty()) {
            throw new ServiceException(422, "检验项快照缺失，不可判定");
        }
        List<String> pending = new ArrayList<>();
        List<String> ctqMissing = new ArrayList<>();
        int defect = 0;
        boolean ctqFail = false;
        for (LotItem it : items) {
            if ("PENDING".equals(it.getJudge())) {
                pending.add(it.getCharacteristicName());
                continue;
            }
            if ("1".equals(it.getCtqFlag()) && "NUMERIC".equals(it.getSpecType())
                    && it.getMeasuredValue() == null) {
                ctqMissing.add(it.getCharacteristicName());
            }
            if ("FAIL".equals(it.getJudge())) {
                defect++;
                if ("1".equals(it.getCtqFlag())) {
                    ctqFail = true;
                }
            }
        }
        if (!ctqMissing.isEmpty()) {
            throw new ServiceException(422, "CTQ 项未完成检验：" + String.join("、", ctqMissing));
        }
        if (!pending.isEmpty()) {
            throw new ServiceException(422, "存在未完成检验项：" + String.join("、", pending));
        }

        int ac = lot.getAcValue() == null ? 0 : lot.getAcValue();
        int re = lot.getReValue() == null ? ac + 1 : lot.getReValue();
        String verdict;
        if (ctqFail || defect >= re) {
            verdict = "FAIL";
        } else if (defect > 0 && defect == ac) {
            verdict = "BOUNDARY";
        } else {
            verdict = "PASS";
        }

        if ("FAIL".equals(verdict)) {
            lot.setResult("FAIL");
            lot.setStatus("FROZEN");
            updateLot(lot);
            setLineQc(lot, "FROZEN");
            // BR-4.12-12：不合格批次自动取消免检
            disableExempt(lot.getItemCode(), lot.getSupplierId(), "批次不合格自动取消免检（" + lot.getLotNo() + "）");
            onLotJudged(lot, "FAIL");
            // 同事务生成 NCR + 两阶段冻结（tasks 6.1，spec ncr-management）
            com.erp.entity.qms.Ncr ncr = ncrService.createFromLot(lot, defect, ac, re, items);
            log.info("lot {} judged FAIL (defect={}, ac={}, re={}), NCR={}", lot.getLotNo(), defect, ac, re,
                    ncr.getNcrNo());
        } else if ("BOUNDARY".equals(verdict)) {
            lot.setResult("PASS");
            lot.setStatus("REVIEWING");
            updateLot(lot);
            log.info("lot {} boundary judgment (defect=ac={}), 转质量工程师复核", lot.getLotNo(), ac);
        } else {
            lot.setResult("PASS");
            lot.setStatus("INPUTTING"); // 判定合格待质检员确认（BR-4.12-20）
            updateLot(lot);
            log.info("lot {} judged PASS, awaiting inspector confirm", lot.getLotNo());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lot", lot);
        out.put("verdict", verdict);
        out.put("defectCount", defect);
        out.put("ac", ac);
        out.put("re", re);
        out.put("items", items);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> reviewBoundary(String lotId, boolean pass, String opinion) {
        requireRole("边界判定复核", "ROLE_QUALITY_ENG");
        InspectionLot lot = requireLot(lotId);
        if (!"REVIEWING".equals(lot.getStatus())) {
            throw new ServiceException(422, "仅边界判定批次可复核：" + lot.getStatus());
        }
        lot.setReviewBy(com.erp.util.SecurityUtils.getCurrentUserId());
        lot.setReviewDate(LocalDateTime.now());
        if (pass) {
            lot.setStatus("INPUTTING");
            lot.setResult("PASS");
            lot.setRemark(hasText(opinion) ? "边界复核通过：" + opinion : "边界复核通过");
        } else {
            lot.setStatus("INPUTTING");
            lot.setResult("PENDING");
            lot.setRemark(hasText(opinion) ? "边界复核退回重检：" + opinion : "边界复核退回重检");
        }
        updateLot(lot);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lot", lot);
        out.put("verdict", pass ? "REVIEW_PASS" : "REVIEW_BACK");
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> confirmRelease(String lotId) {
        requireRole("确认合格放行", "ROLE_INSPECTOR", "ROLE_QUALITY_ENG");
        InspectionLot lot = requireLot(lotId);
        if ("RELEASED".equals(lot.getStatus())) {
            throw new ServiceException(422, "放行不可撤回（已放行）");
        }
        if (!"PASS".equals(lot.getResult()) || "REVIEWING".equals(lot.getStatus())
                || "FROZEN".equals(lot.getStatus())) {
            throw new ServiceException(422, "批次未处于「判定合格待确认」状态，不可放行（当前 "
                    + lot.getStatus() + "/" + lot.getResult() + "）");
        }
        List<LotItem> items = lotItemDao.selectList(new LambdaQueryWrapper<LotItem>()
                .eq(LotItem::getLotId, lotId));
        List<String> failures = new ArrayList<>();
        if (!hasText(lot.getStandardId())) {
            failures.add("任务与标准版本未绑定");
        }
        List<String> ctqMissing = items.stream()
                .filter(it -> "1".equals(it.getCtqFlag()) && "NUMERIC".equals(it.getSpecType())
                        && it.getMeasuredValue() == null)
                .map(LotItem::getCharacteristicName).toList();
        if (!ctqMissing.isEmpty()) {
            failures.add("CTQ 项未完成检验：" + String.join("、", ctqMissing));
        }
        List<String> recordMissing = items.stream()
                .filter(it -> "PENDING".equals(it.getJudge()))
                .map(LotItem::getCharacteristicName).toList();
        if (!recordMissing.isEmpty()) {
            failures.add("检验记录缺失：" + String.join("、", recordMissing));
        }
        long suspect = suspectLotDao.selectCount(new LambdaQueryWrapper<com.erp.entity.qms.SuspectLot>()
                .eq(com.erp.entity.qms.SuspectLot::getLotId, lotId)
                .eq(com.erp.entity.qms.SuspectLot::getStatus, "PENDING_EVAL"));
        if (suspect > 0) {
            failures.add("该批次处于量具失准追溯评估中");
        }
        long openNcr = ncrDao.selectCount(new LambdaQueryWrapper<com.erp.entity.qms.Ncr>()
                .eq(com.erp.entity.qms.Ncr::getLotId, lotId)
                .notIn(com.erp.entity.qms.Ncr::getStatus, "CLOSED", "CANCELLED"));
        if (openNcr > 0) {
            failures.add("存在未关闭 NCR");
        }
        for (LotItem it : items) {
            if (hasText(it.getInstrumentCode())) {
                var g = gaugeDao.selectOne(new LambdaQueryWrapper<com.erp.entity.qms.Gauge>()
                        .eq(com.erp.entity.qms.Gauge::getGaugeCode, it.getInstrumentCode())
                        .last("LIMIT 1"));
                if (g != null && !"VALID".equals(g.getStatus())) {
                    failures.add("器具校准状态非有效：" + it.getInstrumentCode());
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new ServiceException(422, "放行校验未通过：" + String.join("；", failures));
        }

        lot.setStatus("RELEASED");
        lot.setReleasedBy(com.erp.util.SecurityUtils.getCurrentUserId());
        lot.setReleasedDate(LocalDateTime.now());
        updateLot(lot);
        setLineQc(lot, "RELEASED");
        onLotJudged(lot, "PASS");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lot", lot);
        out.put("releasedBy", lot.getReleasedBy());
        out.put("releasedDate", lot.getReleasedDate());
        return out;
    }

    @Override
    public Page<Map<String, Object>> board(long current, long size, String status) {
        LambdaQueryWrapper<InspectionLot> qw = new LambdaQueryWrapper<InspectionLot>()
                .eq(hasText(status), InspectionLot::getStatus, status)
                .in(InspectionLot::getStatus,
                        hasText(status) ? List.of(status)
                                : List.of(ST_BLOCKED, ST_PENDING, "INPUTTING", "REVIEWING"))
                .orderByAsc(InspectionLot::getDueTime);
        Page<InspectionLot> raw = lotDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (InspectionLot l : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("lotNo", l.getLotNo());
            m.put("lotType", l.getLotType());
            m.put("itemCode", l.getItemCode());
            m.put("itemName", l.getItemName());
            m.put("supplierName", l.getSupplierName());
            m.put("batchNo", l.getBatchNo());
            m.put("lotQty", l.getLotQty());
            m.put("riskGrade", l.getRiskGrade());
            m.put("status", l.getStatus());
            m.put("result", l.getResult());
            m.put("standardCode", l.getStandardCode());
            m.put("standardVersion", l.getStandardVersion());
            m.put("sampleBasis", l.getSampleBasis());
            m.put("sampleQty", l.getSampleQty());
            m.put("strictness", l.getStrictness());
            m.put("exemptFlag", l.getExemptFlag());
            m.put("dueTime", l.getDueTime());
            boolean overdue = l.getDueTime() != null && l.getDueTime().isBefore(now);
            m.put("overdue", overdue);
            m.put("remainHours", l.getDueTime() == null ? null
                    : Math.round(java.time.Duration.between(now, l.getDueTime()).toMinutes() / 60.0));
            m.put("grId", l.getGrId());
            m.put("grLineId", l.getGrLineId());
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    private String gaugeOf(String code) {
        var g = gaugeDao.selectOne(new LambdaQueryWrapper<com.erp.entity.qms.Gauge>()
                .eq(com.erp.entity.qms.Gauge::getGaugeCode, code).last("LIMIT 1"));
        return g == null ? null : g.getId();
    }

    /** 质量角色校验（spec quality-permissions）：角色不符 403 */
    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        boolean isAdmin = userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r));
        if (isAdmin) {
            return;
        }
        for (String want : allowed) {
            for (String r : userRoles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    private void setLineQc(InspectionLot lot, String qcStatus) {
        if (lot.getGrLineId() == null) {
            return;
        }
        GoodsReceiptLine line = lineDao.selectById(lot.getGrLineId());
        if (line != null) {
            line.setQcStatus(qcStatus);
            lineDao.updateById(line);
        }
    }
    private void updateLot(InspectionLot lot) {
        if (lotDao.updateById(lot) == 0) {
            throw new ServiceException(422, "批次状态更新冲突，请刷新重试");
        }
    }

    private LocalDateTime dueTimeOf(GoodsReceipt gr, String grade) {
        LocalDateTime base = gr.getCreateDate() != null ? gr.getCreateDate() : LocalDateTime.now();
        return base.plusHours(hoursOf(grade));
    }

    private int hoursOf(String grade) {
        return switch (grade == null ? "C" : grade) {
            case "A" -> hoursA;
            case "B" -> hoursB;
            default -> hoursC;
        };
    }

    private double rateFor(String grade) {
        return switch (grade == null ? "C" : grade) {
            case "A" -> rateA;
            case "B" -> rateB;
            default -> rateC;
        };
    }

    /** 抽样档位上调一档：C 20% → B 50% → A 100%（C-4.12-10） */
    private double tighten(double rate) {
        if (rate <= rateC + 1e-9) {
            return rateB;
        }
        if (rate <= rateB + 1e-9) {
            return rateA;
        }
        return rateA;
    }

    private String riskGradeOf(String itemCode) {
        MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, itemCode).last("LIMIT 1"));
        String g = item == null ? null : item.getRiskGrade();
        return hasText(g) ? g : "C";
    }

    private String nextLotNo() {
        String prefix = "IL" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = lotDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private InspectionLot requireLot(String id) {
        InspectionLot lot = lotDao.selectById(id);
        if (lot == null) {
            throw new ServiceException(404, "检验批不存在");
        }
        return lot;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String fallback(String v, String def) {
        return hasText(v) ? v : def;
    }

    private static BigDecimal dec(Object o) {
        if (o == null || !hasText(String.valueOf(o))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o)).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
