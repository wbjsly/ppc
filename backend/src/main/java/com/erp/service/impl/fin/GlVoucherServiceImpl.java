package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinAccountDao;
import com.erp.dao.fin.FinVoucherDao;
import com.erp.dao.fin.FinVoucherLineDao;
import com.erp.dao.mdm.MdmLegalEntityDao;
import com.erp.entity.fin.FinAccount;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.fin.FinVoucherLine;
import com.erp.entity.mdm.MdmLegalEntity;
import com.erp.service.fin.GlVoucherService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
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

/**
 * 最小总账凭证骨架实现（spec gl-voucher，design D2）。
 * 生成即 POSTED（系统自动过账，无人工审核流——8.1 未建，design D2 边界）；
 * 会计期间 = 凭证日期自然月（近似口径，design D8）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlVoucherServiceImpl implements GlVoucherService {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final FinAccountDao accountDao;
    private final FinVoucherDao voucherDao;
    private final FinVoucherLineDao lineDao;
    private final MdmLegalEntityDao legalEntityDao;

    @Override
    @Transactional
    public FinVoucher create(String voucherType, LocalDate voucherDate, String summary,
                             String sourceType, String sourceDocNo, String supplierId,
                             List<FinVoucherLineSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new ServiceException(422, "凭证行不能为空");
        }
        if (!hasText(voucherType)) {
            throw new ServiceException(422, "凭证类型必填");
        }
        LocalDate date = voucherDate == null ? LocalDate.now() : voucherDate;

        // 1) 科目校验 + 借贷合计（FR-4.6-1-3 / FR-4.6-1-4）
        BigDecimal totalDr = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCr = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<FinVoucherLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (FinVoucherLineSpec spec : specs) {
            if (spec == null || !hasText(spec.accountCode)) {
                throw new ServiceException(422, "凭证行科目编码必填");
            }
            FinAccount acct = accountDao.selectActiveByCode(spec.accountCode.trim());
            if (acct == null) {
                throw new ServiceException(422, "科目不存在：" + spec.accountCode);
            }
            if (!"ACTIVE".equals(acct.getStatus())) {
                throw new ServiceException(422, "科目已停用：" + spec.accountCode);
            }
            String dir = spec.direction == null ? null : spec.direction.trim().toUpperCase();
            if (!"DR".equals(dir) && !"CR".equals(dir)) {
                throw new ServiceException(422, "借贷方向仅支持 DR/CR：" + spec.accountCode);
            }
            if (spec.amount == null || spec.amount.signum() == 0) {
                throw new ServiceException(422, "金额为零或缺失：" + spec.accountCode);
            }
            BigDecimal amt = spec.amount.abs().setScale(2, RoundingMode.HALF_UP);
            if ("DR".equals(dir)) {
                totalDr = totalDr.add(amt);
            } else {
                totalCr = totalCr.add(amt);
            }
            FinVoucherLine l = new FinVoucherLine();
            l.setLineNo(lineNo++);
            l.setAccountCode(acct.getAccountCode());
            l.setAccountName(acct.getAccountName());
            l.setDirection(dir);
            l.setAmount(amt);
            l.setSummary(spec.summary);
            l.setSupplierId(supplierId);
            lines.add(l);
        }
        // 借贷平衡（L1 硬阻断，不落库）
        if (totalDr.compareTo(totalCr) != 0) {
            throw new ServiceException(422, "借贷不平，差额 "
                    + totalDr.subtract(totalCr).toPlainString() + "（FR-4.6-1-4）");
        }

        // 2) 头：编号 / 期间 / 法人主体 / 币种（C-0-08）
        MdmLegalEntity le = legalEntityDao.selectList(new LambdaQueryWrapper<MdmLegalEntity>()
                .last("LIMIT 1")).stream().findFirst().orElse(null);
        if (le == null) {
            throw new ServiceException(422, "缺少法人主体，无法生成凭证（C-0-08）");
        }
        FinVoucher v = new FinVoucher();
        v.setVoucherNo(nextVoucherNo(voucherType, date));
        v.setVoucherType(voucherType);
        v.setVoucherDate(date);
        v.setPeriod(date.format(PERIOD_FMT));
        v.setLegalEntity(le.getId());
        v.setCurrency(hasText(le.getBookkeepingCurrency()) ? le.getBookkeepingCurrency() : "CNY");
        v.setSummary(summary);
        v.setSourceType(sourceType);
        v.setSourceDocNo(sourceDocNo);
        v.setStatus(FinVoucher.STATUS_POSTED);
        v.setTotalDr(totalDr);
        v.setTotalCr(totalCr);
        v.setCreateBy(SecurityUtils.getCurrentUserId());
        voucherDao.insert(v);

        // 3) 行
        for (FinVoucherLine l : lines) {
            l.setVoucherId(v.getId());
            l.setCreateBy(SecurityUtils.getCurrentUserId());
            lineDao.insert(l);
        }
        log.info("voucher {} created type={} source={}/{} dr={} cr={}",
                v.getVoucherNo(), voucherType, sourceType, sourceDocNo, totalDr, totalCr);
        return v;
    }

    @Override
    @Transactional
    public FinVoucher reverse(String voucherId, String reason, String sourceDocNo) {
        FinVoucher src = require(voucherId);
        if (!FinVoucher.STATUS_POSTED.equals(src.getStatus())) {
            throw new ServiceException(422, "仅已过账凭证可红字冲销，当前 " + src.getStatus());
        }
        Long already = voucherDao.selectCount(new LambdaQueryWrapper<FinVoucher>()
                .eq(FinVoucher::getReversesId, src.getId()));
        if (already != null && already > 0) {
            throw new ServiceException(422, "该凭证已红字冲销，不可重复：" + src.getVoucherNo());
        }
        List<FinVoucherLineSpec> specs = new ArrayList<>();
        for (FinVoucherLine l : lineDao.selectList(new LambdaQueryWrapper<FinVoucherLine>()
                .eq(FinVoucherLine::getVoucherId, src.getId())
                .orderByAsc(FinVoucherLine::getLineNo))) {
            String opposite = "DR".equals(l.getDirection()) ? "CR" : "DR";
            specs.add(FinVoucherLineSpec.of(l.getAccountCode(), opposite, l.getAmount(),
                    "红字冲销：" + (hasText(reason) ? reason : src.getVoucherNo())));
        }
        FinVoucher rev = create(FinVoucher.TYPE_REVERSE, LocalDate.now(),
                "红字冲销 " + src.getVoucherNo() + (hasText(reason) ? "：" + reason : ""),
                src.getSourceType(), hasText(sourceDocNo) ? sourceDocNo : src.getVoucherNo(),
                null, specs);
        rev.setReversesId(src.getId());
        voucherDao.updateById(rev);
        log.info("voucher {} reversed by {} (reason={})", src.getVoucherNo(), rev.getVoucherNo(), reason);
        return rev;
    }

    @Override
    public List<Map<String, Object>> listBySource(String sourceType, String sourceDocNo) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FinVoucher v : voucherDao.selectList(new LambdaQueryWrapper<FinVoucher>()
                .eq(FinVoucher::getSourceType, sourceType)
                .eq(FinVoucher::getSourceDocNo, sourceDocNo)
                .orderByDesc(FinVoucher::getCreateDate))) {
            out.add(row(v, false));
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String voucherId) {
        return row(require(voucherId), true);
    }

    @Override
    @Transactional
    public FinVoucher update(String voucherId, String summary) {
        FinVoucher v = require(voucherId);
        if (FinVoucher.STATUS_POSTED.equals(v.getStatus())) {
            throw new ServiceException(422, "已过账凭证不可修改，仅可通过红字冲销纠正（BR-4.6-09）");
        }
        v.setSummary(summary);
        v.setUpdateBy(SecurityUtils.getCurrentUserId());
        voucherDao.updateById(v);
        return v;
    }

    @Override
    @Transactional
    public void voidDraft(String voucherId) {
        FinVoucher v = require(voucherId);
        if (FinVoucher.STATUS_POSTED.equals(v.getStatus())) {
            throw new ServiceException(422, "已过账凭证不可删除，仅可通过红字冲销纠正（BR-4.6-09）");
        }
        voucherDao.deleteById(v.getId());
        log.info("draft voucher {} voided", v.getVoucherNo());
    }

    // ------------------------------------------------------------------

    private Map<String, Object> row(FinVoucher v, boolean withLines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("voucherNo", v.getVoucherNo());
        m.put("voucherType", v.getVoucherType());
        m.put("voucherDate", v.getVoucherDate());
        m.put("period", v.getPeriod());
        m.put("currency", v.getCurrency());
        m.put("summary", v.getSummary());
        m.put("sourceType", v.getSourceType());
        m.put("sourceDocNo", v.getSourceDocNo());
        m.put("status", v.getStatus());
        m.put("reversesId", v.getReversesId());
        m.put("totalDr", v.getTotalDr());
        m.put("totalCr", v.getTotalCr());
        m.put("createDate", v.getCreateDate());
        if (withLines) {
            m.put("lines", lineDao.selectList(new LambdaQueryWrapper<FinVoucherLine>()
                    .eq(FinVoucherLine::getVoucherId, v.getId())
                    .orderByAsc(FinVoucherLine::getLineNo)));
        }
        return m;
    }

    private FinVoucher require(String id) {
        FinVoucher v = voucherDao.selectById(id);
        if (v == null) {
            throw new ServiceException(404, "凭证不存在");
        }
        return v;
    }

    /** 凭证号：类型(3) + 年月(6) + 3 位流水（BR-4.6-07 全局唯一，唯一索引兜底并发） */
    private String nextVoucherNo(String type, LocalDate date) {
        String prefix = type + date.format(PERIOD_FMT);
        Integer max = voucherDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
