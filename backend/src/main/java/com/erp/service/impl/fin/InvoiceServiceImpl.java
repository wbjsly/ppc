package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.ArItemDao;
import com.erp.dao.fin.RedInvoiceDao;
import com.erp.dao.fin.SalesInvoiceDao;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmTaxCodeDao;
import com.erp.dao.sd.InvoiceApplyDao;
import com.erp.dao.sd.ShipmentDao;
import com.erp.dao.sd.ShipmentLineDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.ArItem;
import com.erp.entity.fin.RedInvoice;
import com.erp.entity.fin.SalesInvoice;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmTaxCode;
import com.erp.entity.sd.InvoiceApply;
import com.erp.entity.sd.Shipment;
import com.erp.entity.sd.ShipmentLine;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoLine;
import com.erp.ops.OutboxPublisher;
import com.erp.service.SysParamService;
import com.erp.service.fin.InvoiceService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 销售开票实现（tasks 10.2~10.5，spec sales-invoicing-receivable，D9 按次开票）。
 *
 * 关键口径：
 *  - 发货确认 → 生成开票申请（与发货单一一对应，UK_SD_INVAPPLY_SHIP）+ 同步生成应收与应收明细行；
 *    应收到期日 = 发货确认日 + SO 付款条件天数（解析 NET30 类数字）缺省 AR_DUE_DAYS；
 *  - 税率取物料税码优先、客户税码兜底（FR-4.3-7-1），税率查 erp_mdm_tax_code 闭区间；
 *  - 提交：税务资质 C-4.3-06 L1 阻断（税号必填；专票另需一般纳税人资格有效）
 *    + 金额容差 |申请额 − 发货额| / 发货额 > TOLERANCE_DEFAULT 阻断（C-0-02）；
 *  - 开具（外部系统桩）：生成发票号回写应收与 SO（发票号 + 行 INVOICED_QTY + SO 已开票状态）；
 *  - 红字：红字发票关联原票与退货单，原应收 RED_AMOUNT 累加，余额 = AMOUNT − RED − PAID 回到匹配池。
 */
@Slf4j
@Service
public class InvoiceServiceImpl implements InvoiceService {

    private final InvoiceApplyDao applyDao;
    private final SalesInvoiceDao invoiceDao;
    private final RedInvoiceDao redDao;
    private final ArInvoiceDao arDao;
    private final ArItemDao arItemDao;
    private final ShipmentDao shipDao;
    private final ShipmentLineDao shipLineDao;
    private final SoDao soDao;
    private final SoLineDao soLineDao;
    private final MdmCustomerGroupDao customerDao;
    private final MdmItemDao itemDao;
    private final MdmTaxCodeDao taxCodeDao;
    private final SysParamService paramService;
    private final OutboxPublisher outboxPublisher;

    public InvoiceServiceImpl(InvoiceApplyDao applyDao,
                              SalesInvoiceDao invoiceDao,
                              RedInvoiceDao redDao,
                              ArInvoiceDao arDao,
                              ArItemDao arItemDao,
                              ShipmentDao shipDao,
                              ShipmentLineDao shipLineDao,
                              SoDao soDao,
                              SoLineDao soLineDao,
                              MdmCustomerGroupDao customerDao,
                              MdmItemDao itemDao,
                              MdmTaxCodeDao taxCodeDao,
                              SysParamService paramService,
                              OutboxPublisher outboxPublisher) {
        this.applyDao = applyDao;
        this.invoiceDao = invoiceDao;
        this.redDao = redDao;
        this.arDao = arDao;
        this.arItemDao = arItemDao;
        this.shipDao = shipDao;
        this.shipLineDao = shipLineDao;
        this.soDao = soDao;
        this.soLineDao = soLineDao;
        this.customerDao = customerDao;
        this.itemDao = itemDao;
        this.taxCodeDao = taxCodeDao;
        this.paramService = paramService;
        this.outboxPublisher = outboxPublisher;
    }

    // ---------- 10.2 发货确认触发生成 ----------

