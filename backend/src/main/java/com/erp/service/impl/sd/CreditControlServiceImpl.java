package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.ArWriteoffDao;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmCustomerGroupVersionDao;
import com.erp.dao.sd.CreditCheckDao;
import com.erp.dao.sd.CreditFreezeDao;
import com.erp.dao.sd.PrepaymentNoticeDao;
import com.erp.dao.sd.SoDao;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmCustomerGroupVersion;
import com.erp.entity.sd.CreditCheck;
import com.erp.entity.sd.CreditFreeze;
import com.erp.entity.sd.PrepaymentNotice;
import com.erp.entity.sd.So;
import com.erp.service.SysParamService;
import com.erp.ops.OutboxPublisher;
import com.erp.service.sd.CreditControlService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 客户信用管控（spec customer-credit-control）：
 * BR-4.3-13 四因子可用额度、BR-4.3-14 账龄超比 L2、BR-4.3-15 及时率下调评级、
 * BR-4.3-16 连续逾期临时降额、D5 建单即检冻结卡审批前与回前一稳定状态解冻、
 * BR-4.3-17 预收到账自动解冻、BR-4.3-18 特批上限与永久留痕。
 */
@Slf4j
@Service
public class CreditControlServiceImpl implements CreditControlService {

    private static final BigDecimal SPECIAL_LIMIT_DEFAULT = new BigDecimal("500000");
    private static final BigDecimal AGING_RATIO_LIMIT = new BigDecimal("0.2");
    private static final BigDecimal ON_TIME_RATE_LIMIT = new BigDecimal("0.80");
    private static final int OVERDUE_STREAK = 3;
    private static final int TEMP_CREDIT_DAYS = 90;

    private static final String[] RATINGS = {"D", "C", "B", "A"};

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private final CreditCheckDao checkDao;
    private final CreditFreezeDao freezeDao;
    private final PrepaymentNoticeDao noticeDao;
    private final ArInvoiceDao arDao;
    private final ArWriteoffDao writeoffDao;
    private final SoDao soDao;
    private final MdmCustomerGroupDao customerDao;
    private final com.erp.dao.mdm.MdmCustomerViewDao viewDao;
    private final MdmCustomerGroupVersionDao versionDao;
    private final NoticeService noticeService;
    private final SysParamService paramService;
    private final OutboxPublisher outboxPublisher;

    public CreditControlServiceImpl(CreditCheckDao checkDao,
                                    CreditFreezeDao freezeDao,
                                    PrepaymentNoticeDao noticeDao,
                                    ArInvoiceDao arDao,
                                    ArWriteoffDao writeoffDao,
                                    SoDao soDao,
                                    MdmCustomerGroupDao customerDao,
                                    com.erp.dao.mdm.MdmCustomerViewDao viewDao,
                                    MdmCustomerGroupVersionDao versionDao,
                                    NoticeService noticeService,
                                    SysParamService paramService,
                                    OutboxPublisher outboxPublisher) {
        this.checkDao = checkDao;
        this.freezeDao = freezeDao;
        this.noticeDao = noticeDao;
        this.arDao = arDao;
        this.writeoffDao = writeoffDao;
        this.soDao = soDao;
        this.customerDao = customerDao;
        this.viewDao = viewDao;
        this.versionDao = versionDao;
        this.noticeService = noticeService;
        this.paramService = paramService;
        this.outboxPublisher = outboxPublisher;
    }

    // ---------- 6.2~6.4 实时信用检查 ----------

