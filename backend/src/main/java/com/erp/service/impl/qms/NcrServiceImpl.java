package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.qms.CapaDao;
import com.erp.dao.qms.ConcessionDao;
import com.erp.dao.qms.InspectionLotDao;
import com.erp.dao.qms.LotItemDao;
import com.erp.dao.qms.NcrDao;
import com.erp.dao.qms.NcrLogDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.qms.Capa;
import com.erp.entity.qms.Concession;
import com.erp.entity.qms.InspectionLot;
import com.erp.entity.qms.LotItem;
import com.erp.entity.qms.Ncr;
import com.erp.entity.qms.NcrLog;
import com.erp.service.qms.InspectionLotService;
import com.erp.service.qms.NcrService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCR 状态机实现（spec ncr-management，tasks 6.1~6.6，design D6 两阶段冻结）。
 * 判不合格同事务生成；评审必选处置（安全/法规 CTQ 禁让步 BR-4.12-26）；
 * 处置凭证齐全才 DISPOSED；关闭须 CAPA 有效 + 复检放行；超时/超期扫描幂等。
 */
@Slf4j
@Service
public class NcrServiceImpl implements NcrService {

    /** 状态 */
    private static final String ST_CREATED = "CREATED";
    private static final String ST_DISPOSED = "DISPOSED";
    private static final String ST_CLOSED = "CLOSED";
    private static final String ST_CANCELLED = "CANCELLED";

    /** 处置 */
    private static final String D_RETURN = "RETURN";
    private static final String D_SORT = "SORT";
    private static final String D_REWORK = "REWORK";
    private static final String D_CONCESSION = "CONCESSION";
    /** 报废（add-outbound-workbench 方案 a：报废出库质量来源的正向条件） */
    private static final String D_SCRAP = "SCRAP";
    /** 报废处置进行中（评审后、处置确认前） */
    private static final String ST_SCRAPPING = "SCRAPPING";

    @Value("${app.qms.ncr-review-hours-critical:4}")
    private int hoursCritical;
    @Value("${app.qms.ncr-review-hours-major:24}")
    private int hoursMajor;
    @Value("${app.qms.ncr-review-hours-minor:48}")
    private int hoursMinor;
    @Value("${app.qms.ncr-close-days:30}")
    private int closeDays;
    @Value("${app.qms.ncr-overdue-remind-days:7}")
    private int overdueRemindDays;

    private final NcrDao ncrDao;
    private final NcrLogDao logDao;
    private final GoodsReceiptLineDao lineDao;
    private final InvStockDao stockDao;
    private final InspectionLotDao lotDao;
    private final LotItemDao lotItemDao;
    private final CapaDao capaDao;
    private final ConcessionDao concessionDao;
    private final InspectionLotService lotService;
    private final com.erp.service.proc.ReturnService returnService;
    private final com.erp.service.qms.CapaService capaService;
    private final com.erp.service.qms.CopqService copqService;
    private final com.erp.service.qms.ScarService scarService;
    private final com.erp.service.inv.FreezeService freezeService;

    public NcrServiceImpl(NcrDao ncrDao,
                          NcrLogDao logDao,
                          GoodsReceiptLineDao lineDao,
                          InvStockDao stockDao,
                          InspectionLotDao lotDao,
                          LotItemDao lotItemDao,
                          CapaDao capaDao,
                          ConcessionDao concessionDao,
                          @Lazy InspectionLotService lotService,
                          @Lazy com.erp.service.proc.ReturnService returnService,
                          @Lazy com.erp.service.qms.CapaService capaService,
                          @Lazy com.erp.service.qms.CopqService copqService,
                          @Lazy com.erp.service.qms.ScarService scarService,
                          @Lazy com.erp.service.inv.FreezeService freezeService) {
        this.ncrDao = ncrDao;
        this.logDao = logDao;
        this.lineDao = lineDao;
        this.stockDao = stockDao;
        this.lotDao = lotDao;
        this.lotItemDao = lotItemDao;
        this.capaDao = capaDao;
        this.concessionDao = concessionDao;
        this.lotService = lotService;
        this.returnService = returnService;
        this.capaService = capaService;
        this.copqService = copqService;
        this.scarService = scarService;
        this.freezeService = freezeService;
    }

    // ================= 6.1 生成 + 两阶段冻结 =================

