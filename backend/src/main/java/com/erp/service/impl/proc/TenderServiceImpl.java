package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.proc.FrameworkAgreementDao;
import com.erp.dao.proc.FrameworkAgreementLineDao;
import com.erp.dao.proc.TenderDao;
import com.erp.dao.proc.TenderAwardDao;
import com.erp.dao.proc.TenderJudgeDao;
import com.erp.dao.proc.TenderLineDao;
import com.erp.dao.proc.TenderObjectionDao;
import com.erp.dao.proc.TenderQuoteDao;
import com.erp.dao.proc.TenderScoreDao;
import com.erp.dao.proc.TenderSupplierDao;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.proc.FrameworkAgreement;
import com.erp.entity.proc.FrameworkAgreementLine;
import com.erp.entity.proc.Tender;
import com.erp.entity.proc.TenderAward;
import com.erp.entity.proc.TenderJudge;
import com.erp.entity.proc.TenderLine;
import com.erp.entity.proc.TenderObjection;
import com.erp.entity.proc.TenderQuote;
import com.erp.entity.proc.TenderScore;
import com.erp.entity.proc.TenderSupplier;
import com.erp.entity.system.SysUser;
import com.erp.procurement.TenderStateMachine;
import com.erp.service.proc.TenderService;
import com.erp.service.proc.TenderSupport;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 招标竞价实现（add-tender-bidding design D1~D9）：
 * 查询前置懒 sweep（截止即锁，偏差 D5）；多轮报价只降不升；资格审查重算合格家数（BR-4.2-45）。
 */
@Slf4j
@Service
public class TenderServiceImpl implements TenderService {

    /** 询价/投标最少供应商数（参数 MIN_QUOTE_COUNT，默认 3，与 RfqServiceImpl 同源） */
    @Value("${app.proc.min-quote-count:3}")
    private int minQuoteCount;

    /** 公示期（BR-4.2-48，默认 3 个工作日） */
    @Value("${app.proc.publicity-days:3}")
    private int publicityDays;

    /** 框架协议有效期（BR-4.2-05，默认 12 个月） */
    @Value("${app.proc.agreement-valid-months:12}")
    private int agreementValidMonths;

    /** 协议临期提醒阈值（L1059：到期前 30 天提醒续签，design D4） */
    @Value("${app.proc.expiry-remind-days:30}")
    private int expiryRemindDays;

    private final TenderDao tenderDao;
    private final TenderLineDao lineDao;
    private final TenderSupplierDao supplierDao;
    private final TenderQuoteDao quoteDao;
    private final TenderJudgeDao judgeDao;
    private final TenderScoreDao scoreDao;
    private final TenderAwardDao awardDao;
    private final FrameworkAgreementDao agreementDao;
    private final FrameworkAgreementLineDao agreementLineDao;
    private final TenderObjectionDao objectionDao;
    private final MdmSupplierDao mdmSupplierDao;
    private final com.erp.dao.system.SysUserDao sysUserDao;
    private final TenderSupport support;

    public TenderServiceImpl(TenderDao tenderDao,
                             TenderLineDao lineDao,
                             TenderSupplierDao supplierDao,
                             TenderQuoteDao quoteDao,
                             TenderJudgeDao judgeDao,
                             TenderScoreDao scoreDao,
                             TenderAwardDao awardDao,
                             FrameworkAgreementDao agreementDao,
                             FrameworkAgreementLineDao agreementLineDao,
                             TenderObjectionDao objectionDao,
                             MdmSupplierDao mdmSupplierDao,
                             com.erp.dao.system.SysUserDao sysUserDao,
                             TenderSupport support) {
        this.tenderDao = tenderDao;
        this.lineDao = lineDao;
        this.supplierDao = supplierDao;
        this.quoteDao = quoteDao;
        this.judgeDao = judgeDao;
        this.scoreDao = scoreDao;
        this.awardDao = awardDao;
        this.agreementDao = agreementDao;
        this.agreementLineDao = agreementLineDao;
        this.objectionDao = objectionDao;
        this.mdmSupplierDao = mdmSupplierDao;
        this.sysUserDao = sysUserDao;
        this.support = support;
    }

    // ---------- 分页（sweep 先行） ----------

    @Override
    public Page<Map<String, Object>> page(long current, long size, String status, String keyword) {
        sweep();
        LambdaQueryWrapper<Tender> qw = new LambdaQueryWrapper<Tender>()
                .eq(isNotBlank(status), Tender::getStatus, status)
                .and(isNotBlank(keyword), w -> w
                        .like(Tender::getTenderNo, keyword.trim())
                        .or().like(Tender::getTitle, keyword.trim()))
                .orderByDesc(Tender::getCreateDate);
        Page<Tender> raw = tenderDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> records = new ArrayList<>();
        for (Tender t : raw.getRecords()) {
            records.add(row(t));
        }
        result.setRecords(records);
        return result;
    }