    @Override
    @Transactional
    public Map<String, Object> check(String customerId, BigDecimal orderAmount,
                                     String soId, String soNo, boolean persist) {
        // 3.3.1 信用检查门禁（与 recheckSo 同口径：信用/财务/销售域；PM 等其它角色 403——
        // 原先缺此校验导致任意登录角色可跑四因子并 persist 落检查记录）
        requireAny("信用检查", "ROLE_CREDIT_ADMIN", "ROLE_FINANCE_MGR",
                "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR", "ROLE_ADMIN");
        if (!hasText(customerId)) {
            throw new ServiceException(422, "客户必填");
        }
        MdmCustomerGroup customer = customerDao.selectById(customerId);
        if (customer == null) {
            throw new ServiceException(404, "客户不存在");
        }
        List<String> actions = new ArrayList<>();

        // ---- 因子①：信用额度（集团额度 + 法人视图有效临时额度，BR-4.1-33 口径） ----
        BigDecimal limit = nvl(customer.getCreditLimitTotal());
        com.erp.entity.mdm.MdmCustomerView tempView = viewDao.selectOne(
                new LambdaQueryWrapper<com.erp.entity.mdm.MdmCustomerView>()
                        .eq(com.erp.entity.mdm.MdmCustomerView::getGroupId, customerId)
                        .isNotNull(com.erp.entity.mdm.MdmCustomerView::getTempCreditLimit)
                        .last("LIMIT 1"));
        if (tempView == null) {
            // 无视图带临时额：取第一条视图作为临时额度载体（降额写入点）
            tempView = viewDao.selectOne(new LambdaQueryWrapper<com.erp.entity.mdm.MdmCustomerView>()
                    .eq(com.erp.entity.mdm.MdmCustomerView::getGroupId, customerId)
                    .last("LIMIT 1"));
        }
        boolean tempValid = tempView != null
                && tempView.getTempCreditLimit() != null
                && tempView.getTempExpireDate() != null
                && tempView.getTempExpireDate().isAfter(LocalDate.now());
        if (tempValid) {
            limit = limit.add(tempView.getTempCreditLimit());
        }

        // ---- 因子②：应收余额 + 账龄 ----
        BigDecimal arBalance = nvl(arDao.selectOpenBalance(customerId));
        BigDecimal over90 = nvl(arDao.selectOver90Balance(customerId));
        BigDecimal over90Ratio = arBalance.signum() > 0
                ? over90.divide(arBalance, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // ---- 因子③：未清 SO 预占（排除自身与已关闭/已冻结） ----
        BigDecimal soReserved = nvl(soDao.selectOpenReserved(customerId, soId));

        // ---- 因子④：本次订单 + 寄售占用 ----
        BigDecimal order = orderAmount == null ? BigDecimal.ZERO : orderAmount;
        BigDecimal consign = BigDecimal.ZERO; // 寄售专项：本轮无未结算寄售口径，取 0（偏差已记）

        BigDecimal available = limit.subtract(arBalance).subtract(soReserved)
                .subtract(order).subtract(consign);

        // ---- 6.4 付款及时率（近 12 个月）→ 评级下调 ----
        long totalPay = writeoffDao.selectCount12m(customerId);
        BigDecimal onTimeRate = null;
        if (totalPay > 0) {
            long onTime = writeoffDao.selectOnTimeCount12m(customerId);
            onTimeRate = BigDecimal.valueOf(onTime)
                    .divide(BigDecimal.valueOf(totalPay), 4, RoundingMode.HALF_UP);
            if (onTimeRate.compareTo(ON_TIME_RATE_LIMIT) < 0) {
                String oldRating = customer.getCreditRating();
                String newRating = downgrade(oldRating);
                if (newRating != null && !newRating.equals(oldRating)) {
                    customer.setCreditRating(newRating);
                    customerDao.updateById(customer);
                    saveCreditVersion(customer, "信用评级自动下调 " + oldRating + " → " + newRating
                            + "（近 12 个月付款及时率 " + percent(onTimeRate) + " < 80%，BR-4.3-15）");
                    actions.add("评级下调：" + oldRating + " → " + newRating + "（BR-4.3-15）");
                }
            }
        }

        // ---- 6.4 连续 3 次逾期 → 临时额度下调 50%（写法人视图，BR-4.1-33 载体） ----
        long overdueStreak = writeoffDao.selectRecentOverdueCount(customerId, OVERDUE_STREAK);
        boolean alreadyCut = tempView != null && tempView.getTempCreditLimit() != null;
        if (overdueStreak >= OVERDUE_STREAK && !alreadyCut && tempView != null) {
            BigDecimal base = nvl(tempView.getTempCreditLimit()).signum() > 0
                    ? tempView.getTempCreditLimit() : nvl(customer.getCreditLimitTotal());
            BigDecimal newTemp = base.multiply(new BigDecimal("0.5")).setScale(2, RoundingMode.HALF_UP);
            tempView.setTempCreditLimit(newTemp);
            tempView.setTempExpireDate(LocalDate.now().plusDays(TEMP_CREDIT_DAYS));
            viewDao.updateById(tempView);
            saveCreditVersion(customer, "连续 " + OVERDUE_STREAK + " 次逾期，临时额度下调 50% 至 "
                    + newTemp + "（有效期至 " + LocalDate.now().plusDays(TEMP_CREDIT_DAYS)
                    + "，恢复条件：连续 3 期按时付款，BR-4.3-16）");
            actions.add("临时额度下调 50% 至 " + newTemp + "（BR-4.3-16）");
            // 降额后额度口径变化，重算可用额度
            limit = nvl(customer.getCreditLimitTotal()).add(newTemp);
            available = limit.subtract(arBalance).subtract(soReserved).subtract(order).subtract(consign);
        } else if (overdueStreak >= OVERDUE_STREAK && tempView == null) {
            actions.add("连续 " + OVERDUE_STREAK + " 次逾期但客户无临时额度载体（法人视图缺失），降额未执行（BR-4.3-16）");
        }

        // ---- 判定 ----
        String result;
        String reason = null;
        BigDecimal gap = BigDecimal.ZERO;
        if (available.signum() < 0) {
            result = CreditCheck.R_FROZEN;
            gap = available.negate();
            reason = "可用额度不足，缺口 " + gap + " 元（BR-4.3-13）";
        } else if (over90Ratio.compareTo(AGING_RATIO_LIMIT) > 0) {
            result = CreditCheck.R_NEED_APPROVAL;
            reason = "超期 90 天以上应收占比 " + percent(over90Ratio) + " > 20%，需信用管理员审批（BR-4.3-14）";
        } else {
            result = CreditCheck.R_PASS;
        }

        // 账龄报告 JSON
        Map<String, Object> aging = new LinkedHashMap<>();
        aging.put("arBalance", arBalance);
        aging.put("over90", over90);
        aging.put("over90Ratio", over90Ratio);
        aging.put("onTimeRate", onTimeRate);
        aging.put("payCount12m", totalPay);
        aging.put("overdueStreak", overdueStreak);

        // ---- 落检查记录 ----
        CreditCheck row = new CreditCheck();
        row.setCustomerId(customerId);
        row.setCustomerCode(customer.getCustomerCode());
        row.setCustomerName(customer.getCustomerName());
        row.setSoId(soId);
        row.setSoNo(soNo);
        row.setOrderAmount(order);
        row.setCreditLimitTotal(limit);
        row.setArBalance(arBalance);
        row.setSoReserved(soReserved);
        row.setConsignOccupied(consign);
        row.setAvailable(available);
        row.setArOver90(over90);
        row.setOver90Ratio(over90Ratio);
        row.setPayOnTimeRate(onTimeRate);
        row.setResult(result);
        row.setReason(reason);
        row.setAgingReport(toJson(aging));
        row.setCheckBy(currentUser());
        row.setCheckAt(LocalDateTime.now());
        if (persist) {
            checkDao.insert(row);
        }

        // ---- 账龄超比 → 推送信用管理员（L2） ----
        if (CreditCheck.R_NEED_APPROVAL.equals(result)) {
            noticeService.push("ROLE_CREDIT_ADMIN", null,
                    "账龄超比需审批：" + customer.getCustomerCode() + " " + customer.getCustomerName(),
                    "超期 90 天以上应收占比 " + percent(over90Ratio) + "，应收余额 " + arBalance
                            + "，超期金额 " + over90 + "。请审阅账龄分析报告。",
                    "CREDIT_AGING", row.getId());
            actions.add("已推送信用管理员（账龄 L2，BR-4.3-14）");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result);
        out.put("reason", reason);
        out.put("gap", gap);
        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("creditLimit", limit);
        factors.put("arBalance", arBalance);
        factors.put("soReserved", soReserved);
        factors.put("consignOccupied", consign);
        factors.put("orderAmount", order);
        factors.put("available", available);
        out.put("factors", factors);
        out.put("aging", aging);
        out.put("actions", actions);
        out.put("checkId", row.getId());
        return out;
    }

    // ---------- 6.5 建单即检 ----------

    @Override
    @Transactional
    public Map<String, Object> checkOnSoCreate(String soId) {
        So so = soDao.selectById(soId);
        if (so == null) {
            throw new ServiceException(404, "SO 不存在");
        }
        Map<String, Object> decision = check(so.getCustomerId(), so.getTotalAmount(),
                so.getId(), so.getSoNo(), true);
        String result = String.valueOf(decision.get("result"));

        if (CreditCheck.R_FROZEN.equals(result)) {
            freezeSo(so, CreditFreeze.RS_LIMIT,
                    String.valueOf(decision.get("checkId")), (BigDecimal) decision.get("gap"));
            decision.put("frozen", true);
        } else {
            decision.put("frozen", false);
        }
        return decision;
    }

    @Override
    @Transactional
    public Map<String, Object> recheckSo(String soId) {
        requireAny("信用重检", "ROLE_CREDIT_ADMIN", "ROLE_FINANCE_MGR", "ROLE_SALES");
        return checkOnSoCreate(soId);
    }

    // ---------- 冻结看板 ----------

    @Override
    public Page<CreditFreeze> freezePage(long current, long size, String status, String keyword) {
        requireAny("查看冻结看板", "ROLE_CREDIT_ADMIN", "ROLE_FINANCE_MGR");
        LambdaQueryWrapper<CreditFreeze> qw = new LambdaQueryWrapper<CreditFreeze>()
                .orderByDesc(CreditFreeze::getFrozenAt);
        if (hasText(status)) {
            qw.eq(CreditFreeze::getStatus, status.trim());
        }
        if (hasText(keyword)) {
            String k = keyword.trim();
            qw.and(w -> w.like(CreditFreeze::getSoNo, k)
                    .or().like(CreditFreeze::getCustomerName, k));
        }
        return freezeDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public CreditFreeze getFreeze(String id) {
        requireAny("查看冻结单", "ROLE_CREDIT_ADMIN", "ROLE_FINANCE_MGR");
        CreditFreeze f = freezeDao.selectById(id);
        if (f == null) {
            throw new ServiceException(404, "冻结单不存在");
        }
        return f;
    }

    // ---------- 6.6 解冻：回前一稳定状态 ----------

    @Override
    @Transactional
    public CreditFreeze unfreeze(String freezeId, String method, String remark) {
        CreditFreeze f = freezeDao.selectById(freezeId);
        if (f == null) {
            throw new ServiceException(404, "冻结单不存在");
        }
        if (CreditFreeze.ST_UNFROZEN.equals(f.getStatus())) {
            return f; // 幂等
        }
        // SO 状态回前一稳定状态（D5：不重跑审批、不释放预留）
        So so = soDao.selectById(f.getSoId());
        if (so != null) {
            String target = hasText(f.getPrevStatus()) ? f.getPrevStatus() : So.ST_DRAFT;
            so.setStatus(target);
            soDao.updateById(so);
        }
        f.setStatus(CreditFreeze.ST_UNFROZEN);
        f.setUnfreezeMethod(method);
        f.setUnfreezeNo(nextUnfreezeNo());
        f.setUnfrozenBy(currentUser());
        f.setUnfrozenAt(LocalDateTime.now());
        f.setUnfreezeRemark(remark);
        freezeDao.updateById(f);
        log.info("credit unfreeze {} so={} method={} → {}", f.getUnfreezeNo(), f.getSoNo(),
                method, so == null ? "?" : so.getStatus());
        return f;
    }

    // ---------- 6.7 预收 ----------

    @Override
    public Page<PrepaymentNotice> noticePage(long current, long size, String status, String keyword) {
        requireAny("查看预收通知", "ROLE_CREDIT_ADMIN", "ROLE_FINANCE_MGR", "ROLE_SALES");
        LambdaQueryWrapper<PrepaymentNotice> qw = new LambdaQueryWrapper<PrepaymentNotice>()
                .orderByDesc(PrepaymentNotice::getCreateDate);
        if (hasText(status)) {
            qw.eq(PrepaymentNotice::getStatus, status.trim());
        }
        if (hasText(keyword)) {
            String k = keyword.trim();
            qw.and(w -> w.like(PrepaymentNotice::getNoticeNo, k)
                    .or().like(PrepaymentNotice::getSoNo, k)
                    .or().like(PrepaymentNotice::getCustomerName, k));
        }
        return noticeDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public PrepaymentNotice notify(String noticeId) {
        requireAny("发送预收通知", "ROLE_CREDIT_ADMIN", "ROLE_SALES");
        PrepaymentNotice n = requireNotice(noticeId);
        LocalDateTime now = LocalDateTime.now();
        if (n.getNotifySalesAt() == null) {
            n.setNotifySalesAt(now);
        }
        if (n.getNotifyCustomerAt() == null) {
            n.setNotifyCustomerAt(now);
        }
        if (PrepaymentNotice.ST_PENDING.equals(n.getStatus())) {
            n.setStatus(PrepaymentNotice.ST_NOTIFIED);
        }
        noticeDao.updateById(n);
        return n;
    }

    @Override
    @Transactional
    public PrepaymentNotice registerReceived(String noticeId, BigDecimal amount, String remark) {
        requireAny("登记预收到账", "ROLE_CREDIT_ADMIN", "ROLE_SALES");
        PrepaymentNotice n = requireNotice(noticeId);
        if (PrepaymentNotice.ST_SETTLED.equals(n.getStatus())) {
            throw new ServiceException(422, "该通知已结清");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "到账金额须大于 0");
        }
        BigDecimal received = nvl(n.getReceivedAmount()).add(amount);
        n.setReceivedAmount(received);
        n.setReceivedAt(LocalDateTime.now());
        n.setRemainAmount(nvl(n.getGapAmount()).subtract(received));
        n.setStatus(PrepaymentNotice.ST_REGISTERED);
        if (hasText(remark)) {
            n.setRemark(remark);
        }
        noticeDao.updateById(n);
        return n;
    }

    @Override
    @Transactional
    public Map<String, Object> confirmReceived(String noticeId) {
        // 6.9：预收确认仅 FINANCE_MGR / ADMIN
        requireAny("确认预收到账", "ROLE_FINANCE_MGR");
        PrepaymentNotice n = requireNotice(noticeId);
        if (PrepaymentNotice.ST_SETTLED.equals(n.getStatus())) {
            throw new ServiceException(422, "该通知已结清");
        }
        BigDecimal received = nvl(n.getReceivedAmount());
        BigDecimal gap = nvl(n.getGapAmount());
        n.setConfirmBy(currentUser());
        n.setConfirmAt(LocalDateTime.now());

        Map<String, Object> out = new LinkedHashMap<>();
        if (received.compareTo(gap) < 0) {
            // 到账不足 → 保持冻结，返回尚差
            n.setRemainAmount(gap.subtract(received));
            noticeDao.updateById(n);
            out.put("ok", false);
            out.put("remain", gap.subtract(received));
            out.put("message", "到账不足，尚差 " + gap.subtract(received) + " 元，保持冻结（BR-4.3-17）");
            return out;
        }
        // 足额 → 自动解冻 + 解冻确认单
        CreditFreeze f = freezeDao.selectById(n.getFreezeId());
        CreditFreeze unfrozen = null;
        if (f != null && CreditFreeze.ST_FROZEN.equals(f.getStatus())) {
            unfrozen = unfreeze(f.getId(), CreditFreeze.M_PREPAY,
                    "预收到账 " + received + " ≥ 缺口 " + gap + "，自动解冻（BR-4.3-17）");
        }
        n.setStatus(PrepaymentNotice.ST_SETTLED);
        n.setRemainAmount(BigDecimal.ZERO);
        noticeDao.updateById(n);
        out.put("ok", true);
        out.put("unfreezeNo", unfrozen == null ? null : unfrozen.getUnfreezeNo());
        out.put("message", "到账足额，已自动解冻并生成《SO 解冻确认单》");
        return out;
    }

    // ---------- 6.8 信用特批 ----------

    @Override
    @Transactional
    public CreditFreeze specialApprove(String freezeId, BigDecimal amount, String reason) {
        requireAny("信用特批", "ROLE_CREDIT_ADMIN", "ROLE_FINANCE_MGR");
        CreditFreeze f = freezeDao.selectById(freezeId);
        if (f == null) {
            throw new ServiceException(404, "冻结单不存在");
        }
        if (CreditFreeze.ST_UNFROZEN.equals(f.getStatus())) {
            throw new ServiceException(422, "该冻结单已解冻");
        }
        if (!hasText(reason)) {
            throw new ServiceException(422, "特批理由必填（BR-4.3-18）");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "特批金额必填且须大于 0");
        }
        BigDecimal limit = paramService.getAmount("CREDIT_SPECIAL_LIMIT", SPECIAL_LIMIT_DEFAULT);
        if (amount.compareTo(limit) > 0) {
            throw new ServiceException(422, "特批金额 " + amount + " 超过授权上限 " + limit + "（BR-4.3-18）");
        }
        // 永久留痕：操作人/时间/金额/理由写入冻结单解冻备注（行不删除）
        return unfreeze(freezeId, CreditFreeze.M_SPECIAL,
                "信用特批 " + amount + "：" + reason.trim());
    }

    // ---------- 检查记录 ----------

    @Override
    public Page<CreditCheck> checkPage(long current, long size, String customerId, String result) {
        requireAny("查看信用检查", "ROLE_CREDIT_ADMIN", "ROLE_FINANCE_MGR");
        LambdaQueryWrapper<CreditCheck> qw = new LambdaQueryWrapper<CreditCheck>()
                .orderByDesc(CreditCheck::getCheckAt);
        if (hasText(customerId)) {
            qw.eq(CreditCheck::getCustomerId, customerId.trim());
        }
        if (hasText(result)) {
            qw.eq(CreditCheck::getResult, result.trim());
        }
        return checkDao.selectPage(new Page<>(current, size), qw);
    }

    // ---------- 内部 ----------

    /** 冻结 SO（6.5）：置挂起态 + 冻结单（PREV_STATUS=当前状态）+《预收款通知单》 */
    private void freezeSo(So so, String reason, String checkId, BigDecimal gap) {
        String prev = so.getStatus();
        so.setStatus("CREDIT_FREEZE");
        soDao.updateById(so);

        CreditFreeze f = new CreditFreeze();
        f.setSoId(so.getId());
        f.setSoNo(so.getSoNo());
        f.setCustomerId(so.getCustomerId());
        f.setCustomerName(so.getCustomerName());
        f.setCreditCheckId(checkId);
        f.setGapAmount(gap);
        f.setStatus(CreditFreeze.ST_FROZEN);
        f.setPrevStatus(prev);
        f.setFreezeReason(reason);
        f.setFrozenAt(LocalDateTime.now());
        freezeDao.insert(f);

        PrepaymentNotice n = new PrepaymentNotice();
        n.setNoticeNo(nextNoticeNo());
        n.setFreezeId(f.getId());
        n.setSoId(so.getId());
        n.setSoNo(so.getSoNo());
        n.setCustomerId(so.getCustomerId());
        n.setCustomerName(so.getCustomerName());
        n.setGapAmount(gap);
        n.setStatus(PrepaymentNotice.ST_PENDING);
        noticeDao.insert(n);

        // 15.3 跨域事件：CREDIT.FROZEN 信用冻结（recordVersion=该 SO 冻结轮次，
        // 保证解冻后再冻结幂等键不同；消费方：库存域预留占用提示 / BI 冻结口径，桩口径明示）
        long freezeRound = freezeDao.selectCount(new LambdaQueryWrapper<CreditFreeze>()
                .eq(CreditFreeze::getSoId, so.getId()));
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("customerId", so.getCustomerId());
        evt.put("customerCode", so.getCustomerCode());
        evt.put("gapAmount", gap);
        evt.put("prevStatus", prev);
        evt.put("freezeReason", reason);
        evt.put("creditCheckId", checkId);
        evt.put("freezeNo", f.getId());
        outboxPublisher.publishSourced("CREDIT.FROZEN", so.getSoNo(),
                (int) Math.max(freezeRound, 1), null,
                "信用冻结：缺口 " + gap, evt, "sd-service");
        log.info("SO {} credit frozen, gap={}, prev={}", so.getSoNo(), gap, prev);
    }

    /** 评级下调一级（A→B→C→D；D 不动） */
    private String downgrade(String rating) {
        if (rating == null) {
            return "B";
        }
        for (int i = 0; i < RATINGS.length; i++) {
            if (RATINGS[i].equals(rating.toUpperCase())) {
                return i == 0 ? null : RATINGS[i - 1];
            }
        }
        return null;
    }

    /** 信用变更台账（BR-4.3-15/16 记录变更日志）——复用客户集团版本表 */
    private void saveCreditVersion(MdmCustomerGroup g, String summary) {
        try {
            MdmCustomerGroupVersion v = new MdmCustomerGroupVersion();
            v.setEntityId(g.getId());
            Integer max = versionDao.selectMaxVersion(g.getId());
            v.setVersionNo(max == null ? 1 : max + 1);
            v.setSnapshotJson(mapper.writeValueAsString(g));
            v.setDiffSummary(summary);
            v.setOpType("CREDIT_ADJUST");
            v.setChangeReason(summary);
            v.setCreateBy(currentUser());
            versionDao.insert(v);
        } catch (Exception e) {
            log.warn("credit version log failed: {}", e.getMessage());
        }
    }

    private String nextUnfreezeNo() {
        String prefix = "UF" + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : freezeDao.selectUnfreezeNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private String nextNoticeNo() {
        String prefix = "PN" + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : noticeDao.selectNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private PrepaymentNotice requireNotice(String id) {
        if (!hasText(id)) {
            throw new ServiceException(422, "通知单 ID 必填");
        }
        PrepaymentNotice n = noticeDao.selectById(id);
        if (n == null) {
            throw new ServiceException(404, "预收通知单不存在");
        }
        return n;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String percent(BigDecimal r) {
        return r.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
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
        throw new ServiceException(403, "无权限" + action);
    }

    private String currentUser() {
        String id = SecurityUtils.getCurrentUserId();
        return id == null ? "system" : id;
    }

    private boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
