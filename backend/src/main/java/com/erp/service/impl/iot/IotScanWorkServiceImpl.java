package com.erp.service.impl.iot;

import com.erp.common.ServiceException;
import com.erp.dao.iot.IotOfflineQueueDao;
import com.erp.dao.iot.IotMobileTodoDao;
import com.erp.entity.iot.IotMobileTodo;
import com.erp.entity.iot.IotOfflineQueue;
import com.erp.service.iot.IotScanWorkService;
import com.erp.service.inv.BinAssignmentService;
import com.erp.service.inv.CountInputService;
import com.erp.service.inv.PickScanService;
import com.erp.service.proc.GoodsReceiptService;
import com.erp.service.sd.ShipmentService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 扫码作业五场景薄适配（add-iot-mobile 13.2，FR-4.11-2-x）。
 * 复用 PROC/INV/SD 服务层校验不改本体；本层补：
 * RFID 序列号损坏/重复拒收（C-4.11-02）、批次不一致冻结货位+通知主管（FR-4.11-2-3）、
 * 移动端异常码映射（L1 硬阻断 / L4 提示 / L2 强制审批）。
 */
@Slf4j
@Service
public class IotScanWorkServiceImpl implements IotScanWorkService {

    private final GoodsReceiptService receiptService;
    private final BinAssignmentService binAssignmentService;
    private final PickScanService pickScanService;
    private final ShipmentService shipmentService;
    private final CountInputService countInputService;
    private final JdbcTemplate jdbc;
    private final IotOfflineQueueDao queueDao;
    private final IotMobileTodoDao todoDao;

    public IotScanWorkServiceImpl(GoodsReceiptService receiptService,
                                  BinAssignmentService binAssignmentService,
                                  PickScanService pickScanService,
                                  ShipmentService shipmentService,
                                  CountInputService countInputService,
                                  JdbcTemplate jdbc,
                                  IotOfflineQueueDao queueDao,
                                  IotMobileTodoDao todoDao) {
        this.receiptService = receiptService;
        this.binAssignmentService = binAssignmentService;
        this.pickScanService = pickScanService;
        this.shipmentService = shipmentService;
        this.countInputService = countInputService;
        this.jdbc = jdbc;
        this.queueDao = queueDao;
        this.todoDao = todoDao;
    }

