package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinInternalInvoiceDao;
import com.erp.dao.mdm.MdmLegalEntityDao;
import com.erp.entity.fin.FinInternalInvoice;
import com.erp.entity.inv.InvTransferOrder;
import com.erp.entity.mdm.MdmLegalEntity;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.inv.InternalTransferAccountingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

/**
 * 跨法人调拨内部核算实现（spec internal-transfer-accounting，design D4）。
 * 依赖注入口径：GlVoucherService 统一分录入口（凭证号自动、借贷平衡 L1、生成即 POSTED）。
 */
@Slf4j
@Service
public class InternalTransferAccountingServiceImpl implements InternalTransferAccountingService {

    /** 凭证类型（自由串，语义=调拨凭证；复用 gl-voucher 契约） */
    private static final String VT_TRANSFER = "TRF";
    private static final String SOURCE_TRANSFER = "TRANSFER";
    private static final BigDecimal VAT_RATE = new BigDecimal("0.13");
    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMM");

    private final MdmLegalEntityDao legalEntityDao;
    private final GlVoucherService voucherService;
    private final FinInternalInvoiceDao invoiceDao;

    public InternalTransferAccountingServiceImpl(MdmLegalEntityDao legalEntityDao,
                                                 GlVoucherService voucherService,
                                                 FinInternalInvoiceDao invoiceDao) {
        this.legalEntityDao = legalEntityDao;
        this.voucherService = voucherService;
        this.invoiceDao = invoiceDao;
    }

