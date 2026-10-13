package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mrp.TrialApplyDao;
import com.erp.dao.system.ApprovalInstanceDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.TrialApply;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.mrp.TrialApplyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 试产申请单实现（change add-trial-production-management，spec trial-order）。
 * 编号 TA-YYYYMMDD-NNN（同 MO_NO 冲突递增模式）；提交进 TrialApply 审批（单节点工艺主管）；
 * 回调置 APPROVED / 回 DRAFT（change add-trial-production-management design D10 权限面）。
 */
@Slf4j
@Service
public class TrialApplyServiceImpl implements TrialApplyService {

    /** 试产申请审批 BIZ_TYPE（approval-workflow 底座；bizId = 申请单号） */
    public static final String BIZ_TRIAL_APPLY = "TrialApply";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Set<String> STAGES = Set.of(TrialApply.EVT, TrialApply.DVT, TrialApply.PVT);

    private final TrialApplyDao applyDao;
    private final MdmItemDao mdmItemDao;
    private final ApprovalInstanceDao approvalInstanceDao;
    private final ApprovalEngine approvalEngine;
    private final com.erp.dao.mrp.MrpMoDao moDao;
    private final com.erp.dao.mrp.TrialSummaryDao summaryDao;

    public TrialApplyServiceImpl(TrialApplyDao applyDao, MdmItemDao mdmItemDao,
                                 ApprovalInstanceDao approvalInstanceDao,
                                 com.erp.dao.mrp.MrpMoDao moDao,
                                 com.erp.dao.mrp.TrialSummaryDao summaryDao,
                                 @org.springframework.context.annotation.Lazy ApprovalEngine approvalEngine) {
        this.applyDao = applyDao;
        this.mdmItemDao = mdmItemDao;
        this.approvalInstanceDao = approvalInstanceDao;
        this.moDao = moDao;
        this.summaryDao = summaryDao;
        this.approvalEngine = approvalEngine;
    }

