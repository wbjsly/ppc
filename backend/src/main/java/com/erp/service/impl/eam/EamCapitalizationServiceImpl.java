package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamCapitalizationDao;
import com.erp.dao.eam.EamCipCostDao;
import com.erp.dao.eam.EamCipProjectDao;
import com.erp.dao.eam.EamAssetCategoryDao;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamAssetCategory;
import com.erp.entity.eam.EamCapitalization;
import com.erp.entity.eam.EamCipProject;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.service.eam.EamCapitalizationService;
import com.erp.service.fin.GlVoucherService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产转资实现（add-asset-capitalization，spec asset-capitalization）。
 * 双模式申请 → 校验 → 审批单签 → 一物一卡建卡 + 转资凭证（design D3~D6/D8）。
 */
@Slf4j
@Service
public class EamCapitalizationServiceImpl implements EamCapitalizationService {

    /** 一物一卡拆分上限（design D3） */
    private static final int CARD_QTY_LIMIT = 500;

    private final EamCapitalizationDao capDao;
    private final EamAssetCardDao cardDao;
    private final EamAssetCategoryDao categoryDao;
    private final EamCipProjectDao cipProjectDao;
    private final EamCipCostDao cipCostDao;
    private final PurchaseOrderDao poDao;
    private final GoodsReceiptDao grDao;
    private final GoodsReceiptLineDao grLineDao;
    private final FinApInvoiceDao apInvoiceDao;
    private final GlVoucherService voucherService;
    private final com.erp.service.SysParamService sysParamService;
    private final com.erp.service.system.NoticeService noticeService;
    private final com.erp.service.approval.ApprovalEngine approvalEngine;

    public EamCapitalizationServiceImpl(EamCapitalizationDao capDao,
                                        EamAssetCardDao cardDao,
                                        EamAssetCategoryDao categoryDao,
                                        EamCipProjectDao cipProjectDao,
                                        EamCipCostDao cipCostDao,
                                        PurchaseOrderDao poDao,
                                        GoodsReceiptDao grDao,
                                        GoodsReceiptLineDao grLineDao,
                                        FinApInvoiceDao apInvoiceDao,
                                        GlVoucherService voucherService,
                                        com.erp.service.SysParamService sysParamService,
                                        com.erp.service.system.NoticeService noticeService,
                                        com.erp.service.approval.ApprovalEngine approvalEngine) {
        this.capDao = capDao;
        this.cardDao = cardDao;
        this.categoryDao = categoryDao;
        this.cipProjectDao = cipProjectDao;
        this.cipCostDao = cipCostDao;
        this.poDao = poDao;
        this.grDao = grDao;
        this.grLineDao = grLineDao;
        this.apInvoiceDao = apInvoiceDao;
        this.voucherService = voucherService;
        this.sysParamService = sysParamService;
        this.noticeService = noticeService;
        this.approvalEngine = approvalEngine;
    }

    // ================= 分页 / 详情 =================

    @Override
    public Page<EamCapitalization> page(long current, long size, String mode, String status,
                                        String keyword) {
        LambdaQueryWrapper<EamCapitalization> qw = new LambdaQueryWrapper<EamCapitalization>()
                .eq(mode != null && !mode.isEmpty(), EamCapitalization::getMode, mode)
                .eq(status != null && !status.isEmpty(), EamCapitalization::getStatus, status)
                .and(keyword != null && !keyword.isEmpty(), w -> w
                        .like(EamCapitalization::getCapNo, keyword)
                        .or().like(EamCapitalization::getSourceDocNo, keyword)
                        .or().like(EamCapitalization::getAssetName, keyword))
                .orderByDesc(EamCapitalization::getCreateDate);
        return capDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        EamCapitalization cap = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cap", cap);
        out.put("category", categoryDao.selectActiveByCode(cap.getCategoryCode()));
        out.put("preview", previewOf(cap));
        if ("CIP".equals(cap.getMode())) {
            EamCipProject p = cipProjectDao.selectById(cap.getSourceId());
            out.put("cipProject", p);
            out.put("cipCostSummary", cipCostDao.sumByType(cap.getSourceId()));
        } else {
            PurchaseOrder po = poDao.selectById(cap.getSourceId());
            out.put("po", po);
        }
        return out;
    }

