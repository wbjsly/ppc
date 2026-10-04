package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.mdm.MdmTaxCodeDao;
import com.erp.dao.proc.ProcPrLineDao;
import com.erp.dao.proc.ProcRequisitionDao;
import com.erp.dao.proc.QuoteDao;
import com.erp.dao.proc.RfqDao;
import com.erp.dao.proc.RfqLineDao;
import com.erp.dao.proc.RfqSupplierDao;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.mdm.MdmTaxCode;
import com.erp.entity.proc.ProcPrLine;
import com.erp.entity.proc.ProcRequisition;
import com.erp.entity.proc.Quote;
import com.erp.entity.proc.Rfq;
import com.erp.entity.proc.RfqLine;
import com.erp.entity.proc.RfqSupplier;
import com.erp.procurement.RequisitionStateMachine;
import com.erp.procurement.RfqStateMachine;
import com.erp.service.proc.EmergencyService;
import com.erp.service.proc.RfqService;
import com.erp.service.proc.RfqSupport;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 询价比价实现（design add-rfq-comparison D2~D5）：
 * 查询前置懒 sweep（截止锁价 + 不足标记）；矩阵含 ±20% 异常持久化与含税试算（跨税码域）。
 */
@Slf4j
@Service
public class RfqServiceImpl implements RfqService {

    /** 询价最少供应商数（参数 MIN_QUOTE_COUNT，L266 默认 3） */
    @Value("${app.proc.min-quote-count:3}")
    private int minQuoteCount;

    /**
     * 招标门槛金额（BR-4.2-44；design D7 口径 = 本年 PR 预估累计金额）。
     * 参数机制见 design D9（yml + @Value，无参数表）。默认 100 万。
     */
    @Value("${app.proc.tender-threshold-amount:1000000}")
    private java.math.BigDecimal tenderThresholdAmount;

    private final RfqDao rfqDao;
    private final RfqLineDao lineDao;
    private final RfqSupplierDao rfqSupplierDao;
    private final QuoteDao quoteDao;
    private final ProcRequisitionDao prDao;
    private final ProcPrLineDao prLineDao;
    private final MdmSupplierDao mdmSupplierDao;
    private final MdmTaxCodeDao taxCodeDao;
    private final RfqSupport support;
    private final EmergencyService emergencyService;

    public RfqServiceImpl(RfqDao rfqDao,
                          RfqLineDao lineDao,
                          RfqSupplierDao rfqSupplierDao,
                          QuoteDao quoteDao,
                          ProcRequisitionDao prDao,
                          ProcPrLineDao prLineDao,
                          MdmSupplierDao mdmSupplierDao,
                          MdmTaxCodeDao taxCodeDao,
                          RfqSupport support,
                          EmergencyService emergencyService) {
        this.rfqDao = rfqDao;
        this.lineDao = lineDao;
        this.rfqSupplierDao = rfqSupplierDao;
        this.quoteDao = quoteDao;
        this.prDao = prDao;
        this.prLineDao = prLineDao;
        this.mdmSupplierDao = mdmSupplierDao;
        this.taxCodeDao = taxCodeDao;
        this.support = support;
        this.emergencyService = emergencyService;
    }

    // ---------- 分页（sweep 先行） ----------