    @Override
    @Transactional
    public Ncr createFromLot(InspectionLot lot, int defectCount, int ac, int re, List<LotItem> items) {
        // 幂等：同批已有未作废 NCR 直接返回（判定点只会有一次，防并发重复）
        Ncr existed = ncrDao.selectOne(new LambdaQueryWrapper<Ncr>()
                .eq(Ncr::getLotId, lot.getId())
                .notIn(Ncr::getStatus, ST_CANCELLED)
                .last("LIMIT 1"));
        if (existed != null) {
            return existed;
        }

        // ---- 严重度自动分级（tasks 6.1）：CTQ/安全法规 → CRITICAL ----
        boolean ctqFail = false;
        boolean regulatoryFail = false;
        List<String> defectNames = new ArrayList<>();
        for (LotItem it : items == null ? List.<LotItem>of() : items) {
            if (!"FAIL".equals(it.getJudge())) {
                continue;
            }
            defectNames.add(it.getCharacteristicName());
            if ("1".equals(it.getCtqFlag())) {
                ctqFail = true;
            }
            if ("1".equals(it.getRegulatoryFlag())) {
                regulatoryFail = true;
            }
        }
        String severity;
        String gradeBasis;
        if (ctqFail || regulatoryFail) {
            severity = "CRITICAL";
            gradeBasis = "CTQ/安全法规项不合格 → Critical（BR-4.12-25）";
        } else if (defectCount >= Math.max(re, 3) || "A".equals(lot.getRiskGrade())) {
            severity = "MAJOR";
            gradeBasis = "缺陷数 " + defectCount + " ≥ Re " + re + " 或高风险物料 A → Major";
        } else {
            severity = "MINOR";
            gradeBasis = "一般不合格（缺陷数 " + defectCount + " < Re " + re + "）→ Minor";
        }

        Ncr ncr = new Ncr();
        ncr.setNcrNo(nextNcrNo());
        ncr.setLotId(lot.getId());
        ncr.setLotNo(lot.getLotNo());
        ncr.setGrId(lot.getGrId());
        ncr.setGrLineId(lot.getGrLineId());
        ncr.setItemCode(lot.getItemCode());
        ncr.setItemName(lot.getItemName());
        ncr.setSupplierId(lot.getSupplierId());
        ncr.setSupplierName(lot.getSupplierName());
        ncr.setBatchNo(lot.getBatchNo());
        ncr.setQty(lot.getLotQty());
        ncr.setSeverity(severity);
        ncr.setDefectItem(defectNames.isEmpty() ? null : String.join("、", defectNames));
        ncr.setDefectDesc("检验批 " + lot.getLotNo() + " 判定不合格（缺陷 " + defectCount
                + " 项 / Ac=" + ac + " / Re=" + re + "）");
        ncr.setCtqFlag(ctqFail ? "1" : "0");
        ncr.setRegulatoryFlag(regulatoryFail ? "1" : "0");
        ncr.setStatus(ST_CREATED);
        ncr.setFrozenFlag("1");
        ncr.setReviewEscalatedFlag("0");
        ncr.setReviewRemindCount(0);
        ncr.setOverdueRemindCount(0);
        ncr.setOverdueFlag("0");
        ncr.setEscalatedFlag("0");
        ncr.setPerfFrozenFlag("0");
        ncr.setReviewDueTime(LocalDateTime.now().plusHours(hoursOf(severity)));
        ncr.setCreateBy(SecurityUtils.getCurrentUserId());

        // ---- 两阶段冻结（design D6）----
        GoodsReceiptLine line = lot.getGrLineId() == null ? null : lineDao.selectById(lot.getGrLineId());
        boolean frozenStock = false;
        String scope;
        if (line != null && "POSTED".equals(line.getStatus())) {
            // 已入库 → 冻结库存（AVAILABLE → QC）
            frozenStock = freezeStock(ncr);
            scope = "STOCK";
        } else if (line != null) {
            // 未过账 → 冻结 GR 行（不入库，BR-4.2-25）
            scope = "GR_LINE";
        } else {
            // 无 GR 行（IPQC/OQC 手工批）→ 尝试冻库存
            frozenStock = freezeStock(ncr);
            scope = frozenStock ? "STOCK" : "NONE";
        }
        ncr.setFreezeScope(scope);
        ncrDao.insert(ncr);

        if (line != null) {
            line.setQcStatus("FROZEN");
            lineDao.updateById(line);
        }

        writeLog(ncr, "CREATE", null, ST_CREATED,
                "NCR 生成：" + gradeBasis + "；冻结=" + scope + (frozenStock ? "（库存 AVAILABLE→QC）" : ""),
                null, null);
        // CAPA 触发判定（tasks 9.1，BR-4.12-31）：Critical / 3 月 3 次重复不合格 → 同事务立项绑 NCR
        var capa = capaService.maybeCreateFromNcr(ncr);
        if (capa != null) {
            writeLog(ncr, "CAPA", ST_CREATED, ST_CREATED,
                    "CAPA 自动立项：" + capa.getCapaNo() + "（BR-4.12-31）", null, null);
        }
        log.info("NCR {} created: lot={} severity={} scope={} ({}), capa={}", ncr.getNcrNo(), lot.getLotNo(),
                severity, scope, gradeBasis, capa == null ? "-" : capa.getCapaNo());
        return ncr;
    }

    // ================= 6.2 评审（必选处置） =================