    @Override
    public Map<String, Object> receive(Map<String, Object> payload) {
        String serialNo = str(payload.get("serialNo"));
        // C-4.11-02：RFID 序列号缺失或损坏 → L1 硬阻断拒收
        if (serialNo == null || serialNo.isBlank()) {
            throw new ServiceException(409, "【L1 硬阻断】RFID 序列号缺失，拒绝收货（C-4.11-02）");
        }
        if (serialNo.contains("DAMAGE") || serialNo.contains("损坏")) {
            throw new ServiceException(409, "【L1 硬阻断】RFID 序列号损坏，拒绝收货（C-4.11-02）");
        }
        // 防重：同序列号已收货 → 拒绝
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_serial WHERE SERIAL_NO=? AND DEL_FLAG='0'",
                Integer.class, serialNo);
        if (dup != null && dup > 0) {
            throw new ServiceException(409, "【L1 硬阻断】序列号重复扫码，防重拒绝：" + serialNo);
        }
        // 转收货登记（复用 PROC 服务层：容差分流 + 差异单）
        Map<String, Object> out = receiptService.createFree(payload);
        // 序列号登记（防重锚点）
        String itemCode = firstLineItem(payload);
        String grNo = out.get("gr") instanceof com.erp.entity.proc.GoodsReceipt gr
                ? gr.getGrNo() : str(out.get("grNo"));
        jdbc.update("INSERT INTO erp_inv_serial (ID, SERIAL_NO, ITEM_CODE, ITEM_NAME, STATUS, " +
                        "SOURCE_DOC_NO, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString().replace("-", ""), serialNo, itemCode, null,
                "ACTIVE", grNo, SecurityUtils.getCurrentUserId(), LocalDateTime.now(), "0", 0);
        Map<String, Object> r = new LinkedHashMap<>(out);
        r.put("grNo", grNo);
        r.put("serialNo", serialNo);
        r.put("level", "OK");
        return r;
    }

    @Override
    public Map<String, Object> putaway(Map<String, Object> payload) {
        String grNo = str(payload.get("grNo"));
        Integer lineNo = payload.get("lineNo") == null ? null
                : Integer.valueOf(String.valueOf(payload.get("lineNo")));
        String binCode = str(payload.get("binCode"));
        try {
            // 复用仓位分配（三属性合规 + 容量校验，违规 422 不豁免）
            binAssignmentService.assign(grNo, lineNo, binCode);
        } catch (ServiceException e) {
            // 移动端异常码映射：仅业务校验（422）转 L1 硬阻断提示；403/404 原样抛
            if (e.getCode() != 422) {
                throw e;
            }
            throw new ServiceException(409, "【L1 硬阻断】上架被拒：" + e.getMessage()
                    + "（存储条件/货位容量不符，可扫备选货位）");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("grNo", grNo);
        r.put("lineNo", lineNo);
        r.put("binCode", binCode);
        r.put("level", "OK");
        // 货位已满自动推荐备选（FR-4.11-2-2 异常处理①）
        try {
            r.put("alternatives", binAssignmentService.candidates(grNo, lineNo));
        } catch (ServiceException ignore) {
            // 候选查询失败不阻断上架结果
        }
        return r;
    }

    @Override
    public Map<String, Object> pickScan(Map<String, Object> payload) {
        String taskId = str(payload.get("taskId"));
        Integer lineNo = payload.get("lineNo") == null ? null
                : Integer.valueOf(String.valueOf(payload.get("lineNo")));
        String binCode = str(payload.get("binCode"));
        String itemCode = str(payload.get("itemCode"));
        String batchNo = str(payload.get("batchNo"));
        try {
            Map<String, Object> v = pickScanService.verify(taskId, lineNo, binCode, itemCode, batchNo);
            // 校验通过 → 行确认（先放行后实拣）
            if (payload.get("pickedQty") != null) {
                pickScanService.confirmLine(taskId, lineNo,
                        new BigDecimal(String.valueOf(payload.get("pickedQty"))),
                        str(payload.get("serials")));
            }
            Map<String, Object> r = new LinkedHashMap<>(v);
            r.put("level", "OK");
            return r;
        } catch (ServiceException e) {
            if (e.getCode() != 422) {
                throw e;
            }
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // 批次不符（firstFail BATCH: → 422 消息含"期望"）→ 冻结货位 + 通知仓库主管（FR-4.11-2-3）
            if (msg.contains("期望") || msg.contains("批次")) {
                freezeBinOfTask(taskId, itemCode, batchNo);
                notifyWarehouseSupervisor(taskId, itemCode, batchNo);
                throw new ServiceException(409,
                        "【L1 硬阻断】实物批次与系统批次不一致，已冻结该货位库存并通知仓库主管核查："
                                + msg);
            }
            throw new ServiceException(409, "【L1 硬阻断】拣货扫码被拒：" + msg);
        }
    }

    @Override
    public Map<String, Object> shipReview(Map<String, Object> payload) {
        String shipId = str(payload.get("shipId"));
        String itemCode = str(payload.get("itemCode"));
        String batchNo = str(payload.get("batchNo"));
        BigDecimal qty = payload.get("qty") == null ? null
                : new BigDecimal(String.valueOf(payload.get("qty")));
        Map<String, Object> detail = shipmentService.detail(shipId);
        Object rawLines = detail.get("lines");
        if (!(rawLines instanceof List<?> lines) || lines.isEmpty()) {
            throw new ServiceException(404, "发货单无明细行");
        }
        // 逐件复核：比对发货清单（物料/批次/数量），不一致 L1 停止发货（FR-4.11-2-4）
        Object line = lines.stream()
                .filter(l -> itemCode != null && itemCode.equals(prop(l, "itemCode")))
                .findFirst()
                .orElseThrow(() -> new ServiceException(409,
                        "【L1 硬阻断】复核物料 " + itemCode + " 不在发货清单，停止发货"));
        String lineBatch = prop(line, "batchNo");
        if (batchNo != null && lineBatch != null && !batchNo.equals(lineBatch)) {
            throw new ServiceException(409,
                    "【L1 硬阻断】复核批次不符（清单 " + lineBatch + " 实扫 " + batchNo + "），停止发货");
        }
        BigDecimal planQty = new BigDecimal(String.valueOf(prop(line, "qty")));
        if (qty != null && qty.compareTo(planQty) > 0) {
            throw new ServiceException(409,
                    "【L1 硬阻断】复核数量 " + qty + " 超出发货清单 " + planQty + "，停止发货");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("shipId", shipId);
        r.put("itemCode", itemCode);
        r.put("planQty", planQty);
        r.put("reviewedQty", qty);
        r.put("level", "OK");
        // 复核通过 → 触发发货确认（服务层校验 POSTED 前置，非 POSTED 返回可读提示）
        if (Boolean.TRUE.equals(payload.get("confirm"))) {
            try {
                r.put("confirmed", true);
                r.put("shipment", shipmentService.confirm(shipId,
                        str(payload.get("logisticsCo")), str(payload.get("logisticsNo")),
                        str(payload.get("exceptNote"))));
            } catch (ServiceException e) {
                r.put("confirmed", false);
                r.put("confirmHint", e.getMessage());
            }
        }
        return r;
    }

    @Override
    public Map<String, Object> countScan(Map<String, Object> payload) {
        String lineId = str(payload.get("lineId"));
        BigDecimal actualQty = new BigDecimal(String.valueOf(payload.get("actualQty")));
        try {
            Map<String, Object> out = countInputService.submitCount(lineId, actualQty,
                    str(payload.get("abnormalFlag")), str(payload.get("remark")));
            String outcome = str(out.get("outcome"));
            Map<String, Object> r = new LinkedHashMap<>(out);
            // 移动端异常码映射：超容差差异单 → L2 强制审批提示
            if (outcome == null || !"AUTO_ADJUSTED".equals(outcome)) {
                r.put("level", "L2");
                r.put("levelHint", "【L2 强制审批】盘亏超容差已生成差异单，需仓库经理审批后过账");
            } else {
                r.put("level", "OK");
            }
            return r;
        } catch (ServiceException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // >10% 阻断待复盘（BR-4.4-39）→ 移动端复盘提示
            if (msg.contains("复盘")) {
                throw new ServiceException(409,
                        "【L1 硬阻断】差异率超 10% 待复盘，请两人复核后重新录入：" + msg);
            }
            throw e;
        }
    }

    // ---------- internal ----------

    /** 批次不符 → 冻结该货位库存（erp_inv_freeze ACTIVE 记录，SCOPE=BATCH） */
    private void freezeBinOfTask(String taskId, String itemCode, String batchNo) {
        Map<String, Object> line = jdbc.queryForMap(
                "SELECT l.BIN_CODE, l.WAREHOUSE_CODE FROM erp_pick_task_line l " +
                        "WHERE l.TASK_ID=? AND (? IS NULL OR l.ITEM_CODE=?) LIMIT 1",
                taskId, itemCode, itemCode);
        String binCode = str(line.get("BIN_CODE"));
        String wh = str(line.get("WAREHOUSE_CODE"));
        // 幂等：同货位同批次已有 ACTIVE 冻结不重复
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_freeze WHERE ITEM_CODE=? AND " +
                        "(BATCH_NO IS NULL OR BATCH_NO=?) AND STATUS='ACTIVE' AND DEL_FLAG='0'",
                Integer.class, itemCode, batchNo);
        if (exists != null && exists > 0) {
            return;
        }
        BigDecimal qty = jdbc.query(
                "SELECT COALESCE(SUM(AVAILABLE_QTY),0) FROM erp_inv_stock " +
                        "WHERE ITEM_CODE=? AND BIN_CODE=? AND DEL_FLAG='0'",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, itemCode, binCode);
        jdbc.update("INSERT INTO erp_inv_freeze (ID, FREEZE_NO, FREEZE_TYPE, WAREHOUSE_CODE, " +
                        "ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, REASON, SCOPE, STATUS, SOURCE, " +
                        "APPLY_BY, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, '0', 0)",
                UUID.randomUUID().toString().replace("-", ""),
                "FZ-SCN-" + System.currentTimeMillis(), "QUALITY", wh == null ? "WH-MAIN" : wh,
                itemCode, null, batchNo, qty == null ? BigDecimal.ZERO : qty,
                "移动扫码批次不一致冻结货位 " + binCode + "（FR-4.11-2-3）", "BATCH", "ACTIVE", "MANUAL",
                SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserId(), LocalDateTime.now());
        log.info("[iot] batch mismatch freeze item={} batch={} bin={}", itemCode, batchNo, binCode);
    }

    /** 通知仓库主管核查（移动待办） */
    private void notifyWarehouseSupervisor(String taskId, String itemCode, String batchNo) {
        List<String> supers = jdbc.queryForList(
                "SELECT u.USERNAME FROM erp_admin_user u " +
                        "INNER JOIN erp_admin_user_role ur ON ur.USER_ID=u.ID " +
                        "INNER JOIN erp_admin_role r ON r.ID=ur.ROLE_ID " +
                        "WHERE r.ROLE_CODE IN ('ROLE_WAREHOUSE','ROLE_ADMIN') " +
                        "AND r.STATUS='1' AND u.STATUS='1' LIMIT 1", String.class);
        IotMobileTodo todo = new IotMobileTodo();
        todo.setTodoType(IotMobileTodo.TYPE_EXCEPTION);
        todo.setRefType("PICK_TASK");
        todo.setRefId(taskId);
        todo.setTitle("批次不一致冻结核查：" + itemCode + " / " + batchNo);
        todo.setAssignee(supers.isEmpty() ? "user-admin" : supers.get(0));
        todo.setStatus(IotMobileTodo.ST_PENDING);
        todo.setDueAt(LocalDateTime.now().plusMinutes(30));
        todo.setCreateBy(SecurityUtils.getCurrentUserId());
        todoDao.insert(todo);
    }

    private String firstLineItem(Map<String, Object> payload) {
        Object lines = payload.get("lines");
        if (lines instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
            Object ic = m.get("itemCode");
            return ic == null ? null : String.valueOf(ic);
        }
        return str(payload.get("itemCode"));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** 反射取属性（detail.lines 元素可能是实体或 Map，两态兼容） */
    private static String prop(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Map<?, ?> m) {
            Object v = m.get(name);
            return v == null ? null : String.valueOf(v);
        }
        String cap = name.substring(0, 1).toUpperCase() + name.substring(1);
        try {
            Object v = obj.getClass().getMethod("get" + cap).invoke(obj);
            return v == null ? null : String.valueOf(v);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