    @Override
    public Page<Map<String, Object>> page(long current, long size, String status, String keyword) {
        sweep();
        LambdaQueryWrapper<Rfq> qw = new LambdaQueryWrapper<Rfq>()
                .eq(isNotBlank(status), Rfq::getStatus, status)
                .like(isNotBlank(keyword), Rfq::getRfqNo,
                        isNotBlank(keyword) ? keyword.trim() : null)
                .orderByDesc(Rfq::getCreateDate);
        Page<Rfq> raw = rfqDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> records = new ArrayList<>();
        for (Rfq rfq : raw.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rfq.getId());
            row.put("rfqNo", rfq.getRfqNo());
            row.put("prId", rfq.getPrId());
            row.put("prNo", prNo(rfq.getPrId()));
            row.put("status", rfq.getStatus());
            row.put("quoteDeadline", rfq.getQuoteDeadline());
            row.put("emergencyFlag", rfq.getEmergencyFlag());
            row.put("insufficientFlag", rfq.getInsufficientFlag());
            row.put("sendMode", rfq.getSendMode());
            row.put("sendStatus", rfq.getSendStatus());
            row.put("awardSupplierId", rfq.getAwardSupplierId());
            row.put("createDate", rfq.getCreateDate());
            long quoted = quoteDao.selectCount(new LambdaQueryWrapper<Quote>()
                    .eq(Quote::getRfqId, rfq.getId()));
            long suppliers = rfqSupplierDao.selectCount(new LambdaQueryWrapper<RfqSupplier>()
                    .eq(RfqSupplier::getRfqId, rfq.getId()));
            row.put("quotedCount", quoted);
            row.put("supplierCount", suppliers);
            records.add(row);
        }
        result.setRecords(records);
        return result;
    }

    @Override
    public Map<String, Object> detail(String id) {
        sweep();
        Rfq rfq = support.requireRfq(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rfq", rfq);
        result.put("prNo", prNo(rfq.getPrId()));
        result.put("minQuoteCount", minQuoteCount);

        List<Map<String, Object>> lines = new ArrayList<>();
        for (RfqLine l : lineDao.selectList(new LambdaQueryWrapper<RfqLine>()
                .eq(RfqLine::getRfqId, id).orderByAsc(RfqLine::getLineNo))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineNo", l.getLineNo());
            m.put("itemCode", l.getItemCode());
            m.put("qty", l.getQty());
            m.put("reqDate", l.getReqDate());
            lines.add(m);
        }
        result.put("lines", lines);

        Map<String, String> names = new HashMap<>();
        Map<String, String> statuses = new HashMap<>();
        List<RfqSupplier> rfqSuppliers = rfqSupplierDao.selectList(
                new LambdaQueryWrapper<RfqSupplier>().eq(RfqSupplier::getRfqId, id));
        for (RfqSupplier rs : rfqSuppliers) {
            MdmSupplier s = mdmSupplierDao.selectById(rs.getSupplierId());
            if (s != null) {
                names.put(s.getId(), s.getSupplierName());
                statuses.put(s.getId(), s.getStatus());
            }
        }
        List<Map<String, Object>> sups = new ArrayList<>();
        for (RfqSupplier rs : rfqSuppliers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supplierId", rs.getSupplierId());
            m.put("supplierName", names.getOrDefault(rs.getSupplierId(), rs.getSupplierId()));
            m.put("supplierStatus", statuses.get(rs.getSupplierId()));
            m.put("hasQuote", quoteDao.selectCount(new LambdaQueryWrapper<Quote>()
                    .eq(Quote::getRfqId, id)
                    .eq(Quote::getSupplierId, rs.getSupplierId())) > 0);
            sups.add(m);
        }
        result.put("suppliers", sups);

        List<Map<String, Object>> quotes = new ArrayList<>();
        for (Quote q : quoteDao.selectList(new LambdaQueryWrapper<Quote>()
                .eq(Quote::getRfqId, id))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("supplierId", q.getSupplierId());
            m.put("supplierName", names.getOrDefault(q.getSupplierId(), q.getSupplierId()));
            m.put("unitPrice", q.getUnitPrice());
            m.put("leadTimeDays", q.getLeadTimeDays());
            m.put("moq", q.getMoq());
            m.put("paymentTerms", q.getPaymentTerms());
            m.put("quoteValidDate", q.getQuoteValidDate());
            m.put("negotiatedPrice", q.getNegotiatedPrice());
            m.put("negotiateNote", q.getNegotiateNote());
            m.put("anomalyFlag", q.getAnomalyFlag());
            m.put("anomalyConfirmed", q.getAnomalyConfirmed());
            m.put("excluded", q.getExcluded());
            m.put("excludedReason", q.getExcludedReason());
            quotes.add(m);
        }
        result.put("quotes", quotes);
        return result;
    }

    @Override
    public Map<String, Object> prCandidates() {
        List<ProcRequisition> prs = prDao.selectList(new LambdaQueryWrapper<ProcRequisition>()
                .eq(ProcRequisition::getStatus, RequisitionStateMachine.PENDING_RFQ)
                .orderByDesc(ProcRequisition::getCreateDate));
        Set<String> busy = new HashSet<>();
        for (Rfq r : rfqDao.selectList(new LambdaQueryWrapper<Rfq>()
                .ne(Rfq::getStatus, RfqStateMachine.CLOSED))) {
            busy.add(r.getPrId());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProcRequisition pr : prs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("prId", pr.getId());
            m.put("prNo", pr.getPrNo());
            m.put("approvalAmount", pr.getApprovalAmount());
            m.put("hasOpenRfq", busy.contains(pr.getId()));
            try {
                Map<String, Object> cl = emergencyService.clearance(pr.getPrNo());
                m.put("emergencyClear", Boolean.TRUE.equals(cl.get("emergency"))
                        && !"1".equals(String.valueOf(cl.get("exceptionFlag"))));
                m.put("emergencyHint", cl.get("hint"));
            } catch (Exception e) {
                m.put("emergencyClear", false);
            }
            items.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidates", items);
        result.put("total", items.size());
        result.put("minQuoteCount", minQuoteCount);
        if (items.isEmpty()) {
            result.put("hint", "暂无待询价的 PR（2.1.1/2.1.4 流转后可见）");
        }
        return result;
    }

    // ---------- 创建（D3） ----------

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String prId = str(payload.get("prId"));
        if (!isNotBlank(prId)) {
            throw new ServiceException(422, "须选择已流转「待询价」的 PR");
        }
        ProcRequisition pr = prDao.selectById(prId);
        if (pr == null) {
            throw new ServiceException(404, "PR 不存在");
        }
        if (!RequisitionStateMachine.PENDING_RFQ.equals(pr.getStatus())) {
            throw new ServiceException(422, "仅限已流转待询价（PENDING_RFQ）的 PR，当前状态 "
                    + pr.getStatus());
        }
        Long openRfq = rfqDao.selectCount(new LambdaQueryWrapper<Rfq>()
                .eq(Rfq::getPrId, prId)
                .ne(Rfq::getStatus, RfqStateMachine.CLOSED));
        if (openRfq != null && openRfq > 0) {
            throw new ServiceException(422, "该 PR 已存在进行中的询价单，请勿重复发起");
        }
        List<String> supplierIds = strList(payload.get("supplierIds"));
        if (supplierIds.isEmpty()) {
            throw new ServiceException(422, "至少选择 1 家合格供应商");
        }
        for (String sid : supplierIds) {
            MdmSupplier s = mdmSupplierDao.selectById(sid);
            if (s == null) {
                throw new ServiceException(422, "供应商不存在：" + sid);
            }
            if (!"QUALIFIED".equals(s.getStatus())) {
                throw new ServiceException(422, "供应商 " + s.getSupplierName()
                        + " 非合格状态（" + s.getStatus() + "），不可参与询价");
            }
        }
        // C-4.2-01 卡控 + 紧急放行例外（clearance 桩首次消费）
        boolean emergencyClear = false;
        try {
            Map<String, Object> cl = emergencyService.clearance(pr.getPrNo());
            emergencyClear = Boolean.TRUE.equals(cl.get("emergency"))
                    && !"1".equals(String.valueOf(cl.get("exceptionFlag")));
        } catch (Exception e) {
            log.warn("clearance check failed: {}", e.getMessage());
        }
        int minCount = emergencyClear ? 1 : minQuoteCount;
        if (supplierIds.size() < minCount) {
            throw new ServiceException(422, emergencyClear
                    ? "紧急放行下仍至少选择 1 家供应商"
                    : "合格供应商数 " + supplierIds.size() + " 低于 MIN_QUOTE_COUNT（"
                    + minQuoteCount + "，C-4.2-01）：请扩充供应商库或发起新供应商准入；"
                    + "紧急采购经采购总监特批可放宽至最低 1 家");
        }
        List<ProcPrLine> prLines = prLineDao.selectList(new LambdaQueryWrapper<ProcPrLine>()
                .eq(ProcPrLine::getPrId, prId).orderByAsc(ProcPrLine::getLineNo));
        if (prLines.isEmpty()) {
            throw new ServiceException(422, "PR 无请购行，不可询价");
        }
        // BR-4.2-44（L1 硬阻断，design D7：口径=本年 PR 预估累计金额）
        assertBelowTenderThreshold(prLines);
        LocalDate earliest = prLines.stream().map(ProcPrLine::getReqDate)
                .min(LocalDate::compareTo).orElseThrow();
        LocalDate deadline;
        String dl = str(payload.get("quoteDeadline"));
        if (isNotBlank(dl)) {
            try {
                deadline = LocalDate.parse(dl.trim());
            } catch (Exception e) {
                throw new ServiceException(422, "报价截止日期格式须为 yyyy-MM-dd");
            }
        } else {
            deadline = RequisitionStateMachine.minusBusinessDays(earliest, 5);
        }
        if (!deadline.isAfter(LocalDate.now())) {
            throw new ServiceException(422, "报价截止日期须晚于今天（默认口径=最早需求日 "
                    + earliest + " − 5 个工作日 = " + deadline + "，请手工指定更晚的截止日）");
        }
        Rfq rfq = new Rfq();
        rfq.setRfqNo(support.nextRfqNo());
        rfq.setPrId(prId);
        rfq.setStatus(RfqStateMachine.DRAFT);
        rfq.setQuoteDeadline(deadline);
        rfq.setSendMode(isNotBlank(str(payload.get("sendMode")))
                ? str(payload.get("sendMode")).trim().toUpperCase() : "OFFLINE");
        rfq.setTaxCodeNo(str(payload.get("taxCodeNo")));
        rfq.setTechNote(str(payload.get("techNote")));
        rfq.setEmergencyFlag(emergencyClear ? "1" : "0");
        rfq.setInsufficientFlag("0");
        try {
            rfqDao.insert(rfq);
        } catch (DuplicateKeyException ex) {
            throw new ServiceException(409, "询价单号生成冲突，请重试");
        }
        int no = 1;
        for (ProcPrLine pl : prLines) {
            RfqLine rl = new RfqLine();
            rl.setRfqId(rfq.getId());
            rl.setLineNo(no++);
            rl.setItemCode(pl.getItemCode());
            rl.setQty(pl.getQty());
            rl.setReqDate(pl.getReqDate());
            lineDao.insert(rl);
        }
        for (String sid : supplierIds) {
            RfqSupplier rs = new RfqSupplier();
            rs.setRfqId(rfq.getId());
            rs.setSupplierId(sid);
            rfqSupplierDao.insert(rs);
        }
        support.publishHead(rfq, "PROC.RFQ.CREATED",
                "创建询价 " + rfq.getRfqNo() + " 供应商 " + supplierIds.size() + " 家"
                        + (emergencyClear ? "（紧急放行）" : "") + " 截止 " + deadline);
        log.info("RFQ created {} pr={} suppliers={} deadline={} emergency={}",
                rfq.getRfqNo(), pr.getPrNo(), supplierIds.size(), deadline, emergencyClear);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rfq", rfq);
        result.put("emergencyClear", emergencyClear);
        return result;
    }

    // ---------- 发出 / 报价 ----------

    @Override
    @Transactional
    public Map<String, Object> send(String id, String sendMode) {
        Rfq rfq = support.requireRfq(id);
        if (!RfqStateMachine.DRAFT.equals(rfq.getStatus())) {
            throw new ServiceException(422, "仅草稿状态可发出，当前 " + rfq.getStatus());
        }
        String mode = isNotBlank(sendMode) ? sendMode.trim().toUpperCase() : "OFFLINE";
        if (!"OFFLINE".equals(mode) && !"ONLINE".equals(mode)) {
            throw new ServiceException(422, "发送模式须为 OFFLINE/ONLINE");
        }
        rfq.setSendMode(mode);
        rfq.setSendStatus("SENT");
        rfq.setSentDate(LocalDateTime.now());
        support.persist(rfq);
        support.transition(rfq, RfqStateMachine.SENT, "发出询价（" + mode + "）");
        support.publishHead(rfq, "PROC.RFQ.SENT",
                "ONLINE".equals(mode) ? "在线发送（邮件/门户为桩，仅记状态）" : "线下确认发出");
        return Map.of("rfq", rfq);
    }

    @Override
    @Transactional
    public Map<String, Object> saveQuote(String rfqId, Map<String, Object> payload) {
        Rfq rfq = support.requireRfq(rfqId);
        String st = rfq.getStatus();
        if (RfqStateMachine.DRAFT.equals(st)) {
            throw new ServiceException(422, "草稿状态不可录入报价，须先发出询价");
        }
        if (!RfqStateMachine.SENT.equals(st) && !RfqStateMachine.QUOTING.equals(st)) {
            throw new ServiceException(422, "当前状态 " + st + " 已锁价或已结束，报价不可录入/修改");
        }
        String supplierId = str(payload.get("supplierId"));
        if (!isNotBlank(supplierId)) {
            throw new ServiceException(422, "须指定报价供应商");
        }
        boolean invited = rfqSupplierDao.selectCount(new LambdaQueryWrapper<RfqSupplier>()
                .eq(RfqSupplier::getRfqId, rfqId)
                .eq(RfqSupplier::getSupplierId, supplierId)) > 0;
        if (!invited) {
            throw new ServiceException(422, "该供应商不在本询价单邀请清单内");
        }
        BigDecimal price = decimal(payload.get("unitPrice"), "单价");
        if (price == null || price.signum() <= 0) {
            throw new ServiceException(422, "单价须大于 0（4 位小数）");
        }
        Integer lead = intOf(payload.get("leadTimeDays"));
        if (lead == null || lead <= 0) {
            throw new ServiceException(422, "交货期（天）须大于 0");
        }
        BigDecimal moq = decimal(payload.get("moq"), "MOQ");
        if (moq == null || moq.signum() <= 0) {
            throw new ServiceException(422, "MOQ 须大于 0");
        }
        String terms = str(payload.get("paymentTerms"));
        if (!Set.of("NET30", "NET60", "NET90", "PREPAY").contains(terms == null ? "" : terms)) {
            throw new ServiceException(422, "付款条件须为 NET30/NET60/NET90/款到发货(PREPAY) 之一");
        }
        LocalDate validDate = date(str(payload.get("quoteValidDate")));
        if (validDate == null) {
            throw new ServiceException(422, "报价有效期必填");
        }
        Quote existing = quoteDao.selectOne(new LambdaQueryWrapper<Quote>()
                .eq(Quote::getRfqId, rfqId)
                .eq(Quote::getSupplierId, supplierId));
        boolean first = existing == null;
        if (first) {
            existing = new Quote();
            existing.setRfqId(rfqId);
            existing.setSupplierId(supplierId);
            existing.setAnomalyFlag("0");
            existing.setAnomalyConfirmed("0");
            existing.setExcluded("0");
        }
        existing.setUnitPrice(price.setScale(4, RoundingMode.HALF_UP));
        existing.setLeadTimeDays(lead);
        existing.setMoq(moq);
        existing.setPaymentTerms(terms);
        existing.setQuoteValidDate(validDate);
        if (first) {
            quoteDao.insert(existing);
        } else {
            quoteDao.updateById(existing);
        }
        // 双事件：QUOTED（先 persist 递增保证键唯一）+ 首录时 SENT→QUOTING
        support.persist(rfq);
        support.publishHead(rfq, "PROC.RFQ.QUOTED",
                "收到报价：" + supplierId + " 单价 " + price);
        if (RfqStateMachine.SENT.equals(st)) {
            support.transition(rfq, RfqStateMachine.QUOTING, "首条报价录入");
            support.publishHead(rfq, "PROC.RFQ.QUOTING", "进入报价收集");
        }
        log.info("RFQ {} quote {} price={} first={}", rfq.getRfqNo(), supplierId, price, first);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rfq", rfq);
        result.put("first", first);
        return result;
    }

    // ---------- 延期 / 追加 / 关闭 ----------

    @Override
    @Transactional
    public Map<String, Object> postpone(String id, String newDeadline, String reason) {
        Rfq rfq = support.requireRfq(id);
        String st = rfq.getStatus();
        if (RfqStateMachine.QUOTED_CLOSED.equals(st) || RfqStateMachine.AWARDED.equals(st)
                || RfqStateMachine.CLOSED.equals(st)) {
            throw new ServiceException(422, "已锁价/已定标/已关闭的询价单不可延期（当前 " + st
                    + "；锁价不足请作废重询）");
        }
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "延期原因必填（至少 2 字）");
        }
        LocalDate nd;
        try {
            nd = LocalDate.parse(newDeadline == null ? "" : newDeadline.trim());
        } catch (Exception e) {
            throw new ServiceException(422, "新截止日期格式须为 yyyy-MM-dd");
        }
        if (!nd.isAfter(rfq.getQuoteDeadline())) {
            throw new ServiceException(422, "新截止日须晚于当前截止日 " + rfq.getQuoteDeadline());
        }
        LocalDate old = rfq.getQuoteDeadline();
        rfq.setQuoteDeadline(nd);
        support.persist(rfq);
        log.info("RFQ {} deadline {} -> {} reason={} (BR-4.2-13 处置留痕)", rfq.getRfqNo(),
                old, nd, reason.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rfq", rfq);
        result.put("oldDeadline", String.valueOf(old));
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> addSuppliers(String id, List<String> supplierIds) {
        Rfq rfq = support.requireRfq(id);
        String st = rfq.getStatus();
        if (RfqStateMachine.QUOTED_CLOSED.equals(st) || RfqStateMachine.AWARDED.equals(st)
                || RfqStateMachine.CLOSED.equals(st)) {
            throw new ServiceException(422, "已锁价/已定标/已关闭的询价单不可追加供应商（当前 " + st
                    + "；锁价不足请作废重询）");
        }
        if (supplierIds == null || supplierIds.isEmpty()) {
            throw new ServiceException(422, "至少选择 1 家供应商");
        }
        int added = 0;
        for (String sid : supplierIds) {
            MdmSupplier s = mdmSupplierDao.selectById(sid);
            if (s == null || !"QUALIFIED".equals(s.getStatus())) {
                throw new ServiceException(422, "供应商 " + (s == null ? sid : s.getSupplierName())
                        + " 非合格状态，不可追加");
            }
            Long exists = rfqSupplierDao.selectCount(new LambdaQueryWrapper<RfqSupplier>()
                    .eq(RfqSupplier::getRfqId, id)
                    .eq(RfqSupplier::getSupplierId, sid));
            if (exists == null || exists == 0) {
                RfqSupplier rs = new RfqSupplier();
                rs.setRfqId(id);
                rs.setSupplierId(sid);
                rfqSupplierDao.insert(rs);
                added++;
            }
        }
        long total = rfqSupplierDao.selectCount(new LambdaQueryWrapper<RfqSupplier>()
                .eq(RfqSupplier::getRfqId, id));
        if (total < 1) {
            throw new ServiceException(422, "追加后邀请清单不得为空");
        }
        rfq.setSendStatus("RESEND_REQUIRED");
        support.persist(rfq);
        log.info("RFQ {} addSuppliers +{} total={} (追加后须重新发出，桩)", rfq.getRfqNo(), added, total);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", added);
        result.put("totalSuppliers", total);
        return result;
    }

    @Override
    @Transactional
    public void closeRfq(String id, String reason) {
        Rfq rfq = support.requireRfq(id);
        if (RfqStateMachine.AWARDED.equals(rfq.getStatus())) {
            throw new ServiceException(422, "已定标不可关闭");
        }
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "关闭原因必填（至少 2 字）");
        }
        rfq.setCloseReason(reason.trim());
        support.persist(rfq);
        support.transition(rfq, RfqStateMachine.CLOSED, reason.trim());
        support.publishHead(rfq, "PROC.RFQ.CLOSED", "关闭/作废：" + reason.trim());
        log.info("RFQ {} closed: {}（作废重询后 PR 可重建）", rfq.getRfqNo(), reason);
    }

    // ---------- 比价矩阵（D4） ----------

    @Override
    public Map<String, Object> matrix(String id, Integer weightPrice, Integer weightDelivery) {
        Rfq rfq = support.requireRfq(id);
        int wp = weightPrice == null ? 60 : weightPrice;
        int wd = weightDelivery == null ? 40 : weightDelivery;
        if (wp + wd != 100) {
            throw new ServiceException(422, "权重合计须为 100（当前 " + (wp + wd) + "）");
        }
        List<Quote> all = quoteDao.selectList(new LambdaQueryWrapper<Quote>()
                .eq(Quote::getRfqId, id));
        List<Quote> valid = all.stream()
                .filter(q -> !"1".equals(q.getExcluded()))
                .filter(q -> q.getId() != null && q.getUnitPrice() != null
                        && q.getLeadTimeDays() != null)
                .toList();
        if (valid.size() < all.stream().filter(q -> !"1".equals(q.getExcluded())).count()) {
            log.warn("RFQ {} matrix skipped rows with null id/price/lead (all={} valid={})",
                    rfq.getRfqNo(), all.size(), valid.size());
        }
        Map<String, BigDecimal> effective = new HashMap<>();
        for (Quote q : valid) {
            effective.put(q.getId(), effPrice(q));
        }
        BigDecimal mean = null;
        if (!valid.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Quote q : valid) {
                sum = sum.add(effective.get(q.getId()));
            }
            mean = sum.divide(BigDecimal.valueOf(valid.size()), 4, RoundingMode.HALF_UP);
        }
        // 含税试算（跨税码域：TAX_CODE_NO + RFQ 创建日）
        BigDecimal taxRate = null;
        String taxNote;
        if (isNotBlank(rfq.getTaxCodeNo())) {
            MdmTaxCode hit = taxCodeDao.selectHit(rfq.getTaxCodeNo().trim().toUpperCase(),
                    rfq.getCreateDate().toLocalDate());
            if (hit != null) {
                taxRate = hit.getTaxRate();
                taxNote = "税码 " + hit.getTaxCode() + " @" + rfq.getCreateDate().toLocalDate().toString();
            } else {
                taxNote = "待税率（税码 " + rfq.getTaxCodeNo() + " 于 RFQ 创建日无覆盖）";
            }
        } else {
            taxNote = "待税率（未指定税码编号）";
        }
        BigDecimal minPrice = valid.stream().map(q -> effective.get(q.getId()))
                .filter(java.util.Objects::nonNull)
                .min(BigDecimal::compareTo).orElse(null);
        Integer minLead = valid.stream().map(Quote::getLeadTimeDays)
                .min(Integer::compareTo).orElse(null);

        Map<String, String> supplierNames = supplierNames(id);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Quote q : all) {
            boolean excluded = "1".equals(q.getExcluded());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("quoteId", q.getId());
            m.put("supplierId", q.getSupplierId());
            m.put("supplierName", supplierNames.getOrDefault(q.getSupplierId(), q.getSupplierId()));
            m.put("unitPrice", q.getUnitPrice());
            m.put("negotiatedPrice", q.getNegotiatedPrice());
            m.put("effectivePrice", excluded ? null : effective.get(q.getId()));
            m.put("leadTimeDays", q.getLeadTimeDays());
            m.put("moq", q.getMoq());
            m.put("paymentTerms", q.getPaymentTerms());
            m.put("quoteValidDate", q.getQuoteValidDate());
            m.put("excluded", excluded);
            m.put("excludedReason", q.getExcludedReason());
            m.put("anomalyConfirmed", q.getAnomalyConfirmed());
            if (excluded) {
                m.put("anomaly", false);
                m.put("deviation", null);
            } else {
                BigDecimal eff = effective.get(q.getId());
                boolean anomaly = false;
                BigDecimal deviation = null;
                if (mean != null && mean.signum() > 0) {
                    deviation = eff.subtract(mean).abs()
                            .divide(mean, 4, RoundingMode.HALF_UP);
                    anomaly = deviation.compareTo(new BigDecimal("0.20")) > 0;
                }
                m.put("anomaly", anomaly);
                m.put("deviation", deviation);
                if (anomaly && !"1".equals(q.getAnomalyFlag())) {
                    q.setAnomalyFlag("1");
                    quoteDao.updateById(q);
                    log.info("RFQ {} quote {} 异常偏离 {} (>20%)", rfq.getRfqNo(),
                            q.getSupplierId(), deviation);
                }
            }
            m.put("anomalyFlag", q.getAnomalyFlag());
            if (excluded || effective.get(q.getId()) == null) {
                m.put("taxIncluded", null);
            } else if (taxRate != null) {
                m.put("taxIncluded", effective.get(q.getId())
                        .multiply(BigDecimal.ONE.add(taxRate.divide(new BigDecimal("100"), 6,
                                RoundingMode.HALF_UP)))
                        .setScale(4, RoundingMode.HALF_UP));
            } else {
                m.put("taxIncluded", null);
            }
            m.put("onTimeDelivery", null);
            m.put("qualityRate", null);
            m.put("qualityLevel", null);
            m.put("compositeScore", null);
            rows.add(m);
        }
        // 加权总分（仅有效行）
        for (Map<String, Object> m : rows) {
            if (Boolean.TRUE.equals(m.get("excluded"))) {
                m.put("priceScore", null);
                m.put("deliveryScore", null);
                m.put("totalScore", null);
                continue;
            }
            BigDecimal eff = (BigDecimal) m.get("effectivePrice");
            BigDecimal priceScore = (minPrice != null && minPrice.signum() > 0 && eff != null)
                    ? minPrice.divide(eff, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;
            Integer lead = (Integer) m.get("leadTimeDays");
            BigDecimal deliveryScore;
            if (lead == null || lead <= 0 || minLead == null || minLead <= 0) {
                deliveryScore = BigDecimal.ZERO;
            } else {
                deliveryScore = BigDecimal.valueOf(minLead)
                        .divide(BigDecimal.valueOf(lead), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
            BigDecimal total = priceScore.multiply(BigDecimal.valueOf(wp))
                    .add(deliveryScore.multiply(BigDecimal.valueOf(wd)))
                    .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            m.put("priceScore", priceScore.setScale(2, RoundingMode.HALF_UP));
            m.put("deliveryScore", deliveryScore.setScale(2, RoundingMode.HALF_UP));
            m.put("totalScore", total.setScale(2, RoundingMode.HALF_UP));
        }
        rows.sort(Comparator.comparing(
                (Map<String, Object> m) -> m.get("totalScore") == null
                        ? BigDecimal.ZERO : (BigDecimal) m.get("totalScore"),
                Comparator.reverseOrder()));
        long anomalyOpen = rows.stream()
                .filter(m -> Boolean.TRUE.equals(m.get("anomaly"))
                        && !"1".equals(String.valueOf(m.get("anomalyConfirmed")))
                        && !Boolean.TRUE.equals(m.get("excluded")))
                .count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("weightPrice", wp);
        result.put("weightDelivery", wd);
        result.put("mean", mean);
        result.put("taxNote", taxNote);
        result.put("taxRate", taxRate);
        result.put("anomalyOpenCount", anomalyOpen);
        result.put("stubColumns", List.of("历史准时交付率", "历史来料合格率", "质量等级", "综合评分（待接入 PO/质检数据）"));
        return result;
    }

    // ---------- 报价动作（3.2） ----------

    @Override
    @Transactional
    public Map<String, Object> confirmAnomaly(String quoteId) {
        Quote q = requireQuote(quoteId);
        if (!"1".equals(q.getAnomalyFlag())) {
            throw new ServiceException(422, "该报价无异常偏离标记，无需确认");
        }
        q.setAnomalyConfirmed("1");
        q.setAnomalyConfirmBy(SecurityUtils.getCurrentUserId());
        q.setAnomalyConfirmDate(LocalDateTime.now());
        quoteDao.updateById(q);
        log.info("RFQ quote {} anomaly confirmed by {}", q.getSupplierId(),
                q.getAnomalyConfirmBy());
        return Map.of("quote", q);
    }

    @Override
    @Transactional
    public Map<String, Object> excludeQuote(String quoteId, String reason) {
        Quote q = requireQuote(quoteId);
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "剔除原因必填（至少 2 字）");
        }
        q.setExcluded("1");
        q.setExcludedReason(reason.trim());
        quoteDao.updateById(q);
        log.info("RFQ quote {} excluded: {}", q.getSupplierId(), reason);
        return Map.of("quote", q);
    }

    @Override
    @Transactional
    public Map<String, Object> negotiate(String quoteId, BigDecimal price, String note) {
        Quote q = requireQuote(quoteId);
        if (price == null || price.signum() <= 0) {
            throw new ServiceException(422, "谈判后单价须大于 0");
        }
        if (!isNotBlank(note) || note.trim().length() < 2) {
            throw new ServiceException(422, "谈判说明必填（至少 2 字）");
        }
        Rfq rfq = support.requireRfq(q.getRfqId());
        if (RfqStateMachine.QUOTED_CLOSED.equals(rfq.getStatus())
                || RfqStateMachine.AWARDED.equals(rfq.getStatus())
                || RfqStateMachine.CLOSED.equals(rfq.getStatus())) {
            throw new ServiceException(422, "已锁价/已定标/已关闭不可谈判改价（当前 " + rfq.getStatus() + "）");
        }
        BigDecimal old = q.getUnitPrice();
        q.setNegotiatedPrice(price.setScale(4, RoundingMode.HALF_UP));
        q.setNegotiateNote(note.trim());
        quoteDao.updateById(q);
        log.info("RFQ {} negotiate {} original={} negotiated={} note={}", rfq.getRfqNo(),
                q.getSupplierId(), old, price, note.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quote", q);
        result.put("originalPrice", String.valueOf(old));
        return result;
    }

    // ---------- 定标（3.3） ----------

    @Override
    @Transactional
    public Map<String, Object> award(String id, Map<String, Object> payload) {
        Rfq rfq = support.requireRfq(id);
        if (!RfqStateMachine.QUOTED_CLOSED.equals(rfq.getStatus())) {
            throw new ServiceException(422, "仅已锁价（QUOTED_CLOSED）可定标，当前 "
                    + rfq.getStatus()
                    + (RfqStateMachine.DRAFT.equals(rfq.getStatus())
                    || RfqStateMachine.SENT.equals(rfq.getStatus())
                    || RfqStateMachine.QUOTING.equals(rfq.getStatus())
                    ? "（未到截止日，可用延期或等待锁价）" : ""));
        }
        if ("1".equals(rfq.getInsufficientFlag())) {
            throw new ServiceException(422, "有效报价不足 MIN_QUOTE_COUNT 且无紧急放行（BR-4.2-13）："
                    + "定标被阻断，请作废重询或扩充报价");
        }
        String supplierId = str(payload.get("supplierId"));
        if (!isNotBlank(supplierId)) {
            throw new ServiceException(422, "须指定中选供应商");
        }
        Quote win = quoteDao.selectOne(new LambdaQueryWrapper<Quote>()
                .eq(Quote::getRfqId, id)
                .eq(Quote::getSupplierId, supplierId));
        if (win == null) {
            throw new ServiceException(422, "中选供应商无报价记录");
        }
        if ("1".equals(win.getExcluded())) {
            throw new ServiceException(422, "中选报价已被剔除，不可定标");
        }
        long validCount = quoteDao.selectCount(new LambdaQueryWrapper<Quote>()
                .eq(Quote::getRfqId, id)
                .eq(Quote::getExcluded, "0"));
        boolean emergency = "1".equals(rfq.getEmergencyFlag());
        if (validCount < minQuoteCount && !emergency) {
            throw new ServiceException(422, "有效报价 " + validCount + " 家 < MIN_QUOTE_COUNT（"
                    + minQuoteCount + "）且无紧急放行（C-4.2-01/BR-4.2-13）");
        }
        List<Quote> unhandled = quoteDao.selectList(new LambdaQueryWrapper<Quote>()
                .eq(Quote::getRfqId, id)
                .eq(Quote::getAnomalyFlag, "1")
                .eq(Quote::getAnomalyConfirmed, "0")
                .eq(Quote::getExcluded, "0"));
        if (!unhandled.isEmpty()) {
            throw new ServiceException(422, "存在 " + unhandled.size()
                    + " 条未处理的异常偏离报价（BR-4.2-12）：须逐条确认保留或剔除后方可定标");
        }
        String analysisNo = str(payload.get("analysisNo"));
        String conclusion = str(payload.get("conclusion"));
        if (!isNotBlank(analysisNo) || analysisNo.trim().length() < 4) {
            throw new ServiceException(422, "比价分析表编号必填（≥4 字符，文档生成为桩）");
        }
        if (!isNotBlank(conclusion) || conclusion.trim().length() < 2) {
            throw new ServiceException(422, "比价分析结论必填（至少 2 字）");
        }
        Integer wp = intOf(payload.get("weightPrice"));
        Integer wd = intOf(payload.get("weightDelivery"));
        if (wp == null || wd == null || wp + wd != 100) {
            throw new ServiceException(422, "定标权重须随单快照且合计 100（当前 "
                    + (wp == null ? 0 : wp) + "+" + (wd == null ? 0 : wd) + "）");
        }
        BigDecimal finalPrice = win.getNegotiatedPrice() != null
                ? win.getNegotiatedPrice() : win.getUnitPrice();
        MdmSupplier winner = mdmSupplierDao.selectById(supplierId);
        rfq.setWeightPrice(wp);
        rfq.setWeightDelivery(wd);
        rfq.setAwardSupplierId(supplierId);
        rfq.setAwardPrice(finalPrice);
        rfq.setAnalysisNo(analysisNo.trim());
        rfq.setAnalysisConclusion(conclusion.trim());
        support.persist(rfq);
        support.transition(rfq, RfqStateMachine.AWARDED, "定标 " + analysisNo.trim());
        support.publishHead(rfq, "PROC.RFQ.AWARDED",
                "定标：中选 " + (winner == null ? supplierId : winner.getSupplierName())
                        + " 成交价 " + finalPrice + " 分析表 " + analysisNo.trim()
                        + " 权重 " + wp + "/" + wd);
        log.info("RFQ {} awarded to {} price={} analysis={}", rfq.getRfqNo(), supplierId,
                finalPrice, analysisNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rfq", rfq);
        result.put("awardPrice", finalPrice);
        result.put("supplierName", winner == null ? null : winner.getSupplierName());
        return result;
    }

    @Override
    public Map<String, Object> awarded(String prNo) {
        if (!isNotBlank(prNo)) {
            throw new ServiceException(422, "prNo 必填");
        }
        ProcRequisition pr = prDao.selectOne(new LambdaQueryWrapper<ProcRequisition>()
                .eq(ProcRequisition::getPrNo, prNo.trim()));
        if (pr == null) {
            throw new ServiceException(404, "PR 不存在：" + prNo);
        }
        Rfq rfq = rfqDao.selectOne(new LambdaQueryWrapper<Rfq>()
                .eq(Rfq::getPrId, pr.getId())
                .eq(Rfq::getStatus, RfqStateMachine.AWARDED)
                .orderByDesc(Rfq::getCreateDate)
                .last("LIMIT 1"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prNo", pr.getPrNo());
        if (rfq == null) {
            result.put("awarded", false);
            result.put("hint", "该 PR 无中选结果（供 2.3.1 订单创建消费的桩）");
            return result;
        }
        MdmSupplier winner = rfq.getAwardSupplierId() == null ? null
                : mdmSupplierDao.selectById(rfq.getAwardSupplierId());
        result.put("awarded", true);
        result.put("rfqNo", rfq.getRfqNo());
        result.put("supplierId", rfq.getAwardSupplierId());
        result.put("supplierName", winner == null ? null : winner.getSupplierName());
        result.put("awardPrice", rfq.getAwardPrice());
        result.put("emergency", "1".equals(rfq.getEmergencyFlag()));
        result.put("analysisNo", rfq.getAnalysisNo());
        return result;
    }

    // ---------- 懒 sweep：截止锁价 + 不足标记（D2） ----------

    private void sweep() {
        for (Rfq rfq : rfqDao.selectList(new LambdaQueryWrapper<Rfq>()
                .in(Rfq::getStatus,
                        RfqStateMachine.DRAFT, RfqStateMachine.SENT, RfqStateMachine.QUOTING)
                .isNotNull(Rfq::getQuoteDeadline))) {
            long overdueWd = RequisitionStateMachine.businessDaysBetween(
                    rfq.getQuoteDeadline(), LocalDateTime.now());
            if (overdueWd >= 1) {
                support.transition(rfq, RfqStateMachine.QUOTED_CLOSED, "报价截止锁价");
                support.publishHead(rfq, "PROC.RFQ.LOCKED",
                        "截止 " + rfq.getQuoteDeadline() + " 锁价");
                long valid = quoteDao.selectCount(new LambdaQueryWrapper<Quote>()
                        .eq(Quote::getRfqId, rfq.getId())
                        .eq(Quote::getExcluded, "0"));
                boolean emergency = "1".equals(rfq.getEmergencyFlag()) && emergencyStillValid(rfq);
                if (valid < minQuoteCount && !emergency) {
                    rfq.setInsufficientFlag("1");
                    support.persist(rfq);
                    log.info("[TODO-NOTIFY] RFQ {} 锁价后有效报价 {} < {} 不足（BR-4.2-13，处置=作废重询）",
                            rfq.getRfqNo(), valid, minQuoteCount);
                }
                log.info("RFQ {} locked by deadline {}", rfq.getRfqNo(), rfq.getQuoteDeadline());
            }
        }
    }

    /** 紧急放行复核（EA 逾期后失效） */
    private boolean emergencyStillValid(Rfq rfq) {
        try {
            String prNo = prNo(rfq.getPrId());
            Map<String, Object> cl = emergencyService.clearance(prNo);
            return Boolean.TRUE.equals(cl.get("emergency"))
                    && !"1".equals(String.valueOf(cl.get("exceptionFlag")));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 私有 ----------

    /**
     * BR-4.2-44（L1 硬阻断）：任一品类本年累计 PR 预估金额 &gt; 招标门槛 → 阻断创建询价。
     * <p>design D7：当前无 PO 表，"本年累计采购额"以本年 PR 预估金额 Σ(qty×estUnitPrice) 近似，
     * 与 ProcApprovalService 的判级金额同源；PO 落地后再校准真实口径。</p>
     */
    private void assertBelowTenderThreshold(List<ProcPrLine> prLines) {
        if (tenderThresholdAmount == null
                || tenderThresholdAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        List<String> itemCodes = new ArrayList<>();
        for (ProcPrLine pl : prLines) {
            if (isNotBlank(pl.getItemCode()) && !itemCodes.contains(pl.getItemCode())) {
                itemCodes.add(pl.getItemCode());
            }
        }
        if (itemCodes.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = prLineDao.sumYearPrAmountByItemCodes(itemCodes);
        for (Map<String, Object> row : rows) {
            Object cat = row.get("categoryCode");
            Object amt = row.get("amount");
            if (cat == null || amt == null) {
                continue;
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(String.valueOf(amt));
            } catch (NumberFormatException e) {
                continue;
            }
            if (amount.compareTo(tenderThresholdAmount) > 0) {
                throw new ServiceException(422, "品类「" + cat + "」本年累计 PR 预估金额 "
                        + amount.stripTrailingZeros().toPlainString() + " 元，已超招标门槛 "
                        + tenderThresholdAmount.stripTrailingZeros().toPlainString()
                        + " 元（BR-4.2-44，L1 硬阻断）：该品类须走招标流程，"
                        + "请先创建招标项目，不得直接询比价下单");
            }
        }
    }

    private BigDecimal effPrice(Quote q) {
        return q.getNegotiatedPrice() != null ? q.getNegotiatedPrice() : q.getUnitPrice();
    }

    private Map<String, String> supplierNames(String rfqId) {
        Map<String, String> out = new HashMap<>();
        List<RfqSupplier> list = rfqSupplierDao.selectList(new LambdaQueryWrapper<RfqSupplier>()
                .eq(RfqSupplier::getRfqId, rfqId));
        for (RfqSupplier rs : list) {
            MdmSupplier s = mdmSupplierDao.selectById(rs.getSupplierId());
            out.put(rs.getSupplierId(), s == null ? rs.getSupplierId() : s.getSupplierName());
        }
        return out;
    }

    private String prNo(String prId) {
        ProcRequisition pr = prDao.selectById(prId);
        return pr == null ? null : pr.getPrNo();
    }

    private Quote requireQuote(String quoteId) {
        Quote q = quoteDao.selectById(quoteId);
        if (q == null) {
            throw new ServiceException(404, "报价记录不存在");
        }
        return q;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                if (x != null && isNotBlank(String.valueOf(x))) {
                    out.add(String.valueOf(x).trim());
                }
            }
        }
        return out;
    }

    private BigDecimal decimal(Object o, String name) {
        String s = str(o);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(422, name + "格式非法：" + s);
        }
    }

    private Integer intOf(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ServiceException(422, "日期格式须为 yyyy-MM-dd：" + s);
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
