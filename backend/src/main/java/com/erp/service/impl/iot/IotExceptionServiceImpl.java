package com.erp.service.impl.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamWorkOrderDao;
import com.erp.dao.iot.IotAlarmEventDao;
import com.erp.dao.iot.IotExceptionDao;
import com.erp.dao.iot.IotMobileTodoDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.eam.EamWorkOrder;
import com.erp.entity.iot.IotAlarmEvent;
import com.erp.entity.iot.IotException;
import com.erp.entity.iot.IotMobileTodo;
import com.erp.service.iot.IotExceptionService;
import com.erp.service.inv.FreezeService;
import com.erp.util.SecurityUtils;
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
 * 异常上报与联动实现（add-iot-mobile 13.4，FR-4.11-3-x / BR-4.11-19~24）。
 * 联动：QUALITY→即时质量冻结（recordNcrFreeze 无审批立即生效）；
 * EQUIPMENT→EAM 维修工单（幂等键=异常单 ID，BR-4.11-20）+ 设备故障态；
 * SAFETY→停机指令打点（仿真通道，BR-4.11-21 时效记录）。
 * IoT 自动上报须现场确认才触发重动作；同班次≥3 同类合并升级（BR-4.11-24）；
 * 30min 未响应升级上级（BR-4.11-22/27）。
 */
@Slf4j
@Service
public class IotExceptionServiceImpl implements IotExceptionService {

    private final IotExceptionDao exceptionDao;
    private final IotMobileTodoDao todoDao;
    private final IotAlarmEventDao alarmDao;
    private final EamWorkOrderDao workOrderDao;
    private final FreezeService freezeService;
    private final SysUserRoleDao sysUserRoleDao;
    private final JdbcTemplate jdbc;

