package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.crm.OppStageLogDao;
import com.erp.dao.crm.OpportunityDao;
import com.erp.dao.crm.ContractDao;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.sd.SdQuoteDao;
import com.erp.dao.sd.QuoteLineDao;
import com.erp.dao.sd.QuoteVersionDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.entity.crm.OppStageLog;
import com.erp.entity.crm.Opportunity;
import com.erp.entity.crm.Contract;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.sd.SdQuote;
import com.erp.entity.sd.QuoteLine;
import com.erp.entity.sd.QuoteVersion;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoLine;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.crm.ContractService;
import com.erp.service.mdm.MdmCrossDomainService;
import com.erp.service.sd.QuoteService;
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
 * 销售报价（spec sales-quote）：
 * 商机入口前置卡控（BR-4.3-07/08）、明细 MOQ 校验（BR-4.3-09，样品单豁免）、
 * 三类协议取价（BR-4.3-10 无协议阻断）、毛利测算与阈值处置（BR-4.3-11/12）、
 * 金额分档审批与发布版本（FR-4.3-1-6/1-7）、转化 SO 并推进商机（FR-4.3-1-8 / D12）。
 */
@Slf4j
@Service
public class QuoteServiceImpl implements QuoteService {

    private static final BigDecimal MIN_MARGIN_DEFAULT = new BigDecimal("0.05");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private final SdQuoteDao quoteDao;
    private final QuoteLineDao lineDao;
    private final QuoteVersionDao versionDao;
    private final SoDao soDao;
    private final SoLineDao soLineDao;
    private final OpportunityDao oppDao;
    private final OppStageLogDao stageLogDao;
    private final ContractDao contractDao;
    private final MdmItemDao itemDao;
    private final MdmCustomerGroupDao customerDao;
    private final MdmCrossDomainService crossDomain;
    private final ApprovalEngine approvalEngine;
    private final SysParamService paramService;
    private final QuotePublisher publisher;
    private final com.erp.service.sd.CreditControlService creditControlService;
    private final ContractService contractService;

    public QuoteServiceImpl(SdQuoteDao quoteDao,
                            QuoteLineDao lineDao,
                            QuoteVersionDao versionDao,
                            SoDao soDao,
                            SoLineDao soLineDao,
                            OpportunityDao oppDao,
                            OppStageLogDao stageLogDao,
                            ContractDao contractDao,
                            MdmItemDao itemDao,
                            MdmCustomerGroupDao customerDao,
                            MdmCrossDomainService crossDomain,
                            ApprovalEngine approvalEngine,
                            SysParamService paramService,
                            QuotePublisher publisher,
                            com.erp.service.sd.CreditControlService creditControlService,
                            ContractService contractService) {
        this.quoteDao = quoteDao;
        this.lineDao = lineDao;
        this.versionDao = versionDao;
        this.soDao = soDao;
        this.soLineDao = soLineDao;
        this.oppDao = oppDao;
        this.stageLogDao = stageLogDao;
        this.contractDao = contractDao;
        this.itemDao = itemDao;
        this.customerDao = customerDao;
        this.crossDomain = crossDomain;
        this.approvalEngine = approvalEngine;
        this.paramService = paramService;
        this.publisher = publisher;
        this.creditControlService = creditControlService;
        this.contractService = contractService;
    }

    // ---------- 查询 ----------

