package com.erp.service.impl.crm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.crm.LeadDao;
import com.erp.dao.crm.OppFollowupDao;
import com.erp.dao.crm.OppStageLogDao;
import com.erp.dao.crm.OpportunityDao;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.entity.crm.Lead;
import com.erp.entity.crm.OppFollowup;
import com.erp.entity.crm.OppStageLog;
import com.erp.entity.crm.Opportunity;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.crm.OpportunityService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商机域（spec opportunity-management）：
 * 两入口创建与自动编号、超百万通知、5 阶段推进全审批（审批通过前停原阶段）、
 * 30 天停留超期标记、丢失归档、转化闸口（BR-4.3-07）与漏斗赢率统计（BR-4.8-09）。
 */
@Slf4j
@Service
public class OpportunityServiceImpl implements OpportunityService {

    /** 预期金额超过该值自动通知销售经理（FR-4.8-1-5，单位：元） */
    private static final BigDecimal LARGE_AMOUNT = new BigDecimal("1000000");
    private static final int STAGE_OVERDUE_DAYS = 30;

    private final OpportunityDao oppDao;
    private final OppStageLogDao stageLogDao;
    private final OppFollowupDao followupDao;
    private final LeadDao leadDao;
    private final MdmCustomerGroupDao customerDao;
    private final ApprovalEngine approvalEngine;
    private final NoticeService noticeService;

    public OpportunityServiceImpl(OpportunityDao oppDao,
                                  OppStageLogDao stageLogDao,
                                  OppFollowupDao followupDao,
                                  LeadDao leadDao,
                                  MdmCustomerGroupDao customerDao,
                                  ApprovalEngine approvalEngine,
                                  NoticeService noticeService) {
        this.oppDao = oppDao;
        this.stageLogDao = stageLogDao;
        this.followupDao = followupDao;
        this.leadDao = leadDao;
        this.customerDao = customerDao;
        this.approvalEngine = approvalEngine;
        this.noticeService = noticeService;
    }

    // ---------- 查询 ----------

    @Override
    public Page<Opportunity> page(long current, long size, String keyword, String status,
                                  String stage, String ownerId) {
        requireAny("查询商机", "ROLE_SALES", "ROLE_SALES_MGR");
        LambdaQueryWrapper<Opportunity> qw = new LambdaQueryWrapper<Opportunity>()
                .orderByDesc(Opportunity::getCreateDate);
        if (hasText(status)) {
            qw.eq(Opportunity::getStatus, status.trim());
        }
        if (hasText(stage)) {
            qw.eq(Opportunity::getStage, stage.trim());
        }
        if (hasText(ownerId)) {
            qw.eq(Opportunity::getOwnerId, ownerId.trim());
        }
        if (hasText(keyword)) {
            String k = keyword.trim();
            qw.and(w -> w.like(Opportunity::getOppNo, k)
                    .or().like(Opportunity::getOppName, k)
                    .or().like(Opportunity::getCustomerName, k));
        }
        Page<Opportunity> page = oppDao.selectPage(new Page<>(current, size), qw);
        // 查询时实时计算停留天数与超期（L4），调度标记列仅作筛选与批量口径
        for (Opportunity o : page.getRecords()) {
            enrich(o);
        }
        return page;
    }

    @Override
    public Opportunity get(String id) {
        requireAny("查看商机", "ROLE_SALES", "ROLE_SALES_MGR");
        Opportunity o = require(id);
        enrich(o);
        return o;
    }

    // ---------- 创建 / 变更 ----------