    @Override
    @Transactional
    public Ncr review(String id, String disposition, String opinion) {
        requireRole("评审 NCR", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Ncr ncr = require(id);
        if (!ST_CREATED.equals(ncr.getStatus()) && !"REVIEWING".equals(ncr.getStatus())) {
            throw new ServiceException(422, "当前状态不可评审：" + ncr.getStatus());
        }
        if (opinion == null || opinion.trim().length() < 2) {
            throw new ServiceException(422, "评审意见必填（至少 2 字）");
        }
        String disp = disposition == null ? "" : disposition.trim().toUpperCase();
        if (disp.isEmpty() || "PENDING".equals(disp) || "待定".equals(disposition)) {
            throw new ServiceException(422, "评审必须选定处置方式（退货 RETURN / 挑选 SORT / 返工 REWORK / 让步接收 CONCESSION / 报废 SCRAP）");
        }
        if (!List.of(D_RETURN, D_SORT, D_REWORK, D_CONCESSION, D_SCRAP).contains(disp)) {
            throw new ServiceException(422, "未知处置方式：" + disposition);
        }
        // BR-4.12-26：安全/法规 CTQ 不合格只能退货或返工，禁止让步接收与报废
        if ((D_CONCESSION.equals(disp) || D_SCRAP.equals(disp))
                && "1".equals(ncr.getRegulatoryFlag())) {
            throw new ServiceException(422, "安全/法规 CTQ 不合格禁止让步接收与报废，仅可退货或返工（BR-4.12-26）");
        }

        String from = ncr.getStatus();
        String to = switch (disp) {
            case D_RETURN -> "RETURNING";
            case D_SORT -> "SORTING";
            case D_REWORK -> "REWORKING";
            case D_SCRAP -> ST_SCRAPPING;
            default -> "CONCESSION";
        };
        ncr.setStatus(to);
        ncr.setDisposition(disp);
        ncr.setReviewBy(SecurityUtils.getCurrentUserId());
        ncr.setReviewTime(LocalDateTime.now());
        ncr.setReviewOpinion(opinion);
        updateNcr(ncr);
        writeLog(ncr, "REVIEW", from, to, "评审选定处置=" + disp + "；意见：" + opinion, null, null);
        // 退货处置 → 同事务自动带出退货单（tasks 8.1，BR-4.2-32：PO/收货单/数量/PO 单价）
        if (D_RETURN.equals(disp)) {
            var ro = returnService.createFromNcr(ncr);
            log.info("NCR {} review RETURN -> return order {}", ncr.getNcrNo(), ro.getReturnNo());
            // SCAR 触发（tasks 10.1，BR-4.12-43）：退货处置自动带出索赔草稿
            var scar = scarService.createFromNcr(ncr, "NCR_RETURN");
            if (scar != null) {
                writeLog(ncr, "SCAR", ncr.getStatus(), ncr.getStatus(),
                        "SCAR 草稿自动带出：" + scar.getScarNo(), null, null);
            }
        } else if (D_SORT.equals(disp)) {
            // 挑选处置同属退货挑选触发面（BR-4.12-43）
            var scar = scarService.createFromNcr(ncr, "NCR_RETURN");
            if (scar != null) {
                writeLog(ncr, "SCAR", ncr.getStatus(), ncr.getStatus(),
                        "SCAR 草稿自动带出（挑选）：" + scar.getScarNo(), null, null);
            }
        }
        return ncr;
    }

    // ================= 6.3 处置方案录入 =================

    @Override
    @Transactional
    public Ncr planDisposition(String id, String plan) {
        requireRole("录入处置方案", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Ncr ncr = require(id);
        if (ncr.getDisposition() == null) {
            throw new ServiceException(422, "请先完成评审并选定处置方式");
        }
        if (!List.of(D_SORT, D_REWORK).contains(ncr.getDisposition())) {
            throw new ServiceException(422, "当前处置（" + ncr.getDisposition() + "）无需录入方案");
        }
        if (!List.of("SORTING", "REWORKING").contains(ncr.getStatus())) {
            throw new ServiceException(422, "当前状态不可录入方案：" + ncr.getStatus());
        }
        if (plan == null || plan.trim().length() < 2) {
            throw new ServiceException(422, "处置方案必填（挑选范围/返工方式/责任方等）");
        }
        ncr.setDisposePlan(plan);
        updateNcr(ncr);
        writeLog(ncr, "DISPOSE", ncr.getStatus(), ncr.getStatus(),
                (D_SORT.equals(ncr.getDisposition()) ? "挑选" : "返工") + "方案：" + plan, null, null);
        return ncr;
    }

    // ================= 6.4 处置执行确认（凭证校验） =================

    @Override
    @Transactional
    public Ncr confirmDisposed(String id, String result) {
        return confirmDisposed(id, result, null);
    }

    @Override
    @Transactional
    public Ncr confirmDisposed(String id, String result, java.math.BigDecimal costAmount) {
        requireRole("确认处置执行", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Ncr ncr = require(id);
        String disp = ncr.getDisposition();
        List<String> missing = new ArrayList<>();
        if (disp == null) {
            missing.add("未评审选定处置");
        }
        // 按处置方式校验凭证齐全（tasks 6.4，缺失 422 逐条列出）
        switch (disp == null ? "" : disp) {
            case D_SORT -> {
                if (!hasText(ncr.getDisposePlan())) {
                    missing.add("挑选方案未录入");
                }
                if (!hasText(result)) {
                    missing.add("挑选执行结果未录入（合格/隔离/报废数量）");
                }
            }
            case D_REWORK -> {
                if (!hasText(ncr.getDisposePlan())) {
                    missing.add("返工方案未录入");
                }
                if (!hasText(result)) {
                    missing.add("返工完成凭证未录入（返工批号/完成时间）");
                }
            }
            case D_RETURN -> {
                long returns = returnCount(ncr.getId());
                if (returns == 0) {
                    missing.add("退货单未生成（2.6.1 质量退货接入后自动带出）");
                }
            }
            case D_CONCESSION -> {
                long approved = concessionDao.selectCount(new LambdaQueryWrapper<Concession>()
                        .eq(Concession::getNcrId, ncr.getId())
                        .eq(Concession::getStatus, "APPROVED"));
                if (approved == 0) {
                    missing.add("让步接收双签未批准（2.5.2）");
                }
            }
            case D_SCRAP -> {
                if (!hasText(result)) {
                    missing.add("报废执行凭证未回填（关联报废单号，4.5.4）");
                }
            }
            default -> missing.add("处置方式缺失");
        }
        if (!List.of("SORTING", "REWORKING", "RETURNING", "CONCESSION", ST_SCRAPPING)
                .contains(ncr.getStatus())) {
            missing.add("状态不可确认执行：" + ncr.getStatus());
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "处置凭证不齐全：" + String.join("；", missing));
        }

        String from = ncr.getStatus();
        // 挑选/返工完成后生成复检批（tasks 6.3：完成后重新检验）
        if (D_SORT.equals(disp) || D_REWORK.equals(disp)) {
            InspectionLot recheck = lotService.createRecheck(ncr);
            writeLog(ncr, "RECHECK", from, "DISPOSED",
                    "复检批生成：" + recheck.getLotNo() + "（复检合格后方可关闭）", null, null);
        }
        ncr.setDisposeResult(result);
        ncr.setDisposeBy(SecurityUtils.getCurrentUserId());
        ncr.setDisposedDate(LocalDateTime.now());
        ncr.setStatus(ST_DISPOSED);
        updateNcr(ncr);
        writeLog(ncr, "CONFIRM", from, ST_DISPOSED, "处置执行确认：" + result, null, null);
        // COPQ 归集（tasks 9.6）：处置成本（内部失败类），成本额由业务方带入，未带不归集
        if (costAmount != null && costAmount.signum() > 0) {
            try {
                copqService.record(java.util.Map.of(
                        "category", "INTERNAL_FAILURE",
                        "amount", costAmount,
                        "sourceType", "NCR",
                        "sourceId", ncr.getId(),
                        "sourceNo", ncr.getNcrNo(),
                        "ncrId", ncr.getId(),
                        "itemCode", ncr.getItemCode() == null ? "" : ncr.getItemCode(),
                        "batchNo", ncr.getBatchNo() == null ? "" : ncr.getBatchNo(),
                        "supplierId", ncr.getSupplierId() == null ? "" : ncr.getSupplierId(),
                        "supplierName", ncr.getSupplierName() == null ? "" : ncr.getSupplierName()));
            } catch (ServiceException se) {
                throw se;
            } catch (Exception ex) {
                log.warn("COPQ record failed for NCR {}: {}", ncr.getNcrNo(), ex.getMessage());
            }
        }
        return ncr;
    }

    // ================= 6.5 关闭校验 + 解冻 =================

    @Override
    @Transactional
    public Ncr close(String id, String opinion) {
        requireRole("关闭 NCR", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR", "ROLE_QUALITY_DIRECTOR");
        Ncr ncr = require(id);
        if (ST_CLOSED.equals(ncr.getStatus())) {
            throw new ServiceException(422, "NCR 已关闭，只读归档");
        }
        if (!ST_DISPOSED.equals(ncr.getStatus())) {
            throw new ServiceException(422, "处置完成后方可关闭（当前 " + ncr.getStatus() + "）");
        }
        List<String> failures = new ArrayList<>();

        // CAPA 有效性校验（tasks 6.5：未立项/未验证 422）
        Capa capa = findCapa(ncr);
        if (capa == null) {
            failures.add("未立项 CAPA（须先完成根因分析与措施验证）");
        } else {
            if (!"VALID".equals(capa.getVerifyResult())) {
                failures.add("CAPA " + capa.getCapaNo() + " 有效性未验证通过（当前 "
                        + capa.getVerifyResult() + "）");
            }
            if (!"CLOSED".equals(capa.getStatus())) {
                failures.add("CAPA " + capa.getCapaNo() + " 未关闭（当前 " + capa.getStatus() + "）");
            }
            ncr.setCapaId(capa.getId());
        }

        // 挑选/返工：复检批须已放行（处置完成含复检合格）
        if (D_SORT.equals(ncr.getDisposition()) || D_REWORK.equals(ncr.getDisposition())) {
            long released = lotDao.selectCount(new LambdaQueryWrapper<InspectionLot>()
                    .eq(InspectionLot::getRefType, "NCR")
                    .eq(InspectionLot::getRefId, ncr.getId())
                    .eq(InspectionLot::getSourceType, "RECHECK")
                    .eq(InspectionLot::getStatus, "RELEASED"));
            long recheckTotal = lotDao.selectCount(new LambdaQueryWrapper<InspectionLot>()
                    .eq(InspectionLot::getRefType, "NCR")
                    .eq(InspectionLot::getRefId, ncr.getId())
                    .eq(InspectionLot::getSourceType, "RECHECK"));
            if (recheckTotal == 0) {
                failures.add("复检批未生成");
            } else if (released == 0) {
                failures.add("复检批未放行（复检合格方可关闭）");
            }
        }
        if (!failures.isEmpty()) {
            throw new ServiceException(422, "关闭校验未通过：" + String.join("；", failures));
        }

        String from = ncr.getStatus();
        ncr.setStatus(ST_CLOSED);
        ncr.setClosedBy(SecurityUtils.getCurrentUserId());
        ncr.setClosedDate(LocalDateTime.now());
        ncr.setFrozenFlag("0");
        ncr.setOverdueFlag("0");
        ncr.setPerfFrozenFlag("0");
        updateNcr(ncr);
        unfreezeAll(ncr);
        writeLog(ncr, "UNFREEZE", from, ST_CLOSED,
                "解冻：" + unfreezeDesc(ncr) + (hasText(opinion) ? "；关闭意见：" + opinion : ""), null, null);
        writeLog(ncr, "CLOSE", from, ST_CLOSED, "NCR 关闭归档（只读，作废须填原因）", null, null);
        log.info("NCR {} closed, unfrozen scope={}", ncr.getNcrNo(), ncr.getFreezeScope());
        return ncr;
    }

    // ================= 作废（解冻） =================

    @Override
    @Transactional
    public Ncr cancel(String id, String reason) {
        requireRole("作废 NCR", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Ncr ncr = require(id);
        if (!ST_CREATED.equals(ncr.getStatus()) && !"REVIEWING".equals(ncr.getStatus())) {
            throw new ServiceException(422, "仅评审前的 NCR 可作废（已进入处置流请走关闭链）：" + ncr.getStatus());
        }
        if (reason == null || reason.trim().length() < 2) {
            throw new ServiceException(422, "作废原因必填（至少 2 字）");
        }
        String from = ncr.getStatus();
        ncr.setStatus(ST_CANCELLED);
        ncr.setFrozenFlag("0");
        updateNcr(ncr);
        // 作废 = 判定推翻：GR 行回到待检（可重新检验），库存锁定还原
        GoodsReceiptLine line = hasText(ncr.getGrLineId()) ? lineDao.selectById(ncr.getGrLineId()) : null;
        if (line != null && "FROZEN".equals(line.getQcStatus())) {
            line.setQcStatus("PENDING");
            lineDao.updateById(line);
        }
        unfreezeStock(ncr);
        writeLog(ncr, "CANCEL", from, ST_CANCELLED, "作废：" + reason, null, null);
        return ncr;
    }

    @Override
    @Transactional
    public Ncr reopenForReview(String ncrId, String reason) {
        Ncr ncr = ncrDao.selectById(ncrId);
        if (ncr == null || !"CONCESSION".equals(ncr.getStatus())) {
            // 幂等：非让步态无需回评审
            return ncr;
        }
        String from = ncr.getStatus();
        ncr.setReviewOpinion(hasText(ncr.getReviewOpinion())
                ? ncr.getReviewOpinion() + "；让步驳回：" + reason : "让步驳回：" + reason);
        // updateById 忽略 null 字段（MP 默认 NOT_NULL 策略）→ 处置选择须显式置 NULL
        int rows = ncrDao.update(null, new LambdaUpdateWrapper<Ncr>()
                .eq(Ncr::getId, ncr.getId())
                .set(Ncr::getStatus, ST_CREATED)
                .setSql("DISPOSITION = NULL")
                .set(Ncr::getReviewOpinion, ncr.getReviewOpinion())
                .set(Ncr::getVerNo, ncr.getVerNo() + 1));
        if (rows == 0) {
            throw new ServiceException(409, "NCR 回评审更新冲突，请刷新重试");
        }
        ncr.setStatus(ST_CREATED);
        ncr.setDisposition(null);
        writeLog(ncr, "REVIEW", from, ST_CREATED,
                "让步接收双签驳回 → NCR 回评审：" + reason, null, null);
        log.info("NCR {} reopened for review (concession rejected): {}", ncr.getNcrNo(), reason);
        return ncr;
    }

    @Override
    public List<NcrLog> logs(String ncrId) {
        return logDao.selectList(new LambdaQueryWrapper<NcrLog>()
                .eq(NcrLog::getNcrId, ncrId)
                .orderByAsc(NcrLog::getCreateDate));
    }

    // ================= 6.2 评审超时扫描（幂等） =================

    @Override
    @Transactional
    public int sweepReviewTimeouts() {
        LocalDateTime now = LocalDateTime.now();
        int handled = 0;

        // 1 倍超时 → 升级质量经理
        List<Ncr> overdueReview = ncrDao.selectList(new LambdaQueryWrapper<Ncr>()
                .eq(Ncr::getStatus, ST_CREATED)
                .eq(Ncr::getReviewEscalatedFlag, "0")
                .isNotNull(Ncr::getReviewDueTime)
                .le(Ncr::getReviewDueTime, now)
                .last("LIMIT 200"));
        for (Ncr ncr : overdueReview) {
            ncr.setReviewEscalatedFlag("1");
            ncr.setReviewRemindCount((ncr.getReviewRemindCount() == null ? 0 : ncr.getReviewRemindCount()) + 1);
            ncr.setLastEscalateTime(now);
            updateNcr(ncr);
            writeLog(ncr, "ESCALATE_MGR", ST_CREATED, ST_CREATED,
                    "评审超时限（" + hoursOf(ncr.getSeverity()) + "h）未完成，升级质量经理",
                    "ROLE_QUALITY_MGR", null);
            handled++;
        }

        // 2 倍超时 → 升级质量总监 + 抄送采购经理
        List<Ncr> escalatedMgr = ncrDao.selectList(new LambdaQueryWrapper<Ncr>()
                .eq(Ncr::getStatus, ST_CREATED)
                .eq(Ncr::getReviewEscalatedFlag, "1")
                .eq(Ncr::getEscalatedFlag, "0")
                .isNotNull(Ncr::getReviewDueTime)
                .last("LIMIT 200"));
        for (Ncr ncr : escalatedMgr) {
            LocalDateTime created = ncr.getCreateDate() != null ? ncr.getCreateDate() : ncr.getReviewDueTime();
            LocalDateTime due = ncr.getReviewDueTime();
            LocalDateTime twoTimes = created.plus(Duration.between(created, due).multipliedBy(2));
            if (twoTimes.isAfter(now)) {
                continue;
            }
            ncr.setEscalatedFlag("1");
            ncr.setLastEscalateTime(now);
            updateNcr(ncr);
            writeLog(ncr, "ESCALATE_DIRECTOR", ST_CREATED, ST_CREATED,
                    "评审超 2 倍时限仍未完成，升级质量总监", "ROLE_QUALITY_DIRECTOR", "ROLE_PM");
            handled++;
        }
        return handled;
    }

    // ================= 6.6 超期 30 天升级 + 每 7 天提醒（幂等） =================

    @Override
    @Transactional
    public int sweepOverdueEscalation() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(closeDays);
        List<Ncr> open = ncrDao.selectList(new LambdaQueryWrapper<Ncr>()
                .in(Ncr::getStatus, ST_CREATED, "REVIEWING", "RETURNING", "SORTING",
                        "REWORKING", "CONCESSION", ST_SCRAPPING, ST_DISPOSED)
                .lt(Ncr::getCreateDate, cutoff)
                .last("LIMIT 200"));
        int handled = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Ncr ncr : open) {
            if (!"1".equals(ncr.getOverdueFlag())) {
                // 首次：升级质量总监 + 抄送采购经理 + 冻结供应商绩效发布（BR-4.2-27）
                ncr.setOverdueFlag("1");
                ncr.setEscalatedFlag("1");
                ncr.setPerfFrozenFlag("1");
                ncr.setOverdueRemindCount(1);
                ncr.setLastEscalateTime(now);
                updateNcr(ncr);
                writeLog(ncr, "ESCALATE_DIRECTOR", ncr.getStatus(), ncr.getStatus(),
                        "超 " + closeDays + " 天未关闭，升级质量总监并抄送采购经理；供应商本期绩效发布冻结（BR-4.2-27）",
                        "ROLE_QUALITY_DIRECTOR", "ROLE_PM");
                handled++;
            } else {
                LocalDateTime last = ncr.getLastEscalateTime();
                if (last != null && !last.plusDays(overdueRemindDays).isAfter(now)) {
                    ncr.setOverdueRemindCount((ncr.getOverdueRemindCount() == null ? 1
                            : ncr.getOverdueRemindCount()) + 1);
                    ncr.setLastEscalateTime(now);
                    updateNcr(ncr);
                    writeLog(ncr, "REMIND", ncr.getStatus(), ncr.getStatus(),
                            "超期第 " + ncr.getOverdueRemindCount() + " 次提醒（每 " + overdueRemindDays
                                    + " 天一次），NCR 仍关闭", "ROLE_QUALITY_DIRECTOR", "ROLE_PM");
                    handled++;
                }
            }
        }
        return handled;
    }

    // ================= 查询 =================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String keyword, String status, String severity) {
        LambdaQueryWrapper<Ncr> qw = new LambdaQueryWrapper<Ncr>()
                .eq(hasText(status), Ncr::getStatus, status)
                .eq(hasText(severity), Ncr::getSeverity, severity)
                .and(hasText(keyword), w -> w.like(Ncr::getNcrNo, keyword)
                        .or().like(Ncr::getItemCode, keyword)
                        .or().like(Ncr::getSupplierName, keyword)
                        .or().like(Ncr::getLotNo, keyword))
                .orderByDesc(Ncr::getCreateDate);
        Page<Ncr> raw = ncrDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Ncr n : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("ncrNo", n.getNcrNo());
            m.put("lotNo", n.getLotNo());
            m.put("itemCode", n.getItemCode());
            m.put("itemName", n.getItemName());
            m.put("supplierName", n.getSupplierName());
            m.put("qty", n.getQty());
            m.put("severity", n.getSeverity());
            m.put("status", n.getStatus());
            m.put("disposition", n.getDisposition());
            m.put("ctqFlag", n.getCtqFlag());
            m.put("regulatoryFlag", n.getRegulatoryFlag());
            m.put("frozenFlag", n.getFrozenFlag());
            m.put("freezeScope", n.getFreezeScope());
            m.put("reviewDueTime", n.getReviewDueTime());
            boolean reviewOverdue = ST_CREATED.equals(n.getStatus()) && n.getReviewDueTime() != null
                    && n.getReviewDueTime().isBefore(now);
            m.put("reviewOverdue", reviewOverdue);
            m.put("reviewEscalatedFlag", n.getReviewEscalatedFlag());
            m.put("overdueFlag", n.getOverdueFlag());
            m.put("perfFrozenFlag", n.getPerfFrozenFlag());
            m.put("createDate", n.getCreateDate());
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        Ncr ncr = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ncr", ncr);
        if (hasText(ncr.getLotId())) {
            InspectionLot lot = lotDao.selectById(ncr.getLotId());
            out.put("lot", lot);
            if (lot != null) {
                out.put("items", lotItemDao.selectList(new LambdaQueryWrapper<LotItem>()
                        .eq(LotItem::getLotId, lot.getId()).orderByAsc(LotItem::getSequenceNo)));
            }
        }
        if (hasText(ncr.getGrLineId())) {
            out.put("grLine", lineDao.selectById(ncr.getGrLineId()));
        }
        Capa capa = findCapa(ncr);
        if (capa != null) {
            out.put("capa", capa);
        }
        out.put("recheckLots", lotDao.selectList(new LambdaQueryWrapper<InspectionLot>()
                .eq(InspectionLot::getRefType, "NCR")
                .eq(InspectionLot::getRefId, ncr.getId())
                .eq(InspectionLot::getSourceType, "RECHECK")
                .orderByDesc(InspectionLot::getCreateDate)));
        out.put("returns", returnNos(ncr.getId()));
        out.put("concessions", concessionNos(ncr.getId()));
        out.put("logs", logs(id));
        return out;
    }

    // ================= 内部：库存冻结/解冻 =================

    /** AVAILABLE → QC（冻结），返回是否发生转移；位行粒度下按 FIFO 逐位行拆分冻结（A1 适配） */
    private boolean freezeStock(Ncr ncr) {
        List<InvStock> rows = stockRows(ncr.getItemCode(), ncr.getBatchNo());
        if (rows.isEmpty()) {
            return false;
        }
        BigDecimal availTotal = BigDecimal.ZERO;
        for (InvStock r : rows) {
            availTotal = availTotal.add(r.getAvailableQty() == null ? BigDecimal.ZERO : r.getAvailableQty());
        }
        BigDecimal available = ncr.getQty() == null ? availTotal : ncr.getQty().min(availTotal);
        if (available.signum() <= 0) {
            return false;
        }
        BigDecimal remain = available;
        for (InvStock s : rows) {
            if (remain.signum() <= 0) {
                break;
            }
            BigDecimal avail = s.getAvailableQty() == null ? BigDecimal.ZERO : s.getAvailableQty();
            if (avail.signum() <= 0) {
                continue;
            }
            BigDecimal take = avail.min(remain);
            int upd = stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                    .eq(InvStock::getId, s.getId())
                    .eq(InvStock::getVerNo, s.getVerNo())
                    .setSql("AVAILABLE_QTY = AVAILABLE_QTY - " + take.toPlainString())
                    .setSql("QC_QTY = QC_QTY + " + take.toPlainString()));
            if (upd == 0) {
                throw new ServiceException(409, "库存冻结并发冲突，请重试：" + ncr.getItemCode());
            }
            remain = remain.subtract(take);
        }
        // 台账归集（spec ncr-management MODIFIED，SOURCE=NCR 即时生效）
        freezeService.recordNcrFreeze(rows.get(0).getWarehouseCode(), ncr.getItemCode(),
                ncr.getBatchNo(), available, ncr.getId());
        return true;
    }

