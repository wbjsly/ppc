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
 * 偏差（change rework-inspection-tasks proposal）：
 *   D-1 无排班表：分派以「在手未结批数最少」近似能力上限，超时自动改派一次；
 *   D-2 投诉域未建：客户协议 COMPLAINT_STREAK 由质量工程师手工登记/清零（留痕）；
 *   D-3 合格证无防伪编号体系，CERT_NO 仅流水单号。
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
    /** 放行监听（rework 3.8：返工复检合格→入库解冻关单，同事务） */
    private final java.util.List<com.erp.service.qms.LotReleaseListener> releaseListeners;
    /** 外协检验工作台（5.10.2）：GR→PO→外协派工单上下文 */
    private final com.erp.dao.mrp.MrpDispatchDao dispatchDao;
    private final com.erp.dao.proc.GoodsReceiptDao grDao;
    /** 分派/超时（change rework-inspection-tasks design D2） */
    private final com.erp.dao.system.SysUserDao userDao;
    private final com.erp.dao.system.SysUserRoleDao userRoleDao;
    private final com.erp.dao.system.SysRoleDao roleDao;
    private final com.erp.service.system.NoticeService noticeService;
    private final com.erp.service.qms.CustomerAgreementService agreementService;
    /** OQC 完工批放行联动入库（change rework-inspection-tasks design D1） */
    private final com.erp.service.inv.StockPostingEngine stockPostingEngine;
    private final com.erp.dao.inv.InvTransactionDao txnDao;
    private final com.erp.service.SysParamService sysParam;
    private final com.erp.dao.qms.CertificateDao certificateDao;

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
                                    @Lazy com.erp.service.qms.NcrService ncrService,
                                    java.util.List<com.erp.service.qms.LotReleaseListener> releaseListeners,
                                    com.erp.dao.mrp.MrpDispatchDao dispatchDao,
                                    com.erp.dao.proc.GoodsReceiptDao grDao,
                                    com.erp.dao.system.SysUserDao userDao,
                                    com.erp.dao.system.SysUserRoleDao userRoleDao,
                                    com.erp.dao.system.SysRoleDao roleDao,
                                    com.erp.service.system.NoticeService noticeService,
                                    com.erp.service.qms.CustomerAgreementService agreementService,
                                    com.erp.service.inv.StockPostingEngine stockPostingEngine,
                                    com.erp.dao.inv.InvTransactionDao txnDao,
                                    com.erp.service.SysParamService sysParam,
                                    com.erp.dao.qms.CertificateDao certificateDao) {
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
        this.releaseListeners = releaseListeners == null ? java.util.List.of() : releaseListeners;
        this.dispatchDao = dispatchDao;
        this.grDao = grDao;
        this.userDao = userDao;
        this.userRoleDao = userRoleDao;
        this.roleDao = roleDao;
        this.noticeService = noticeService;
        this.agreementService = agreementService;
        this.stockPostingEngine = stockPostingEngine;
        this.txnDao = txnDao;
        this.sysParam = sysParam;
        this.certificateDao = certificateDao;
    }

    // ================= 外协收货检验工作台（change add-outsourcing-processing） =================

    @Override
    public Page<Map<String, Object>> outsourceBoard(long current, long size, String status) {
        // 权限两条线（spec outsourcing-settlement「菜单与权限」）：5.10.2 仅质量线可见，
        // PLANNER/PURCHASER 调用 403（读接口同样受叶子权限约束）
        requireRole("查看外协收货检验", "ROLE_INSPECTOR", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        // 1) 外协派工单（含 PO 回联）→ poNo → dispatch 上下文
        java.util.Map<String, com.erp.entity.mrp.MrpDispatch> dispatchByPo = new java.util.HashMap<>();
        java.util.List<com.erp.entity.mrp.MrpDispatch> ds = dispatchDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.mrp.MrpDispatch>()
                        .eq(com.erp.entity.mrp.MrpDispatch::getOutsourceFlag, "1")
                        .isNotNull(com.erp.entity.mrp.MrpDispatch::getPoNo));
        for (com.erp.entity.mrp.MrpDispatch d : ds) {
            if (d.getPoNo() != null && !d.getPoNo().isEmpty()) {
                dispatchByPo.put(d.getPoNo(), d);
            }
        }
        Page<Map<String, Object>> out = new Page<>(current, size, 0);
        if (dispatchByPo.isEmpty()) {
            out.setRecords(List.of());
            return out;
        }
        // 2) PO → GR → 检验批（外协批 = GR 来自外协 PO）
        java.util.List<com.erp.entity.proc.GoodsReceipt> grs = grDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.proc.GoodsReceipt>()
                        .in(com.erp.entity.proc.GoodsReceipt::getPoNo, dispatchByPo.keySet()));
        java.util.Map<String, com.erp.entity.proc.GoodsReceipt> grById = new java.util.HashMap<>();
        java.util.List<String> grIds = new java.util.ArrayList<>();
        for (com.erp.entity.proc.GoodsReceipt gr : grs) {
            grById.put(gr.getId(), gr);
            grIds.add(gr.getId());
        }
        if (grIds.isEmpty()) {
            out.setRecords(List.of());
            return out;
        }
        LambdaQueryWrapper<InspectionLot> qw = new LambdaQueryWrapper<InspectionLot>()
                .in(InspectionLot::getGrId, grIds)
                .eq(hasText(status), InspectionLot::getStatus, status)
                .orderByAsc(InspectionLot::getDueTime);
        Page<InspectionLot> raw = lotDao.selectPage(new Page<>(current, size), qw);
        out.setTotal(raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (InspectionLot l : raw.getRecords()) {
            com.erp.entity.proc.GoodsReceipt gr = grById.get(l.getGrId());
            com.erp.entity.mrp.MrpDispatch d = gr == null ? null : dispatchByPo.get(gr.getPoNo());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("lotNo", l.getLotNo());
            m.put("status", l.getStatus());
            m.put("result", l.getResult());
            m.put("itemCode", l.getItemCode());
            m.put("itemName", l.getItemName());
            m.put("supplierName", l.getSupplierName());
            m.put("batchNo", l.getBatchNo());
            m.put("lotQty", l.getLotQty());
            m.put("exemptFlag", l.getExemptFlag());
            boolean overdue = l.getDueTime() != null && l.getDueTime().isBefore(now);
            m.put("overdue", overdue);
            // 外协上下文（spec：供应商/派工单号/工单工序/供料方式）
            m.put("poNo", gr == null ? null : gr.getPoNo());
            m.put("grId", l.getGrId());
            m.put("grLineId", l.getGrLineId());
            m.put("dispatchNo", d == null ? null : d.getDispatchNo());
            m.put("moNo", d == null ? null : d.getMoNo());
            m.put("opSeq", d == null ? null : d.getOpSeq());
            m.put("opName", d == null ? null : d.getOpName());
            m.put("osSupplyType", d == null ? null : d.getOsSupplyType());
            // 不合格联动 SCAR 提示（spec「不合格联动 SCAR 提示」）
            m.put("scarHint", l.getResult() != null && "FAIL".equals(l.getResult()));
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
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
            assignInspector(lot); // 分派（spec 质检员自动分派）
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
        assignInspector(lot); // 分派
        log.info("recheck lot {} created for NCR {}", lot.getLotNo(), ncr.getNcrNo());
        return lot;
    }

    @Override
    public InspectionLot createReworkRecheck(String refMoId, String itemCode, String itemName,
                                             String batchNo, java.math.BigDecimal qty) {
        // 幂等：同 refId 未放行返工复检批 → 返回既有（完工重复触发安全）
        InspectionLot existed = lotDao.selectOne(new LambdaQueryWrapper<InspectionLot>()
                .eq(InspectionLot::getRefType, "WORKORDER")
                .eq(InspectionLot::getRefId, refMoId + "#REWORK")
                .eq(InspectionLot::getSourceType, "RECHECK")
                .notIn(InspectionLot::getStatus, "RELEASED", "CANCELLED")
                .orderByDesc(InspectionLot::getCreateDate)
                .last("LIMIT 1"));
        if (existed != null) {
            return existed;
        }
        InspectionLot lot = new InspectionLot();
        lot.setLotNo(nextLotNo());
        lot.setLotType("OQC");
        lot.setSourceType("RECHECK");
        lot.setRefType("WORKORDER");
        lot.setRefId(refMoId + "#REWORK");
        lot.setItemCode(itemCode);
        lot.setItemName(itemName);
        lot.setBatchNo(batchNo);
        lot.setLotQty(qty);
        lot.setRiskGrade(riskGradeOf(itemCode));
        lot.setDueTime(LocalDateTime.now().plusHours(hoursOf(lot.getRiskGrade())));
        lot.setStatus(ST_PENDING);
        lot.setResult("PENDING");
        // 加严一档（FR-4.5-7-6 / BR-4.5-02：返工产出按原方案加严——NORMAL→TIGHTENED，已 TIGHTENED 保持）
        lot.setStrictness("TIGHTENED");
        lot.setPriority("HIGH");
        lot.setSampleBasis("返工复检（原检验方案加严一档，FR-4.5-7-6）：" + refMoId);
        Map<String, Object> hit = standardService.resolveFor(itemCode, null, null, null, null);
        if (hit.isEmpty()) {
            lot.setStatus(ST_BLOCKED);
            lot.setSampleBasis("返工复检：无生效检验标准（BR-4.12-07），请先维护标准后激活");
            lotDao.insert(lot);
            return lot;
        }
        fillFromStandard(lot, hit);
        lotDao.insert(lot);
        snapshotItems(lot, (List<Map<String, Object>>) hit.get("characteristics"));
        assignInspector(lot); // 分派
        log.info("rework recheck lot {} created for {} strictness={}",
                lot.getLotNo(), refMoId, lot.getStrictness());
        return lot;
    }

    @Override
    public InspectionLot createTrialLot(String refMoId, String itemCode, String itemName,
                                       String batchNo, java.math.BigDecimal qty) {
        // 幂等：同 refId 未放行试产批 → 返回既有（完工重复触发/冲销重入安全）
        InspectionLot existed = lotDao.selectOne(new LambdaQueryWrapper<InspectionLot>()
                .eq(InspectionLot::getRefType, "WORKORDER")
                .eq(InspectionLot::getRefId, refMoId + "#TRIAL")
                .notIn(InspectionLot::getStatus, "RELEASED", "CANCELLED")
                .orderByDesc(InspectionLot::getCreateDate)
                .last("LIMIT 1"));
        if (existed != null) {
            return existed;
        }
        InspectionLot lot = new InspectionLot();
        lot.setLotNo(nextLotNo());
        lot.setLotType("OQC");
        lot.setSourceType("MANUAL");
        lot.setRefType("WORKORDER");
        lot.setRefId(refMoId + "#TRIAL");
        lot.setItemCode(itemCode);
        lot.setItemName(itemName);
        lot.setBatchNo(batchNo);
        lot.setLotQty(qty);
        lot.setRiskGrade(riskGradeOf(itemCode));
        lot.setDueTime(LocalDateTime.now().plusHours(hoursOf(lot.getRiskGrade())));
        lot.setStatus(ST_PENDING);
        lot.setResult("PENDING");
        // 试产产出按物料既有方案检验（不加严——试产非复检）
        lot.setStrictness("NORMAL");
        lot.setPriority("NORMAL");
        lot.setSampleBasis("试产产出入库检验（OQC，FR-4.5-8-5/BR-4.5-34）：" + refMoId);
        Map<String, Object> hit = standardService.resolveFor(itemCode, null, null, null, null);
        if (hit.isEmpty()) {
            lot.setStatus(ST_BLOCKED);
            lot.setSampleBasis("试产产出检验：无生效检验标准（BR-4.12-07），请先维护标准后激活");
            lotDao.insert(lot);
            return lot;
        }
        fillFromStandard(lot, hit);
        lotDao.insert(lot);
        snapshotItems(lot, (List<Map<String, Object>>) hit.get("characteristics"));
        assignInspector(lot); // 分派
        log.info("trial OQC lot {} created for {}", lot.getLotNo(), refMoId);
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
        if ("URGENT".equals(lot.getPriority())) {
            applyUrgent(lot, "1"); // 手工建批显式紧急 → 从严一档（须在 fillFromStandard 后，防 sampleBasis 被覆盖）
        }
        lotDao.insert(lot);
        snapshotItems(lot, (List<Map<String, Object>>) hit.get("characteristics"));
        applyAgreement(lot, str(body.get("customerId")), str(body.get("categoryCode"))); // 协议加严（OQC 带客户时）
        assignInspector(lot); // 分派（spec 质检员自动分派）
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

    // ================= 分派与超时（change rework-inspection-tasks design D2/D3） =================

    /** 建批后分派：在手未结批最少的启用质检员；ASSIGN_LOG JSON 追加留痕 */
    private void assignInspector(InspectionLot lot) {
        // BLOCKED/SKIPPED 不参与分派（spec 质检员自动分派）
        if (!ST_PENDING.equals(lot.getStatus())) {
            return;
        }
        String bestId = null;
        String bestName = null;
        long bestLoad = Long.MAX_VALUE;
        for (var u : inspectorUsers()) {
            long load = lotDao.selectCount(new LambdaQueryWrapper<InspectionLot>()
                    .in(InspectionLot::getStatus, ST_PENDING, "INPUTTING", "REVIEWING")
                    .ne(InspectionLot::getOverdueFlag, "1")
                    .eq(InspectionLot::getInspectorId, u.getId()));
            if (load < bestLoad) {
                bestLoad = load;
                bestId = u.getId();
                bestName = u.getNickName() != null ? u.getNickName() : u.getUsername();
            }
        }
        if (bestId == null) {
            return; // 无可用质检员 → 悬置待人工指派
        }
        lot.setInspectorId(bestId);
        lot.setInspectorName(bestName);
        lot.setAssignLog(appendAssignLog(lot.getAssignLog(), "ASSIGN", null, bestName, "建批自动分派"));
        lotDao.updateById(lot);
    }

    /** 启用状态的质检员（ROLE_INSPECTOR） */
    private List<com.erp.entity.system.SysUser> inspectorUsers() {
        var role = roleDao.selectOne(new LambdaQueryWrapper<com.erp.entity.system.SysRole>()
                .eq(com.erp.entity.system.SysRole::getRoleCode, "ROLE_INSPECTOR")
                .last("LIMIT 1"));
        if (role == null) {
            return List.of();
        }
        List<String> userIds = userRoleDao.selectList(new LambdaQueryWrapper<com.erp.entity.system.SysUserRole>()
                        .eq(com.erp.entity.system.SysUserRole::getRoleId, role.getId()))
                .stream().map(com.erp.entity.system.SysUserRole::getUserId).toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        return userDao.selectList(new LambdaQueryWrapper<com.erp.entity.system.SysUser>()
                .in(com.erp.entity.system.SysUser::getId, userIds)
                .eq(com.erp.entity.system.SysUser::getStatus, "1"));
    }

    /** ASSIGN_LOG 追加一条 JSON 记录（坏数据按空重置） */
    private String appendAssignLog(String current, String action, String from, String to, String reason) {
        String entry = String.format(Locale.ROOT,
                "{\"ts\":\"%s\",\"action\":\"%s\",\"from\":%s,\"to\":%s,\"reason\":%s}",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
                action, jsonStr(from), jsonStr(to), jsonStr(reason));
        if (!hasText(current) || !current.trim().startsWith("[")) {
            return "[" + entry + "]";
        }
        String trimmed = current.trim();
        return trimmed.substring(0, trimmed.length() - 1) + "," + entry + "]";
    }

    private static String jsonStr(String v) {
        return v == null ? "null" : "\"" + v.replace("\"", "\\\"") + "\"";
    }

    @Override
    public int sweepOverdue() {
        LocalDateTime now = LocalDateTime.now();
        List<InspectionLot> overdue = lotDao.selectList(new LambdaQueryWrapper<InspectionLot>()
                .in(InspectionLot::getStatus, ST_PENDING, "INPUTTING", "REVIEWING")
                .ne(InspectionLot::getOverdueFlag, "1")
                .isNotNull(InspectionLot::getDueTime));
        int handled = 0;
        for (InspectionLot lot : overdue) {
            if (!lot.getDueTime().isBefore(now)) {
                continue;
            }
            lot.setOverdueFlag("1");
            lotDao.updateById(lot);
            handled++;
            // ① 通知质检员本人；IPQC 追加通知车间主管（spec 超时扫描与升级通知）
            if (hasText(lot.getInspectorId())) {
                noticeService.push(null, lot.getInspectorId(),
                        "检验批超时：" + lot.getLotNo(),
                        "批次 " + lot.getLotNo() + "（" + lot.getItemCode() + "）已超检验时限，请尽快处理",
                        "QMS_LOT_OVERDUE", lot.getId());
            }
            if ("IPQC".equals(lot.getLotType())) {
                noticeService.push("ROLE_SHOP_FLOOR", null,
                        "巡检批超时：" + lot.getLotNo(),
                        "IPQC 巡检批 " + lot.getLotNo() + " 超时未出结论",
                        "QMS_LOT_OVERDUE", lot.getId());
            }
            // ② 改派：无 REASSIGN 记录 → 改派在手最少他人 + 通知质量经理；二次超时仅升级
            boolean reassigned = hasText(lot.getAssignLog()) && lot.getAssignLog().contains("REASSIGN");
            if (reassigned) {
                noticeService.push("ROLE_QUALITY_MGR", null,
                        "检验批二次超时升级：" + lot.getLotNo(),
                        "批次 " + lot.getLotNo() + " 已改派后仍超时，请人工协调",
                        "QMS_LOT_ESCALATE", lot.getId());
                continue;
            }
            String newId = null;
            String newName = null;
            long bestLoad = Long.MAX_VALUE;
            for (var u : inspectorUsers()) {
                if (u.getId().equals(lot.getInspectorId())) {
                    continue; // 不改派回原质检员
                }
                long load = lotDao.selectCount(new LambdaQueryWrapper<InspectionLot>()
                        .in(InspectionLot::getStatus, ST_PENDING, "INPUTTING", "REVIEWING")
                        .ne(InspectionLot::getOverdueFlag, "1")
                        .eq(InspectionLot::getInspectorId, u.getId()));
                if (load < bestLoad) {
                    bestLoad = load;
                    newId = u.getId();
                    newName = u.getNickName() != null ? u.getNickName() : u.getUsername();
                }
            }
            if (newId != null) {
                String oldName = lot.getInspectorName();
                lot.setInspectorId(newId);
                lot.setInspectorName(newName);
                lot.setAssignLog(appendAssignLog(lot.getAssignLog(), "REASSIGN",
                        oldName, newName, "超时自动改派"));
                lotDao.updateById(lot);
                noticeService.push("ROLE_QUALITY_MGR", null,
                        "检验批超时已改派：" + lot.getLotNo(),
                        "批次 " + lot.getLotNo() + " 超时，已由 " + oldName + " 改派至 " + newName,
                        "QMS_LOT_REASSIGN", lot.getId());
            }
        }
        return handled;
    }

    /**
     * 客户协议加严命中（spec 命中加严与投诉升级全检）：OQC 建批且批次带客户时调用。
     * 命中 ACTIVE 协议 → AGREE_CODE 快照 + 加严 CTQ 并入检验项 + 档位取严 + 投诉≥2 全检留痕。
     * 客户空 → 不误命中（spec 明确）。
     */
    private void applyAgreement(InspectionLot lot, String customerId, String categoryCode) {
        if (!"OQC".equals(lot.getLotType()) || !hasText(customerId)) {
            return;
        }
        Map<String, Object> hit = agreementService.hitFor(customerId, lot.getItemCode(), categoryCode);
        if (hit.isEmpty()) {
            return;
        }
        com.erp.entity.qms.CustomerAgreement agree =
                (com.erp.entity.qms.CustomerAgreement) hit.get("agreement");
        lot.setAgreeCode(agree.getAgreeCode());
        String basis = (lot.getSampleBasis() == null ? "" : lot.getSampleBasis() + "；")
                + "客户协议 " + agree.getAgreeCode() + " 命中";
        // ① 档位覆盖取严
        String override = (String) hit.get("sampleOverride");
        if (hasText(override)) {
            double overrideRate = rateFor(override);
            double baseRate = rateFor(lot.getRiskGrade());
            if (overrideRate > baseRate) {
                basis += "；协议档位 " + override + " 严于风险档 → 按协议取样";
                // 重算样本量（沿 fillFromStandard 口径：Max(协议档比例, AQL 查表)）
                long qty = lot.getLotQty() == null ? 0L : lot.getLotQty().longValue();
                int sampleA = (int) Math.ceil(qty * overrideRate);
                int sampleB = AqlTables.sampleSizeByLot(qty);
                int sample = Math.max(sampleA, sampleB);
                if (sample > qty) {
                    sample = (int) qty;
                }
                lot.setSampleQty(BigDecimal.valueOf(sample));
                double aql = AqlTables.parseAql(lot.getAqlLevel());
                lot.setAcValue(AqlTables.acceptNumber(sample, aql));
                lot.setReValue(AqlTables.rejectNumber(sample, aql));
            } else {
                basis += "；协议档位 " + override + " 不严于风险档 → 按风险档取样";
            }
        }
        // ② 投诉连续 ≥2 → 100% 全检
        if (Boolean.TRUE.equals(hit.get("fullInspect"))) {
            lot.setSampleQty(lot.getLotQty());
            lot.setAcValue(0);
            lot.setReValue(1);
            basis += "；客户连续投诉升级（" + agree.getComplaintStreak() + " 次）→ 100% 全检";
        }
        lot.setSampleBasis(basis);
        // ③ 加严 CTQ 并入检验项（CTQ 必检）
        String tightJson = (String) hit.get("tightCtq");
        if (hasText(tightJson)) {
            mergeTightCtq(lot, tightJson);
        }
        lotDao.updateById(lot);
    }

    /** 协议加严 CTQ 并入检验项：同名跳过（快照已有），新项插入并标 CTQ */
    @SuppressWarnings("unchecked")
    private void mergeTightCtq(InspectionLot lot, String tightJson) {
        List<Map<String, Object>> rows;
        try {
            rows = (List<Map<String, Object>>) new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(tightJson, List.class);
        } catch (Exception e) {
            log.warn("agreement tightCtq JSON 解析失败 lot={}：{}", lot.getLotNo(), e.getMessage());
            return;
        }
        Set<String> existing = new HashSet<>();
        for (LotItem li : lotItemDao.selectList(new LambdaQueryWrapper<LotItem>()
                .eq(LotItem::getLotId, lot.getId()))) {
            existing.add(li.getCharacteristicName());
        }
        int seq = Math.toIntExact(lotItemDao.selectCount(new LambdaQueryWrapper<LotItem>()
                .eq(LotItem::getLotId, lot.getId()))) + 1;
        for (Map<String, Object> r : rows) {
            String name = str(r.get("name"));
            if (!hasText(name) || existing.contains(name)) {
                continue;
            }
            LotItem li = new LotItem();
            li.setLotId(lot.getId());
            li.setSequenceNo(seq++);
            li.setCharacteristicName(name);
            li.setCtqFlag("1"); // 协议加严项一律 CTQ 必检
            li.setRegulatoryFlag(fallback(str(r.get("regulatory")), "0"));
            li.setSpecType(fallback(str(r.get("specType")), "LOOK"));
            li.setLowerLimit(dec(r.get("lower")));
            li.setUpperLimit(dec(r.get("upper")));
            li.setUnit(str(r.get("unit")));
            li.setMethodName(str(r.get("method")));
            li.setJudge("PENDING");
            lotItemDao.insert(li);
        }
    }

    /** 紧急口径（spec 紧急批次优先级）：工单最高优先级（值域顶格=1）→ URGENT + 时限从严一档（B→A、C→B） */    private void applyUrgent(InspectionLot lot, String moPriority) {
        if (!"1".equals(moPriority)) {
            return;
        }
        lot.setPriority("URGENT");
        // 从严一档：用上一档风险等级的时限重算（A 档已最严不变）
        String grade = lot.getRiskGrade() == null ? "C" : lot.getRiskGrade();
        String tighter = switch (grade) {
            case "A" -> "A";
            case "B" -> "A";
            default -> "B";
        };
        lot.setDueTime(LocalDateTime.now().plusHours(hoursOf(tighter)));
        lot.setSampleBasis((lot.getSampleBasis() == null ? "" : lot.getSampleBasis() + "；")
                + "紧急批次（工单最高优先级）→ 时限从严（" + grade + "→" + tighter + " 档）");
    }

    // ================= OQC 完工全链（change rework-inspection-tasks design D1） =================

    @Override
    @Transactional
    public InspectionLot createFinishedGoodsOqc(String refMoId, String itemCode, String itemName,
                                                String batchNo, java.math.BigDecimal qty, Integer moPriority) {
        // 幂等：同 refId 未取消批 → 返回既有
        InspectionLot existed = lotDao.selectOne(new LambdaQueryWrapper<InspectionLot>()
                .eq(InspectionLot::getRefType, "WORKORDER")
                .eq(InspectionLot::getRefId, refMoId + "#FG")
                .notIn(InspectionLot::getStatus, "CANCELLED")
                .orderByDesc(InspectionLot::getCreateDate)
                .last("LIMIT 1"));
        if (existed != null) {
            return existed;
        }
        InspectionLot lot = new InspectionLot();
        lot.setLotNo(nextLotNo());
        lot.setLotType("OQC");
        lot.setSourceType("FG");
        lot.setRefType("WORKORDER");
        lot.setRefId(refMoId + "#FG");
        lot.setItemCode(itemCode);
        lot.setItemName(itemName);
        lot.setBatchNo(batchNo);
        lot.setLotQty(qty == null ? BigDecimal.ZERO : qty);
        lot.setRiskGrade(riskGradeOf(itemCode));
        lot.setDueTime(LocalDateTime.now().plusHours(hoursOf(lot.getRiskGrade())));
        lot.setStatus(ST_PENDING);
        lot.setResult("PENDING");
        lot.setStrictness("NORMAL");
        lot.setPriority("NORMAL");
        if (moPriority != null && moPriority == 1) {
            applyUrgent(lot, "1"); // 工单最高优先级 → URGENT + 从严
        }
        lot.setSampleBasis("正常工单完工入库检验（OQC，FR-4.12-1-3 触发①）：" + refMoId
                + (lot.getPriority().equals("URGENT") ? "；紧急批次时限从严" : ""));

        Map<String, Object> hit = standardService.resolveFor(itemCode, null, null, null, null);
        if (hit.isEmpty()) {
            lot.setStatus(ST_BLOCKED);
            lot.setSampleBasis(lot.getSampleBasis() + "；无生效检验标准（BR-4.12-07），请先维护标准后激活");
            lotDao.insert(lot); // BLOCKED 不阻断完工（沿 IQC 口径）
            return lot;
        }
        fillFromStandard(lot, hit);
        lot.setSampleBasis("正常工单完工入库检验（OQC，FR-4.12-1-3 触发①）：" + refMoId
                + "；" + lot.getSampleBasis());
        lotDao.insert(lot);
        snapshotItems(lot, (List<Map<String, Object>>) hit.get("characteristics"));
        assignInspector(lot);
        log.info("FG OQC lot {} created for {}", lot.getLotNo(), refMoId);
        return lot;
    }

    @Override
    public int cancelFgLotsForMo(String refMoId) {
        List<InspectionLot> open = lotDao.selectList(new LambdaQueryWrapper<InspectionLot>()
                .eq(InspectionLot::getRefType, "WORKORDER")
                .eq(InspectionLot::getRefId, refMoId + "#FG")
                .notIn(InspectionLot::getStatus, "RELEASED", "CANCELLED"));
        for (InspectionLot l : open) {
            l.setStatus("CANCELLED");
            l.setRemark(hasText(l.getRemark()) ? l.getRemark() + "；完工冲销取消" : "完工冲销取消");
            lotDao.updateById(l);
        }
        long released = lotDao.selectCount(new LambdaQueryWrapper<InspectionLot>()
                .eq(InspectionLot::getRefType, "WORKORDER")
                .eq(InspectionLot::getRefId, refMoId + "#FG")
                .eq(InspectionLot::getStatus, "RELEASED"));
        if (released > 0) {
            log.warn("完工冲销 {}：已放行 OQC 批 {} 张不回滚，需人工红冲入库", refMoId, released);
        }
        return open.size();
    }

    /** OQC 完工批（refId=<moId>#FG）放行联动 WIP_IN 入库 + 合格证（design D1/D6） */
    private void releaseFgLinked(InspectionLot lot) {
        if (!"OQC".equals(lot.getLotType()) || lot.getRefId() == null
                || !lot.getRefId().endsWith("#FG")) {
            return;
        }
        String moId = lot.getRefId().substring(0, lot.getRefId().length() - 3);
        // 幂等锚：同批号 MO_FINISH 流水已存在 → 跳过（防重复放行重入）
        Long prior = txnDao.selectCount(new LambdaQueryWrapper<com.erp.entity.inv.InvTransaction>()
                .eq(com.erp.entity.inv.InvTransaction::getBizDocType, "MO_FINISH")
                .eq(com.erp.entity.inv.InvTransaction::getBizDocNo, lot.getLotNo()));
        // 仓库与批次：沿试产口径——仓库取参数 FG_WH（默认 WH-MAIN），批次取工单号
        String wh = sysParam.getValue("FG_WH", "WH-MAIN");
        com.erp.service.inv.StockPostingEngine.Line line = new com.erp.service.inv.StockPostingEngine.Line();
        line.warehouseCode = wh;
        line.itemCode = lot.getItemCode();
        line.itemName = lot.getItemName();
        line.batchNo = hasText(lot.getBatchNo()) ? lot.getBatchNo() : ("MO" + moId);
        line.qty = lot.getLotQty();
        var item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, lot.getItemCode()).last("LIMIT 1"));
        if (item != null && "1".equals(item.getBatchFlag())) {
            int days = item.getShelfLifeDays() == null ? 365 : item.getShelfLifeDays();
            line.expiryDate = LocalDate.now().plusDays(days);
        }
        if (prior == null || prior == 0) {
            stockPostingEngine.post(com.erp.service.inv.StockPostingEngine.Request.of(
                    "WIP_IN", "MO_FINISH", lot.getLotNo(), List.of(line)));
            log.info("FG OQC 放行联动入库 {}：{} × {} → {}", lot.getLotNo(),
                    lot.getItemCode(), lot.getLotQty(), wh);
        }
        issueCertificate(lot); // 合格证（OQC 放行同事务，D6）
    }

    /** OQC 合格证（spec 合格证；D-3 偏差：CERT_NO 仅流水无防伪） */
    private void issueCertificate(InspectionLot lot) {
        Long existing = certificateDao.selectCount(new LambdaQueryWrapper<com.erp.entity.qms.Certificate>()
                .eq(com.erp.entity.qms.Certificate::getLotNo, lot.getLotNo()));
        if (existing != null && existing > 0) {
            return; // 幂等
        }
        com.erp.entity.qms.Certificate cert = new com.erp.entity.qms.Certificate();
        cert.setCertNo(nextCertNo());
        cert.setLotNo(lot.getLotNo());
        cert.setLotId(lot.getId());
        cert.setItemCode(lot.getItemCode());
        cert.setItemName(lot.getItemName());
        cert.setQty(lot.getLotQty());
        cert.setCustomerName(lot.getSupplierName()); // 完工批无客户场景留空；发货关联由 OQC 手工批带客户
        cert.setResult("PASS");
        cert.setIssuedBy(com.erp.util.SecurityUtils.getCurrentUserId());
        cert.setIssuedDate(LocalDateTime.now());
        certificateDao.insert(cert);
        log.info("OQC 合格证 {} issued for lot {}", cert.getCertNo(), lot.getLotNo());
    }

    private String nextCertNo() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Long seq = certificateDao.selectCount(new LambdaQueryWrapper<com.erp.entity.qms.Certificate>()
                .likeRight(com.erp.entity.qms.Certificate::getCertNo, "C" + date));
        return "C" + date + "-" + String.format("%06d", (seq == null ? 0 : seq) + 1);
    }

    @Override
    public com.erp.entity.qms.Certificate certificateOf(String lotId) {
        return certificateDao.selectOne(new LambdaQueryWrapper<com.erp.entity.qms.Certificate>()
                .eq(com.erp.entity.qms.Certificate::getLotId, lotId)
                .last("LIMIT 1"));
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
        // 放行监听（同事务；返工复检批→入库解冻关单三件套，change add-rework-management FR-4.5-7-8）
        for (com.erp.service.qms.LotReleaseListener l : releaseListeners) {
            l.onReleased(lot);
        }
        // OQC 完工批放行联动 WIP_IN 入库 + 合格证（rework-inspection-tasks design D1/D6，失败整体回滚）
        releaseFgLinked(lot);
        // 放行通知（rework-qualified-release，spec 合格放行通知）：同事务发送，失败降级不回滚
        sendReleaseNotice(lot);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lot", lot);
        out.put("releasedBy", lot.getReleasedBy());
        out.put("releasedDate", lot.getReleasedDate());
        return out;
    }

    @Override
    public Page<Map<String, Object>> board(long current, long size, String status,
                                           Boolean samplingDone, Boolean releaseMode) {
        LambdaQueryWrapper<InspectionLot> qw = new LambdaQueryWrapper<InspectionLot>()
                .eq(hasText(status), InspectionLot::getStatus, status)
                .in(InspectionLot::getStatus,
                        hasText(status) ? List.of(status)
                                : List.of(ST_BLOCKED, ST_PENDING, "INPUTTING", "REVIEWING"))
                // 6.3.1 抽样模式：false = 仅未抽样在途批（SAMPLED_FLAG IS NULL）
                .isNull(Boolean.FALSE.equals(samplingDone), InspectionLot::getSampledFlag)
                // 6.4.1 放行模式：true = 仅待放行批（判定合格待确认，confirmRelease 合法入口态）
                .eq(Boolean.TRUE.equals(releaseMode), InspectionLot::getResult, "PASS")
                .eq(Boolean.TRUE.equals(releaseMode), InspectionLot::getStatus, "INPUTTING")
                // spec 紧急批次优先级：紧急优先、其次超时、再按创建时间
                .orderByDesc(InspectionLot::getPriority)
                .orderByDesc(InspectionLot::getOverdueFlag)
                .orderByAsc(InspectionLot::getCreateDate);
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
            m.put("overdueFlag", l.getOverdueFlag());
            m.put("sampledFlag", l.getSampledFlag());
            m.put("priority", l.getPriority());
            m.put("inspectorId", l.getInspectorId());
            m.put("inspectorName", l.getInspectorName());
            m.put("assignLog", l.getAssignLog());
            m.put("agreeCode", l.getAgreeCode());
            boolean overdue = "1".equals(l.getOverdueFlag())
                    || (l.getDueTime() != null && l.getDueTime().isBefore(now));
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

    /**
     * 放行通知（FR-4.12-3-1 ①③，spec 合格放行通知）：
     * IQC → 仓库上架待办；OQC → 仓库+销售跟单可发货（携带合格证号）；IPQC 不发（偏差 D-3）。
     * 同事务发送；异常降级为告警日志，不回滚放行（spec：通知失败不回滚放行）。
     */
    private void sendReleaseNotice(InspectionLot lot) {
        try {
            String base = "批号 " + lot.getLotNo() + "（" + lot.getItemCode() + " " + lot.getItemName()
                    + "，数量 " + lot.getLotQty() + "）已检验合格放行，放行人 " + lot.getReleasedBy();
            if ("IQC".equals(lot.getLotType())) {
                noticeService.push("ROLE_WAREHOUSE", null,
                        "IQC 放行待上架：" + lot.getLotNo(),
                        base + "，请安排入库上架", "QMS_RELEASE", lot.getId());
            } else if ("OQC".equals(lot.getLotType())) {
                String certNo = "";
                try {
                    com.erp.entity.qms.Certificate c = certificateOf(lot.getId());
                    if (c != null && hasText(c.getCertNo())) {
                        certNo = "，合格证号 " + c.getCertNo();
                    }
                } catch (Exception ignore) {
                    // 合格证查询失败不阻断通知
                }
                noticeService.push("ROLE_WAREHOUSE", null,
                        "OQC 放行可发货：" + lot.getLotNo(),
                        base + certNo + "，可安排发货出库", "QMS_RELEASE", lot.getId());
                noticeService.push("ROLE_SALES", null,
                        "OQC 放行可发货：" + lot.getLotNo(),
                        base + certNo + "，客户订单可安排发货", "QMS_RELEASE", lot.getId());
            }
            // IPQC 不发（偏差 D-3：报工阻断由未决批断言隐式解除）
        } catch (Exception e) {
            log.warn("放行通知发送失败（降级不回滚，lot={}）：{}", lot.getLotNo(), e.getMessage());
        }
    }

    private void setLineQc(InspectionLot lot, String qcStatus) {        if (lot.getGrLineId() == null) {
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
