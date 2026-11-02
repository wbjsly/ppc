package com.erp.service.impl.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.iot.IotAlarmEventDao;
import com.erp.dao.iot.IotFirstArticleDao;
import com.erp.dao.iot.IotMobileTodoDao;
import com.erp.dao.iot.IotOfflineQueueDao;
import com.erp.dao.iot.IotSyncConflictDao;
import com.erp.dao.mrp.MrpDispatchDao;
import com.erp.dao.mrp.MrpMoBomDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpOutputDao;
import com.erp.dao.mrp.MrpReportingDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.iot.IotAlarmEvent;
import com.erp.entity.iot.IotFirstArticle;
import com.erp.entity.iot.IotMobileTodo;
import com.erp.entity.iot.IotOfflineQueue;
import com.erp.entity.iot.IotSyncConflict;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpOutput;
import com.erp.entity.mrp.MrpReporting;
import com.erp.service.iot.IotReportService;
import com.erp.service.mrp.ReportingService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 移动报工薄适配层（add-iot-mobile 13.1，FR-4.11-1-x / BR-4.11-10/11/54）。
 * 报工本体复用 shop-floor-reporting（open/saveOutput/confirm/超产分级）；
 * 本层补移动端门禁：首件强制（未确认 L1 阻断）、幂等去重、连续 5 不良质量告警、
 * 72h 离线推送升级、扫码领料四校验（BOM/批次/容差/库存）。
 */
@Slf4j
@Service
public class IotReportServiceImpl implements IotReportService {

    /** 连续不良质量告警阈值（BR-4.11-1-3 异常处理③） */
    private static final int CONSECUTIVE_NG_ALERT = 5;

    private final MrpDispatchDao dispatchDao;
    private final MrpMoDao moDao;
    private final MrpMoBomDao moBomDao;
    private final MrpReportingDao reportingDao;
    private final MrpOutputDao outputDao;
    private final ReportingService reportingService;
    private final IotFirstArticleDao firstArticleDao;
    private final IotOfflineQueueDao queueDao;
    private final IotSyncConflictDao conflictDao;
    private final IotAlarmEventDao alarmDao;
    private final IotMobileTodoDao todoDao;
    private final SysUserRoleDao sysUserRoleDao;
    private final JdbcTemplate jdbc;

    @Value("${iot.offline-max-hours:72}")
    private int offlineMaxHours;

    public IotReportServiceImpl(MrpDispatchDao dispatchDao, MrpMoDao moDao, MrpMoBomDao moBomDao,
                                MrpReportingDao reportingDao, MrpOutputDao outputDao,
                                ReportingService reportingService, IotFirstArticleDao firstArticleDao,
                                IotOfflineQueueDao queueDao, IotSyncConflictDao conflictDao,
                                IotAlarmEventDao alarmDao, IotMobileTodoDao todoDao,
                                SysUserRoleDao sysUserRoleDao, JdbcTemplate jdbc) {
        this.dispatchDao = dispatchDao;
        this.moDao = moDao;
        this.moBomDao = moBomDao;
        this.reportingDao = reportingDao;
        this.outputDao = outputDao;
        this.reportingService = reportingService;
        this.firstArticleDao = firstArticleDao;
        this.queueDao = queueDao;
        this.conflictDao = conflictDao;
        this.alarmDao = alarmDao;
        this.todoDao = todoDao;
        this.sysUserRoleDao = sysUserRoleDao;
        this.jdbc = jdbc;
    }