    @Override
    public Page<SdQuote> page(long current, long size, String keyword, String status, String oppId) {
        requireAny("查询报价", "ROLE_SALES", "ROLE_SALES_MGR");
        LambdaQueryWrapper<SdQuote> qw = new LambdaQueryWrapper<SdQuote>()
                .orderByDesc(SdQuote::getCreateDate);
        if (hasText(status)) {
            qw.eq(SdQuote::getStatus, status.trim());
        }
        if (hasText(oppId)) {
            qw.eq(SdQuote::getOppId, oppId.trim());
        }
        if (hasText(keyword)) {
            String k = keyword.trim();
            qw.and(w -> w.like(SdQuote::getDraftNo, k)
                    .or().like(SdQuote::getQuoteNo, k)
                    .or().like(SdQuote::getCustomerName, k)
                    .or().like(SdQuote::getOppNo, k));
        }
        return quoteDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public SdQuote get(String id) {
        requireAny("查看报价", "ROLE_SALES", "ROLE_SALES_MGR");
        return require(id);
    }

    @Override
    public List<QuoteLine> lines(String quoteId) {
        requireAny("查看报价行", "ROLE_SALES", "ROLE_SALES_MGR");
        require(quoteId);
        return lineDao.selectList(new LambdaQueryWrapper<QuoteLine>()
                .eq(QuoteLine::getQuoteId, quoteId)
                .orderByAsc(QuoteLine::getLineNo));
    }

    @Override
    public List<QuoteVersion> versions(String quoteId) {
        requireAny("查看报价版本", "ROLE_SALES", "ROLE_SALES_MGR");
        require(quoteId);
        return versionDao.selectList(new LambdaQueryWrapper<QuoteVersion>()
                .eq(QuoteVersion::getQuoteId, quoteId)
                .orderByDesc(QuoteVersion::getVersionNo)
                .orderByDesc(QuoteVersion::getOperateAt));
    }

    // ---------- 预检试算（不落库） ----------

    @Override
    public Map<String, Object> precheck(SdQuote quote, List<QuoteLine> lines) {
        requireAny("报价试算", "ROLE_SALES", "ROLE_SALES_MGR");
        Evaluation ev = evaluate(quote, lines, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ev.errors.isEmpty());
        out.put("errors", ev.errors);
        out.put("warnings", ev.warnings);
        out.put("pricingLines", ev.pricedLines);
        out.put("totalAmount", ev.totalAmount);
        out.put("totalCost", ev.totalCost);
        out.put("marginRate", ev.marginRate);
        out.put("marginLevel", ev.marginLevel);
        out.put("priceMatch", ev.priceMatch);
        return out;
    }

    // ---------- 创建（4.2~4.6） ----------

    @Override
    @Transactional
    public SdQuote create(SdQuote quote, List<QuoteLine> lines) {
        requireAny("创建报价", "ROLE_SALES", "ROLE_SALES_MGR");
        if (quote == null || !hasText(quote.getOppId())) {
            throw new ServiceException(422, "商机必填（报价由商机入口创建）");
        }
        Evaluation ev = evaluate(quote, lines, null);
        if (!ev.errors.isEmpty()) {
            // L1 阻断：错误清单随异常抛出（含取价匹配记录语义）
            throw new ServiceException(422, String.join("；", ev.errors));
        }

        Opportunity opp = requireOpp(quote.getOppId());
        MdmCustomerGroup customer = customerDao.selectById(quote.getCustomerId());

        SdQuote row = new SdQuote();
        row.setDraftNo(nextNo("QD", quoteDao::selectDraftNosByPrefix));
        row.setOppId(opp.getId());
        row.setOppNo(opp.getOppNo());
        row.setCustomerId(quote.getCustomerId());
        row.setCustomerCode(customer.getCustomerCode());
        row.setCustomerName(customer.getCustomerName());
        row.setOrderType(hasText(quote.getOrderType()) ? quote.getOrderType() : SdQuote.TYPE_STANDARD);
        row.setContactName(quote.getContactName());
        row.setShipAddress(quote.getShipAddress());
        row.setRemark(quote.getRemark());
        row.setTotalAmount(ev.totalAmount);
        row.setTotalCost(ev.totalCost);
        row.setMarginRate(ev.marginRate);
        row.setPriceMatch(toJson(ev.priceMatch));
        row.setVersionNo(1);
        row.setStatus(SdQuote.ST_DRAFT);
        quoteDao.insert(row);

        insertLines(row.getId(), ev.pricedLines);
        saveVersion(row, QuoteVersion.OP_CREATE, "创建草稿");
        log.info("quote draft created: {} amount={} margin={}({})", row.getDraftNo(),
                ev.totalAmount, ev.marginRate, ev.marginLevel);
        return row;
    }

    @Override
    @Transactional
    public SdQuote update(String id, SdQuote quote, List<QuoteLine> lines) {
        requireAny("修改报价", "ROLE_SALES", "ROLE_SALES_MGR");
        SdQuote cur = require(id);
        if (!SdQuote.ST_DRAFT.equals(cur.getStatus()) && !SdQuote.ST_REJECTED.equals(cur.getStatus())) {
            throw new ServiceException(422, "仅草稿/驳回态可修改（已发布请开新版本）");
        }
        SdQuote probe = new SdQuote();
        probe.setOppId(cur.getOppId());
        probe.setCustomerId(quote.getCustomerId() != null ? quote.getCustomerId() : cur.getCustomerId());
        probe.setOrderType(quote.getOrderType() != null ? quote.getOrderType() : cur.getOrderType());
        probe.setShipAddress(quote.getShipAddress() != null ? quote.getShipAddress() : cur.getShipAddress());
        Evaluation ev = evaluate(probe, lines, cur);
        if (!ev.errors.isEmpty()) {
            throw new ServiceException(422, String.join("；", ev.errors));
        }

        cur.setOrderType(probe.getOrderType());
        if (quote.getContactName() != null) cur.setContactName(quote.getContactName());
        if (quote.getShipAddress() != null) cur.setShipAddress(quote.getShipAddress());
        if (quote.getRemark() != null) cur.setRemark(quote.getRemark());
        cur.setTotalAmount(ev.totalAmount);
        cur.setTotalCost(ev.totalCost);
        cur.setMarginRate(ev.marginRate);
        cur.setPriceMatch(toJson(ev.priceMatch));
        // 毛利变了，旧的低毛利确认失效
        cur.setMarginConfirmBy(null);
        cur.setMarginConfirmAt(null);
        if (SdQuote.ST_REJECTED.equals(cur.getStatus())) {
            cur.setStatus(SdQuote.ST_DRAFT);
        }
        quoteDao.updateById(cur);

        lineDao.delete(new LambdaQueryWrapper<QuoteLine>().eq(QuoteLine::getQuoteId, cur.getId()));
        insertLines(cur.getId(), ev.pricedLines);
        saveVersion(cur, QuoteVersion.OP_CREATE, "修改草稿");
        return cur;
    }

    // ---------- 低毛利二次确认（4.6） ----------

    @Override
    @Transactional
    public SdQuote marginConfirm(String id) {
        requireAny("确认报价毛利", "ROLE_SALES", "ROLE_SALES_MGR");
        SdQuote q = require(id);
        if (!SdQuote.ST_DRAFT.equals(q.getStatus()) && !SdQuote.ST_REJECTED.equals(q.getStatus())) {
            throw new ServiceException(422, "仅草稿/驳回态可确认毛利");
        }
        BigDecimal margin = q.getMarginRate() == null ? BigDecimal.ZERO : q.getMarginRate();
        if (margin.signum() < 0) {
            throw new ServiceException(422, "负毛利不可二次确认，须提交销售主管与财务会签");
        }
        if (margin.compareTo(minMarginRate()) >= 0) {
            throw new ServiceException(422, "毛利率达标，无需二次确认");
        }
        q.setMarginConfirmBy(currentUser());
        q.setMarginConfirmAt(LocalDateTime.now());
        quoteDao.updateById(q);
        log.info("quote {} low margin confirmed by {} (rate={})", q.getDraftNo(), q.getMarginConfirmBy(), margin);
        return q;
    }

    // ---------- 提交审批 / 自动发布（4.7） ----------

    @Override
    @Transactional
    public SdQuote submit(String id) {
        requireAny("提交报价审批", "ROLE_SALES", "ROLE_SALES_MGR");
        SdQuote q = require(id);
        if (!SdQuote.ST_DRAFT.equals(q.getStatus()) && !SdQuote.ST_REJECTED.equals(q.getStatus())) {
            throw new ServiceException(422, "仅草稿/驳回态可提交审批：" + q.getStatus());
        }
        if (hasText(q.getApprovalId())) {
            throw new ServiceException(422, "存在在途审批，请先处理");
        }
        if (q.getTotalAmount() == null || q.getTotalAmount().signum() <= 0) {
            throw new ServiceException(422, "报价金额缺失，请先试算取价");
        }
        BigDecimal margin = q.getMarginRate() == null ? BigDecimal.ZERO : q.getMarginRate();
        BigDecimal minMargin = minMarginRate();
        boolean lowMargin = margin.signum() >= 0 && margin.compareTo(minMargin) < 0;
        boolean negative = margin.signum() < 0;

        // 低毛利（0 ≤ m < MIN）：须二次确认留痕方可提交（确认不构成价格授权）
        if (lowMargin && !hasText(q.getMarginConfirmBy())) {
            throw new ServiceException(422, "毛利率低于阈值，请先完成低毛利二次确认");
        }

        // 小额高毛利 → 自动审批直接发布（FR-4.3-1-6 小额高毛利自动）
        BigDecimal autoLimit = paramService.getAmount("QUOTE_APPROVAL_AUTO", new BigDecimal("50000"));
        if (!negative && !lowMargin && q.getTotalAmount().compareTo(autoLimit) < 0) {
            publisher.publish(q.getId(), currentUser());
            log.info("quote {} auto-approved (amount {} < {})", q.getDraftNo(), q.getTotalAmount(), autoLimit);
            return require(id);
        }

        List<List<ApprovalNodeSpec>> chain;
        if (negative) {
            // 负毛利：销售主管与财务会签（同 SEQ 双节点全过），锁定至释放（BR-4.3-11/12）
            chain = List.of(List.of(
                    ApprovalNodeSpec.joint("ROLE_SALES_MGR", "销售主管会签"),
                    ApprovalNodeSpec.joint("ROLE_FINANCE_MGR", "财务会签")));
        } else {
            BigDecimal majorLimit = paramService.getAmount("QUOTE_APPROVAL_MAJOR", new BigDecimal("500000"));
            if (q.getTotalAmount().compareTo(majorLimit) > 0) {
                chain = List.of(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售经理审批")),
                        List.of(ApprovalNodeSpec.sign("ROLE_SALES_DIRECTOR", "销售总监审批")));
            } else {
                chain = List.of(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售经理审批")));
            }
        }
        String title = "报价审批：" + q.getDraftNo()
                + (hasText(q.getQuoteNo()) ? "（" + q.getQuoteNo() + "）" : "")
                + " / " + q.getCustomerName() + " / " + q.getTotalAmount()
                + (negative ? " / 负毛利会签" : lowMargin ? " / 低毛利已确认" : "");
        var inst = approvalEngine.submit("Quote", q.getId(), title, null, chain);

        q.setStatus(SdQuote.ST_PENDING);
        q.setApprovalId(inst.getId());
        if (quoteDao.updateById(q) == 0) {
            throw new ServiceException(409, "报价状态更新冲突");
        }
        saveVersion(q, QuoteVersion.OP_SUBMIT, negative ? "负毛利会签提交" : "提交审批");
        return q;
    }

    // ---------- 新版本（4.8） ----------

    @Override
    @Transactional
    public SdQuote revise(String id) {
        requireAny("报价开新版本", "ROLE_SALES", "ROLE_SALES_MGR");
        SdQuote old = require(id);
        if (!SdQuote.ST_PUBLISHED.equals(old.getStatus())) {
            throw new ServiceException(422, "仅已发布报价可开新版本：" + old.getStatus());
        }
        if (SdQuote.ST_CONVERTED.equals(old.getStatus())) {
            throw new ServiceException(422, "已转化报价不可开新版本");
        }

        // 新草稿复制头行
        SdQuote fresh = new SdQuote();
        fresh.setDraftNo(nextNo("QD", quoteDao::selectDraftNosByPrefix));
        fresh.setOppId(old.getOppId());
        fresh.setOppNo(old.getOppNo());
        fresh.setCustomerId(old.getCustomerId());
        fresh.setCustomerCode(old.getCustomerCode());
        fresh.setCustomerName(old.getCustomerName());
        fresh.setOrderType(old.getOrderType());
        fresh.setContactName(old.getContactName());
        fresh.setShipAddress(old.getShipAddress());
        fresh.setRemark(old.getRemark());
        fresh.setTotalAmount(old.getTotalAmount());
        fresh.setTotalCost(old.getTotalCost());
        fresh.setMarginRate(old.getMarginRate());
        fresh.setPriceMatch(old.getPriceMatch());
        fresh.setVersionNo((old.getVersionNo() == null ? 1 : old.getVersionNo()) + 1);
        fresh.setStatus(SdQuote.ST_DRAFT);
        quoteDao.insert(fresh);

        for (QuoteLine l : lines(id)) {
            l.setId(null);
            l.setQuoteId(fresh.getId());
            lineDao.insert(l);
        }

        // 旧版本置 SUPERSEDED（保留可查，不可转 SO）
        old.setStatus(SdQuote.ST_SUPERSEDED);
        old.setSupersededBy(fresh.getId());
        quoteDao.updateById(old);
        saveVersion(old, QuoteVersion.OP_REVISE, "被新版本 " + fresh.getDraftNo() + " 替代");
        saveVersion(fresh, QuoteVersion.OP_CREATE, "自 " + old.getDraftNo() + " 开新版本");
        log.info("quote revised: {} (v{}) → {} (v{})", old.getDraftNo(), old.getVersionNo(),
                fresh.getDraftNo(), fresh.getVersionNo());
        return fresh;
    }

    // ---------- 转化 SO（4.9） ----------

    @Override
    public Map<String, Object> convertCheck(String id) {
        requireAny("报价转化校验", "ROLE_SALES", "ROLE_SALES_MGR");
        SdQuote q = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        String reason = null;
        if (SdQuote.ST_CONVERTED.equals(q.getStatus())) {
            reason = "报价已被转化（SO：" + q.getSoNo() + "），不可重复占用";
        } else if (SdQuote.ST_SUPERSEDED.equals(q.getStatus())) {
            reason = "该版本已被新版本替代，不可转化";
        } else if (!SdQuote.ST_PUBLISHED.equals(q.getStatus())) {
            reason = "报价未发布，不可转化（当前状态 " + q.getStatus() + "）";
        } else if (q.getValidTo() != null && q.getValidTo().isBefore(LocalDate.now())) {
            reason = "报价已过有效期（" + q.getValidTo() + "），请开新版本更新报价";
        } else {
            MdmCustomerGroup customer = customerDao.selectById(q.getCustomerId());
            if (customer == null) {
                reason = "客户主数据不存在";
            } else if ("2".equals(customer.getStatus())) {
                reason = "客户信用状态已冻结，须重检信用后转化";
            }
        }
        out.put("ok", reason == null);
        if (reason != null) {
            out.put("reason", reason);
        }
        return out;
    }

    @Override
    @Transactional
    public SdQuote convert(String id) {
        requireAny("报价转化 SO", "ROLE_SALES", "ROLE_SALES_MGR");
        SdQuote q = require(id);
        Map<String, Object> check = convertCheck(id);
        if (!Boolean.TRUE.equals(check.get("ok"))) {
            throw new ServiceException(422, String.valueOf(check.get("reason")));
        }
        List<QuoteLine> ql = lines(id);
        if (ql.isEmpty()) {
            throw new ServiceException(422, "报价无明细行，不可转化");
        }

        // 生成 SO 草稿（头 + 行带出报价行）
        So so = new So();
        so.setSoNo(nextNo("SO", soDao::selectNosByPrefix));
        so.setSourceType(So.SRC_QUOTE);
        so.setSourceId(q.getId());
        so.setOppId(q.getOppId());
        so.setCustomerId(q.getCustomerId());
        so.setCustomerCode(q.getCustomerCode());
        so.setCustomerName(q.getCustomerName());
        so.setOrderType(q.getOrderType());
        so.setTotalAmount(q.getTotalAmount());
        // 15.2 串联缺陷修复：转化 SO 必须带报价毛利率，否则 submit 时
        // marginRate=null 按 0% 判低毛利，正常单被误阻断（C-4.3-03）
        so.setMarginRate(q.getMarginRate());
        so.setStatus(So.ST_DRAFT);
        so.setRemark("由报价 " + (q.getQuoteNo() != null ? q.getQuoteNo() : q.getDraftNo()) + " 转化");
        soDao.insert(so);

        int lineNo = 1;
        for (QuoteLine l : ql) {
            SoLine sl = new SoLine();
            sl.setSoId(so.getId());
            sl.setLineNo(lineNo++);
            sl.setQuoteLineId(l.getId());
            sl.setItemCode(l.getItemCode());
            sl.setItemName(l.getItemName());
            sl.setQty(l.getQty());
            sl.setBaseUnit(l.getBaseUnit());
            sl.setUnitPrice(l.getUnitPrice());
            sl.setPriceSource(l.getPriceSource());
            sl.setPaCode(l.getPaCode());
            sl.setAmount(l.getAmount());
            sl.setExpectDeliveryDate(l.getExpectDeliveryDate());
            soLineDao.insert(sl);
        }

        // 回写报价
        q.setStatus(SdQuote.ST_CONVERTED);
        q.setSoId(so.getId());
        q.setSoNo(so.getSoNo());
        q.setConvertedAt(LocalDateTime.now());
        quoteDao.updateById(q);
        saveVersion(q, QuoteVersion.OP_CONVERT, "转化 SO：" + so.getSoNo());

        // 6.5 建单即检（D5）：SO 创建即跑信用检查；未过 → SO 置 CREDIT_FREEZE 挂起
        try {
            Map<String, Object> credit = creditControlService.checkOnSoCreate(so.getId());
            if (Boolean.TRUE.equals(credit.get("frozen"))) {
                log.info("quote {} converted SO {} frozen by credit (gap={})",
                        q.getDraftNo(), so.getSoNo(), credit.get("gap"));
            }
        } catch (Exception e) {
            log.warn("credit check on SO create failed: {}", e.getMessage());
        }

        // 商机自动推进「合同签订」（D12；阶段日志记 AUTO 通过，保留 5 阶段审计口径）
        advanceOpportunityToContract(q);
        log.info("quote {} converted to SO {}", q.getDraftNo(), so.getSoNo());
        return q;
    }

    // ---------- 内部：商机推进 ----------

    private void advanceOpportunityToContract(SdQuote q) {
        if (!hasText(q.getOppId())) {
            return;
        }
        Opportunity opp = oppDao.selectById(q.getOppId());
        if (opp == null || !Opportunity.ST_OPEN.equals(opp.getStatus())) {
            return;
        }
        if (!Opportunity.ST_QUOTE.equals(opp.getStage())) {
            return; // 只在「报价」阶段推进；更早阶段说明链路异常，不动
        }
        LocalDateTime now = LocalDateTime.now();

        OppStageLog logRow = new OppStageLog();
        logRow.setOppId(opp.getId());
        logRow.setFromStage(opp.getStage());
        logRow.setToStage(Opportunity.ST_CONTRACT);
        logRow.setProbability(100);
        logRow.setNextAction("合同签订（报价 " + (q.getQuoteNo() != null ? q.getQuoteNo() : q.getDraftNo()) + " 转化）");
        logRow.setNextActionDate(LocalDate.now());
        logRow.setStatus(OppStageLog.ST_APPROVED);
        logRow.setApplyBy("system");
        logRow.setApplyAt(now);
        logRow.setDecideBy("system");
        logRow.setDecideAt(now);
        logRow.setOpinion("报价转 SO 自动推进（design D12），无须人工审批");
        stageLogDao.insert(logRow);

        opp.setStage(Opportunity.ST_CONTRACT);
        opp.setStageEnteredAt(now);
        opp.setStageOverdue("0");
        opp.setStageProbability(100);
        opp.setNextAction("合同签订（报价转化）");
        opp.setNextActionDate(LocalDate.now());
        opp.setStatus(Opportunity.ST_WON);
        opp.setCloseAt(now);
        oppDao.updateById(opp);
        oppDao.update(null, new LambdaUpdateWrapper<Opportunity>()
                .eq(Opportunity::getId, opp.getId())
                .set(Opportunity::getApprovalId, null));
        log.info("opportunity {} auto-advanced to CONTRACT by quote conversion", opp.getOppNo());

        // 15.2 同步生成合同草稿（D12 第三段：带出商机名称/客户/报价金额作差异基准；
        // 商机名下已有合同则幂等跳过，不重复建）
        createContractDraftFromQuote(q, opp);
    }

    /** 报价转化 → 合同草稿（防重；金额异常只告警不阻断转化） */
    private void createContractDraftFromQuote(SdQuote q, Opportunity opp) {
        try {
            if (contractDao.selectByOppId(opp.getId()) != null) {
                log.info("opportunity {} already has contract, skip draft", opp.getOppNo());
                return;
            }
            if (q.getTotalAmount() == null || q.getTotalAmount().signum() <= 0) {
                log.warn("quote {} amount {} invalid, contract draft skipped",
                        q.getDraftNo(), q.getTotalAmount());
                return;
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("oppId", opp.getId());
            req.put("title", opp.getOppName());
            req.put("amount", q.getTotalAmount());
            req.put("remark", "由报价 " + (q.getQuoteNo() != null ? q.getQuoteNo() : q.getDraftNo())
                    + " 转化自动生成（D12）");
            Contract c = contractService.createDraft(req);
            log.info("contract {} drafted from quote {}", c.getContractNo(), q.getDraftNo());
        } catch (Exception e) {
            // 草稿生成失败不回滚转化主链（推进已生效）；留痕供人工补建
            log.error("contract draft from quote {} failed: {}", q.getDraftNo(), e.getMessage());
        }
    }

    // ---------- 内部：评估（卡控 + 取价 + 毛利） ----------

    /** 评估结果（precheck / create / update 共用） */
    private static class Evaluation {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<Map<String, Object>> pricedLines = new ArrayList<>();
        final Map<String, Object> priceMatch = new LinkedHashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal marginRate;
        String marginLevel; // OK / LOW（低毛利待确认）/ NEGATIVE（锁定会签）
    }

    private Evaluation evaluate(SdQuote quote, List<QuoteLine> lines, SdQuote existing) {
        Evaluation ev = new Evaluation();

        // --- 4.3 前置卡控：商机 / 客户 / 证照 / 地址 ---
        Opportunity opp = null;
        if (hasText(quote.getOppId())) {
            opp = oppDao.selectById(quote.getOppId());
            if (opp == null) {
                ev.errors.add("关联商机不存在");
            } else {
                if (Opportunity.stageIndex(opp.getStage()) < Opportunity.stageIndex(Opportunity.ST_QUOTE)) {
                    ev.errors.add("该商机尚未达到报价阶段（当前：" + opp.stageName() + "）");
                }
                if (Opportunity.ST_LOST.equals(opp.getStatus()) || Opportunity.ST_CLOSED.equals(opp.getStatus())) {
                    ev.errors.add("商机已归档，不可创建报价");
                }
            }
        }
        String customerId = quote.getCustomerId();
        if (!hasText(customerId)) {
            ev.errors.add("客户主数据必填");
        } else {
            MdmCustomerGroup customer = customerDao.selectById(customerId);
            if (customer == null) {
                ev.errors.add("客户主数据不存在，请先完成客户建档");
            } else {
                if ("2".equals(customer.getStatus())) {
                    ev.errors.add("客户已冻结，请联系信用管理员");
                } else if ("3".equals(customer.getStatus())) {
                    ev.errors.add("客户已合并（→ " + customer.getMergedTo() + "），请改用合并后客户");
                }
                if (customer.getLicenseExpire() != null && customer.getLicenseExpire().isBefore(LocalDate.now())) {
                    ev.errors.add("客户资质证照已过期（" + customer.getLicenseExpire() + "），请联系信用管理员");
                }
            }
            if (!hasText(quote.getShipAddress())) {
                ev.warnings.add("收货地址不完整，请补充（不阻断保存）");
            }
        }

        // --- 4.4 明细校验 + 4.5 取价 + 毛利输入 ---
        boolean sample = SdQuote.TYPE_SAMPLE.equals(quote.getOrderType());
        if (lines == null || lines.isEmpty()) {
            ev.errors.add("报价明细至少一行");
        } else {
            int lineNo = 1;
            for (QuoteLine l : lines) {
                Map<String, Object> pl = new LinkedHashMap<>();
                pl.put("lineNo", lineNo++);
                pl.put("itemCode", l.getItemCode());
                if (!hasText(l.getItemCode())) {
                    ev.errors.add("行 " + pl.get("lineNo") + "：SKU 必填");
                    continue;
                }
                MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                        .eq(MdmItem::getItemCode, l.getItemCode()).last("LIMIT 1"));
                if (item == null) {
                    ev.errors.add("行 " + pl.get("lineNo") + "：SKU 不存在（" + l.getItemCode() + "）");
                    continue;
                }
                pl.put("itemName", item.getItemName());
                if (!"1".equals(item.getStatus())) {
                    ev.errors.add("行 " + pl.get("lineNo") + "：" + item.getItemCode() + " 非启用在售物料");
                    continue;
                }
                if (l.getQty() == null || l.getQty().signum() <= 0) {
                    ev.errors.add("行 " + pl.get("lineNo") + "：数量须大于 0");
                    continue;
                }
                pl.put("qty", l.getQty());
                pl.put("baseUnit", item.getBaseUnit());
                pl.put("expectDeliveryDate", l.getExpectDeliveryDate());
                pl.put("specialPack", l.getSpecialPack());

                // MOQ 行级拒绝（BR-4.3-09；样品单豁免）
                BigDecimal moq = item.getMinOrderQty();
                pl.put("moq", moq);
                if (moq != null && !sample && l.getQty().compareTo(moq) < 0) {
                    ev.errors.add("行 " + pl.get("lineNo") + "：" + item.getItemCode() + " 低于 MOQ（需 ≥ "
                            + moq.stripTrailingZeros().toPlainString() + "，建议下单量同 MOQ）");
                }
                // 交期 L4：早于 物料前置期 允许提示
                if (l.getExpectDeliveryDate() != null && item.getLeadTimeDays() != null) {
                    LocalDate earliest = LocalDate.now().plusDays(item.getLeadTimeDays());
                    if (l.getExpectDeliveryDate().isBefore(earliest)) {
                        ev.warnings.add("行 " + pl.get("lineNo") + "：期望交期早于最早可交货日期（" + earliest + "），请确认");
                    }
                }
                // 标准成本（毛利公式输入）
                if (item.getStandardCost() == null) {
                    ev.errors.add("行 " + pl.get("lineNo") + "：" + item.getItemCode() + " 未维护标准成本，毛利不可算");
                    continue;
                }

                // 4.5 三类协议取价
                Map<String, Object> trial;
                try {
                    trial = crossDomain.trial(quote.getCustomerId(), null, l.getItemCode(),
                            l.getQty(), LocalDate.now());
                } catch (Exception ex) {
                    ev.errors.add("行 " + pl.get("lineNo") + "：取价失败（" + ex.getMessage() + "）");
                    continue;
                }
                Map<String, Object> match = new LinkedHashMap<>();
                match.put("itemCode", l.getItemCode());
                match.put("qty", l.getQty());
                Object matched = trial.get("matched");
                if (!Boolean.TRUE.equals(matched)) {
                    match.put("matched", false);
                    match.put("reasons", trial.get("reasons"));
                    match.put("checkedTypes", trial.get("checkedTypes"));
                    ev.priceMatch.put(l.getItemCode(), match);
                    ev.errors.add("行 " + pl.get("lineNo") + "：" + l.getItemCode()
                            + " 无生效协议，请先维护价格协议或提交特殊价格申请（BR-4.3-10）");
                    continue;
                }
                BigDecimal unitPrice = parsePrice(trial.get("unitPrice"));
                if (unitPrice == null) {
                    ev.errors.add("行 " + pl.get("lineNo") + "：取价结果不可解析");
                    continue;
                }
                match.put("matched", true);
                match.put("paCode", trial.get("paCode"));
                match.put("agreementType", trial.get("agreementType"));
                match.put("attachLevel", trial.get("attachLevel"));
                ev.priceMatch.put(l.getItemCode(), match);

                pl.put("unitPrice", unitPrice);
                pl.put("priceSource", trial.get("agreementType"));
                pl.put("paCode", trial.get("paCode"));
                pl.put("standardCost", item.getStandardCost());

                BigDecimal amount = unitPrice.multiply(l.getQty()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal lineCost = item.getStandardCost().multiply(l.getQty()).setScale(2, RoundingMode.HALF_UP);
                pl.put("amount", amount);
                pl.put("lineCost", lineCost);
                ev.totalAmount = ev.totalAmount.add(amount);
                ev.totalCost = ev.totalCost.add(lineCost);
                ev.pricedLines.add(pl);
            }
        }

        // --- 4.6 毛利测算 ---
        if (ev.errors.isEmpty() && ev.totalAmount.signum() > 0) {
            ev.marginRate = ev.totalAmount.subtract(ev.totalCost)
                    .divide(ev.totalAmount, 4, RoundingMode.HALF_UP);
            BigDecimal min = minMarginRate();
            if (ev.marginRate.signum() < 0) {
                ev.marginLevel = "NEGATIVE";
            } else if (ev.marginRate.compareTo(min) < 0) {
                ev.marginLevel = "LOW";
                ev.warnings.add("毛利率 " + percent(ev.marginRate) + " 低于阈值 " + percent(min)
                        + "，提交审批前须低毛利二次确认");
            } else {
                ev.marginLevel = "OK";
            }
        } else if (ev.errors.isEmpty()) {
            ev.marginRate = BigDecimal.ZERO;
            ev.marginLevel = "OK";
        }
        return ev;
    }

    // ---------- 内部：辅助 ----------

    private void insertLines(String quoteId, List<Map<String, Object>> pricedLines) {
        int lineNo = 1;
        for (Map<String, Object> pl : pricedLines) {
            QuoteLine row = new QuoteLine();
            row.setQuoteId(quoteId);
            row.setLineNo(lineNo++);
            row.setItemCode((String) pl.get("itemCode"));
            row.setItemName((String) pl.get("itemName"));
            row.setQty((BigDecimal) pl.get("qty"));
            row.setBaseUnit((String) pl.get("baseUnit"));
            Object edd = pl.get("expectDeliveryDate");
            if (edd instanceof LocalDate) {
                row.setExpectDeliveryDate((LocalDate) edd);
            }
            row.setSpecialPack((String) pl.get("specialPack"));
            row.setUnitPrice((BigDecimal) pl.get("unitPrice"));
            row.setPriceSource((String) pl.get("priceSource"));
            row.setPaCode((String) pl.get("paCode"));
            row.setAmount((BigDecimal) pl.get("amount"));
            row.setStandardCost((BigDecimal) pl.get("standardCost"));
            row.setLineCost((BigDecimal) pl.get("lineCost"));
            row.setMoq((BigDecimal) pl.get("moq"));
            lineDao.insert(row);
        }
    }

    private void saveVersion(SdQuote q, String opType, String remark) {
        QuoteVersion v = new QuoteVersion();
        v.setQuoteId(q.getId());
        v.setVersionNo(q.getVersionNo() == null ? 1 : q.getVersionNo());
        v.setOpType(opType);
        v.setOperatorId(currentUser());
        v.setOperateAt(LocalDateTime.now());
        v.setRemark(remark);
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("header", q);
            snap.put("lines", lines(q.getId()));
            v.setSnapshotJson(mapper.writeValueAsString(snap));
        } catch (Exception e) {
            v.setSnapshotJson("{}");
        }
        versionDao.insert(v);
    }

    /** trial 结果的 unitPrice：非阶梯为 BigDecimal；阶梯为 "min ≤ qty ≤ max → price" 字符串 */
    private BigDecimal parsePrice(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal) {
            return (BigDecimal) raw;
        }
        String s = String.valueOf(raw);
        int arrow = s.lastIndexOf('→');
        if (arrow >= 0) {
            s = s.substring(arrow + 1);
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal minMarginRate() {
        return paramService.getAmount("MIN_MARGIN_RATE", MIN_MARGIN_DEFAULT);
    }

    private String percent(BigDecimal rate) {
        return rate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private interface PrefixQuery {
        List<String> query(String prefix);
    }

    private String nextNo(String kind, PrefixQuery query) {
        String prefix = kind + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : query.query(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private SdQuote require(String id) {
        if (!hasText(id)) {
            throw new ServiceException(422, "报价 ID 必填");
        }
        SdQuote q = quoteDao.selectById(id);
        if (q == null) {
            throw new ServiceException(404, "报价不存在");
        }
        return q;
    }

    private Opportunity requireOpp(String id) {
        Opportunity opp = oppDao.selectById(id);
        if (opp == null) {
            throw new ServiceException(404, "关联商机不存在");
        }
        return opp;
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
