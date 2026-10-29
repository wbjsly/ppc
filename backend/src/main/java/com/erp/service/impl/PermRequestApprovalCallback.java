package com.erp.service.impl;

import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.PermRequest;
import com.erp.dao.system.PermRequestDao;
import com.erp.service.ChangeWindowService;
import com.erp.service.GrantLedgerService;
import com.erp.service.approval.ApprovalCallback;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 权限申请审批回调（add-permission-approval-flow design D5/D7，bizType=PermRequest）。
 * 同事务：APPROVED → KIND=EXCEPTION 先全集替换投影 → 立即生效（特权窗口外置
 * APPROVED_PENDING 待窗口，不回滚审批）/ 指定时点置 APPROVED_PENDING 待 sweep；
 * REJECTED → 申请单 REJECTED，角色不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermRequestApprovalCallback implements ApprovalCallback {

    private final PermRequestDao requestDao;
    private final GrantLedgerService grantLedgerService;
    private final ChangeWindowService changeWindowService;
    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(PermRequestServiceImpl.BIZ_PERM_REQUEST);
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        PermRequest req = requestDao.selectById(instance.getBizId());
        if (req == null || !PermRequest.ST_PENDING.equals(req.getStatus())) {
            return; // 幂等
        }
        List<String> roles = parse(req.getRolesJson());
        boolean privileged = PermRequest.RISK_PRIVILEGED.equals(req.getRiskLevel());

        // 特权窗口校验前置：窗口外不改任何状态，置待窗口生效（审批本身不回滚）
        if (privileged && !changeWindowService.isInWindow(LocalDateTime.now())) {
            req.setStatus(PermRequest.ST_APPROVED_PENDING);
            req.setUpdateBy("approval");
            req.setUpdateDate(LocalDateTime.now());
            requestDao.updateById(req);
            log.info("perm request {} approved but awaiting change window", req.getId());
            return;
        }

        // EXCEPTION：审批通过 = 全集生效（adjust-roles 替换语义，design D7）
        if (PermRequest.KIND_EXCEPTION.equals(req.getKind())) {
            replaceProjection(req.getTargetUserId(), roles);
        }

        if (PermRequest.MODE_SCHEDULED.equals(req.getActivationMode())) {
            req.setStatus(PermRequest.ST_APPROVED_PENDING);
            req.setUpdateBy("approval");
            req.setUpdateDate(LocalDateTime.now());
            requestDao.updateById(req);
            return;
        }

        // 立即生效（特权已过窗口校验，此处不再拦）
        int months = req.getValidMonths() == null ? 12 : req.getValidMonths();
        grantLedgerService.activate(req.getId(), req.getTargetUserId(), roles,
                PermRequest.KIND_EXCEPTION.equals(req.getKind()) ? "EXCEPTION" : "NORMAL",
                LocalDateTime.now().plusMonths(months), false,
                SecurityUtils.getCurrentUserId());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        PermRequest req = requestDao.selectById(instance.getBizId());
        if (req == null || !PermRequest.ST_PENDING.equals(req.getStatus())) {
            return;
        }
        req.setStatus(PermRequest.ST_REJECTED);
        req.setUpdateBy("approval");
        req.setUpdateDate(LocalDateTime.now());
        requestDao.updateById(req);
    }

    /** 例外全集替换：删除目标集合之外的角色绑定（激活补齐缺失部分） */
    private void replaceProjection(String targetUserId, List<String> targetRoles) {
        if (targetRoles.isEmpty()) {
            return;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < targetRoles.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        jdbc.update("DELETE ur FROM erp_admin_user_role ur "
                        + "JOIN erp_admin_role r ON r.ID = ur.ROLE_ID "
                        + "WHERE ur.USER_ID = ? AND r.ROLE_CODE NOT IN (" + in + ")",
                concat(targetUserId, targetRoles));
    }

    private static Object[] concat(String userId, List<String> roles) {
        Object[] args = new Object[roles.size() + 1];
        args[0] = userId;
        for (int i = 0; i < roles.size(); i++) {
            args[i + 1] = roles.get(i);
        }
        return args;
    }

    private List<String> parse(String json) {
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("申请单角色集合反序列化失败", e);
        }
    }
}
