package com.erp.service.impl.crm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.crm.LeadDao;
import com.erp.dao.crm.LeadFollowupDao;
import com.erp.dao.crm.LeadPoolDao;
import com.erp.entity.crm.Lead;
import com.erp.entity.crm.LeadFollowup;
import com.erp.entity.crm.LeadPool;
import com.erp.entity.crm.LeadScoreModel;
import com.erp.service.crm.LeadScoreModelService;
import com.erp.service.crm.LeadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 线索域（spec crm-lead-management）：
 * 录入查重（L1 必填 / L4 重复提示）、五维评分与 A/B/C/D 分级、多套模型取用、
 * 单人锁定的分配与认领、只增不改的跟进记录、D 级入池与 30 天回收。
 */
@Slf4j
@Service
public class LeadServiceImpl implements LeadService {

    private static final int REMIND_WORKDAYS = 3;
    private static final int RECYCLE_DAYS = 30;

    private final LeadDao leadDao;
    private final LeadFollowupDao followupDao;
    private final LeadPoolDao poolDao;
    private final LeadScoreModelService scoreModelService;

    public LeadServiceImpl(LeadDao leadDao,
                           LeadFollowupDao followupDao,
                           LeadPoolDao poolDao,
                           LeadScoreModelService scoreModelService) {
        this.leadDao = leadDao;
        this.followupDao = followupDao;
        this.poolDao = poolDao;
        this.scoreModelService = scoreModelService;
    }

    // ---------- 查询 ----------

