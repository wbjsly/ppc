package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.RedInvoiceDao;
import com.erp.dao.fin.SalesInvoiceDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.SdReturnDao;
import com.erp.dao.sd.SdReturnJudgeLogDao;
import com.erp.dao.sd.SdReturnLineDao;
import com.erp.dao.sd.ShipmentDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.RedInvoice;
import com.erp.entity.fin.SalesInvoice;
import com.erp.entity.inv.InvStock;
import com.erp.entity.sd.SdReturn;
import com.erp.entity.sd.SdReturnJudgeLog;
import com.erp.entity.sd.SdReturnLine;
import com.erp.entity.sd.Shipment;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoLine;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.InvoiceService;
import com.erp.service.sd.ReturnService;
import com.erp.service.sd.ShipmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 销售退货实现（tasks 12.2~12.7，spec sales-return，D14）。
 */
@Slf4j
@Service("sdReturnServiceImpl")
public class ReturnServiceImpl implements ReturnService {

    private final SdReturnDao returnDao;
    private final SdReturnLineDao lineDao;
    private final SdReturnJudgeLogDao logDao;
    private final SoDao soDao;
    private final SoLineDao soLineDao;
    private final ShipmentDao shipDao;
    private final ArInvoiceDao arDao;
    private final SalesInvoiceDao invoiceDao;
    private final RedInvoiceDao redDao;
    private final InvStockDao stockDao;
    private final ApprovalEngine approvalEngine;
    private final InvoiceService invoiceService;
    private final GlVoucherService voucherService;
    private final ShipmentService shipmentService;
    private final SysParamService paramService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final com.erp.service.inv.StockPostingEngine stockPostingEngine;

    public ReturnServiceImpl(SdReturnDao returnDao,
                             SdReturnLineDao lineDao,
                             SdReturnJudgeLogDao logDao,
                             SoDao soDao,
                             SoLineDao soLineDao,
                             ShipmentDao shipDao,
                             ArInvoiceDao arDao,
                             SalesInvoiceDao invoiceDao,
                             RedInvoiceDao redDao,
                             InvStockDao stockDao,
                             ApprovalEngine approvalEngine,
                             InvoiceService invoiceService,
                             GlVoucherService voucherService,
                             ShipmentService shipmentService,
                             SysParamService paramService,
                                 com.erp.service.inv.StockPostingEngine stockPostingEngine) {
        this.returnDao = returnDao;
        this.lineDao = lineDao;
        this.logDao = logDao;
        this.soDao = soDao;
        this.soLineDao = soLineDao;
        this.shipDao = shipDao;
        this.arDao = arDao;
        this.invoiceDao = invoiceDao;
        this.redDao = redDao;
        this.stockDao = stockDao;
        this.approvalEngine = approvalEngine;
        this.invoiceService = invoiceService;
        this.voucherService = voucherService;
        this.shipmentService = shipmentService;
        this.paramService = paramService;
        this.stockPostingEngine = stockPostingEngine;
    }

    // ==================== 12.2 退货申请 ====================