    @Override
    public Map<String, Object> detail(String id) {
        sweep();
        Tender t = support.requireTender(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tender", t);
        result.put("lines", lineDao.selectList(new LambdaQueryWrapper<TenderLine>()
                .eq(TenderLine::getTenderId, id).orderByAsc(TenderLine::getLineNo)));

        // 投标方 + 名称 + 合格标记
        List<Map<String, Object>> sups = new ArrayList<>();
        long qualified = 0;
        for (TenderSupplier ts : supplierDao.selectList(new LambdaQueryWrapper<TenderSupplier>()
                .eq(TenderSupplier::getTenderId, id).orderByAsc(TenderSupplier::getCreateDate))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ts.getId());
            row.put("supplierId", ts.getSupplierId());
            row.put("supplierName", supplierName(ts.getSupplierId()));
            row.put("joinSource", ts.getJoinSource());
            row.put("qualifyStatus", ts.getQualifyStatus());
            row.put("qualifyNote", ts.getQualifyNote());
            row.put("qualifyBy", ts.getQualifyBy());
            row.put("qualifyDate", ts.getQualifyDate());
            if ("PASS".equals(ts.getQualifyStatus())) {
                qualified++;
            }
            sups.add(row);
        }
        result.put("suppliers", sups);
        result.put("qualifiedCount", qualified);

        // 报价按供应商分组（轮次升序）
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (TenderQuote q : quoteDao.selectList(new LambdaQueryWrapper<TenderQuote>()
                .eq(TenderQuote::getTenderId, id)
                .orderByAsc(TenderQuote::getSupplierId).orderByAsc(TenderQuote::getRoundNo))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", q.getId());
            row.put("supplierId", q.getSupplierId());
            row.put("roundNo", q.getRoundNo());
            row.put("unitPrice", q.getUnitPrice());
            row.put("leadTimeDays", q.getLeadTimeDays());
            row.put("moq", q.getMoq());
            row.put("paymentTerms", q.getPaymentTerms());
            row.put("quoteTime", q.getQuoteTime());
            row.put("operator", q.getOperator());
            grouped.computeIfAbsent(q.getSupplierId(), k -> new ArrayList<>()).add(row);
        }
        result.put("quotes", grouped);

        // 评委 + 评分（分评委）
        List<Map<String, Object>> judges = new ArrayList<>();
        for (TenderJudge j : judgeDao.selectList(new LambdaQueryWrapper<TenderJudge>()
                .eq(TenderJudge::getTenderId, id))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("judgeUserId", j.getJudgeUserId());
            row.put("judgeName", j.getJudgeName());
            List<Map<String, Object>> scores = new ArrayList<>();
            for (TenderScore s : scoreDao.selectList(new LambdaQueryWrapper<TenderScore>()
                    .eq(TenderScore::getTenderId, id)
                    .eq(TenderScore::getJudgeUserId, j.getJudgeUserId())
                    .orderByAsc(TenderScore::getSupplierId))) {
                Map<String, Object> sc = new LinkedHashMap<>();
                sc.put("supplierId", s.getSupplierId());
                sc.put("scorePrice", s.getScorePrice());
                sc.put("scoreDelivery", s.getScoreDelivery());
                sc.put("scoreQuality", s.getScoreQuality());
                sc.put("scoreCooperation", s.getScoreCooperation());
                sc.put("weightedScore", s.getWeightedScore());
                sc.put("status", s.getStatus());
                sc.put("submitTime", s.getSubmitTime());
                scores.add(sc);
            }
            row.put("scores", scores);
            judges.add(row);
        }
        result.put("judges", judges);

        // 中标子表（spec「中标子表留存与兼容」：公示/详情/协议生成的权威来源，design D2）
        List<Map<String, Object>> awards = new ArrayList<>();
        for (TenderAward a : awardDao.selectList(new LambdaQueryWrapper<TenderAward>()
                .eq(TenderAward::getTenderId, id).orderByAsc(TenderAward::getLineNo))) {
            Map<String, Object> aw = new LinkedHashMap<>();
            aw.put("id", a.getId());
            aw.put("lineNo", a.getLineNo());
            aw.put("supplierId", a.getSupplierId());
            aw.put("supplierName", a.getSupplierName());
            aw.put("awardPrice", a.getAwardPrice());
            aw.put("sharePct", a.getSharePct());
            awards.add(aw);
        }
        result.put("awards", awards);

        // 异议登记（BR-4.2-48，供复核入口取 ID）
        List<Map<String, Object>> objections = new ArrayList<>();
        for (TenderObjection o : objectionDao.selectList(new LambdaQueryWrapper<TenderObjection>()
                .eq(TenderObjection::getTenderId, id)
                .orderByDesc(TenderObjection::getSubmitDate))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.getId());
            row.put("objectionNo", o.getObjectionNo());
            row.put("validFlag", o.getValidFlag());
            row.put("content", o.getContent());
            row.put("submitDate", o.getSubmitDate());
            row.put("reviewStatus", o.getReviewStatus());
            row.put("reviewResult", o.getReviewResult());
            row.put("reviewBy", o.getReviewBy());
            row.put("reviewDate", o.getReviewDate());
            objections.add(row);
        }
        result.put("objections", objections);
        return result;
    }

    private Map<String, Object> row(Tender t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", t.getId());
        row.put("tenderNo", t.getTenderNo());
        row.put("title", t.getTitle());
        row.put("tenderType", t.getTenderType());
        row.put("evalMethod", t.getEvalMethod());
        row.put("categoryCode", t.getCategoryCode());
        row.put("categoryName", t.getCategoryName());
        row.put("status", t.getStatus());
        row.put("regDeadline", t.getRegDeadline());
        row.put("quoteDeadline", t.getQuoteDeadline());
        row.put("invitePending", t.getInvitePending());
        row.put("qualifiedCount", t.getQualifiedCount());
        row.put("anomalyFlag", t.getAnomalyFlag());
        row.put("objectionFlag", t.getObjectionFlag());
        row.put("awardSupplierId", t.getAwardSupplierId());
        row.put("awardPrice", t.getAwardPrice());
        row.put("publicityStart", t.getPublicityStart());
        row.put("publicityEnd", t.getPublicityEnd());
        row.put("createDate", t.getCreateDate());
        row.put("supplierCount", supplierDao.selectCount(new LambdaQueryWrapper<TenderSupplier>()
                .eq(TenderSupplier::getTenderId, t.getId())));
        row.put("quoteCount", quoteDao.selectCount(new LambdaQueryWrapper<TenderQuote>()
                .eq(TenderQuote::getTenderId, t.getId())));
        return row;
    }

    // ---------- 立项（FR-4.2-10-1） ----------

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String title = str(payload.get("title"));
        if (!isNotBlank(title) || title.trim().length() < 2) {
            throw new ServiceException(422, "招标名称必填且不少于 2 字");
        }
        String evalMethod = str(payload.get("evalMethod"));
        if (!"LOWEST".equals(evalMethod) && !"SCORE".equals(evalMethod)) {
            throw new ServiceException(422, "评标办法须为 LOWEST（最低价法）或 SCORE（综合评分法）");
        }

        // 行（物料）必填
        List<TenderLine> lines = new ArrayList<>();
        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "招标行（物料）必填");
        }
        int no = 1;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String itemCode = str(m.get("itemCode"));
            if (!isNotBlank(itemCode)) {
                throw new ServiceException(422, "招标行物料编码必填");
            }
            TenderLine l = new TenderLine();
            l.setLineNo(no++);
            l.setItemCode(itemCode.trim());
            l.setItemName(str(m.get("itemName")));
            BigDecimal qty = decimal(m.get("qty"), "数量");
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ServiceException(422, "招标行数量须大于 0");
            }
            l.setQty(qty);
            l.setUnit(str(m.get("unit")));
            l.setReqDate(date(str(m.get("reqDate"))));
            lines.add(l);
        }

        // 权重快照（和须 = 100，design D4）
        Integer wp = intOf(payload.get("weightPrice"));
        Integer wd = intOf(payload.get("weightDelivery"));
        Integer wq = intOf(payload.get("weightQuality"));
        Integer wc = intOf(payload.get("weightCooperation"));
        int p = wp == null ? 40 : wp;
        int d = wd == null ? 25 : wd;
        int q = wq == null ? 25 : wq;
        int c = wc == null ? 10 : wc;
        if (p + d + q + c != 100) {
            throw new ServiceException(422, "四项评分权重之和须为 100，当前 " + (p + d + q + c));
        }

        LocalDateTime regDeadline = datetime(str(payload.get("regDeadline")));
        LocalDateTime quoteDeadline = datetime(str(payload.get("quoteDeadline")));
        if (quoteDeadline == null) {
            throw new ServiceException(422, "报价截止时间必填");
        }
        if (!quoteDeadline.isAfter(LocalDateTime.now())) {
            throw new ServiceException(422, "报价截止时间须晚于当前时间");
        }
        if (regDeadline != null && !regDeadline.isBefore(quoteDeadline)) {
            throw new ServiceException(422, "报名截止时间须早于报价截止时间");
        }

        Tender t = new Tender();
        t.setTenderNo(support.nextTenderNo());
        t.setTitle(title.trim());
        String type = str(payload.get("tenderType"));
        t.setTenderType(isNotBlank(type) ? type.trim().toUpperCase() : "OPEN");
        t.setEvalMethod(evalMethod);
        t.setCategoryCode(str(payload.get("categoryCode")));
        t.setCategoryName(str(payload.get("categoryName")));
        t.setEstAnnualQty(decimal(payload.get("estAnnualQty"), "年度预估量"));
        t.setTechSpec(str(payload.get("techSpec")));
        t.setQualifyReq(str(payload.get("qualifyReq")));
        t.setStatus(TenderStateMachine.DRAFT);
        t.setRegDeadline(regDeadline);
        t.setQuoteDeadline(quoteDeadline);
        t.setInvitePending("0");
        t.setWeightPrice(p);
        t.setWeightDelivery(d);
        t.setWeightQuality(q);
        t.setWeightCooperation(c);
        t.setObjectionFlag("0");
        t.setAnomalyFlag("0");
        t.setQualifiedCount(0);
        try {
            tenderDao.insert(t);
        } catch (DuplicateKeyException ex) {
            throw new ServiceException(409, "招标编号生成冲突，请重试");
        }

        for (TenderLine l : lines) {
            l.setTenderId(t.getId());
            l.setCreateBy(SecurityUtils.getCurrentUserId());
            lineDao.insert(l);
        }

        List<String> supplierIds = strList(payload.get("supplierIds"));
        for (String sid : supplierIds) {
            insertSupplier(t.getId(), sid, "APPLY");
        }

        support.publishHead(t, "PROC.TENDER.CREATED",
                "立项 " + t.getTenderNo() + " 行 " + lines.size() + " 投标方 " + supplierIds.size()
                        + " 截止 " + quoteDeadline);
        log.info("TENDER created {} method={} lines={} suppliers={} deadline={}",
                t.getTenderNo(), evalMethod, lines.size(), supplierIds.size(), quoteDeadline);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tender", t);
        result.put("lineCount", lines.size());
        result.put("supplierCount", supplierIds.size());
        return result;
    }

    // ---------- 报名与资格审查（FR-4.2-10-1 / BR-4.2-45） ----------

    /**
     * 更新立项要素（仅 DRAFT）。招标编号创建后不可修改。
     */
    @Override
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> payload) {
        Tender t = support.requireTender(id);

        // 编码不可改（spec「编码创建后不可修改」→ 422）
        Object no = payload.get("tenderNo");
        if (no != null && isNotBlank(String.valueOf(no))
                && !t.getTenderNo().equals(String.valueOf(no).trim())) {
            throw new ServiceException(422, "招标编号创建后不可修改（当前 " + t.getTenderNo() + "）");
        }
        if (!TenderStateMachine.DRAFT.equals(t.getStatus())) {
            throw new ServiceException(422, "仅立项草稿状态可修改，当前 " + t.getStatus());
        }

        String title = str(payload.get("title"));
        if (isNotBlank(title)) {
            if (title.trim().length() < 2) {
                throw new ServiceException(422, "招标名称不少于 2 字");
            }
            t.setTitle(title.trim());
        }
        if (payload.containsKey("techSpec")) {
            t.setTechSpec(str(payload.get("techSpec")));
        }
        if (payload.containsKey("qualifyReq")) {
            t.setQualifyReq(str(payload.get("qualifyReq")));
        }
        if (payload.containsKey("categoryCode")) {
            t.setCategoryCode(str(payload.get("categoryCode")));
        }
        if (payload.containsKey("categoryName")) {
            t.setCategoryName(str(payload.get("categoryName")));
        }
        if (payload.containsKey("estAnnualQty")) {
            t.setEstAnnualQty(decimal(payload.get("estAnnualQty"), "年度预估量"));
        }
        Object rd = payload.get("regDeadline");
        if (rd != null && isNotBlank(str(rd))) {
            LocalDateTime next = datetime(str(rd));
            if (t.getQuoteDeadline() != null && !next.isBefore(t.getQuoteDeadline())) {
                throw new ServiceException(422, "报名截止时间须早于报价截止时间");
            }
            t.setRegDeadline(next);
        }
        Object qd = payload.get("quoteDeadline");
        if (qd != null && isNotBlank(str(qd))) {
            LocalDateTime next = datetime(str(qd));
            if (!next.isAfter(LocalDateTime.now())) {
                throw new ServiceException(422, "报价截止时间须晚于当前时间");
            }
            t.setQuoteDeadline(next);
        }
        support.persist(t);
        support.publishHead(t, "PROC.TENDER.UPDATED", "立项要素更新");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tender", t);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> start(String id) {
        Tender t = support.requireTender(id);
        if (t.getQuoteDeadline() == null) {
            throw new ServiceException(422, "缺少报价截止时间，无法开始报名");
        }
        support.transition(t, TenderStateMachine.BIDDING, "开始报名与竞价");
        support.publishHead(t, "PROC.TENDER.BIDDING", "报名开始，截止 " + t.getQuoteDeadline());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tender", t);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> qualify(String id, String supplierId, String status, String note) {
        Tender t = support.requireTender(id);
        if (TenderStateMachine.isLocked(t.getStatus())) {
            throw new ServiceException(422, "当前状态 " + t.getStatus() + " 不可再进行资格审查");
        }
        if (!"PASS".equals(status) && !"FAIL".equals(status)) {
            throw new ServiceException(422, "资格审查结论须为 PASS 或 FAIL");
        }
        TenderSupplier ts = supplierDao.selectOne(new LambdaQueryWrapper<TenderSupplier>()
                .eq(TenderSupplier::getTenderId, id)
                .eq(TenderSupplier::getSupplierId, supplierId).last("LIMIT 1"));
        if (ts == null) {
            throw new ServiceException(404, "该供应商不在本招标的投标方清单中");
        }
        ts.setQualifyStatus(status);
        ts.setQualifyNote(note);
        ts.setQualifyBy(SecurityUtils.getCurrentUserId());
        ts.setQualifyDate(LocalDateTime.now());
        ts.setUpdateBy(SecurityUtils.getCurrentUserId());
        supplierDao.updateById(ts);

        long qualified = supplierDao.selectCount(new LambdaQueryWrapper<TenderSupplier>()
                .eq(TenderSupplier::getTenderId, id)
                .eq(TenderSupplier::getQualifyStatus, "PASS"));
        t.setQualifiedCount((int) qualified);
        support.persist(t);
        log.info("TENDER {} qualify supplier={} -> {} qualified={}/{}",
                t.getTenderNo(), supplierId, status, qualified, minQuoteCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("qualifiedCount", qualified);
        result.put("minQuoteCount", minQuoteCount);
        result.put("insufficient", qualified < minQuoteCount);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> addSuppliers(String id, List<String> supplierIds) {
        Tender t = support.requireTender(id);
        if (TenderStateMachine.isLocked(t.getStatus())) {
            throw new ServiceException(422, "当前状态 " + t.getStatus() + " 不可追加投标方");
        }
        if (supplierIds == null || supplierIds.isEmpty()) {
            throw new ServiceException(422, "supplierIds 必填");
        }
        int added = 0;
        for (String sid : supplierIds) {
            if (insertSupplier(id, sid, "INVITE")) {
                added++;
            }
        }
        long qualified = supplierDao.selectCount(new LambdaQueryWrapper<TenderSupplier>()
                .eq(TenderSupplier::getTenderId, id)
                .eq(TenderSupplier::getQualifyStatus, "PASS"));
        t.setQualifiedCount((int) qualified);
        support.persist(t);
        support.publishHead(t, "PROC.TENDER.SUPPLIERS", "追加投标方 " + added + " 家，累计 " + qualified + " 合格");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", added);
        result.put("qualifiedCount", qualified);
        return result;
    }

    /** 返回是否新插入（重复不报错，幂等） */
    private boolean insertSupplier(String tenderId, String supplierId, String source) {
        if (!isNotBlank(supplierId)) {
            throw new ServiceException(422, "供应商 ID 必填");
        }
        MdmSupplier s = mdmSupplierDao.selectById(supplierId);
        if (s == null) {
            throw new ServiceException(404, "供应商不存在：" + supplierId);
        }
        if (supplierDao.selectCount(new LambdaQueryWrapper<TenderSupplier>()
                .eq(TenderSupplier::getTenderId, tenderId)
                .eq(TenderSupplier::getSupplierId, supplierId)) > 0) {
            return false;
        }
        TenderSupplier ts = new TenderSupplier();
        ts.setTenderId(tenderId);
        ts.setSupplierId(supplierId);
        ts.setSupplierName(s.getSupplierName());
        ts.setJoinSource(source);
        ts.setQualifyStatus("PENDING");
        ts.setCreateBy(SecurityUtils.getCurrentUserId());
        supplierDao.insert(ts);
        return true;
    }

    /** BR-4.2-45 入口一：延长报名期（新截止更大 + 原因留痕） */
    @Override
    @Transactional
    public Map<String, Object> postponeReg(String id, String newDeadline, String reason) {
        Tender t = support.requireTender(id);
        if (TenderStateMachine.isLocked(t.getStatus())) {
            throw new ServiceException(422, "当前状态 " + t.getStatus() + " 不可延长报名期");
        }
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "延期原因必填（不少于 2 字）");
        }
        LocalDateTime next = datetime(newDeadline);
        if (next == null) {
            throw new ServiceException(422, "新的报名截止时间必填");
        }
        if (t.getRegDeadline() != null && !next.isAfter(t.getRegDeadline())) {
            throw new ServiceException(422, "新的报名截止时间须晚于当前报名截止 " + t.getRegDeadline());
        }
        t.setRegDeadline(next);
        support.persist(t);
        support.publishHead(t, "PROC.TENDER.REG_POSTPONED",
                "报名期延至 " + next + "，原因：" + reason.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("regDeadline", next);
        return result;
    }

    /** BR-4.2-45 入口二：转邀请招标，升级采购总监审批（INVITE_PENDING=1 待批 / 2 批 / 3 驳） */
    @Override
    @Transactional
    public Map<String, Object> toInvite(String id, String reason) {
        Tender t = support.requireTender(id);
        if (TenderStateMachine.isLocked(t.getStatus())) {
            throw new ServiceException(422, "当前状态 " + t.getStatus() + " 不可变更招标方式");
        }
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "变更原因必填（不少于 2 字）");
        }
        t.setTenderType("INVITE");
        t.setInvitePending("1");
        support.persist(t);
        support.publishHead(t, "PROC.TENDER.TO_INVITE",
                "转邀请招标待采购总监审批，原因：" + reason.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenderType", "INVITE");
        result.put("invitePending", "1");
        return result;
    }

    /**
     * 转邀请招标审批（BR-4.2-45 L2）：通过=2，驳回=3 并回退公开招标。
     */
    @Override
    @Transactional
    public Map<String, Object> inviteApproval(String id, boolean approved, String reason) {
        Tender t = support.requireTender(id);
        if (!"1".equals(t.getInvitePending())) {
            throw new ServiceException(422, "当前无待审批的转邀请申请，invitePending=" + t.getInvitePending());
        }
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "审批意见必填（不少于 2 字）");
        }
        String eventType;
        String diff;
        if (approved) {
            t.setInvitePending("2");
            eventType = "PROC.TENDER.INVITE_APPROVED";
            diff = "采购总监批准转邀请招标，意见：" + reason.trim();
        } else {
            t.setInvitePending("3");
            t.setTenderType("OPEN");
            eventType = "PROC.TENDER.INVITE_REJECTED";
            diff = "采购总监驳回，回退公开招标，意见：" + reason.trim();
        }
        // 必须先 persist（verNo+1）再发事件，与 toInvite 等方法保持同一顺序，
        // 否则事件版本 verNo+1 与既往事件撞幂等键（C-0-06 → 409）
        support.persist(t);
        support.publishHead(t, eventType, diff);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invitePending", t.getInvitePending());
        result.put("tenderType", t.getTenderType());
        return result;
    }

    // ---------- 多轮报价与截止锁价（BR-4.2-06，design D2） ----------

    @Override
    @Transactional
    public Map<String, Object> saveQuote(String id, Map<String, Object> payload) {
        Tender t = support.requireTender(id);
        // 3.4 锁价后阻断录价（含未开始报名的草稿态）
        if (!TenderStateMachine.BIDDING.equals(t.getStatus())) {
            throw new ServiceException(422, "当前状态 " + t.getStatus()
                    + " 不可录入报价（最终报价已锁定，BR-4.2-06）");
        }
        String supplierId = str(payload.get("supplierId"));
        if (!isNotBlank(supplierId)) {
            throw new ServiceException(422, "supplierId 必填");
        }
        long inList = supplierDao.selectCount(new LambdaQueryWrapper<TenderSupplier>()
                .eq(TenderSupplier::getTenderId, id)
                .eq(TenderSupplier::getSupplierId, supplierId));
        if (inList == 0) {
            throw new ServiceException(404, "该供应商不在本招标的投标方清单中");
        }
        BigDecimal price = decimal(payload.get("unitPrice"), "报价单价");
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException(422, "报价单价须大于 0");
        }

        // 轮次：自增或校验指定值（历史轮次只读，不可覆盖已存在的轮次）
        long rounds = quoteDao.selectCount(new LambdaQueryWrapper<TenderQuote>()
                .eq(TenderQuote::getTenderId, id)
                .eq(TenderQuote::getSupplierId, supplierId));
        int nextRound = (int) rounds + 1;
        Integer round = intOf(payload.get("roundNo"));
        if (round == null) {
            round = nextRound;
        } else if (round != nextRound) {
            throw new ServiceException(422, "轮次 " + round + " 已存在或非下一可用轮次（历史轮次只读，"
                    + "当前可录入轮次为 " + nextRound + "）");
        }

        // BR-4.2-06 只降不升
        if (rounds > 0) {
            TenderQuote prev = quoteDao.selectOne(new LambdaQueryWrapper<TenderQuote>()
                    .eq(TenderQuote::getTenderId, id)
                    .eq(TenderQuote::getSupplierId, supplierId)
                    .eq(TenderQuote::getRoundNo, round - 1)
                    .last("LIMIT 1"));
            if (prev != null && price.compareTo(prev.getUnitPrice()) > 0) {
                throw new ServiceException(422, "本轮报价 " + price + " 高于该投标方上轮报价 "
                        + prev.getUnitPrice() + "（BR-4.2-06 只降不升）");
            }
        }

        TenderQuote q = new TenderQuote();
        q.setTenderId(id);
        q.setSupplierId(supplierId);
        q.setRoundNo(round);
        q.setUnitPrice(price);
        q.setLeadTimeDays(intOf(payload.get("leadTimeDays")));
        q.setMoq(decimal(payload.get("moq"), "最小起订量"));
        q.setPaymentTerms(str(payload.get("paymentTerms")));
        q.setQuoteValidDate(date(str(payload.get("quoteValidDate"))));
        q.setQuoteTime(LocalDateTime.now());
        q.setOperator(SecurityUtils.getCurrentUserId());
        q.setRemark(str(payload.get("remark")));
        q.setCreateBy(SecurityUtils.getCurrentUserId());
        try {
            quoteDao.insert(q);
        } catch (DuplicateKeyException ex) {
            throw new ServiceException(409, "该轮次报价已存在，历史轮次只读");
        }
        log.info("TENDER {} quote supplier={} round={} price={}",
                t.getTenderNo(), supplierId, round, price);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roundNo", round);
        result.put("unitPrice", price);
        result.put("totalRounds", round);
        return result;
    }

    /** 历史轮次只读：一律拒绝修改（BR-4.2-06 留痕要求） */
    @Override
    @Transactional
    public Map<String, Object> updateQuote(String quoteId, Map<String, Object> payload) {
        TenderQuote q = quoteDao.selectById(quoteId);
        if (q == null) {
            throw new ServiceException(404, "报价记录不存在");
        }
        throw new ServiceException(422, "历史轮次报价只读，不可修改（第 " + q.getRoundNo()
                + " 轮，BR-4.2-06）；请录入新轮次");
    }

    /** 延长报价截止（仅锁价前，新截止须更晚 + 原因留痕） */
    @Override
    @Transactional
    public Map<String, Object> postpone(String id, String newDeadline, String reason) {
        Tender t = support.requireTender(id);
        if (!TenderStateMachine.BIDDING.equals(t.getStatus())) {
            throw new ServiceException(422, "当前状态 " + t.getStatus() + " 不可延长报价截止（已锁价不可延期）");
        }
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "延期原因必填（不少于 2 字）");
        }
        LocalDateTime next = datetime(newDeadline);
        if (next == null) {
            throw new ServiceException(422, "新的报价截止时间必填");
        }
        if (!next.isAfter(LocalDateTime.now())) {
            throw new ServiceException(422, "新的报价截止时间须晚于当前时间");
        }
        if (t.getQuoteDeadline() != null && !next.isAfter(t.getQuoteDeadline())) {
            throw new ServiceException(422, "新的报价截止时间须晚于当前报价截止 " + t.getQuoteDeadline());
        }
        t.setQuoteDeadline(next);
        support.persist(t);
        support.publishHead(t, "PROC.TENDER.DEADLINE_POSTPONED",
                "报价截止延至 " + next + "，原因：" + reason.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quoteDeadline", next);
        return result;
    }

    /**
     * 开标（LOCKED → EVALUATING）。先执行 sweep 确保过期即锁，再按 BR-4.2-45 卡控。
     */
    @Override
    @Transactional
    public Map<String, Object> open(String id) {
        sweep();
        Tender t = support.requireTender(id);

        if (!TenderStateMachine.LOCKED.equals(t.getStatus())) {
            throw new ServiceException(422, "仅已锁价状态可开标，当前 " + t.getStatus()
                    + (TenderStateMachine.BIDDING.equals(t.getStatus())
                    ? "（报价截止 " + t.getQuoteDeadline() + " 尚未到达）" : ""));
        }
        // 转邀请招标未获采购总监批准
        if ("1".equals(t.getInvitePending())) {
            throw new ServiceException(422, "已申请转邀请招标，尚待采购总监审批（BR-4.2-45，L2）");
        }
        // 综合评分法必须在开标前指定评委：否则评标开始后 setJudges 被锁、evaluate 又因无评委失败，会形成死锁
        if ("SCORE".equals(t.getEvalMethod())) {
            long judges = judgeDao.selectCount(new LambdaQueryWrapper<TenderJudge>()
                    .eq(TenderJudge::getTenderId, id));
            if (judges == 0) {
                throw new ServiceException(422, "综合评分法须先指定评标委员（FR-4.2-10-2），"
                        + "请在开标前完成评委指定");
            }
        }
        // BR-4.2-45：合格投标方不足
        long qualified = supplierDao.selectCount(new LambdaQueryWrapper<TenderSupplier>()
                .eq(TenderSupplier::getTenderId, id)
                .eq(TenderSupplier::getQualifyStatus, "PASS"));
        if (qualified < minQuoteCount) {
            throw new ServiceException(422, "合格投标方 " + qualified + " 家 < 最少 "
                    + minQuoteCount + " 家（BR-4.2-45），已阻断开标；"
                    + "请「延长报名期」或「转邀请招标」（须采购总监审批）");
        }

        support.transition(t, TenderStateMachine.EVALUATING, "开标评标（合格 " + qualified + " 家）");
        support.publishHead(t, "PROC.TENDER.OPENED", "开标，合格投标方 " + qualified + " 家");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tender", t);
        result.put("qualifiedCount", qualified);
        return result;
    }

    // ---------- 评标委员会与评分（FR-4.2-10-2，design D4/D5） ----------

    @Override
    @Transactional
    public Map<String, Object> setJudges(String id, List<String> judgeUserIds) {
        Tender t = support.requireTender(id);
        if (TenderStateMachine.EVALUATING.equals(t.getStatus())
                || TenderStateMachine.PENDING_AWARD.equals(t.getStatus())) {
            throw new ServiceException(422, "评标已开始，不可更换评委，当前 " + t.getStatus());
        }
        if (TenderStateMachine.isTerminal(t.getStatus())) {
            throw new ServiceException(422, "招标已结束，不可更换评委");
        }
        if (judgeUserIds == null || judgeUserIds.isEmpty()) {
            throw new ServiceException(422, "评委名单必填（至少 1 人）");
        }
        // 替换式：清空后重建
        judgeDao.delete(new LambdaQueryWrapper<TenderJudge>().eq(TenderJudge::getTenderId, id));
        List<Map<String, Object>> saved = new ArrayList<>();
        for (String uid : judgeUserIds) {
            if (!isNotBlank(uid)) {
                continue;
            }
            SysUser u = sysUserDao.selectById(uid.trim());
            if (u == null) {
                throw new ServiceException(404, "评委用户不存在：" + uid);
            }
            TenderJudge j = new TenderJudge();
            j.setTenderId(id);
            j.setJudgeUserId(uid.trim());
            j.setJudgeName(u.getNickName() != null ? u.getNickName() : u.getUsername());
            j.setCreateBy(SecurityUtils.getCurrentUserId());
            judgeDao.insert(j);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("judgeUserId", j.getJudgeUserId());
            row.put("judgeName", j.getJudgeName());
            saved.add(row);
        }
        if (saved.isEmpty()) {
            throw new ServiceException(422, "评委名单必填（至少 1 人）");
        }
        // 头字段未变，但必须先 persist 让 verNo+1，否则事件版本与既往操作撞幂等键（C-0-06 → 409）
        support.persist(t);
        support.publishHead(t, "PROC.TENDER.JUDGES_SET", "评委 " + saved.size() + " 人");
        log.info("TENDER {} judges={}", t.getTenderNo(), saved.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("judges", saved);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> saveScore(String id, Map<String, Object> payload) {
        Tender t = support.requireTender(id);
        if (!TenderStateMachine.EVALUATING.equals(t.getStatus())) {
            throw new ServiceException(422, "仅评标中状态可评分，当前 " + t.getStatus());
        }
        if ("1".equals(t.getAnomalyFlag())) {
            throw new ServiceException(422, "评标已因异常标记冻结（偏差 D6），解除后方可提交");
        }
        String uid = SecurityUtils.getCurrentUserId();
        long judgeCount = judgeDao.selectCount(new LambdaQueryWrapper<TenderJudge>()
                .eq(TenderJudge::getTenderId, id)
                .eq(TenderJudge::getJudgeUserId, uid));
        if (judgeCount == 0) {
            throw new ServiceException(403, "您不是本招标的评标委员（ROLE_BID_JUDGE），无评分权限");
        }
        String supplierId = str(payload.get("supplierId"));
        if (!isNotBlank(supplierId)) {
            throw new ServiceException(422, "supplierId 必填");
        }
        long sup = supplierDao.selectCount(new LambdaQueryWrapper<TenderSupplier>()
                .eq(TenderSupplier::getTenderId, id)
                .eq(TenderSupplier::getSupplierId, supplierId)
                .eq(TenderSupplier::getQualifyStatus, "PASS"));
        if (sup == 0) {
            throw new ServiceException(404, "该投标方不在合格清单中，不可评分");
        }

        Integer p = intOf(payload.get("scorePrice"));
        Integer d = intOf(payload.get("scoreDelivery"));
        Integer q = intOf(payload.get("scoreQuality"));
        Integer c = intOf(payload.get("scoreCooperation"));
        for (Object v : new Object[]{p, d, q, c}) {
            if (v == null) {
                throw new ServiceException(422, "四个维度评分必填（价格/交付/质量/配合度）");
            }
            int n = (Integer) v;
            if (n < 0 || n > 100) {
                throw new ServiceException(422, "各维度评分须在 0-100 之间");
            }
        }

        TenderScore exist = scoreDao.selectOne(new LambdaQueryWrapper<TenderScore>()
                .eq(TenderScore::getTenderId, id)
                .eq(TenderScore::getSupplierId, supplierId)
                .eq(TenderScore::getJudgeUserId, uid)
                .last("LIMIT 1"));
        // BR-4.2-47：提交后不可直接修改
        if (exist != null && "SUBMITTED".equals(exist.getStatus())
                && !"1".equals(exist.getAmendApproved())) {
            throw new ServiceException(422, "评分已提交，不可直接修改（BR-4.2-47）；"
                    + "如需更正须走合规审批");
        }

        BigDecimal weighted = weighted(p, d, q, c,
                t.getWeightPrice(), t.getWeightDelivery(),
                t.getWeightQuality(), t.getWeightCooperation());
        String status = isNotBlank(str(payload.get("status")))
                ? str(payload.get("status")).trim().toUpperCase() : "SUBMITTED";

        TenderScore s;
        if (exist == null) {
            s = new TenderScore();
            s.setTenderId(id);
            s.setSupplierId(supplierId);
            s.setJudgeUserId(uid);
            s.setCreateBy(uid);
        } else {
            s = exist;
            s.setUpdateBy(uid);
            // 经合规审批后的修改解除锁定标记
            if ("1".equals(s.getAmendApproved()) && "SUBMITTED".equals(status)) {
                s.setAmendApproved("0");
                s.setAmendBy(null);
                s.setAmendDate(null);
                s.setAmendNote(null);
            }
        }
        s.setScorePrice(p);
        s.setScoreDelivery(d);
        s.setScoreQuality(q);
        s.setScoreCooperation(c);
        s.setWeightedScore(weighted);
        s.setStatus(status);
        if ("SUBMITTED".equals(status)) {
            s.setSubmitTime(LocalDateTime.now());
        }
        if (exist == null) {
            scoreDao.insert(s);
        } else {
            scoreDao.updateById(s);
        }
        log.info("TENDER {} score judge={} supplier={} weighted={} status={}",
                t.getTenderNo(), uid, supplierId, weighted, status);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("weightedScore", weighted);
        result.put("status", s.getStatus());
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> amendScore(String id, String scoreId, Map<String, Object> payload) {
        Tender t = support.requireTender(id);
        TenderScore s = scoreDao.selectById(scoreId);
        if (s == null || !id.equals(s.getTenderId())) {
            throw new ServiceException(404, "评分记录不存在");
        }
        String note = str(payload.get("note"));
        if (!isNotBlank(note) || note.trim().length() < 2) {
            throw new ServiceException(422, "合规审批意见必填（不少于 2 字）");
        }
        Integer p = intOf(payload.get("scorePrice"));
        Integer d = intOf(payload.get("scoreDelivery"));
        Integer q = intOf(payload.get("scoreQuality"));
        Integer c = intOf(payload.get("scoreCooperation"));
        for (Object v : new Object[]{p, d, q, c}) {
            if (v == null) {
                throw new ServiceException(422, "四个维度评分必填");
            }
        }
        // 留痕：改前 → 改后（BR-4.2-47 L5）
        String before = String.format("价%d/交%d/质%d/合%d → ", nvl(s.getScorePrice()),
                nvl(s.getScoreDelivery()), nvl(s.getScoreQuality()), nvl(s.getScoreCooperation()));
        s.setScorePrice(p);
        s.setScoreDelivery(d);
        s.setScoreQuality(q);
        s.setScoreCooperation(c);
        s.setWeightedScore(weighted(p, d, q, c,
                t.getWeightPrice(), t.getWeightDelivery(),
                t.getWeightQuality(), t.getWeightCooperation()));
        s.setStatus("SUBMITTED");
        s.setSubmitTime(LocalDateTime.now());
        s.setAmendApproved("1");
        s.setAmendBy(SecurityUtils.getCurrentUserId());
        s.setAmendDate(LocalDateTime.now());
        s.setAmendNote(before + String.format("价%d/交%d/质%d/合%d；合规意见：%s",
                p, d, q, c, note.trim()));
        s.setUpdateBy(SecurityUtils.getCurrentUserId());
        scoreDao.updateById(s);
        // 先 persist 使 verNo+1 再发事件，避免与既往头事件撞幂等键（C-0-06 → 409）
        support.persist(t);
        support.publishHead(t, "PROC.TENDER.SCORE_AMENDED",
                "评分合规修改 " + s.getAmendNote());
        log.info("TENDER {} score amended id={} by={}", t.getTenderNo(), scoreId,
                s.getAmendBy());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("weightedScore", s.getWeightedScore());
        result.put("amendNote", s.getAmendNote());
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> evaluate(String id) {
        sweep();
        Tender t = support.requireTender(id);
        if (!TenderStateMachine.EVALUATING.equals(t.getStatus())) {
            throw new ServiceException(422, "仅评标中状态可汇总定标，当前 " + t.getStatus());
        }
        if ("1".equals(t.getAnomalyFlag())) {
            throw new ServiceException(422, "评标已因异常标记冻结（偏差 D6），解除后方可定标");
        }

        List<TenderSupplier> qualified = supplierDao.selectList(
                new LambdaQueryWrapper<TenderSupplier>()
                        .eq(TenderSupplier::getTenderId, id)
                        .eq(TenderSupplier::getQualifyStatus, "PASS"));
        if (qualified.isEmpty()) {
            throw new ServiceException(422, "无合格投标方，不可定标");
        }

        String awardSid;
        BigDecimal awardPrice = null;
        BigDecimal awardScore = null;
        Map<String, BigDecimal> summary = new LinkedHashMap<>();

        if ("LOWEST".equals(t.getEvalMethod())) {
            // 4.6 最低价法：取各投标方最终轮有效报价的最低者
            BigDecimal best = null;
            String bestSid = null;
            for (TenderSupplier ts : qualified) {
                TenderQuote last = lastQuote(id, ts.getSupplierId());
                if (last == null) {
                    continue;
                }
                if (best == null || last.getUnitPrice().compareTo(best) < 0) {
                    best = last.getUnitPrice();
                    bestSid = ts.getSupplierId();
                }
            }
            if (bestSid == null) {
                throw new ServiceException(422, "无有效报价，最低价法不可定标");
            }
            awardSid = bestSid;
            awardPrice = best;
        } else {
            // 4.4 综合评分法：全部评委 × 全部合格投标方须已提交，取算术平均
            List<TenderJudge> judges = judgeDao.selectList(new LambdaQueryWrapper<TenderJudge>()
                    .eq(TenderJudge::getTenderId, id));
            if (judges.isEmpty()) {
                throw new ServiceException(422, "未指定评标委员，综合评分法不可定标");
            }
            List<TenderScore> scores = scoreDao.selectList(new LambdaQueryWrapper<TenderScore>()
                    .eq(TenderScore::getTenderId, id)
                    .eq(TenderScore::getStatus, "SUBMITTED"));
            StringBuilder missing = new StringBuilder();
            for (TenderSupplier ts : qualified) {
                List<TenderScore> forSup = scores.stream()
                        .filter(x -> ts.getSupplierId().equals(x.getSupplierId()))
                        .toList();
                if (forSup.size() < judges.size()) {
                    missing.append(ts.getSupplierName()).append(" ")
                            .append(forSup.size()).append("/").append(judges.size()).append("；");
                } else {
                    BigDecimal avg = forSup.stream()
                            .map(TenderScore::getWeightedScore)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(forSup.size()), 4,
                                    java.math.RoundingMode.HALF_UP);
                    summary.put(ts.getSupplierId(), avg);
                }
            }
            if (missing.length() > 0) {
                throw new ServiceException(422, "评分未提交齐全（投标方 已提交/评委数）：" + missing);
            }
            awardSid = summary.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            awardScore = summary.get(awardSid);
            // 中标价 = 该投标方最终轮报价
            TenderQuote last = lastQuote(id, awardSid);
            awardPrice = last == null ? null : last.getUnitPrice();
        }

        t.setAwardSupplierId(awardSid);
        t.setAwardPrice(awardPrice);
        t.setAwardScore(awardScore);
        // design D2：评标汇总即落中标子表默认行（单家 100%，采购员可在定标审批前经 saveWinners 调整）
        awardDao.delete(new LambdaQueryWrapper<TenderAward>()
                .eq(TenderAward::getTenderId, id));
        TenderAward def = new TenderAward();
        def.setTenderId(id);
        def.setLineNo(1);
        def.setSupplierId(awardSid);
        def.setSupplierName(supplierName(awardSid));
        def.setAwardPrice(awardPrice == null ? BigDecimal.ZERO : awardPrice);
        def.setSharePct(BigDecimal.valueOf(100));
        awardDao.insert(def);
        support.transition(t, TenderStateMachine.PENDING_AWARD, "评标汇总，拟中标 "
                + supplierName(awardSid));
        support.persist(t);
        support.publishHead(t, "PROC.TENDER.EVALUATED",
                "拟中标 " + supplierName(awardSid) + " 价 " + awardPrice
                        + (awardScore != null ? " 分 " + awardScore : ""));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("awardSupplierId", awardSid);
        result.put("awardSupplierName", supplierName(awardSid));
        result.put("awardPrice", awardPrice);
        result.put("awardScore", awardScore);
        result.put("summary", summary);
        // 定标录入预填候选（spec tender-bidding-management「评标结果作为定标默认建议」）
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (TenderSupplier ts : qualified) {
            TenderQuote last = lastQuote(id, ts.getSupplierId());
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("supplierId", ts.getSupplierId());
            c.put("supplierName", ts.getSupplierName());
            c.put("finalPrice", last == null ? null : last.getUnitPrice());
            c.put("score", summary.get(ts.getSupplierId()));
            c.put("isDefault", ts.getSupplierId().equals(awardSid));
            candidates.add(c);
        }
        result.put("candidates", candidates);
        List<Map<String, Object>> defWinners = new ArrayList<>();
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("supplierId", awardSid);
        w.put("supplierName", supplierName(awardSid));
        w.put("awardPrice", awardPrice);
        w.put("sharePct", BigDecimal.valueOf(100));
        defWinners.add(w);
        result.put("awardWinners", defWinners);
        return result;
    }

    /**
     * 保存中标人与份额（spec tender-bidding-management「多中标人定标录入」，design D2）。
     * 校验：≥1 家、均为合格投标方、单价 = 该家最终轮有效报价、无重复、Σ份额 = 100（两位小数）。
     * 头快照同步为份额最大中标人；PENDING_AWARD（评标汇总后、总监审批前）可反复调整。
     */
    @Override
    @Transactional
    public Map<String, Object> saveWinners(String id, List<Map<String, Object>> winners) {
        Tender t = support.requireTender(id);
        if (!TenderStateMachine.PENDING_AWARD.equals(t.getStatus())) {
            throw new ServiceException(422, "仅待定标审批状态可录入中标人，当前 " + t.getStatus());
        }
        if (winners == null || winners.isEmpty()) {
            throw new ServiceException(422, "中标人至少 1 家");
        }
        List<TenderSupplier> qualified = supplierDao.selectList(
                new LambdaQueryWrapper<TenderSupplier>()
                        .eq(TenderSupplier::getTenderId, id)
                        .eq(TenderSupplier::getQualifyStatus, "PASS"));
        Map<String, TenderSupplier> qMap = new LinkedHashMap<>();
        qualified.forEach(x -> qMap.put(x.getSupplierId(), x));

        List<TenderAward> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> w : winners) {
            String sid = str(w.get("supplierId"));
            if (!isNotBlank(sid) || !qMap.containsKey(sid.trim())) {
                throw new ServiceException(422, "中标人须为该招标的合格投标方"
                        + (isNotBlank(sid) ? "：" + sid : ""));
            }
            sid = sid.trim();
            if (!seen.add(sid)) {
                throw new ServiceException(422, "同一中标人不可重复录入：" + qMap.get(sid).getSupplierName());
            }
            TenderQuote last = lastQuote(id, sid);
            if (last == null) {
                throw new ServiceException(422, qMap.get(sid).getSupplierName()
                        + " 无最终轮有效报价，不可中标");
            }
            BigDecimal price = toDecimal(w.get("awardPrice"));
            if (price == null || price.compareTo(last.getUnitPrice()) != 0) {
                throw new ServiceException(422, qMap.get(sid).getSupplierName()
                        + " 中标单价须等于其最终轮有效报价 " + last.getUnitPrice());
            }
            BigDecimal share = toDecimal(w.get("sharePct"));
            if (share == null || share.signum() <= 0) {
                throw new ServiceException(422, qMap.get(sid).getSupplierName() + " 份额须大于 0");
            }
            share = share.setScale(2, java.math.RoundingMode.HALF_UP);
            sum = sum.add(share);
            TenderAward row = new TenderAward();
            row.setTenderId(id);
            row.setLineNo(rows.size() + 1);
            row.setSupplierId(sid);
            row.setSupplierName(qMap.get(sid).getSupplierName());
            row.setAwardPrice(price);
            row.setSharePct(share);
            rows.add(row);
        }
        if (sum.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new ServiceException(422, "份额合计须等于 100%，当前 " + sum.stripTrailingZeros().toPlainString() + "%");
        }

        // 替换子表（原子：先删后插，同一事务）
        awardDao.delete(new LambdaQueryWrapper<TenderAward>().eq(TenderAward::getTenderId, id));
        for (TenderAward row : rows) {
            awardDao.insert(row);
        }

        // 头快照 = 份额最大中标人（并列取先录者）
        TenderAward main = rows.get(0);
        for (TenderAward r : rows) {
            if (r.getSharePct().compareTo(main.getSharePct()) > 0) {
                main = r;
            }
        }
        t.setAwardSupplierId(main.getSupplierId());
        t.setAwardPrice(main.getAwardPrice());
        if ("SCORE".equals(t.getEvalMethod())) {
            t.setAwardScore(avgSubmittedScore(id, main.getSupplierId()));
        } else {
            t.setAwardScore(null);
        }
        // 先 persist（verNo+1）再发头事件（C-0-06 幂等键铁律）
        support.persist(t);
        support.publishHead(t, "PROC.TENDER.AWARD_WINNERS",
                "录入中标 " + rows.size() + " 家，份额合计 100%；主中标 "
                        + main.getSupplierName() + " " + main.getSharePct() + "%");
        log.info("TENDER {} winners saved n={} main={} share={}", t.getTenderNo(),
                rows.size(), main.getSupplierName(), main.getSharePct());

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> saved = new ArrayList<>();
        for (TenderAward r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supplierId", r.getSupplierId());
            m.put("supplierName", r.getSupplierName());
            m.put("awardPrice", r.getAwardPrice());
            m.put("sharePct", r.getSharePct());
            saved.add(m);
        }
        result.put("winners", saved);
        result.put("awardSupplierId", t.getAwardSupplierId());
        result.put("awardPrice", t.getAwardPrice());
        result.put("awardScore", t.getAwardScore());
        return result;
    }

    /** 综合评分法：该投标方已提交评分的算术平均（与 evaluate 汇总同口径） */
    private BigDecimal avgSubmittedScore(String tenderId, String supplierId) {
        List<TenderScore> scores = scoreDao.selectList(new LambdaQueryWrapper<TenderScore>()
                .eq(TenderScore::getTenderId, tenderId)
                .eq(TenderScore::getSupplierId, supplierId)
                .eq(TenderScore::getStatus, "SUBMITTED"));
        if (scores.isEmpty()) {
            return null;
        }
        return scores.stream().map(TenderScore::getWeightedScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(scores.size()), 4, java.math.RoundingMode.HALF_UP);
    }

    /** 载荷数字解析（接受 Number / 数字字符串），null 或非法返回 null */
    private BigDecimal toDecimal(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    @Transactional
    public Map<String, Object> setAnomaly(String id, boolean anomaly, String note) {
        Tender t = support.requireTender(id);
        if (!isNotBlank(note) || note.trim().length() < 2) {
            throw new ServiceException(422, "异常说明必填（不少于 2 字）");
        }
        if (!anomaly && !"1".equals(t.getAnomalyFlag())) {
            throw new ServiceException(422, "当前无异常标记，无需解除");
        }
        t.setAnomalyFlag(anomaly ? "1" : "0");
        t.setAnomalyNote(note.trim());
        support.persist(t);
        support.publishHead(t, anomaly ? "PROC.TENDER.ANOMALY" : "PROC.TENDER.ANOMALY_CLEARED",
                (anomaly ? "标记围标/串标异常并冻结评标：" : "解除异常，恢复评标：") + note.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("anomalyFlag", t.getAnomalyFlag());
        result.put("frozen", "1".equals(t.getAnomalyFlag()));
        return result;
    }

    /** 该投标方最终轮报价（无则 null） */
    private TenderQuote lastQuote(String tenderId, String supplierId) {
        List<TenderQuote> list = quoteDao.selectList(new LambdaQueryWrapper<TenderQuote>()
                .eq(TenderQuote::getTenderId, tenderId)
                .eq(TenderQuote::getSupplierId, supplierId)
                .orderByDesc(TenderQuote::getRoundNo)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 四维加权得分 = Σ(维度分 × 权重) / 100，保留 4 位 */
    private BigDecimal weighted(int p, int d, int q, int c,
                                Integer wp, Integer wd, Integer wq, Integer wc) {
        int a = wp == null ? 40 : wp;
        int b = wd == null ? 25 : wd;
        int e = wq == null ? 25 : wq;
        int f = wc == null ? 10 : wc;
        int sum = p * a + d * b + q * e + c * f;
        return BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
    }

    private int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    /** 评委个人评标任务：只含本人数据（BR-4.2-46 行级权限精神，design D5） */
    @Override
    public Map<String, Object> myTasks(String id) {
        sweep();
        Tender t = support.requireTender(id);
        String uid = SecurityUtils.getCurrentUserId();
        long isJudge = judgeDao.selectCount(new LambdaQueryWrapper<TenderJudge>()
                .eq(TenderJudge::getTenderId, id)
                .eq(TenderJudge::getJudgeUserId, uid));
        if (isJudge == 0) {
            throw new ServiceException(403, "您不是本招标的评标委员（ROLE_BID_JUDGE）");
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (TenderSupplier ts : supplierDao.selectList(new LambdaQueryWrapper<TenderSupplier>()
                .eq(TenderSupplier::getTenderId, id)
                .eq(TenderSupplier::getQualifyStatus, "PASS")
                .orderByAsc(TenderSupplier::getCreateDate))) {
            TenderScore s = scoreDao.selectOne(new LambdaQueryWrapper<TenderScore>()
                    .eq(TenderScore::getTenderId, id)
                    .eq(TenderScore::getSupplierId, ts.getSupplierId())
                    .eq(TenderScore::getJudgeUserId, uid)
                    .last("LIMIT 1"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("supplierId", ts.getSupplierId());
            row.put("supplierName", supplierName(ts.getSupplierId()));
            row.put("scored", s != null && "SUBMITTED".equals(s.getStatus()));
            row.put("scoreId", s == null ? null : s.getId());
            row.put("scorePrice", s == null ? null : s.getScorePrice());
            row.put("scoreDelivery", s == null ? null : s.getScoreDelivery());
            row.put("scoreQuality", s == null ? null : s.getScoreQuality());
            row.put("scoreCooperation", s == null ? null : s.getScoreCooperation());
            row.put("weightedScore", s == null ? null : s.getWeightedScore());
            row.put("submitTime", s == null ? null : s.getSubmitTime());
            tasks.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenderNo", t.getTenderNo());
        result.put("title", t.getTitle());
        result.put("status", t.getStatus());
        result.put("anomalyFrozen", "1".equals(t.getAnomalyFlag()));
        result.put("weightPrice", t.getWeightPrice());
        result.put("weightDelivery", t.getWeightDelivery());
        result.put("weightQuality", t.getWeightQuality());
        result.put("weightCooperation", t.getWeightCooperation());
        result.put("tasks", tasks);
        return result;
    }

    // ---------- 定标审批（FR-4.2-10-3，design D6 单节点） ----------

    @Override
    public List<Map<String, Object>> approvalTodo() {
        sweep();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tender t : tenderDao.selectList(new LambdaQueryWrapper<Tender>()
                .eq(Tender::getStatus, TenderStateMachine.PENDING_AWARD)
                .orderByDesc(Tender::getCreateDate))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenderId", t.getId());
            row.put("tenderNo", t.getTenderNo());
            row.put("title", t.getTitle());
            row.put("amount", t.getAwardPrice());
            row.put("awardSupplierName", supplierName(t.getAwardSupplierId()));
            row.put("awardScore", t.getAwardScore());
            row.put("createDate", t.getCreateDate());
            // 路由链：招标定标为固定单节点（design D6，不复用 2.1.4 分级判级）
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("nodeNo", 1);
            node.put("role", "DIRECTOR");
            node.put("roleName", "采购总监");
            node.put("status", "ACTIVE");
            node.put("taskId", t.getId());
            List<Map<String, Object>> chain = new ArrayList<>();
            chain.add(node);
            row.put("chain", chain);
            row.put("route", "采购总监");
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> approveAward(String id, boolean approved, String note) {
        sweep();
        Tender t = support.requireTender(id);
        if (!TenderStateMachine.PENDING_AWARD.equals(t.getStatus())) {
            throw new ServiceException(422, "仅待定标审批状态可审批，当前 " + t.getStatus());
        }
        if (!isNotBlank(note) || note.trim().length() < 2) {
            throw new ServiceException(422, "审批意见必填（不少于 2 字）");
        }
        // 防御：审批前子表必须完整且份额和 = 100（spec「多中标人定标录入」）
        List<TenderAward> winners = awardDao.selectList(new LambdaQueryWrapper<TenderAward>()
                .eq(TenderAward::getTenderId, id));
        if (winners.isEmpty()) {
            throw new ServiceException(422, "无中标记录，先完成定标录入");
        }
        BigDecimal winSum = winners.stream().map(TenderAward::getSharePct)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (winSum.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new ServiceException(422, "中标份额合计须等于 100%，当前 "
                    + winSum.stripTrailingZeros().toPlainString() + "%");
        }
        t.setAwardApprovalStatus(approved ? "APPROVED" : "REJECTED");
        t.setAwardApprovedBy(SecurityUtils.getCurrentUserId());
        t.setAwardApprovedDate(LocalDateTime.now());
        t.setAwardApprovalNote(note.trim());
        if (approved) {
            LocalDateTime start = LocalDateTime.now();
            LocalDateTime end = start.plusDays(publicityDays);
            t.setPublicityStart(start);
            t.setPublicityEnd(end);
            t.setObjectionFlag("0");
            support.transition(t, TenderStateMachine.AWAITING_PUBLICITY,
                    "采购总监批准定标，公示至 " + end);
            support.persist(t);
            support.publishHead(t, "PROC.TENDER.AWARD_APPROVED",
                    "定标审批通过，公示 " + publicityDays + " 天至 " + end + "；意见：" + note.trim());
        } else {
            support.transition(t, TenderStateMachine.EVALUATING,
                    "采购总监驳回定标，回评标");
            support.persist(t);
            support.publishHead(t, "PROC.TENDER.AWARD_REJECTED",
                    "定标审批驳回，回评标；意见：" + note.trim());
        }
        log.info("TENDER {} award approval {} by {} note={}",
                t.getTenderNo(), approved ? "APPROVED" : "REJECTED",
                t.getAwardApprovedBy(), note.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", t.getStatus());
        result.put("awardApprovalStatus", t.getAwardApprovalStatus());
        result.put("publicityStart", t.getPublicityStart());
        result.put("publicityEnd", t.getPublicityEnd());
        result.put("publicityDays", publicityDays);
        return result;
    }

    // ---------- 公示异议（BR-4.2-48） ----------

    @Override
    @Transactional
    public Map<String, Object> raiseObjection(String id, String content, boolean valid) {
        Tender t = support.requireTender(id);
        if (!TenderStateMachine.AWAITING_PUBLICITY.equals(t.getStatus())) {
            throw new ServiceException(422, "仅公示中状态可登记异议，当前 " + t.getStatus());
        }
        if (!isNotBlank(content) || content.trim().length() < 2) {
            throw new ServiceException(422, "异议内容必填（不少于 2 字）");
        }
        TenderObjection o = new TenderObjection();
        o.setTenderId(id);
        o.setObjectionNo(nextObjectionNo());
        o.setValidFlag(valid ? "1" : "0");
        o.setObjectioner(str(objectioner(content, null)));
        o.setContent(content.trim());
        o.setSubmitDate(LocalDateTime.now());
        o.setReviewStatus(valid ? "PENDING" : "NONE");
        o.setCreateBy(SecurityUtils.getCurrentUserId());
        try {
            objectionDao.insert(o);
        } catch (DuplicateKeyException ex) {
            throw new ServiceException(409, "异议编号生成冲突，请重试");
        }
        if (valid) {
            t.setObjectionFlag("1");
            support.transition(t, TenderStateMachine.OBJECTION, "公示期收到有效异议，冻结定标");
            support.persist(t);
        }
        support.publishHead(t, valid ? "PROC.TENDER.OBJECTION" : "PROC.TENDER.OBJECTION_INFO",
                (valid ? "有效异议，暂停协议生成：" : "形式审查未通过的异议登记：") + content.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectionNo", o.getObjectionNo());
        result.put("valid", valid);
        result.put("status", t.getStatus());
        return result;
    }

    /** 异议人（内部代录，偏差 D4：取 payload 未提供时用当前操作人） */
    private String objectioner(String ignored, String hint) {
        String u = SecurityUtils.getCurrentUserId();
        return hint != null ? hint : (u == null ? "采购员" : u);
    }

    @Override
    @Transactional
    public Map<String, Object> reviewObjection(String id, String objectionId,
                                               String verdict, String note) {
        Tender t = support.requireTender(id);
        if (!TenderStateMachine.OBJECTION.equals(t.getStatus())) {
            throw new ServiceException(422, "当前无待复核异议，状态 " + t.getStatus());
        }
        if (!isNotBlank(note) || note.trim().length() < 2) {
            throw new ServiceException(422, "复核结论必填（不少于 2 字）");
        }
        TenderObjection o = objectionDao.selectById(objectionId);
        if (o == null || !id.equals(o.getTenderId())) {
            throw new ServiceException(404, "异议记录不存在");
        }
        if (!"PENDING".equals(o.getReviewStatus())) {
            throw new ServiceException(422, "该异议已复核，当前结论 " + o.getReviewStatus());
        }
        String v = verdict == null ? "" : verdict.trim().toUpperCase();
        if (!"MAINTAIN".equals(v) && !"REBID".equals(v)) {
            throw new ServiceException(422, "复核裁定须为 MAINTAIN（维持）或 REBID（重新招标）");
        }
        o.setReviewStatus(v);
        o.setReviewResult(note.trim());
        o.setReviewBy(SecurityUtils.getCurrentUserId());
        o.setReviewDate(LocalDateTime.now());
        objectionDao.updateById(o);
        if ("MAINTAIN".equals(v)) {
            t.setObjectionFlag("0");
            support.transition(t, TenderStateMachine.AWAITING_PUBLICITY,
                    "复核维持原定标，恢复公示流程");
            support.persist(t);
            support.publishHead(t, "PROC.TENDER.OBJECTION_MAINTAIN",
                    "复核维持原定标，恢复公示；结论：" + note.trim());
        } else {
            t.setObjectionFlag("0");
            // spec「中标子表留存与兼容」：重新招标时定标子表随定标结果作废
            awardDao.delete(new LambdaQueryWrapper<TenderAward>().eq(TenderAward::getTenderId, id));
            support.transition(t, TenderStateMachine.CANCELLED,
                    "复核裁定重新招标，作废原定标");
            t.setAbortReason("复核裁定重新招标：" + note.trim());
            support.persist(t);
            support.publishHead(t, "PROC.TENDER.OBJECTION_REBID",
                    "复核裁定重新招标，原定标作废；结论：" + note.trim());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verdict", v);
        result.put("status", t.getStatus());
        return result;
    }

    // ---------- 框架协议（FR-4.2-10-3 / BR-4.2-05，design D8） ----------

    @Override
    public List<Map<String, Object>> agreements(String keyword, String status) {
        sweep();
        LambdaQueryWrapper<FrameworkAgreement> qw = new LambdaQueryWrapper<FrameworkAgreement>()
                .eq(isNotBlank(status), FrameworkAgreement::getStatus, status)
                .and(isNotBlank(keyword), w -> w
                        .like(FrameworkAgreement::getAgreementNo, keyword.trim())
                        .or().like(FrameworkAgreement::getTenderNo, keyword.trim())
                        .or().like(FrameworkAgreement::getTitle, keyword.trim()))
                .orderByDesc(FrameworkAgreement::getCreateDate);
        List<Map<String, Object>> out = new ArrayList<>();
        for (FrameworkAgreement fa : agreementDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", fa.getId());
            row.put("agreementNo", fa.getAgreementNo());
            row.put("tenderNo", fa.getTenderNo());
            row.put("title", fa.getTitle());
            row.put("effectiveDate", fa.getEffectiveDate());
            row.put("expireDate", fa.getExpireDate());
            row.put("status", fa.getStatus());
            // design D4：生效中/临期可被下单引用（3 已到期 / 4 已终止阻断，spec「到期协议阻断引用」）
            row.put("usable", "1".equals(fa.getStatus()) || "2".equals(fa.getStatus()));
            row.put("source", fa.getSource());
            row.put("totalShare", fa.getTotalShare());
            row.put("lineCount", agreementLineDao.selectCount(
                    new LambdaQueryWrapper<FrameworkAgreementLine>()
                            .eq(FrameworkAgreementLine::getAgreementId, fa.getId())));
            out.add(row);
        }
        return out;
    }

    @Override
    public Map<String, Object> agreementDetail(String id) {
        sweep();
        FrameworkAgreement fa = agreementDao.selectById(id);
        if (fa == null) {
            throw new ServiceException(404, "框架协议不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agreement", fa);
        result.put("lines", agreementLineDao.selectList(new LambdaQueryWrapper<FrameworkAgreementLine>()
                .eq(FrameworkAgreementLine::getAgreementId, id)
                .orderByAsc(FrameworkAgreementLine::getLineNo)));
        return result;
    }

    /** 直接改协议明细 —— 一律 422（FR-4.2-10-3 价格与份额锁定） */
    @Override
    public Map<String, Object> updateAgreementLine(String lineId, Map<String, Object> payload) {
        FrameworkAgreementLine line = agreementLineDao.selectById(lineId);
        if (line == null) {
            throw new ServiceException(404, "协议明细不存在");
        }
        throw new ServiceException(422, "协议单价与份额已锁定，不可直接修改（FR-4.2-10-3）；"
                + "请走协议变更审批（change 接口）");
    }

    @Override
    @Transactional
    public Map<String, Object> changeAgreementLine(String agreementId, String lineId,
                                                   Map<String, Object> payload) {
        FrameworkAgreement fa = agreementDao.selectById(agreementId);
        if (fa == null) {
            throw new ServiceException(404, "框架协议不存在");
        }
        FrameworkAgreementLine line = agreementLineDao.selectById(lineId);
        if (line == null || !agreementId.equals(line.getAgreementId())) {
            throw new ServiceException(404, "协议明细不存在");
        }
        String note = str(payload.get("note"));
        if (!isNotBlank(note) || note.trim().length() < 2) {
            throw new ServiceException(422, "变更审批意见必填（不少于 2 字）");
        }
        BigDecimal beforePrice = line.getUnitPrice();
        BigDecimal beforeShare = line.getSharePct();
        BigDecimal newPrice = decimal(payload.get("unitPrice"), "协议单价");
        BigDecimal newShare = decimal(payload.get("sharePct"), "份额");
        if (newPrice == null && newShare == null) {
            throw new ServiceException(422, "变更须至少提供 unitPrice 或 sharePct");
        }
        if (newPrice != null) {
            if (newPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ServiceException(422, "协议单价须大于 0");
            }
            line.setUnitPrice(newPrice);
        }
        if (newShare != null) {
            line.setSharePct(newShare);
        }
        line.setUpdateBy(SecurityUtils.getCurrentUserId());
        line.setVerNo(line.getVerNo() == null ? 1 : line.getVerNo() + 1);
        agreementLineDao.updateById(line);

        String diff = String.format("单价 %s → %s，份额 %s → %s；意见：%s",
                beforePrice, line.getUnitPrice(), beforeShare, line.getSharePct(), note.trim());
        fa.setChangeReason(diff + "（" + SecurityUtils.getCurrentUserId() + "，" + LocalDateTime.now() + "）");
        fa.setVerNo(fa.getVerNo() + 1);
        agreementDao.updateById(fa);
        log.info("AGREEMENT {} line {} changed: {}", fa.getAgreementNo(), lineId, diff);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unitPrice", line.getUnitPrice());
        result.put("sharePct", line.getSharePct());
        result.put("changeReason", fa.getChangeReason());
        return result;
    }

    /**
     * 手工创建协议（design D5，spec「手工创建协议」）：采购经理权限、创建即生效（status=1），
     * 与招标生成协议共用锁定/变更留痕/到期状态机，来源标记 MANUAL。
     * 行校验：物料与供应商必填、单价 > 0、priceMin/Max 须成对且含 unitPrice、Σ份额 = 100。
     */
    @Override
    @Transactional
    public Map<String, Object> createAgreement(Map<String, Object> payload) {
        String title = str(payload.get("title"));
        if (!isNotBlank(title) || title.trim().length() < 2) {
            throw new ServiceException(422, "协议名称必填（不少于 2 字）");
        }
        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "协议明细行必填");
        }
        LocalDate effective = parseDate(payload.get("effectiveDate"));
        if (effective == null) {
            effective = LocalDate.now();
        }
        LocalDate expire = parseDate(payload.get("expireDate"));
        if (expire == null) {
            expire = effective.plusMonths(agreementValidMonths);
        }
        if (!expire.isAfter(effective)) {
            throw new ServiceException(422, "到期日须晚于生效日");
        }

        // 行校验（先全量校验再落库，避免半截）
        record Row(String itemCode, String itemName, String sid, String sname,
                   BigDecimal price, BigDecimal min, BigDecimal max,
                   BigDecimal share, BigDecimal commit) {}
        List<Row> rows = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        Set<String> items = new HashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new ServiceException(422, "明细行格式非法");
            }
            String itemCode = str(m.get("itemCode"));
            String sid = str(m.get("supplierId"));
            if (!isNotBlank(itemCode)) {
                throw new ServiceException(422, "明细行物料必填");
            }
            if (!items.add(itemCode)) {
                throw new ServiceException(422, "同物料手工协议行不可重复：" + itemCode);
            }
            if (!isNotBlank(sid)) {
                throw new ServiceException(422, "明细行供应商必填");
            }
            BigDecimal price = toDecimal(m.get("unitPrice"));
            if (price == null || price.signum() <= 0) {
                throw new ServiceException(422, itemCode + " 单价须大于 0");
            }
            BigDecimal min = toDecimal(m.get("priceMin"));
            BigDecimal max = toDecimal(m.get("priceMax"));
            if ((min == null) != (max == null)) {
                throw new ServiceException(422, itemCode + " 价格区间上下限须同时填写");
            }
            if (min != null) {
                if (min.compareTo(max) > 0) {
                    throw new ServiceException(422, itemCode + " 价格下限不得高于上限");
                }
                if (price.compareTo(min) < 0 || price.compareTo(max) > 0) {
                    throw new ServiceException(422, itemCode + " 单价须落在价格区间内");
                }
            }
            BigDecimal share = toDecimal(m.get("sharePct"));
            if (share == null || share.signum() <= 0) {
                throw new ServiceException(422, itemCode + " 份额须大于 0");
            }
            share = share.setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal commit = toDecimal(m.get("commitQty"));
            if (commit != null && commit.signum() < 0) {
                throw new ServiceException(422, itemCode + " 承诺量不得为负");
            }
            sum = sum.add(share);
            rows.add(new Row(itemCode.trim(), str(m.get("itemName")), sid.trim(),
                    str(m.get("supplierName")), price, min, max, share, commit));
        }
        if (sum.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new ServiceException(422, "份额合计须等于 100%，当前 "
                    + sum.stripTrailingZeros().toPlainString() + "%");
        }

        FrameworkAgreement fa = new FrameworkAgreement();
        fa.setAgreementNo(nextAgreementNo());
        fa.setTitle(title.trim());
        fa.setEffectiveDate(effective);
        fa.setExpireDate(expire);
        fa.setStatus("1");
        fa.setSource("MANUAL");
        fa.setTotalShare(BigDecimal.valueOf(100));
        fa.setChangeNote("手工创建（" + SecurityUtils.getCurrentUserId() + "，" + LocalDateTime.now() + "）");
        agreementDao.insert(fa);

        int no = 1;
        for (Row r : rows) {
            FrameworkAgreementLine fl = new FrameworkAgreementLine();
            fl.setAgreementId(fa.getId());
            fl.setLineNo(no++);
            fl.setItemCode(r.itemCode());
            fl.setItemName(r.itemName() == null ? r.itemCode() : r.itemName());
            fl.setAwardSupplierId(r.sid());
            fl.setAwardSupplierName(isNotBlank(r.sname()) ? r.sname() : supplierName(r.sid()));
            fl.setUnitPrice(r.price());
            fl.setSharePct(r.share());
            fl.setCommitQty(r.commit());
            fl.setOrderedQty(BigDecimal.ZERO);
            fl.setPriceMin(r.min());
            fl.setPriceMax(r.max());
            fl.setCreateBy(SecurityUtils.getCurrentUserId());
            agreementLineDao.insert(fl);
        }
        log.info("AGREEMENT {} manually created lines={} expire={}", fa.getAgreementNo(), rows.size(), expire);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agreement", fa);
        result.put("lineCount", rows.size());
        return result;
    }

    /**
     * 续签（spec「续签与终止」/ L1059 到期前 30 天提醒续签）：
     * 仅临期(2)/已到期(3)可续签 → 生成新协议（新编号、继承行结构与份额、有效期 +N 月），
     * 原协议置已到期并记录关联。
     */
    @Override
    @Transactional
    public Map<String, Object> renewAgreement(String id) {
        FrameworkAgreement orig = agreementDao.selectById(id);
        if (orig == null) {
            throw new ServiceException(404, "框架协议不存在");
        }
        if (!"2".equals(orig.getStatus()) && !"3".equals(orig.getStatus())) {
            throw new ServiceException(422, "仅临期或已到期协议可续签，当前状态 " + orig.getStatus());
        }
        if (agreementDao.selectCount(new LambdaQueryWrapper<FrameworkAgreement>()
                .eq(FrameworkAgreement::getRenewOf, id)) > 0) {
            throw new ServiceException(422, "该协议已存在续签记录，请勿重复续签");
        }
        List<FrameworkAgreementLine> origLines = agreementLineDao.selectList(
                new LambdaQueryWrapper<FrameworkAgreementLine>()
                        .eq(FrameworkAgreementLine::getAgreementId, id)
                        .orderByAsc(FrameworkAgreementLine::getLineNo));
        if (origLines.isEmpty()) {
            throw new ServiceException(422, "原协议无明细行，不可续签");
        }

        LocalDate effective = LocalDate.now();
        FrameworkAgreement na = new FrameworkAgreement();
        na.setAgreementNo(nextAgreementNo());
        na.setTenderNo(orig.getTenderNo());
        na.setTenderId(orig.getTenderId());
        na.setTitle(orig.getTitle());
        na.setEffectiveDate(effective);
        na.setExpireDate(effective.plusMonths(agreementValidMonths));
        na.setStatus("1");
        na.setSource(orig.getSource());
        na.setRenewOf(orig.getId());
        na.setTotalShare(orig.getTotalShare());
        na.setChangeNote("由 " + orig.getAgreementNo() + " 续签（" + SecurityUtils.getCurrentUserId()
                + "，" + LocalDateTime.now() + "）");
        agreementDao.insert(na);

        int no = 1;
        for (FrameworkAgreementLine l : origLines) {
            FrameworkAgreementLine nl = new FrameworkAgreementLine();
            nl.setAgreementId(na.getId());
            nl.setLineNo(no++);
            nl.setItemCode(l.getItemCode());
            nl.setItemName(l.getItemName());
            nl.setAwardSupplierId(l.getAwardSupplierId());
            nl.setAwardSupplierName(l.getAwardSupplierName());
            nl.setUnitPrice(l.getUnitPrice());
            nl.setSharePct(l.getSharePct());
            nl.setCommitQty(l.getCommitQty());
            nl.setOrderedQty(BigDecimal.ZERO);   // 新协议余量从零计
            nl.setPriceMin(l.getPriceMin());
            nl.setPriceMax(l.getPriceMax());
            nl.setCreateBy(SecurityUtils.getCurrentUserId());
            agreementLineDao.insert(nl);
        }

        orig.setStatus("3");
        orig.setChangeNote("已由 " + na.getAgreementNo() + " 续签（" + LocalDateTime.now() + "）");
        agreementDao.updateById(orig);
        log.info("AGREEMENT {} renewed to {} (lines={})", orig.getAgreementNo(),
                na.getAgreementNo(), origLines.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agreement", na);
        result.put("renewedFrom", orig.getAgreementNo());
        return result;
    }

    /** 终止协议（spec「续签与终止」）：仅生效中/临期可终止，原因必填，置 4 后不可逆 */
    @Override
    @Transactional
    public Map<String, Object> stopAgreement(String id, String reason) {
        FrameworkAgreement fa = agreementDao.selectById(id);
        if (fa == null) {
            throw new ServiceException(404, "框架协议不存在");
        }
        if (!"1".equals(fa.getStatus()) && !"2".equals(fa.getStatus())) {
            throw new ServiceException(422, "仅生效中/临期协议可终止，当前状态 " + fa.getStatus());
        }
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "终止原因必填（不少于 2 字）");
        }
        fa.setStatus("4");
        fa.setStopReason(reason.trim());
        fa.setChangeNote("终止（" + SecurityUtils.getCurrentUserId() + "，" + LocalDateTime.now() + "）");
        agreementDao.updateById(fa);
        log.info("AGREEMENT {} stopped: {}", fa.getAgreementNo(), reason.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", fa.getStatus());
        result.put("stopReason", fa.getStopReason());
        return result;
    }

    /** 协议状态懒推进（design D4，与招标 sweep 同范式，无 @Scheduled）：1→2 临期、1/2→3 已到期 */
    private void sweepAgreements() {
        LocalDate today = LocalDate.now();
        for (FrameworkAgreement fa : agreementDao.selectList(new LambdaQueryWrapper<FrameworkAgreement>()
                .in(FrameworkAgreement::getStatus, "1", "2")
                .isNotNull(FrameworkAgreement::getExpireDate))) {
            if (fa.getExpireDate().isBefore(today)) {
                fa.setStatus("3");
                agreementDao.updateById(fa);
                log.info("AGREEMENT {} expired (was {})", fa.getAgreementNo(), "1".equals(fa.getStatus()) ? "生效中" : "临期");
            } else if ("1".equals(fa.getStatus())
                    && !fa.getExpireDate().isAfter(today.plusDays(expiryRemindDays))) {
                fa.setStatus("2");
                agreementDao.updateById(fa);
                log.info("AGREEMENT {} approaching expiry {} (<= {}d), reminder on",
                        fa.getAgreementNo(), fa.getExpireDate(), expiryRemindDays);
            }
        }
    }

    /** 解析 yyyy-MM-dd 日期（载荷），非法抛 422 */
    private LocalDate parseDate(Object o) {
        if (o == null || !isNotBlank(String.valueOf(o))) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(o).trim().substring(0, 10));
        } catch (Exception e) {
            throw new ServiceException(422, "日期格式非法（应为 yyyy-MM-dd）：" + o);
        }
    }

    /** 协议编号 FA-YYYYMMDD-NNN */
    private String nextAgreementNo() {
        String prefix = "FA-" + LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = agreementDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    /** 异议编号 OBJ-YYYYMMDD-NNN */
    private String nextObjectionNo() {
        String prefix = "OBJ-" + LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = objectionDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    // ---------- 作废 ----------

    @Override
    @Transactional
    public void cancel(String id, String reason) {
        Tender t = support.requireTender(id);
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "作废原因必填（不少于 2 字）");
        }
        long agreements = agreementDao.selectCount(new LambdaQueryWrapper<FrameworkAgreement>()
                .eq(FrameworkAgreement::getTenderId, id));
        if (agreements > 0) {
            throw new ServiceException(422, "已生成框架协议（" + agreements + " 份），不可作废");
        }
        support.transition(t, TenderStateMachine.CANCELLED, "作废：" + reason.trim());
        t.setAbortReason(reason.trim());
        support.persist(t);
        support.publishHead(t, "PROC.TENDER.CANCELLED", "作废原因：" + reason.trim());
    }

    // ---------- 懒 sweep：截止即锁（D2/D5） + 公示期满生成协议（FR-4.2-10-3） ----------

    private void sweep() {
        // 1) 报价截止即锁价
        for (Tender t : tenderDao.selectList(new LambdaQueryWrapper<Tender>()
                .in(Tender::getStatus, TenderStateMachine.BIDDING)
                .isNotNull(Tender::getQuoteDeadline))) {
            if (t.getQuoteDeadline().isBefore(LocalDateTime.now())) {
                support.transition(t, TenderStateMachine.LOCKED, "报价截止锁价（BR-4.2-06，偏差 D5）");
                support.publishHead(t, "PROC.TENDER.LOCKED", "截止 " + t.getQuoteDeadline() + " 锁价");
                long rounds = quoteDao.selectCount(new LambdaQueryWrapper<TenderQuote>()
                        .eq(TenderQuote::getTenderId, t.getId()));
                log.info("TENDER {} locked by deadline {} rounds={}",
                        t.getTenderNo(), t.getQuoteDeadline(), rounds);
            }
        }
        // 2) 公示期满且无有效异议 → 自动生成框架协议（BR-4.2-05 / FR-4.2-10-3）
        for (Tender t : tenderDao.selectList(new LambdaQueryWrapper<Tender>()
                .eq(Tender::getStatus, TenderStateMachine.AWAITING_PUBLICITY)
                .isNotNull(Tender::getPublicityEnd))) {
            if (!t.getPublicityEnd().isBefore(LocalDateTime.now())) {
                continue;   // 公示未到期
            }
            if ("1".equals(t.getObjectionFlag())) {
                log.info("TENDER {} publicity expired but objection pending, agreement held", t.getTenderNo());
                continue;   // BR-4.2-48：有效异议暂停协议生成
            }
            generateAgreement(t);
        }
        // 3) 协议状态懒推进（design D4：临期提醒 / 到期，L1059）
        sweepAgreements();
    }

    /**
     * 公示期满无异议 → 自动生成框架协议（幂等：TENDER_NO 唯一约束兜底）。
     * 明细 = 招标行 × 中标子表每家中标人（design D2/D3）：单价取该家中标价、份额取子表值、
     * COMMIT_QTY = 招标行数量、ORDERED_QTY = 0。份额和 ≠ 100 阻断生成（在 sweep 内只记日志不抛出）。
     */
    private void generateAgreement(Tender t) {
        if (t.getAwardSupplierId() == null) {
            log.warn("TENDER {} cannot generate agreement: no award winner (BR-4.2-05)", t.getTenderNo());
            return;
        }
        if (agreementDao.selectCount(new LambdaQueryWrapper<FrameworkAgreement>()
                .eq(FrameworkAgreement::getTenderId, t.getId())) > 0) {
            return;   // 已生成，幂等
        }
        // 中标子表为权威来源；空则回退头快照合成单家（存量/迁移兜底）
        List<TenderAward> winners = awardDao.selectList(new LambdaQueryWrapper<TenderAward>()
                .eq(TenderAward::getTenderId, t.getId())
                .orderByAsc(TenderAward::getLineNo));
        if (winners.isEmpty()) {
            if (t.getAwardPrice() == null) {
                log.warn("TENDER {} cannot generate agreement: award price missing (BR-4.2-05)", t.getTenderNo());
                return;
            }
            TenderAward legacy = new TenderAward();
            legacy.setSupplierId(t.getAwardSupplierId());
            legacy.setSupplierName(supplierName(t.getAwardSupplierId()));
            legacy.setAwardPrice(t.getAwardPrice());
            legacy.setSharePct(BigDecimal.valueOf(100));
            winners = new ArrayList<>(List.of(legacy));
        }
        BigDecimal winSum = winners.stream().map(TenderAward::getSharePct)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (winSum.compareTo(BigDecimal.valueOf(100)) != 0) {
            log.warn("TENDER {} award share sum {} != 100, agreement generation blocked",
                    t.getTenderNo(), winSum);
            return;
        }
        FrameworkAgreement fa = new FrameworkAgreement();
        fa.setAgreementNo(nextAgreementNo());
        fa.setTenderNo(t.getTenderNo());
        fa.setTenderId(t.getId());
        fa.setTitle(t.getTitle());
        LocalDate effective = t.getPublicityEnd().toLocalDate();
        fa.setEffectiveDate(effective);
        fa.setExpireDate(effective.plusMonths(agreementValidMonths));
        fa.setStatus("1");
        fa.setTotalShare(BigDecimal.valueOf(100));
        try {
            agreementDao.insert(fa);
        } catch (DuplicateKeyException ex) {
            log.info("AGREEMENT already exists for tender {}", t.getTenderNo());
            return;
        }

        List<TenderLine> lines = lineDao.selectList(new LambdaQueryWrapper<TenderLine>()
                .eq(TenderLine::getTenderId, t.getId())
                .orderByAsc(TenderLine::getLineNo));
        int no = 1;
        for (TenderLine l : lines) {
            for (TenderAward w : winners) {
                FrameworkAgreementLine fl = new FrameworkAgreementLine();
                fl.setAgreementId(fa.getId());
                fl.setLineNo(no++);
                fl.setItemCode(l.getItemCode());
                fl.setItemName(l.getItemName());
                fl.setAwardSupplierId(w.getSupplierId());
                fl.setAwardSupplierName(isNotBlank(w.getSupplierName())
                        ? w.getSupplierName() : supplierName(w.getSupplierId()));
                fl.setUnitPrice(w.getAwardPrice());
                fl.setSharePct(w.getSharePct());
                // design D3：承诺量 = 招标行数量，已下单量清零
                fl.setCommitQty(l.getQty());
                fl.setOrderedQty(BigDecimal.ZERO);
                fl.setCreateBy("system");
                agreementLineDao.insert(fl);
            }
        }

        support.transition(t, TenderStateMachine.AWARDED,
                "公示期满无异议，生成框架协议 " + fa.getAgreementNo());
        support.persist(t);
        StringBuilder winnersDesc = new StringBuilder();
        for (TenderAward w : winners) {
            if (winnersDesc.length() > 0) {
                winnersDesc.append("、");
            }
            winnersDesc.append(w.getSupplierName()).append(" ").append(w.getSharePct()).append("%");
        }
        support.publishHead(t, "PROC.TENDER.AWARDED",
                "公示期满，生成协议 " + fa.getAgreementNo() + " 中标 " + winnersDesc
                        + " 有效期至 " + fa.getExpireDate());
        log.info("AGREEMENT {} generated for tender {} lines={} winners={} expire={}",
                fa.getAgreementNo(), t.getTenderNo(), lines.size(), winners.size(), fa.getExpireDate());
    }

    // ---------- 私有 ----------

    private String supplierName(String supplierId) {
        MdmSupplier s = mdmSupplierDao.selectById(supplierId);
        return s == null ? supplierId : s.getSupplierName();
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

    /** 支持 yyyy-MM-dd'T'HH:mm[:ss] / yyyy-MM-dd HH:mm[:ss] / yyyy-MM-dd */
    private LocalDateTime datetime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim();
        try {
            if (v.length() <= 10) {
                return LocalDate.parse(v).atStartOfDay();
            }
            String norm = v.replace(' ', 'T');
            if (norm.length() == 16) {
                norm = norm + ":00";
            }
            return LocalDateTime.parse(norm);
        } catch (DateTimeParseException e) {
            throw new ServiceException(422, "时间格式非法（须 yyyy-MM-dd 或 yyyy-MM-dd HH:mm）：" + s);
        }
    }

    private LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim().substring(0, Math.min(10, s.trim().length())));
        } catch (Exception e) {
            throw new ServiceException(422, "日期格式须为 yyyy-MM-dd：" + s);
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