    @Override
    @Transactional
    public Opportunity create(Opportunity opp) {
        requireAny("创建商机", "ROLE_SALES", "ROLE_SALES_MGR");
        if (opp == null) {
            throw new ServiceException(422, "商机数据必填");
        }
        requireText(opp.getOppName(), "商机名称");
        if (isBlank(opp.getCustomerId())) {
            throw new ServiceException(422, "客户主数据必填");
        }

        // 客户主数据不存在 → 引导先建档（FR-4.8-1-5 场景「客户主数据缺失引导」）
        MdmCustomerGroup customer = customerDao.selectById(opp.getCustomerId());
        if (customer == null) {
            throw new ServiceException(422, "客户主数据不存在，请先创建客户档案后再建商机");
        }

        Lead lead = null;
        if (hasText(opp.getLeadId())) {
            // 线索转化入口：等级 ≥ B（A/B）、要素齐备
            lead = leadDao.selectById(opp.getLeadId());
            if (lead == null) {
                throw new ServiceException(404, "来源线索不存在");
            }
            if (!"A".equals(lead.getGrade()) && !"B".equals(lead.getGrade())) {
                throw new ServiceException(422, "线索等级需为 A 或 B 才能转化为商机（当前 "
                        + (lead.getGrade() == null ? "未评分" : lead.getGrade()) + "）");
            }
            if (Lead.ST_CONVERTED.equals(lead.getStatus())) {
                throw new ServiceException(422, "该线索已转化过商机，不可重复转化");
            }
            // 转化要素必填（FR-4.8-1-5：名称/金额/日期/竞争分析/需求摘要）
            requireText(opp.getExpectAmount() == null ? null : opp.getExpectAmount().toPlainString(), "预期金额");
            if (opp.getExpectCloseDate() == null) {
                throw new ServiceException(422, "预计成交日期必填");
            }
            requireText(opp.getCompetition(), "竞争分析");
            requireText(opp.getDemandSummary(), "客户需求摘要");
        }

        Opportunity row = new Opportunity();
        row.setOppNo(nextOppNo());
        row.setOppName(opp.getOppName().trim());
        row.setCustomerId(opp.getCustomerId());
        row.setCustomerCode(customer.getCustomerCode());
        row.setCustomerName(customer.getCustomerName());
        row.setLeadId(opp.getLeadId());
        row.setExpectAmount(opp.getExpectAmount());
        row.setExpectCloseDate(opp.getExpectCloseDate());
        row.setCompetition(opp.getCompetition());
        row.setDemandSummary(opp.getDemandSummary());
        row.setBudgetRef(opp.getBudgetRef());
        row.setStage(Opportunity.ST_REQUIREMENT);
        row.setStageEnteredAt(LocalDateTime.now());
        row.setStageOverdue("0");
        row.setStatus(Opportunity.ST_OPEN);
        row.setOwnerId(hasText(opp.getOwnerId()) ? opp.getOwnerId() : currentUser());
        row.setOwnerName(hasText(opp.getOwnerName()) ? opp.getOwnerName() : currentUser());
        row.setRemark(opp.getRemark());
        oppDao.insert(row);

        // 线索回写：CONVERTED + 关联商机 ID
        if (lead != null) {
            lead.setStatus(Lead.ST_CONVERTED);
            lead.setConvertedOppId(row.getId());
            leadDao.updateById(lead);
            // 首次转化的跟进留痕
            addSystemFollowup(row.getId(), "线索转化：" + lead.getLeadNo() + " → 商机 " + row.getOppNo());
        }

        notifyIfLarge(row);
        log.info("opportunity created: {} customer={} lead={}", row.getOppNo(),
                row.getCustomerCode(), row.getLeadId());
        return row;
    }

    @Override
    @Transactional
    public Opportunity update(Opportunity opp) {
        requireAny("修改商机", "ROLE_SALES", "ROLE_SALES_MGR");
        Opportunity cur = require(opp.getId());
        if (!Opportunity.ST_OPEN.equals(cur.getStatus())) {
            throw new ServiceException(422, "商机已归档（" + cur.getStatus() + "），只读不可修改");
        }
        if (hasText(opp.getOppNo()) && !cur.getOppNo().equals(opp.getOppNo())) {
            throw new ServiceException(422, "商机编号创建后不可修改");
        }
        Opportunity upd = new Opportunity();
        upd.setId(cur.getId());
        upd.setOppName(hasText(opp.getOppName()) ? opp.getOppName().trim() : cur.getOppName());
        if (opp.getExpectAmount() != null) {
            upd.setExpectAmount(opp.getExpectAmount());
        }
        if (opp.getExpectCloseDate() != null) {
            upd.setExpectCloseDate(opp.getExpectCloseDate());
        }
        upd.setCompetition(opp.getCompetition() != null ? opp.getCompetition() : cur.getCompetition());
        upd.setDemandSummary(opp.getDemandSummary() != null ? opp.getDemandSummary() : cur.getDemandSummary());
        if (opp.getBudgetRef() != null) {
            upd.setBudgetRef(opp.getBudgetRef());
        }
        if (opp.getOwnerId() != null) {
            upd.setOwnerId(opp.getOwnerId());
            upd.setOwnerName(opp.getOwnerName());
        }
        if (opp.getRemark() != null) {
            upd.setRemark(opp.getRemark());
        }
        oppDao.updateById(upd);
        Opportunity after = require(cur.getId());
        notifyIfLarge(after);
        return after;
    }