    // ================= 创建 / 编辑 =================

    @Override
    @Transactional
    public Map<String, Object> create(EamCapitalization cap) {
        validateBase(cap);
        String mode = cap.getMode().trim().toUpperCase();
        cap.setMode(mode);
        List<String> warnings = new ArrayList<>();

        // ---- 来源校验（FR-4.13-1-1/1-2）----
        LocalDate acceptanceDate;
        if ("PURCHASE".equals(mode)) {
            acceptanceDate = validatePurchaseSource(cap, warnings);
        } else {
            acceptanceDate = validateCipSource(cap, warnings);
        }
        // 重复转资阻断（FR-4.13-1-2）
        if (capDao.countBySource(cap.getSourceId()) > 0) {
            throw new ServiceException(422, "该来源单据已有转资单，不可重复转资");
        }

        // ---- 折旧政策：类别带入 + 调整原因 ----
        EamAssetCategory category = categoryDao.selectActiveByCode(cap.getCategoryCode());
        if (category == null) {
            throw new ServiceException(422, "资产分类未登记或未生效：" + cap.getCategoryCode());
        }
        if (cap.getLifeYears() == null) {
            cap.setLifeYears(category.getDefaultLifeYears());
            cap.setResidualRate(category.getDefaultResidualRate());
            cap.setDepreciationMethod(category.getDefaultDepreciationMethod());
        } else if (!cap.getLifeYears().equals(category.getDefaultLifeYears())
                && !hasText(cap.getAdjustReason())) {
            throw new ServiceException(422, "使用年限偏离分类默认值时须填写调整原因（FR-4.13-1-3）");
        }
        // 年限范围 L4 预警（不阻断）
        if (cap.getLifeYears() < category.getLifeYearsMin()
                || cap.getLifeYears() > category.getLifeYearsMax()) {
            warnings.add("使用年限 " + cap.getLifeYears() + " 年超出分类「" + category.getName()
                    + "」标准范围 " + category.getLifeYearsMin() + "~" + category.getLifeYearsMax()
                    + " 年，请复核（L4 预警）");
        }

        // ---- BR-4.13-09 资本化下限（逐件 L1 阻断）----
        if (cap.getUnitAmount().compareTo(category.getCapitalizationFloor()) < 0) {
            throw new ServiceException(422, "单件原值 " + cap.getUnitAmount() + " 低于分类「"
                    + category.getName() + "」资本化下限 " + category.getCapitalizationFloor()
                    + "，不可固定资产建卡，请改用低值易耗品一次摊销或五五摊销入账（BR-4.13-09）");
        }

        // ---- BR-4.13-08 部分转资强制 ----
        if ("CIP".equals(mode) && cap.getPartialFlag() == null) {
            cap.setPartialFlag("0");
        }

        // ---- 启用日期（BR-4.13-06 = 验收合格日，可改但不可早于来源日期，L1）----
        if (cap.getEffectiveDate() == null) {
            cap.setEffectiveDate(acceptanceDate);
        }
        if ("PURCHASE".equals(mode) && cap.getEffectiveDate().isBefore(acceptanceDate)) {
            throw new ServiceException(422, "启用日期不可早于采购/验收日期（BR-4.13-06 L1 阻断）");
        }

        cap.setId(null);
        cap.setCapNo(nextCapNo());
        cap.setStatus("DRAFT");
        cap.setCreateBy(SecurityUtils.getCurrentUserId());
        capDao.insert(cap);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cap", cap);
        out.put("warnings", warnings);
        return out;
    }