    @Override
    public void checkTaxQualified(InvTransferOrder order) {
        if (!isCross(order)) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String le : new String[]{order.getOutLeCode(), order.getInLeCode()}) {
            MdmLegalEntity e = requireLe(le);
            if (isBlank(e.getUscc())) {
                missing.add(le + ".USCC");
            }
            if (isBlank(e.getLocalTaxNo())) {
                missing.add(le + ".LOCAL_TAX_NO");
            }
        }
        if (!missing.isEmpty()) {
            // C-4.3-06 语义 L1：数据源换法人主数据（偏差记录），过账前阻断无残留
            throw new ServiceException(422, "法人税务资质缺失，阻断跨法人调拨过账："
                    + String.join("、", missing) + "（请补全法人主数据税务信息）");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void onOutPosted(InvTransferOrder order) {
        if (!isCross(order)) {
            return;
        }
        BigDecimal amt = order.getTotalAmount();
        // F1：内部销售凭证（调出方）——借 调拨在途 / 贷 原材料
        voucherService.create(VT_TRANSFER, LocalDate.now(),
                "跨法人调拨内部销售 " + order.getTransferNo(),
                SOURCE_TRANSFER, order.getTransferNo(), null,
                List.of(
                        GlVoucherService.FinVoucherLineSpec.of("1461", "DR", amt,
                                "调拨在途 " + order.getOutWhCode() + "→" + order.getInWhCode()),
                        GlVoucherService.FinVoucherLineSpec.of("1403", "CR", amt,
                                "调拨出库 " + order.getTransferNo())));
        // F3：内部销售发票（OUT，调出方视角）
        FinInternalInvoice inv = newInvoice(order, FinInternalInvoice.DIR_OUT,
                order.getOutLeCode(), amt);
        invoiceDao.insert(inv);
        log.info("transfer {} out: voucher TRF + internal invoice {} ({} LE {} -> {})",
                order.getTransferNo(), inv.getIntInvNo(), FinInternalInvoice.DIR_OUT,
                order.getOutLeCode(), order.getInLeCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void onInPosted(InvTransferOrder order) {
        if (!isCross(order)) {
            return;
        }
        BigDecimal amt = order.getTotalAmount();
        // F1：内部采购凭证（调入方）——借 原材料 / 贷 调拨在途（在途核销归零）
        voucherService.create(VT_TRANSFER, LocalDate.now(),
                "跨法人调拨内部采购 " + order.getTransferNo(),
                SOURCE_TRANSFER, order.getTransferNo(), null,
                List.of(
                        GlVoucherService.FinVoucherLineSpec.of("1403", "DR", amt,
                                "调拨入库 " + order.getTransferNo()),
                        GlVoucherService.FinVoucherLineSpec.of("1461", "CR", amt,
                                "调拨在途核销 " + order.getOutWhCode() + "→" + order.getInWhCode())));
        // F3：内部采购发票（IN，调入方视角）
        FinInternalInvoice inv = newInvoice(order, FinInternalInvoice.DIR_IN,
                order.getInLeCode(), amt);
        invoiceDao.insert(inv);
        // 自动配对核销：同调拨单 OUT 票（ISSUED）↔ 本 IN 票（条件更新幂等）
        FinInternalInvoice out = invoiceDao.selectOne(new LambdaQueryWrapper<FinInternalInvoice>()
                .eq(FinInternalInvoice::getTransferNo, order.getTransferNo())
                .eq(FinInternalInvoice::getDirection, FinInternalInvoice.DIR_OUT)
                .eq(FinInternalInvoice::getStatus, FinInternalInvoice.ST_ISSUED)
                .orderByAsc(FinInternalInvoice::getCreateDate)
                .last("LIMIT 1"));
        if (out != null) {
            settleOne(out.getId(), inv.getId());
            settleOne(inv.getId(), out.getId());
        } else {
            log.warn("transfer {} in-posted without ISSUED OUT invoice (pair deferred)",
                    order.getTransferNo());
        }
        log.info("transfer {} in: voucher TRF + internal invoice {} paired={}",
                order.getTransferNo(), inv.getIntInvNo(), out != null);
    }

    @Override
    public Map<String, Object> settlementCheck(InvTransferOrder order) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        if (isCross(order)) {
            // ① 在途科目核销：出库凭证 + 入库成对（sourceDocNo 锚点反查）
            int voucherCount = voucherService.listBySource(SOURCE_TRANSFER,
                    order.getTransferNo()).size();
            if (voucherCount < 2) {
                pending.add("调拨在途凭证未核销（已生成 " + voucherCount + "/2 张，缺入库段凭证）");
            }
            // ② 票对 SETTLED
            List<FinInternalInvoice> issued = invoiceDao.selectList(
                    new LambdaQueryWrapper<FinInternalInvoice>()
                            .eq(FinInternalInvoice::getTransferNo, order.getTransferNo())
                            .eq(FinInternalInvoice::getStatus, FinInternalInvoice.ST_ISSUED));
            for (FinInternalInvoice i : issued) {
                pending.add("内部发票未配对：" + i.getIntInvNo()
                        + "（" + i.getDirection() + "，" + i.getTotalAmount() + "）");
            }
        }
        out.put("settled", pending.isEmpty());
        out.put("pending", pending);
        return out;
    }

    @Override
    public Map<String, Object> receivableView() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", invoiceDao.sumIssuedByPair());
        return out;
    }

    @Override
    public Map<String, Object> receivableDetail(String outLe, String inLe, String direction,
                                                String status) {
        List<FinInternalInvoice> list = invoiceDao.selectList(
                new LambdaQueryWrapper<FinInternalInvoice>()
                        .eq(!isBlank(outLe), FinInternalInvoice::getOutLeCode, outLe)
                        .eq(!isBlank(inLe), FinInternalInvoice::getInLeCode, inLe)
                        .eq(!isBlank(direction), FinInternalInvoice::getDirection, direction)
                        .eq(!isBlank(status), FinInternalInvoice::getStatus, status)
                        .orderByDesc(FinInternalInvoice::getCreateDate));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", list);
        out.put("total", list.size());
        return out;
    }

    // ---------- helpers ----------

    private FinInternalInvoice newInvoice(InvTransferOrder order, String direction,
                                          String issuerLe, BigDecimal net) {
        FinInternalInvoice inv = new FinInternalInvoice();
        inv.setIntInvNo(nextNo());
        inv.setDirection(direction);
        inv.setTransferNo(order.getTransferNo());
        inv.setOutLeCode(order.getOutLeCode());
        inv.setInLeCode(order.getInLeCode());
        inv.setIssuerLeCode(issuerLe);
        inv.setNetAmount(net);
        inv.setTaxRate(VAT_RATE);
        BigDecimal tax = net.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);
        inv.setTaxAmount(tax);
        inv.setTotalAmount(net.add(tax));
        inv.setStatus(FinInternalInvoice.ST_ISSUED);
        inv.setRemark("跨法人调拨内部发票（桩口径，偏差 D2）");
        return inv;
    }

    private void settleOne(String id, String pairId) {
        FinInternalInvoice upd = new FinInternalInvoice();
        upd.setId(id);
        upd.setVerNo(current(id).getVerNo());
        upd.setPairId(pairId);
        upd.setStatus(FinInternalInvoice.ST_SETTLED);
        upd.setSettledAt(LocalDateTime.now());
        invoiceDao.updateById(upd);
    }

    private FinInternalInvoice current(String id) {
        FinInternalInvoice i = invoiceDao.selectById(id);
        if (i == null) {
            throw new ServiceException(404, "内部发票不存在");
        }
        return i;
    }

    private MdmLegalEntity requireLe(String leCode) {
        MdmLegalEntity e = legalEntityDao.selectOne(new LambdaQueryWrapper<MdmLegalEntity>()
                .eq(MdmLegalEntity::getLeCode, leCode));
        if (e == null) {
            throw new ServiceException(422, "法人不存在，阻断跨法人调拨过账：" + leCode);
        }
        return e;
    }

    private String nextNo() {
        String prefix = "IT" + LocalDate.now().format(NO_DAY) + "-";
        int max = 0;
        for (String no : invoiceDao.selectNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private boolean isCross(InvTransferOrder order) {
        return "1".equals(order.getCrossLe());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