    // ---------- 阶段推进（FR-4.8-1-6 全审批） ----------

    @Override
    @Transactional
    public OppStageLog advanceStage(String oppId, String toStage, Integer probability,
                                    String nextAction, LocalDate nextActionDate) {
        requireAny("推进商机阶段", "ROLE_SALES", "ROLE_SALES_MGR");
        Opportunity opp = require(oppId);
        if (!Opportunity.ST_OPEN.equals(opp.getStatus())) {
            throw new ServiceException(422, "商机非在跟状态，不可推进：" + opp.getStatus());
        }
        if (hasText(opp.getApprovalId())) {
            throw new ServiceException(422, "该商机存在在途阶段审批，请先处理");
        }
        long pending = stageLogDao.selectCount(new LambdaQueryWrapper<OppStageLog>()
                .eq(OppStageLog::getOppId, oppId)
                .eq(OppStageLog::getStatus, OppStageLog.ST_PENDING));
        if (pending > 0) {
            throw new ServiceException(422, "该商机存在在途阶段审批，请先处理");
        }
        // 仅允许推进到下一阶段（不许跳级/回退）
        String expected = Opportunity.nextStage(opp.getStage());
        if (expected == null || !expected.equals(toStage)) {
            throw new ServiceException(422, "只能推进到下一阶段（当前 "
                    + opp.stageName() + "，可推进至 "
                    + (expected == null ? "无（已是最后阶段）" : stageNameOf(expected)) + "）");
        }
        if (probability == null || probability < 0 || probability > 100) {
            throw new ServiceException(422, "阶段概率必填（0-100）");
        }
        requireText(nextAction, "下一步行动");
        if (nextActionDate == null) {
            throw new ServiceException(422, "下一步预计完成日期必填");
        }

        OppStageLog row = new OppStageLog();
        row.setOppId(oppId);
        row.setFromStage(opp.getStage());
        row.setToStage(toStage);
        row.setProbability(probability);
        row.setNextAction(nextAction.trim());
        row.setNextActionDate(nextActionDate);
        row.setStatus(OppStageLog.ST_PENDING);
        row.setApplyBy(currentUser());
        row.setApplyAt(LocalDateTime.now());
        stageLogDao.insert(row);

        // 提交销售经理审批（默认节点，design 决策「5 阶段全审批」）
        List<List<ApprovalNodeSpec>> chain = List.of(
                List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售经理审批")));
        ApprovalInstance inst = approvalEngine.submit("OppStage", row.getId(),
                "商机阶段推进：" + opp.getOppNo() + " " + opp.getOppName()
                        + "（" + stageNameOf(row.getFromStage()) + " → " + stageNameOf(row.getToStage()) + "）",
                null, chain);
        row.setApprovalId(inst.getId());
        stageLogDao.updateById(row);

        opp.setApprovalId(inst.getId());
        oppDao.updateById(opp);
        log.info("opportunity {} stage advance submitted: {} -> {} ({})", opp.getOppNo(),
                row.getFromStage(), row.getToStage(), inst.getId());
        return row;
    }

    // ---------- 丢失归档（FR-4.8-1-6） ----------

    @Override
    @Transactional
    public Opportunity markLost(String oppId, String category, String remark) {
        requireAny("丢失归档商机", "ROLE_SALES", "ROLE_SALES_MGR");
        Opportunity opp = require(oppId);
        if (!Opportunity.ST_OPEN.equals(opp.getStatus())) {
            throw new ServiceException(422, "商机非在跟状态，不可标记丢失：" + opp.getStatus());
        }
        if (hasText(opp.getApprovalId())) {
            throw new ServiceException(422, "存在在途阶段审批，请先撤回或处理后再归档");
        }
        requireText(category, "丢失原因分类");
        boolean known = false;
        for (String c : Opportunity.LOSS_CATEGORIES) {
            if (c.equals(category)) {
                known = true;
                break;
            }
        }
        if (!known) {
            throw new ServiceException(422, "丢失原因分类不合法：" + category);
        }
        requireText(remark, "丢失说明");

        opp.setStatus(Opportunity.ST_LOST);
        opp.setLossCategory(category);
        opp.setLossRemark(remark.trim());
        opp.setLossAt(LocalDateTime.now());
        opp.setCloseAt(LocalDateTime.now());
        oppDao.updateById(opp);
        addSystemFollowup(oppId, "丢失归档：" + category + " / " + remark.trim());
        log.info("opportunity {} lost: {}", opp.getOppNo(), category);
        return opp;
    }

    // ---------- 跟进记录（仅追加） ----------

    @Override
    public List<OppStageLog> stageLogs(String oppId) {
        requireAny("查看阶段日志", "ROLE_SALES", "ROLE_SALES_MGR");
        require(oppId);
        return stageLogDao.selectList(new LambdaQueryWrapper<OppStageLog>()
                .eq(OppStageLog::getOppId, oppId)
                .orderByDesc(OppStageLog::getApplyAt));
    }

    @Override
    public List<OppFollowup> followups(String oppId) {
        requireAny("查看商机跟进", "ROLE_SALES", "ROLE_SALES_MGR");
        require(oppId);
        return followupDao.selectList(new LambdaQueryWrapper<OppFollowup>()
                .eq(OppFollowup::getOppId, oppId)
                .orderByDesc(OppFollowup::getFollowAt));
    }

    @Override
    public OppFollowup addFollowup(OppFollowup f) {
        requireAny("记录商机跟进", "ROLE_SALES", "ROLE_SALES_MGR");
        if (f == null || isBlank(f.getOppId())) {
            throw new ServiceException(422, "商机 ID 必填");
        }
        requireText(f.getFollowType(), "跟进方式");
        requireText(f.getContent(), "跟进内容");
        boolean known = false;
        for (String t : OppFollowup.TYPES) {
            if (t.equals(f.getFollowType())) {
                known = true;
                break;
            }
        }
        if (!known) {
            throw new ServiceException(422, "跟进方式不合法：" + f.getFollowType());
        }
        if ("SYSTEM".equals(f.getFollowType())) {
            throw new ServiceException(422, "SYSTEM 为系统自动记录类型，不可手工录入");
        }
        require(f.getOppId());

        OppFollowup row = new OppFollowup();
        row.setOppId(f.getOppId());
        row.setFollowType(f.getFollowType());
        row.setContent(f.getContent().trim());
        row.setNextPlan(f.getNextPlan());
        row.setOperatorId(currentUser());
        row.setOperatorName(currentUser());
        row.setFollowAt(LocalDateTime.now());
        followupDao.insert(row);
        return row;
    }

    // ---------- 3.1.2 转化闸口（BR-4.3-07） ----------

    @Override
    @Transactional
    public Map<String, Object> checkQuoteGate(String oppId) {
        requireAny("商机转化校验", "ROLE_SALES", "ROLE_SALES_MGR");
        Opportunity opp = require(oppId);
        Map<String, Object> out = new LinkedHashMap<>();

        String reason = null;
        if (Opportunity.ST_LOST.equals(opp.getStatus()) || Opportunity.ST_CLOSED.equals(opp.getStatus())) {
            reason = "该商机已归档（" + ("LOST".equals(opp.getStatus()) ? "丢失" : "关闭") + "），不可引用";
        } else if (Opportunity.stageIndex(opp.getStage()) < Opportunity.stageIndex(Opportunity.ST_QUOTE)) {
            reason = "该商机尚未达到报价阶段（当前：" + opp.stageName() + "）";
        } else {
            MdmCustomerGroup customer = customerDao.selectById(opp.getCustomerId());
            if (customer == null) {
                reason = "客户主数据不存在，请先完成客户建档";
            } else if ("2".equals(customer.getStatus())) {
                reason = "客户已冻结，不可引用";
            } else if ("3".equals(customer.getStatus())) {
                reason = "客户已合并（→ " + customer.getMergedTo() + "），请改用合并后客户";
            }
        }

        if (reason != null) {
            // 不可引用原因回写商机追踪记录（spec：回写商机追踪记录）
            opp.setQuoteBlockReason(reason);
            oppDao.updateById(opp);
            addSystemFollowup(oppId, "转化闸口校验未通过：" + reason);
            out.put("ok", false);
            out.put("reason", reason);
            return out;
        }

        // 通过：清空历史阻断原因 + 返回报价预填数据
        oppDao.update(null, new LambdaUpdateWrapper<Opportunity>()
                .eq(Opportunity::getId, oppId)
                .set(Opportunity::getQuoteBlockReason, null));
        out.put("ok", true);
        Map<String, Object> prefill = new LinkedHashMap<>();
        prefill.put("oppId", opp.getId());
        prefill.put("oppNo", opp.getOppNo());
        prefill.put("oppName", opp.getOppName());
        prefill.put("customerId", opp.getCustomerId());
        prefill.put("customerCode", opp.getCustomerCode());
        prefill.put("customerName", opp.getCustomerName());
        prefill.put("demandSummary", opp.getDemandSummary());
        prefill.put("expectAmount", opp.getExpectAmount());
        prefill.put("stage", opp.getStage());
        out.put("prefill", prefill);
        return out;
    }

    // ---------- 统计（BR-4.8-09） ----------

    @Override
    public Map<String, Object> stats() {
        requireAny("查看商机统计", "ROLE_SALES", "ROLE_SALES_MGR");

        // 漏斗：5 阶段全列出（无商机的阶段补 0，图表不缺桶）
        Map<String, Map<String, Object>> byStage = new LinkedHashMap<>();
        for (String s : Opportunity.STAGES) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("stage", s);
            bucket.put("stageName", stageNameOf(s));
            bucket.put("count", 0);
            bucket.put("amount", BigDecimal.ZERO);
            byStage.put(s, bucket);
        }
        for (Map<String, Object> r : oppDao.selectFunnel()) {
            String s = String.valueOf(r.get("stage"));
            Map<String, Object> bucket = byStage.get(s);
            if (bucket != null) {
                bucket.put("count", r.get("cnt"));
                bucket.put("amount", r.get("amount"));
            }
        }

        long total = nz(oppDao.selectCount(new LambdaQueryWrapper<Opportunity>()));
        long open = nz(oppDao.selectCount(new LambdaQueryWrapper<Opportunity>()
                .eq(Opportunity::getStatus, Opportunity.ST_OPEN)));
        long won = nz(oppDao.selectCount(new LambdaQueryWrapper<Opportunity>()
                .eq(Opportunity::getStatus, Opportunity.ST_WON)));
        long lost = nz(oppDao.selectCount(new LambdaQueryWrapper<Opportunity>()
                .eq(Opportunity::getStatus, Opportunity.ST_LOST)));

        // 赢率 = 赢单 / (赢单 + 丢失)
        long decided = won + lost;
        BigDecimal winRate = decided == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(won).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(decided), 1, RoundingMode.HALF_UP);

