package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamWorkOrderDao;
import com.erp.dao.eam.EamWorkOrderTimeDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamWorkOrder;
import com.erp.entity.eam.EamWorkOrderTime;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.eam.EamSafetyLockService;
import com.erp.service.eam.EamWorkOrderService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
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
 * 统一工单（add-asset-maintenance，design D3/D6）。
 * 状态机单点 transition 校验；关闭前置 BR-20 四项 + 成本三构成归集；
 * BR-19 三次同故障评估冻结、BR-18 预算偏差提示（L4）。
 */
@Slf4j
@Service
public class EamWorkOrderServiceImpl implements EamWorkOrderService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 合法状态流转（design D3：单点校验防跳序） */
    private static final Map<String, String> NEXT = Map.of(
            "PENDING", "ASSIGNED",
            "ASSIGNED", "EXECUTING",
            "EXECUTING", "PENDING_ACCEPT",
            "PENDING_ACCEPT", "CLOSED");

    private final EamWorkOrderDao woDao;
    private final EamWorkOrderTimeDao timeDao;
    private final EamAssetCardDao cardDao;
    private final EamSafetyLockService safetyLockService;
    private final SysParamService sysParamService;
    private final ApprovalEngine approvalEngine;
    private final NoticeService noticeService;
    private final com.erp.service.vmi.MaterialIssueService materialIssueService;
    private final JdbcTemplate jdbc;

    public EamWorkOrderServiceImpl(EamWorkOrderDao woDao,
                                   EamWorkOrderTimeDao timeDao,
                                   EamAssetCardDao cardDao,
                                   @Lazy EamSafetyLockService safetyLockService,
                                   SysParamService sysParamService,
                                   ApprovalEngine approvalEngine,
                                   NoticeService noticeService,
                                   com.erp.service.vmi.MaterialIssueService materialIssueService,
                                   JdbcTemplate jdbc) {
        this.woDao = woDao;
        this.timeDao = timeDao;
        this.cardDao = cardDao;
        this.safetyLockService = safetyLockService;
        this.sysParamService = sysParamService;
        this.approvalEngine = approvalEngine;
        this.noticeService = noticeService;
        this.materialIssueService = materialIssueService;
        this.jdbc = jdbc;
    }

    @Override
    public Page<EamWorkOrder> page(long current, long size, String woType, String status, String cardNo) {
        LambdaQueryWrapper<EamWorkOrder> qw = new LambdaQueryWrapper<EamWorkOrder>()
                .eq(woType != null && !woType.isBlank(), EamWorkOrder::getWoType, woType)
                .eq(status != null && !status.isBlank(), EamWorkOrder::getStatus, status)
                .like(cardNo != null && !cardNo.isBlank(), EamWorkOrder::getCardNo, cardNo)
                .orderByDesc(EamWorkOrder::getCreateDate);
        return woDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String woNo) {
        EamWorkOrder wo = require(woNo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workOrder", wo);
        out.put("times", timeDao.selectList(new LambdaQueryWrapper<EamWorkOrderTime>()
                .eq(EamWorkOrderTime::getWoNo, woNo)));
        // 领料明细（按工单号反查 MAINT 领料单）
        out.put("issues", jdbc.queryForList(
                "SELECT ISSUE_NO, STATUS, POST_DATE FROM erp_inv_material_issue "
                        + "WHERE WORK_ORDER_NO = ? AND ISSUE_TYPE = 'MAINT' AND DEL_FLAG = '0'",
                woNo));
        // 子工单
        out.put("subWorkOrders", woDao.selectList(new LambdaQueryWrapper<EamWorkOrder>()
                .eq(EamWorkOrder::getParentWoNo, woNo)));
        // 成本构成
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("spareCost", wo.getSpareCost());
        cost.put("laborCost", wo.getLaborCost());
        cost.put("laborRatePending", "1".equals(wo.getLaborRatePending()));
        cost.put("outsourceAmount", wo.getOutsourceAmount());
        cost.put("totalCost", wo.getTotalCost());
        out.put("cost", cost);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> assign(String woNo, String assignee) {
        EamWorkOrder wo = require(woNo);
        transition(wo, "ASSIGNED");
        if (assignee == null || assignee.isBlank()) {
            throw new ServiceException(422, "须指派维修人员");
        }
        // BR-4.13-16：安全停机未解除 → 阻断派工并展示锁信息（assertNoActiveLock 抛含锁信息 422）
        safetyLockService.assertNoActiveLock(wo.getCardId());

        EamWorkOrder upd = mutable(wo.getId());
        upd.setAssignee(assignee);
        upd.setStatus("ASSIGNED");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        woDao.updateById(upd);
        return row("wo", reload(wo.getId()));
    }

    @Override
    @Transactional
    public Map<String, Object> startExecution(String woNo) {
        EamWorkOrder wo = require(woNo);
        transition(wo, "EXECUTING");
        EamWorkOrder upd = mutable(wo.getId());
        upd.setStatus("EXECUTING");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        woDao.updateById(upd);
        return row("wo", reload(wo.getId()));
    }

    @Override
    @Transactional
    public Map<String, Object> recordExecution(String woNo, String execDesc, String issueDesc, String partsChanged) {
        EamWorkOrder wo = require(woNo);
        if (!"EXECUTING".equals(wo.getStatus())) {
            throw new ServiceException(422, "仅执行中工单可记录执行内容（当前 " + wo.getStatus() + "）");
        }
        EamWorkOrder upd = mutable(wo.getId());
        upd.setExecDesc(execDesc);
        upd.setIssueDesc(issueDesc);
        upd.setPartsChanged(partsChanged);
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        woDao.updateById(upd);
        return row("wo", reload(wo.getId()));
    }

    @Override
    @Transactional
    public Map<String, Object> createSubWorkOrder(String parentWoNo, String desc, String faultSymptom) {
        EamWorkOrder parent = require(parentWoNo);
        if (!"EXECUTING".equals(parent.getStatus())) {
            throw new ServiceException(422, "仅执行中工单可创建子工单（FR-4.13-3-9）");
        }
        safetyLockService.assertNoActiveLock(parent.getCardId());
        EamWorkOrder sub = new EamWorkOrder();
        sub.setWoNo(nextWoNo());
        sub.setWoType(parent.getWoType());
        sub.setSrcType("MANUAL");
        sub.setParentWoNo(parent.getWoNo());
        sub.setCardId(parent.getCardId());
        sub.setCardNo(parent.getCardNo());
        sub.setPlanDesc(desc);
        sub.setFaultSymptom(faultSymptom);
        sub.setFaultTime(LocalDateTime.now());
        sub.setStatus("PENDING");
        sub.setCreateBy(SecurityUtils.getCurrentUserId());
        woDao.insert(sub);
        return row("wo", sub);
    }

    @Override
    @Transactional
    public Map<String, Object> submitForAcceptance(String woNo) {
        EamWorkOrder wo = require(woNo);
        transition(wo, "PENDING_ACCEPT");
        EamWorkOrder upd = mutable(wo.getId());
        upd.setStatus("PENDING_ACCEPT");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        woDao.updateById(upd);
        return row("wo", reload(wo.getId()));
    }

    @Override
    @Transactional
    public Map<String, Object> accept(String woNo, String who, String resultEval) {
        EamWorkOrder wo = require(woNo);
        if (!"PENDING_ACCEPT".equals(wo.getStatus())) {
            throw new ServiceException(422, "仅待验收工单可验收（当前 " + wo.getStatus() + "）");
        }
        if (who == null || !List.of("OP", "ENG").contains(who)) {
            throw new ServiceException(422, "验收人角色必须为 OP/ENG");
        }
        boolean repair = "REPAIR".equals(wo.getWoType());
        EamWorkOrder upd = mutable(wo.getId());
        String user = SecurityUtils.getCurrentUserId();
        if ("OP".equals(who)) {
            upd.setAcceptByOp(user);
        } else {
            upd.setAcceptByEng(user);
            if (resultEval != null && !resultEval.isBlank()) {
                upd.setResultEval(resultEval);
            }
        }
        woDao.updateById(upd);
        EamWorkOrder cur = reload(wo.getId());
        // 双确认齐（REPAIR）或工程师确认（PREVENTIVE）→ 验收完成
        boolean done = repair
                ? cur.getAcceptByOp() != null && cur.getAcceptByEng() != null
                : cur.getAcceptByEng() != null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wo", cur);
        out.put("accepted", done);
        if (done) {
            EamWorkOrder fin = mutable(wo.getId());
            fin.setAcceptDate(LocalDateTime.now());
            woDao.updateById(fin);
            // BR-19：REPAIR 验收通过 → 同故障评估检查
            if (repair) {
                checkOverhaulTrigger(reload(wo.getId()));
            }
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> cancel(String woNo, String reason) {
        EamWorkOrder wo = require(woNo);
        if ("CLOSED".equals(wo.getStatus()) || "CANCELLED".equals(wo.getStatus())) {
            throw new ServiceException(409, "终态工单不可取消");
        }
        EamWorkOrder upd = mutable(wo.getId());
        upd.setStatus("CANCELLED");
        upd.setCancelReason(reason);
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        woDao.updateById(upd);
        return row("wo", reload(wo.getId()));
    }

    @Override
    @Transactional
    public Map<String, Object> close(String woNo) {
        EamWorkOrder wo = require(woNo);
        transition(wo, "CLOSED");

        // ---- BR-4.13-20 四前置校验，返回未完事项清单 ----
        List<String> blockers = new ArrayList<>();
        // ① 未过账领料单
        Integer draftIssues = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_material_issue WHERE WORK_ORDER_NO = ? "
                        + "AND ISSUE_TYPE = 'MAINT' AND STATUS = 'DRAFT' AND DEL_FLAG = '0'",
                Integer.class, woNo);
        if (draftIssues != null && draftIssues > 0) {
            blockers.add("存在 " + draftIssues + " 张未过账领料单");
        }
        // ② 未回填工时（预计>0 且实际=0）
        BigDecimal actualHours = jdbc.queryForObject(
                "SELECT COALESCE(SUM(WORK_HOURS), 0) FROM erp_eam_work_order_time "
                        + "WHERE WO_NO = ? AND DEL_FLAG = '0'", BigDecimal.class, woNo);
        if (wo.getEstHours() != null && wo.getEstHours().signum() > 0
                && (actualHours == null || actualHours.signum() == 0)) {
            blockers.add("预计工时 " + wo.getEstHours() + " 但未回填实际工时");
        }
        // ③ 未闭子工单
        Long openSub = woDao.selectCount(new LambdaQueryWrapper<EamWorkOrder>()
                .eq(EamWorkOrder::getParentWoNo, woNo)
                .notIn(EamWorkOrder::getStatus, "CLOSED", "CANCELLED"));
        if (openSub != null && openSub > 0) {
            blockers.add("存在 " + openSub + " 个未关闭子工单");
        }
        // ④ 外协未上传验收单
        if ("REPAIR".equals(wo.getWoType()) && "OUTSOURCE".equals(wo.getResolvePlan())
                && (wo.getOutsourceAcceptFile() == null || wo.getOutsourceAcceptFile().isBlank())) {
            blockers.add("外协维修未上传外协验收单");
        }
        if (!blockers.isEmpty()) {
            throw new ServiceException(422, "工单关闭被阻断（BR-4.13-20），未完事项：" + String.join("；", blockers));
        }

        // ---- 成本归集（design D6：备件+人工+外协三构成）----
        // 备件成本 = 已过账 MAINT 领料行合计（移动平均价由库存过账回写）
        BigDecimal spare = jdbc.queryForObject(
                "SELECT COALESCE(SUM(l.AMOUNT), 0) FROM erp_inv_material_issue_line l "
                        + "JOIN erp_inv_material_issue i ON i.ID = l.ISSUE_ID "
                        + "WHERE i.WORK_ORDER_NO = ? AND i.ISSUE_TYPE = 'MAINT' "
                        + "AND i.STATUS = 'POSTED' AND i.DEL_FLAG = '0' AND l.DEL_FLAG = '0'",
                BigDecimal.class, woNo);
        spare = spare == null ? BigDecimal.ZERO : spare;

        // 人工成本 = Σ(工时×岗位费率)；缺参 → 0 且标待调整（FR-4.13-3-6 容错）
        Map<String, BigDecimal> rates = laborRates();
        BigDecimal labor = BigDecimal.ZERO;
        boolean pending = false;
        for (EamWorkOrderTime t : timeDao.selectList(new LambdaQueryWrapper<EamWorkOrderTime>()
                .eq(EamWorkOrderTime::getWoNo, woNo))) {
            BigDecimal rate = rateFor(t.getPostType(), rates);
            if (rate == null) {
                pending = true;
                continue;
            }
            labor = labor.add(t.getWorkHours().multiply(rate));
        }
        labor = labor.setScale(2, RoundingMode.HALF_UP);
        BigDecimal outsource = wo.getOutsourceAmount() == null ? BigDecimal.ZERO : wo.getOutsourceAmount();
        BigDecimal total = spare.add(labor).add(outsource).setScale(2, RoundingMode.HALF_UP);

        EamWorkOrder upd = mutable(wo.getId());
        upd.setStatus("CLOSED");
        upd.setSpareCost(spare.setScale(2, RoundingMode.HALF_UP));
        upd.setLaborCost(labor);
        upd.setLaborRatePending(pending ? "1" : "0");
        upd.setTotalCost(total);
        upd.setCloseBy(SecurityUtils.getCurrentUserId());
        upd.setCloseDate(LocalDateTime.now());
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        woDao.updateById(upd);

        EamWorkOrder closed = reload(wo.getId());
        // BR-4.13-18：滚动 12 月维修成本 vs 预算偏差 → L4 资本化评估提示
        checkBudgetDeviation(closed);
        return row("wo", closed);
    }

    // ================= BR-19 重复故障升级 =================

    /**
     * REPAIR 验收后同卡同现象滚动 12 月 CLOSED ≥3 → 评估触发 + 冻结同类工单创建。
     */
    private void checkOverhaulTrigger(EamWorkOrder wo) {
        if (wo.getFaultSymptom() == null || wo.getFaultSymptom().isBlank()) {
            return;
        }
        if ("1".equals(wo.getOverhaulAssessFlag()) && "PENDING_APPROVE".equals(wo.getAssessStatus())) {
            return; // 已触发待评估
        }
        Integer closedCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_eam_work_order WHERE CARD_ID = ? AND FAULT_SYMPTOM = ? "
                        + "AND WO_TYPE = 'REPAIR' "
                        + "AND ACCEPT_DATE >= DATE_SUB(NOW(), INTERVAL 12 MONTH) "
                        + "AND ACCEPT_DATE IS NOT NULL",
                Integer.class, wo.getCardId(), wo.getFaultSymptom());
        if (closedCnt == null || closedCnt < 3) {
            return;
        }
        EamWorkOrder upd = mutable(wo.getId());
        upd.setOverhaulAssessFlag("1");
        upd.setAssessStatus("PENDING_APPROVE");
        woDao.updateById(upd);
        noticeService.push("ROLE_EQUIP_MGR", null, "大修/更换评估任务",
                "资产 " + wo.getCardNo() + " 故障「" + wo.getFaultSymptom()
                        + "」12 个月内维修 " + closedCnt + " 次，触发大修或更换评估（BR-4.13-19）",
                "AssetOverhaul", wo.getWoNo());
        approvalEngine.submit("AssetOverhaul", wo.getId(),
                "大修/更换评估：" + wo.getCardNo() + " " + wo.getFaultSymptom()
                        + "（12 月内 " + closedCnt + " 次）", null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_EQUIP_MGR", "设备主管评估"))));
        log.info("工单 {} 触发大修评估（同故障 {} 次）", wo.getWoNo(), closedCnt);
    }

    /** 创建同类 REPAIR 工单时的冻结校验（评估未归档 → 422，BR-19） */
    @Override
    public void assertNoOverhaulFreeze(String cardId, String faultSymptom) {
        if (faultSymptom == null || faultSymptom.isBlank()) {
            return;
        }
        Integer frozen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_eam_work_order WHERE CARD_ID = ? AND FAULT_SYMPTOM = ? "
                        + "AND OVERHAUL_ASSESS_FLAG = '1' AND ASSESS_STATUS = 'PENDING_APPROVE' "
                        + "AND DEL_FLAG = '0'",
                Integer.class, cardId, faultSymptom);
        if (frozen != null && frozen > 0) {
            throw new ServiceException(422, "该故障现象已触发大修/更换评估且未归档，"
                    + "禁止创建同类维修工单（BR-4.13-19），请先完成评估");
        }
    }

    /** 评估审批通过/驳回回调（结论归档 → 解冻） */
    @Override
    @Transactional
    public Map<String, Object> completeOverhaulAssess(String woId, String assessStatus) {
        EamWorkOrder upd = mutable(woId);
        upd.setAssessStatus(assessStatus);
        upd.setUpdateBy("system");
        woDao.updateById(upd);
        return row("woId", woId);
    }

    // ================= BR-4.13-18 预算偏差 =================

    private void checkBudgetDeviation(EamWorkOrder wo) {
        try {
            // 卡片预算优先，分类兜底（design D6 双层）
            BigDecimal budget = jdbc.queryForObject(
                    "SELECT COALESCE(c.MAINT_BUDGET, cat.MAINT_BUDGET) FROM erp_eam_asset_card c "
                            + "LEFT JOIN erp_eam_asset_category cat ON cat.CATEGORY_CODE = c.CATEGORY_CODE "
                            + "WHERE c.ID = ?", BigDecimal.class, wo.getCardId());
            if (budget == null || budget.signum() <= 0) {
                return; // 未维护预算 → 不判定
            }
            BigDecimal actual = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(TOTAL_COST), 0) FROM erp_eam_work_order "
                            + "WHERE CARD_ID = ? AND STATUS = 'CLOSED' AND DEL_FLAG = '0' "
                            + "AND CLOSE_DATE >= DATE_SUB(NOW(), INTERVAL 12 MONTH)",
                    BigDecimal.class, wo.getCardId());
            actual = actual == null ? BigDecimal.ZERO : actual;
            BigDecimal deviationPct = sysParamService.getAmount("MAINT_BUDGET_DEVIATION",
                    BigDecimal.TEN);
            BigDecimal threshold = budget.multiply(deviationPct).divide(BigDecimal.valueOf(100), 2,
                    RoundingMode.HALF_UP);
            if (actual.subtract(budget).compareTo(threshold) > 0) {
                noticeService.push("ROLE_FIN_ASSET", null, "后续支出资本化评估提示",
                        "资产 " + wo.getCardNo() + " 滚动 12 个月维修成本 ¥" + actual
                                + " 超年度维护预算 ¥" + budget + "（偏差阈值 ¥" + threshold
                                + "，BR-4.13-18），请评估是否资本化",
                        "MaintBudgetDeviation", wo.getCardNo());
                log.info("资产 {} 维修成本偏差超阈值，已推送资本化评估提示", wo.getCardNo());
            }
        } catch (Exception ex) {
            log.warn("预算偏差检查异常（不阻断关闭）：{}", ex.getMessage());
        }
    }

    // ================= 备件领用（BR-4.13-17）=================

    @Override
    @Transactional
    public Map<String, Object> requestSpares(String woNo, List<Map<String, Object>> lines, String overReason) {
        EamWorkOrder wo = require(woNo);
        if (!"EXECUTING".equals(wo.getStatus())) {
            throw new ServiceException(422, "仅执行中工单可领用备件（当前 " + wo.getStatus() + "）");
        }
        if (lines == null || lines.isEmpty()) {
            throw new ServiceException(422, "领用明细必填");
        }
        // 超领校验：逐物料比对工单备件清单快照（BOM_JSON），超出且无原因 → 422 L1（BR-4.13-17）
        Map<String, BigDecimal> bom = parseBom(wo.getBomJson());
        for (Map<String, Object> line : lines) {
            String itemCode = line.get("itemCode") == null ? null : String.valueOf(line.get("itemCode"));
            BigDecimal need;
            try {
                need = new BigDecimal(String.valueOf(line.getOrDefault("qty", "0")));
            } catch (RuntimeException ex) {
                throw new ServiceException(422, "物料 " + itemCode + " 数量格式非法");
            }
            BigDecimal allowed = bom.get(itemCode);
            if (allowed != null && need.compareTo(allowed) > 0
                    && (overReason == null || overReason.isBlank())) {
                throw new ServiceException(422, "物料 " + itemCode + " 本次领用 " + need
                        + " 超出工单备件清单 " + allowed + "，超领须填写原因（BR-4.13-17 L1 阻断）");
            }
        }
        // 生成 MAINT 领料单（DRAFT，库存 FIFO 配批；过账由仓库执行，移动平均价回写行）
        EamAssetCard card = cardDao.selectById(wo.getCardId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workOrderNo", woNo);
        payload.put("dept", card == null ? null : card.getDeptName());
        payload.put("purpose", "工单 " + woNo + " 备件领用"
                + ((overReason == null || overReason.isBlank()) ? "" : "（超领：" + overReason + "）"));
        payload.put("lines", lines);
        Map<String, Object> out = materialIssueService.createMaintIssue(payload);
        log.info("工单 {} 备件领用建单完成（超领原因：{}）", woNo,
                overReason == null || overReason.isBlank() ? "无" : overReason);
        return out;
    }

    /** 解析备件清单快照 [{itemCode,itemName,qty}] → itemCode→qty */
    private Map<String, BigDecimal> parseBom(String bomJson) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (bomJson == null || bomJson.isBlank()) {
            return out;
        }
        try {
            List<Map<String, Object>> items = JSON.readValue(bomJson,
                    new TypeReference<List<Map<String, Object>>>() { });
            for (Map<String, Object> item : items) {
                out.put(String.valueOf(item.get("itemCode")),
                        new BigDecimal(String.valueOf(item.getOrDefault("qty", "0"))));
            }
        } catch (Exception ex) {
            log.warn("备件清单快照解析失败：{}", ex.getMessage());
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> recordTime(String woNo, String worker, String postType,
                                          BigDecimal workHours, String workDate) {
        EamWorkOrder wo = require(woNo);
        if (!"EXECUTING".equals(wo.getStatus()) && !"PENDING_ACCEPT".equals(wo.getStatus())) {
            throw new ServiceException(422, "仅执行中/待验收工单可回填工时（当前 " + wo.getStatus() + "）");
        }
        if (workHours == null || workHours.signum() <= 0) {
            throw new ServiceException(422, "工时数须大于 0");
        }
        if (postType == null || !List.of("ENGR", "TECH", "OTHER").contains(postType)) {
            throw new ServiceException(422, "岗位必须为 ENGR/TECH/OTHER");
        }
        EamWorkOrderTime t = new EamWorkOrderTime();
        t.setWoNo(woNo);
        t.setWorker(worker == null || worker.isBlank() ? SecurityUtils.getCurrentUserId() : worker);
        t.setPostType(postType);
        t.setWorkHours(workHours);
        t.setWorkDate(workDate == null || workDate.isBlank()
                ? LocalDate.now() : LocalDate.parse(workDate));
        t.setCreateBy(SecurityUtils.getCurrentUserId());
        timeDao.insert(t);
        return row("time", t);
    }

    // ================= 内部 =================

    /** 状态机单点校验（design D3：防跳序 422） */
    private void transition(EamWorkOrder wo, String to) {
        String from = wo.getStatus();
        if ("CLOSED".equals(from) || "CANCELLED".equals(from)) {
            throw new ServiceException(422, "终态工单不可流转（当前 " + from + "）");
        }
        String expected = NEXT.get(from);
        if (!to.equals(expected)) {
            throw new ServiceException(422, "工单状态不可从 " + from + " 流转至 " + to
                    + "（应为 " + expected + "，BR-4.13 状态机防跳序）");
        }
    }

    private Map<String, BigDecimal> laborRates() {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        putRate(rates, "ENGR", "EAM_LABOR_RATE_ENG");
        putRate(rates, "TECH", "EAM_LABOR_RATE_TECH");
        putRate(rates, "DEFAULT", "EAM_LABOR_RATE_DEFAULT");
        return rates;
    }

    private void putRate(Map<String, BigDecimal> rates, String key, String param) {
        try {
            String v = sysParamService.getValue(param);
            if (v != null && !v.isBlank()) {
                rates.put(key, new BigDecimal(v.trim()));
            }
        } catch (Exception ignore) {
            // 缺参/非法 → 不放入，触发待调整
        }
    }

    private BigDecimal rateFor(String postType, Map<String, BigDecimal> rates) {
        if ("ENGR".equals(postType)) {
            return rates.get("ENGR");
        }
        if ("TECH".equals(postType)) {
            return rates.get("TECH");
        }
        return rates.get("DEFAULT");
    }

    private EamWorkOrder require(String woNo) {
        EamWorkOrder wo = woDao.selectOne(new LambdaQueryWrapper<EamWorkOrder>()
                .eq(EamWorkOrder::getWoNo, woNo).last("LIMIT 1"));
        if (wo == null) {
            throw new ServiceException(422, "工单不存在：" + woNo);
        }
        return wo;
    }

    private EamWorkOrder reload(String id) {
        return woDao.selectById(id);
    }

    /** 加载实体再改（BaseEntity @Version 乐观锁：新建实体 verNo=0 会导致二次更新静默不命中） */
    private EamWorkOrder mutable(String id) {
        EamWorkOrder e = woDao.selectById(id);
        if (e == null) {
            throw new ServiceException(422, "工单不存在：" + id);
        }
        return e;
    }

    private Map<String, Object> row(String key, Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(key, value);
        return out;
    }

    /** 工单号 WO + yyyyMMdd + - + 4 位流水 */
    private String nextWoNo() {
        String prefix = "WO" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        Integer max = woDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }
}
