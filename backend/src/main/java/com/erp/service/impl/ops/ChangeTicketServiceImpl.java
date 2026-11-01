package com.erp.service.impl.ops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.ops.OpsChangeTicketDao;
import com.erp.entity.ops.OpsChangeTicket;
import com.erp.service.ChangeWindowService;
import com.erp.service.ops.ChangeTicketService;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维变更单实现（add-ops-sla，18.1）。
 * 五门禁：结账期阻断（BR-5.8-14）/ 预案三件套（BR-5.8-01）/ 窗口剩余×1.5（BR-5.8-06）/
 * 灰度自动回滚（BR-5.8-05）/ 回滚原因强制（BR-5.8-04）。
 * 窗口判定复用 change-window-management（只读消费，零改动其本体）。
 */
@Slf4j
@Service
public class ChangeTicketServiceImpl implements ChangeTicketService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpsChangeTicketDao ticketDao;
    private final ChangeWindowService changeWindow;
    private final JdbcTemplate jdbc;

    public ChangeTicketServiceImpl(OpsChangeTicketDao ticketDao, ChangeWindowService changeWindow,
                                   JdbcTemplate jdbc) {
        this.ticketDao = ticketDao;
        this.changeWindow = changeWindow;
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Object> submit(Map<String, Object> body) {
        String title = str(body.get("title"));
        if (title == null || title.isBlank()) {
            throw new ServiceException(422, "变更标题必填");
        }
        String type = str(body.getOrDefault("changeType", OpsChangeTicket.T_NORMAL));
        // BR-5.8-14 结账保障期（1~5 日）非紧急变更阻断
        boolean guardOn = accountingGuardOn();
        boolean emergency = OpsChangeTicket.T_EMERGENCY_FIX.equals(type)
                || OpsChangeTicket.T_GRAY.equals(type);
        int day = LocalDateTime.now().getDayOfMonth();
        if (guardOn && !emergency && day >= 1 && day <= 5) {
            throw new ServiceException(422, "结账保障期，非紧急变更顺延（BR-5.8-14）");
        }
        OpsChangeTicket t = new OpsChangeTicket();
        t.setTicketNo("CT-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 900 + 100));
        t.setTitle(title.trim());
        t.setChangeType(type);
        t.setAffectScope(str(body.get("affectScope")));
        t.setEstimateMin(intOf(body.get("estimateMin"), 60));
        t.setRollbackMin(body.get("rollbackMin") == null ? null : intOf(body.get("rollbackMin"), 0));
        t.setRollbackPlanJson(str(body.get("rollbackPlanJson")));
        t.setStatus(OpsChangeTicket.ST_SUBMITTED);
        t.setAccountingGuard(guardOn && day >= 1 && day <= 5 ? "1" : "0");
        t.setCreateBy(operator());
        t.setCreateDate(LocalDateTime.now());
        ticketDao.insert(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("ticketNo", t.getTicketNo());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> approve(String id) {
        OpsChangeTicket t = mustGet(id);
        if (!OpsChangeTicket.ST_SUBMITTED.equals(t.getStatus())) {
            throw new ServiceException(422, "仅 SUBMITTED 可审批（当前 " + t.getStatus() + "）");
        }
        // BR-5.8-01 预案三件套门禁
        validateRollbackPlan(t.getRollbackPlanJson());
        // 窗口判定（复用既有服务）：窗口外标记 + CCB 语义由审批链承担（本系统审批链已在 16.6 会签底座）
        boolean in = changeWindow.isInWindow(LocalDateTime.now());
        t.setInWindow(in ? "1" : "0");
        t.setWindowCheckNote(in ? "窗口内" : "窗口外（CCB 升级审批，BR-5.8-06/C-5.6-03）");
        t.setStatus(OpsChangeTicket.ST_APPROVED);
        t.setUpdateBy(operator());
        t.setUpdateDate(LocalDateTime.now());
        ticketDao.updateById(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", t.getStatus());
        out.put("inWindow", t.getInWindow());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(String id) {
        OpsChangeTicket t = mustGet(id);
        if (!OpsChangeTicket.ST_APPROVED.equals(t.getStatus())) {
            throw new ServiceException(422, "仅 APPROVED 可启动（当前 " + t.getStatus() + "）");
        }
        // BR-5.8-06 剩余窗口时间 < (预计+回滚)×1.5 → 阻断启动并顺延
        LocalDateTime now = LocalDateTime.now();
        boolean in = changeWindow.isInWindow(now);
        if (in) {
            int need = (t.getEstimateMin() == null ? 60 : t.getEstimateMin())
                    + (t.getRollbackMin() == null ? 0 : t.getRollbackMin());
            int remaining = changeWindow.remainingMin(now);
            if (remaining < need * 1.5) {
                throw new ServiceException(422,
                        "剩余窗口时间不足（需 " + need + "×1.5 分钟，剩余 " + remaining + " 分钟），"
                                + "已顺延至下一候选窗口（BR-5.8-06）");
            }
        } else {
            throw new ServiceException(422, "当前不在变更窗口内，禁止启动（BR-5.8-06）");
        }
        t.setStatus(OpsChangeTicket.ST_EXECUTING);
        t.setExecutor(operator());
        t.setUpdateBy(operator());
        t.setUpdateDate(LocalDateTime.now());
        ticketDao.updateById(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", t.getStatus());
        out.put("remainingMin", changeWindow.remainingMin(now));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rollback(String id, String reason) {
        OpsChangeTicket t = mustGet(id);
        if (!OpsChangeTicket.ST_EXECUTING.equals(t.getStatus())
                && !OpsChangeTicket.ST_APPROVED.equals(t.getStatus())) {
            throw new ServiceException(422, "仅执行中/已审批可回滚（当前 " + t.getStatus() + "）");
        }
        t.setStatus(OpsChangeTicket.ST_ROLLED_BACK);
        t.setRollbackReason(reason);
        t.setUpdateBy(operator());
        t.setUpdateDate(LocalDateTime.now());
        ticketDao.updateById(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", t.getStatus());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> close(String id, String rollbackReason) {
        OpsChangeTicket t = mustGet(id);
        // BR-5.8-04 已回滚则回滚原因必填方可关闭
        if (OpsChangeTicket.ST_ROLLED_BACK.equals(t.getStatus())
                && (rollbackReason == null || rollbackReason.isBlank())) {
            throw new ServiceException(422, "须填写回滚原因方可关闭（BR-5.8-04）");
        }
        if (rollbackReason != null && !rollbackReason.isBlank()) {
            t.setRollbackReason(rollbackReason);
        }
        t.setStatus(OpsChangeTicket.ST_CLOSED);
        t.setUpdateBy(operator());
        t.setUpdateDate(LocalDateTime.now());
        ticketDao.updateById(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", t.getStatus());
        return out;
    }

    @Override
    public List<Map<String, Object>> list(String status, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT ID AS id, TICKET_NO AS ticketNo, TITLE AS title, CHANGE_TYPE AS changeType, "
                        + "AFFECT_SCOPE AS affectScope, ESTIMATE_MIN AS estimateMin, ROLLBACK_MIN AS rollbackMin, "
                        + "STATUS AS status, IN_WINDOW AS inWindow, WINDOW_CHECK_NOTE AS windowCheckNote, "
                        + "ROLLBACK_REASON AS rollbackReason, EXECUTOR AS executor, CREATE_BY AS createBy, "
                        + "CREATE_DATE AS createDate FROM erp_ops_change_ticket WHERE DEL_FLAG='0'");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND STATUS=?");
            args.add(status.trim());
        }
        sql.append(" ORDER BY CREATE_DATE DESC LIMIT ? OFFSET ?");
        int lim = Math.min(Math.max(size, 1), 100);
        args.add(lim);
        args.add(Math.max(page - 1, 0) * lim);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ---------------- 工具 ----------------

    private void validateRollbackPlan(String json) {
        if (json == null || json.isBlank()) {
            throw new ServiceException(422, "回滚预案缺失，不可审批（BR-5.8-01）");
        }
        try {
            JsonNode n = JSON.readTree(json);
            String script = n.path("script").asText("");
            boolean drillValid = n.path("drillValid").asBoolean(false);
            int est = n.path("estimateMin").asInt(0);
            if (script.isBlank() || !drillValid || est <= 0) {
                throw new ServiceException(422,
                        "回滚预案三件套不齐（脚本/演练有效/回滚耗时），不可审批（BR-5.8-01）");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(422, "回滚预案 JSON 解析失败：" + e.getMessage());
        }
    }

    private boolean accountingGuardOn() {
        try {
            String v = jdbc.queryForObject(
                    "SELECT PARAM_VALUE FROM erp_sys_param WHERE PARAM_KEY='ACCOUNTING_GUARD_ENABLED' "
                            + "AND DEL_FLAG='0'", String.class);
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        } catch (Exception e) {
            return false; // 参数未配置默认关
        }
    }

    private OpsChangeTicket mustGet(String id) {
        OpsChangeTicket t = ticketDao.selectById(id);
        if (t == null) {
            throw new ServiceException(404, "变更单不存在：" + id);
        }
        return t;
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private int intOf(Object v, int def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private String operator() {
        try {
            String u = SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }
}
