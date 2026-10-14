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
    private final com.erp.service.system.NoticeService noticeService;
    /** 报工来源在制冻结解冻回调（5.7；@Lazy 断环：MoFreezeService → NcrService → 本服务） */
    private final com.erp.service.mrp.MoFreezeService moFreezeService;
    /** 审批底座（CTQ 处置审批 NcrDisposition；@Lazy 断环：ApprovalServiceImpl →
     *  ConcessionCallback/NcrDispositionCallback → NcrService → ApprovalEngine） */
    private final com.erp.service.approval.ApprovalEngine approvalEngine;
    /** 退货单 DAO（6.5.2 处置阶段推导批量反查） */
    private final com.erp.dao.proc.ReturnOrderDao returnOrderDao;
    /** 通知 DAO（重复 NCR 待办去重锚，rework-ncr-control 4.2） */
    private final com.erp.dao.system.SysNoticeDao noticeDao;
    /** 追溯单 DAO + 追溯服务（漏检召回评估双向关联，rework-ncr-control 6.1/6.2） */
    private final com.erp.dao.inv.TraceOrderDao traceOrderDao;
    private final com.erp.service.inv.TraceService traceService;

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
                          @Lazy com.erp.service.inv.FreezeService freezeService,
                          @Lazy com.erp.service.mrp.MoFreezeService moFreezeService,
                          @Lazy com.erp.service.approval.ApprovalEngine approvalEngine,
                          com.erp.dao.proc.ReturnOrderDao returnOrderDao,
                          com.erp.dao.system.SysNoticeDao noticeDao,
                          com.erp.dao.inv.TraceOrderDao traceOrderDao,
                          @Lazy com.erp.service.inv.TraceService traceService,
                          com.erp.service.system.NoticeService noticeService) {
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
        this.moFreezeService = moFreezeService;
        this.approvalEngine = approvalEngine;
        this.returnOrderDao = returnOrderDao;
        this.noticeDao = noticeDao;
        this.traceOrderDao = traceOrderDao;
        this.traceService = traceService;
        this.noticeService = noticeService;
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

        // ---- 二次 NCR 上调（rework-ncr-control 4.1，spec 重复 NCR 升级链①/C-4.12-16）----
        // 复检仍不合格（复检链 refType=NCR，或同批已有历史 NCR）→ 严重度封顶上调一级
        Ncr priorNcr = findPriorNcr(lot);
        if (priorNcr != null) {
            String up = severityUpOne(severity);
            if (!up.equals(severity)) {
                gradeBasis = gradeBasis + "；复检/重复不合格→上调 " + severity + "→" + up
                        + "（C-4.12-16，前单 " + priorNcr.getNcrNo() + "）";
                severity = up;
            } else {
                gradeBasis = gradeBasis + "；复检/重复不合格（已达 Critical 封顶，前单 "
                        + priorNcr.getNcrNo() + "）";
            }
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
        // 二次不合格关联前单（rework-ncr-control 4.1，spec 重复 NCR 升级链①）
        ncr.setRefNcrId(priorNcr == null ? null : priorNcr.getId());
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
        // 先落库取 ID（freezeStock 的台账归集与计划通知需 ncr.getId() 关联，rework-qualified-release 修复）
        ncr.setFreezeScope("PENDING");
        ncrDao.insert(ncr);

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
        ncrDao.updateById(ncr);

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
        // 处置通知（FR-4.12-3-2 ④，rework-qualified-release）：同事务发质量工程师，评审时效起算锚点；
        // 异常降级告警不回滚 NCR（spec：通知失败不回滚 NCR）
        try {
            noticeService.push("ROLE_QUALITY_ENG", null,
                    "NCR 待处置：" + ncr.getNcrNo(),
                    "严重度 " + severity + "，物料 " + ncr.getItemCode() + "（批号 "
                            + (ncr.getBatchNo() == null ? "-" : ncr.getBatchNo()) + "），不合格项："
                            + (ncr.getDefectItem() == null ? "-" : ncr.getDefectItem())
                            + (priorNcr != null ? "；重复不合格，关联前单 " + priorNcr.getNcrNo()
                                    + "，严重度已上调" : "")
                            + "；请于 " + ncr.getReviewDueTime() + " 前完成评审（48h 常规/4h 紧急）",
                    "QMS_NCR", ncr.getId());
        } catch (Exception e) {
            log.warn("NCR 处置通知发送失败（降级不回滚，ncr={}）：{}", ncr.getNcrNo(), e.getMessage());
        }
        log.info("NCR {} created: lot={} severity={} scope={} ({}), capa={}", ncr.getNcrNo(), lot.getLotNo(),
                severity, scope, gradeBasis, capa == null ? "-" : capa.getCapaNo());
        return ncr;
    }

    // ================= 5.7 报工来源 NCR（add-reporting-management design D6） =================

    @Override
    @Transactional
    public Ncr createForWorkOrder(String moId, Integer opSeq, String refId, String itemCode,
                                  String itemName, BigDecimal ngQty, String disposition,
                                  String severity, String defectDesc) {
        // 幂等：同 refId 已有未作废 NCR 返回既有（报工重复触发/并发防重）
        Ncr existed = ncrDao.selectOne(new LambdaQueryWrapper<Ncr>()
                .eq(Ncr::getRefType, "WORKORDER")
                .eq(Ncr::getRefId, refId)
                .notIn(Ncr::getStatus, ST_CANCELLED)
                .last("LIMIT 1"));
        if (existed != null) {
            return existed;
        }
        String sev = severity == null ? "MAJOR" : severity;
        Ncr ncr = new Ncr();
        ncr.setNcrNo(nextNcrNo());
        ncr.setRefType("WORKORDER");
        ncr.setRefId(refId);
        ncr.setItemCode(itemCode);
        ncr.setItemName(itemName);
        ncr.setQty(ngQty == null ? BigDecimal.ZERO : ngQty);
        ncr.setSeverity(sev);
        ncr.setDefectItem("报工不合格（工序 " + opSeq + "）");
        ncr.setDefectDesc(defectDesc);
        ncr.setCtqFlag("0");
        ncr.setRegulatoryFlag("0");
        ncr.setStatus(ST_CREATED);
        if (hasText(disposition)) {
            ncr.setDisposition(disposition);
        }
        // 冻结语义 = WORKORDER：挡派工/完工，不写库存冻结行（偏差 D5）
        ncr.setFreezeScope("WORKORDER");
        ncr.setFrozenFlag("1");
        ncr.setReviewEscalatedFlag("0");
        ncr.setReviewRemindCount(0);
        ncr.setOverdueRemindCount(0);
        ncr.setOverdueFlag("0");
        ncr.setEscalatedFlag("0");
        ncr.setPerfFrozenFlag("0");
        ncr.setReviewDueTime(LocalDateTime.now().plusHours(hoursOf(sev)));
        ncr.setCreateBy(SecurityUtils.getCurrentUserId());
        ncrDao.insert(ncr);
        writeLog(ncr, "CREATE", null, ST_CREATED,
                "报工来源 NCR 生成（refType=WORKORDER）：" + defectDesc
                        + "；冻结=WORKORDER（挡派工/完工，不冻库存）", null, null);
        log.info("NCR {} created: workorder ref={} severity={} ng={}",
                ncr.getNcrNo(), refId, sev, ncr.getQty());
        return ncr;
    }

    // ================= 6.2 评审（必选处置） =================    @Override
    @Transactional
    public Ncr review(String id, String disposition, String opinion) {
        requireRole("评审 NCR", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Ncr ncr = require(id);
        if (!ST_CREATED.equals(ncr.getStatus()) && !"REVIEWING".equals(ncr.getStatus())) {
            throw new ServiceException(422, "当前状态不可评审：" + ncr.getStatus());
        }
        if ("1".equals(ncr.getDispoPendingFlag())) {
            throw new ServiceException(422, "CTQ 处置审批中，不可重复提交评审（可在审批中心撤回或等待审批结果）");
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

        // CTQ 处置审批（spec CTQ 处置质量经理审批，FR-4.12-4-1 要点④）：
        // 提交不流转——评审暂存 + DISPO_PENDING_FLAG='1' + 审批底座单节点质量经理
        if ("1".equals(ncr.getCtqFlag())) {
            ncr.setDisposition(disp);
            ncr.setReviewBy(SecurityUtils.getCurrentUserId());
            ncr.setReviewTime(LocalDateTime.now());
            ncr.setReviewOpinion(opinion);
            ncr.setDispoPendingFlag("1");
            updateNcr(ncr);
            writeLog(ncr, "REVIEW_SUBMIT", ncr.getStatus(), ncr.getStatus(),
                    "CTQ 处置提交审批：处置=" + disp + "；意见：" + opinion + "（待质量经理审批）",
                    "ROLE_QUALITY_MGR", null);
            try {
                approvalEngine.submit("NcrDisposition", ncr.getId(),
                        "CTQ 处置审批：" + ncr.getNcrNo() + "（" + disp + "）",
                        "ROLE_QUALITY_DIRECTOR",
                        List.of(List.of(com.erp.service.approval.ApprovalNodeSpec
                                .sign("ROLE_QUALITY_MGR", "质量经理"))));
            } catch (RuntimeException ex) {
                // 提交审批失败回滚暂存标记（同事务，抛出即整体回滚）
                throw ex;
            }
            return ncr;
        }
        return applyReviewTransition(ncr, disp, opinion, "REVIEW");
    }

    /**
     * 评审流转段（非 CTQ 直接走 / CTQ 审批通过回调复用）：
     * 状态按处置转入 + 退货单/SCAR 自动带出。
     */
    Ncr applyReviewTransition(Ncr ncr, String disp, String opinion, String logAction) {
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
        // CTQ 审批通过回调场景：评审人/时间已在提交时暂存，不被审批人覆盖
        if (ncr.getReviewTime() == null) {
            ncr.setReviewBy(SecurityUtils.getCurrentUserId());
            ncr.setReviewTime(LocalDateTime.now());
            ncr.setReviewOpinion(opinion);
        }
        // updateById 忽略 null 字段，pending 清除须显式 set（驳回同理）
        ncrDao.update(null, new LambdaUpdateWrapper<Ncr>()
                .eq(Ncr::getId, ncr.getId())
                .set(Ncr::getStatus, to)
                .set(Ncr::getDisposition, disp)
                .set(Ncr::getReviewBy, ncr.getReviewBy())
                .set(Ncr::getReviewTime, ncr.getReviewTime())
                .set(Ncr::getReviewOpinion, ncr.getReviewOpinion())
                .set(Ncr::getDispoPendingFlag, null));
        writeLog(ncr, logAction, from, to, "评审选定处置=" + disp + "；意见：" + opinion, null, null);
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

    @Override
    @Transactional
    public Ncr applyDispositionApproval(String ncrId, String approvalOpinion) {
        Ncr ncr = require(ncrId);
        if (!"1".equals(ncr.getDispoPendingFlag())) {
            return ncr; // 幂等：非待审批态跳过
        }
        if (!ST_CREATED.equals(ncr.getStatus()) && !"REVIEWING".equals(ncr.getStatus())) {
            throw new ServiceException(422, "CTQ 审批通过但 NCR 状态不可流转：" + ncr.getStatus());
        }
        String disp = ncr.getDisposition();
        if (!List.of(D_RETURN, D_SORT, D_REWORK, D_CONCESSION, D_SCRAP).contains(disp)) {
            throw new ServiceException(422, "CTQ 审批通过但处置方式缺失：" + disp);
        }
        Ncr out = applyReviewTransition(ncr, disp, ncr.getReviewOpinion(), "REVIEW");
        writeLog(ncr, "REVIEW_APPROVE", null, null,
                "CTQ 处置审批通过：" + (approvalOpinion == null ? "" : approvalOpinion),
                null, null);
        return out;
    }

    @Override
    @Transactional
    public Ncr rejectDispositionApproval(String ncrId, String reason) {
        Ncr ncr = require(ncrId);
        if (!"1".equals(ncr.getDispoPendingFlag())) {
            return ncr; // 幂等
        }
        String from = ncr.getStatus();
        // null 字段须显式 set（updateById 忽略 null）
        ncrDao.update(null, new LambdaUpdateWrapper<Ncr>()
                .eq(Ncr::getId, ncr.getId())
                .set(Ncr::getDispoPendingFlag, null)
                .set(Ncr::getDisposition, null));
        ncr.setDispoPendingFlag(null);
        ncr.setDisposition(null);
        writeLog(ncr, "REVIEW_REJECT", from, from,
                "CTQ 处置审批驳回，可改选处置重新提交：" + (reason == null ? "" : reason), null, null);
        return ncr;
    }

    // ================= 6.3 处置方案录入 =================

    @Override
    @Transactional
    public Ncr planDisposition(String id, String plan) {
        return planDisposition(id, plan, null, null, null, null);
    }

    @Override
    @Transactional
    public Ncr planDisposition(String id, String plan, String reworkSteps, String reworkParams,
                               String reworkCheckReq, Boolean ctqConfirmed) {
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
        // 返工方案结构化（spec MODIFIED 处置执行确认：三字段必填 + CTQ 全含勾选）
        if (D_REWORK.equals(ncr.getDisposition())) {
            List<String> miss = new ArrayList<>();
            if (!hasText(reworkSteps)) {
                miss.add("返工工序步骤");
            }
            if (!hasText(reworkParams)) {
                miss.add("关键工艺参数");
            }
            if (!hasText(reworkCheckReq)) {
                miss.add("返工后检验要求（至少含原检验标准全部 CTQ 项）");
            }
            if (!miss.isEmpty()) {
                throw new ServiceException(422, "返工方案结构化字段必填：" + String.join("、", miss));
            }
            if (!Boolean.TRUE.equals(ctqConfirmed)) {
                throw new ServiceException(422, "须勾选确认「返工检验标准含原 CTQ 全部项」");
            }
            ncr.setReworkSteps(reworkSteps);
            ncr.setReworkParams(reworkParams);
            ncr.setReworkCheckReq(reworkCheckReq);
            ncr.setReworkCtqConfirmed("1");
        }
        ncr.setDisposePlan(plan);
        updateNcr(ncr);
        writeLog(ncr, "DISPOSE", ncr.getStatus(), ncr.getStatus(),
                (D_SORT.equals(ncr.getDisposition()) ? "挑选" : "返工") + "方案：" + plan
                        + (D_REWORK.equals(ncr.getDisposition())
                        ? "；返工工序：" + reworkSteps + "；CTQ 全含已确认" : ""), null, null);
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
        Map<String, Object> out = confirmDisposed(id, result, costAmount, null, null, null, null);
        return (Ncr) out.get("ncr");
    }

    @Override
    @Transactional
    public Map<String, Object> confirmDisposed(String id, String result, java.math.BigDecimal costAmount,
                                               java.math.BigDecimal sortTotal, java.math.BigDecimal sortOk,
                                               java.math.BigDecimal sortNg, java.math.BigDecimal sortHours) {
        requireRole("确认处置执行", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Ncr ncr = require(id);
        String disp = ncr.getDisposition();
        List<String> missing = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
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
                // 挑选结果结构化（spec MODIFIED 处置执行确认）：四字段必填 + 合计校验
                if (sortTotal == null || sortOk == null || sortNg == null || sortHours == null) {
                    missing.add("挑选结果结构化字段必填（总数量/合格数量/不合格数量/挑选工时）");
                } else {
                    if (sortTotal.signum() <= 0) {
                        missing.add("挑选总数量必须大于 0");
                    }
                    if (sortOk.signum() < 0 || sortNg.signum() < 0) {
                        missing.add("挑选合格/不合格数量不可为负");
                    }
                    if (sortHours.signum() < 0) {
                        missing.add("挑选工时不可为负");
                    }
                    if (missing.isEmpty() && sortOk.add(sortNg).compareTo(sortTotal) != 0) {
                        missing.add("挑选数量不平：合格 " + sortOk + " + 不合格 " + sortNg
                                + " ≠ 总数量 " + sortTotal);
                    }
                    // 低合格率软提示（规格 2b「建议转退货」，不阻断）
                    if (missing.isEmpty() && sortTotal.signum() > 0
                            && sortOk.divide(sortTotal, 4, java.math.RoundingMode.HALF_UP)
                                    .compareTo(new BigDecimal("0.5")) < 0) {
                        warnings.add("挑选合格率低于 50%，建议转退货处置（规格 2b 建议口径，不阻断）");
                    }
                }
            }
            case D_REWORK -> {
                if (!hasText(ncr.getDisposePlan())) {
                    missing.add("返工方案未录入");
                }
                if (!hasText(ncr.getReworkSteps()) || !hasText(ncr.getReworkCheckReq())
                        || !"1".equals(ncr.getReworkCtqConfirmed())) {
                    missing.add("返工方案结构化未完成（工序/检验要求/CTQ 全含勾选，见方案录入）");
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
        // 挑选结构化结果 + 成本落库（spec MODIFIED 处置执行确认）
        if (D_SORT.equals(disp) && sortTotal != null) {
            ncr.setSortTotalQty(sortTotal);
            ncr.setSortOkQty(sortOk);
            ncr.setSortNgQty(sortNg);
            ncr.setSortHours(sortHours);
        }
        if (costAmount != null) {
            ncr.setDisposeCostAmount(costAmount);
        }
        updateNcr(ncr);
        writeLog(ncr, "CONFIRM", from, ST_DISPOSED, "处置执行确认：" + result
                + (D_SORT.equals(disp) && sortTotal != null
                ? "；挑选结果 总" + sortTotal + "/合格" + sortOk + "/不合格" + sortNg
                        + "/工时" + sortHours + "h" : "")
                + (costAmount != null && costAmount.signum() > 0 ? "；成本 " + costAmount + " 元" : ""),
                null, null);
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ncr", ncr);
        out.put("warnings", warnings);
        return out;
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
        // 报工来源：作废 = 判定推翻 → 同样解在制冻结
        if ("WORKORDER".equals(ncr.getRefType()) && hasText(ncr.getRefId())) {
            moFreezeService.releaseForNcr(ncr.getRefId().split("#", 2)[0], ncr.getId(),
                    "NCR 作废 " + ncr.getNcrNo());
        }
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

    // ================= 重复 NCR 升级链扫描（rework-ncr-control 4.2，spec C-4.12-16） =================

    @Override
    public int sweepRepeatNcrCapaTodos() {
        LocalDateTime since = LocalDateTime.now().minusDays(90);
        List<Ncr> recent = ncrDao.selectList(new LambdaQueryWrapper<Ncr>()
                .ge(Ncr::getCreateDate, since)
                .notIn(Ncr::getStatus, ST_CANCELLED)
                .last("LIMIT 500"));
        if (recent.size() < 3) {
            return 0;
        }
        // 三维度分组：物料 / 供应商 / 工序（报工来源 refType=WORKORDER 的 moId#opSeq 段）
        Map<String, List<Ncr>> byItem = new LinkedHashMap<>();
        Map<String, List<Ncr>> bySupplier = new LinkedHashMap<>();
        Map<String, List<Ncr>> byProcess = new LinkedHashMap<>();
        for (Ncr n : recent) {
            if (hasText(n.getItemCode())) {
                byItem.computeIfAbsent(n.getItemCode(), k -> new ArrayList<>()).add(n);
            }
            if (hasText(n.getSupplierId())) {
                bySupplier.computeIfAbsent(n.getSupplierId(), k -> new ArrayList<>()).add(n);
            }
            if ("WORKORDER".equals(n.getRefType()) && hasText(n.getRefId())) {
                // refId = moId#opSeq#rptNo → 工序键取 moId#opSeq
                String ref = n.getRefId();
                int second = ref.indexOf('#', ref.indexOf('#') + 1);
                String opKey = second > 0 ? ref.substring(0, second) : ref;
                byProcess.computeIfAbsent(opKey, k -> new ArrayList<>()).add(n);
            }
        }
        int handled = 0;
        String monthKey = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        handled += fireRepeatCapaTodo("MATERIAL", byItem, monthKey, false);
        handled += fireRepeatCapaTodo("SUPPLIER", bySupplier, monthKey, true);
        handled += fireRepeatCapaTodo("PROCESS", byProcess, monthKey, false);
        return handled;
    }

    /**
     * 单维度触发：count≥3 → 查在途 CAPA（有则跳过）→ 查窗口去重锚（已有待办跳过）
     * → 推 CAPA 立项待办（质量经理必达，供应商维度加 SQE）。
     * 待办正文携带：维度计数明细 + 全部 NCR 单号 + 严重度上调一级建议（C-4.12-16）。
     */
    private int fireRepeatCapaTodo(String dimType, Map<String, List<Ncr>> dims,
                                   String monthKey, boolean alsoSqe) {
        int fired = 0;
        for (Map.Entry<String, List<Ncr>> e : dims.entrySet()) {
            List<Ncr> group = e.getValue();
            if (group.size() < 3) {
                continue;
            }
            String dimKey = dimType + ":" + e.getKey();
            String bizId = "CAPA_TODO:" + dimKey + ":" + monthKey;
            // 幂等锚①：同维度键+月窗口已有待办 → 跳过
            Long pushed = noticeDao.selectCount(new LambdaQueryWrapper<com.erp.entity.system.SysNotice>()
                    .eq(com.erp.entity.system.SysNotice::getBizType, "QMS_CAPA_REPEAT")
                    .eq(com.erp.entity.system.SysNotice::getBizId, bizId));
            if (pushed != null && pushed > 0) {
                continue;
            }
            // 幂等锚②：组内任一 NCR 存在未关闭 CAPA → 跳过（在途 CAPA 已覆盖）
            List<String> ids = group.stream().map(Ncr::getId).toList();
            Long openCapa = capaDao.selectCount(new LambdaQueryWrapper<Capa>()
                    .in(Capa::getNcrId, ids)
                    .notIn(Capa::getStatus, "CLOSED"));
            if (openCapa != null && openCapa > 0) {
                continue;
            }
            // 严重度上调一级建议：组内最高严重度 → 上调（封顶 Critical）
            String maxSev = "MINOR";
            for (Ncr n : group) {
                if ("CRITICAL".equals(n.getSeverity())) {
                    maxSev = "CRITICAL";
                    break;
                }
                if ("MAJOR".equals(n.getSeverity())) {
                    maxSev = "MAJOR";
                }
            }
            String uplifted = severityUpOne(maxSev);
            String ncrNos = group.stream().map(Ncr::getNcrNo).sorted().reduce((a, b) -> a + "、" + b)
                    .orElse("");
            String title = "CAPA 立项待办：" + dimType + " " + e.getKey()
                    + " 近 3 个月 NCR " + group.size() + " 张";
            String content = "重复不合格触发强制 CAPA 立项（C-4.12-16）：维度 " + dimKey
                    + "，滚动 3 个月累计 NCR " + group.size() + " 张（" + ncrNos + "）。"
                    + "建议 CAPA 严重度 " + uplifted + "（组内最高 " + maxSev + " 上调一级，封顶 Critical）。"
                    + "请质量工程师完成 CAPA 立项并关联上述历史 NCR——本待办不代建 CAPA 单。";
            try {
                noticeService.push("ROLE_QUALITY_MGR", null, title, content,
                        "QMS_CAPA_REPEAT", bizId);
                if (alsoSqe) {
                    noticeService.push("ROLE_SQE", null, title, content,
                            "QMS_CAPA_REPEAT", bizId);
                }
                fired++;
                log.info("repeat NCR CAPA todo fired: {} (count={}, sev {}→{})",
                        dimKey, group.size(), maxSev, uplifted);
            } catch (Exception ex) {
                log.warn("repeat NCR CAPA todo push failed（降级不回滚，dim={}）：{}",
                        dimKey, ex.getMessage());
            }
        }
        return fired;
    }

    // ================= 漏检召回评估（rework-ncr-control 6.1/6.2，spec 6753①） =================

    @Override
    @Transactional
    public Ncr createForReleasedLot(String lotId, String defectItem, String defectDesc,
                                    String severity, String ctqFlag) {
        requireRole("创建放行后发现 NCR", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        InspectionLot lot = hasText(lotId) ? lotDao.selectById(lotId) : null;
        if (lot == null) {
            throw new ServiceException(404, "检验批不存在：" + lotId);
        }
        if (!"RELEASED".equals(lot.getStatus())) {
            throw new ServiceException(422, "该批次未放行，请走检验判定自动建单（当前状态 "
                    + lot.getStatus() + "）");
        }
        // 幂等：该批存在未关闭的手工 NCR → 422 返回既有单号（spec 幂等拒绝重复创建）
        Ncr existed = ncrDao.selectOne(new LambdaQueryWrapper<Ncr>()
                .eq(Ncr::getRefType, "RELEASED_LOT")
                .eq(Ncr::getRefId, lotId)
                .notIn(Ncr::getStatus, ST_CLOSED, ST_CANCELLED)
                .last("LIMIT 1"));
        if (existed != null) {
            throw new ServiceException(422, "该批次已存在未关闭的手工 NCR：" + existed.getNcrNo());
        }
        String sev = hasText(severity) ? severity.trim().toUpperCase() : "MAJOR";
        if (!List.of("CRITICAL", "MAJOR", "MINOR").contains(sev)) {
            sev = "MAJOR";
        }
        boolean ctq = "1".equals(ctqFlag);
        String markedDesc = (defectDesc == null ? "" : defectDesc.trim());
        if (!markedDesc.contains("放行后发现")) {
            markedDesc = "【放行后发现】" + (markedDesc.isEmpty() ? "放行后漏检/误判" : markedDesc);
        }

        Ncr ncr = new Ncr();
        ncr.setNcrNo(nextNcrNo());
        ncr.setRefType("RELEASED_LOT");
        ncr.setRefId(lotId);
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
        ncr.setSeverity(sev);
        ncr.setDefectItem(hasText(defectItem) ? defectItem.trim() : "放行后发现不合格");
        ncr.setDefectDesc(markedDesc);
        ncr.setCtqFlag(ctq ? "1" : "0");
        ncr.setRegulatoryFlag("0");
        ncr.setStatus(ST_CREATED);
        ncr.setFrozenFlag("1");
        ncr.setReviewEscalatedFlag("0");
        ncr.setReviewRemindCount(0);
        ncr.setOverdueRemindCount(0);
        ncr.setOverdueFlag("0");
        ncr.setEscalatedFlag("0");
        ncr.setPerfFrozenFlag("0");
        ncr.setReviewDueTime(LocalDateTime.now().plusHours(hoursOf(sev)));
        ncr.setCreateBy(SecurityUtils.getCurrentUserId());
        // 先落库取 ID（freezeStock 台账归集需 ncr.getId()，同 createFromLot 修复口径）
        ncr.setFreezeScope("PENDING");
        ncrDao.insert(ncr);

        // 冻结：复用 createFromLot 两阶段降级路径（POSTED→冻库存 / 未过账→GR 行 / 无库存→仅标记）
        GoodsReceiptLine line = lot.getGrLineId() == null ? null : lineDao.selectById(lot.getGrLineId());
        boolean frozenStock = false;
        String scope;
        if (line != null && "POSTED".equals(line.getStatus())) {
            frozenStock = freezeStock(ncr);
            scope = "STOCK";
        } else if (line != null) {
            scope = "GR_LINE";
        } else {
            frozenStock = freezeStock(ncr);
            scope = frozenStock ? "STOCK" : "NONE";
        }
        ncr.setFreezeScope(scope);
        updateNcr(ncr);
        if (line != null) {
            line.setQcStatus("FROZEN");
            lineDao.updateById(line);
        }

        writeLog(ncr, "CREATE", null, ST_CREATED,
                "手工创建（放行后发现）：" + markedDesc + "；批次 " + lot.getLotNo()
                        + " 冻结=" + scope + (frozenStock ? "（库存 AVAILABLE→QC）" : ""),
                null, null);
        log.info("NCR {} created: released-lot={} sev={} scope={} (manual, missed-defect)",
                ncr.getNcrNo(), lotId, sev, scope);
        return ncr;
    }

    @Override
    @Transactional
    public Map<String, Object> recallEvaluation(String ncrId) {
        requireRole("发起召回评估", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Ncr ncr = require(ncrId);
        if (!"RELEASED_LOT".equals(ncr.getRefType())) {
            throw new ServiceException(422, "仅「放行后发现」的 NCR 可发起召回评估（refType=RELEASED_LOT）");
        }
        InspectionLot lot = hasText(ncr.getLotId()) ? lotDao.selectById(ncr.getLotId()) : null;
        String batchNo = lot != null && hasText(lot.getBatchNo()) ? lot.getBatchNo() : ncr.getBatchNo();
        if (!hasText(batchNo)) {
            throw new ServiceException(422, "批次号缺失，无法发起召回评估");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // 命中既有未结案追溯单 → 不重复建单（关联后返回既有供跳转）
        com.erp.entity.inv.TraceOrder existing = traceOrderDao.selectOne(
                new LambdaQueryWrapper<com.erp.entity.inv.TraceOrder>()
                        .eq(com.erp.entity.inv.TraceOrder::getBatchNo, batchNo)
                        .ne(com.erp.entity.inv.TraceOrder::getStatus, "CLOSED")
                        .last("LIMIT 1"));
        if (existing != null) {
            if (!hasText(existing.getSourceNcrId())) {
                existing.setSourceNcrId(ncr.getId());
                traceOrderDao.updateById(existing);
            }
            writeLog(ncr, "RECALL_EVAL", ncr.getStatus(), ncr.getStatus(),
                    "召回评估关联既有追溯单：" + existing.getTraceNo() + "（不重复建单）", null, null);
            out.put("traceId", existing.getId());
            out.put("traceNo", existing.getTraceNo());
            out.put("existing", true);
            return out;
        }
        // 发起追溯（批次索引，缺陷描述携带 NCR 号）；analyze 只物化流向，不执行冻结
        String reason = "漏检召回评估：" + ncr.getNcrNo() + "（"
                + (ncr.getDefectItem() == null ? "" : ncr.getDefectItem()) + "）"
                + (ncr.getDefectDesc() == null ? "" : ncr.getDefectDesc());
        Map<String, Object> trace = traceService.analyze("BATCH", batchNo, reason);
        String traceId = String.valueOf(trace.get("id"));
        // 双向关联：追溯单回写来源 NCR
        com.erp.entity.inv.TraceOrder t = traceOrderDao.selectById(traceId);
        if (t != null) {
            t.setSourceNcrId(ncr.getId());
            traceOrderDao.updateById(t);
        }
        writeLog(ncr, "RECALL_EVAL", ncr.getStatus(), ncr.getStatus(),
                "发起召回评估追溯单：" + trace.get("traceNo")
                        + "（评估权留人：冻结等重动作由追溯流程按流向分析决策）", null, null);
        out.put("traceId", traceId);
        out.put("traceNo", trace.get("traceNo"));
        out.put("existing", false);
        return out;
    }

    // ================= 查询 =================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String keyword, String status, String severity) {
        return page(current, size, keyword, status, null, severity, null, null);
    }

    @Override
    public Page<Map<String, Object>> page(long current, long size, String keyword, String status,
                                          String statuses, String severity, Boolean frozenOnly, Boolean withStage) {
        List<String> statusList = null;
        if (hasText(statuses)) {
            statusList = java.util.Arrays.stream(statuses.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        LambdaQueryWrapper<Ncr> qw = new LambdaQueryWrapper<Ncr>()
                .eq(hasText(status), Ncr::getStatus, status)
                .in(statusList != null && !statusList.isEmpty(), Ncr::getStatus, statusList)
                .eq(hasText(severity), Ncr::getSeverity, severity)
                // 6.5.1 隔离清单：冻结中且未作废/未关闭（spec 不合格品隔离确认与标识卡）
                .eq(Boolean.TRUE.equals(frozenOnly), Ncr::getFrozenFlag, "1")
                .ne(Boolean.TRUE.equals(frozenOnly), Ncr::getStatus, ST_CANCELLED)
                .ne(Boolean.TRUE.equals(frozenOnly), Ncr::getStatus, ST_CLOSED)
                .and(hasText(keyword), w -> w.like(Ncr::getNcrNo, keyword)
                        .or().like(Ncr::getItemCode, keyword)
                        .or().like(Ncr::getSupplierName, keyword)
                        .or().like(Ncr::getLotNo, keyword))
                .orderByDesc(Ncr::getCreateDate);
        Page<Ncr> raw = ncrDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        // 6.5.2 处置阶段：批量反查关联单据（IN 批查防 N+1，design D4）
        Map<String, Map<String, Object>> stages = Boolean.TRUE.equals(withStage)
                ? deriveDisposeStages(raw.getRecords()) : Map.of();
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
            m.put("isolatedFlag", n.getIsolatedFlag());
            m.put("isolateBin", n.getIsolateBin());
            m.put("isolatedBy", n.getIsolatedBy());
            m.put("isolatedTime", n.getIsolatedTime());
            m.put("dispoPendingFlag", n.getDispoPendingFlag());
            m.put("reviewDueTime", n.getReviewDueTime());
            boolean reviewOverdue = ST_CREATED.equals(n.getStatus()) && n.getReviewDueTime() != null
                    && n.getReviewDueTime().isBefore(now);
            m.put("reviewOverdue", reviewOverdue);
            m.put("reviewEscalatedFlag", n.getReviewEscalatedFlag());
            m.put("overdueFlag", n.getOverdueFlag());
            m.put("perfFrozenFlag", n.getPerfFrozenFlag());
            m.put("createDate", n.getCreateDate());
            Map<String, Object> st = stages.get(n.getId());
            if (st != null) {
                m.put("disposeStage", st.get("disposeStage"));
                m.put("disposeStageDetail", st.get("disposeStageDetail"));
            }
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    /**
     * 处置阶段推导（spec 处置跟踪多态视图，design D4）：批量反查，实时计算不落库；
     * 反查异常降级为处置方式原文（不影响列表主流程）。
     */
    private Map<String, Map<String, Object>> deriveDisposeStages(List<Ncr> records) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try {
            // CTQ 审批中（DISPO_PENDING_FLAG=1）优先于状态分支——即便状态为 CREATED 也纳入推导
            List<Ncr> inflow = records.stream()
                    .filter(n -> List.of("RETURNING", "SORTING", "REWORKING", "CONCESSION", ST_SCRAPPING)
                            .contains(n.getStatus()) || "1".equals(n.getDispoPendingFlag()))
                    .toList();
            if (inflow.isEmpty()) {
                return out;
            }
            List<String> ids = inflow.stream().map(Ncr::getId).toList();
            // 退货：批量反查退货单出库状态
            Map<String, String> returnStatus = new LinkedHashMap<>();
            if (inflow.stream().anyMatch(n -> D_RETURN.equals(n.getDisposition()))) {
                var roDao = returnOrderDao;
                roDao.selectList(new LambdaQueryWrapper<com.erp.entity.proc.ReturnOrder>()
                                .in(com.erp.entity.proc.ReturnOrder::getNcrId, ids))
                        .forEach(r -> returnStatus.putIfAbsent(r.getNcrId(), r.getStatus()));
            }
            // 让步：批量反查 APPROVED 让步单
            java.util.Set<String> concessionApproved = new java.util.HashSet<>();
            if (inflow.stream().anyMatch(n -> D_CONCESSION.equals(n.getDisposition()))) {
                concessionDao.selectList(new LambdaQueryWrapper<Concession>()
                                .in(Concession::getNcrId, ids)
                                .eq(Concession::getStatus, "APPROVED"))
                        .forEach(c -> concessionApproved.add(c.getNcrId()));
            }
            // 挑选/返工已确认未放行：复检批统计
            Map<String, long[]> recheckStat = new LinkedHashMap<>();
            if (inflow.stream().anyMatch(n -> D_SORT.equals(n.getDisposition())
                    || D_REWORK.equals(n.getDisposition()))) {
                lotDao.selectList(new LambdaQueryWrapper<InspectionLot>()
                                .eq(InspectionLot::getRefType, "NCR")
                                .in(InspectionLot::getRefId, ids)
                                .eq(InspectionLot::getSourceType, "RECHECK"))
                        .forEach(l -> {
                            long[] c = recheckStat.computeIfAbsent(l.getRefId(), k -> new long[2]);
                            c[0]++;
                            if ("RELEASED".equals(l.getStatus())) {
                                c[1]++;
                            }
                        });
            }
            for (Ncr n : inflow) {
                String stage;
                String detail = "";
                if ("1".equals(n.getDispoPendingFlag())) {
                    // CTQ 处置待质量经理审批（spec 阶段标识，优先于状态分支）
                    stage = "处置待质量经理审批";
                    String disp = switch (n.getDisposition() == null ? "" : n.getDisposition()) {
                        case D_RETURN -> "退货";
                        case D_SORT -> "挑选";
                        case D_REWORK -> "返工";
                        case D_CONCESSION -> "让步接收";
                        case D_SCRAP -> "报废";
                        default -> "未选处置";
                    };
                    detail = "CTQ 单评审已提交，审批通过后按所选处置流转（" + disp + "）";
                    Map<String, Object> pst = new LinkedHashMap<>();
                    pst.put("disposeStage", stage);
                    pst.put("disposeStageDetail", detail);
                    out.put(n.getId(), pst);
                    continue;
                }
                switch (n.getStatus()) {
                    case "RETURNING" -> {
                        String rs = returnStatus.get(n.getId());
                        if ("OUT_DONE".equals(rs)) {
                            stage = "退货出库完成待确认";
                        } else {
                            stage = "待退货出库";
                        }
                        detail = "退货单状态：" + (rs == null ? "未生成" : rs);
                    }
                    case "SORTING", "REWORKING" -> {
                        boolean planned = hasText(n.getDisposePlan());
                        long[] c = recheckStat.getOrDefault(n.getId(), new long[2]);
                        if (!planned) {
                            stage = "待方案录入";
                            detail = "请录入" + (D_SORT.equals(n.getDisposition()) ? "挑选" : "返工") + "方案";
                        } else {
                            stage = "待处置确认";
                            detail = "方案已录入，待执行确认";
                        }
                        if (c[1] > 0) {
                            stage = "复检已放行待关闭";
                            detail = "复检批已放行，可关闭 NCR";
                        } else if (c[0] > 0) {
                            stage = "待复检放行";
                            detail = "复检批已生成未放行（" + c[0] + " 批）";
                        }
                    }
                    case "CONCESSION" -> {
                        if (concessionApproved.contains(n.getId())) {
                            stage = "双签完成待确认";
                            detail = "让步双签已批准，待处置确认";
                        } else {
                            stage = "待双签批准";
                            detail = "让步单待质量经理+技术负责人双签（2.5.2）";
                        }
                    }
                    case "SCRAPPING" -> {
                        if (hasText(n.getDisposeResult())) {
                            stage = "待处置确认";
                            detail = "报废单号已回填";
                        } else {
                            stage = "待回填报废单号";
                            detail = "先在 4.5.4 创建报废单并完成会签过账，再回填";
                        }
                    }
                    default -> stage = n.getDisposition();
                }
                Map<String, Object> st = new LinkedHashMap<>();
                st.put("disposeStage", stage);
                st.put("disposeStageDetail", detail);
                out.put(n.getId(), st);
            }
        } catch (Exception ex) {
            log.warn("disposeStage derive failed, fallback to disposition text: {}", ex.getMessage());
            out.clear();
            for (Ncr n : records) {
                if (n.getDisposition() != null) {
                    Map<String, Object> st = new LinkedHashMap<>();
                    st.put("disposeStage", n.getDisposition());
                    st.put("disposeStageDetail", "");
                    out.put(n.getId(), st);
                }
            }
        }
        return out;
    }

    // ================= 6.5.1 隔离确认 =================

    @Override
    @Transactional
    public Ncr isolate(String id, String bin) {
        requireRole("隔离确认", "ROLE_INSPECTOR", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Ncr ncr = require(id);
        if (ST_CLOSED.equals(ncr.getStatus()) || ST_CANCELLED.equals(ncr.getStatus())) {
            throw new ServiceException(422, "已关闭/已作废的 NCR 不可隔离确认：" + ncr.getStatus());
        }
        if (bin == null || bin.trim().isEmpty()) {
            throw new ServiceException(422, "隔离区货位必填");
        }
        String userId = SecurityUtils.getCurrentUserId();
        boolean repeated = "1".equals(ncr.getIsolatedFlag());
        ncr.setIsolatedFlag("1");
        ncr.setIsolateBin(bin.trim());
        ncr.setIsolatedBy(userId);
        ncr.setIsolatedTime(LocalDateTime.now());
        updateNcr(ncr);
        writeLog(ncr, "ISOLATE", ncr.getStatus(), ncr.getStatus(),
                (repeated ? "隔离确认更新" : "隔离确认") + "：货位 " + bin.trim() + "；贴标人 " + userId, null, null);
        return ncr;
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
        // 漏检召回评估关联（rework-ncr-control 6.2：追溯单 SOURCE_NCR_ID 反查）
        if ("RELEASED_LOT".equals(ncr.getRefType())) {
            com.erp.entity.inv.TraceOrder recall = traceOrderDao.selectOne(
                    new LambdaQueryWrapper<com.erp.entity.inv.TraceOrder>()
                            .eq(com.erp.entity.inv.TraceOrder::getSourceNcrId, ncr.getId())
                            .orderByDesc(com.erp.entity.inv.TraceOrder::getCreateDate)
                            .last("LIMIT 1"));
            if (recall != null) {
                out.put("recallTraceId", recall.getId());
                out.put("recallTraceNo", recall.getTraceNo());
                out.put("recallTraceStatus", recall.getStatus());
            }
        }
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
        // 下游计划通知（FR-4.12-3-2 异常①，rework-qualified-release）：受影响计划调整
        try {
            noticeService.push("ROLE_PLANNER", null,
                    "冻结影响计划：" + ncr.getNcrNo(),
                    "物料 " + ncr.getItemCode() + " 批次 " + ncr.getBatchNo() + " 因 NCR "
                            + ncr.getNcrNo() + " 冻结 " + available + "，请调整受影响工单与领用计划",
                    "QMS_NCR_FREEZE", ncr.getId());
        } catch (Exception e) {
            log.warn("NCR 冻结计划通知发送失败（降级不回滚，ncr={}）：{}", ncr.getNcrNo(), e.getMessage());
        }
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
        // 报工来源 NCR（WORKORDER）：在制冻结（挡派工/完工）解冻回调（5.7 spec
        // 「NCR 关闭解冻」；refId=moId#... 取 moId 段）
        if ("WORKORDER".equals(ncr.getRefType()) && hasText(ncr.getRefId())) {
            String moId = ncr.getRefId().split("#", 2)[0];
            moFreezeService.releaseForNcr(moId, ncr.getId(),
                    "NCR 关闭 " + ncr.getNcrNo());
        }
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

    /**
     * 前序 NCR 查找（rework-ncr-control 4.1，spec 重复 NCR 升级链）：
     * ① 复检链——复检批 refType=NCR 直接反查触发单；
     * ② 同批历史——批次号维度最近一张未作废 NCR。
     * 调用时点在本单 insert 之前，不会自匹配。
     */
    private Ncr findPriorNcr(InspectionLot lot) {
        if ("NCR".equals(lot.getRefType()) && hasText(lot.getRefId())) {
            Ncr prior = ncrDao.selectById(lot.getRefId());
            if (prior != null) {
                return prior;
            }
        }
        if (hasText(lot.getBatchNo())) {
            return ncrDao.selectOne(new LambdaQueryWrapper<Ncr>()
                    .eq(Ncr::getBatchNo, lot.getBatchNo())
                    .ne(Ncr::getStatus, ST_CANCELLED)
                    .orderByDesc(Ncr::getCreateDate)
                    .last("LIMIT 1"));
        }
        return null;
    }

    /** 严重度上调一级（C-4.12-16，Minor→Major→Critical 封顶） */
    private String severityUpOne(String severity) {
        if ("MINOR".equals(severity)) {
            return "MAJOR";
        }
        // MAJOR→CRITICAL；CRITICAL 已封顶（返回同值 = 不变）
        return "CRITICAL";
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