    @Override
    @Transactional
    public InvoiceApply onShipmentConfirmed(Shipment ship) {
        // 幂等：同发货单仅一张申请（一一对应）
        List<InvoiceApply> exists = applyDao.selectList(new LambdaQueryWrapper<InvoiceApply>()
                .eq(InvoiceApply::getShipId, ship.getId()));
        if (!exists.isEmpty()) {
            return exists.get(0);
        }
        List<ShipmentLine> lines = shipLineDao.selectList(new LambdaQueryWrapper<ShipmentLine>()
                .eq(ShipmentLine::getShipId, ship.getId())
                .orderByAsc(ShipmentLine::getLineNo));
        // 合并发货可跨 SO：全部行同 SO 才回挂 SO（跨 SO 时置空，remark 留痕）
        String soId = null;
        String soNo = null;
        if (!lines.isEmpty()) {
            String first = lines.get(0).getSoId();
            boolean sameSo = lines.stream()
                    .allMatch(l -> first != null && first.equals(l.getSoId())
                            && !"CANCELLED".equals(l.getLineStatus()));
            if (sameSo) {
                soId = first;
                soNo = lines.get(0).getSoNo();
            }
        }
        So so = soId == null ? null : soDao.selectById(soId);

        // 税率：物料税码一致时优先，否则客户税码，缺省 VAT13（FR-4.3-7-1）
        String taxCode = resolveTaxCode(ship.getCustomerId(), lines);
        BigDecimal rate = taxRate(taxCode);

        BigDecimal shipAmount = nvl(ship.getTotalAmt());
        LocalDate today = LocalDate.now();

        InvoiceApply apply = new InvoiceApply();
        apply.setId(uuid());
        apply.setApplyNo(nextNo("IA", applyDao::selectNosByPrefix));
        apply.setShipId(ship.getId());
        apply.setShipNo(ship.getShipNo());
        apply.setSoId(soId);
        apply.setSoNo(soNo);
        apply.setCustomerId(ship.getCustomerId());
        apply.setCustomerCode(ship.getCustomerCode());
        apply.setCustomerName(ship.getCustomerName());
        apply.setShipAmount(shipAmount);
        apply.setApplyAmount(shipAmount);
        apply.setTaxCode(taxCode);
        apply.setTaxRate(rate);
        apply.setNetAmount(netOf(shipAmount, rate));
        apply.setTaxAmount(shipAmount.subtract(netOf(shipAmount, rate)));
        apply.setInvoiceType(InvoiceApply.TYPE_SPECIAL);
        apply.setStatus(InvoiceApply.ST_DRAFT);
        apply.setTaxQualified("0");
        apply.setCreateBy(currentUser());
        applyDao.insert(apply);

        // 同步生成应收（D9 应收生于发货；到期日按付款条件/参数）
        ArInvoice ar = new ArInvoice();
        ar.setId(uuid());
        ar.setArNo(nextNo("AR", arDao::selectNosByPrefix));
        ar.setCustomerId(ship.getCustomerId());
        ar.setCustomerCode(ship.getCustomerCode());
        ar.setCustomerName(ship.getCustomerName());
        ar.setSoId(soId);
        ar.setSoNo(soNo);
        ar.setApplyId(apply.getId());
        ar.setApplyNo(apply.getApplyNo());
        ar.setShipId(ship.getId());
        ar.setShipNo(ship.getShipNo());
        ar.setTaxCode(taxCode);
        ar.setTaxRate(rate);
        ar.setTaxAmount(apply.getTaxAmount());
        ar.setRedAmount(BigDecimal.ZERO);
        ar.setInvoiceDate(today);
        ar.setDueDate(today.plusDays(dueDays(so)));
        ar.setAmount(shipAmount);
        ar.setPaidAmount(BigDecimal.ZERO);
        ar.setStatus(ArInvoice.ST_UNPAID);
        ar.setCreateBy(currentUser());
        arDao.insert(ar);

        apply.setArId(ar.getId());
        apply.setArNo(ar.getArNo());
        applyDao.updateById(apply);

        // 应收明细行（物料级，支撑对账逐笔）
        int lineNo = 1;
        for (ShipmentLine sl : lines) {
            if (ShipmentLine.LS_CANCELLED.equals(sl.getLineStatus())) {
                continue;
            }
            String itemTax = itemTaxCode(sl.getItemCode(), ship.getCustomerId());
            BigDecimal lr = taxRate(itemTax);
            ArItem item = new ArItem();
            item.setId(uuid());
            item.setArId(ar.getId());
            item.setArNo(ar.getArNo());
            item.setLineNo(lineNo++);
            item.setSoLineId(sl.getSoLineId());
            item.setShipLineId(sl.getId());
            item.setItemCode(sl.getItemCode());
            item.setItemName(sl.getItemName());
            item.setQty(sl.getQty());
            item.setBaseUnit(sl.getBaseUnit());
            item.setUnitPrice(sl.getUnitPrice());
            item.setAmount(nvl(sl.getAmount()));
            item.setTaxRate(lr);
            item.setTaxAmount(nvl(sl.getAmount()).subtract(netOf(nvl(sl.getAmount()), lr)));
            item.setCreateBy(currentUser());
            arItemDao.insert(item);
        }
        log.info("invoice apply {} + AR {} generated from shipment {}",
                apply.getApplyNo(), ar.getArNo(), ship.getShipNo());
        return apply;
    }

