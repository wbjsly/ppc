package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.sd.FrameworkDao;
import com.erp.dao.sd.FrameworkLineDao;
import com.erp.entity.sd.Framework;
import com.erp.entity.sd.FrameworkLine;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 框架协议变更/终止审批回调（BIZ_TYPE = Framework，S-4.3-11，tasks 13.5）。
 * 通过 → 执行 PENDING_CHANGE：TOTAL 重算行总量与协议总量（复验不低于已发量）、
 * PRICE 回写锁定价（前后值入变更历史）、TERMINATE 置 TERMINATED 冻结后续发货；
 * 驳回 → 丢弃 pending 并留痕。仅注入 DAO（不依赖 FrameworkService，防循环）。
 */
@Slf4j
@Component
public class FrameworkApprovalCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "Framework";

    private final FrameworkDao fwDao;
    private final FrameworkLineDao lineDao;
    private final ObjectMapper mapper = new ObjectMapper();

    public FrameworkApprovalCallback(FrameworkDao fwDao, FrameworkLineDao lineDao) {
        this.fwDao = fwDao;
        this.lineDao = lineDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_TYPE);
    }

    @Override
    @SuppressWarnings("unchecked")
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        Framework fw = fwDao.selectById(instance.getBizId());
        if (fw == null) {
            log.warn("framework approved but record missing: {}", instance.getBizId());
            return;
        }
        Map<String, Object> change = read(fw.getPendingChange());
        if (change.isEmpty()) {
            fw.setApprovalId(null);
            fwDao.updateById(fw);
            return;
        }
        String type = String.valueOf(change.get("type"));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) change.getOrDefault("lines", List.of());
        List<Map<String, Object>> before = (List<Map<String, Object>>) change.getOrDefault("before", List.of());
        String execNote;

        if ("TOTAL".equals(type)) {
            // 总量调整：复验新量不低于已发量（审批期间可能已发货），重算协议总量
            for (Map<String, Object> b : before) {
                FrameworkLine l = lineDao.selectById(String.valueOf(b.get("lineId")));
                if (l == null) {
                    continue;
                }
                BigDecimal after = new BigDecimal(String.valueOf(b.get("after")));
                if (after.compareTo(nvl(l.getShippedQty())) < 0) {
                    throw new IllegalStateException("行 " + l.getLineNo() + " 调整后总量 "
                            + after.stripTrailingZeros().toPlainString()
                            + " 低于已发量 " + nvl(l.getShippedQty()).stripTrailingZeros().toPlainString()
                            + "，变更失效（S-4.3-11）");
                }
                l.setTotalQty(after);
                lineDao.updateById(l);
            }
            BigDecimal total = BigDecimal.ZERO;
            for (FrameworkLine l : lines(fw.getId())) {
                total = total.add(nvl(l.getTotalQty()));
            }
            fw.setTotalQty(total);
            execNote = "总量调整已生效（重算协议总量 " + total.stripTrailingZeros().toPlainString() + "）";
        } else if ("PRICE".equals(type)) {
            for (Map<String, Object> b : before) {
                FrameworkLine l = lineDao.selectById(String.valueOf(b.get("lineId")));
                if (l == null) {
                    continue;
                }
                l.setUnitPrice(new BigDecimal(String.valueOf(b.get("after"))));
                lineDao.updateById(l);
            }
            execNote = "单价重谈已生效（锁定价更新，前后值入变更历史）";
        } else if ("TERMINATE".equals(type)) {
            fw.setStatus(Framework.ST_TERMINATED);
            fw.setTerminateReason(String.valueOf(change.get("reason")));
            fw.setTerminateBy(com.erp.util.SecurityUtils.getCurrentUserId());
            fw.setTerminateAt(LocalDateTime.now());
            execNote = "提前终止已生效，未发余量冻结（S-4.3-11）";
        } else {
            execNote = "未知变更类型 " + type;
        }

        appendLog(fw, change, "APPROVED", execNote);
        // MP updateById 忽略 null 字段 → PENDING_CHANGE/APPROVAL_ID 必须显式置 NULL
        var uw = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Framework>()
                .eq(Framework::getId, fw.getId())
                .set(Framework::getPendingChange, null)
                .set(Framework::getApprovalId, null)
                .set(Framework::getTotalQty, fw.getTotalQty())
                .set(Framework::getStatus, fw.getStatus())
                .set(Framework::getChangeLog, fw.getChangeLog());
        if (Framework.ST_TERMINATED.equals(fw.getStatus())) {
            uw.set(Framework::getTerminateReason, fw.getTerminateReason())
                    .set(Framework::getTerminateBy, fw.getTerminateBy())
                    .set(Framework::getTerminateAt, fw.getTerminateAt());
        }
        fwDao.update(null, uw);
        log.info("framework {} change {} approved: {}", fw.getFwNo(), type, execNote);
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        Framework fw = fwDao.selectById(instance.getBizId());
        if (fw == null) {
            log.warn("framework rejected but record missing: {}", instance.getBizId());
            return;
        }
        Map<String, Object> change = read(fw.getPendingChange());
        appendLog(fw, change, "REJECTED", "审批驳回，变更未生效（可重新发起）");
        fwDao.update(null, new com.baomidou.mybatisplus.core.conditions.update
                .LambdaUpdateWrapper<Framework>()
                .eq(Framework::getId, fw.getId())
                .set(Framework::getPendingChange, null)
                .set(Framework::getApprovalId, null)
                .set(Framework::getChangeLog, fw.getChangeLog()));
        log.info("framework {} change rejected", fw.getFwNo());
    }

    // ---------- 私有 ----------

    @SuppressWarnings("unchecked")
    private void appendLog(Framework fw, Map<String, Object> change, String result,
                           String note) {
        List<Map<String, Object>> logs = new ArrayList<>();
        try {
            if (fw.getChangeLog() != null && !fw.getChangeLog().isBlank()) {
                logs = mapper.readValue(fw.getChangeLog(), List.class);
            }
        } catch (Exception ignore) {
            logs = new ArrayList<>();
        }
        Map<String, Object> entry = new LinkedHashMap<>(change);
        entry.put("result", result);
        entry.put("note", note);
        entry.put("doneAt", LocalDateTime.now().toString());
        logs.add(entry);
        try {
            fw.setChangeLog(mapper.writeValueAsString(logs));
        } catch (Exception e) {
            fw.setChangeLog("[]");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<FrameworkLine> lines(String fwId) {
        return lineDao.selectList(new LambdaQueryWrapper<FrameworkLine>()
                .eq(FrameworkLine::getFrameworkId, fwId));
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
