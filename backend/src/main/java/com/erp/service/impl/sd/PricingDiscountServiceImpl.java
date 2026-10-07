package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmCustomerViewDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmPriceAgreementDao;
import com.erp.dao.mdm.MdmPriceAgreementLineDao;
import com.erp.dao.sd.DiscountChannelDao;
import com.erp.dao.sd.PriceAuditDao;
import com.erp.dao.sd.PromotionDao;
import com.erp.dao.sd.SpecialPriceDao;
import com.erp.entity.mdm.MdmCustomerView;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmPriceAgreement;
import com.erp.entity.mdm.MdmPriceAgreementLine;
import com.erp.entity.sd.DiscountChannel;
import com.erp.entity.sd.PriceAudit;
import com.erp.entity.sd.Promotion;
import com.erp.entity.sd.SpecialPrice;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.mdm.MdmCrossDomainService;
import com.erp.service.sd.PricingDiscountService;
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
 * 三层折扣引擎（spec sales-pricing-discount）：
 * 渠道（客户主数据渠道属性，缺失置 0 + L4）→ 阶梯（LADDER 协议行级匹配）→
 * 促销（PUBLISHED 窗口内，重叠取最大 + TIME 协议按最后生效价裁决）→
 * 叠加（BEST_SINGLE 默认 / STACKED 逐层递减 + 求和异常阻断）→
 * 毛利硬阻断（特批放行回写 SP_NO）→ 审计全留痕（只增不改）。
 */
@Slf4j
@Service
public class PricingDiscountServiceImpl implements PricingDiscountService {

    private static final BigDecimal MIN_MARGIN_DEFAULT = new BigDecimal("0.05");
    private static final int DEFAULT_RETENTION_DAYS = 365;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private final DiscountChannelDao channelDao;
    private final PromotionDao promoDao;
    private final PriceAuditDao auditDao;
    private final SpecialPriceDao specialDao;
    private final MdmCustomerViewDao viewDao;
    private final MdmItemDao itemDao;
    private final MdmPriceAgreementDao agreementDao;
    private final MdmPriceAgreementLineDao agreementLineDao;
    private final MdmCrossDomainService crossDomain;
    private final com.erp.dao.mdm.MdmCustomerGroupDao customerGroupDao;
    private final SysParamService paramService;
    private final ApprovalEngine approvalEngine;

    public PricingDiscountServiceImpl(DiscountChannelDao channelDao,
                                      PromotionDao promoDao,
                                      PriceAuditDao auditDao,
                                      SpecialPriceDao specialDao,
                                      MdmCustomerViewDao viewDao,
                                      MdmItemDao itemDao,
                                      MdmPriceAgreementDao agreementDao,
                                      MdmPriceAgreementLineDao agreementLineDao,
                                      MdmCrossDomainService crossDomain,
                                      com.erp.dao.mdm.MdmCustomerGroupDao customerGroupDao,
                                      SysParamService paramService,
                                      ApprovalEngine approvalEngine) {
        this.channelDao = channelDao;
        this.promoDao = promoDao;
        this.auditDao = auditDao;
        this.specialDao = specialDao;
        this.viewDao = viewDao;
        this.itemDao = itemDao;
        this.agreementDao = agreementDao;
        this.agreementLineDao = agreementLineDao;
        this.crossDomain = crossDomain;
        this.customerGroupDao = customerGroupDao;
        this.paramService = paramService;
        this.approvalEngine = approvalEngine;
    }

    // ---------- 渠道折扣配置 ----------