    // ---------- 3.8.1 开票触发 ----------

    @Override
    public Page<InvoiceApply> page(long current, long size, String status,
                                   String keyword, String customerId, String soNo) {
        LambdaQueryWrapper<InvoiceApply> qw = new LambdaQueryWrapper<InvoiceApply>()
                .eq(isNotBlank(status), InvoiceApply::getStatus, status)
                .eq(isNotBlank(customerId), InvoiceApply::getCustomerId, customerId)
                .eq(isNotBlank(soNo), InvoiceApply::getSoNo, soNo)
                .and(isNotBlank(keyword), w -> w.like(InvoiceApply::getApplyNo, keyword)
                        .or().like(InvoiceApply::getCustomerName, keyword)
                        .or().like(InvoiceApply::getShipNo, keyword))
                .orderByDesc(InvoiceApply::getCreateDate);
        return applyDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String applyId) {
        InvoiceApply apply = require(applyId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", apply);
        List<ShipmentLine> lines = shipLineDao.selectList(new LambdaQueryWrapper<ShipmentLine>()
                .eq(ShipmentLine::getShipId, apply.getShipId())
                .orderByAsc(ShipmentLine::getLineNo));
        out.put("shipLines", lines);
        if (isNotBlank(apply.getInvoiceId())) {
            out.put("invoice", invoiceDao.selectById(apply.getInvoiceId()));
        }
        if (isNotBlank(apply.getArId())) {
            out.put("ar", arDao.selectById(apply.getArId()));
            out.put("arItems", arItemDao.selectList(new LambdaQueryWrapper<ArItem>()
                    .eq(ArItem::getArId, apply.getArId()).orderByAsc(ArItem::getLineNo)));
        }
        // 税务资质实时结论（供列表红标）
        MdmCustomerGroup cust = customerDao.selectById(apply.getCustomerId());
        out.put("taxCheck", taxCheck(cust, apply.getInvoiceType()));
        return out;
    }

    @Override
    @Transactional
    public InvoiceApply updateAmount(String applyId, BigDecimal applyAmount, String remark) {
        requireAny("调整开票金额", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        InvoiceApply apply = require(applyId);
        if (!InvoiceApply.ST_DRAFT.equals(apply.getStatus())
                && !InvoiceApply.ST_RETURNED.equals(apply.getStatus())) {
            throw new ServiceException(422, "仅草稿/退回状态可调整金额（当前：" + apply.getStatus() + "）");
        }
        if (applyAmount == null || applyAmount.signum() <= 0) {
            throw new ServiceException(422, "开票金额必须大于 0");
        }
        apply.setApplyAmount(applyAmount);
        BigDecimal rate = nvl(apply.getTaxRate());
        apply.setNetAmount(netOf(applyAmount, rate));
        apply.setTaxAmount(applyAmount.subtract(netOf(applyAmount, rate)));
        if (isNotBlank(remark)) {
            apply.setRemark(remark);
        }
        applyDao.updateById(apply);
        return apply;
    }

    @Override
    @Transactional
    public InvoiceApply submit(String applyId) {
        requireAny("提交开票审核", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        InvoiceApply apply = require(applyId);
        if (!InvoiceApply.ST_DRAFT.equals(apply.getStatus())
                && !InvoiceApply.ST_RETURNED.equals(apply.getStatus())) {
            throw new ServiceException(422, "仅草稿/退回状态可提交（当前：" + apply.getStatus() + "）");
        }
        // C-4.3-06 税务资质 L1 硬阻断（结论实时计算并随异常返回；detail 同口径展示）
        MdmCustomerGroup cust = customerDao.selectById(apply.getCustomerId());
        Map<String, Object> check = taxCheck(cust, apply.getInvoiceType());
        if (!Boolean.TRUE.equals(check.get("pass"))) {
            throw new ServiceException(422, "税务资质校验未通过（C-4.3-06）：" + check.get("reason"));
        }
        // 金额容差（C-0-02）：|申请额 − 发货额| / 发货额 > TOLERANCE_DEFAULT 阻断
        BigDecimal shipAmt = nvl(apply.getShipAmount());
        if (shipAmt.signum() > 0) {
            BigDecimal diff = apply.getApplyAmount().subtract(shipAmt).abs();
            BigDecimal tolerance = paramService.getRate("TOLERANCE_DEFAULT", new BigDecimal("0.005"));
            if (diff.divide(shipAmt, 6, RoundingMode.HALF_UP).compareTo(tolerance) > 0) {
                throw new ServiceException(422, String.format(
                        "开票金额与发货金额差异 %.2f 元（%.2f%%）超出容差 %.2f%%，已阻断提交",
                        diff, diff.divide(shipAmt, 6, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                        tolerance.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)));
            }
        }
        apply.setStatus(InvoiceApply.ST_AUDITING);
        apply.setTaxQualified("1");
        apply.setTaxFailReason(null);
        applyDao.updateById(apply);
        return apply;
    }

    @Override
    @Transactional
    public InvoiceApply audit(String applyId, boolean pass, String opinion) {
        requireAny("开票审核", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        InvoiceApply apply = require(applyId);
        if (!InvoiceApply.ST_AUDITING.equals(apply.getStatus())) {
            throw new ServiceException(422, "仅待审核状态可执行审核（当前：" + apply.getStatus() + "）");
        }
        if (!pass && isBlank(opinion)) {
            throw new ServiceException(422, "退回修改必须填写审核意见");
        }
        apply.setAuditBy(currentUser());
        apply.setAuditAt(LocalDateTime.now());
        apply.setAuditOpinion(opinion);
        apply.setStatus(pass ? InvoiceApply.ST_APPROVED : InvoiceApply.ST_RETURNED);
        applyDao.updateById(apply);
        return apply;
    }

    // ---------- 10.4 开具（外部开票系统桩）与回写 ----------

    @Override
    @Transactional
    public Map<String, Object> issue(String applyId) {
        requireAny("开具发票", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        InvoiceApply apply = require(applyId);
        if (!InvoiceApply.ST_APPROVED.equals(apply.getStatus())) {
            throw new ServiceException(422, "仅审核通过的申请可开具（当前：" + apply.getStatus() + "）");
        }
        // 复核税务资质（L1，审核后客户资料可能变化）
        MdmCustomerGroup cust = customerDao.selectById(apply.getCustomerId());
        Map<String, Object> check = taxCheck(cust, apply.getInvoiceType());
        if (!Boolean.TRUE.equals(check.get("pass"))) {
            throw new ServiceException(422, "税务资质校验未通过（C-4.3-06）：" + check.get("reason"));
        }
        BigDecimal amount = nvl(apply.getApplyAmount());
        BigDecimal rate = nvl(apply.getTaxRate());
        LocalDate today = LocalDate.now();

        SalesInvoice inv = new SalesInvoice();
        inv.setId(uuid());
        inv.setInvoiceNo(nextNo("FP", invoiceDao::selectNosByPrefix));
        inv.setExternalNo("EXT-" + inv.getInvoiceNo());   // 外部开票系统按桩口径
        inv.setApplyId(apply.getId());
        inv.setApplyNo(apply.getApplyNo());
        inv.setShipId(apply.getShipId());
        inv.setShipNo(apply.getShipNo());
        inv.setSoId(apply.getSoId());
        inv.setSoNo(apply.getSoNo());
        inv.setCustomerId(apply.getCustomerId());
        inv.setCustomerCode(apply.getCustomerCode());
        inv.setCustomerName(apply.getCustomerName());
        inv.setTaxNo(cust == null ? null : cust.getTaxNo());
        inv.setInvoiceType(apply.getInvoiceType());
        inv.setInvoiceDate(today);
        inv.setAmount(amount);
        inv.setNetAmount(netOf(amount, rate));
        inv.setTaxRate(rate);
        inv.setTaxAmount(amount.subtract(netOf(amount, rate)));
        inv.setStatus(SalesInvoice.ST_ISSUED);
        inv.setArId(apply.getArId());
        inv.setArNo(apply.getArNo());
        inv.setRedAmount(BigDecimal.ZERO);
        inv.setCreateBy(currentUser());
        invoiceDao.insert(inv);

        // 回写应收（10.4 发票号回写应收与 SO）
        ArInvoice ar = arDao.selectById(apply.getArId());
        if (ar != null) {
            ar.setInvoiceId(inv.getId());
            ar.setInvoiceNo(inv.getInvoiceNo());
            arDao.updateById(ar);
        }
        // 回写 SO：发票号 + 行 INVOICED_QTY + SO 已开票状态
        List<ShipmentLine> shipLines = shipLineDao.selectList(new LambdaQueryWrapper<ShipmentLine>()
                .eq(ShipmentLine::getShipId, apply.getShipId()));
        So so = isNotBlank(apply.getSoId()) ? soDao.selectById(apply.getSoId()) : null;
        boolean allInvoiced = so != null;
        for (ShipmentLine sl : shipLines) {
            if (ShipmentLine.LS_CANCELLED.equals(sl.getLineStatus())) {
                continue;
            }
            SoLine line = soLineDao.selectById(sl.getSoLineId());
            if (line == null) {
                continue;
            }
            line.setInvoicedQty(nvl(line.getInvoicedQty()).add(sl.getQty()));
            boolean lineFull = nvl(line.getQty()).compareTo(line.getInvoicedQty()) <= 0;
            if (lineFull && !"CANCELLED".equals(line.getLineStatus())) {
                line.setLineStatus("INVOICED");
            }
            soLineDao.updateById(line);
            if (!lineFull) {
                allInvoiced = false;
            }
        }
        if (so != null) {
            so.setInvoiceNo(inv.getInvoiceNo());
            if (allInvoiced && (So.ST_SIGNED.equals(so.getStatus())
                    || So.ST_SHIPPED.equals(so.getStatus())
                    || So.ST_PARTIAL_SHIPPED.equals(so.getStatus()))) {
                so.setStatus(So.ST_INVOICED);
            }
            soDao.updateById(so);
        }

        // 申请状态与发票号回写
        apply.setStatus(InvoiceApply.ST_ISSUED);
        apply.setIssueBy(currentUser());
        apply.setIssueAt(LocalDateTime.now());
        apply.setInvoiceId(inv.getId());
        apply.setInvoiceNo(inv.getInvoiceNo());
        applyDao.updateById(apply);

        // INV.ISSUED 事件（同事务入 outbox）
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("invoiceNo", inv.getInvoiceNo());
        extra.put("applyNo", apply.getApplyNo());
        extra.put("soNo", apply.getSoNo());
        extra.put("amount", inv.getAmount());
        extra.put("customerId", inv.getCustomerId());
        outboxPublisher.publishSourced("INV.ISSUED", inv.getInvoiceNo(), 1, null,
                "发票开具：" + inv.getAmount(), extra, "fin-service");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", apply);
        out.put("invoice", inv);
        return out;
    }

    @Override
    @Transactional
    public InvoiceApply confirm(String applyId) {
        requireAny("客户确认发票", "ROLE_FINANCE_MGR", "ROLE_ADMIN", "ROLE_SALES");
        InvoiceApply apply = require(applyId);
        if (!InvoiceApply.ST_ISSUED.equals(apply.getStatus())
                && !InvoiceApply.ST_DISPUTED.equals(apply.getStatus())) {
            throw new ServiceException(422, "仅已开具/异议状态可确认（当前：" + apply.getStatus() + "）");
        }
        apply.setStatus(InvoiceApply.ST_CONFIRMED);
        apply.setConfirmBy(currentUser());
        apply.setConfirmAt(LocalDateTime.now());
        applyDao.updateById(apply);
        if (isNotBlank(apply.getInvoiceId())) {
            SalesInvoice inv = invoiceDao.selectById(apply.getInvoiceId());
            if (inv != null) {
                inv.setStatus(SalesInvoice.ST_CONFIRMED);
                inv.setConfirmBy(currentUser());
                inv.setConfirmAt(LocalDateTime.now());
                invoiceDao.updateById(inv);
            }
        }
        return apply;
    }

    @Override
    @Transactional
    public InvoiceApply dispute(String applyId, String note) {
        requireAny("登记客户异议", "ROLE_FINANCE_MGR", "ROLE_ADMIN", "ROLE_SALES");
        InvoiceApply apply = require(applyId);
        if (isBlank(note)) {
            throw new ServiceException(422, "异议说明必填");
        }
        if (!InvoiceApply.ST_ISSUED.equals(apply.getStatus())
                && !InvoiceApply.ST_CONFIRMED.equals(apply.getStatus())) {
            throw new ServiceException(422, "仅已开具/已确认状态可登记异议（当前：" + apply.getStatus() + "）");
        }
        apply.setStatus(InvoiceApply.ST_DISPUTED);
        apply.setDisputeNote(note);
        applyDao.updateById(apply);
        if (isNotBlank(apply.getInvoiceId())) {
            SalesInvoice inv = invoiceDao.selectById(apply.getInvoiceId());
            if (inv != null) {
                inv.setStatus(SalesInvoice.ST_DISPUTED);
                inv.setDisputeNote(note);
                invoiceDao.updateById(inv);
            }
        }
        return apply;
    }

    // ---------- 10.5 红字发票与应收红冲 ----------

    @Override
    @Transactional
    public RedInvoice redInvoice(String originInvoiceId, String returnId, String returnNo,
                                 BigDecimal amount, String reason) {
        requireAny("开具红字发票", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        SalesInvoice origin = invoiceDao.selectById(originInvoiceId);
        if (origin == null) {
            throw new ServiceException(404, "原发票不存在：" + originInvoiceId);
        }
        if (isBlank(reason)) {
            throw new ServiceException(422, "红冲原因必填");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "红冲金额必须大于 0");
        }
        BigDecimal remain = nvl(origin.getAmount()).subtract(nvl(origin.getRedAmount()));
        if (amount.compareTo(remain) > 0) {
            throw new ServiceException(422, "红冲金额超出可冲余额 " + strip(remain));
        }
        BigDecimal rate = nvl(origin.getTaxRate());

        RedInvoice red = new RedInvoice();
        red.setId(uuid());
        red.setRedNo(nextNo("FPZ", redDao::selectNosByPrefix));
        red.setOriginInvoiceId(origin.getId());
        red.setOriginInvoiceNo(origin.getInvoiceNo());
        red.setArId(origin.getArId());
        red.setArNo(origin.getArNo());
        red.setReturnId(returnId);
        red.setReturnNo(returnNo);
        red.setCustomerId(origin.getCustomerId());
        red.setCustomerCode(origin.getCustomerCode());
        red.setCustomerName(origin.getCustomerName());
        red.setRedDate(LocalDate.now());
        red.setAmount(amount);
        red.setNetAmount(netOf(amount, rate));
        red.setTaxRate(rate);
        red.setTaxAmount(amount.subtract(netOf(amount, rate)));
        red.setReason(reason);
        red.setStatus(RedInvoice.ST_ISSUED);
        red.setIssueBy(currentUser());
        red.setIssueAt(LocalDateTime.now());
        red.setCreateBy(currentUser());
        redDao.insert(red);

        // 原票累计红冲 + 状态
        origin.setRedAmount(nvl(origin.getRedAmount()).add(amount));
        invoiceDao.updateById(origin);

        // 原应收红冲：余额 = AMOUNT − RED − PAID 相应减少并回到匹配池（spec 场景）
        ArInvoice ar = arDao.selectById(origin.getArId());
        if (ar != null) {
            ar.setRedAmount(nvl(ar.getRedAmount()).add(amount));
            BigDecimal balance = nvl(ar.getAmount()).subtract(nvl(ar.getRedAmount()))
                    .subtract(nvl(ar.getPaidAmount()));
            ar.setStatus(statusOf(ar, balance));
            arDao.updateById(ar);
        }
        // 申请单置红字终态
        List<InvoiceApply> applies = applyDao.selectList(new LambdaQueryWrapper<InvoiceApply>()
                .eq(InvoiceApply::getInvoiceId, origin.getId()));
        for (InvoiceApply a : applies) {
            a.setStatus(InvoiceApply.ST_RED);
            applyDao.updateById(a);
        }
        log.info("red invoice {} for {} amount {}", red.getRedNo(), origin.getInvoiceNo(), amount);
        return red;
    }

    // ---------- 销项发票台账 ----------

    @Override
    public Page<SalesInvoice> invoicePage(long current, long size, String status,
                                          String keyword, String customerId) {
        return invoiceDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<SalesInvoice>()
                        .eq(isNotBlank(status), SalesInvoice::getStatus, status)
                        .eq(isNotBlank(customerId), SalesInvoice::getCustomerId, customerId)
                        .and(isNotBlank(keyword), w -> w.like(SalesInvoice::getInvoiceNo, keyword)
                                .or().like(SalesInvoice::getCustomerName, keyword)
                                .or().like(SalesInvoice::getSoNo, keyword))
                        .orderByDesc(SalesInvoice::getInvoiceDate));
    }

    @Override
    public SalesInvoice getInvoice(String id) {
        SalesInvoice inv = invoiceDao.selectById(id);
        if (inv == null) {
            throw new ServiceException(404, "发票不存在：" + id);
        }
        return inv;
    }

    // ---------- 税务资质与税率 ----------

    /** C-4.3-06：税号必填；专票另需一般纳税人资格有效（过期/未维护阻断） */
    private Map<String, Object> taxCheck(MdmCustomerGroup cust, String invoiceType) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (cust == null) {
            out.put("pass", false);
            out.put("reason", "客户不存在");
            return out;
        }
        if (isBlank(cust.getTaxNo())) {
            out.put("pass", false);
            out.put("reason", "纳税人识别号缺失，请补充客户税务信息");
            return out;
        }
        if (InvoiceApply.TYPE_SPECIAL.equals(invoiceType)) {
            if (isBlank(cust.getTaxpayerType())) {
                out.put("pass", false);
                out.put("reason", "一般纳税人资格未维护，请补充客户税务信息");
                return out;
            }
            if (!"GENERAL".equals(cust.getTaxpayerType())) {
                out.put("pass", false);
                out.put("reason", "客户非一般纳税人，不可开具增值税专用发票");
                return out;
            }
            if (cust.getTaxQualExpire() != null && cust.getTaxQualExpire().isBefore(LocalDate.now())) {
                out.put("pass", false);
                out.put("reason", "一般纳税人资格已于 " + cust.getTaxQualExpire() + " 过期，请更新资格信息");
                return out;
            }
        }
        out.put("pass", true);
        out.put("reason", null);
        return out;
    }

    /** 税码解析：物料税码一致 → 优先；否则客户税码；缺省 VAT13 */
    private String resolveTaxCode(String customerId, List<ShipmentLine> lines) {
        String found = null;
        for (ShipmentLine sl : lines) {
            if (ShipmentLine.LS_CANCELLED.equals(sl.getLineStatus())) {
                continue;
            }
            String code = itemTaxCode(sl.getItemCode(), customerId);
            if (found == null) {
                found = code;
            } else if (!found.equals(code)) {
                found = null;   // 多物料税码不一致 → 回落客户税码
                break;
            }
        }
        if (isNotBlank(found)) {
            return found;
        }
        MdmCustomerGroup cust = customerDao.selectById(customerId);
        if (cust != null && isNotBlank(cust.getTaxCode())) {
            return cust.getTaxCode();
        }
        return "VAT13";
    }

    private String itemTaxCode(String itemCode, String customerId) {
        if (isNotBlank(itemCode)) {
            List<MdmItem> items = itemDao.selectList(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, itemCode).last("LIMIT 1"));
            if (!items.isEmpty() && isNotBlank(items.get(0).getTaxCode())) {
                return items.get(0).getTaxCode();
            }
        }
        MdmCustomerGroup cust = customerDao.selectById(customerId);
        if (cust != null && isNotBlank(cust.getTaxCode())) {
            return cust.getTaxCode();
        }
        return "VAT13";
    }

    private BigDecimal taxRate(String taxCode) {
        MdmTaxCode hit = taxCodeDao.selectHit(taxCode, LocalDate.now());
        if (hit == null) {
            throw new ServiceException(422, "税码 " + taxCode + " 无生效税率记录，无法开票");
        }
        return hit.getTaxRate();
    }

    private static BigDecimal netOf(BigDecimal amount, BigDecimal rate) {
        if (rate == null || rate.signum() == 0) {
            return nvl(amount).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal divisor = BigDecimal.ONE.add(rate);
        return nvl(amount).divide(divisor, 2, RoundingMode.HALF_UP);
    }

    /** 应收到期日：SO 付款条件中的天数（NET30/30 天）→ 缺省 AR_DUE_DAYS */
    private long dueDays(So so) {
        if (so != null && isNotBlank(so.getPaymentTerms())) {
            StringBuilder digits = new StringBuilder();
            for (char c : so.getPaymentTerms().toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                }
            }
            if (digits.length() > 0) {
                return Long.parseLong(digits.toString());
            }
        }
        return paramService.getInt("AR_DUE_DAYS", 30);
    }

    private static String statusOf(ArInvoice ar, BigDecimal balance) {
        if (balance.signum() <= 0) {
            return nvl(ar.getPaidAmount()).signum() > 0 ? ArInvoice.ST_PAID : ArInvoice.ST_RED;
        }
        return nvl(ar.getPaidAmount()).signum() > 0 ? ArInvoice.ST_PARTIAL : ArInvoice.ST_UNPAID;
    }

    // ---------- 通用 ----------

    private InvoiceApply require(String id) {
        InvoiceApply apply = applyDao.selectById(id);
        if (apply == null) {
            throw new ServiceException(404, "开票申请不存在：" + id);
        }
        return apply;
    }

    private void requireAny(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : roles) {
                if (want.equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "无权" + action + "（需 "
                + String.join("/", allowed).replace("ROLE_", "") + "）");
    }

    private String currentUser() {
        String id = SecurityUtils.getCurrentUserId();
        return id == null ? "system" : id;
    }

    private String nextNo(String kind, PrefixQuery query) {
        String prefix = kind + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        List<String> nos = query.byPrefix(prefix + "%");   // LIKE 通配符由调用方拼接（项目惯例）
        for (String no : nos) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 种子/非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private interface PrefixQuery {
        List<String> byPrefix(String prefix);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
