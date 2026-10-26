package com.erp.service.impl.crm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.crm.CrmAssignRuleDao;
import com.erp.dao.crm.CrmSalesProfileDao;
import com.erp.dao.crm.LeadDao;
import com.erp.dao.crm.LeadPoolDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.crm.CrmAssignRule;
import com.erp.entity.crm.CrmSalesProfile;
import com.erp.entity.crm.Lead;
import com.erp.entity.crm.LeadPool;
import com.erp.service.crm.LeadAutoAssignService;
import com.erp.service.system.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 线索自动分配实现（add-lead-auto-assign，FR-4.8-1-3）。
 * 路由口径：A→资深集内「在手最少」轮询；B/C→规则优先级首条命中目标集轮询，
 * 无命中/字段空回落全员「在手最少」；已分配与池内线索 SKIP；改派/认领不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadAutoAssignServiceImpl implements LeadAutoAssignService {

    public static final String SRC_AUTO = "AUTO";
    public static final String SRC_RULE = "RULE";
    public static final String SRC_FALLBACK = "FALLBACK";
    public static final String BIZ_LEAD_AUTO_ASSIGN = "LEAD_AUTO_ASSIGN";

    private final LeadDao leadDao;
    private final CrmSalesProfileDao profileDao;
    private final CrmAssignRuleDao ruleDao;
    private final LeadPoolDao poolDao;
    private final SysUserRoleDao userRoleDao;
    private final NoticeService noticeService;

    // ================================================================
    // 路由
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> route(Lead lead) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leadId", lead.getId());
        if (!isBlank(lead.getOwnerId()) || "1".equals(lead.getPoolFlag())) {
            out.put("assigned", false);
            out.put("reason", "SKIP_ASSIGNED"); // 已分配/池内不重路由
            return out;
        }
        if (Lead.ST_CONVERTED.equals(lead.getStatus()) || Lead.ST_CLOSED.equals(lead.getStatus())
                || isBlank(lead.getGrade())) {
            out.put("assigned", false);
            out.put("reason", "SKIP_STATUS");
            return out;
        }
        String grade = lead.getGrade().trim();
        List<String> candidates;
        String source;
        String warning = null;
        if ("A".equals(grade)) {
            List<String> seniors = seniorAccounts();
            if (seniors.isEmpty()) {
                candidates = salesAccounts();
                source = SRC_FALLBACK;
                warning = "L4 资深名单为空，请在 11.1.2 分配规则页维护（A 级已按全员最少在手回落）";
            } else {
                candidates = seniors;
                source = SRC_AUTO;
            }
        } else {
            CrmAssignRule hit = matchRule(grade, lead.getRegion(), lead.getIndustry());
            if (hit == null) {
                candidates = salesAccounts();
                source = SRC_FALLBACK;
            } else {
                candidates = splitAccounts(hit.getTargetEmpIds());
                source = SRC_RULE;
            }
        }
        if (candidates.isEmpty()) {
            out.put("assigned", false);
            out.put("reason", "NO_CANDIDATE");
            out.put("warning", "系统内无可用销售账号（ROLE_SALES），线索保持未分配");
            return out;
        }
        String pick = pickLeastBusy(candidates);
        if (pick == null) {
            out.put("assigned", false);
            out.put("reason", "NO_CANDIDATE");
            return out;
        }
        assignInternal(lead, pick, source);
        notify(lead, pick, source, grade);
        out.put("assigned", true);
        out.put("ownerId", pick);
        out.put("source", source);
        if (warning != null) {
            out.put("warning", warning);
        }
        log.info("lead auto assigned: {} grade={} {} -> {}", lead.getLeadNo(), grade, source, pick);
        return out;
    }

    // ================================================================
    // 资深名单与规则配置
    // ================================================================

    @Override
    public List<CrmSalesProfile> seniorList() {
        return profileDao.selectList(new LambdaQueryWrapper<CrmSalesProfile>()
                .eq(CrmSalesProfile::getDelFlag, "0")
                .orderByDesc(CrmSalesProfile::getIsSenior)
                .orderByAsc(CrmSalesProfile::getEmpId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSeniors(List<Map<String, String>> seniors) {
        // 全量覆盖语义：先清全部资深标记，再按提交集合置 1
        profileDao.update(null, new LambdaUpdateWrapper<CrmSalesProfile>()
                .eq(CrmSalesProfile::getIsSenior, "1")
                .set(CrmSalesProfile::getIsSenior, "0"));
        for (Map<String, String> item : seniors == null ? List.<Map<String, String>>of() : seniors) {
            String empId = item.get("empId");
            if (isBlank(empId)) {
                continue;
            }
            CrmSalesProfile p = profileDao.selectOne(new LambdaQueryWrapper<CrmSalesProfile>()
                    .eq(CrmSalesProfile::getEmpId, empId.trim())
                    .last("LIMIT 1"));
            boolean isNew = p == null;
            if (isNew) {
                p = new CrmSalesProfile();
                p.setId(uuid());
                p.setEmpId(empId.trim());
                p.setCreateBy(currentUser());
            }
            p.setEmpName(item.get("empName"));
            p.setIsSenior("1");
            p.setRemark(item.get("remark"));
            p.setUpdateBy(currentUser());
            p.setUpdateDate(LocalDateTime.now());
            if (isNew) {
                profileDao.insert(p);
            } else {
                profileDao.updateById(p);
            }
        }
    }

    @Override
    public List<CrmAssignRule> ruleList() {
        return ruleDao.selectList(new LambdaQueryWrapper<CrmAssignRule>()
                .eq(CrmAssignRule::getDelFlag, "0")
                .orderByAsc(CrmAssignRule::getPriority)
                .orderByAsc(CrmAssignRule::getGrade));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CrmAssignRule saveRule(CrmAssignRule rule) {
        String grade = rule.getGrade() == null ? null : rule.getGrade().trim();
        if (!"A".equals(grade) && !"B".equals(grade) && !"C".equals(grade)) {
            throw new ServiceException(422, "适用等级非法（A/B/C）");
        }
        if (isBlank(rule.getTargetEmpIds())) {
            throw new ServiceException(422, "目标销售集必填");
        }
        rule.setGrade(grade);
        rule.setTargetEmpIds(rule.getTargetEmpIds().trim());
        if (rule.getPriority() == null) {
            rule.setPriority(100);
        }
        if (isBlank(rule.getEnabled())) {
            rule.setEnabled("1");
        }
        boolean isNew = isBlank(rule.getId());
        if (isNew) {
            rule.setId(uuid());
            rule.setCreateBy(currentUser());
        } else {
            CrmAssignRule stored = ruleDao.selectById(rule.getId());
            if (stored == null) {
                throw new ServiceException(404, "规则不存在：" + rule.getId());
            }
            rule.setCreateBy(stored.getCreateBy());
            rule.setCreateDate(stored.getCreateDate());
        }
        rule.setUpdateBy(currentUser());
        rule.setUpdateDate(LocalDateTime.now());
        if (isNew) {
            ruleDao.insert(rule);
        } else {
            ruleDao.updateById(rule);
        }
        return rule;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(String id) {
        CrmAssignRule rule = ruleDao.selectById(id);
        if (rule == null) {
            throw new ServiceException(404, "规则不存在：" + id);
        }
        ruleDao.deleteById(id); // @TableLogic 逻辑删
        log.info("assign rule deleted: {} {} {}", id, rule.getGrade(), rule.getTargetEmpIds());
    }

    // ================================================================
    // internals
    // ================================================================

    /** 规则匹配：启用中、等级相符、区域/行业条件为空=通配；按优先级升序首条命中 */
    private CrmAssignRule matchRule(String grade, String region, String industry) {
        for (CrmAssignRule r : ruleDao.selectList(new LambdaQueryWrapper<CrmAssignRule>()
                .eq(CrmAssignRule::getDelFlag, "0")
                .eq(CrmAssignRule::getEnabled, "1")
                .eq(CrmAssignRule::getGrade, grade)
                .orderByAsc(CrmAssignRule::getPriority))) {
            if (!condHit(r.getRegion(), region) || !condHit(r.getIndustry(), industry)) {
                continue;
            }
            return r;
        }
        return null;
    }

    private static boolean condHit(String ruleCond, String leadVal) {
        if (isBlank(ruleCond)) {
            return true; // 通配
        }
        return !isBlank(leadVal) && ruleCond.trim().equalsIgnoreCase(leadVal.trim());
    }

    /** 在手线索最少者优先（在手=未转化/未关闭的已分配线索），平手按账号升序 */
    private String pickLeastBusy(List<String> accounts) {
        String pick = null;
        long min = Long.MAX_VALUE;
        for (String acc : accounts) {
            if (isBlank(acc)) {
                continue;
            }
            Long cnt = leadDao.selectCount(new LambdaQueryWrapper<Lead>()
                    .eq(Lead::getOwnerId, acc.trim())
                    .notIn(Lead::getStatus, Lead.ST_CONVERTED, Lead.ST_CLOSED));
            long c = cnt == null ? 0 : cnt;
            if (c < min || (c == min && pick != null && acc.trim().compareTo(pick) < 0)) {
                min = c;
                pick = acc.trim();
            }
        }
        return pick;
    }

    /** 自动分配落库（免权限的内部 assign，语义与 LeadServiceImpl.assign 一致） */
    private void assignInternal(Lead lead, String ownerId, String source) {
        lead.setOwnerId(ownerId);
        lead.setOwnerName(ownerId);
        lead.setAssignedAt(LocalDateTime.now());
        lead.setRemindAt(null);
        lead.setPoolFlag("0");
        if (Lead.ST_RECYCLED.equals(lead.getStatus())) {
            lead.setStatus(Lead.ST_OPEN);
        }
        lead.setRemark((lead.getRemark() == null ? "" : lead.getRemark() + " | ")
                + "自动分配(" + source + ")");
        leadDao.updateById(lead);
        // 出池：PENDING 池记录置 ASSIGNED（与 closePendingPool 语义一致）
        poolDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<LeadPool>()
                .eq(LeadPool::getLeadId, lead.getId())
                .eq(LeadPool::getStatus, LeadPool.ST_PENDING)
                .set(LeadPool::getStatus, LeadPool.ST_ASSIGNED)
                .set(LeadPool::getAssignedTo, ownerId)
                .set(LeadPool::getAssignedAt, LocalDateTime.now()));
    }

    private void notify(Lead lead, String owner, String source, String grade) {
        String detail = "新线索自动分配给你：" + lead.getLeadNo() + " " + lead.getCompanyName()
                + "（等级 " + grade + "，来源 " + source + "，预期金额 "
                + (lead.getExpectAmount() == null ? "-" : lead.getExpectAmount()) + "）";
        try {
            noticeService.push(null, owner, "线索自动分配", detail, BIZ_LEAD_AUTO_ASSIGN, lead.getId());
            noticeService.push("ROLE_SALES", null, "线索自动分配-" + lead.getLeadNo(), detail,
                    BIZ_LEAD_AUTO_ASSIGN, lead.getId());
        } catch (Exception e) {
            log.warn("lead auto assign notify failed {}: {}", lead.getId(), e.getMessage());
        }
    }

    private List<String> seniorAccounts() {
        List<String> out = new ArrayList<>();
        for (CrmSalesProfile p : profileDao.selectList(new LambdaQueryWrapper<CrmSalesProfile>()
                .eq(CrmSalesProfile::getDelFlag, "0")
                .eq(CrmSalesProfile::getIsSenior, "1"))) {
            if (!isBlank(p.getEmpId())) {
                out.add(p.getEmpId().trim());
            }
        }
        return out;
    }

    /** 销售全集：ROLE_SALES 启用账号（登录账号即 ownerId 语义） */
    private List<String> salesAccounts() {
        return userRoleDao.selectUsernamesByRoleCode("ROLE_SALES");
    }

    private static List<String> splitAccounts(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!isBlank(s)) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String currentUser() {
        try {
            return org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
