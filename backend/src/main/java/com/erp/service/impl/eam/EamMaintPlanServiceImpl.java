package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamMaintPlanDao;
import com.erp.dao.eam.EamSafetyLockDao;
import com.erp.dao.eam.EamWorkOrderDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamMaintPlan;
import com.erp.entity.eam.EamSafetyLock;
import com.erp.entity.eam.EamWorkOrder;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.eam.EamMaintPlanService;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 维护计划（add-asset-maintenance，design D2，FR-4.13-3-1/2）。
 */
@Slf4j
@Service
public class EamMaintPlanServiceImpl implements EamMaintPlanService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EamMaintPlanDao planDao;
    private final EamWorkOrderDao woDao;
    private final EamAssetCardDao cardDao;
    private final EamSafetyLockDao lockDao;
    private final ApprovalEngine approvalEngine;
    private final SysParamService sysParamService;
    private final JdbcTemplate jdbc;

    public EamMaintPlanServiceImpl(EamMaintPlanDao planDao,
                                   EamWorkOrderDao woDao,
                                   EamAssetCardDao cardDao,
                                   EamSafetyLockDao lockDao,
                                   ApprovalEngine approvalEngine,
                                   SysParamService sysParamService,
                                   JdbcTemplate jdbc) {
        this.planDao = planDao;
        this.woDao = woDao;
        this.cardDao = cardDao;
        this.lockDao = lockDao;
        this.approvalEngine = approvalEngine;
        this.sysParamService = sysParamService;
        this.jdbc = jdbc;
    }

    @Override
    public Page<EamMaintPlan> page(long current, long size, String cardNo, String criticalFlag) {
        LambdaQueryWrapper<EamMaintPlan> qw = new LambdaQueryWrapper<EamMaintPlan>()
                .like(cardNo != null && !cardNo.isBlank(), EamMaintPlan::getCardNo, cardNo)
                .eq(criticalFlag != null && !criticalFlag.isBlank(), EamMaintPlan::getCriticalFlag, criticalFlag)
                .orderByDesc(EamMaintPlan::getCreateDate);
        return planDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public Map<String, Object> savePlan(EamMaintPlan plan) {
        if (plan.getCardId() == null || plan.getCardId().isBlank()) {
            throw new ServiceException(422, "资产卡片必选");
        }
        EamAssetCard card = cardDao.selectById(plan.getCardId());
        if (card == null) {
            throw new ServiceException(422, "资产卡片不存在：" + plan.getCardId());
        }
        if (plan.getItemName() == null || plan.getItemName().isBlank()) {
            throw new ServiceException(422, "维护项目必填");
        }
        if (plan.getCycle() == null || !List.of("D", "W", "M", "Q", "Y").contains(plan.getCycle())) {
            throw new ServiceException(422, "维护周期必须为 D/W/M/Q/Y");
        }
        boolean critical = "1".equals(plan.getCriticalFlag());
        if (plan.getId() == null || plan.getId().isBlank()) {
            plan.setPlanNo(nextPlanNo());
            plan.setCardNo(card.getCardNo());
            plan.setApprovalStatus(critical ? "PENDING_APPROVE" : "NOT_REQUIRED");
            plan.setEffectiveFlag("1");
            if (plan.getNextPlanDate() == null) {
                plan.setNextPlanDate(LocalDate.now().plusDays(1));
            }
            plan.setCreateBy(SecurityUtils.getCurrentUserId());
            planDao.insert(plan);
        } else {
            EamMaintPlan old = planDao.selectById(plan.getId());
            if (old == null) {
                throw new ServiceException(422, "计划不存在：" + plan.getId());
            }
            plan.setPlanNo(old.getPlanNo());
            plan.setCardNo(old.getCardNo());
            // 关键标志变化：转关键须重新审批
            if (critical && !"1".equals(old.getCriticalFlag())) {
                plan.setApprovalStatus("PENDING_APPROVE");
            } else {
                plan.setApprovalStatus(old.getApprovalStatus());
            }
            plan.setUpdateBy(SecurityUtils.getCurrentUserId());
            planDao.updateById(plan);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", plan);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> submitApproval(String planId) {
        EamMaintPlan plan = require(planId);
        if (!"1".equals(plan.getCriticalFlag())) {
            throw new ServiceException(422, "非关键设备计划无需审批");
        }
        if (!"PENDING_APPROVE".equals(plan.getApprovalStatus())) {
            throw new ServiceException(409, "计划当前状态不可提交审批：" + plan.getApprovalStatus());
        }
        approvalEngine.submit("MaintPlan", plan.getId(),
                "维护计划审批：" + plan.getPlanNo() + " " + plan.getItemName(), null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_EQUIP_MGR", "设备主管审批"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> approveFromApproval(String planId) {
        EamMaintPlan plan = require(planId);
        plan.setApprovalStatus("APPROVED");
        plan.setUpdateBy("system");
        planDao.updateById(plan);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> rejectFromApproval(String planId, String reason) {
        EamMaintPlan plan = require(planId);
        plan.setApprovalStatus("REJECTED");
        plan.setUpdateBy("system");
        planDao.updateById(plan);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> disable(String planId) {
        EamMaintPlan plan = require(planId);
        plan.setEffectiveFlag("0");
        plan.setUpdateBy(SecurityUtils.getCurrentUserId());
        planDao.updateById(plan);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> generatePlanOrders() {
        int leadDays = 3;
        try {
            String v = sysParamService.getValue("MAINT_ORDER_LEAD_DAYS");
            if (v != null && !v.isBlank()) {
                leadDays = Integer.parseInt(v.trim());
            }
        } catch (Exception ignore) {
            // 参数缺失/非法 → 用默认 3 天
        }
        LocalDate today = LocalDate.now();
        LocalDate until = today.plusDays(leadDays);

        List<EamMaintPlan> due = planDao.selectList(new LambdaQueryWrapper<EamMaintPlan>()
                .eq(EamMaintPlan::getEffectiveFlag, "1")
                .isNotNull(EamMaintPlan::getNextPlanDate)
                .ge(EamMaintPlan::getNextPlanDate, today)
                .le(EamMaintPlan::getNextPlanDate, until));

        List<String> created = new ArrayList<>();
        List<String> postponed = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        List<Map<String, Object>> spareWarnings = new ArrayList<>();

        for (EamMaintPlan plan : due) {
            // 关键设备未审批 → 记录阻断（不整批失败，返回清单）
            if ("1".equals(plan.getCriticalFlag()) && !"APPROVED".equals(plan.getApprovalStatus())) {
                blocked.add(plan.getPlanNo() + "（关键设备计划待审批 " + plan.getApprovalStatus() + "）");
                continue;
            }
            LocalDate planDate = plan.getNextPlanDate();
            // 幂等键 = 计划ID+计划日；已存在有效工单 → 跳过
            String idem = plan.getId() + "@" + planDate;
            Long exist = woDao.selectCount(new LambdaQueryWrapper<EamWorkOrder>()
                    .eq(EamWorkOrder::getIdempotentKey, idem)
                    .ne(EamWorkOrder::getStatus, "CANCELLED"));
            if (exist != null && exist > 0) {
                continue;
            }
            // 停机顺延：该卡有 ACTIVE 挂锁 → 计划日 +1 并留痕，本窗口不建单
            List<EamSafetyLock> locks = lockDao.selectActiveByCard(plan.getCardId());
            if (!locks.isEmpty()) {
                plan.setNextPlanDate(planDate.plusDays(1));
                plan.setRemark("计划日 " + planDate + " 资产安全停机中，顺延至 " + plan.getNextPlanDate());
                plan.setUpdateBy("system");
                planDao.updateById(plan);
                postponed.add(plan.getPlanNo() + "→" + plan.getNextPlanDate());
                continue;
            }
            // 建单
            EamWorkOrder wo = new EamWorkOrder();
            wo.setWoNo(nextWoNo());
            wo.setWoType("PREVENTIVE");
            wo.setSrcType("PLAN");
            wo.setIdempotentKey(idem);
            wo.setPlanId(plan.getId());
            wo.setPlanDate(planDate);
            wo.setCardId(plan.getCardId());
            wo.setCardNo(plan.getCardNo());
            wo.setPlanDesc(plan.getItemName() + (plan.getStandard() != null ? "（标准：" + plan.getStandard() + "）" : ""));
            wo.setBomJson(plan.getSparesJson());
            wo.setEstHours(plan.getEstHours());
            wo.setStatus("PENDING");
            wo.setCreateBy("system");
            woDao.insert(wo);
            created.add(wo.getWoNo());

            // 备件库存不足 → L4 预警收集
            checkSparesShortage(plan.getSparesJson(), wo.getWoNo(), spareWarnings);

            // 推进下次计划日
            plan.setNextPlanDate(advance(planDate, plan.getCycle()));
            plan.setUpdateBy("system");
            planDao.updateById(plan);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", today + "~" + until);
        out.put("created", created);
        out.put("postponed", postponed);
        out.put("blocked", blocked);
        out.put("spareWarnings", spareWarnings);
        if (!blocked.isEmpty()) {
            // 存在未审批关键计划：不整批失败，但把阻断信息带回（FR-4.13-3-2 口径）
            log.warn("计划工单生成存在阻断项：{}", blocked);
        }
        return out;
    }

    // ================= 内部 =================

    private void checkSparesShortage(String sparesJson, String woNo,
                                     List<Map<String, Object>> warnings) {
        if (sparesJson == null || sparesJson.isBlank()) {
            return;
        }
        try {
            List<Map<String, Object>> items = JSON.readValue(sparesJson,
                    new TypeReference<List<Map<String, Object>>>() { });
            for (Map<String, Object> item : items) {
                String itemCode = String.valueOf(item.get("itemCode"));
                BigDecimal need = new BigDecimal(String.valueOf(item.getOrDefault("qty", "0")));
                Long availRow = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(AVAILABLE_QTY), 0) FROM erp_inv_stock WHERE ITEM_CODE = ?",
                        Long.class, itemCode);
                long avail = availRow == null ? 0 : availRow;
                if (avail < need.longValue()) {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("woNo", woNo);
                    w.put("itemCode", itemCode);
                    w.put("required", need);
                    w.put("available", avail);
                    w.put("level", "L4");
                    warnings.add(w);
                }
            }
        } catch (Exception ex) {
            log.warn("备件清单解析失败（不阻断建单）：{}", ex.getMessage());
        }
    }

    /** 周期推进：D+1 / W+7 / M+1月 / Q+3月 / Y+1年 */
    private LocalDate advance(LocalDate date, String cycle) {
        switch (cycle) {
            case "D": return date.plusDays(1);
            case "W": return date.plusWeeks(1);
            case "Q": return date.plusMonths(3);
            case "Y": return date.plusYears(1);
            case "M":
            default: return date.plusMonths(1);
        }
    }

    private EamMaintPlan require(String id) {
        EamMaintPlan plan = planDao.selectById(id);
        if (plan == null) {
            throw new ServiceException(422, "维护计划不存在：" + id);
        }
        return plan;
    }

    /** 计划号 MP + yyyyMMdd + - + 4 位流水 */
    private String nextPlanNo() {
        String prefix = "MP" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        Integer max = planDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    /** 工单号 WO + yyyyMMdd + - + 4 位流水 */
    private String nextWoNo() {
        String prefix = "WO" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        Integer max = woDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }
}