    @Override
    public Page<Lead> page(long current, long size, String keyword, String status,
                           String grade, String ownerId) {
        requireAny("查询线索", "ROLE_SALES", "ROLE_SALES_MGR");
        LambdaQueryWrapper<Lead> qw = new LambdaQueryWrapper<Lead>()
                .orderByDesc(Lead::getCreateDate);
        if (status != null && !status.trim().isEmpty()) {
            qw.eq(Lead::getStatus, status.trim());
        }
        if (grade != null && !grade.trim().isEmpty()) {
            qw.eq(Lead::getGrade, grade.trim());
        }
        if (ownerId != null && !ownerId.trim().isEmpty()) {
            qw.eq(Lead::getOwnerId, ownerId.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = keyword.trim();
            qw.and(w -> w.like(Lead::getLeadNo, k)
                    .or().like(Lead::getCompanyName, k)
                    .or().like(Lead::getContactName, k));
        }
        return leadDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Lead get(String id) {
        requireAny("查询线索", "ROLE_SALES", "ROLE_SALES_MGR");
        return require(id);
    }

    // ---------- 录入与查重 ----------

    @Override
    public List<Lead> checkDuplicate(String companyName, String contactName) {
        requireAny("查重", "ROLE_SALES", "ROLE_SALES_MGR");
        if (isBlank(companyName) || isBlank(contactName)) {
            return List.of();
        }
        return leadDao.selectDuplicates(companyName.trim(), contactName.trim());
    }

    @Override
    @Transactional
    public Lead create(Lead lead) {
        requireAny("录入线索", "ROLE_SALES", "ROLE_SALES_MGR");
        if (lead == null) {
            throw new ServiceException(422, "线索数据缺失");
        }
        // FR-4.8-1-1：必填字段缺失 → L1 硬阻断
        requireText(lead.getSourceChannel(), "来源渠道");
        requireText(lead.getCompanyName(), "客户名称");
        requireText(lead.getContactName(), "联系人");
        if (!isValidChannel(lead.getSourceChannel())) {
            throw new ServiceException(422, "来源渠道须为系统预设值：展会/网络/渠道/转介绍/主动开拓");
        }

        Lead row = new Lead();
        row.setLeadNo(nextLeadNo());
        row.setSourceChannel(lead.getSourceChannel().trim());
        row.setCompanyName(lead.getCompanyName().trim());
        row.setContactName(lead.getContactName().trim());
        row.setContactPhone(lead.getContactPhone());
        row.setDemandDesc(lead.getDemandDesc());
        row.setExpectAmount(lead.getExpectAmount());
        row.setExpectCloseDate(lead.getExpectCloseDate());
        row.setRemark(lead.getRemark());
        // 查重命中的既有线索：允许带差异说明关联（L4 语义，非阻断）
        if (!isBlank(lead.getDuplicateOf())) {
            row.setDuplicateOf(lead.getDuplicateOf());
        }
        row.setStatus(Lead.ST_OPEN);
        row.setPoolFlag("0");
        if (!isBlank(lead.getOwnerId())) {
            row.setOwnerId(lead.getOwnerId());
            row.setOwnerName(lead.getOwnerName());
            row.setAssignedAt(LocalDateTime.now());
        }
        leadDao.insert(row);
        return row;
    }

    @Override
    @Transactional
    public Lead update(Lead lead) {
        requireAny("变更线索", "ROLE_SALES", "ROLE_SALES_MGR");
        if (lead == null || isBlank(lead.getId())) {
            throw new ServiceException(422, "线索 ID 必填");
        }
        Lead stored = require(lead.getId());
        if (Lead.ST_CONVERTED.equals(stored.getStatus()) || Lead.ST_CLOSED.equals(stored.getStatus())) {
            throw new ServiceException(422, "已转化或已关闭的线索不可变更，当前 " + stored.getStatus());
        }
        if (isBlank(lead.getCompanyName()) || isBlank(lead.getContactName())) {
            throw new ServiceException(422, "客户名称与联系人必填");
        }
        if (!isBlank(lead.getSourceChannel()) && !isValidChannel(lead.getSourceChannel())) {
            throw new ServiceException(422, "来源渠道须为系统预设值");
        }
        if (!isBlank(lead.getSourceChannel())) {
            stored.setSourceChannel(lead.getSourceChannel().trim());
        }
        stored.setCompanyName(lead.getCompanyName().trim());
        stored.setContactName(lead.getContactName().trim());
        stored.setContactPhone(lead.getContactPhone());
        stored.setDemandDesc(lead.getDemandDesc());
        stored.setExpectAmount(lead.getExpectAmount());
        stored.setExpectCloseDate(lead.getExpectCloseDate());
        stored.setRemark(lead.getRemark());
        leadDao.updateById(stored);
        return stored;
    }

    // ---------- 分配与单人锁定 ----------

    @Override
    @Transactional
    public void assign(String leadId, String ownerId, String ownerName) {
        // FR-4.8-1-3 异常：销售经理可手动调整分配
        requireAny("分配线索", "ROLE_SALES_MGR");
        if (isBlank(ownerId)) {
            throw new ServiceException(422, "跟进负责人必填");
        }
        Lead lead = require(leadId);
        if (Lead.ST_CONVERTED.equals(lead.getStatus()) || Lead.ST_CLOSED.equals(lead.getStatus())) {
            throw new ServiceException(422, "已转化或已关闭的线索不可分配");
        }
        lead.setOwnerId(ownerId.trim());
        lead.setOwnerName(isBlank(ownerName) ? ownerId : ownerName.trim());
        lead.setAssignedAt(LocalDateTime.now());
        lead.setRemindAt(null);
        lead.setPoolFlag("0");
        if (Lead.ST_RECYCLED.equals(lead.getStatus())) {
            lead.setStatus(Lead.ST_OPEN);
        }
        leadDao.updateById(lead);
        closePendingPool(leadId, ownerId);
    }

    @Override
    @Transactional
    public void claim(String leadId) {
        requireAny("认领线索", "ROLE_SALES", "ROLE_SALES_MGR");
        String me = currentUser();
        Lead lead = require(leadId);
        if (Lead.ST_CONVERTED.equals(lead.getStatus()) || Lead.ST_CLOSED.equals(lead.getStatus())) {
            throw new ServiceException(422, "已转化或已关闭的线索不可认领");
        }
        // BR-4.8-08：同一线索不可同时被两名销售跟进（系统锁定机制）
        if (!isBlank(lead.getOwnerId()) && !lead.getOwnerId().equals(me)) {
            throw new ServiceException(409, "该线索已由 " + nvl(lead.getOwnerName(), lead.getOwnerId())
                    + " 跟进，不可重复认领");
        }
        lead.setOwnerId(me);
        lead.setOwnerName(me);
        lead.setAssignedAt(LocalDateTime.now());
        lead.setRemindAt(null);
        lead.setPoolFlag("0");
        if (Lead.ST_RECYCLED.equals(lead.getStatus())) {
            lead.setStatus(Lead.ST_OPEN);
        }
        leadDao.updateById(lead);
        closePendingPool(leadId, me);
    }

    // ---------- 评分 ----------

    @Override
    @Transactional
    public Map<String, Object> score(String leadId, Integer need, Integer budget, Integer chain,
                                     Integer urgency, Integer compete) {
        requireAny("线索评分", "ROLE_SALES", "ROLE_SALES_MGR");
        Lead lead = require(leadId);
        LeadScoreModel model = scoreModelService.active(null, null);

        List<String> missing = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        total = total.add(dim(need, model.getWNeed(), "需求明确度", missing));
        total = total.add(dim(budget, model.getWBudget(), "预算确认度", missing));
        total = total.add(dim(chain, model.getWChain(), "决策链清晰度", missing));
        total = total.add(dim(urgency, model.getWUrgency(), "时间紧迫度", missing));
        total = total.add(dim(compete, model.getWCompete(), "竞争态势", missing));
        total = total.setScale(2, RoundingMode.HALF_UP);

        String grade = gradeOf(total, model);

        lead.setScoreNeed(need);
        lead.setScoreBudget(budget);
        lead.setScoreChain(chain);
        lead.setScoreUrgency(urgency);
        lead.setScoreCompete(compete);
        lead.setScore(total);
        lead.setGrade(grade);
        lead.setScoreModelId(model.getId());
        lead.setScoreModelVersion(model.getVersion());
        lead.setScoreAt(LocalDateTime.now());
        leadDao.updateById(lead);

        // FR-4.8-1-3：D 级线索进入线索池暂存
        if ("D".equals(grade)) {
            poolIn(lead, LeadPool.RS_D_GRADE, "D 级线索自动入池");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", total);
        out.put("grade", grade);
        out.put("missing", missing);
        out.put("model", model.getModelKey() + " v" + model.getVersion());
        if (!missing.isEmpty()) {
            out.put("warning", "L4 以下维度未评分按 0 分计，请补充：" + String.join("、", missing));
        }
        return out;
    }

    private BigDecimal dim(Integer value, Integer weight, String name, List<String> missing) {
        int w = weight == null ? 0 : weight;
        if (value == null) {
            missing.add(name);
            return BigDecimal.ZERO;
        }
        if (value < 1 || value > 5) {
            throw new ServiceException(422, name + " 须为 1-5 分");
        }
        // 评分 = Σ(维度得分/5 × 权重)：每维度满分 = 权重，总分 0-100
        return BigDecimal.valueOf(value)
                .multiply(BigDecimal.valueOf(w))
                .divide(BigDecimal.valueOf(5), 4, RoundingMode.HALF_UP);
    }

    private String gradeOf(BigDecimal score, LeadScoreModel m) {
        if (score.compareTo(BigDecimal.valueOf(m.getGradeAMin())) >= 0) {
            return "A";
        }
        if (score.compareTo(BigDecimal.valueOf(m.getGradeBMin())) >= 0) {
            return "B";
        }
        if (score.compareTo(BigDecimal.valueOf(m.getGradeCMin())) >= 0) {
            return "C";
        }
        return "D";
    }

    // ---------- 跟进 ----------

    @Override
    public List<LeadFollowup> followups(String leadId) {
        requireAny("查询跟进", "ROLE_SALES", "ROLE_SALES_MGR");
        return followupDao.selectList(new LambdaQueryWrapper<LeadFollowup>()
                .eq(LeadFollowup::getLeadId, leadId)
                .orderByDesc(LeadFollowup::getFollowAt));
    }

    @Override
    @Transactional
    public LeadFollowup addFollowup(LeadFollowup f) {
        requireAny("记录跟进", "ROLE_SALES", "ROLE_SALES_MGR");
        if (f == null || isBlank(f.getLeadId())) {
            throw new ServiceException(422, "线索 ID 必填");
        }
        requireText(f.getFollowType(), "跟进方式");
        requireText(f.getContent(), "跟进内容");
        boolean knownType = false;
        for (String t : LeadFollowup.TYPES) {
            if (t.equals(f.getFollowType())) {
                knownType = true;
                break;
            }
        }
        if (!knownType) {
            throw new ServiceException(422, "跟进方式不合法：" + f.getFollowType());
        }
        Lead lead = require(f.getLeadId());

        LeadFollowup row = new LeadFollowup();
        row.setLeadId(f.getLeadId());
        row.setFollowType(f.getFollowType());
        row.setContent(f.getContent().trim());
        row.setOperatorId(currentUser());
        row.setOperatorName(currentUser());
        row.setFollowAt(LocalDateTime.now());
        // 按等级生成下次跟进计划（A 每 3 天 / B 每 7 天 / C 与 D 每 14 天，FR-4.8-1-4）
        LocalDate plan = f.getNextPlan() != null ? f.getNextPlan()
                : LocalDate.now().plusDays(planDays(lead.getGrade()));
        row.setNextPlan(plan);
        followupDao.insert(row);

        lead.setLastFollowupAt(row.getFollowAt());
        lead.setNextPlanDate(plan);
        if (Lead.ST_RECYCLED.equals(lead.getStatus()) && !isBlank(lead.getOwnerId())) {
            lead.setStatus(Lead.ST_OPEN);
        }
        leadDao.updateById(lead);
        return row;
    }

    private int planDays(String grade) {
        if ("A".equals(grade)) {
            return 3;
        }
        if ("B".equals(grade)) {
            return 7;
        }
        return 14; // C、D：每 14 天
    }

    // ---------- 线索池 ----------

    @Override
    public List<LeadPool> poolList(String status) {
        requireAny("查询线索池", "ROLE_SALES", "ROLE_SALES_MGR");
        LambdaQueryWrapper<LeadPool> qw = new LambdaQueryWrapper<LeadPool>()
                .orderByDesc(LeadPool::getPooledAt);
        if (status != null && !status.trim().isEmpty()) {
            qw.eq(LeadPool::getStatus, status.trim());
        }
        return poolDao.selectList(qw);
    }

    @Override
    @Transactional
    public void toPool(String leadId, String reason, String remark) {
        requireAny("线索入池", "ROLE_SALES_MGR");
        if (isBlank(reason)) {
            throw new ServiceException(422, "入池原因必填");
        }
        poolIn(require(leadId), reason, remark);
    }

    @Override
    @Transactional
    public void assignFromPool(String poolId, String ownerId, String ownerName) {
        requireAny("线索池指派", "ROLE_SALES_MGR");
        if (isBlank(ownerId)) {
            throw new ServiceException(422, "指派对象必填");
        }
        LeadPool pool = poolDao.selectById(poolId);
        if (pool == null) {
            throw new ServiceException(404, "线索池记录不存在：" + poolId);
        }
        if (!LeadPool.ST_PENDING.equals(pool.getStatus())) {
            throw new ServiceException(422, "该记录已指派");
        }
        pool.setStatus(LeadPool.ST_ASSIGNED);
        pool.setAssignedTo(ownerId);
        pool.setAssignedBy(currentUser());
        pool.setAssignedAt(LocalDateTime.now());
        poolDao.updateById(pool);

        Lead lead = require(pool.getLeadId());
        lead.setOwnerId(ownerId);
        lead.setOwnerName(isBlank(ownerName) ? ownerId : ownerName);
        lead.setAssignedAt(LocalDateTime.now());
        lead.setRemindAt(null);
        lead.setPoolFlag("0");
        lead.setStatus(Lead.ST_OPEN);
        leadDao.updateById(lead);
    }

    /** 入池：线索置 RECYCLED、清空负责人、写入池记录（调度器内部调用，不做角色校验） */
    private void poolIn(Lead lead, String reason, String remark) {
        lead.setStatus(Lead.ST_RECYCLED);
        lead.setPoolFlag("1");
        lead.setOwnerId(null);
        lead.setOwnerName(null);
        lead.setAssignedAt(null);
        lead.setRemindAt(null);
        leadDao.updateById(lead);

        LeadPool pool = new LeadPool();
        pool.setLeadId(lead.getId());
        pool.setReason(reason);
        pool.setGradeAtEntry(lead.getGrade());
        pool.setPooledAt(LocalDateTime.now());
        pool.setStatus(LeadPool.ST_PENDING);
        pool.setRemark(remark);
        poolDao.insert(pool);
    }

    private void closePendingPool(String leadId, String ownerId) {
        LeadPool pending = poolDao.selectPendingByLead(leadId);
        if (pending != null) {
            pending.setStatus(LeadPool.ST_ASSIGNED);
            pending.setAssignedTo(ownerId);
            pending.setAssignedBy(currentUser());
            pending.setAssignedAt(LocalDateTime.now());
            poolDao.updateById(pending);
        }
    }

    // ---------- 调度（FR-4.8-1-3 / 1-4 异常） ----------

    @Override
    @Transactional
    public Map<String, Integer> sweep() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime remindBefore = minusWorkdays(now, REMIND_WORKDAYS);
        int reminded = 0;
        int recycled = 0;

        List<Lead> open = leadDao.selectList(new LambdaQueryWrapper<Lead>()
                .eq(Lead::getStatus, Lead.ST_OPEN)
                .orderByAsc(Lead::getCreateDate)
                .last("LIMIT 500"));
        for (Lead lead : open) {
            if (isBlank(lead.getOwnerId())) {
                continue; // 未分配的线索不参与提醒与回收（回收基准从分配起算）
            }
            LocalDateTime since = lead.getLastFollowupAt() != null
                    ? lead.getLastFollowupAt()
                    : (lead.getAssignedAt() != null ? lead.getAssignedAt() : lead.getCreateDate());
            if (since == null) {
                continue;
            }
            // 1) 分配后 3 工作日内未跟进 → 提醒销售经理（每日最多记一次）
            boolean noFollowSinceAssign = lead.getLastFollowupAt() == null
                    || (lead.getAssignedAt() != null && lead.getLastFollowupAt().isBefore(lead.getAssignedAt()));
            if (noFollowSinceAssign && lead.getAssignedAt() != null
                    && lead.getAssignedAt().isBefore(remindBefore)
                    && (lead.getRemindAt() == null || lead.getRemindAt().isBefore(remindBefore))) {
                lead.setRemindAt(now);
                leadDao.updateById(lead);
                reminded++;
            }
            // 2) 超过 30 天未跟进 → 自动回收至线索池
            if (ChronoUnit.DAYS.between(since, now) > RECYCLE_DAYS) {
                poolIn(lead, LeadPool.RS_RECYCLE, RECYCLE_DAYS + " 天未跟进自动回收（FR-4.8-1-4）");
                recycled++;
            }
        }

        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("reminded", reminded);
        out.put("recycled", recycled);
        log.info("线索调度完成：提醒 {} 条，回收 {} 条", reminded, recycled);
        return out;
    }

    // ---------- 工具 ----------

    private Lead require(String id) {
        Lead lead = leadDao.selectById(id);
        if (lead == null) {
            throw new ServiceException(404, "线索不存在：" + id);
        }
        return lead;
    }

    private String nextLeadNo() {
        String prefix = "LD" + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : leadDao.selectNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    /** 回退 n 个工作日（跳过周六、周日） */
    private LocalDateTime minusWorkdays(LocalDateTime from, int n) {
        LocalDateTime cur = from;
        int done = 0;
        while (done < n) {
            cur = cur.minusDays(1);
            DayOfWeek dow = cur.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                done++;
            }
        }
        return cur;
    }

    private boolean isValidChannel(String channel) {        for (String c : Lead.CHANNELS) {
            if (c.equalsIgnoreCase(channel.trim())) {
                return true;
            }
        }
        return false;
    }

    private void requireText(String v, String name) {
        if (isBlank(v)) {
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
        String id = com.erp.util.SecurityUtils.getCurrentUserId();
        return id == null ? "system" : id;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String nvl(String s, String def) {
        return isBlank(s) ? def : s;
    }
}