    @Override
    public Page<DiscountChannel> channelPage(long current, long size, String channel) {
        requireAny("查询渠道折扣", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        LambdaQueryWrapper<DiscountChannel> qw = new LambdaQueryWrapper<DiscountChannel>()
                .orderByDesc(DiscountChannel::getCreateDate);
        if (hasText(channel)) {
            qw.eq(DiscountChannel::getChannel, channel.trim());
        }
        return channelDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public DiscountChannel saveChannel(DiscountChannel c) {
        requireAny("维护渠道折扣", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        if (c == null || !hasText(c.getChannel())) {
            throw new ServiceException(422, "渠道属性必填");
        }
        if (c.getDiscountRate() == null || c.getDiscountRate().signum() < 0
                || c.getDiscountRate().compareTo(new BigDecimal("0.5")) > 0) {
            throw new ServiceException(422, "折扣率须在 0 ~ 50% 之间");
        }
        if (c.getEffectiveFrom() == null) {
            c.setEffectiveFrom(LocalDate.now());
        }
        if (c.getEffectiveTo() != null && c.getEffectiveFrom().isAfter(c.getEffectiveTo())) {
            throw new ServiceException(422, "生效止期不得早于起期");
        }
        if (c.getId() == null) {
            c.setStatus(DiscountChannel.ST_ACTIVE);
            channelDao.insert(c);
        } else {
            DiscountChannel cur = channelDao.selectById(c.getId());
            if (cur == null) {
                throw new ServiceException(404, "渠道折扣不存在");
            }
            cur.setChannel(c.getChannel());
            cur.setDiscountRate(c.getDiscountRate());
            cur.setEffectiveFrom(c.getEffectiveFrom());
            cur.setEffectiveTo(c.getEffectiveTo());
            cur.setRemark(c.getRemark());
            channelDao.updateById(cur);
            return cur;
        }
        return c;
    }

    @Override
    @Transactional
    public DiscountChannel stopChannel(String id, String reason) {
        requireAny("停用渠道折扣", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        DiscountChannel c = requireChannel(id);
        if (!hasText(reason)) {
            throw new ServiceException(422, "停用原因必填");
        }
        c.setStatus(DiscountChannel.ST_STOPPED);
        c.setRemark(reason);
        channelDao.updateById(c);
        return c;
    }

    @Override
    @Transactional
    public DiscountChannel enableChannel(String id) {
        requireAny("启用渠道折扣", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        DiscountChannel c = requireChannel(id);
        c.setStatus(DiscountChannel.ST_ACTIVE);
        channelDao.updateById(c);
        return c;
    }

    // ---------- 促销活动配置 ----------

    @Override
    public Page<Promotion> promoPage(long current, long size, String keyword, String status) {
        requireAny("查询促销活动", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        LambdaQueryWrapper<Promotion> qw = new LambdaQueryWrapper<Promotion>()
                .orderByDesc(Promotion::getStartDate);
        if (hasText(status)) {
            qw.eq(Promotion::getStatus, status.trim());
        }
        if (hasText(keyword)) {
            String k = keyword.trim();
            qw.and(w -> w.like(Promotion::getPromoNo, k)
                    .or().like(Promotion::getPromoName, k)
                    .or().like(Promotion::getItemCode, k));
        }
        return promoDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public Promotion savePromotion(Promotion p) {
        requireAny("维护促销活动", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        if (p == null || !hasText(p.getPromoName()) || !hasText(p.getItemCode())) {
            throw new ServiceException(422, "活动名称与 SKU 必填");
        }
        if (p.getDiscountRate() == null || p.getDiscountRate().signum() <= 0
                || p.getDiscountRate().compareTo(new BigDecimal("0.5")) > 0) {
            throw new ServiceException(422, "促销折扣率须在 0 ~ 50% 之间");
        }
        if (p.getStartDate() == null || p.getEndDate() == null) {
            throw new ServiceException(422, "活动窗口起止日期必填");
        }
        if (p.getStartDate().isAfter(p.getEndDate())) {
            throw new ServiceException(422, "窗口止期不得早于起期");
        }
        if (p.getId() == null) {
            p.setPromoNo(nextNo());
            p.setStatus(Promotion.ST_DRAFT);
            promoDao.insert(p);
            return p;
        }
        Promotion cur = promoDao.selectById(p.getId());
        if (cur == null) {
            throw new ServiceException(404, "促销活动不存在");
        }
        if (Promotion.ST_PUBLISHED.equals(cur.getStatus())) {
            throw new ServiceException(422, "已发布活动不可修改，请先停用");
        }
        cur.setPromoName(p.getPromoName());
        cur.setItemCode(p.getItemCode());
        cur.setDiscountRate(p.getDiscountRate());
        cur.setStartDate(p.getStartDate());
        cur.setEndDate(p.getEndDate());
        cur.setRemark(p.getRemark());
        promoDao.updateById(cur);
        return cur;
    }

    @Override
    @Transactional
    public Promotion publishPromotion(String id) {
        requireAny("发布促销活动", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        Promotion p = promoDao.selectById(id);
        if (p == null) {
            throw new ServiceException(404, "促销活动不存在");
        }
        if (p.getEndDate().isBefore(LocalDate.now())) {
            throw new ServiceException(422, "活动窗口已过期，不可发布");
        }
        p.setStatus(Promotion.ST_PUBLISHED);
        promoDao.updateById(p);
        return p;
    }

    @Override
    @Transactional
    public Promotion stopPromotion(String id, String reason) {
        requireAny("停用促销活动", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        Promotion p = promoDao.selectById(id);
        if (p == null) {
            throw new ServiceException(404, "促销活动不存在");
        }
        if (!hasText(reason)) {
            throw new ServiceException(422, "停用原因必填");
        }
        p.setStatus(Promotion.ST_STOPPED);
        p.setRemark(reason);
        promoDao.updateById(p);
        return p;
    }

    // ---------- 折扣引擎 ----------

    @Override
    public Map<String, Object> calc(String customerId, String itemCode, BigDecimal qty,
                                    BigDecimal basePrice, String srcType, String srcId,
                                    Integer lineNo, boolean persistAudit) {
        requireAny("价格计算", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        return doCalc(customerId, itemCode, qty, basePrice, srcType, srcId, lineNo, persistAudit);
    }

    /** 计算主流程（无权限校验，供内部与矩阵复用；权限在入口把关） */
    private Map<String, Object> doCalc(String customerId, String itemCode, BigDecimal qty,
                                       BigDecimal basePrice, String srcType, String srcId,
                                       Integer lineNo, boolean persistAudit) {
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();

        if (basePrice == null || basePrice.signum() <= 0) {
            throw new ServiceException(422, "基准价缺失，无法计算折扣（请先完成协议试算）");
        }

        // --- 5.2 渠道层 ---
        BigDecimal channelRate = BigDecimal.ZERO;
        String channel = resolveChannel(customerId);
        if (!hasText(channel)) {
            warnings.add("客户渠道属性未维护，渠道折扣按 0 计算，请补充主数据（BR-4.3-32，已记录提示）");
        } else {
            DiscountChannel dc = findActiveChannel(channel);
            if (dc == null) {
                warnings.add("渠道「" + channel + "」暂无生效折扣配置，按 0 计算");
            } else {
                channelRate = dc.getDiscountRate();
            }
        }

        // --- 5.3 阶梯层（LADDER 协议，行级数量、不跨 SKU；不足用基准价） ---
        BigDecimal ladderRate = BigDecimal.ZERO;
        BigDecimal ladderPrice = null;
        Map<String, Object> ladderHit = matchLadder(customerId, itemCode, qty);
        if (ladderHit != null) {
            ladderPrice = (BigDecimal) ladderHit.get("price");
            if (ladderPrice != null && basePrice.signum() > 0) {
                BigDecimal r = basePrice.subtract(ladderPrice)
                        .divide(basePrice, 6, RoundingMode.HALF_UP);
                ladderRate = r.signum() > 0 ? r : BigDecimal.ZERO;
            }
        }
        // 不满足任何阶梯 → ladderRate 保持 0（用基准价）

        // --- 5.4 时间层（促销 PUBLISHED 窗口内，重叠取最大；TIME 协议按最后生效价裁决） ---
        List<Map<String, Object>> promoCandidates = new ArrayList<>();
        Promotion best = null;
        for (Promotion p : promoDao.selectList(new LambdaQueryWrapper<Promotion>()
                .eq(Promotion::getItemCode, itemCode)
                .eq(Promotion::getStatus, Promotion.ST_PUBLISHED))) {
            if (p.getStartDate().isAfter(LocalDate.now()) || p.getEndDate().isBefore(LocalDate.now())) {
                rejected.add(entry("PROMO", p.getPromoNo(), p.getDiscountRate(), "不在有效期内"));
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("source", "PROMO");
            c.put("no", p.getPromoNo());
            c.put("rate", p.getDiscountRate());
            c.put("effDate", p.getStartDate());
            promoCandidates.add(c);
            if (best == null || p.getDiscountRate().compareTo(best.getDiscountRate()) > 0) {
                best = p;
            }
        }
        // TIME 协议候选（时间层与 TIME 协议并存 → 最后生效价裁决，D4）
        Map<String, Object> timeCandidate = null;
        if ("TIME".equals(tryTrialType(customerId, itemCode, qty))) {
            Map<String, Object> hit = trial(customerId, itemCode, qty);
            if (hit != null && Boolean.TRUE.equals(hit.get("matched"))) {
                BigDecimal tp = parsePrice(hit.get("unitPrice"));
                if (tp != null && basePrice.signum() > 0 && tp.compareTo(basePrice) < 0) {
                    BigDecimal r = basePrice.subtract(tp).divide(basePrice, 6, RoundingMode.HALF_UP);
                    timeCandidate = new LinkedHashMap<>();
                    timeCandidate.put("source", "TIME");
                    timeCandidate.put("no", String.valueOf(hit.get("paCode")));
                    timeCandidate.put("rate", r.signum() > 0 ? r : BigDecimal.ZERO);
                    timeCandidate.put("effDate", hit.get("effectiveDate"));
                }
            }
        }

        BigDecimal promoRate = BigDecimal.ZERO;
        String promoNo = null;
        Map<String, Object> conflict = null;
        if (best != null) {
            promoRate = best.getDiscountRate();
            promoNo = best.getPromoNo();
        }
        if (timeCandidate != null) {
            BigDecimal timeRate = (BigDecimal) timeCandidate.get("rate");
            LocalDate promoDate = best != null ? best.getStartDate() : null;
            LocalDate timeDate = timeCandidate.get("effDate") instanceof LocalDate
                    ? (LocalDate) timeCandidate.get("effDate") : null;
            // 最后生效价裁决（C-4.3-04）：生效日期新者赢
            boolean timeWins = promoDate == null
                    || (timeDate != null && timeDate.isAfter(promoDate))
                    || (timeDate != null && timeDate.equals(promoDate) && timeRate.compareTo(promoRate) > 0);
            if (timeWins && timeRate.signum() > 0) {
                if (best != null) {
                    conflict = conflict(best.getPromoNo(), promoRate, String.valueOf(timeCandidate.get("no")),
                            timeRate, timeDate);
                    rejected.add(entry("PROMO", best.getPromoNo(), promoRate, "与 TIME 协议冲突，按最后生效价采用协议"));
                }
                promoRate = timeRate;
                promoNo = String.valueOf(timeCandidate.get("no"));
            } else if (best != null) {
                conflict = conflict(String.valueOf(timeCandidate.get("no")), timeRate,
                        best.getPromoNo(), promoRate, promoDate);
                rejected.add(entry("TIME", String.valueOf(timeCandidate.get("no")), timeRate,
                        "生效日期早于促销活动，按最后生效价采用促销"));
            } else {
                promoRate = timeRate;
                promoNo = String.valueOf(timeCandidate.get("no"));
            }
        }
        // 促销重叠：未取到的活动进未采用清单
        for (Promotion p : promoDao.selectList(new LambdaQueryWrapper<Promotion>()
                .eq(Promotion::getItemCode, itemCode)
                .eq(Promotion::getStatus, Promotion.ST_PUBLISHED))) {
            boolean inWindow = !p.getStartDate().isAfter(LocalDate.now()) && !p.getEndDate().isBefore(LocalDate.now());
            if (inWindow && (best == null || !p.getPromoNo().equals(best.getPromoNo()))) {
                if (rejected.stream().noneMatch(e -> p.getPromoNo().equals(e.get("no")))) {
                    rejected.add(entry("PROMO", p.getPromoNo(), p.getDiscountRate(),
                            best != null ? "重叠窗口取最大（采用 " + best.getPromoNo() + "）" : "未采用"));
                }
            }
        }

        // --- 5.5 叠加 ---
        String stackMode = getStackMode();
        BigDecimal finalPrice;
        String applied;
        boolean sumAnomaly = false;
        BigDecimal sumRates = channelRate.add(ladderRate).add(promoRate);
        if ("STACKED".equals(stackMode)) {
            // 渠道 > 量价 > 时间 逐层递减
            BigDecimal p1 = basePrice.multiply(BigDecimal.ONE.subtract(channelRate));
            BigDecimal p2 = p1.multiply(BigDecimal.ONE.subtract(ladderRate));
            finalPrice = p2.multiply(BigDecimal.ONE.subtract(promoRate)).setScale(4, RoundingMode.HALF_UP);
            applied = "STACKED";
        } else {
            BigDecimal bestRate = channelRate;
            applied = channelRate.signum() > 0 ? "CHANNEL" : "NONE";
            if (ladderRate.compareTo(bestRate) > 0) {
                bestRate = ladderRate;
                applied = "LADDER";
            }
            if (promoRate.compareTo(bestRate) > 0) {
                bestRate = promoRate;
                applied = "PROMO";
            }
            finalPrice = basePrice.multiply(BigDecimal.ONE.subtract(bestRate)).setScale(4, RoundingMode.HALF_UP);
        }

        // 毛利阈值与求和异常检测（C-4.3-03）
        MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, itemCode).last("LIMIT 1"));
        BigDecimal cost = item == null ? null : item.getStandardCost();
        BigDecimal minMargin = paramService.getAmount("MIN_MARGIN_RATE", MIN_MARGIN_DEFAULT);
        BigDecimal marginThreshold = cost == null ? null
                : cost.multiply(BigDecimal.ONE.add(minMargin)).setScale(4, RoundingMode.HALF_UP);

        String blockReason = null;
        if ("STACKED".equals(stackMode) && sumRates.compareTo(BigDecimal.ONE) > 0) {
            sumAnomaly = true;
            blockReason = "价格计算异常需人工确认：三层折扣率之和超过 100%（求和叠加异常，C-4.3-03）";
        } else if ("STACKED".equals(stackMode) && marginThreshold != null
                && finalPrice.compareTo(marginThreshold) < 0
                && sumRates.compareTo(bestSingle(channelRate, ladderRate, promoRate)) > 0) {
            sumAnomaly = true;
            blockReason = "价格计算异常需人工确认：叠加结果低于最低毛利阈值且为求和叠加（C-4.3-03）";
        }

        // --- 5.6 最低毛利硬阻断 ---
        Map<String, Object> margin = new LinkedHashMap<>();
        BigDecimal gap = null;
        String specialNo = null;
        boolean marginBlocked = false;
        if (marginThreshold != null) {
            margin.put("cost", cost);
            margin.put("threshold", marginThreshold);
            if (finalPrice.compareTo(marginThreshold) < 0) {
                gap = marginThreshold.subtract(finalPrice).setScale(4, RoundingMode.HALF_UP);
                SpecialPrice sp = findActiveSpecial(customerId, itemCode);
                if (sp == null) {
                    marginBlocked = true;
                } else {
                    specialNo = sp.getSpNo();
                }
            }
            margin.put("gap", gap);
            margin.put("blocked", marginBlocked);
            margin.put("specialNo", specialNo);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", Map.of("channel", channel == null ? "" : channel, "rate", channelRate));
        out.put("ladder", ladderHit == null
                ? Map.of("rate", ladderRate, "matched", false)
                : Map.of("rate", ladderRate, "matched", true, "price", ladderHit.get("price")));
        out.put("promo", Map.of("no", promoNo == null ? "" : promoNo, "rate", promoRate));
        out.put("stackMode", stackMode);
        out.put("basePrice", basePrice);
        out.put("finalPrice", finalPrice);
        out.put("appliedSource", applied);
        out.put("rejected", rejected);
        out.put("conflict", conflict);
        out.put("blocked", sumAnomaly);
        out.put("blockReason", blockReason);
        out.put("margin", margin);
        out.put("warnings", warnings);

        // --- 5.7 审计（SO/QUOTE 来源才落；CALC 只读不落） ---
        if (persistAudit && !PriceAudit.SRC_CALC.equals(srcType)) {
            PriceAudit a = new PriceAudit();
            a.setSrcType(srcType);
            a.setSrcId(srcId);
            a.setLineNo(lineNo);
            a.setCustomerId(customerId);
            a.setCustomerCode(resolveCustomerCode(customerId));
            a.setItemCode(itemCode);
            a.setQty(qty);
            a.setBasePrice(basePrice);
            a.setChannelRate(channelRate);
            a.setLadderRate(ladderRate);
            a.setPromoRate(promoRate);
            a.setStackMode(stackMode);
            a.setAppliedSource(applied);
            a.setFinalPrice(finalPrice);
            a.setConflictJson(toJson(conflict));
            a.setRejectedJson(toJson(rejected));
            a.setSpecialApprovalNo(specialNo);
            a.setOperatorId(currentUser());
            a.setOperateAt(LocalDateTime.now());
            int retention = paramService.getInt("AUDIT_RETENTION_DAYS", DEFAULT_RETENTION_DAYS);
            a.setRetentionUntil(LocalDate.now().plusDays(retention));
            auditDao.insert(a);
        }
        return out;
    }

    @Override
    public Map<String, Object> matrix(String customerId, List<String> itemCodes, BigDecimal qty) {
        requireAny("查询价格矩阵", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        if (!hasText(customerId) || itemCodes == null || itemCodes.isEmpty()) {
            throw new ServiceException(422, "客户与物料范围必填");
        }
        BigDecimal q = qty == null || qty.signum() <= 0 ? BigDecimal.ONE : qty;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String itemCode : itemCodes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemCode", itemCode);
            MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, itemCode).last("LIMIT 1"));
            if (item == null) {
                row.put("error", "SKU 不存在");
                rows.add(row);
                continue;
            }
            row.put("itemName", item.getItemName());
            // 基准价 = 协议试算（trial 命中价；未命中则基准缺失）
            Map<String, Object> hit = trial(customerId, itemCode, q);
            boolean matched = hit != null && Boolean.TRUE.equals(hit.get("matched"));
            row.put("protocolMatched", matched);
            row.put("paCode", matched ? hit.get("paCode") : null);
            row.put("paType", matched ? hit.get("agreementType") : null);
            if (!matched) {
                row.put("error", "无生效协议，无法取价（BR-4.3-10）");
                rows.add(row);
                continue;
            }
            BigDecimal base = parsePrice(hit.get("unitPrice"));
            Map<String, Object> calc = doCalc(customerId, itemCode, q, base,
                    PriceAudit.SRC_CALC, null, null, false);
            row.put("basePrice", base);
            row.put("finalPrice", calc.get("finalPrice"));
            row.put("appliedSource", calc.get("appliedSource"));
            row.put("channelRate", ((Map<?, ?>) calc.get("channel")).get("rate"));
            row.put("ladderRate", ((Map<?, ?>) calc.get("ladder")).get("rate"));
            row.put("promoRate", ((Map<?, ?>) calc.get("promo")).get("rate"));
            row.put("stackMode", calc.get("stackMode"));
            row.put("marginBlocked", ((Map<?, ?>) calc.get("margin")).get("blocked"));
            row.put("warnings", calc.get("warnings"));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("stackMode", getStackMode());
        return out;
    }

    // ---------- 审计查询 ----------

    @Override
    public Page<PriceAudit> auditPage(long current, long size, String srcType, String srcId,
                                      Integer lineNo, String operatorId,
                                      LocalDate from, LocalDate to) {
        requireAny("查询取价记录", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        LambdaQueryWrapper<PriceAudit> qw = new LambdaQueryWrapper<PriceAudit>()
                .orderByDesc(PriceAudit::getOperateAt);
        if (hasText(srcType)) qw.eq(PriceAudit::getSrcType, srcType.trim());
        if (hasText(srcId)) qw.eq(PriceAudit::getSrcId, srcId.trim());
        if (lineNo != null) qw.eq(PriceAudit::getLineNo, lineNo);
        if (hasText(operatorId)) qw.like(PriceAudit::getOperatorId, operatorId.trim());
        if (from != null) qw.ge(PriceAudit::getOperateAt, from.atStartOfDay());
        if (to != null) qw.lt(PriceAudit::getOperateAt, to.plusDays(1).atStartOfDay());
        return auditDao.selectPage(new Page<>(current, size), qw);
    }

    // ---------- 特殊价格审批单 ----------

    @Override
    public Page<SpecialPrice> specialPage(long current, long size, String keyword, String status) {
        requireAny("查询特批单", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        LambdaQueryWrapper<SpecialPrice> qw = new LambdaQueryWrapper<SpecialPrice>()
                .orderByDesc(SpecialPrice::getCreateDate);
        if (hasText(status)) qw.eq(SpecialPrice::getStatus, status.trim());
        if (hasText(keyword)) {
            String k = keyword.trim();
            qw.and(w -> w.like(SpecialPrice::getSpNo, k)
                    .or().like(SpecialPrice::getCustomerName, k)
                    .or().like(SpecialPrice::getItemCode, k));
        }
        return specialDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public SpecialPrice getSpecial(String id) {
        requireAny("查看特批单", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        SpecialPrice sp = specialDao.selectById(id);
        if (sp == null) {
            throw new ServiceException(404, "特批单不存在");
        }
        return sp;
    }

    @Override
    @Transactional
    public SpecialPrice applySpecial(SpecialPrice sp) {
        requireAny("申请特殊价格", "ROLE_SALES", "ROLE_SALES_MGR");
        if (sp == null || !hasText(sp.getCustomerId())) {
            throw new ServiceException(422, "客户必填");
        }
        if (sp.getSpecialPrice() == null || sp.getOriginPrice() == null) {
            throw new ServiceException(422, "原价与特批价必填");
        }
        if (sp.getSpecialPrice().compareTo(sp.getOriginPrice()) >= 0) {
            throw new ServiceException(422, "特批价须低于原价");
        }
        requireText(sp.getValidFrom(), "特批有效期起");
        if (sp.getValidTo() == null) {
            throw new ServiceException(422, "特批有效期止必填");
        }
        requireText(sp.getReason(), "申请原因");

        SpecialPrice row = new SpecialPrice();
        row.setSpNo(nextSpNo());
        row.setCustomerId(sp.getCustomerId());
        row.setCustomerCode(sp.getCustomerCode());
        row.setCustomerName(sp.getCustomerName());
        row.setItemCode(sp.getItemCode());
        row.setOriginPrice(sp.getOriginPrice());
        row.setSpecialPrice(sp.getSpecialPrice());
        row.setStandardCost(sp.getStandardCost());
        row.setValidFrom(sp.getValidFrom());
        row.setValidTo(sp.getValidTo());
        row.setReason(sp.getReason().trim());
        row.setStatus(SpecialPrice.ST_DRAFT);
        row.setApplyBy(currentUser());
        specialDao.insert(row);
        return row;
    }

    @Override
    @Transactional
    public SpecialPrice submitSpecial(String id) {
        requireAny("提交特批审批", "ROLE_SALES", "ROLE_SALES_MGR");
        SpecialPrice sp = specialDao.selectById(id);
        if (sp == null) {
            throw new ServiceException(404, "特批单不存在");
        }
        if (!SpecialPrice.ST_DRAFT.equals(sp.getStatus()) && !SpecialPrice.ST_REJECTED.equals(sp.getStatus())) {
            throw new ServiceException(422, "仅草稿/驳回态可提交：" + sp.getStatus());
        }
        if (hasText(sp.getApprovalId())) {
            throw new ServiceException(422, "存在在途审批");
        }
        // S-4.3-08：销售总监 + 财务会签（同 SEQ 双节点）
        var inst = approvalEngine.submit("SpecialPrice", sp.getId(),
                "特殊价格审批：" + sp.getSpNo() + " / " + sp.getCustomerName()
                        + " / " + sp.getOriginPrice() + " → " + sp.getSpecialPrice(),
                null,
                List.of(List.of(
                        ApprovalNodeSpec.joint("ROLE_SALES_DIRECTOR", "销售总监会签"),
                        ApprovalNodeSpec.joint("ROLE_FINANCE_MGR", "财务会签"))));
        sp.setStatus(SpecialPrice.ST_PENDING);
        sp.setApprovalId(inst.getId());
        specialDao.updateById(sp);
        return sp;
    }

    // ---------- 叠加参数 ----------

    @Override
    public String getStackMode() {
        String m = paramService.getValue("DISCOUNT_STACK_MODE", "BEST_SINGLE");
        return "STACKED".equals(m) ? "STACKED" : "BEST_SINGLE";
    }

    @Override
    public void setStackMode(String mode, String operator) {
        requireAny("维护叠加规则", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        if (!"STACKED".equals(mode) && !"BEST_SINGLE".equals(mode)) {
            throw new ServiceException(422, "叠加模式仅支持 BEST_SINGLE / STACKED");
        }
        paramService.setValue("DISCOUNT_STACK_MODE", mode, "ENUM", "DISCOUNT",
                "折扣叠加模式（BR-4.3-31）", operator);
    }

    // ---------- 内部 ----------

    /** 客户渠道：集团下法人视图第一条非空 CHANNEL（视图粒度简化为集团口径） */
    private String resolveChannel(String customerId) {
        List<MdmCustomerView> views = viewDao.selectList(new LambdaQueryWrapper<MdmCustomerView>()
                .eq(MdmCustomerView::getGroupId, customerId)
                .last("LIMIT 10"));
        for (MdmCustomerView v : views) {
            if (hasText(v.getChannel())) {
                return v.getChannel();
            }
        }
        return null;
    }

    private String resolveCustomerCode(String customerId) {
        com.erp.entity.mdm.MdmCustomerGroup g = customerGroupDao.selectById(customerId);
        return g == null ? null : g.getCustomerCode();
    }

    private DiscountChannel findActiveChannel(String channel) {
        LocalDate today = LocalDate.now();
        List<DiscountChannel> list = channelDao.selectList(new LambdaQueryWrapper<DiscountChannel>()
                .eq(DiscountChannel::getChannel, channel)
                .eq(DiscountChannel::getStatus, DiscountChannel.ST_ACTIVE)
                .le(DiscountChannel::getEffectiveFrom, today)
                .and(w -> w.isNull(DiscountChannel::getEffectiveTo)
                        .or().ge(DiscountChannel::getEffectiveTo, today))
                .orderByDesc(DiscountChannel::getEffectiveFrom));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 量价阶梯：LADDER 协议挂靠客户（集团/法人视图）行级匹配；无命中返回 null */
    private Map<String, Object> matchLadder(String customerId, String itemCode, BigDecimal qty) {
        List<MdmPriceAgreement> ags = agreementDao.selectList(new LambdaQueryWrapper<MdmPriceAgreement>()
                .eq(MdmPriceAgreement::getAgreementType, "LADDER")
                .in(MdmPriceAgreement::getStatus, java.util.Arrays.asList("0", "1"))
                .and(w -> w.eq(MdmPriceAgreement::getCustomerGroupId, customerId)
                        .or().isNull(MdmPriceAgreement::getCustomerGroupId)));
        LocalDate today = LocalDate.now();
        for (MdmPriceAgreement pa : ags) {
            if (pa.getEffectiveDate() != null && pa.getEffectiveDate().isAfter(today)) continue;
            if (pa.getExpireDate() != null && pa.getExpireDate().isBefore(today)) continue;
            List<MdmPriceAgreementLine> lines = agreementLineDao.selectList(
                    new LambdaQueryWrapper<MdmPriceAgreementLine>().eq(MdmPriceAgreementLine::getPaId, pa.getId()));
            for (MdmPriceAgreementLine l : lines) {
                if (!itemCode.equals(l.getItemCode()) || l.getMinQty() == null || l.getMaxQty() == null) {
                    continue;
                }
                if (qty.compareTo(l.getMinQty()) >= 0 && qty.compareTo(l.getMaxQty()) <= 0) {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("paCode", pa.getPaCode());
                    hit.put("price", l.getUnitPrice());
                    return hit;
                }
            }
        }
        return null; // 不满足任何阶梯 → 用基准价
    }

    private Map<String, Object> trial(String customerId, String itemCode, BigDecimal qty) {
        try {
            return crossDomain.trial(customerId, null, itemCode, qty, LocalDate.now());
        } catch (Exception e) {
            return null;
        }
    }

    private String tryTrialType(String customerId, String itemCode, BigDecimal qty) {
        Map<String, Object> hit = trial(customerId, itemCode, qty);
        if (hit == null || !Boolean.TRUE.equals(hit.get("matched"))) {
            return null;
        }
        return String.valueOf(hit.get("agreementType"));
    }

    private SpecialPrice findActiveSpecial(String customerId, String itemCode) {
        LocalDate today = LocalDate.now();
        List<SpecialPrice> list = specialDao.selectList(new LambdaQueryWrapper<SpecialPrice>()
                .eq(SpecialPrice::getCustomerId, customerId)
                .eq(SpecialPrice::getStatus, SpecialPrice.ST_APPROVED)
                .le(SpecialPrice::getValidFrom, today)
                .ge(SpecialPrice::getValidTo, today)
                .and(w -> w.isNull(SpecialPrice::getItemCode)
                        .or().eq(SpecialPrice::getItemCode, itemCode))
                .orderByDesc(SpecialPrice::getValidTo));
        return list.isEmpty() ? null : list.get(0);
    }

    private BigDecimal bestSingle(BigDecimal... rates) {
        BigDecimal max = BigDecimal.ZERO;
        for (BigDecimal r : rates) {
            if (r != null && r.compareTo(max) > 0) {
                max = r;
            }
        }
        return max;
    }

    private Map<String, Object> entry(String source, String no, BigDecimal rate, String why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("no", no);
        m.put("rate", rate);
        m.put("why", why);
        return m;
    }

    private Map<String, Object> conflict(String aNo, BigDecimal aRate, String bNo,
                                         BigDecimal bRate, LocalDate effDate) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("rule", "最后生效价（C-4.3-04）");
        c.put("adopted", bNo);
        c.put("adoptedRate", bRate);
        c.put("discarded", aNo);
        c.put("discardedRate", aRate);
        c.put("effDate", effDate == null ? null : effDate.toString());
        return c;
    }

    private BigDecimal parsePrice(Object raw) {
        if (raw == null) return null;
        if (raw instanceof BigDecimal) return (BigDecimal) raw;
        String s = String.valueOf(raw);
        int arrow = s.lastIndexOf('→');
        if (arrow >= 0) s = s.substring(arrow + 1);
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String nextNo() {
        String prefix = "PM" + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : promoDao.selectNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private String nextSpNo() {
        String prefix = "SP" + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : specialDao.selectNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private DiscountChannel requireChannel(String id) {
        DiscountChannel c = channelDao.selectById(id);
        if (c == null) {
            throw new ServiceException(404, "渠道折扣不存在");
        }
        return c;
    }

    private void requireText(Object v, String name) {
        if (v == null) {
            throw new ServiceException(422, name + "必填");
        }
        if (v instanceof String && ((String) v).trim().isEmpty()) {
            throw new ServiceException(422, name + "必填");
        }
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