    @Override
    public List<Map<String, Object>> myOrders(String userId, String wcCode) {
        // 已释放工单按工位/人员精准推送（FR-4.11-1-1）：RELEASED 派工单 + 工单头
        LambdaQueryWrapper<MrpDispatch> qw = new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getStatus, MrpDispatch.ST_RELEASED)
                .eq(wcCode != null && !wcCode.isBlank(), MrpDispatch::getWcCode, wcCode)
                .orderByAsc(MrpDispatch::getPlanStart);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpDispatch dp : dispatchDao.selectList(qw)) {
            MrpMo mo = moDao.selectById(dp.getMoId());
            if (mo == null || !MrpMo.ST_RELEASED.equals(mo.getStatus())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dispatchId", dp.getId());
            m.put("dispatchNo", dp.getDispatchNo());
            m.put("moId", mo.getId());
            m.put("moNo", mo.getMoNo());
            m.put("productCode", mo.getProductCode());
            m.put("productName", mo.getProductName());
            m.put("planQty", mo.getQty());
            m.put("opSeq", dp.getOpSeq());
            m.put("opName", dp.getOpName());
            m.put("wcCode", dp.getWcCode());
            m.put("planStart", dp.getPlanStart());
            m.put("planEnd", dp.getPlanEnd());
            // BOM 摘要（推送内容含物料清单，FR-4.11-1-1）
            List<Map<String, Object>> bom = jdbc.query(
                    "SELECT ITEM_CODE, ITEM_NAME, UNIT_QTY, LOSS_RATE FROM erp_mrp_mo_bom " +
                            "WHERE MO_ID=? AND LINE_TYPE='MATERIAL' ORDER BY LINE_NO",
                    new org.springframework.jdbc.core.ColumnMapRowMapper(), dp.getMoId());
            m.put("bom", bom);
            // 首件状态（移动报工页直接展示门禁态）
            IotFirstArticle fa = firstArticleDao.selectOne(new LambdaQueryWrapper<IotFirstArticle>()
                    .eq(IotFirstArticle::getDispatchId, dp.getId()));
            m.put("firstArticleStatus", fa == null ? "NONE" : fa.getStatus());
            m.put("ctqRequired", !bom.isEmpty());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> scanPushEscalations() {
        // 终端离线超 OFFLINE_SYNC_MAX_HOURS 且名下有已释放工单未开工 → 升级车间主任
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT t.DEVICE_CODE, t.BIND_USER_ID, t.LAST_ONLINE_AT FROM erp_iot_terminal t " +
                        "WHERE t.DEL_FLAG='0' AND t.BIND_USER_ID IS NOT NULL AND " +
                        "(t.LAST_ONLINE_AT IS NULL OR t.LAST_ONLINE_AT < DATE_SUB(NOW(), INTERVAL " +
                        offlineMaxHours + " HOUR))",
                new org.springframework.jdbc.core.ColumnMapRowMapper());
        List<String> directors = sysUserRoleDao.selectUsernamesByRoleCode("ROLE_PLAN_MGR");
        String director = directors.isEmpty() ? "user-admin" : directors.get(0);
        int escalated = 0;
        List<String> details = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String userId = (String) r.get("BIND_USER_ID");
            Integer pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_mrp_dispatch d INNER JOIN erp_mrp_mo m ON m.ID=d.MO_ID " +
                            "WHERE d.STATUS='RELEASED' AND m.STATUS='RELEASED' AND d.DEL_FLAG='0' " +
                            "AND d.CREATE_BY=? AND NOT EXISTS (" +
                            "SELECT 1 FROM erp_mrp_reporting r WHERE r.DISPATCH_ID=d.ID AND r.DEL_FLAG='0')",
                    Integer.class, userId);
            if (pending == null || pending == 0) {
                continue;
            }
            // 已有待办未重复升级
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_iot_mobile_todo WHERE TODO_TYPE='ESCALATE' " +
                            "AND REF_ID=? AND STATUS='PENDING' AND DEL_FLAG='0'",
                    Integer.class, r.get("DEVICE_CODE"));
            if (exists != null && exists > 0) {
                continue;
            }
            IotMobileTodo todo = new IotMobileTodo();
            todo.setTodoType(IotMobileTodo.TYPE_ESCALATE);
            todo.setRefType("TERMINAL");
            todo.setRefId((String) r.get("DEVICE_CODE"));
            todo.setTitle("终端 " + r.get("DEVICE_CODE") + " 离线超 " + offlineMaxHours
                    + "h，" + pending + " 张工单未接收");
            todo.setAssignee(director);
            todo.setStatus(IotMobileTodo.ST_PENDING);
            todo.setDueAt(LocalDateTime.now().plusMinutes(30));
            todo.setCreateBy("system");
            todoDao.insert(todo);
            escalated++;
            details.add(r.get("DEVICE_CODE") + "→" + director);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("offlineTerminals", rows.size());
        out.put("escalated", escalated);
        out.put("details", details);
        return out;
    }

    @Override
    public Map<String, Object> verifyMaterial(String dispatchId, String scanCode,
                                              BigDecimal qty, String reason) {
        MrpDispatch dp = dispatchDao.selectById(dispatchId);
        if (dp == null) {
            throw new ServiceException(404, "派工单不存在");
        }
        // 主/辅条码映射：一物多码场景按 ITEM_CODE 精确匹配；支持 ITEM|BATCH 双段格式
        String rawCode = scanCode == null ? "" : scanCode.trim();
        String batchNo = null;
        final String itemCode;
        if (rawCode.contains("|")) {
            String[] seg = rawCode.split("\\|", 2);
            itemCode = seg[0].trim();
            batchNo = seg[1].trim();
        } else {
            itemCode = rawCode;
        }
        List<MrpMoBom> lines = moBomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                .eq(MrpMoBom::getMoId, dp.getMoId()));
        MrpMoBom hit = lines.stream()
                .filter(l -> "MATERIAL".equals(l.getLineType()) && itemCode.equals(l.getItemCode()))
                .findFirst().orElse(null);
        if (hit == null) {
            // L1 硬阻断：物料编码不匹配工单 BOM（FR-4.11-1-2 异常处理①）
            throw new ServiceException(409, "物料条码规则异常：'" + itemCode + "' 不在该工单 BOM 中");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pass", true);
        out.put("itemCode", hit.getItemCode());
        out.put("itemName", hit.getItemName());
        out.put("bomUnitQty", hit.getUnitQty());
        out.put("lossRate", hit.getLossRate());
        out.put("checkedQty", qty);
        out.put("batchNo", batchNo);

        // 批次有效期校验
        if (batchNo != null && !batchNo.isBlank()) {
            java.sql.Date expiry = jdbc.query(
                    "SELECT EXPIRY_DATE FROM erp_inv_batch WHERE ITEM_CODE=? AND BATCH_NO=? AND DEL_FLAG='0'",
                    rs -> rs.next() ? rs.getDate(1) : null, itemCode, batchNo);
            if (expiry != null && expiry.before(java.sql.Date.valueOf(java.time.LocalDate.now()))) {
                throw new ServiceException(409, "物料已过期：" + batchNo + "，拒绝领料");
            }
        }

        // 超容差（TOLERANCE_DEFAULT）→ L4 需填原因（无原因 422，带原因放行留痕）
        BigDecimal tol = jdbc.query(
                "SELECT CAST(PARAM_VALUE AS DECIMAL(10,6)) FROM erp_sys_param WHERE PARAM_KEY='TOLERANCE_DEFAULT'",
                rs -> rs.next() ? rs.getBigDecimal(1) : null);
        BigDecimal tolerance = tol == null ? new BigDecimal("0.005") : tol;
        BigDecimal bomNeed = nvl(hit.getUnitQty());
        if (qty != null && qty.signum() > 0 && bomNeed.signum() > 0) {
            BigDecimal max = bomNeed.multiply(BigDecimal.ONE.add(tolerance));
            if (qty.compareTo(max) > 0) {
                if (reason == null || reason.isBlank()) {
                    throw new ServiceException(422, "领料数量 " + qty + " 超 BOM 需求 "
                            + bomNeed + " + 容差 " + tolerance.multiply(BigDecimal.valueOf(100)).stripTrailingZeros()
                            .toPlainString() + "%，请填写超量原因后确认");
                }
                out.put("overTolerance", true);
                out.put("overReason", reason);
            }
        }

        // 库存不足提示（不阻断领料，触发缺料预警标记）
        BigDecimal avail = jdbc.query(
                "SELECT COALESCE(SUM(AVAILABLE_QTY),0) FROM erp_inv_stock WHERE ITEM_CODE=? AND DEL_FLAG='0'",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, itemCode);
        if (qty != null && avail != null && avail.compareTo(qty) < 0) {
            out.put("stockShortage", true);
            out.put("availableQty", avail);
            out.put("warning", "库存不足（可用 " + avail + " < 领料 " + qty + "），已触发缺料预警");
        }
        return out;
    }

    /** 简单二段取值辅助（expiry null-safe）——已内联至 verifyMaterial，保留占位删除 */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitFirstArticle(Map<String, Object> body) {
        String dispatchId = (String) body.get("dispatchId");
        MrpDispatch dp = dispatchDao.selectById(dispatchId);
        if (dp == null) {
            throw new ServiceException(404, "派工单不存在");
        }
        IotFirstArticle exist = firstArticleDao.selectOne(new LambdaQueryWrapper<IotFirstArticle>()
                .eq(IotFirstArticle::getDispatchId, dispatchId));
        IotFirstArticle fa = exist == null ? new IotFirstArticle() : exist;
        fa.setDispatchId(dispatchId);
        fa.setMoNo(dp.getMoNo());
        fa.setOpSeq(dp.getOpSeq());
        MrpMo mo = moDao.selectById(dp.getMoId());
        fa.setItemCode(mo == null ? null : mo.getProductCode());
        fa.setCtqJson((String) body.get("ctqJson"));
        fa.setPhotoUrl((String) body.get("photoUrl"));
        // 离线条件放行（BR-4.11-10）：offline=true 且在线 IPQC 未确认 → OFFLINE_FLAG=1
        boolean offline = Boolean.TRUE.equals(body.get("offline"));
        fa.setOfflineFlag(offline ? "1" : "0");
        fa.setStatus(IotFirstArticle.ST_PENDING);
        fa.setSubmitBy(SecurityUtils.getCurrentUserId());
        fa.setSubmitAt(LocalDateTime.now());
        if (exist == null) {
            firstArticleDao.insert(fa);
        } else {
            firstArticleDao.updateById(fa);
        }
        return faMap(fa);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmFirstArticle(String id, boolean pass, String remark) {
        IotFirstArticle fa = firstArticleDao.selectById(id);
        if (fa == null) {
            throw new ServiceException(404, "首件记录不存在");
        }
        if (!IotFirstArticle.ST_PENDING.equals(fa.getStatus())) {
            throw new ServiceException(409, "首件已处理，不可重复确认");
        }
        fa.setConfirmBy(SecurityUtils.getCurrentUserId());
        fa.setConfirmAt(LocalDateTime.now());
        if (pass) {
            fa.setStatus(IotFirstArticle.ST_CONFIRMED);
        } else {
            fa.setStatus(IotFirstArticle.ST_REJECTED);
            // 离线放行批次的联网复核不合格（BR-4.11-54）：整批转差异单，不置工单暂停
            if ("1".equals(fa.getOfflineFlag())) {
                IotSyncConflict c = new IotSyncConflict();
                c.setQueueId(fa.getId());
                c.setConflictType(IotSyncConflict.TYPE_DIFF);
                c.setFieldPath("FIRST_ARTICLE:" + fa.getDispatchId());
                c.setBeforeSnapshot("离线条件放行报工批次");
                c.setAfterSnapshot("首件复核不合格：" + remark);
                c.setStatus(IotSyncConflict.ST_NEED_REVIEW);
                c.setNeedFinApproval("0");
                c.setCreateBy(SecurityUtils.getCurrentUserId());
                conflictDao.insert(c);
                fa.setDiffConflictId(c.getId());
            }
        }
        fa.setRemark(remark);
        firstArticleDao.updateById(fa);
        return faMap(fa);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reportOutput(Map<String, Object> body) {
        String dispatchId = (String) body.get("dispatchId");
        String idemKey = (String) body.get("idempotentKey");
        String deviceCode = (String) body.get("deviceCode");
        BigDecimal goodQty = dec(body.get("goodQty"));
        BigDecimal ngQty = dec(body.get("ngQty"));
        boolean overprodAck = Boolean.TRUE.equals(body.get("overprodAck"));

        // 幂等键去重（FR-4.11-1-4 要点②：工单号+工序+时间戳+设备 ID）——重复提交不重复过账
        if (idemKey != null && !idemKey.isBlank()) {
            IotOfflineQueue dup = queueDao.selectOne(new LambdaQueryWrapper<IotOfflineQueue>()
                    .eq(IotOfflineQueue::getIdempotentKey, idemKey));
            if (dup != null) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("duplicate", true);
                out.put("rptId", dup.getPayloadJson());
                out.put("message", "重复提交已去重，不重复过账");
                return out;
            }
        }

        MrpDispatch dp = dispatchDao.selectById(dispatchId);
        if (dp == null) {
            throw new ServiceException(404, "派工单不存在");
        }

        // 首件门禁（BR-4.11-1-3 要点① L1 硬阻断）：未确认不可批量报工
        IotFirstArticle fa = firstArticleDao.selectOne(new LambdaQueryWrapper<IotFirstArticle>()
                .eq(IotFirstArticle::getDispatchId, dispatchId));
        if (fa == null) {
            throw new ServiceException(422, "首件未提交：请先完成首件报工");
        }
        if (!IotFirstArticle.ST_CONFIRMED.equals(fa.getStatus())) {
            if ("1".equals(fa.getOfflineFlag()) && IotFirstArticle.ST_PENDING.equals(fa.getStatus())) {
                // 离线例外（BR-4.11-10）：条件放行，联网后强制复核（confirmFirstArticle 复核）
                log.info("[iot] 离线首件条件放行 dispatch={}", dispatchId);
            } else {
                throw new ServiceException(422, "首件待确认：IPQC 确认通过后方可批量报工");
            }
        }

        // 复用报工本体：开工 → 产出（超产分级在 saveOutput 内）→ 确认
        MrpReporting rpt = reportingService.open(dispatchId, "BATCH");
        reportingService.saveOutput(rpt.getId(), goodQty, ngQty, (String) body.get("ngDispose"),
                overprodAck);

        // 连续 5 不良质量告警（BR-4.11-1-3 异常处理③）
        boolean qualityAlert = false;
        if (ngQty != null && ngQty.signum() > 0 && (goodQty == null || goodQty.signum() == 0)) {
            Integer consec = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT o.RPT_ID FROM erp_mrp_output o " +
                            "INNER JOIN erp_mrp_reporting r ON r.ID=o.RPT_ID " +
                            "WHERE r.MO_ID=? AND r.OP_SEQ=? AND r.STATUS='CONFIRMED' AND r.DEL_FLAG='0' " +
                            "ORDER BY r.CONFIRM_AT DESC LIMIT " + CONSECUTIVE_NG_ALERT + ") t " +
                            "WHERE t.RPT_ID IS NOT NULL",
                    Integer.class, dp.getMoId(), dp.getOpSeq());
            if (consec != null && consec >= CONSECUTIVE_NG_ALERT) {
                writeAlarm(deviceCode == null ? "MOBILE" : deviceCode, IotAlarmEvent.LV_L2,
                        IotAlarmEvent.TYPE_EXCEPTION,
                        "连续 " + CONSECUTIVE_NG_ALERT + " 件不良：" + dp.getMoNo()
                                + " 工序 " + dp.getOpSeq());
                qualityAlert = true;
            }
        }

        // 超产挂起预检（confirm 抛异常会污染共享事务 → 事务内预判返回，不走异常）：
        // saveOutput 超阈值已置 OVERPROD_FLAG+挂审批，未批 → PENDING_REVIEW 待车间主任审核
        MrpReporting preCheck = reportingDao.selectById(rpt.getId());
        if (preCheck != null && "1".equals(preCheck.getOverprodFlag())
                && !"1".equals(preCheck.getProdConfirmFlag())) {
            IotMobileTodo todo = new IotMobileTodo();
            todo.setTodoType(IotMobileTodo.TYPE_REPORT);
            todo.setRefType("MRP_REPORTING");
            todo.setRefId(rpt.getId());
            todo.setTitle("超产报工待审：" + rpt.getRptNo());
            todo.setAssignee(sysUserRoleDao.selectUsernamesByRoleCode("ROLE_PLAN_MGR")
                    .stream().findFirst().orElse("user-admin"));
            todo.setStatus(IotMobileTodo.ST_PENDING);
            todo.setDueAt(LocalDateTime.now().plusMinutes(30));
            todo.setCreateBy(SecurityUtils.getCurrentUserId());
            todoDao.insert(todo);
            Map<String, Object> pendingOut = new LinkedHashMap<>();
            pendingOut.put("rptId", rpt.getId());
            pendingOut.put("status", "PENDING_REVIEW");
            pendingOut.put("overprodAckRequired", true);
            pendingOut.put("todoId", todo.getId());
            recordIdem(idemKey, deviceCode, rpt.getId(), "SYNCED");
            return pendingOut;
        }

        reportingService.confirm(rpt.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rptId", rpt.getId());
        out.put("rptNo", rpt.getRptNo());
        out.put("status", "CONFIRMED");
        out.put("duplicate", false);
        out.put("qualityAlert", qualityAlert);
        recordIdem(idemKey, deviceCode, rpt.getId(), "SYNCED");
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reviewReport(String rptId, boolean pass, String opinion) {
        MrpReporting rpt = reportingDao.selectById(rptId);
        if (rpt == null) {
            throw new ServiceException(404, "报工单不存在");
        }
        // 车间主任/计划主管审核：通过 → 超产确认回调放行过账；驳回 → 留痕退回
        reportingService.approveOverProd(rptId, pass, opinion);
        if (pass) {
            try {
                reportingService.confirm(rptId);
            } catch (ServiceException e) {
                // 审批通过但仍有其他前置未决（如 IPQC）→ 原样抛出
                throw e;
            }
            // 关闭相关待办
            jdbc.update("UPDATE erp_iot_mobile_todo SET STATUS='DONE', HANDLED_BY=?, HANDLED_AT=NOW(), " +
                            "HANDLING_OPINION=? WHERE REF_ID=? AND TODO_TYPE='REPORT' AND STATUS='PENDING' AND DEL_FLAG='0'",
                    SecurityUtils.getCurrentUserId(), opinion, rptId);
        } else {
            jdbc.update("UPDATE erp_iot_mobile_todo SET STATUS='CANCELLED', HANDLED_BY=?, HANDLED_AT=NOW(), " +
                            "HANDLING_OPINION=? WHERE REF_ID=? AND TODO_TYPE='REPORT' AND STATUS='PENDING' AND DEL_FLAG='0'",
                    SecurityUtils.getCurrentUserId(), "驳回：" + opinion, rptId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rptId", rptId);
        out.put("reviewed", true);
        out.put("pass", pass);
        MrpReporting after = reportingDao.selectById(rptId);
        out.put("status", after.getStatus());
        return out;
    }

    @Override
    public Map<String, Object> firstArticleOf(String dispatchId) {
        IotFirstArticle fa = firstArticleDao.selectOne(new LambdaQueryWrapper<IotFirstArticle>()
                .eq(IotFirstArticle::getDispatchId, dispatchId));
        return fa == null ? Map.of() : faMap(fa);
    }

    // ---------- internal ----------

    private void recordIdem(String idemKey, String deviceCode, String rptId, String status) {
        if (idemKey == null || idemKey.isBlank()) {
            return;
        }
        IotOfflineQueue q = new IotOfflineQueue();
        q.setIdempotentKey(idemKey);
        q.setDeviceCode(deviceCode == null ? "MOBILE" : deviceCode);
        q.setBizType(IotOfflineQueue.BIZ_REPORT);
        q.setPayloadJson(rptId);
        q.setClientVerNo(0);
        q.setClientTs(LocalDateTime.now());
        q.setStatus(status);
        q.setRetryCnt(0);
        q.setSyncedAt(LocalDateTime.now());
        q.setCreateBy(SecurityUtils.getCurrentUserId());
        queueDao.insert(q);
    }

    private void writeAlarm(String deviceNo, String level, String type, String reason) {
        IotAlarmEvent ev = new IotAlarmEvent();
        ev.setEventNo("AL" + System.currentTimeMillis() + (int) (Math.random() * 900 + 100));
        ev.setDeviceNo(deviceNo);
        ev.setAlertLevel(level);
        ev.setAlertType(type);
        ev.setRuleSnap(reason);
        ev.setMergeKey(type + ":" + deviceNo);
        ev.setMergeCnt(1);
        ev.setStatus(IotAlarmEvent.ST_OPEN);
        ev.setOccurredAt(LocalDateTime.now());
        ev.setRemark(reason);
        ev.setCreateBy(SecurityUtils.getCurrentUserId());
        alarmDao.insert(ev);
    }

    private Map<String, Object> faMap(IotFirstArticle fa) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", fa.getId());
        m.put("dispatchId", fa.getDispatchId());
        m.put("moNo", fa.getMoNo());
        m.put("opSeq", fa.getOpSeq());
        m.put("itemCode", fa.getItemCode());
        m.put("ctqJson", fa.getCtqJson());
        m.put("photoUrl", fa.getPhotoUrl());
        m.put("status", fa.getStatus());
        m.put("offlineFlag", fa.getOfflineFlag());
        m.put("recheckResult", fa.getRecheckResult());
        m.put("diffConflictId", fa.getDiffConflictId());
        m.put("submitAt", fa.getSubmitAt());
        m.put("confirmAt", fa.getConfirmAt());
        return m;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(String.valueOf(v));
    }
}