        // 商机转化率 = 已转化线索 / 全部线索（无线索时为 0，不臆造）
        long leadTotal = nz(leadDao.selectCount(null));
        long leadConverted = nz(leadDao.selectCount(new LambdaQueryWrapper<Lead>()
                .eq(Lead::getStatus, Lead.ST_CONVERTED)));
        BigDecimal conversionRate = leadTotal == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(leadConverted).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(leadTotal), 1, RoundingMode.HALF_UP);

        // 平均销售周期（已终结商机：创建 → 丢失/赢单）
        List<Integer> cycles = oppDao.selectClosedCycleDays();
        BigDecimal avgCycle = BigDecimal.ZERO;
        if (!cycles.isEmpty()) {
            int sum = 0;
            for (Integer d : cycles) {
                sum += d == null ? 0 : d;
            }
            avgCycle = BigDecimal.valueOf(sum)
                    .divide(BigDecimal.valueOf(cycles.size()), 1, RoundingMode.HALF_UP);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("funnel", new ArrayList<>(byStage.values()));
        out.put("total", total);
        out.put("open", open);
        out.put("won", won);
        out.put("lost", lost);
        out.put("winRate", winRate);
        out.put("conversionRate", conversionRate);
        out.put("leadTotal", leadTotal);
        out.put("leadConverted", leadConverted);
        out.put("avgCycleDays", avgCycle);
        out.put("lossDistribution", oppDao.selectLossDistribution());
        return out;
    }

    // ---------- 调度：30 天停留超期标记（L4） ----------

    @Override
    public int sweepOverdue() {
        // 调度线程无登录上下文：不做角色校验（与 LeadService.sweep 同范式）
        LocalDateTime deadline = LocalDateTime.now().minusDays(STAGE_OVERDUE_DAYS);
        int marked = oppDao.update(null, new LambdaUpdateWrapper<Opportunity>()
                .eq(Opportunity::getStatus, Opportunity.ST_OPEN)
                .eq(Opportunity::getStageOverdue, "0")
                .lt(Opportunity::getStageEnteredAt, deadline)
                .set(Opportunity::getStageOverdue, "1"));
        int cleared = oppDao.update(null, new LambdaUpdateWrapper<Opportunity>()
                .eq(Opportunity::getStatus, Opportunity.ST_OPEN)
                .eq(Opportunity::getStageOverdue, "1")
                .ge(Opportunity::getStageEnteredAt, deadline)
                .set(Opportunity::getStageOverdue, "0"));
        // 归档商机不再参与超期口径
        oppDao.update(null, new LambdaUpdateWrapper<Opportunity>()
                .ne(Opportunity::getStatus, Opportunity.ST_OPEN)
                .eq(Opportunity::getStageOverdue, "1")
                .set(Opportunity::getStageOverdue, "0"));
        if (marked > 0 || cleared > 0) {
            log.info("opportunity stage overdue sweep: marked={} cleared={}", marked, cleared);
        }
        return marked + cleared;
    }

    // ---------- helpers ----------

    private void enrich(Opportunity o) {
        if (o.getStageEnteredAt() == null) {
            return;
        }
        long days = ChronoUnit.HOURS.between(o.getStageEnteredAt(), LocalDateTime.now()) / 24;
        o.setStageDays((int) days);
        o.setStageOverdueLive(days > STAGE_OVERDUE_DAYS ? "1" : "0");
        o.setStagePending(hasText(o.getApprovalId()));
    }

    private void notifyIfLarge(Opportunity opp) {
        if (opp.getExpectAmount() != null && opp.getExpectAmount().compareTo(LARGE_AMOUNT) > 0) {
            noticeService.push("ROLE_SALES_MGR", null,
                    "大额商机提醒：" + opp.getOppNo(),
                    "商机「" + opp.getOppName() + "」（客户 " + opp.getCustomerName()
                            + "）预期金额 " + opp.getExpectAmount().toPlainString() + " 元，超过 100 万，请关注。",
                    com.erp.entity.system.SysNotice.BIZ_OPP_LARGE, opp.getId());
        }
    }

    private void addSystemFollowup(String oppId, String content) {
        OppFollowup f = new OppFollowup();
        f.setOppId(oppId);
        f.setFollowType("SYSTEM");
        f.setContent(content);
        f.setOperatorId("system");
        f.setOperatorName("system");
        f.setFollowAt(LocalDateTime.now());
        followupDao.insert(f);
    }

    private String nextOppNo() {
        String prefix = "OPP" + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : oppDao.selectNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private Opportunity require(String id) {
        if (isBlank(id)) {
            throw new ServiceException(422, "商机 ID 必填");
        }
        Opportunity o = oppDao.selectById(id);
        if (o == null) {
            throw new ServiceException(404, "商机不存在");
        }
        return o;
    }

    private static String stageNameOf(String stage) {
        switch (stage == null ? "" : stage) {
            case Opportunity.ST_REQUIREMENT: return "需求确认";
            case Opportunity.ST_DEMO: return "方案演示";
            case Opportunity.ST_NEGOTIATION: return "商务谈判";
            case Opportunity.ST_QUOTE: return "报价";
            case Opportunity.ST_CONTRACT: return "合同签订";
            default: return stage;
        }
    }

    private long nz(Long v) {
        return v == null ? 0 : v;
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

    private void requireText(String s, String name) {
        if (isBlank(s)) {
            throw new ServiceException(422, name + "必填");
        }
    }

    private boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
