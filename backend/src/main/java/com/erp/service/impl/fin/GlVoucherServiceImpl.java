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
    // ---- add-gl-management ----
    private final com.erp.dao.fin.FinVoucherTemplateDao templateDao;
    private final com.erp.service.fin.GlBalanceService balanceService;
    private final com.erp.service.fin.FinPeriodService periodService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    @Transactional
    public FinVoucher create(String voucherType, LocalDate voucherDate, String summary,
                             String sourceType, String sourceDocNo, String supplierId,
                             List<FinVoucherLineSpec> specs) {
        return create(voucherType, voucherDate, summary, sourceType, sourceDocNo,
                supplierId, specs, false);
    }

    @Override
    @Transactional
    public FinVoucher create(String voucherType, LocalDate voucherDate, String summary,
                             String sourceType, String sourceDocNo, String supplierId,
                             List<FinVoucherLineSpec> specs, boolean draft) {
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
        v.setStatus(draft ? FinVoucher.STATUS_DRAFT : FinVoucher.STATUS_POSTED);
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
        // add-gl-management D3：POSTED 即回写科目余额（13 域零改动；DRAFT 不写）
        if (FinVoucher.STATUS_POSTED.equals(v.getStatus())) {
            balanceService.writeBack(v);
        }
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
    // ---- add-gl-management：手工凭证三态（design D2）----

    @Override
    @Transactional
    public FinVoucher createManual(FinVoucher header, List<FinVoucherLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ServiceException(422, "凭证行不能为空");
        }
        String period = header.getVoucherDate() == null
                ? LocalDate.now().format(PERIOD_FMT)
                : header.getVoucherDate().format(PERIOD_FMT);
        periodService.assertOpen(period);
        List<String> failures = new ArrayList<>();
        // C-4.6-06 法人/币种
        MdmLegalEntity le = legalEntityDao.selectList(new LambdaQueryWrapper<MdmLegalEntity>()
                .last("LIMIT 1")).stream().findFirst().orElse(null);
        if (le == null) {
            failures.add("缺少法人主体或记账币种（C-4.6-06）");
        }
        BigDecimal totalDr = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCr = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<FinVoucherLine> built = new ArrayList<>();
        int lineNo = 1;
        for (FinVoucherLine l : lines) {
            if (l == null || !hasText(l.getAccountCode())) {
                failures.add("第 " + lineNo + " 行科目编码必填");
                lineNo++;
                continue;
            }
            FinAccount acct = accountDao.selectActiveByCode(l.getAccountCode().trim());
            if (acct == null) {
                failures.add("第 " + lineNo + " 行科目不存在：" + l.getAccountCode());
            } else if (!"ACTIVE".equals(acct.getStatus())) {
                failures.add("第 " + lineNo + " 行科目已停用：" + l.getAccountCode());
            }
            String dir = l.getDirection() == null ? null : l.getDirection().trim().toUpperCase();
            if (!"DR".equals(dir) && !"CR".equals(dir)) {
                failures.add("第 " + lineNo + " 行借贷方向仅 DR/CR");
            }
            if (l.getAmount() == null || l.getAmount().signum() == 0) {
                failures.add("第 " + lineNo + " 行金额为零或缺失");
            }
            BigDecimal amt = l.getAmount() == null ? BigDecimal.ZERO
                    : l.getAmount().abs().setScale(2, RoundingMode.HALF_UP);
            if ("DR".equals(dir)) {
                totalDr = totalDr.add(amt);
            } else if ("CR".equals(dir)) {
                totalCr = totalCr.add(amt);
            }
            FinVoucherLine b = new FinVoucherLine();
            b.setLineNo(lineNo++);
            b.setAccountCode(acct == null ? l.getAccountCode().trim() : acct.getAccountCode());
            b.setAccountName(acct == null ? null : acct.getAccountName());
            b.setDirection(dir);
            b.setAmount(amt);
            b.setSummary(l.getSummary());
            b.setCostCenter(l.getCostCenter());
            b.setProfitCenter(l.getProfitCenter());
            b.setAuxCustomer(l.getAuxCustomer());
            b.setAuxSupplier(l.getAuxSupplier());
            b.setAuxProject(l.getAuxProject());
            b.setAuxEmployee(l.getAuxEmployee());
            b.setAuxDept(l.getAuxDept());
            built.add(b);
        }
        // C-4.6-01 借贷平衡（差额列示）
        if (totalDr.compareTo(totalCr) != 0) {
            failures.add("借贷不平，借方 " + totalDr + " vs 贷方 " + totalCr
                    + "，差额 " + totalDr.subtract(totalCr).toPlainString() + "（C-4.6-01）");
        }
        if (!failures.isEmpty()) {
            throw new ServiceException(422, "手工凭证校验未通过（逐项列示）：" + String.join("；", failures));
        }
        FinVoucher v = new FinVoucher();
        v.setVoucherNo(nextVoucherNo("MNL", header.getVoucherDate() == null ? LocalDate.now() : header.getVoucherDate()));
        v.setVoucherType(hasText(header.getVoucherType()) ? header.getVoucherType() : "MANUAL");
        v.setVoucherDate(header.getVoucherDate() == null ? LocalDate.now() : header.getVoucherDate());
        v.setPeriod(period);
        v.setLegalEntity(le.getId());
        v.setCurrency(hasText(le.getBookkeepingCurrency()) ? le.getBookkeepingCurrency() : "CNY");
        v.setSummary(header.getSummary());
        v.setSourceType("MANUAL");
        v.setSourceDocNo(header.getSourceDocNo());
        v.setStatus(FinVoucher.STATUS_DRAFT);
        v.setTotalDr(totalDr);
        v.setTotalCr(totalCr);
        v.setCreateBy(SecurityUtils.getCurrentUserId());
        voucherDao.insert(v);
        for (FinVoucherLine b : built) {
            b.setVoucherId(v.getId());
            b.setCreateBy(SecurityUtils.getCurrentUserId());
            lineDao.insert(b);
        }
        log.info("manual voucher {} saved as DRAFT dr={} cr={}", v.getVoucherNo(), totalDr, totalCr);
        return v;
    }

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<FinVoucher> manualPage(
            long current, long size, String status, String keyword) {
        LambdaQueryWrapper<FinVoucher> qw = new LambdaQueryWrapper<FinVoucher>()
                .eq(FinVoucher::getSourceType, "MANUAL")
                .eq(FinVoucher::getDelFlag, "0")
                .orderByDesc(FinVoucher::getCreateDate);
        if (hasText(status)) {
            qw.eq(FinVoucher::getStatus, status);
        }
        if (hasText(keyword)) {
            qw.and(w -> w.like(FinVoucher::getVoucherNo, keyword).or().like(FinVoucher::getSummary, keyword));
        }
        return voucherDao.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public FinVoucher audit(String voucherId) {
        FinVoucher v = require(voucherId);
        if (!FinVoucher.STATUS_DRAFT.equals(v.getStatus())) {
            throw new ServiceException(422, "仅 DRAFT 手工凭证可审核（当前 " + v.getStatus() + "）");
        }
        String auditor = SecurityUtils.getCurrentUserId();
        if (auditor != null && auditor.equals(v.getCreateBy())) {
            throw new ServiceException(422, "制单人与审核人不能为同一人（C-4.6-07 职责分离）");
        }
        voucherDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FinVoucher>()
                .eq(FinVoucher::getId, v.getId())
                .set(FinVoucher::getStatus, "AUDITED")
                .set(FinVoucher::getAuditBy, auditor)
                .set(FinVoucher::getAuditDate, LocalDateTime.now())
                .set(FinVoucher::getVerNo, v.getVerNo() + 1));
        log.info("manual voucher {} audited by {}", v.getVoucherNo(), auditor);
        return voucherDao.selectById(v.getId());
    }

    @Override
    @Transactional
    public void reject(String voucherId, String reason) {
        FinVoucher v = require(voucherId);
        if (!FinVoucher.STATUS_DRAFT.equals(v.getStatus())) {
            throw new ServiceException(422, "仅 DRAFT 手工凭证可驳回");
        }
        voucherDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FinVoucher>()
                .eq(FinVoucher::getId, v.getId())
                .set(FinVoucher::getAuditBy, SecurityUtils.getCurrentUserId())
                .set(FinVoucher::getAuditDate, LocalDateTime.now())
                .set(FinVoucher::getAuditOpinion, "驳回：" + (hasText(reason) ? reason : "未填写原因"))
                .set(FinVoucher::getVerNo, v.getVerNo() + 1));
        log.info("manual voucher {} rejected ({})", v.getVoucherNo(), reason);
    }

    @Override
    @Transactional
    public FinVoucher post(String voucherId) {
        FinVoucher v = require(voucherId);
        if (!"AUDITED".equals(v.getStatus())) {
            throw new ServiceException(422, "仅 AUDITED 凭证可过账（当前 " + v.getStatus() + "）");
        }
        periodService.assertOpen(v.getPeriod());   // FR-4.6-1-7 已关期间阻断
        voucherDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FinVoucher>()
                .eq(FinVoucher::getId, v.getId())
                .set(FinVoucher::getStatus, FinVoucher.STATUS_POSTED)
                .set(FinVoucher::getVerNo, v.getVerNo() + 1));
        FinVoucher posted = voucherDao.selectById(v.getId());
        balanceService.writeBack(posted);
        log.info("manual voucher {} posted", v.getVoucherNo());
        return posted;
    }

    // ---- 凭证模板（design D5）----

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.erp.entity.fin.FinVoucherTemplate> templatePage(
            long current, long size, String keyword) {
        LambdaQueryWrapper<com.erp.entity.fin.FinVoucherTemplate> qw =
                new LambdaQueryWrapper<com.erp.entity.fin.FinVoucherTemplate>()
                        .eq(com.erp.entity.fin.FinVoucherTemplate::getDelFlag, "0")
                        .orderByDesc(com.erp.entity.fin.FinVoucherTemplate::getCreateDate);
        if (hasText(keyword)) {
            qw.like(com.erp.entity.fin.FinVoucherTemplate::getName, keyword);
        }
        return templateDao.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public com.erp.entity.fin.FinVoucherTemplate saveTemplate(com.erp.entity.fin.FinVoucherTemplate template) {
        if (!hasText(template.getName())) {
            throw new ServiceException(422, "模板名称必填");
        }
        if (template.getId() == null) {
            template.setCreateBy(SecurityUtils.getCurrentUserId());
            templateDao.insert(template);
        } else {
            com.erp.entity.fin.FinVoucherTemplate old = templateDao.selectById(template.getId());
            if (old == null) {
                throw new ServiceException(404, "模板不存在");
            }
            template.setUpdateBy(SecurityUtils.getCurrentUserId());
            template.setVerNo(old.getVerNo());
            templateDao.updateById(template);
        }
        return template;
    }

    @Override
    @Transactional
    public void deleteTemplate(String templateId) {
        templateDao.deleteById(templateId);
    }

    @Override
    public Map<String, Object> applyTemplate(String templateId) {
        com.erp.entity.fin.FinVoucherTemplate t = templateDao.selectById(templateId);
        if (t == null) {
            throw new ServiceException(404, "模板不存在");
        }
        List<Map<String, Object>> rows;
        try {
            rows = objectMapper.readValue(
                    hasText(t.getRowsJson()) ? t.getRowsJson() : "[]",
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception ex) {
            throw new ServiceException(500, "模板行快照解析失败：" + ex.getMessage());
        }
        // 逐行校验科目 ACTIVE；停用行标注 blocked 不阻断（design D5）
        for (Map<String, Object> r : rows) {
            String code = r.get("accountCode") == null ? null : String.valueOf(r.get("accountCode"));
            FinAccount acct = code == null ? null : accountDao.selectActiveByCode(code.trim());
            r.put("blocked", acct == null || !"ACTIVE".equals(acct.getStatus()));
            if (acct != null) {
                r.put("accountName", acct.getAccountName());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templateId", templateId);
        out.put("name", t.getName());
        out.put("rows", rows);
        return out;
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
