package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinAccrualDao;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.entity.fin.FinAccrual;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.service.fin.AccrualService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.ThreeWayMatchService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 应付暂估实现（2.7.1，spec ap-accrual，design D3~D6）。
 * 冲回内核条件更新幂等；部分冲减用 OFFSETTED_AMOUNT 累加；挂起批次（合并未确认）不参与任何自动动作。
 */
@Slf4j
@Service
public class AccrualServiceImpl implements AccrualService {

    /** 存货（原材料）—— 暂估借方科目 */
    public static final String ACCT_INVENTORY = "1403";
    /** 应付暂估 */
    public static final String ACCT_ACCRUAL = "2203";
    /** 应付账款 */
    public static final String ACCT_AP = "2202";

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final FinAccrualDao accrualDao;
    private final GlVoucherService voucherService;
    private final GoodsReceiptDao grDao;
    private final GoodsReceiptLineDao grLineDao;
    /** 循环依赖（确认批次 → 重跑匹配）：@Lazy 代理延迟解析 */
    private final ThreeWayMatchService matchService;

    public AccrualServiceImpl(FinAccrualDao accrualDao,
                              GlVoucherService voucherService,
                              GoodsReceiptDao grDao,
                              GoodsReceiptLineDao grLineDao,
                              @Lazy ThreeWayMatchService matchService) {
        this.accrualDao = accrualDao;
        this.voucherService = voucherService;
        this.grDao = grDao;
        this.grLineDao = grLineDao;
        this.matchService = matchService;
    }

    // ================================================================
    // 3.1 / 3.2 生成
    // ================================================================

    @Override
    @Transactional
    public FinAccrual createFromGr(GoodsReceipt gr, List<GoodsReceiptLine> postedLines) {
        if (gr == null || postedLines == null) {
            throw new ServiceException(422, "缺少收货单或过账行，无法生成应付暂估");
        }
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (GoodsReceiptLine gl : postedLines) {
            if (gl.getUnitPrice() == null) {
                continue;                       // FREE 无单价行不计价（spec ap-accrual）
            }
            if (nvl(gl.getWithinToleranceQty()).signum() <= 0) {
                continue;
            }
            total = total.add(nvl(gl.getWithinToleranceQty())
                    .multiply(gl.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
        }
        if (total.signum() <= 0) {
            log.info("GR {} posting {} 全部过账行无单价，跳过应付暂估生成（BR-4.2-30 免生成）",
                    gr.getGrNo(), gr.getPostingDocNo());
            return null;
        }
        FinAccrual a = newAccrual(FinAccrual.SRC_GR, gr.getGrNo(), gr.getPostingDocNo(),
                gr.getPoNo(), gr.getSupplierId(), gr.getSupplierName(), total);
        FinVoucher v = voucherService.create(FinVoucher.TYPE_ACCRUAL, LocalDate.now(),
                "入库暂估：" + gr.getGrNo(), "GR", gr.getPostingDocNo(), gr.getSupplierId(),
                List.of(GlVoucherService.FinVoucherLineSpec.of(ACCT_INVENTORY, "DR", total,
                        "借 存货（入库 " + gr.getGrNo() + "）"),
                        GlVoucherService.FinVoucherLineSpec.of(ACCT_ACCRUAL, "CR", total,
                                "贷 应付暂估（BR-4.2-30）")));
        a.setVoucherId(v.getId());
        accrualDao.updateById(a);
        log.info("GR {} 生成暂估 {} 金额 {} 凭证 {}", gr.getGrNo(), a.getAccrualNo(), total, v.getVoucherNo());
        return a;
    }

    @Override
    @Transactional
    public FinAccrual createFromVmiTransfer(String sourceType, String transferDocNo, String supplierId,
                                            String supplierName, String poNo, BigDecimal qty,
                                            BigDecimal agreementPrice) {
        if (!FinAccrual.SRC_GR.equals(sourceType) && !FinAccrual.SRC_VMI.equals(sourceType)) {
            throw new ServiceException(422, "暂估来源仅支持 GR 与 VMI_TRANSFER：" + sourceType);
        }
        if (!FinAccrual.SRC_VMI.equals(sourceType)) {
            throw new ServiceException(422, "GR 来源由入库过账自动触发，请走收货过账链路");
        }
        if (!hasText(transferDocNo)) {
            throw new ServiceException(422, "寄售转自有凭证号必填");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "物权转移数量必须大于 0");
        }
        if (agreementPrice == null || agreementPrice.signum() <= 0) {
            throw new ServiceException(422, "协议价必填且必须大于 0");
        }
        if (!hasText(supplierId)) {
            throw new ServiceException(422, "供应商必填");
        }
        BigDecimal amount = qty.multiply(agreementPrice).setScale(2, RoundingMode.HALF_UP);
        FinAccrual a = newAccrual(FinAccrual.SRC_VMI, null, transferDocNo, poNo,
                supplierId, supplierName, amount);
        FinVoucher v = voucherService.create(FinVoucher.TYPE_ACCRUAL, LocalDate.now(),
                "寄售转自有暂估：" + transferDocNo, "VMI_TRANSFER", transferDocNo, supplierId,
                List.of(GlVoucherService.FinVoucherLineSpec.of(ACCT_INVENTORY, "DR", amount,
                        "借 存货（物权转移 " + transferDocNo + "）"),
                        GlVoucherService.FinVoucherLineSpec.of(ACCT_ACCRUAL, "CR", amount,
                                "贷 应付暂估（FR-4.2-8-3）")));
        a.setVoucherId(v.getId());
        accrualDao.updateById(a);
        log.info("VMI 转自有 {} 生成暂估 {} 金额 {}", transferDocNo, a.getAccrualNo(), amount);
        return a;
    }

    // ================================================================
    // 3.3 / 3.4 冲回
    // ================================================================

    @Override
    @Transactional
    public FinAccrual reverse(String accrualNo, String invoiceNo, String reason, String source) {
        FinAccrual a = requireByNo(accrualNo);
        int rows = accrualDao.update(null, new LambdaUpdateWrapper<FinAccrual>()
                .eq(FinAccrual::getId, a.getId())
                .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN)
                .set(FinAccrual::getStatus, FinAccrual.ST_REVERSED)
                .set(FinAccrual::getReverseSource, source)
                .set(FinAccrual::getReverseInvoiceNo, invoiceNo)
                .set(FinAccrual::getReverseReason, reason)
                .set(FinAccrual::getReverseBy, SecurityUtils.getCurrentUserId())
                .set(FinAccrual::getReverseAt, LocalDateTime.now()));
        if (rows == 0) {
            FinAccrual cur = requireByNo(accrualNo);
            if (FinAccrual.ST_REVERSED.equals(cur.getStatus())) {
                throw new ServiceException(422, "该暂估已冲回，不允许重复操作：" + accrualNo);
            }
            throw new ServiceException(422, "暂估状态不可冲回：" + cur.getStatus());
        }
        // 凭证侧：手工冲回生成红字冲销凭证；AUTO_MATCH 由三方匹配的正式应付凭证承担冲回
        // （spec ap-accrual「红字或正式凭证」——两者并做会双重计入暂估科目）
        if (!FinAccrual.REV_AUTO.equals(source) && hasText(a.getVoucherId())) {
            voucherService.reverse(a.getVoucherId(), reason, accrualNo);
        }
        log.info("暂估 {} 冲回 source={} invoice={} reason={}", accrualNo, source, invoiceNo, reason);
        return requireByNo(accrualNo);
    }

