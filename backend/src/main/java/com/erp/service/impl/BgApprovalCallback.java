package com.erp.service.impl;

import com.erp.dao.system.BgRequestDao;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.BgRequest;
import com.erp.service.GrantLedgerService;
import com.erp.service.approval.ApprovalCallback;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Break-Glass 审批回调（add-break-glass-console design D3，bizType=BreakGlassRequest）。
 * 同事务：双签全过 → 立即授予台账（SOURCE=BREAK_GLASS，EXPIRE_AT=签发+时长，requireWindow=false
 * 豁免窗口——BG 即窗口外合规通道）+ 申请单 ACTIVE；驳回 → REJECTED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BgApprovalCallback implements ApprovalCallback {

    private final BgRequestDao requestDao;
    private final GrantLedgerService grantLedgerService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BreakGlassServiceImpl.BIZ_BREAK_GLASS);
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        BgRequest req = requestDao.selectById(instance.getBizId());
        if (req == null || !BgRequest.ST_PENDING.equals(req.getStatus())) {
            return; // 幂等
        }
        List<String> roles = parse(req.getRolesJson());
        int hours = req.getDurationHours() == null ? 2 : req.getDurationHours();
        // 生效（豁免窗口：requireWindow=false；台账 SOURCE=BREAK_GLASS）
        Map<String, Object> out = grantLedgerService.activate(req.getId(), req.getTargetUserId(),
                roles, BreakGlassServiceImpl.SOURCE_BREAK_GLASS,
                LocalDateTime.now().plusHours(hours), false, SecurityUtils.getCurrentUserId());
        @SuppressWarnings("unchecked")
        List<String> grantIds = (List<String>) out.get("grantIds");
        req.setStatus(BgRequest.ST_ACTIVE);
        req.setGrantId(grantIds == null || grantIds.isEmpty() ? null : grantIds.get(0));
        req.setUpdateBy("approval");
        req.setUpdateDate(LocalDateTime.now());
        requestDao.updateById(req);
        log.info("break-glass request {} activated, {}h, ledger {}", req.getId(), hours,
                req.getGrantId());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        BgRequest req = requestDao.selectById(instance.getBizId());
        if (req == null || !BgRequest.ST_PENDING.equals(req.getStatus())) {
            return;
        }
        req.setStatus(BgRequest.ST_REJECTED);
        req.setUpdateBy("approval");
        req.setUpdateDate(LocalDateTime.now());
        requestDao.updateById(req);
    }

    private List<String> parse(String json) {
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("模板角色集合反序列化失败", e);
        }
    }
}