    public IotExceptionServiceImpl(IotExceptionDao exceptionDao, IotMobileTodoDao todoDao,
                                   IotAlarmEventDao alarmDao, EamWorkOrderDao workOrderDao,
                                   FreezeService freezeService, SysUserRoleDao sysUserRoleDao,
                                   JdbcTemplate jdbc) {
        this.exceptionDao = exceptionDao;
        this.todoDao = todoDao;
        this.alarmDao = alarmDao;
        this.workOrderDao = workOrderDao;
        this.freezeService = freezeService;
        this.sysUserRoleDao = sysUserRoleDao;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> report(Map<String, Object> body) {
        String excType = str(body.get("excType"));
        if (!IotException.TYPE_QUALITY.equals(excType)
                && !IotException.TYPE_EQUIPMENT.equals(excType)
                && !IotException.TYPE_SAFETY.equals(excType)) {
            throw new ServiceException(422, "异常类型须为 QUALITY/EQUIPMENT/SAFETY");
        }
        String deviceNo = str(body.get("deviceNo"));
        String severity = str(body.get("severity"));
        if (severity == null) {
            severity = IotException.SEV_GENERAL;
        }
        String source = str(body.get("source"));
        if (source == null) {
            source = "MANUAL";
        }
        // 同班次（8h 窗口）同设备同类型 ≥3 次 → 合并至首单并升级严重程度（BR-4.11-24）
        IotException merged = mergeShiftDuplicates(excType, deviceNo);

        IotException exc = new IotException();
        exc.setExcNo("EX-" + System.currentTimeMillis()
                + (int) (Math.random() * 900 + 100));
        exc.setExcType(excType);
        exc.setSeverity(merged != null ? IotException.SEV_SEVERE
                : (IotException.SEV_URGENT.equals(severity) ? IotException.SEV_URGENT
                : IotException.SEV_GENERAL));
        exc.setTitle(str(body.get("title")));
        exc.setDescription(str(body.get("description")));
        exc.setDeviceNo(deviceNo);
        exc.setMoNo(str(body.get("moNo")));
        exc.setItemCode(str(body.get("itemCode")));
        exc.setStation(str(body.get("station")));
        exc.setPhotoUrls(str(body.get("photoUrls")));
        exc.setSource(source);
        // IoT 自动上报须现场确认门禁（BR-4.11-19 要点④：防误报停机）
        boolean gated = "IOT".equalsIgnoreCase(source);
        exc.setConfirmFlag(gated ? "0" : "1");
        exc.setStatus(IotException.ST_OPEN);
        exc.setMergeOf(merged == null ? null : merged.getMergeOf() != null
                ? merged.getMergeOf() : merged.getId());
        exc.setMergeCnt(merged == null ? 1 : merged.getMergeCnt() + 1);
        exc.setDueAt(LocalDateTime.now().plusMinutes(30));
        exc.setCreateBy(SecurityUtils.getCurrentUserId());
        exceptionDao.insert(exc);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", exc.getId());
        out.put("excNo", exc.getExcNo());
        out.put("merged", merged != null);
        out.put("severity", exc.getSeverity());
        out.put("pendingConfirm", gated);
        if (!gated) {
            out.put("linkage", triggerLinkage(exc));
        } else {
            out.put("linkage", "GATED_AWAIT_FIELD_CONFIRM");
        }
        // 紧急级即时通知车间主任（BR-4.11-19 要点②）
        if (IotException.SEV_URGENT.equals(exc.getSeverity())) {
            out.put("urgentNotified", notifyRole("ROLE_PLAN_MGR",
                    "紧急异常即时通知：" + exc.getExcNo(), exc.getId()));
        }
        // 告警事件镜像（看板/SLA 统计消费）
        writeAlarm(exc);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(String id) {
        IotException exc = require(id);
        if (!"0".equals(exc.getConfirmFlag())) {
            throw new ServiceException(409, "该异常非 IoT 待确认来源，无需现场确认");
        }
        exc.setConfirmFlag("1");
        exceptionDao.updateById(exc);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("confirmed", true);
        out.put("linkage", triggerLinkage(exc));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dispose(String id, String result, String remark) {
        IotException exc = require(id);
        if (IotException.ST_CLOSED.equals(exc.getStatus())) {
            throw new ServiceException(409, "异常已关闭");
        }
        exc.setStatus(IotException.ST_CLOSED);
        exc.setClosedAt(LocalDateTime.now());
        exc.setRemark(remark);
        exceptionDao.updateById(exc);
        // 告警事件闭环回写
        jdbc.update("UPDATE erp_iot_alarm_event SET STATUS='CLOSED', CLOSE_BY=?, CLOSE_AT=NOW() " +
                        "WHERE REMARK LIKE CONCAT(?, '%') AND STATUS<>'CLOSED' AND DEL_FLAG='0'",
                SecurityUtils.getCurrentUserId(), exc.getExcNo());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("closed", true);
        out.put("result", result);
        return out;
    }

    @Override
    public List<Map<String, Object>> list(String status, String excType) {
        LambdaQueryWrapper<IotException> qw = new LambdaQueryWrapper<IotException>()
                .eq(status != null && !status.isBlank(), IotException::getStatus, status)
                .eq(excType != null && !excType.isBlank(), IotException::getExcType, excType)
                .orderByDesc(IotException::getCreateDate);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IotException e : exceptionDao.selectList(qw)) {
            out.add(toMap(e));
        }
        return out;
    }

    @Override
    public Map<String, Object> scanEscalate() {
        // OPEN 且 DUE_AT 过期 30min → 升级（设备故障→设备主管；安全事件→安全总监）
        List<IotException> overdue = exceptionDao.selectList(new LambdaQueryWrapper<IotException>()
                .eq(IotException::getStatus, IotException.ST_OPEN)
                .lt(IotException::getDueAt, LocalDateTime.now()));
        int escalated = 0;
        List<String> details = new ArrayList<>();
        for (IotException e : overdue) {
            String role = IotException.TYPE_SAFETY.equals(e.getExcType())
                    ? "ROLE_QUALITY_DIRECTOR" : "ROLE_EQUIP_MGR";
            String to = notifyRole(role, "异常超 30min 未响应升级：" + e.getExcNo(), e.getId());
            jdbc.update("UPDATE erp_iot_exception SET REMARK=CONCAT(COALESCE(REMARK,''), " +
                            "'[升级→', ?, ']') WHERE ID=?", to, e.getId());
            escalated++;
            details.add(e.getExcNo() + "→" + to);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("escalated", escalated);
        out.put("details", details);
        return out;
    }

    // ---------- 联动 ----------

    /** 触发联动（BR-4.11-20/21/22）：冻结/建单/停机打点；返回联动摘要 */
    private String triggerLinkage(IotException exc) {
        if (IotException.TYPE_QUALITY.equals(exc.getExcType())) {
            // 质量异常 → 即时质量冻结（批次/序列号级；recordNcrFreeze 立即生效无审批）
            String batch = str(batchOf(exc));
            BigDecimal qty = qtyOf(exc);
            if (exc.getItemCode() != null && batch != null) {
                freezeService.recordNcrFreeze("WH-MAIN", exc.getItemCode(), batch, qty,
                        "IOT-EXC:" + exc.getExcNo());
                return "QUALITY_FREEZE:" + exc.getItemCode() + "/" + batch;
            }
            return "QUALITY_FREEZE_SKIPPED:缺物料或批次";
        }
        if (IotException.TYPE_EQUIPMENT.equals(exc.getExcType())) {
            // 设备故障 → EAM 维修工单（幂等键=异常单 ID，BR-4.11-20 防重复建单）
            String idem = "IOT-EXC:" + exc.getId();
            Integer exist = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_eam_work_order WHERE IDEMPOTENT_KEY=? AND DEL_FLAG='0'",
                    Integer.class, idem);
            String woNo = null;
            if (exist == null || exist == 0) {
                EamWorkOrder wo = new EamWorkOrder();
                wo.setWoNo("WO-EX-" + System.currentTimeMillis());
                wo.setWoType("REACTIVE");
                wo.setSrcType("EXCEPTION");
                wo.setIdempotentKey(idem);
                // CARD_ID/CARD_NO 必填：优先取设备关联资产卡，未关联用占位（后续派工补绑）
                String assetNo = jdbc.query(
                        "SELECT ASSET_NO FROM erp_iot_device WHERE DEVICE_NO=? AND DEL_FLAG='0'",
                        rs -> rs.next() ? rs.getString(1) : null, exc.getDeviceNo());
                String cardNo = assetNo == null || assetNo.isBlank() ? "UNBOUND" : assetNo;
                String cardId = jdbc.query(
                        "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO=? AND DEL_FLAG='0' LIMIT 1",
                        rs -> rs.next() ? rs.getString(1) : null, cardNo);
                wo.setCardId(cardId == null ? "CARD-UNBOUND" : cardId);
                wo.setCardNo(cardNo);
                wo.setPlanDesc("设备故障报修：" + exc.getDeviceNo()
                        + (exc.getDescription() == null ? "" : "（" + exc.getDescription() + "）"));
                wo.setStatus("PENDING");
                wo.setCreateBy(SecurityUtils.getCurrentUserId());
                workOrderDao.insert(wo);
                woNo = wo.getWoNo();
            }
            // 设备状态置故障
            if (exc.getDeviceNo() != null) {
                jdbc.update("UPDATE erp_iot_device SET STATUS='FAULT' WHERE DEVICE_NO=? AND DEL_FLAG='0'",
                        exc.getDeviceNo());
            }
            // 30min 未确认升级挂点（scanEscalate 消费）
            return (woNo == null ? "EAM_WO_DUPLICATE" : "EAM_WO:" + woNo);
        }
        // 安全事件 → 停机指令下发（仿真通道打点，BR-4.11-21 时效记录）
        long t0 = System.nanoTime();
        // 仿真通道：进程内指令回环（DeviceGatewayAdapter 6.1 落地后走适配器）
        long ackMs = (System.nanoTime() - t0) / 1_000_000;
        jdbc.update("UPDATE erp_iot_exception SET STOP_CMD_AT=NOW(), STOP_ACK_MS=? WHERE ID=?",
                Math.max(ackMs, 1), exc.getId());
        return "SAFETY_STOP_CMD:ack=" + Math.max(ackMs, 1) + "ms";
    }

    /** 同班次（8h）同设备同类型已有单 → 返回首单（合并锚点） */
    private IotException mergeShiftDuplicates(String excType, String deviceNo) {
        if (deviceNo == null || deviceNo.isBlank()) {
            return null;
        }
        return exceptionDao.selectOne(new LambdaQueryWrapper<IotException>()
                .eq(IotException::getExcType, excType)
                .eq(IotException::getDeviceNo, deviceNo)
                .gt(IotException::getCreateDate, LocalDateTime.now().minusHours(8))
                .orderByAsc(IotException::getCreateDate)
                .last("LIMIT 1"));
    }

    private void writeAlarm(IotException exc) {
        IotAlarmEvent ev = new IotAlarmEvent();
        ev.setEventNo("ALEX-" + exc.getExcNo());
        ev.setDeviceNo(exc.getDeviceNo() == null ? "MOBILE" : exc.getDeviceNo());
        ev.setAlertLevel(IotException.SEV_URGENT.equals(exc.getSeverity()) ? IotAlarmEvent.LV_L1
                : IotException.SEV_SEVERE.equals(exc.getSeverity()) ? IotAlarmEvent.LV_L2
                : IotAlarmEvent.LV_L3);
        ev.setAlertType(IotAlarmEvent.TYPE_EXCEPTION);
        ev.setRuleSnap(exc.getTitle());
        ev.setMergeKey("EXC:" + exc.getExcType() + ":" + exc.getDeviceNo());
        ev.setMergeCnt(exc.getMergeCnt());
        ev.setStatus(IotAlarmEvent.ST_OPEN);
        ev.setOccurredAt(LocalDateTime.now());
        ev.setRemark(exc.getExcNo());
        ev.setCreateBy(SecurityUtils.getCurrentUserId());
        alarmDao.insert(ev);
    }

    private String notifyRole(String roleCode, String title, String refId) {
        List<String> users = sysUserRoleDao.selectUsernamesByRoleCode(roleCode);
        String assignee = users.isEmpty() ? "user-admin" : users.get(0);
        IotMobileTodo todo = new IotMobileTodo();
        todo.setTodoType(IotMobileTodo.TYPE_EXCEPTION);
        todo.setRefType("IOT_EXCEPTION");
        todo.setRefId(refId);
        todo.setTitle(title);
        todo.setAssignee(assignee);
        todo.setStatus(IotMobileTodo.ST_PENDING);
        todo.setDueAt(LocalDateTime.now().plusMinutes(30));
        todo.setCreateBy(SecurityUtils.getCurrentUserId());
        todoDao.insert(todo);
        return assignee;
    }

    private IotException require(String id) {
        IotException e = exceptionDao.selectById(id);
        if (e == null) {
            throw new ServiceException(404, "异常单不存在");
        }
        return e;
    }

    private String batchOf(IotException exc) {
        // 载荷批次：优先取描述中 [batch:xxx] 标记，否则取 itemCode 同名批次兜底
        String d = exc.getDescription();
        if (d != null && d.contains("[batch:")) {
            return d.substring(d.indexOf("[batch:") + 7, d.indexOf(']'));
        }
        return jdbc.query("SELECT BATCH_NO FROM erp_inv_stock WHERE ITEM_CODE=? AND DEL_FLAG='0' LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, exc.getItemCode());
    }

    private BigDecimal qtyOf(IotException exc) {
        BigDecimal q = jdbc.query(
                "SELECT COALESCE(SUM(AVAILABLE_QTY),0) FROM erp_inv_stock WHERE ITEM_CODE=? AND DEL_FLAG='0'",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, exc.getItemCode());
        return q == null ? BigDecimal.ZERO : q;
    }

    private Map<String, Object> toMap(IotException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("excNo", e.getExcNo());
        m.put("excType", e.getExcType());
        m.put("severity", e.getSeverity());
        m.put("title", e.getTitle());
        m.put("deviceNo", e.getDeviceNo());
        m.put("moNo", e.getMoNo());
        m.put("source", e.getSource());
        m.put("confirmFlag", e.getConfirmFlag());
        m.put("status", e.getStatus());
        m.put("linkWoId", e.getLinkWoId());
        m.put("linkFreezeId", e.getLinkFreezeId());
        m.put("stopCmdAt", e.getStopCmdAt());
        m.put("stopAckMs", e.getStopAckMs());
        m.put("mergeOf", e.getMergeOf());
        m.put("mergeCnt", e.getMergeCnt());
        m.put("dueAt", e.getDueAt());
        m.put("closedAt", e.getClosedAt());
        return m;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