    @Override
    @Transactional
    public FinAccrual reverseManual(String accrualNo, String invoiceNo, String reason) {
        requireRole("手工冲回", "ROLE_ADMIN");
        if (!hasText(invoiceNo)) {
            throw new ServiceException(422, "必须填写关联发票号");
        }
        if (!hasText(reason)) {
            throw new ServiceException(422, "必须填写冲回原因");
        }
        return reverse(accrualNo, invoiceNo, reason, FinAccrual.REV_MANUAL);
    }

    // ================================================================
    // 3.5 退货冲减
    // ================================================================

    @Override
    @Transactional
    public BigDecimal offset(String supplierId, String poNo, BigDecimal amount, String returnNo) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal remaining = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal consumed = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if (hasText(supplierId) && hasText(poNo)) {
            // 挂起批次跳过留痕（BR-4.1-29：合并迁移未确认不参与冲减）
            Long suspended = accrualDao.selectCount(new LambdaQueryWrapper<FinAccrual>()
                    .eq(FinAccrual::getSupplierId, supplierId)
                    .eq(FinAccrual::getPoNo, poNo)
                    .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN)
                    .eq(FinAccrual::getMigrationConfirmed, 0));
            if (suspended != null && suspended > 0) {
                log.info("退货 {} 冲减跳过 {} 条挂起暂估（合并迁移批次未财务确认）", returnNo, suspended);
            }
            List<FinAccrual> list = accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                    .eq(FinAccrual::getSupplierId, supplierId)
                    .eq(FinAccrual::getPoNo, poNo)
                    .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN)
                    .and(w -> w.isNull(FinAccrual::getMigrationConfirmed)
                            .or().eq(FinAccrual::getMigrationConfirmed, 1))
                    .orderByDesc(FinAccrual::getCreateDate));
            for (FinAccrual a : list) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal available = nvl(a.getAmount()).subtract(nvl(a.getOffsettedAmount()));
                if (available.signum() <= 0) {
                    continue;
                }
                BigDecimal take = remaining.min(available);
                int rows = accrualDao.update(null, new LambdaUpdateWrapper<FinAccrual>()
                        .eq(FinAccrual::getId, a.getId())
                        .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN)
                        .apply("OFFSETTED_AMOUNT + {0} <= AMOUNT", take)
                        .setSql("OFFSETTED_AMOUNT = OFFSETTED_AMOUNT + " + take.toPlainString()));
                if (rows == 0) {
                    continue;   // 并发已冲减/已冲回，顺延下一单
                }
                consumed = consumed.add(take);
                remaining = remaining.subtract(take);
            }
        }
        if (remaining.signum() > 0) {
            // 暂估不足部分记应付借项（借 应付账款 / 贷 存货）
            voucherService.create(FinVoucher.TYPE_AP_DEBIT, LocalDate.now(),
                    "退货冲减不足记应付借项：" + returnNo, "RETURN", returnNo, supplierId,
                    List.of(GlVoucherService.FinVoucherLineSpec.of(ACCT_AP, "DR", remaining,
                            "借 应付账款（退货 " + returnNo + "）"),
                            GlVoucherService.FinVoucherLineSpec.of(ACCT_INVENTORY, "CR", remaining,
                                    "贷 存货（退货红字 " + returnNo + "）")));
            log.info("退货 {} 冲减暂估 {} 不足，余 {} 记应付借项凭证", returnNo, consumed, remaining);
        }
        return consumed;
    }

    // ================================================================
    // 3.6 台账 / 明细 / 汇总
    // ================================================================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String supplierId, String poNo,
                                          String status, String dateFrom, String dateTo) {
        LambdaQueryWrapper<FinAccrual> qw = new LambdaQueryWrapper<FinAccrual>()
                .eq(hasText(supplierId), FinAccrual::getSupplierId, supplierId)
                .like(hasText(poNo), FinAccrual::getPoNo, poNo)
                .eq(hasText(status), FinAccrual::getStatus, status)
                .ge(hasText(dateFrom), FinAccrual::getCreateDate, dateFrom + " 00:00:00")
                .le(hasText(dateTo), FinAccrual::getCreateDate, dateTo + " 23:59:59")
                .orderByDesc(FinAccrual::getCreateDate);
        Page<FinAccrual> raw = accrualDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FinAccrual a : raw.getRecords()) {
            rows.add(row(a));
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        FinAccrual a = accrualDao.selectById(id);
        if (a == null) {
            throw new ServiceException(404, "暂估单不存在");
        }
        Map<String, Object> m = row(a);
        List<Map<String, Object>> lines = new ArrayList<>();
        if (hasText(a.getGrNo())) {
            GoodsReceipt gr = grDao.selectOne(new LambdaQueryWrapper<GoodsReceipt>()
                    .eq(GoodsReceipt::getGrNo, a.getGrNo()).last("LIMIT 1"));
            if (gr != null) {
                m.put("grId", gr.getId());
                m.put("arrivalDate", gr.getArrivalDate());
                m.put("batchNo", gr.getBatchNo());
                for (GoodsReceiptLine gl : grLineDao.selectList(new LambdaQueryWrapper<GoodsReceiptLine>()
                        .eq(GoodsReceiptLine::getGrId, gr.getId())
                        .orderByAsc(GoodsReceiptLine::getLineNo))) {
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("lineNo", gl.getLineNo());
                    l.put("itemCode", gl.getItemCode());
                    l.put("itemName", gl.getItemName());
                    l.put("unit", gl.getUnit());
                    l.put("qty", gl.getWithinToleranceQty());
                    l.put("unitPrice", gl.getUnitPrice());
                    l.put("amount", gl.getAmount());
                    l.put("postingDocNo", gr.getPostingDocNo());
                    lines.add(l);
                }
            }
        }
        m.put("lines", lines);
        if (hasText(a.getVoucherId())) {
            m.put("voucher", voucherService.detail(a.getVoucherId()));
        }
        return m;
    }

    @Override
    public Map<String, Object> summary() {
        LocalDate today = LocalDate.now();
        LocalDate monthFirst = today.withDayOfMonth(1);
        LocalDate monthLast = today.withDayOfMonth(today.lengthOfMonth());
        String monthStart = monthFirst + " 00:00:00";
        String monthEnd = monthLast + " 23:59:59";
        // 暂估余额 = Σ OPEN（金额 − 已冲减）
        BigDecimal balance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (FinAccrual a : accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN))) {
            balance = balance.add(nvl(a.getAmount()).subtract(nvl(a.getOffsettedAmount())));
        }
        BigDecimal monthGen = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (FinAccrual a : accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                .ge(FinAccrual::getCreateDate, monthStart)
                .le(FinAccrual::getCreateDate, monthEnd))) {
            monthGen = monthGen.add(nvl(a.getAmount()));
        }
        BigDecimal monthRev = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (FinAccrual a : accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getStatus, FinAccrual.ST_REVERSED)
                .ge(FinAccrual::getReverseAt, monthStart)
                .le(FinAccrual::getReverseAt, monthEnd))) {
            monthRev = monthRev.add(nvl(a.getAmount()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("balance", balance);
        m.put("monthGenerated", monthGen);
        m.put("monthReversed", monthRev);
        m.put("openCount", accrualDao.selectCount(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN)));
        m.put("reversedCount", accrualDao.selectCount(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getStatus, FinAccrual.ST_REVERSED)));
        return m;
    }

    // ================================================================
    // 4.4 合并迁移批次财务确认（BR-4.1-29）
    // ================================================================

    @Override
    @Transactional
    public int confirmMigration(String batchNo) {
        requireRole("财务确认", "ROLE_ADMIN");
        if (!hasText(batchNo)) {
            throw new ServiceException(422, "迁移批次号必填");
        }
        List<FinAccrual> batch = accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getMigrationBatchNo, batchNo)
                .eq(FinAccrual::getMigrationConfirmed, 0));
        if (batch.isEmpty()) {
            throw new ServiceException(422, "迁移批次不存在或已确认：" + batchNo);
        }
        Set<String> suppliers = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now();
        for (FinAccrual a : batch) {
            a.setMigrationConfirmed(1);
            a.setMigrationConfirmBy(SecurityUtils.getCurrentUserId());
            a.setMigrationConfirmAt(now);
            accrualDao.updateById(a);
            suppliers.add(a.getSupplierId());
        }
        log.info("迁移批次 {} 财务确认 {} 条暂估，重跑三方匹配", batchNo, batch.size());
        for (String supplierId : suppliers) {
            try {
                matchService.rerunBySupplier(supplierId);
            } catch (RuntimeException e) {
                log.warn("迁移批次 {} 供应商 {} 重跑三方匹配异常：{}", batchNo, supplierId, e.getMessage());
            }
        }
        return batch.size();
    }

    // ------------------------------------------------------------------

    private FinAccrual newAccrual(String sourceType, String grNo, String docNo, String poNo,
                                  String supplierId, String supplierName, BigDecimal amount) {
        FinAccrual a = new FinAccrual();
        a.setAccrualNo(nextAccrualNo());
        a.setSourceType(sourceType);
        a.setGrNo(grNo);
        a.setPostingDocNo(docNo);
        a.setPoNo(poNo);
        a.setSupplierId(supplierId);
        a.setSupplierName(supplierName);
        a.setAmount(amount);
        a.setOffsettedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        a.setStatus(FinAccrual.ST_OPEN);
        a.setCreateBy(SecurityUtils.getCurrentUserId());
        accrualDao.insert(a);
        return a;
    }

    private Map<String, Object> row(FinAccrual a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("accrualNo", a.getAccrualNo());
        m.put("sourceType", a.getSourceType());
        m.put("grNo", a.getGrNo());
        m.put("postingDocNo", a.getPostingDocNo());
        m.put("poNo", a.getPoNo());
        m.put("supplierId", a.getSupplierId());
        m.put("supplierName", a.getSupplierName());
        m.put("amount", a.getAmount());
        m.put("offsettedAmount", a.getOffsettedAmount());
        m.put("balance", nvl(a.getAmount()).subtract(nvl(a.getOffsettedAmount())));
        m.put("status", a.getStatus());
        m.put("reverseSource", a.getReverseSource());
        m.put("reverseInvoiceNo", a.getReverseInvoiceNo());
        m.put("reverseReason", a.getReverseReason());
        m.put("reverseBy", a.getReverseBy());
        m.put("reverseAt", a.getReverseAt());
        m.put("voucherId", a.getVoucherId());
        m.put("originalSupplierCode", a.getOriginalSupplierCode());
        m.put("migrationBatchNo", a.getMigrationBatchNo());
        m.put("migrationConfirmed", a.getMigrationConfirmed());
        m.put("createDate", a.getCreateDate());
        return m;
    }

    private FinAccrual requireByNo(String accrualNo) {
        if (!hasText(accrualNo)) {
            throw new ServiceException(422, "暂估单号必填");
        }
        FinAccrual a = accrualDao.selectOne(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getAccrualNo, accrualNo).last("LIMIT 1"));
        if (a == null) {
            throw new ServiceException(404, "暂估单不存在：" + accrualNo);
        }
        return a;
    }

    /** 暂估单号：AC + yyyyMMdd + 3 位流水（唯一索引兜底并发） */
    private String nextAccrualNo() {
        String prefix = "AC" + LocalDate.now().format(DAY_FMT);
        Integer max = accrualDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(r -> userRoles.add(r.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
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

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
