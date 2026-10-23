package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.fin.FinBalanceDao;
import com.erp.dao.fin.FinPeriodDao;
import com.erp.dao.fin.FinVoucherDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.fin.FinPeriod;
import com.erp.entity.fin.FinVoucher;
import com.erp.service.SysParamService;
import com.erp.service.fin.FinPeriodService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 会计期间实现（add-gl-management，design D4：checklist 四检查项）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinPeriodServiceImpl implements FinPeriodService {

    private final FinPeriodDao periodDao;
    private final FinVoucherDao voucherDao;
    private final FinBalanceDao balanceDao;
    private final EamAssetCardDao cardDao;
    private final SysParamService sysParamService;
    private final JdbcTemplate jdbc;
    private final com.erp.dao.fin.ArProvisionDao arProvisionDao;
    /** 结账⑦需计提卡池/POSTED 运行判定（add-depreciation-monitoring，只读） */
    private final com.erp.service.fin.DepreciationMonitorService depreciationMonitorService;
    /** 结账⑧已对账（add-bank-reconciliation，BR-4.6-37 按需阻断） */
    private final com.erp.service.fin.BankReconciliationService bankReconciliationService;

    @Override
    public Map<String, Object> current() {
        String p = todayPeriod();
        FinPeriod fp = periodDao.selectByPeriod(p);
        if (fp == null) {
            fp = new FinPeriod();
            fp.setPeriod(p);
            fp.setStatus(FinPeriod.STATUS_OPEN);
            fp.setCreateBy("system");
            periodDao.insert(fp);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", fp.getPeriod());
        out.put("status", fp.getStatus());
        out.put("closeBy", fp.getCloseBy());
        out.put("closeAt", fp.getCloseAt());
        return out;
    }

    @Override
    public List<Map<String, Object>> list() {
        List<FinPeriod> rows = periodDao.selectList(new LambdaQueryWrapper<FinPeriod>()
                .eq(FinPeriod::getDelFlag, "0")
                .orderByDesc(FinPeriod::getPeriod));
        List<Map<String, Object>> out = new ArrayList<>();
        for (FinPeriod fp : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", fp.getPeriod());
            m.put("status", fp.getStatus());
            m.put("closeBy", fp.getCloseBy());
            m.put("closeAt", fp.getCloseAt());
            out.add(m);
        }
        return out;
    }

    @Override
    public void assertOpen(String period) {
        FinPeriod fp = periodDao.selectByPeriod(period);
        if (fp != null && FinPeriod.STATUS_CLOSED.equals(fp.getStatus())) {
            throw new ServiceException(422, "会计期间 " + period + " 已关闭，禁止新过账与新手工凭证");
        }
    }

    @Override
    public Map<String, Object> checklist(String period) {
        if (!hasText(period)) {
            period = todayPeriod();
        }
        List<Map<String, Object>> items = new ArrayList<>();

        // ①未过账手工凭证（MANUAL 的 DRAFT/AUDITED）
        Long pending = voucherDao.selectCount(new LambdaQueryWrapper<FinVoucher>()
                .eq(FinVoucher::getPeriod, period)
                .eq(FinVoucher::getSourceType, "MANUAL")
                .in(FinVoucher::getStatus, "DRAFT", "AUDITED")
                .eq(FinVoucher::getDelFlag, "0"));
        items.add(item("pendingManual", "无未过账手工凭证",
                pending == null || pending == 0,
                "存在 " + (pending == null ? 0 : pending) + " 张 DRAFT/AUDITED 手工凭证，请先过账或作废"));

        // ②试算平衡：期间 POSTED 行借贷合计
        Map<String, Object> tb = balanceDao.trialBalance(period);
        BigDecimal dr = (BigDecimal) tb.get("DR_SUM");
        BigDecimal cr = (BigDecimal) tb.get("CR_SUM");
        BigDecimal diff = (dr == null ? BigDecimal.ZERO : dr)
                .subtract(cr == null ? BigDecimal.ZERO : cr).abs();
        items.add(item("trialBalance", "试算平衡（借=贷）",
                diff.compareTo(new BigDecimal("0.01")) <= 0,
                "借方合计 " + dr + " vs 贷方合计 " + cr + "，差额 " + diff.setScale(2, RoundingMode.HALF_UP)));

        // ③资产勾稽 C-4.13-01：卡片 NBV vs 总账 1601−1602 累计余额
        BigDecimal tol = sysParamService.getAmount("TOLERANCE_DEFAULT", new BigDecimal("0.5"));
        BigDecimal cardNbv = BigDecimal.ZERO;
        List<EamAssetCard> cards = cardDao.selectList(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getDelFlag, "0")
                .notIn(EamAssetCard::getStatus, "DISPOSED", "REPLACED"));
        for (EamAssetCard c : cards) {
            BigDecimal cost = nz(c.getAssetCost());
            BigDecimal accum = nz(c.getAccumDepr()).add(nz(c.getImpairment()));
            cardNbv = cardNbv.add(cost.subtract(accum).max(BigDecimal.ZERO));
        }
        BigDecimal faBalance = nz(balanceDao.cumulativeBalance("1601", period));
        BigDecimal accumBalance = nz(balanceDao.cumulativeBalance("1602", period));
        BigDecimal glNbv = faBalance.subtract(accumBalance);
        BigDecimal nbvDiff = cardNbv.subtract(glNbv).abs();
        items.add(item("assetRecon", "资产勾稽 C-4.13-01（卡片 NBV≈总账 1601−1602）",
                nbvDiff.compareTo(tol) <= 0,
                "卡片 NBV " + cardNbv.setScale(2, RoundingMode.HALF_UP)
                        + " vs 总账净额 " + glNbv.setScale(2, RoundingMode.HALF_UP)
                        + "，差额 " + nbvDiff.setScale(2, RoundingMode.HALF_UP)));

        // ④汇率覆盖 C-4.1-15：期间内已启用外币凭证须币种汇率齐备（本期 CNY 单币种恒绿，外币域未建桩位）
        Long fx = voucherDao.selectCount(new LambdaQueryWrapper<FinVoucher>()
                .eq(FinVoucher::getPeriod, period)
                .ne(FinVoucher::getCurrency, "CNY")
                .eq(FinVoucher::getDelFlag, "0"));
        boolean fxOk = fx == null || fx == 0;
        items.add(item("fxCoverage", "汇率覆盖 C-4.1-15（外币凭证汇率齐备）",
                fxOk,
                fxOk ? "本期无外币凭证" : "存在 " + fx + " 张外币凭证，请确认当期汇率已录入（多币种重估域未建，桩位提示）"));

        // ⑤无未过账坏账计提（add-ar-management spec：已确认但凭证未过账阻断结账）
        long pendingProv = arProvisionDao.countUnposted(period);
        items.add(item("pendingProvision", "无未过账坏账计提",
                pendingProv == 0,
                pendingProv == 0 ? "无已确认未过账计提"
                        : "存在 " + pendingProv + " 条已确认未过账坏账计提，请先过账"));

        // ⑥无未过账重估凭证（add-fx-revaluation spec fx-revaluation：REVAL 的 DRAFT/AUDITED 阻断结账）
        Long pendingReval = voucherDao.selectCount(new LambdaQueryWrapper<FinVoucher>()
                .eq(FinVoucher::getPeriod, period)
                .eq(FinVoucher::getSourceType, "REVAL")
                .in(FinVoucher::getStatus, "DRAFT", "AUDITED")
                .eq(FinVoucher::getDelFlag, "0"));
        boolean revalOk = pendingReval == null || pendingReval == 0;
        items.add(item("pendingReval", "无未过账重估凭证",
                revalOk,
                revalOk ? "无未过账外币重估凭证"
                        : "存在 " + pendingReval + " 张 DRAFT/AUDITED 重估凭证（"
                                + period + "），请先审核过账或作废"));

        // ⑦已计提折旧（add-depreciation-monitoring，按需阻断 design D3）：
        // 本期存在需计提卡（在用未提足已过起提月）才要求 POSTED 折旧运行；无折旧义务直接放行
        long deprCards = depreciationMonitorService.countDepreciableCards(period);
        boolean deprOk = deprCards == 0 || depreciationMonitorService.hasPostedRun(period);
        items.add(item("pendingDepreciation", "已计提折旧",
                deprOk,
                deprCards == 0 ? "本期无需计提资产卡（全提足或无在用卡），放行"
                        : deprOk ? "本期 " + deprCards + " 张需计提卡，折旧已过账"
                                : "存在 " + deprCards + " 张需计提资产卡但无已过账折旧运行，请先执行本期折旧计提"));

        // ⑧已对账（add-bank-reconciliation，BR-4.6-37 按需阻断 design D5）：
        // 本期已录对账单的 ACTIVE 账户须全部 AUDITED；未录对账单的账户不阻断（银行出单滞后是常态）
        Map<String, Object> bankCheck = bankReconciliationService.closingCheck(period);
        long stmtTotal = ((Number) bankCheck.get("total")).longValue();
        long stmtUnaudited = ((Number) bankCheck.get("unaudited")).longValue();
        boolean bankOk = stmtUnaudited == 0;
        items.add(item("pendingBankRecon", "已对账",
                bankOk,
                stmtTotal == 0 ? "本期未录入银行对账单（银行未出单），放行"
                        : bankOk ? "本期 " + stmtTotal + " 张对账单均已审核（AUDITED）"
                                : "存在 " + stmtUnaudited + " 张未审核银行对账单（"
                                        + bankCheck.get("unauditedList") + "），请先完成对账确认与主管审核"));

        boolean allOk = items.stream().allMatch(m -> Boolean.TRUE.equals(m.get("ok")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("allOk", allOk);
        out.put("items", items);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> close(String period) {
        if (!hasText(period)) {
            period = todayPeriod();
        }
        FinPeriod fp = periodDao.selectByPeriod(period);
        if (fp != null && FinPeriod.STATUS_CLOSED.equals(fp.getStatus())) {
            throw new ServiceException(422, "期间 " + period + " 已关闭");
        }
        Map<String, Object> check = checklist(period);
        if (!Boolean.TRUE.equals(check.get("allOk"))) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
            List<String> fails = new ArrayList<>();
            for (Map<String, Object> it : items) {
                if (!Boolean.TRUE.equals(it.get("ok"))) {
                    fails.add(it.get("name") + "：" + it.get("detail"));
                }
            }
            throw new ServiceException(422, "结账检查未通过（逐项列示）：" + String.join("；", fails));
        }
        if (fp == null) {
            fp = new FinPeriod();
            fp.setPeriod(period);
            fp.setStatus(FinPeriod.STATUS_OPEN);
            periodDao.insert(fp);
        }
        periodDao.update(null, new LambdaUpdateWrapper<FinPeriod>()
                .eq(FinPeriod::getId, fp.getId())
                .set(FinPeriod::getStatus, FinPeriod.STATUS_CLOSED)
                .set(FinPeriod::getCloseBy, SecurityUtils.getCurrentUserId())
                .set(FinPeriod::getCloseAt, LocalDateTime.now())
                .set(FinPeriod::getVerNo, fp.getVerNo() + 1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("status", FinPeriod.STATUS_CLOSED);
        out.put("closeBy", SecurityUtils.getCurrentUserId());
        out.put("closeAt", LocalDateTime.now());
        return out;
    }

    private Map<String, Object> item(String key, String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }

    private static String todayPeriod() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }
}