    @Override
    public Map<String, Object> returnables(String soId) {
        requireAny("查询可退余量", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_ADMIN",
                "ROLE_FINANCE_MGR", "ROLE_WAREHOUSE");
        So so = requireSo(soId);
        Map<String, BigDecimal> returned = returnedBySoLine(soId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SoLine l : soLines(soId)) {
            if (SoLine.LS_CANCELLED.equals(l.getLineStatus())) {
                continue;
            }
            BigDecimal shipped = nvl(l.getShippedQty());
            BigDecimal back = returned.getOrDefault(l.getId(), BigDecimal.ZERO);
            BigDecimal retirable = shipped.subtract(back);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("soLineId", l.getId());
            row.put("lineNo", l.getLineNo());
            row.put("itemCode", l.getItemCode());
            row.put("itemName", l.getItemName());
            row.put("qty", l.getQty());
            row.put("shippedQty", shipped);
            row.put("returnedQty", back);
            row.put("retirableQty", retirable.max(BigDecimal.ZERO));
            row.put("unitPrice", l.getUnitPrice());
            row.put("warehouseCode", l.getWarehouseCode());
            row.put("invoiced", nvl(l.getInvoicedQty()).signum() > 0);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("so", so);
        out.put("lines", rows);
        out.put("invoiced", rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("invoiced"))));
        return out;
    }

    @Override
    @Transactional
    public SdReturn createApply(Map<String, Object> req) {
        requireAny("创建退货申请", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_ADMIN");
        String soId = str(req.get("soId"));
        if (isBlank(soId)) {
            throw new ServiceException(422, "原销售订单必填（BR-4.3-68 关联原 SO）");
        }
        So so = requireSo(soId);
        String reasonType = str(req.get("reasonType"));
        String returnReason = str(req.get("returnReason"));
        String handleType = str(req.get("handleType"));
        String evidence = str(req.get("evidence"));
        if (isBlank(reasonType)) {
            throw new ServiceException(422, "退货原因分类必填（质量/客户/物流）");
        }
        if (isBlank(returnReason)) {
            throw new ServiceException(422, "退货原因说明必填");
        }
        if (!SdReturn.HANDLE_REFUND.equals(handleType)
                && !SdReturn.HANDLE_EXCHANGE.equals(handleType)) {
            throw new ServiceException(422, "期望处理方式 REFUND/EXCHANGE 必选");
        }
        if (isBlank(evidence)) {
            throw new ServiceException(422, "凭证说明必填（spec 12.2 申请必填项）");
        }
        Object rawLines = req.get("lines");
        List<Map<String, Object>> lines = mapper.convertValue(rawLines,
                mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        if (lines == null || lines.isEmpty()) {
            throw new ServiceException(422, "退货行必填");
        }

        Map<String, BigDecimal> returned = returnedBySoLine(soId);
        Map<String, SoLine> soLineMap = new LinkedHashMap<>();
        for (SoLine l : soLines(soId)) {
            soLineMap.put(l.getId(), l);
        }

        SdReturn ret = new SdReturn();
        ret.setId(uuid());
        ret.setReturnNo(nextReturnNo());
        ret.setSourceType(SdReturn.SRC_CUSTOMER);
        ret.setSoId(so.getId());
        ret.setSoNo(so.getSoNo());
        String shipId = str(req.get("shipId"));
        if (isNotBlank(shipId)) {
            Shipment ship = shipDao.selectById(shipId);
            if (ship != null) {
                ret.setShipId(ship.getId());
                ret.setShipNo(ship.getShipNo());
            }
        }
        ret.setCustomerId(so.getCustomerId());
        ret.setCustomerCode(so.getCustomerCode());
        ret.setCustomerName(so.getCustomerName());
        ret.setHandleType(handleType);
        ret.setReturnReason(returnReason);
        ret.setReasonType(reasonType);
        ret.setRemark(evidence);
        ret.setStatus(SdReturn.ST_DRAFT);
        ret.setApplyBy(currentUser());
        ret.setApplyAt(LocalDateTime.now());
        ret.setOverdue("0");
        ret.setRefundAmt(BigDecimal.ZERO);
        ret.setStockIn("0");
        ret.setCreateBy(currentUser());

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        boolean anyInvoiced = false;
        int lineNo = 1;
        List<SdReturnLine> entityLines = new ArrayList<>();
        for (Map<String, Object> raw : lines) {
            String soLineId = str(raw.get("soLineId"));
            SoLine sl = soLineMap.get(soLineId);
            if (sl == null) {
                throw new ServiceException(422, "SO 行不存在：" + soLineId);
            }
            BigDecimal qty = num(raw.get("qty"));
            if (qty.signum() <= 0) {
                throw new ServiceException(422, "退货数量必须大于 0（行 " + sl.getLineNo() + "）");
            }
            BigDecimal back = returned.getOrDefault(soLineId, BigDecimal.ZERO);
            BigDecimal retirable = nvl(sl.getShippedQty()).subtract(back);
            if (qty.compareTo(retirable) > 0) {
                throw new ServiceException(422, "行 " + sl.getLineNo() + "（"
                        + sl.getItemCode() + "）退货数量 " + strip(qty)
                        + " 超过剩余可退数量 " + strip(retirable.max(BigDecimal.ZERO)));
            }
            if (nvl(sl.getInvoicedQty()).signum() > 0) {
                anyInvoiced = true;
            }
            SdReturnLine rl = new SdReturnLine();
            rl.setId(uuid());
            rl.setReturnId(ret.getId());
            rl.setLineNo(lineNo++);
            rl.setSoLineId(sl.getId());
            rl.setSoLineNo(sl.getLineNo());
            rl.setItemCode(sl.getItemCode());
            rl.setItemName(sl.getItemName());
            rl.setQty(qty);
            rl.setBaseUnit(sl.getBaseUnit());
            rl.setUnitPrice(sl.getUnitPrice());
            BigDecimal amount = nvl(sl.getUnitPrice()).multiply(qty).setScale(2, RoundingMode.HALF_UP);
            rl.setAmount(amount);
            rl.setWarehouseCode(isBlank(sl.getWarehouseCode()) ? "WH-MAIN" : sl.getWarehouseCode());
            rl.setLineStatus(SdReturnLine.LS_PENDING);
            rl.setCreateBy(currentUser());
            entityLines.add(rl);
            totalQty = totalQty.add(qty);
            totalAmt = totalAmt.add(amount);
        }
        // 开票状态分流（spec 12.2）：任一 SO 行已开票 → 已开票退货（判定通过后走红字）
        ret.setInvoiceFlag(anyInvoiced ? SdReturn.INV_INVOICED : SdReturn.INV_UNINVOICED);
        ret.setTotalQty(totalQty);
        ret.setTotalAmt(totalAmt);
        returnDao.insert(ret);
        for (SdReturnLine rl : entityLines) {
            lineDao.insert(rl);
        }
        log.info("return {} created (so={}, invoiced={}, qty={})", ret.getReturnNo(),
                so.getSoNo(), ret.getInvoiceFlag(), totalQty);
        return ret;
    }

    @Override
    public Page<SdReturn> page(long current, long size, String keyword, String status,
                               String customerId) {
        requireAny("查询退货单", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_ADMIN",
                "ROLE_FINANCE_MGR", "ROLE_WAREHOUSE");
        LambdaQueryWrapper<SdReturn> qw = new LambdaQueryWrapper<SdReturn>()
                .eq(isNotBlank(status), SdReturn::getStatus, status)
                .eq(isNotBlank(customerId), SdReturn::getCustomerId, customerId)
                .and(isNotBlank(keyword), w -> w.like(SdReturn::getReturnNo, keyword)
                        .or().like(SdReturn::getSoNo, keyword)
                        .or().like(SdReturn::getCustomerName, keyword))
                .orderByDesc(SdReturn::getApplyAt);
        return returnDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        requireAny("查询退货详情", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_ADMIN",
                "ROLE_FINANCE_MGR", "ROLE_WAREHOUSE");
        SdReturn ret = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("return", ret);
        out.put("lines", linesOf(id));
        out.put("judgeLogs", logDao.selectList(new LambdaQueryWrapper<SdReturnJudgeLog>()
                .eq(SdReturnJudgeLog::getReturnId, id)
                .orderByAsc(SdReturnJudgeLog::getOpAt)));
        if (isNotBlank(ret.getApprovalId())) {
            out.put("approval", approvalEngine.getInstance(ret.getApprovalId()));
        }
        out.put("approvalLogs", approvalEngine.logs("SdReturn", id));
        if (isNotBlank(ret.getRedInvoiceId())) {
            RedInvoice red = redDao.selectById(ret.getRedInvoiceId());
            out.put("redInvoice", red);
        }
        if (isNotBlank(ret.getExchangeShipId())) {
            Shipment ship = shipDao.selectById(ret.getExchangeShipId());
            out.put("exchangeShip", ship);
        }
        if (isNotBlank(ret.getShipId())) {
            out.put("originShip", shipDao.selectById(ret.getShipId()));
        }
        return out;
    }

    // ==================== 12.3 判定 ====================

    @Override
    @Transactional
    public SdReturn judge(String id, Map<String, Object> payload) {
        requireAny("退货判定", "ROLE_SALES_MGR", "ROLE_ADMIN");
        SdReturn ret = require(id);
        if (!SdReturn.ST_DRAFT.equals(ret.getStatus())
                && !SdReturn.ST_REJECTED.equals(ret.getStatus())) {
            throw new ServiceException(422, "仅草稿/已驳回可判定（当前：" + ret.getStatus() + "）");
        }
        String liability = str(payload.get("liability"));
        if (isBlank(liability)) {
            throw new ServiceException(422, "责任方必填（我方质量/客户原因/物流破损）");
        }
        String handle = str(payload.get("handle"));
        if (!SdReturn.HANDLE_REFUND.equals(handle) && !SdReturn.HANDLE_EXCHANGE.equals(handle)) {
            throw new ServiceException(422, "判定处理方式 REFUND/EXCHANGE 必选");
        }
        String basis = str(payload.get("basis"));
        if (isBlank(basis) || basis.trim().length() < 2) {
            throw new ServiceException(422, "判定依据必填（记录判定人/时间/依据，spec 12.3）");
        }
        Object rawLines = payload.get("lines");
        List<Map<String, Object>> judgeLines = rawLines == null ? List.of()
                : mapper.convertValue(rawLines,
                mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

        List<SdReturnLine> lines = linesOf(id);
        Map<String, BigDecimal> judgeMap = new LinkedHashMap<>();
        BigDecimal judgeQtyTotal = BigDecimal.ZERO;
        for (Map<String, Object> raw : judgeLines) {
            String lineId = str(raw.get("lineId"));
            BigDecimal judgeQty = num(raw.get("judgeQty"));
            SdReturnLine target = lines.stream()
                    .filter(l -> l.getId().equals(lineId)).findFirst().orElse(null);
            if (target == null) {
                throw new ServiceException(422, "退货行不存在：" + lineId);
            }
            if (judgeQty.signum() < 0 || judgeQty.compareTo(nvl(target.getQty())) > 0) {
                throw new ServiceException(422, "行 " + target.getLineNo() + " 核定可退数量非法（≤ "
                        + strip(target.getQty()) + "）");
            }
            judgeMap.put(lineId, judgeQty);
            judgeQtyTotal = judgeQtyTotal.add(judgeQty);
        }
        if (judgeMap.isEmpty()) {
            throw new ServiceException(422, "判定需给出每行核定可退数量");
        }
        if (judgeQtyTotal.signum() <= 0) {
            throw new ServiceException(422, "核定可退总数量必须大于 0（不可退请走判定驳回）");
        }

        // 超期自动判定（spec 12.3：超过约定退货期标记超期）
        int periodDays = paramService.getInt("RETURN_PERIOD_DAYS", 30);
        LocalDateTime applyAt = ret.getApplyAt() == null ? ret.getCreateDate() : ret.getApplyAt();
        boolean overdue = applyAt != null && applyAt.plusDays(periodDays).isBefore(LocalDateTime.now());
        String overdueNote = str(payload.get("overdueNote"));

        for (SdReturnLine l : lines) {
            BigDecimal jq = judgeMap.get(l.getId());
            l.setJudgeQty(jq == null ? BigDecimal.ZERO : jq);
            lineDao.updateById(l);
        }
        ret.setLiability(liability);
        ret.setJudgeHandle(handle);
        ret.setJudgeBy(currentUser());
        ret.setJudgeAt(LocalDateTime.now());
        ret.setJudgeNote(basis.trim());
        ret.setOverdue(overdue ? "1" : "0");
        ret.setOverdueNote(overdue ? (isBlank(overdueNote) ? basis.trim() : overdueNote) : null);
        ret.setStatus(SdReturn.ST_JUDGED);
        if (returnDao.updateById(ret) == 0) {
            throw new ServiceException(409, "退货单状态更新冲突");
        }
        writeLog(ret, SdReturnJudgeLog.OP_JUDGE, liability, handle,
                overdue ? "1" : "0", judgeQtyTotal, basis.trim(), judgeJson(judgeLines));
        log.info("return {} judged: liability={} handle={} overdue={}", ret.getReturnNo(),
                liability, handle, overdue);
        return ret;
    }

    @Override
    @Transactional
    public SdReturn judgeReject(String id, String reason) {
        requireAny("判定驳回", "ROLE_SALES_MGR", "ROLE_ADMIN");
        SdReturn ret = require(id);
        if (!SdReturn.ST_DRAFT.equals(ret.getStatus())
                && !SdReturn.ST_JUDGED.equals(ret.getStatus())) {
            throw new ServiceException(422, "当前状态不可判定驳回：" + ret.getStatus());
        }
        if (isBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "判定驳回原因必填（≥2 字）");
        }
        ret.setStatus(SdReturn.ST_REJECTED);
        returnDao.updateById(ret);
        writeLog(ret, SdReturnJudgeLog.OP_JUDGE_REJECT, ret.getLiability(),
                ret.getJudgeHandle(), ret.getOverdue(), null, reason.trim(), null);
        return ret;
    }

    // ==================== 12.4 L2 审批 ====================

    @Override
    @Transactional
    public SdReturn submit(String id) {
        requireAny("提交退货审批", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_ADMIN");
        SdReturn ret = require(id);
        if (!SdReturn.ST_JUDGED.equals(ret.getStatus())) {
            throw new ServiceException(422, "仅已判定的退货单可提交审批（当前：" + ret.getStatus() + "）");
        }
        String handle = isNotBlank(ret.getJudgeHandle()) ? ret.getJudgeHandle() : ret.getHandleType();
        List<List<ApprovalNodeSpec>> chain;
        if (SdReturn.HANDLE_REFUND.equals(handle)) {
            // 退款类 L2：销售经理 + 财务（spec 12.4）
            chain = List.of(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售经理审批")),
                    List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "财务审批")));
        } else {
            chain = List.of(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售经理审批")));
        }
        String title = "退货审批：" + ret.getReturnNo() + " / " + ret.getCustomerName()
                + " / " + ("1".equals(ret.getOverdue()) ? "超期·" : "")
                + (SdReturn.HANDLE_REFUND.equals(handle) ? "退款 L2" : "换货")
                + " / ¥" + strip(ret.getTotalAmt());
        var inst = approvalEngine.submit("SdReturn", ret.getId(), title, null, chain);
        ret.setApprovalId(inst.getId());
        ret.setStatus(SdReturn.ST_APPROVING);
        ret.setSubmitAt(LocalDateTime.now());
        returnDao.updateById(ret);
        writeLog(ret, SdReturnJudgeLog.OP_SUBMIT, ret.getLiability(), handle,
                ret.getOverdue(), null, "提交审批", null);
        return ret;
    }

    // ==================== 12.5 退款执行 ====================

    @Override
    @Transactional
    public Map<String, Object> refund(String id, BigDecimal refundAmt, LocalDate refundDate,
                                      String payNo) {
        requireAny("退款执行", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        SdReturn ret = require(id);
        if (!SdReturn.ST_APPROVED.equals(ret.getStatus())) {
            throw new ServiceException(422, "仅审批通过的退款单可执行（当前：" + ret.getStatus() + "）");
        }
        if (!SdReturn.HANDLE_REFUND.equals(ret.getJudgeHandle())) {
            throw new ServiceException(422, "判定为换货，请走换货执行（退款/换货互斥 BR-4.3-70）");
        }
        List<SdReturnLine> lines = linesOf(id).stream()
                .filter(l -> nvl(l.getJudgeQty()).signum() > 0).toList();
        if (lines.isEmpty()) {
            throw new ServiceException(422, "无可退核定数量，不可退款");
        }
        BigDecimal due = BigDecimal.ZERO;
        for (SdReturnLine l : lines) {
            due = due.add(nvl(l.getUnitPrice()).multiply(nvl(l.getJudgeQty()))
                    .setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal amt = refundAmt == null ? due : refundAmt;
        if (amt.signum() <= 0) {
            throw new ServiceException(422, "退款金额必须大于 0");
        }
        BigDecimal already = nvl(ret.getRefundAmt());
        if (already.add(amt).compareTo(due) > 0) {
            throw new ServiceException(422, "累计退款超出应退金额 " + strip(due)
                    + "（已退 " + strip(already) + "）");
        }
        LocalDate date = refundDate == null ? LocalDate.now() : refundDate;

        RedInvoice red = null;
        String creditAccount = "1122";
        if (SdReturn.INV_INVOICED.equals(ret.getInvoiceFlag())) {
            // ---- 已开票：红字发票（本地建模，关联原票与退货单）+ 应收红冲 ----
            SalesInvoice origin = invoiceDao.selectList(new LambdaQueryWrapper<SalesInvoice>()
                            .eq(SalesInvoice::getSoId, ret.getSoId())
                            .in(SalesInvoice::getStatus, SalesInvoice.ST_ISSUED,
                                    SalesInvoice.ST_CONFIRMED, SalesInvoice.ST_DISPUTED)
                            .orderByDesc(SalesInvoice::getInvoiceDate)
                            .last("LIMIT 1"))
                    .stream().findFirst().orElse(null);
            if (origin == null) {
                throw new ServiceException(422, "已开票退货未找到原销项发票（SO "
                        + ret.getSoNo() + "），无法红冲");
            }
            red = invoiceService.redInvoice(origin.getId(), ret.getId(), ret.getReturnNo(),
                    amt, "退货退款 " + ret.getReturnNo());
            ret.setRedInvoiceId(red.getId());
            ret.setRedInvoiceNo(red.getRedNo());
        } else {
            // ---- 未开票：不生成红字发票，直接冲减应收事实记录（spec 场景） ----
            ArInvoice ar = arDao.selectList(new LambdaQueryWrapper<ArInvoice>()
                            .eq(ArInvoice::getSoId, ret.getSoId())
                            .last("LIMIT 1"))
                    .stream().findFirst().orElse(null);
            if (ar != null) {
                ar.setRedAmount(nvl(ar.getRedAmount()).add(amt));
                BigDecimal balance = nvl(ar.getAmount()).subtract(nvl(ar.getRedAmount()))
                        .subtract(nvl(ar.getPaidAmount()));
                ar.setStatus(balance.signum() <= 0 ? ArInvoice.ST_PAID
                        : (nvl(ar.getPaidAmount()).signum() > 0 ? ArInvoice.ST_PARTIAL
                        : ArInvoice.ST_UNPAID));
                arDao.updateById(ar);
                creditAccount = "1122";
            } else {
                creditAccount = "1002";
            }
        }

        // 红冲凭证：借 6001 主营业务收入 / 贷 1122 应收（无应收直接退钱 → 贷 1002）
        List<GlVoucherService.FinVoucherLineSpec> vLines = new ArrayList<>();
        vLines.add(GlVoucherService.FinVoucherLineSpec.of("6001", "DR", amt,
                "退货退款红冲 " + ret.getReturnNo()));
        vLines.add(GlVoucherService.FinVoucherLineSpec.of(creditAccount, "CR", amt,
                "退货退款 " + ret.getCustomerName()));
        var v = voucherService.create("SRT", date,
                "退货退款红冲凭证 " + ret.getReturnNo() + " " + strip(amt),
                "S_RETURN", ret.getReturnNo(), null, vLines);
        ret.setVoucherId(v.getId());
        ret.setVoucherNo(v.getVoucherNo());

        ret.setRefundAmt(already.add(amt));
        ret.setRefundAt(LocalDateTime.now());
        ret.setRefundBy(currentUser());
        if (isNotBlank(payNo)) {
            ret.setRefundPayNo(payNo);
        }
        ret.setStatus(SdReturn.ST_DONE);
        if (returnDao.updateById(ret) == 0) {
            throw new ServiceException(409, "退货单状态更新冲突");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("refundAmt", amt);
        detail.put("redInvoiceNo", red == null ? null : red.getRedNo());
        detail.put("voucherNo", v.getVoucherNo());
        detail.put("creditAccount", creditAccount);
        writeLog(ret, SdReturnJudgeLog.OP_REFUND, ret.getLiability(), ret.getJudgeHandle(),
                ret.getOverdue(), amt, isBlank(payNo) ? "全额退款" : "退款支付登记 " + payNo,
                toJson(detail));
        log.info("return {} refunded {} voucher {}", ret.getReturnNo(), strip(amt),
                v.getVoucherNo());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("return", ret);
        out.put("redInvoice", red);
        out.put("voucherNo", v.getVoucherNo());
        return out;
    }

    // ==================== 12.6 换货执行 ====================

    @Override
    @Transactional
    public Map<String, Object> exchange(String id) {
        requireAny("换货执行", "ROLE_SALES_MGR", "ROLE_SALES", "ROLE_WAREHOUSE", "ROLE_ADMIN");
        SdReturn ret = require(id);
        if (!SdReturn.ST_APPROVED.equals(ret.getStatus())) {
            throw new ServiceException(422, "仅审批通过的换货单可执行（当前：" + ret.getStatus() + "）");
        }
        if (!SdReturn.HANDLE_EXCHANGE.equals(ret.getJudgeHandle())) {
            throw new ServiceException(422, "判定为退款，请走退款执行（退款/换货互斥 BR-4.3-70）");
        }
        List<SdReturnLine> lines = linesOf(id).stream()
                .filter(l -> nvl(l.getJudgeQty()).signum() > 0).toList();
        if (lines.isEmpty()) {
            throw new ServiceException(422, "无可退核定数量，不可换货");
        }
        // 生成关联退货单的新发货单 + 同事务重走 ATP 锁批；库存不足 → 422 阻断（spec 场景）
        Map<String, Object> gen = shipmentService.exchangeFromReturn(ret, lines);
        @SuppressWarnings("unchecked")
        Shipment ship = (Shipment) gen.get("shipment");
        ret.setExchangeShipId(ship.getId());
        ret.setExchangeShipNo(ship.getShipNo());
        ret.setStatus(SdReturn.ST_DONE);
        if (returnDao.updateById(ret) == 0) {
            throw new ServiceException(409, "退货单状态更新冲突");
        }
        writeLog(ret, SdReturnJudgeLog.OP_EXCHANGE, ret.getLiability(), ret.getJudgeHandle(),
                ret.getOverdue(), ret.getTotalQty(),
                "换货发货单 " + ship.getShipNo(), null);
        log.info("return {} exchange ship {}", ret.getReturnNo(), ship.getShipNo());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("return", ret);
        out.put("shipment", ship);
        out.put("lines", gen.get("lines"));
        return out;
    }

    // ==================== 12.7 实物入库 ====================

    @Override
    @Transactional
    public Map<String, Object> stockIn(String id, List<Map<String, Object>> items) {
        requireAny("退货实物入库", "ROLE_WAREHOUSE", "ROLE_ADMIN");
        SdReturn ret = require(id);
        String st = ret.getStatus();
        if (SdReturn.ST_DRAFT.equals(st) || SdReturn.ST_REJECTED.equals(st)
                || SdReturn.ST_CANCELLED.equals(st)) {
            throw new ServiceException(422, "待判定/已驳回/已撤销的退货单不可入库（当前：" + st + "）");
        }
        if (items == null || items.isEmpty()) {
            throw new ServiceException(422, "入库明细必填");
        }
        List<SdReturnLine> lines = linesOf(id);
        BigDecimal stockAmt = BigDecimal.ZERO;
        List<Map<String, Object>> done = new ArrayList<>();
        for (Map<String, Object> raw : items) {
            String lineId = str(raw.get("lineId"));
            String batchNo = str(raw.get("batchNo"));
            BigDecimal qty = num(raw.get("qty"));
            boolean qc = "1".equals(str(raw.get("qc"))) || Boolean.TRUE.equals(raw.get("qc"));
            SdReturnLine line = lines.stream().filter(l -> l.getId().equals(lineId))
                    .findFirst().orElse(null);
            if (line == null) {
                throw new ServiceException(422, "退货行不存在：" + lineId);
            }
            if (qty.signum() <= 0) {
                throw new ServiceException(422, "入库数量必须大于 0");
            }
            if (isBlank(batchNo)) {
                throw new ServiceException(422, "批次号必填（按批次回补，spec 12.7）");
            }
            BigDecimal cap = nvl(line.getJudgeQty()).signum() > 0
                    ? line.getJudgeQty() : line.getQty();
            BigDecimal in = nvl(line.getInQty());
            if (in.add(qty).compareTo(cap) > 0) {
                throw new ServiceException(422, "行 " + line.getLineNo() + " 入库量超出核定可退量（上限 "
                        + strip(cap) + "，已入 " + strip(in) + "）");
            }
            String wh = isBlank(line.getWarehouseCode()) ? "WH-MAIN" : line.getWarehouseCode();

            // 按批次回补经通用引擎（add-stock-posting-engine，sales-return MODIFIED：
            // SALES_RETURN_IN 流水、首插记 INBOUND_DATE 当日补货不刷新、批次台账联动建档；
            // qc=1 → QC_QTY（不计 ATP）；否则 AVAILABLE_QTY（可被预留占用）
            com.erp.service.inv.StockPostingEngine.Line el =
                    new com.erp.service.inv.StockPostingEngine.Line();
            el.warehouseCode = wh;
            el.itemCode = line.getItemCode();
            el.itemName = line.getItemName();
            el.batchNo = batchNo;
            el.qty = qty;
            el.intoQc = qc;
            stockPostingEngine.post(com.erp.service.inv.StockPostingEngine.Request.of(
                    "SALES_RETURN_IN", "RETURN", ret.getReturnNo(), List.of(el)));

            line.setInQty(in.add(qty));
            line.setStockBatchNo(batchNo);
            line.setQcFlag(qc ? "1" : "0");
            lineDao.updateById(line);
            stockAmt = stockAmt.add(nvl(line.getUnitPrice()).multiply(qty)
                    .setScale(2, RoundingMode.HALF_UP));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("lineId", lineId);
            d.put("itemCode", line.getItemCode());
            d.put("batchNo", batchNo);
            d.put("qty", qty);
            d.put("qc", qc);
            done.add(d);
        }

        // 退货入库凭证：借 1405 库存商品 / 贷 6401 主营业务成本
        String voucherNo = null;
        if (stockAmt.signum() > 0) {
            List<GlVoucherService.FinVoucherLineSpec> vLines = new ArrayList<>();
            vLines.add(GlVoucherService.FinVoucherLineSpec.of("1405", "DR", stockAmt,
                    "退货入库 " + ret.getReturnNo()));
            vLines.add(GlVoucherService.FinVoucherLineSpec.of("6401", "CR", stockAmt,
                    "退货入库冲减成本 " + ret.getReturnNo()));
            var v = voucherService.create("SIN", LocalDate.now(),
                    "退货入库凭证 " + ret.getReturnNo() + " " + strip(stockAmt),
                    "S_STOCK_IN", ret.getReturnNo(), null, vLines);
            voucherNo = v.getVoucherNo();
        }

        // 全部行入齐 → 实物入库完成
        List<SdReturnLine> fresh = linesOf(id);
        boolean allIn = fresh.stream().allMatch(l -> {
            BigDecimal cap = nvl(l.getJudgeQty()).signum() > 0 ? l.getJudgeQty() : l.getQty();
            return cap.signum() <= 0 || nvl(l.getInQty()).compareTo(cap) >= 0;
        });
        if (allIn) {
            ret.setStockIn("1");
            ret.setStockInAt(LocalDateTime.now());
            ret.setStockInBy(currentUser());
            returnDao.updateById(ret);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("items", done);
        detail.put("voucherNo", voucherNo);
        detail.put("stockIn", allIn);
        writeLog(ret, SdReturnJudgeLog.OP_STOCK_IN, ret.getLiability(), ret.getJudgeHandle(),
                ret.getOverdue(), stockAmt,
                "实物入库" + (allIn ? "完成" : "部分") + (voucherNo == null ? "" : "，凭证 " + voucherNo),
                toJson(detail));
        log.info("return {} stock-in {} (voucher={})", ret.getReturnNo(), strip(stockAmt),
                voucherNo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("return", ret);
        out.put("voucherNo", voucherNo);
        out.put("stockIn", allIn);
        out.put("items", done);
        return out;
    }

    @Override
    @Transactional
    public SdReturn cancel(String id, String reason) {
        requireAny("撤销退货单", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_ADMIN");
        SdReturn ret = require(id);
        if (!SdReturn.ST_DRAFT.equals(ret.getStatus())
                && !SdReturn.ST_REJECTED.equals(ret.getStatus())) {
            throw new ServiceException(422, "仅草稿/已驳回可撤销（当前：" + ret.getStatus() + "）");
        }
        if (isBlank(reason)) {
            throw new ServiceException(422, "撤销原因必填");
        }
        ret.setStatus(SdReturn.ST_CANCELLED);
        ret.setRemark((ret.getRemark() == null ? "" : ret.getRemark() + "；") + "撤销：" + reason);
        returnDao.updateById(ret);
        return ret;
    }

    // ==================== 私有工具 ====================

    /** 该 SO 各行已被未终止退货单占用的申请数量（CANCELLED/REJECTED 不占用） */
    private Map<String, BigDecimal> returnedBySoLine(String soId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        List<SdReturn> returns = returnDao.selectList(new LambdaQueryWrapper<SdReturn>()
                .eq(SdReturn::getSoId, soId)
                .notIn(SdReturn::getStatus, SdReturn.ST_CANCELLED, SdReturn.ST_REJECTED));
        if (returns.isEmpty()) {
            return out;
        }
        List<String> ids = returns.stream().map(SdReturn::getId).toList();
        List<SdReturnLine> rLines = lineDao.selectList(new LambdaQueryWrapper<SdReturnLine>()
                .in(SdReturnLine::getReturnId, ids));
        for (SdReturnLine l : rLines) {
            if (l.getSoLineId() == null) {
                continue;
            }
            out.merge(l.getSoLineId(), nvl(l.getQty()), BigDecimal::add);
        }
        return out;
    }

    private List<SdReturnLine> linesOf(String returnId) {
        return lineDao.selectList(new LambdaQueryWrapper<SdReturnLine>()
                .eq(SdReturnLine::getReturnId, returnId)
                .orderByAsc(SdReturnLine::getLineNo));
    }

    private void writeLog(SdReturn ret, String opType, String liability, String handle,
                          String overdue, BigDecimal judgeQty, String basis, String detail) {
        SdReturnJudgeLog l = new SdReturnJudgeLog();
        l.setId(uuid());
        l.setReturnId(ret.getId());
        l.setReturnNo(ret.getReturnNo());
        l.setOpType(opType);
        l.setLiability(liability);
        l.setJudgeHandle(handle);
        l.setOverdue(overdue);
        l.setJudgeQty(judgeQty);
        l.setBasis(basis);
        l.setDetail(detail);
        l.setOpBy(currentUser());
        l.setOpAt(LocalDateTime.now());
        l.setCreateBy(currentUser());
        logDao.insert(l);
    }

    private String judgeJson(List<Map<String, Object>> judgeLines) {
        try {
            return mapper.writeValueAsString(judgeLines);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private SdReturn require(String id) {
        SdReturn ret = returnDao.selectById(id);
        if (ret == null) {
            throw new ServiceException(404, "退货单不存在：" + id);
        }
        return ret;
    }

    private So requireSo(String soId) {
        So so = soDao.selectById(soId);
        if (so == null) {
            throw new ServiceException(404, "销售订单不存在：" + soId);
        }
        return so;
    }

    private List<SoLine> soLines(String soId) {
        return soLineDao.selectList(new LambdaQueryWrapper<SoLine>()
                .eq(SoLine::getSoId, soId)
                .orderByAsc(SoLine::getLineNo));
    }

    private String nextReturnNo() {
        String prefix = "RT" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        int max = 0;
        List<String> nos = returnDao.selectList(new LambdaQueryWrapper<SdReturn>()
                        .select(SdReturn::getReturnNo)
                        .likeRight(SdReturn::getReturnNo, prefix))
                .stream().map(SdReturn::getReturnNo).toList();
        for (String no : nos) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 跳过非规范编号
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private void requireAny(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = List.of(allowed);
        boolean ok = auth.getAuthorities().stream()
                .anyMatch(a -> roles.contains(a.getAuthority()));
        if (!ok) {
            throw new ServiceException(403, "无权限" + action);
        }
    }

    private String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "system" : auth.getName();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal num(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String strip(BigDecimal v) {
        return nvl(v).stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
