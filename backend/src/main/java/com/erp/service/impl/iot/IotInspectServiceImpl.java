package com.erp.service.impl.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.iot.IotExceptionDao;
import com.erp.dao.iot.IotInspectRecordDao;
import com.erp.entity.iot.IotException;
import com.erp.entity.iot.IotInspectRecord;
import com.erp.service.iot.IotInspectService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 设备巡检执行实现（add-iot-mobile 13.3）：
 * 计划到期生成 / 扫码签到 / 逐项点检 / 异常标记联动 / 拍照留证 / 超时漏检。
 */
@Slf4j
@Service
public class IotInspectServiceImpl implements IotInspectService {

    private final IotInspectRecordDao recordDao;
    private final IotExceptionDao exceptionDao;
    private final JdbcTemplate jdbc;

    public IotInspectServiceImpl(IotInspectRecordDao recordDao,
                                 IotExceptionDao exceptionDao, JdbcTemplate jdbc) {
        this.recordDao = recordDao;
        this.exceptionDao = exceptionDao;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateFromPlans() {
        // ACTIVE 巡检计划且 NEXT_RUN_TIME 到期 → 生成执行记录（源=QMS 巡检计划）
        List<Map<String, Object>> due = jdbc.query(
                "SELECT PLAN_CODE, ITEM_CODE, ITEM_NAME, NEXT_RUN_TIME, INTERVAL_MINUTES " +
                        "FROM erp_qms_patrol_plan WHERE STATUS='ACTIVE' AND DEL_FLAG='0' " +
                        "AND NEXT_RUN_TIME IS NOT NULL AND NEXT_RUN_TIME <= NOW()",
                new org.springframework.jdbc.core.ColumnMapRowMapper());
        int created = 0;
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> p : due) {
            String planCode = String.valueOf(p.get("PLAN_CODE"));
            // 同计划未闭环任务不重复生成
            Long open = recordDao.selectCount(new LambdaQueryWrapper<IotInspectRecord>()
                    .eq(IotInspectRecord::getPlanCode, planCode)
                    .eq(IotInspectRecord::getStatus, IotInspectRecord.ST_OPEN));
            if (open != null && open > 0) {
                continue;
            }
            IotInspectRecord r = new IotInspectRecord();
            r.setPlanCode(planCode);
            r.setDeviceNo(String.valueOf(p.get("ITEM_CODE")));
            r.setStatus(IotInspectRecord.ST_OPEN);
            r.setAbnormalFlag("0");
            r.setOverdueFlag("0");
            Object iv = p.get("INTERVAL_MINUTES");
            int minutes = iv == null ? 120 : Integer.parseInt(String.valueOf(iv));
            r.setDueAt(LocalDateTime.now().plusMinutes(minutes));
            r.setCreateBy("system");
            recordDao.insert(r);
            codes.add(planCode);
            created++;
            // 推进计划下次生成
            jdbc.update("UPDATE erp_qms_patrol_plan SET LAST_RUN_TIME=NOW(), " +
                            "NEXT_RUN_TIME=DATE_ADD(NOW(), INTERVAL ? MINUTE) WHERE PLAN_CODE=?",
                    minutes, planCode);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("duePlans", due.size());
        out.put("created", created);
        out.put("planCodes", codes);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Map<String, Object> body) {
        String deviceNo = (String) body.get("deviceNo");
        if (deviceNo == null || deviceNo.isBlank()) {
            throw new ServiceException(422, "被检设备编码必填");
        }
        IotInspectRecord r = new IotInspectRecord();
        r.setPlanCode((String) body.get("planCode"));
        r.setDeviceNo(deviceNo);
        r.setStatus(IotInspectRecord.ST_OPEN);
        r.setAbnormalFlag("0");
        r.setOverdueFlag("0");
        Object due = body.get("dueAt");
        r.setDueAt(due == null ? LocalDateTime.now().plusHours(2)
                : LocalDateTime.parse(String.valueOf(due)));
        r.setRemark((String) body.get("remark"));
        r.setCreateBy(SecurityUtils.getCurrentUserId());
        recordDao.insert(r);
        return toMap(r);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkin(String id, String deviceNo) {
        IotInspectRecord r = require(id);
        if (!IotInspectRecord.ST_OPEN.equals(r.getStatus())) {
            throw new ServiceException(409, "巡检记录已提交，不可重复签到");
        }
        // 扫码签到：扫的设备须与任务设备一致（不一致 L1 拒绝）
        if (deviceNo != null && !deviceNo.isBlank() && !deviceNo.equals(r.getDeviceNo())) {
            throw new ServiceException(409, "【L1 硬阻断】签到设备 " + deviceNo
                    + " 与巡检任务设备 " + r.getDeviceNo() + " 不一致");
        }
        r.setCheckinAt(LocalDateTime.now());
        r.setCheckinBy(SecurityUtils.getCurrentUserId());
        recordDao.updateById(r);
        return toMap(r);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(String id, String itemsJson, String photoUrls) {
        IotInspectRecord r = require(id);
        if (IotInspectRecord.ST_SUBMITTED.equals(r.getStatus())) {
            throw new ServiceException(409, "巡检记录已提交");
        }
        if (itemsJson == null || itemsJson.isBlank()) {
            throw new ServiceException(422, "点检项记录必填（逐项勾选/数值）");
        }
        boolean abnormal = itemsJson.contains("\"abnormal\":true")
                || itemsJson.contains("\"abnormal\":1");
        // 异常项须拍照留证（缺图提交校验提示补拍）
        if (abnormal && (photoUrls == null || photoUrls.isBlank() || "[]".equals(photoUrls))) {
            throw new ServiceException(422, "存在异常点检项：请拍照留证后提交（缺图补拍）");
        }
        r.setItemsJson(itemsJson);
        r.setPhotoUrls(photoUrls);
        r.setAbnormalFlag(abnormal ? "1" : "0");
        r.setStatus(IotInspectRecord.ST_SUBMITTED);
        r.setSubmitAt(LocalDateTime.now());
        // 异常标记 → 自动生成异常上报单（EQUIPMENT）并回填关联
        if (abnormal) {
            IotException exc = new IotException();
            exc.setExcNo("EX-INS-" + System.currentTimeMillis());
            exc.setExcType(IotException.TYPE_EQUIPMENT);
            exc.setSeverity("SEVERE");
            exc.setTitle("巡检异常标记：" + r.getDeviceNo());
            exc.setDescription("点检异常项：" + itemsJson);
            exc.setDeviceNo(r.getDeviceNo());
            exc.setPhotoUrls(photoUrls);
            exc.setSource("MANUAL");
            exc.setConfirmFlag("1");
            exc.setStatus(IotException.ST_OPEN);
            exc.setDueAt(LocalDateTime.now().plusMinutes(30));
            exc.setCreateBy(SecurityUtils.getCurrentUserId());
            exceptionDao.insert(exc);
            r.setExceptionId(exc.getId());
        }
        recordDao.updateById(r);
        return toMap(r);
    }

    @Override
    public List<Map<String, Object>> list(String deviceNo, String status) {
        LambdaQueryWrapper<IotInspectRecord> qw = new LambdaQueryWrapper<IotInspectRecord>()
                .eq(deviceNo != null && !deviceNo.isBlank(), IotInspectRecord::getDeviceNo, deviceNo)
                .eq(status != null && !status.isBlank(), IotInspectRecord::getStatus, status)
                .orderByDesc(IotInspectRecord::getCreateDate);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IotInspectRecord r : recordDao.selectList(qw)) {
            out.add(toMap(r));
        }
        return out;
    }

    @Override
    public Map<String, Object> scanOverdue() {
        List<IotInspectRecord> overdue = recordDao.selectList(new LambdaQueryWrapper<IotInspectRecord>()
                .eq(IotInspectRecord::getStatus, IotInspectRecord.ST_OPEN)
                .eq(IotInspectRecord::getOverdueFlag, "0")
                .lt(IotInspectRecord::getDueAt, LocalDateTime.now()));
        for (IotInspectRecord r : overdue) {
            r.setOverdueFlag("1");
            recordDao.updateById(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overdueMarked", overdue.size());
        return out;
    }

    // ---------- internal ----------

    private IotInspectRecord require(String id) {
        IotInspectRecord r = recordDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "巡检记录不存在");
        }
        return r;
    }

    private Map<String, Object> toMap(IotInspectRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("planCode", r.getPlanCode());
        m.put("deviceNo", r.getDeviceNo());
        m.put("checkinAt", r.getCheckinAt());
        m.put("checkinBy", r.getCheckinBy());
        m.put("itemsJson", r.getItemsJson());
        m.put("abnormalFlag", r.getAbnormalFlag());
        m.put("exceptionId", r.getExceptionId());
        m.put("photoUrls", r.getPhotoUrls());
        m.put("status", r.getStatus());
        m.put("overdueFlag", r.getOverdueFlag());
        m.put("dueAt", r.getDueAt());
        m.put("submitAt", r.getSubmitAt());
        return m;
    }
}