    /**
     * 阶段闸口（design D10，spec「阶段推进闸口」）：DVT 前置 EVT、PVT 前置 DVT——
     * 同产品上一阶段试产工单的总结评审须 APPROVED，否则 422「上一阶段评审未通过」。
     * 上一阶段无任何试产工单 → 放行（首阶段无前置；EVT 无前置）。
     */
    private void enforceStageGate(String stage, String productCode) {
        String prev = "DVT".equals(stage) ? "EVT" : ("PVT".equals(stage) ? "DVT" : null);
        if (prev == null) {
            return;
        }
        List<com.erp.entity.mrp.MrpMo> prevMos = moDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.mrp.MrpMo>()
                        .eq(com.erp.entity.mrp.MrpMo::getProductCode, productCode)
                        .eq(com.erp.entity.mrp.MrpMo::getMoType, com.erp.entity.mrp.MrpMo.MO_TYPE_TRIAL)
                        .eq(com.erp.entity.mrp.MrpMo::getTrialStage, prev));
        if (prevMos.isEmpty()) {
            return; // 上一阶段未开单 → 无评审前置
        }
        for (com.erp.entity.mrp.MrpMo prevMo : prevMos) {
            com.erp.entity.mrp.TrialSummary s = summaryDao.selectOne(
                    new LambdaQueryWrapper<com.erp.entity.mrp.TrialSummary>()
                            .eq(com.erp.entity.mrp.TrialSummary::getMoId, prevMo.getId())
                            .last("LIMIT 1"));
            if (s == null || !com.erp.entity.mrp.TrialSummary.ST_APPROVED.equals(s.getStatus())) {
                throw new ServiceException(422, "阶段闸口：上一阶段（" + prev + "）试产工单 "
                        + prevMo.getMoNo() + " 的总结评审未通过（当前 "
                        + (s == null ? "未建总结单" : s.getStatus())
                        + "），通过后方可申请 " + stage + " 试产");
            }
        }
    }

    @Override
    @Transactional
    public TrialApply create(TrialApply head) {
        requireWriter("创建试产申请");
        if (head == null) {
            throw new ServiceException(422, "申请单必填");
        }
        // FR-4.5-8-1 异常「阶段缺失或目标不明确 → 退回补充」：落为提交前校验
        String stage = head.getTrialStage() == null ? "" : head.getTrialStage().trim().toUpperCase();
        if (!STAGES.contains(stage)) {
            throw new ServiceException(422, "试产阶段必填（EVT/DVT/PVT）");
        }
        if (head.getGoal() == null || head.getGoal().isBlank()) {
            throw new ServiceException(422, "验证目标必填（试产阶段或目标不明确 → 退回补充，FR-4.5-8-1）");
        }
        if (head.getQty() == null || head.getQty().signum() <= 0) {
            throw new ServiceException(422, "试产数量必须大于 0");
        }
        String productCode = head.getProductCode() == null ? "" : head.getProductCode().trim();
        if (productCode.isEmpty()) {
            throw new ServiceException(422, "产品编码必填");
        }
        MdmItem item = mdmItemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, productCode).last("LIMIT 1"));
        if (item == null || !"1".equals(item.getStatus())) {
            throw new ServiceException(422, "产品不存在或已停用：" + productCode);
        }
        if (!"MAKE".equals(item.getPurchaseType())) {
            throw new ServiceException(422, "仅自制件（MAKE）可试产：" + productCode);
        }

        // 阶段闸口（design D10）：DVT/PVT 申请前置同产品上一阶段试产总结评审 APPROVED
        enforceStageGate(stage, productCode);

        TrialApply row = new TrialApply();
        row.setTrialStage(stage);
        row.setProductCode(productCode);
        row.setProductName(item.getItemName());
        row.setQty(head.getQty());
        row.setGoal(head.getGoal().trim());
        row.setStatus(TrialApply.ST_DRAFT);
        insertResolvingConflict(row);
        log.info("试产申请建档：{} 阶段={} 产品={} 数量={}", row.getTrialNo(), stage, productCode, row.getQty());
        return row;
    }

    @Override
    @Transactional
    public TrialApply submit(String trialNo) {
        requireWriter("提交试产申请审批");
        TrialApply row = require(trialNo);
        if (TrialApply.ST_PENDING.equals(row.getStatus())) {
            throw new ServiceException(422, "申请单重复提交（当前 PENDING）：" + trialNo);
        }
        if (!TrialApply.ST_DRAFT.equals(row.getStatus())) {
            throw new ServiceException(422,
                    "仅草稿状态可提交审批（当前 " + row.getStatus() + "）：" + trialNo);
        }
        // 幂等：已有 PENDING 实例直接复用（并发双提交兜底）
        ApprovalInstance pending = approvalInstanceDao.selectOne(
                new LambdaQueryWrapper<ApprovalInstance>()
                        .eq(ApprovalInstance::getBizType, BIZ_TRIAL_APPLY)
                        .eq(ApprovalInstance::getBizId, trialNo)
                        .eq(ApprovalInstance::getStatus, "PENDING")
                        .last("LIMIT 1"));
        ApprovalInstance inst = pending != null ? pending : approvalEngine.submit(
                BIZ_TRIAL_APPLY, trialNo,
                "试产申请审批：" + trialNo + "（" + row.getTrialStage() + " " + row.getProductName() + "）",
                null, List.of(List.of(ApprovalNodeSpec.sign("ROLE_PROCESS_MGR", "工艺主管"))));
        applyDao.update(null, new LambdaUpdateWrapper<TrialApply>()
                .eq(TrialApply::getTrialNo, trialNo)
                .eq(TrialApply::getStatus, TrialApply.ST_DRAFT)
                .set(TrialApply::getStatus, TrialApply.ST_PENDING)
                .set(TrialApply::getApprovalId, inst.getId())
                .set(TrialApply::getSubmitBy, currentOperator())
                .set(TrialApply::getSubmitAt, LocalDateTime.now()));
        log.info("试产申请提交审批：{} apprNo={}", trialNo, inst.getApprNo());
        return require(trialNo);
    }

    @Override
    public TrialApply get(String trialNo) {
        return require(trialNo);
    }

    @Override
    public List<TrialApply> list(String trialStage, String status, String productCode) {
        requireWriter("查询试产申请");
        return applyDao.selectList(new LambdaQueryWrapper<TrialApply>()
                .eq(trialStage != null && !trialStage.isBlank(), TrialApply::getTrialStage,
                        trialStage == null ? null : trialStage.trim().toUpperCase())
                .eq(status != null && !status.isBlank(), TrialApply::getStatus, status)
                .eq(productCode != null && !productCode.isBlank(), TrialApply::getProductCode, productCode)
                .orderByDesc(TrialApply::getCreateDate));
    }

    @Override
    @Transactional
    public Map<String, Object> onApproval(String trialNo, boolean approved, String opinion, String operator) {
        TrialApply row = require(trialNo);
        if (!TrialApply.ST_PENDING.equals(row.getStatus())) {
            // 幂等：非 PENDING（重复回调）直接短路
            return Map.of("trialNo", trialNo, "status", row.getStatus(), "idempotent", true);
        }
        if (approved) {
            applyDao.update(null, new LambdaUpdateWrapper<TrialApply>()
                    .eq(TrialApply::getId, row.getId())
                    .eq(TrialApply::getStatus, TrialApply.ST_PENDING)
                    .set(TrialApply::getStatus, TrialApply.ST_APPROVED)
                    .set(TrialApply::getApproveBy, operator)
                    .set(TrialApply::getApproveAt, LocalDateTime.now()));
            log.info("试产申请审批通过：{} by {}", trialNo, operator);
        } else {
            // spec 场景「审批驳回回草稿」：意见留痕、可修改重提
            applyDao.update(null, new LambdaUpdateWrapper<TrialApply>()
                    .eq(TrialApply::getId, row.getId())
                    .eq(TrialApply::getStatus, TrialApply.ST_PENDING)
                    .set(TrialApply::getStatus, TrialApply.ST_DRAFT)
                    .set(TrialApply::getRejectReason, opinion == null ? "" : opinion));
            log.info("试产申请审批驳回：{} 意见={} by {}", trialNo, opinion, operator);
        }
        return Map.of("trialNo", trialNo, "approved", approved);
    }

    // ================= 私有助手 =================

    private TrialApply require(String trialNo) {
        if (trialNo == null || trialNo.isBlank()) {
            throw new ServiceException(422, "申请单号必填");
        }
        TrialApply row = applyDao.selectOne(new LambdaQueryWrapper<TrialApply>()
                .eq(TrialApply::getTrialNo, trialNo.trim()).last("LIMIT 1"));
        if (row == null) {
            throw new ServiceException(422, "试产申请单不存在：" + trialNo);
        }
        return row;
    }

    /** TA-YYYYMMDD-NNN 编号（冲突递增，同 MO_NO 模式） */
    private void insertResolvingConflict(TrialApply row) {
        String prefix = "TA-" + LocalDate.now().format(DAY) + "-";
        long today = applyDao.selectCount(new LambdaQueryWrapper<TrialApply>()
                .likeRight(TrialApply::getTrialNo, prefix));
        for (int attempt = 0; attempt < 99; attempt++) {
            row.setTrialNo(prefix + String.format("%03d", today + 1 + attempt));
            try {
                applyDao.insert(row);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                today++;
            }
        }
        throw new ServiceException(422, "试产申请单号分配冲突超限，请稍后重试");
    }

    /** 申请侧写权限（spec trial-order 菜单权限：计划两方 + 工艺两方 + PM + ADMIN） */
    private void requireWriter(String action) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String want : new String[]{"ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_PROCESS_ENG",
                "ROLE_PROCESS_MGR", "ROLE_PM", "ROLE_ADMIN"}) {
            if (auth.getAuthorities().contains(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(want))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action);
    }

    private String currentOperator() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