    /**
     * 采购来源校验：入库单已过账 + 质检合格（GR 行 QC_STATUS）；返回验收合格日。
     * 部分合格 → 分批转资：以已合格 GR 的到货日为验收日。
     */
    private LocalDate validatePurchaseSource(EamCapitalization cap, List<String> warnings) {
        PurchaseOrder po = poDao.selectById(cap.getSourceId());
        if (po == null) {
            throw new ServiceException(422, "采购订单不存在：" + cap.getSourceId());
        }
        cap.setSourceDocNo(po.getPoNo());
        List<GoodsReceipt> grs = grDao.selectList(new LambdaQueryWrapper<GoodsReceipt>()
                .eq(GoodsReceipt::getPoId, po.getId())
                .eq(GoodsReceipt::getStatus, GoodsReceipt.ST_POSTED));
        if (grs.isEmpty()) {
            throw new ServiceException(422, "该采购订单尚无已过账入库单，不具备预定可使用状态（FR-4.13-1-1）");
        }
        // 已过账 GR 行中取质检合格（RELEASED/CONCESSION 视为合格放行）最早到货日为验收日
        LocalDate earliest = null;
        for (GoodsReceipt gr : grs) {
            List<GoodsReceiptLine> lines = grLineDao.selectList(new LambdaQueryWrapper<GoodsReceiptLine>()
                    .eq(GoodsReceiptLine::getGrId, gr.getId()));
            boolean hasQc = lines.stream().anyMatch(l ->
                    "RELEASED".equals(l.getQcStatus()) || "CONCESSION".equals(l.getQcStatus()));
            if (hasQc && gr.getArrivalDate() != null
                    && (earliest == null || gr.getArrivalDate().isBefore(earliest))) {
                earliest = gr.getArrivalDate();
            }
        }
        if (earliest == null) {
            throw new ServiceException(422, "该采购订单入库单尚无质检合格行（验收不合格走退货流程，部分合格待合格后再转资，FR-4.13-1-1）");
        }
        if (po.getCreateDate() != null) {
            LocalDateTime poDate = po.getCreateDate();
            if (earliest.isBefore(poDate.toLocalDate())) {
                warnings.add("验收日期早于采购订单日期，请复核（BR-4.13-06）");
            }
        }
        return earliest;
    }

    /** 在建来源校验：项目 COMPLETED 且有归集；BR-4.13-08 差额检查；返回完工验收日 */
    private LocalDate validateCipSource(EamCapitalization cap, List<String> warnings) {
        EamCipProject project = cipProjectDao.selectById(cap.getSourceId());
        if (project == null) {
            throw new ServiceException(422, "在建工程项目不存在：" + cap.getSourceId());
        }
        cap.setSourceDocNo(project.getProjectNo());
        if (!"COMPLETED".equals(project.getStatus()) && !"CLOSED".equals(project.getStatus())) {
            throw new ServiceException(422, "在建工程尚未完工验收，不可转资（当前状态 " + project.getStatus() + "）");
        }
        BigDecimal collected = cipCostDao.sumTotal(project.getId());
        if (collected.signum() <= 0) {
            throw new ServiceException(422, "在建工程无成本归集，不可转资");
        }
        // 原值默认取归集合计（未显式传入时）
        if (cap.getTotalAmount() == null) {
            cap.setTotalAmount(collected);
            if (cap.getQty() != null && cap.getQty() > 0) {
                cap.setUnitAmount(collected.divide(BigDecimal.valueOf(cap.getQty()), 2,
                        RoundingMode.HALF_UP));
            }
        }
        if (cap.getTotalAmount() == null || cap.getUnitAmount() == null) {
            throw new ServiceException(422, "原值合计与单件原值必填");
        }
        // BR-4.13-08 归集 vs 结算差额 ≠ 0 → 强制部分转资 + 说明
        BigDecimal settled = project.getSettledAmount() == null ? BigDecimal.ZERO
                : project.getSettledAmount();
        BigDecimal diff = collected.subtract(settled);
        if (diff.signum() != 0) {
            if (!"1".equals(cap.getPartialFlag())) {
                throw new ServiceException(422, "成本未完全归集（归集 " + collected + " vs 已结算 "
                        + settled + "，差额 " + diff + "），须选择「部分转资」并填写说明（BR-4.13-08）");
            }
            if (!hasText(cap.getPartialRemark())) {
                throw new ServiceException(422, "部分转资须填写说明（未转资部分继续挂账在建工程，BR-4.13-08）");
            }
        }
        return project.getCompletionDate() == null ? LocalDate.now() : project.getCompletionDate();
    }

