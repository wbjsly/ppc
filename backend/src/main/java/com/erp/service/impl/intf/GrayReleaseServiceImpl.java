package com.erp.service.impl.intf;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfGrayReleaseDao;
import com.erp.dao.intf.IntfGrayStepLogDao;
import com.erp.entity.intf.IntfGrayRelease;
import com.erp.entity.intf.IntfGrayStepLog;
import com.erp.security.AuditLogWriter;
import com.erp.service.intf.GrayReleaseService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 灰度切流量实现（add-intf-portal-menus，spec interface-canary-release）。
 * 规则配置/切量/回滚限 ADMIN；观察窗口错误率超阈自动回滚（首档中止）。
 * 网关侧按 CURRENT_STEP 百分比选凭证（只读 hook，见 OpenApiGatewayServiceImpl）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrayReleaseServiceImpl implements GrayReleaseService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IntfGrayReleaseDao releaseDao;
    private final IntfGrayStepLogDao stepLogDao;
    private final AuditLogWriter auditLogWriter;
    private final JdbcTemplate jdbc;

    @Override
    public Map<String, Object> createRelease(Map<String, Object> payload) {
        requireAdmin("配置灰度规则");
        String channelCode = str(payload.get("channelCode"));
        String oldCredId = str(payload.get("oldCredId"));
        String newCredId = str(payload.get("newCredId"));
        String ladderJson = str(payload.get("ladderJson"));
        if (channelCode == null || channelCode.isBlank() || oldCredId == null
                || newCredId == null || ladderJson == null || ladderJson.isBlank()) {
            throw new ServiceException(422, "channelCode/oldCredId/newCredId/ladderJson 必填");
        }
        List<Integer> ladder = parseLadder(ladderJson);
        if (ladder.isEmpty()) {
            throw new ServiceException(422, "阶梯百分比非法（如 [10,50,100]）");
        }
        IntfGrayRelease r = new IntfGrayRelease();
        r.setId(uuid());
        r.setChannelCode(channelCode);
        r.setOldCredId(oldCredId);
        r.setNewCredId(newCredId);
        r.setLadderJson(toJson(ladder));
        r.setCurrentStep(0);
        r.setObserveMin(intOf(payload.get("observeMin"), 60));
        r.setRollbackErrorRate(decOf(payload.get("rollbackErrorRate"), new BigDecimal("50")));
        r.setStatus(IntfGrayRelease.ST_DRAFT);
        r.setCreateBy(operator());
        releaseDao.insert(r);
        auditLogWriter.write("gray:" + channelCode, "GRAY_RELEASE_CREATED", null, "DRAFT",
                "配置灰度规则（阶梯 " + ladder + "）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("status", r.getStatus());
        out.put("ladder", ladder);
        return out;
    }

    @Override
    public List<Map<String, Object>> listReleases() {
        return jdbc.queryForList(
                "SELECT ID AS id, CHANNEL_CODE AS channelCode, OLD_CRED_ID AS oldCredId, "
                        + "NEW_CRED_ID AS newCredId, LADDER_JSON AS ladderJson, CURRENT_STEP AS currentStep, "
                        + "OBSERVE_MIN AS observeMin, ROLLBACK_ERROR_RATE AS rollbackErrorRate, "
                        + "STATUS AS status FROM erp_intf_gray_release WHERE DEL_FLAG='0' "
                        + "ORDER BY CREATE_DATE DESC");
    }

    @Override
    public Map<String, Object> getRelease(String id) {
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT ID AS id, CHANNEL_CODE AS channelCode, OLD_CRED_ID AS oldCredId, "
                        + "NEW_CRED_ID AS newCredId, LADDER_JSON AS ladderJson, CURRENT_STEP AS currentStep, "
                        + "OBSERVE_MIN AS observeMin, ROLLBACK_ERROR_RATE AS rollbackErrorRate, "
                        + "STATUS AS status FROM erp_intf_gray_release WHERE ID=? AND DEL_FLAG='0'", id);
        List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT STEP AS step, PCT AS pct, ACTION AS action, RESULT AS result, "
                        + "OPERATOR AS operator, ERROR_RATE AS errorRate, CREATE_DATE AS at "
                        + "FROM erp_intf_gray_step_log WHERE RELEASE_ID=? AND DEL_FLAG='0' "
                        + "ORDER BY CREATE_DATE DESC", id);
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("stepLogs", logs);
        return out;
    }

    @Override
    public Map<String, Object> stepUp(String id) {
        requireAdmin("灰度切量");
        IntfGrayRelease r = requireRelease(id);
        if (!IntfGrayRelease.ST_RUNNING.equals(r.getStatus())
                && !IntfGrayRelease.ST_DRAFT.equals(r.getStatus())) {
            throw new ServiceException(422, "仅 DRAFT/RUNNING 状态可切量（当前 " + r.getStatus() + "）");
        }
        List<Integer> ladder = parseLadder(r.getLadderJson());
        int next = (r.getCurrentStep() == null ? 0 : r.getCurrentStep()) + 1;
        if (next > ladder.size()) {
            throw new ServiceException(422, "已达最高档（" + ladder.get(ladder.size() - 1) + "%），切量结束请完成灰度");
        }
        r.setCurrentStep(next);
        r.setStatus(IntfGrayRelease.ST_RUNNING);
        r.setUpdateBy(operator());
        releaseDao.updateById(r);
        writeLog(r.getId(), next, ladder.get(next - 1), IntfGrayStepLog.ACT_STEP_IN,
                IntfGrayStepLog.RES_SUCCESS, operator(), null, "切量至 " + ladder.get(next - 1) + "%");
        auditLogWriter.write("gray:" + r.getChannelCode(), "GRAY_STEP_IN", String.valueOf(next - 1),
                String.valueOf(next), "灰度切量至 " + ladder.get(next - 1) + "%");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("currentStep", next);
        out.put("pct", ladder.get(next - 1));
        out.put("status", r.getStatus());
        return out;
    }

    @Override
    public Map<String, Object> rollback(String id) {
        requireAdmin("灰度回滚");
        IntfGrayRelease r = requireRelease(id);
        List<Integer> ladder = parseLadder(r.getLadderJson());
        int cur = r.getCurrentStep() == null ? 0 : r.getCurrentStep();
        // 首档（currentStep=1，10%）回滚 = 中止灰度（全量回旧凭证）
        boolean abort = cur <= 1;
        r.setCurrentStep(Math.max(0, cur - 1));
        r.setStatus(abort ? IntfGrayRelease.ST_ABORTED : IntfGrayRelease.ST_ROLLBACK);
        r.setUpdateBy(operator());
        releaseDao.updateById(r);
        writeLog(r.getId(), r.getCurrentStep(), abort ? 0 : ladder.get(r.getCurrentStep() - 1),
                abort ? IntfGrayStepLog.ACT_ABORT : IntfGrayStepLog.ACT_ROLLBACK,
                IntfGrayStepLog.RES_SUCCESS, operator(), null,
                abort ? "首档超阈中止灰度，全量回旧凭证" : "回滚至上一档");
        auditLogWriter.write("gray:" + r.getChannelCode(), "GRAY_ROLLBACK", String.valueOf(cur),
                r.getStatus(), abort ? "灰度中止" : "灰度回滚");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("currentStep", r.getCurrentStep());
        out.put("status", r.getStatus());
        return out;
    }

    @Override
    public int observeAndAutoRollback() {
        List<Map<String, Object>> running = jdbc.queryForList(
                "SELECT ID, CHANNEL_CODE, CURRENT_STEP, ROLLBACK_ERROR_RATE, OBSERVE_MIN "
                        + "FROM erp_intf_gray_release WHERE STATUS='RUNNING' AND DEL_FLAG='0'");
        int handled = 0;
        for (Map<String, Object> row : running) {
            String id = str(row.get("ID"));
            String channel = str(row.get("CHANNEL_CODE"));
            // 新凭证观察窗口错误率（erp_intf_call_log 按 PARTNER_CODE 聚合；口径沿 open-api-gateway 按伙伴计）
            BigDecimal errorRate = queryErrorRate(channel, (int) num(row.get("OBSERVE_MIN")));
            BigDecimal threshold = decOf(row.get("ROLLBACK_ERROR_RATE"), new BigDecimal("50"));
            if (errorRate != null && errorRate.compareTo(threshold) > 0) {
                // 超阈自动回滚：首档中止，否则回上一档
                IntfGrayRelease r = releaseDao.selectById(id);
                if (r == null) {
                    continue;
                }
                List<Integer> ladder = parseLadder(r.getLadderJson());
                int cur = r.getCurrentStep() == null ? 0 : r.getCurrentStep();
                boolean abort = cur <= 1;
                r.setCurrentStep(Math.max(0, cur - 1));
                r.setStatus(abort ? IntfGrayRelease.ST_ABORTED : IntfGrayRelease.ST_ROLLBACK);
                r.setUpdateBy("system");
                releaseDao.updateById(r);
                writeLog(id, r.getCurrentStep(), abort ? 0 : ladder.get(Math.max(0, r.getCurrentStep() - 1)),
                        abort ? IntfGrayStepLog.ACT_ABORT : IntfGrayStepLog.ACT_ROLLBACK,
                        IntfGrayStepLog.RES_SUCCESS, "system", errorRate,
                        "自动回滚（错误率 " + errorRate + "% > 阈值 " + threshold + "%）");
                auditLogWriter.write("gray:" + channel, "GRAY_AUTO_ROLLBACK", String.valueOf(cur),
                        r.getStatus(), "观察窗口错误率超阈自动回滚");
                handled++;
            }
        }
        return handled;
    }

    // ---- 工具 ----

    @Override
    public String remapApiKey(String inboundApiKey, String requestId) {
        if (inboundApiKey == null || inboundApiKey.isBlank()) {
            return inboundApiKey;
        }
        try {
            // 命中 RUNNING 灰度规则且入站 Key=旧凭证 Key 时按百分比分流（只读 hook）
            List<Map<String, Object>> running = jdbc.queryForList(
                    "SELECT g.ID, g.CURRENT_STEP, g.LADDER_JSON, o.API_KEY AS OLD_KEY, "
                            + "n.API_KEY AS NEW_KEY FROM erp_intf_gray_release g "
                            + "JOIN erp_intf_credential o ON o.ID = g.OLD_CRED_ID "
                            + "JOIN erp_intf_credential n ON n.ID = g.NEW_CRED_ID "
                            + "WHERE g.STATUS='RUNNING' AND g.DEL_FLAG='0'");
            for (Map<String, Object> row : running) {
                if (!inboundApiKey.equals(str(row.get("OLD_KEY")))) {
                    continue;
                }
                List<Integer> ladder = parseLadder(str(row.get("LADDER_JSON")));
                int step = (int) num(row.get("CURRENT_STEP"));
                if (ladder.isEmpty() || step < 0 || step >= ladder.size()) {
                    return inboundApiKey;
                }
                int pct = ladder.get(step);
                // 按 requestId 哈希取模分桶（确定性，不改限流/熔断判定）
                int bucket = Math.floorMod(requestId == null ? 0 : requestId.hashCode(), 100);
                if (bucket < pct) {
                    return str(row.get("NEW_KEY"));
                }
                return inboundApiKey;
            }
            return inboundApiKey;
        } catch (Exception e) {
            // hook 失败退化为原逻辑（不阻断网关）
            log.warn("gray remap fallback: {}", e.getMessage());
            return inboundApiKey;
        }
    }

    /** 伙伴观察窗口错误率（失败率%，erp_intf_call_log 近 N 分钟按 PARTNER_CODE 聚合） */
    private BigDecimal queryErrorRate(String partnerCode, int observeMin) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT COUNT(*) AS total, "
                            + "SUM(CASE WHEN RESP_CODE >= 500 OR RESP_CODE = 0 THEN 1 ELSE 0 END) AS failed "
                            + "FROM erp_intf_call_log WHERE DEL_FLAG='0' AND CREATE_DATE >= DATE_SUB(NOW(), "
                            + "INTERVAL ? MINUTE) AND PARTNER_CODE = ?", observeMin, partnerCode);
            long total = num(row.get("total"));
            if (total == 0) {
                return null;
            }
            long failed = num(row.get("failed"));
            return BigDecimal.valueOf(failed * 100)
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("gray observe error rate failed: {}", e.getMessage());
            return null;
        }
    }

    private IntfGrayRelease requireRelease(String id) {
        IntfGrayRelease r = releaseDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "灰度规则不存在");
        }
        return r;
    }

    private void writeLog(String releaseId, int step, int pct, String action, String result,
                          String operator, BigDecimal errorRate, String remark) {
        IntfGrayStepLog l = new IntfGrayStepLog();
        l.setId(uuid());
        l.setReleaseId(releaseId);
        l.setStep(step);
        l.setPct(pct);
        l.setAction(action);
        l.setResult(result);
        l.setOperator(operator);
        l.setErrorRate(errorRate);
        l.setRemark(remark);
        l.setCreateBy(operator);
        stepLogDao.insert(l);
    }

    /** 高危动作限 ADMIN（spec interface-canary-release：非 ADMIN 403 留痕） */
    private void requireAdmin(String action) {
        if (!com.erp.security.IntfGuard.isAdmin()) {
            auditLogWriter.write("gray:" + SecurityUtilsUser(), "GRAY_FORBIDDEN", null, "DENIED",
                    action + " 被拒（非 ADMIN）");
            throw new ServiceException(403, action + " 仅 ADMIN 可操作");
        }
    }

    private List<Integer> parseLadder(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<Integer>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(422, "JSON 序列化失败");
        }
    }

    private BigDecimal decOf(Object v, BigDecimal dflt) {
        if (v == null) {
            return dflt;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private int intOf(Object v, int dflt) {
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private long num(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String SecurityUtilsUser() {
        try {
            String u = com.erp.util.SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }

    private String operator() {
        return SecurityUtilsUser();
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