    /** QC → AVAILABLE（解冻还原，仅非退货分支；退货由出库扣减）——位行粒度下逐位行回补（A1 适配） */
    private void unfreezeStock(Ncr ncr) {
        List<InvStock> rows = stockRows(ncr.getItemCode(), ncr.getBatchNo());
        String wh = rows.isEmpty() || rows.get(0).getWarehouseCode() == null
                ? InvStock.DEFAULT_WH : rows.get(0).getWarehouseCode();
        // NCR 台账归账（任意出口统一释放、幂等——仅动本 NCR 自有 ACTIVE 行；
        // 退货分支 QC 由 2.6.1 出库扣减，台账在此归零，spec ncr-management MODIFIED）
        freezeService.recordNcrUnfreeze(wh, ncr.getItemCode(), ncr.getBatchNo(),
                ncr.getQty(), ncr.getId());
        if (D_RETURN.equals(ncr.getDisposition())) {
            return;
        }
        BigDecimal qcTotal = BigDecimal.ZERO;
        for (InvStock r : rows) {
            qcTotal = qcTotal.add(r.getQcQty() == null ? BigDecimal.ZERO : r.getQcQty());
        }
        if (qcTotal.signum() <= 0) {
            return;
        }
        BigDecimal back = ncr.getQty() == null ? qcTotal : ncr.getQty();
        if (qcTotal.compareTo(back) < 0) {
            back = qcTotal;
        }
        BigDecimal remain = back;
        for (InvStock s : rows) {
            if (remain.signum() <= 0) {
                break;
            }
            BigDecimal held = s.getQcQty() == null ? BigDecimal.ZERO : s.getQcQty();
            if (held.signum() <= 0) {
                continue;
            }
            BigDecimal take = held.min(remain);
            stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                    .eq(InvStock::getId, s.getId())
                    .setSql("QC_QTY = QC_QTY - " + take.toPlainString())
                    .setSql("AVAILABLE_QTY = AVAILABLE_QTY + " + take.toPlainString()));
            remain = remain.subtract(take);
        }
    }

    private void unfreezeAll(Ncr ncr) {
        unfreezeStock(ncr);
        if (hasText(ncr.getGrLineId())) {
            GoodsReceiptLine line = lineDao.selectById(ncr.getGrLineId());
            if (line != null && "FROZEN".equals(line.getQcStatus())) {
                String disp = ncr.getDisposition();
                if (D_SORT.equals(disp) || D_REWORK.equals(disp)) {
                    // 复检放行时已驱动；此处兜底恢复可过账
                    line.setQcStatus("RELEASED");
                    lineDao.updateById(line);
                }
                // RETURN / CONCESSION 分支由退货出库（2.6.1）/ 让步过账（2.5.2）驱动
            }
        }
    }

    private String unfreezeDesc(Ncr ncr) {
        StringBuilder sb = new StringBuilder();
        if ("STOCK".equals(ncr.getFreezeScope()) || "BOTH".equals(ncr.getFreezeScope())) {
            sb.append("库存 QC→AVAILABLE 还原");
        }
        if (D_RETURN.equals(ncr.getDisposition())) {
            sb.append("（退货分支库存保持锁定，待 2.6.1 出库扣减）");
        }
        if (hasText(ncr.getGrLineId())) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("GR 行冻结解除");
        }
        return sb.length() == 0 ? "标记位清零" : sb.toString();
    }

    /** 批次全部位行（inbound → bin 序；位行粒度下同批次可多行，A1 适配） */
    private List<InvStock> stockRows(String itemCode, String batchNo) {
        if (!hasText(itemCode)) {
            return List.of();
        }
        return stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, InvStock.DEFAULT_WH)
                .eq(InvStock::getItemCode, itemCode)
                .eq(InvStock::getBatchNo, batchNo == null ? "" : batchNo)
                .orderByAsc(InvStock::getInboundDate)
                .orderByAsc(InvStock::getBinCode));
    }

    // ================= 内部工具 =================

    private Ncr require(String id) {
        Ncr ncr = ncrDao.selectById(id);
        if (ncr == null) {
            throw new ServiceException(404, "NCR 不存在：" + id);
        }
        return ncr;
    }

    private void updateNcr(Ncr ncr) {
        if (ncrDao.updateById(ncr) == 0) {
            throw new ServiceException(409, "NCR 状态更新冲突，请刷新重试：" + ncr.getNcrNo());
        }
    }

    private Capa findCapa(Ncr ncr) {
        if (hasText(ncr.getCapaId())) {
            Capa c = capaDao.selectById(ncr.getCapaId());
            if (c != null) {
                return c;
            }
        }
        return capaDao.selectOne(new LambdaQueryWrapper<Capa>()
                .eq(Capa::getNcrId, ncr.getId())
                .orderByDesc(Capa::getCreateDate)
                .last("LIMIT 1"));
    }

    private long returnCount(String ncrId) {
        // 退货表由 2.6.1（组 8）服务化，此处经 NcrDao 表级直查（040 已建表）
        Long c = ncrDao.countReturnByNcr(ncrId);
        return c == null ? 0 : c;
    }

    private List<String> returnNos(String ncrId) {
        List<String> nos = ncrDao.selectReturnNosByNcr(ncrId);
        return nos == null ? List.of() : nos;
    }

    private List<String> concessionNos(String ncrId) {
        return concessionDao.selectList(new LambdaQueryWrapper<Concession>()
                        .eq(Concession::getNcrId, ncrId)
                        .orderByDesc(Concession::getCreateDate)).stream()
                .map(Concession::getConcessionNo).toList();
    }

    private void writeLog(Ncr ncr, String action, String from, String to, String detail,
                          String notifyRole, String ccRole) {
        NcrLog l = new NcrLog();
        l.setNcrId(ncr.getId());
        l.setNcrNo(ncr.getNcrNo());
        l.setAction(action);
        l.setFromStatus(from);
        l.setToStatus(to);
        l.setDetail(detail);
        l.setNotifyRole(notifyRole);
        l.setCcRole(ccRole);
        String userId = SecurityUtils.getCurrentUserId();
        l.setOperator(userId);
        l.setCreateBy(userId);
        l.setCreateDate(LocalDateTime.now());
        logDao.insert(l);
    }

    private String nextNcrNo() {
        String prefix = "NCR" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = ncrDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private int hoursOf(String severity) {
        return switch (severity == null ? "MAJOR" : severity) {
            case "CRITICAL" -> hoursCritical;
            case "MINOR" -> hoursMinor;
            default -> hoursMajor;
        };
    }

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

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