    @Override
    @Transactional
    public EamCapitalization update(EamCapitalization cap) {
        EamCapitalization old = require(cap.getId());
        if (!"DRAFT".equals(old.getStatus())) {
            throw new ServiceException(422, "仅草稿状态可编辑（当前 " + old.getStatus() + "）");
        }
        EamCapitalization upd = new EamCapitalization();
        upd.setId(old.getId());
        upd.setVerNo(old.getVerNo());
        upd.setAssetName(cap.getAssetName());
        upd.setSpecModel(cap.getSpecModel());
        upd.setDeptName(cap.getDeptName());
        upd.setCostCenterCode(cap.getCostCenterCode());
        upd.setStorageLocation(cap.getStorageLocation());
        upd.setLifeYears(cap.getLifeYears());
        upd.setResidualRate(cap.getResidualRate());
        upd.setDepreciationMethod(cap.getDepreciationMethod());
        upd.setEffectiveDate(cap.getEffectiveDate());
        upd.setPartialFlag(cap.getPartialFlag());
        upd.setPartialRemark(cap.getPartialRemark());
        upd.setAdjustReason(cap.getAdjustReason());
        upd.setRemark(cap.getRemark());
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (capDao.updateById(upd) == 0) {
            throw new ServiceException(409, "转资单更新冲突，请刷新后重试");
        }
        return capDao.selectById(old.getId());
    }

    // ================= 提交审核（BR-4.13-07 原值一致性）=================

    @Override
    @Transactional
    public EamCapitalization submit(String id) {
        EamCapitalization cap = require(id);
        if (!"DRAFT".equals(cap.getStatus()) && !"REJECTED".equals(cap.getStatus())) {
            throw new ServiceException(422, "仅草稿/已驳回可提交审核（当前 " + cap.getStatus() + "）");
        }
        // 科目前置预检（design D4：凭证失败不允许半提交，前置拦截）
        EamAssetCategory category = categoryDao.selectActiveByCode(cap.getCategoryCode());
        if (category == null || !hasText(category.getAssetAccount())) {
            throw new ServiceException(422, "资产分类或资产科目未配置，不可提交（转资凭证取数缺失）");
        }
        if ("CIP".equals(cap.getMode()) && !hasText(sysParamService.getValue("EAM_CIP_ACCOUNT"))) {
            throw new ServiceException(422, "系统参数 EAM_CIP_ACCOUNT（在建工程科目）未配置");
        }
        if ("PURCHASE".equals(cap.getMode())
                && !hasText(sysParamService.getValue("EAM_AP_ACCRUAL_ACCOUNT"))) {
            throw new ServiceException(422, "系统参数 EAM_AP_ACCRUAL_ACCOUNT（应付暂估科目）未配置");
        }

        // BR-4.13-07 原值一致性（采购：发票不含税；在建：归集合计）
        BigDecimal baseline;
        String baselineDesc;
        if ("PURCHASE".equals(cap.getMode())) {
            baseline = purchaseExTaxBaseline(cap);
            baselineDesc = "采购发票/订单不含税金额";
        } else {
            baseline = cipCostDao.sumTotal(cap.getSourceId());
            baselineDesc = "在建工程归集合计";
        }
        BigDecimal tolerance = sysParamService.getAmount("TOLERANCE_DEFAULT", BigDecimal.ZERO);
        BigDecimal diff = cap.getTotalAmount().subtract(baseline).abs();
        if (diff.compareTo(tolerance) > 0) {
            throw new ServiceException(422, "转资原值与" + baselineDesc + "差异超容差（原值 "
                    + cap.getTotalAmount() + " vs 基准 " + baseline + "，差额 " + diff
                    + "，容差 " + tolerance + "），阻断审核（BR-4.13-07）");
        }

        cap.setStatus("PENDING_APPROVE");
        cap.setUpdateBy(SecurityUtils.getCurrentUserId());
        capDao.updateById(cap);

        // 审批底座单签（design D4：财务资产会计 ROLE_FIN_ASSET，驳回回草稿）
        var inst = approvalEngine.submit("AssetCapital", cap.getId(),
                "资产转资审核：" + cap.getCapNo() + " " + cap.getAssetName()
                        + " / 原值 " + cap.getTotalAmount(), null,
                List.of(List.of(com.erp.service.approval.ApprovalNodeSpec
                        .sign("ROLE_FIN_ASSET", "财务资产会计审核"))));
        EamCapitalization upd = new EamCapitalization();
        upd.setId(cap.getId());
        upd.setVerNo(cap.getVerNo());
        upd.setApprovalId(inst.getId());
        capDao.updateById(upd);
        return capDao.selectById(cap.getId());
    }

    /** 采购基准：AP 发票含税总额 /(1+税率) 反推不含税；无发票退化 PO 含税 /(1+税率)（design D6） */
    private BigDecimal purchaseExTaxBaseline(EamCapitalization cap) {
        PurchaseOrder po = poDao.selectById(cap.getSourceId());
        BigDecimal taxRate = po == null || po.getTaxRate() == null ? BigDecimal.ZERO : po.getTaxRate();
        List<FinApInvoice> invoices = apInvoiceDao.selectList(new LambdaQueryWrapper<FinApInvoice>()
                .eq(FinApInvoice::getPoNo, cap.getSourceDocNo())
                .eq(FinApInvoice::getDelFlag, "0"));
        BigDecimal exTaxTotal = null;
        for (FinApInvoice inv : invoices) {
            if (inv.getTotalAmount() == null) {
                continue;
            }
            BigDecimal exTax = inv.getTotalAmount()
                    .divide(BigDecimal.ONE.add(taxRate), 2, RoundingMode.HALF_UP);
            exTaxTotal = exTaxTotal == null ? exTax : exTaxTotal.add(exTax);
        }
        if (exTaxTotal != null) {
            return exTaxTotal;
        }
        // 无发票 → PO 含税总额反推
        if (po == null || po.getTotalAmt() == null) {
            throw new ServiceException(422, "无采购发票与订单金额可作原值校验基准（BR-4.13-07）");
        }
        return po.getTotalAmt().divide(BigDecimal.ONE.add(taxRate), 2, RoundingMode.HALF_UP);
    }

    // ================= 折旧预览（D8，纯计算）=================

    @Override
    public Map<String, Object> depreciationPreview(String id) {
        EamCapitalization cap = require(id);
        return previewOf(cap);
    }

    private Map<String, Object> previewOf(EamCapitalization cap) {
        Map<String, Object> out = new LinkedHashMap<>();
        BigDecimal residualRate = cap.getResidualRate() == null ? BigDecimal.ZERO
                : cap.getResidualRate();
        BigDecimal residual = cap.getTotalAmount().multiply(residualRate)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal depreciable = cap.getTotalAmount().subtract(residual);
        BigDecimal monthly = BigDecimal.ZERO;
        if (cap.getLifeYears() != null && cap.getLifeYears() > 0) {
            monthly = depreciable.divide(BigDecimal.valueOf(cap.getLifeYears())
                    .multiply(BigDecimal.valueOf(12)), 2, RoundingMode.HALF_UP);
        }
        out.put("residual", residual);
        out.put("depreciable", depreciable);
        out.put("monthlyDepreciation", monthly);
        out.put("method", cap.getDepreciationMethod());
        return out;
    }

    // ================= 审核通过：一物一卡 + 凭证（D3/D4/D5）=================

    @Override
    @Transactional
    public Map<String, Object> completeFromApproval(String capId) {
        EamCapitalization cap = require(capId);
        if ("COMPLETED".equals(cap.getStatus())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cap", cap);
            out.put("idempotent", true);
            return out; // 幂等
        }
        int qty = cap.getQty() == null ? 1 : cap.getQty();
        if (qty <= 0) {
            throw new ServiceException(422, "转资数量必须大于 0");
        }
        if (qty > CARD_QTY_LIMIT) {
            throw new ServiceException(422, "一物一卡拆分上限 " + CARD_QTY_LIMIT + " 张，请拆单（当前 "
                    + qty + " 件）");
        }
        EamAssetCategory category = categoryDao.selectActiveByCode(cap.getCategoryCode());
        if (category == null) {
            throw new ServiceException(422, "资产分类不存在：" + cap.getCategoryCode());
        }

        // ---- 一物一卡拆分建卡（D3：每张独立卡号，同批同政策）----
        List<EamAssetCard> cards = new ArrayList<>(qty);
        for (int i = 0; i < qty; i++) {
            EamAssetCard card = new EamAssetCard();
            card.setCardNo(nextCardNo());
            card.setAssetName(cap.getAssetName());
            card.setSpecModel(cap.getSpecModel());
            card.setCategoryCode(cap.getCategoryCode());
            card.setDeptName(cap.getDeptName());
            card.setCostCenterCode(cap.getCostCenterCode());
            card.setStorageLocation(cap.getStorageLocation());
            card.setAssetCost(cap.getUnitAmount());
            card.setLifeYears(cap.getLifeYears());
            card.setResidualRate(cap.getResidualRate());
            card.setDepreciationMethod(cap.getDepreciationMethod());
            card.setEffectiveDate(cap.getEffectiveDate());
            card.setStatus("IN_USE");
            card.setDeprStatus("NOT_DEPRECIATED");
            card.setAccumDepr(BigDecimal.ZERO);
            card.setLockFlag("0");
            card.setSourceCapId(cap.getId());
            card.setSourceCapNo(cap.getCapNo());
            card.setCreateBy(SecurityUtils.getCurrentUserId());
            cardDao.insert(card);
            cards.add(card);
        }

        // ---- 转资凭证（D5：借 分类科目 / 贷 CIP 或应付暂估）----
        String creditAccount = "CIP".equals(cap.getMode())
                ? sysParamService.getValue("EAM_CIP_ACCOUNT")
                : sysParamService.getValue("EAM_AP_ACCRUAL_ACCOUNT");
        String summary = "资产转资：" + cap.getCapNo() + " " + cap.getAssetName()
                + " ×" + qty;
        FinVoucherCreateResult vr = createCapitalVoucher(cap, category, creditAccount, summary);

        // ---- 置 COMPLETED + 锁定（BR-4.13-10 凭证过账后五字段锁定）----
        EamCapitalization upd = new EamCapitalization();
        upd.setId(cap.getId());
        upd.setVerNo(cap.getVerNo());
        upd.setStatus("COMPLETED");
        upd.setCardCount(qty);
        upd.setVoucherId(vr.voucherId());
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (capDao.updateById(upd) == 0) {
            throw new ServiceException(422, "转资单状态更新冲突");
        }
        for (EamAssetCard card : cards) {
            card.setLockFlag("1");
            card.setUpdateBy(SecurityUtils.getCurrentUserId());
            cardDao.updateById(card);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cap", capDao.selectById(cap.getId()));
        out.put("cards", cards);
        out.put("voucherId", vr.voucherId());
        return out;
    }

    /** 凭证创建小包装（记录凭证 ID，供上层回写单据） */
    private record FinVoucherCreateResult(String voucherId) {
    }

    private FinVoucherCreateResult createCapitalVoucher(EamCapitalization cap,
                                                        EamAssetCategory category,
                                                        String creditAccount, String summary) {
        List<GlVoucherService.FinVoucherLineSpec> lines = List.of(
                GlVoucherService.FinVoucherLineSpec.of(category.getAssetAccount(), "DR",
                        cap.getTotalAmount(), summary),
                GlVoucherService.FinVoucherLineSpec.of(creditAccount, "CR",
                        cap.getTotalAmount(), summary));
        var voucher = voucherService.create("TRF", LocalDate.now(), summary,
                "CAPITAL", cap.getCapNo(), null, lines);
        return new FinVoucherCreateResult(voucher.getId());
    }

    @Override
    @Transactional
    public EamCapitalization rejectToDraft(String capId, String reason) {
        EamCapitalization cap = require(capId);
        if ("COMPLETED".equals(cap.getStatus())) {
            return cap; // 幂等
        }
        EamCapitalization upd = new EamCapitalization();
        upd.setId(cap.getId());
        upd.setVerNo(cap.getVerNo());
        upd.setStatus("DRAFT");
        upd.setRejectReason(reason);
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        capDao.updateById(upd);
        return capDao.selectById(cap.getId());
    }

    // ================= BR-4.13-10 卡片锁定拦截 =================

    @Override
    @Transactional
    public com.erp.entity.eam.EamAssetCard updateCard(com.erp.entity.eam.EamAssetCard card) {
        com.erp.entity.eam.EamAssetCard old = cardDao.selectById(card.getId());
        if (old == null) {
            throw new ServiceException(404, "资产卡片不存在：" + card.getId());
        }
        // BR-4.13-10：凭证过账后（LOCK_FLAG=1）折旧政策五字段锁定。
        // 部分更新语义：入参为 null 视为「未提供该字段」，不判定为变更。
        if ("1".equals(old.getLockFlag())) {
            boolean policyChanged = changed(old.getAssetCost(), card.getAssetCost())
                    || changedInt(old.getLifeYears(), card.getLifeYears())
                    || changed(old.getResidualRate(), card.getResidualRate())
                    || changedStr(old.getDepreciationMethod(), card.getDepreciationMethod())
                    || changedDate(old.getEffectiveDate(), card.getEffectiveDate());
            if (policyChanged) {
                throw new ServiceException(422,
                        "折旧政策字段已锁定（转资凭证已过账），修改须走资产变更流程（BR-4.13-10）");
            }
        }
        // 仅更新非锁定字段（存放地点/部门/成本中心/备注）
        com.erp.entity.eam.EamAssetCard upd = new com.erp.entity.eam.EamAssetCard();
        upd.setId(old.getId());
        upd.setVerNo(old.getVerNo());
        upd.setDeptName(card.getDeptName());
        upd.setCostCenterCode(card.getCostCenterCode());
        upd.setStorageLocation(card.getStorageLocation());
        upd.setRemark(card.getRemark());
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (cardDao.updateById(upd) == 0) {
            throw new ServiceException(409, "卡片更新冲突，请刷新后重试");
        }
        return cardDao.selectById(old.getId());
    }

    /** 入参为 null 视为未提供，不算变更；否则与原值比对 */
    private static boolean changed(java.math.BigDecimal oldV, java.math.BigDecimal newV) {
        if (newV == null) {
            return false;
        }
        return oldV == null || oldV.compareTo(newV) != 0;
    }

    private static boolean changedInt(Integer oldV, Integer newV) {
        if (newV == null) {
            return false;
        }
        return !newV.equals(oldV);
    }

    private static boolean changedStr(String oldV, String newV) {
        if (newV == null) {
            return false;
        }
        return !newV.equals(oldV);
    }

    private static boolean changedDate(LocalDate oldV, LocalDate newV) {
        if (newV == null) {
            return false;
        }
        return !newV.equals(oldV);
    }

    // ================= 待转资清单 + 超期预警（D7）=================

    @Override
    public List<Map<String, Object>> pendingList() {
        int grace = sysParamService.getInt("CAPITALIZATION_GRACE_DAYS", 30);
        List<Map<String, Object>> out = new ArrayList<>();

        // 采购侧：已过账+质检合格的 GR 按 PO 聚合，排除已有转资单
        List<GoodsReceipt> grs = grDao.selectList(new LambdaQueryWrapper<GoodsReceipt>()
                .eq(GoodsReceipt::getStatus, GoodsReceipt.ST_POSTED));
        Map<String, LocalDate> acceptanceByPo = new LinkedHashMap<>();
        for (GoodsReceipt gr : grs) {
            if (gr.getPoId() == null || gr.getArrivalDate() == null) {
                continue;
            }
            List<GoodsReceiptLine> lines = grLineDao.selectList(new LambdaQueryWrapper<GoodsReceiptLine>()
                    .eq(GoodsReceiptLine::getGrId, gr.getId()));
            boolean hasQc = lines.stream().anyMatch(l ->
                    "RELEASED".equals(l.getQcStatus()) || "CONCESSION".equals(l.getQcStatus()));
            if (!hasQc) {
                continue;
            }
            LocalDate cur = acceptanceByPo.get(gr.getPoId());
            if (cur == null || gr.getArrivalDate().isBefore(cur)) {
                acceptanceByPo.put(gr.getPoId(), gr.getArrivalDate());
            }
        }
        for (Map.Entry<String, LocalDate> e : acceptanceByPo.entrySet()) {
            if (capDao.countBySource(e.getKey()) > 0) {
                continue; // 已有转资单
            }
            PurchaseOrder po = poDao.selectById(e.getKey());
            if (po == null) {
                continue;
            }
            out.add(row("PURCHASE", po.getId(), po.getPoNo(),
                    po.getSupplierName() == null ? "" : po.getSupplierName(),
                    e.getValue(), grace));
        }

        // 在建侧：COMPLETED 且未转资项目
        List<EamCipProject> projects = cipProjectDao.selectList(new LambdaQueryWrapper<EamCipProject>()
                .eq(EamCipProject::getStatus, "COMPLETED"));
        for (EamCipProject p : projects) {
            if (capDao.countBySource(p.getId()) > 0) {
                continue;
            }
            if (p.getCompletionDate() == null) {
                continue;
            }
            out.add(row("CIP", p.getId(), p.getProjectNo(), p.getName(),
                    p.getCompletionDate(), grace));
        }
        // 超期推送（D7：查询即推送，notice 幂等去重不重复推）
        pushOverdueNotices(out);
        return out;
    }

    /** 超期推送（>grace 预警 ROLE_ASSET_ADMIN / >60 升级 ROLE_FIN_ASSET） */
    private void pushOverdueNotices(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            String level = String.valueOf(row.get("level"));
            if ("NORMAL".equals(level)) {
                continue;
            }
            String role = "ESCALATED".equals(level) ? "ROLE_FIN_ASSET" : "ROLE_ASSET_ADMIN";
            try {
                noticeService.push(role, null, "转资超期预警",
                        row.get("sourceDocNo") + "（" + row.get("name") + "）验收合格已 "
                                + row.get("overdueDays") + " 天未转资",
                        "EAM_CAP_OVERDUE", String.valueOf(row.get("sourceDocNo")));
            } catch (Exception ex) {
                log.warn("转资超期通知推送失败（不阻断查询）：{}", ex.getMessage());
            }
        }
    }

    /** 超期分级行（>grace 预警 / >60 升级财务经理，design D7） */
    private Map<String, Object> row(String mode, String sourceId, String sourceNo,
                                    String name, LocalDate acceptanceDate, int grace) {
        long days = ChronoUnit.DAYS.between(acceptanceDate, LocalDate.now());
        String level = "NORMAL";
        if (days > 60) {
            level = "ESCALATED";
        } else if (days > grace) {
            level = "WARNED";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("sourceId", sourceId);
        m.put("sourceDocNo", sourceNo);
        m.put("name", name);
        m.put("acceptanceDate", acceptanceDate);
        m.put("overdueDays", days);
        m.put("level", level);
        return m;
    }

    // ================= 内部 =================

    private void validateBase(EamCapitalization cap) {
        String mode = cap.getMode() == null ? "" : cap.getMode().trim().toUpperCase();
        if (!"PURCHASE".equals(mode) && !"CIP".equals(mode)) {
            throw new ServiceException(422, "转资模式取值域：PURCHASE 采购转资 / CIP 在建转资");
        }
        if (!hasText(cap.getSourceId())) {
            throw new ServiceException(422, "来源单据必填（采购订单 / 在建工程项目）");
        }
        if (!hasText(cap.getAssetName())) {
            throw new ServiceException(422, "资产名称必填");
        }
        if (!hasText(cap.getCategoryCode())) {
            throw new ServiceException(422, "资产分类必填");
        }
        if (cap.getQty() == null || cap.getQty() <= 0) {
            cap.setQty(1);
        }
        if (cap.getQty() > CARD_QTY_LIMIT) {
            throw new ServiceException(422, "一物一卡拆分上限 " + CARD_QTY_LIMIT + " 张，请拆单（当前 "
                    + cap.getQty() + " 件）");
        }
        if (cap.getTotalAmount() == null || cap.getTotalAmount().signum() <= 0) {
            throw new ServiceException(422, "原值合计必须大于 0");
        }
        if (cap.getUnitAmount() == null || cap.getUnitAmount().signum() <= 0) {
            // 单件原值缺省 = 合计 / 数量
            cap.setUnitAmount(cap.getTotalAmount()
                    .divide(BigDecimal.valueOf(cap.getQty()), 2, RoundingMode.HALF_UP));
        }
    }

    private EamCapitalization require(String id) {
        EamCapitalization cap = id == null ? null : capDao.selectById(id);
        if (cap == null) {
            throw new ServiceException(404, "转资单不存在：" + id);
        }
        return cap;
    }

    private String nextCapNo() {
        String prefix = "ZT" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = capDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private String nextCardNo() {
        String prefix = "ZC" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Integer max = cardDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
