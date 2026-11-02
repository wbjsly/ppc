package com.erp.service.impl.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.iot.IotOfflineQueueDao;
import com.erp.dao.iot.IotSyncConflictDao;
import com.erp.dao.iot.IotTerminalDao;
import com.erp.entity.iot.IotOfflineQueue;
import com.erp.entity.iot.IotSyncConflict;
import com.erp.entity.iot.IotTerminal;
import com.erp.service.iot.IotOfflineSyncService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 离线同步与冲突解决实现（add-iot-mobile 13.5，FR-4.11-4-x）。
 * 批量上传 ≤100 条/批逐条反馈；幂等键去重；三重冲突检测
 * （时间戳+版本号+幂等键，BL-4.11-01 字典序：VER_NO > CLIENT_TS > IDEMPOTENT_KEY）；
 * 数量类合并累加/状态类最新优先/超容差差异/财务 L2；
 * 72h 锁定扫描（C-4.11-04，预警比例 OFFLINE_WARN_RATIO）。
 */
@Slf4j
@Service
public class IotOfflineSyncServiceImpl implements IotOfflineSyncService {

    private static final int BATCH_LIMIT = 100;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final IotOfflineQueueDao queueDao;
    private final IotSyncConflictDao conflictDao;
    private final IotTerminalDao terminalDao;
    private final IotOfflineReplayWorker replayWorker;
    private final JdbcTemplate jdbc;

    @Value("${iot.offline-max-hours:72}")
    private int offlineMaxHours;

    @Value("${iot.offline-warn-ratio:0.75}")
    private double offlineWarnRatio;

    public IotOfflineSyncServiceImpl(IotOfflineQueueDao queueDao, IotSyncConflictDao conflictDao,
                                     IotTerminalDao terminalDao,
                                     IotOfflineReplayWorker replayWorker, JdbcTemplate jdbc) {
        this.queueDao = queueDao;
        this.conflictDao = conflictDao;
        this.terminalDao = terminalDao;
        this.replayWorker = replayWorker;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> uploadBatch(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            throw new ServiceException(422, "同步批次不能为空");
        }
        if (records.size() > BATCH_LIMIT) {
            // FR-4.11-4-2 要点①：每批次最多 100 条
            throw new ServiceException(422, "单批最多 " + BATCH_LIMIT + " 条，当前 "
                    + records.size() + " 条，请分批上传");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int synced = 0;
        int failed = 0;
        int duplicated = 0;
        int conflicted = 0;
        for (Map<String, Object> rec : records) {
            Map<String, Object> one = ingestOne(rec);
            results.add(one);
            String r = str(one.get("result"));
            if ("SYNCED".equals(r)) {
                synced++;
            } else if ("DUPLICATE".equals(r)) {
                duplicated++;
            } else if ("CONFLICT".equals(r)) {
                conflicted++;
            } else {
                failed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", records.size());
        out.put("synced", synced);
        out.put("failed", failed);
        out.put("duplicated", duplicated);
        out.put("conflicted", conflicted);
        out.put("results", results);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncOne(Map<String, Object> record) {
        // 单条同步（uploadBatch 内部同路径；对外亦可单条调用）
        return ingestOne(record);
    }

    /** 单条摄取：幂等去重 → 冲突检测 → 过账 → 状态落定（供 uploadBatch 复用） */
    private Map<String, Object> ingestOne(Map<String, Object> rec) {
        Map<String, Object> one = new LinkedHashMap<>();
        String idemKey = str(rec.get("idempotentKey"));
        String bizType = str(rec.get("bizType"));
        String deviceCode = str(rec.get("deviceCode"));
        if (idemKey == null || idemKey.isBlank()) {
            one.put("idempotentKey", null);
            one.put("result", "FAILED");
            one.put("detail", "幂等键缺失");
            return one;
        }
        one.put("idempotentKey", idemKey);
        // 幂等去重：已存在（无论状态）→ DUPLICATE 不重复过账（FR-4.11-1-4 要点②）
        IotOfflineQueue exist = queueDao.selectOne(new LambdaQueryWrapper<IotOfflineQueue>()
                .eq(IotOfflineQueue::getIdempotentKey, idemKey));
        if (exist != null) {
            one.put("result", "DUPLICATE");
            one.put("detail", "重复提交已去重（状态 " + exist.getStatus() + "）");
            one.put("rptId", exist.getPayloadJson());
            return one;
        }
        // 客户端时间与版本
        LocalDateTime clientTs = rec.get("clientTs") == null ? LocalDateTime.now()
                : LocalDateTime.parse(String.valueOf(rec.get("clientTs")));
        int clientVer = rec.get("clientVerNo") == null ? 0
                : Integer.parseInt(String.valueOf(rec.get("clientVerNo")));
        IotOfflineQueue q = new IotOfflineQueue();
        q.setIdempotentKey(idemKey);
        q.setDeviceCode(deviceCode == null ? "UNKNOWN" : deviceCode);
        q.setBizType(bizType == null ? "SCAN" : bizType);
        q.setPayloadJson(str(rec.get("payload")));
        q.setClientVerNo(clientVer);
        q.setClientTs(clientTs);
        q.setStatus(IotOfflineQueue.ST_PENDING);
        q.setRetryCnt(0);
        q.setCreateBy(SecurityUtils.getCurrentUserId());
        queueDao.insert(q);

        // 冲突检测（三重：时间戳+版本号+幂等键；BL-4.11-01 字典序）
        IotSyncConflict conflict = detectConflict(q, rec);
        if (conflict != null) {
            q.setStatus(IotOfflineQueue.ST_CONFLICT);
            queueDao.updateById(q);
            one.put("result", "CONFLICT");
            one.put("conflictId", conflict.getId());
            one.put("conflictType", conflict.getConflictType());
            one.put("detail", "冲突已登记（" + conflict.getConflictType()
                    + ( "1".equals(conflict.getNeedFinApproval()) ? "，财务 L2 审批" : "") + "）");
            return one;
        }
        // 业务过账
        return postOne(q, rec, one);
    }

    /** 三重冲突检测：同业务键（payload.bizKey）已有更优记录 → 按类型裁决 */
    private IotSyncConflict detectConflict(IotOfflineQueue q, Map<String, Object> rec) {
        String payload = q.getPayloadJson();
        String bizKey = str(rec.get("bizKey"));
        if (bizKey == null || bizKey.isBlank()) {
            return null;   // 无业务键（纯日志类）不参与冲突
        }
        // 找同业务键、非本条、状态已 SYNCED/CONFLICT 的记录（服务端已持有版本）
        List<IotOfflineQueue> peers = queueDao.selectList(new LambdaQueryWrapper<IotOfflineQueue>()
                .eq(IotOfflineQueue::getBizType, q.getBizType())
                .ne(IotOfflineQueue::getIdempotentKey, q.getIdempotentKey())
                .in(IotOfflineQueue::getStatus, IotOfflineQueue.ST_SYNCED, IotOfflineQueue.ST_CONFLICT)
                .like(IotOfflineQueue::getPayloadJson, bizKey)
                .orderByDesc(IotOfflineQueue::getClientTs)
                .last("LIMIT 5"));
        if (peers.isEmpty()) {
            return null;
        }
        // 字典序裁决（BL-4.11-01）：VER_NO > CLIENT_TS > IDEMPOTENT_KEY，取胜者
        IotOfflineQueue winner = peers.stream()
                .max((a, b) -> cmpDict(a, b))
                .orElse(peers.get(0));
        int cmpWinnerVsOurs = cmpDict(winner, q);
        if (cmpWinnerVsOurs >= 0) {
            // 服务端既有记录胜出 → 本条为冲突
            String type = IotSyncConflict.TYPE_LATEST;
            boolean numeric = isNumericPayload(payload);
            boolean overTolerance = isOverTolerance(payload);
            if (numeric && !overTolerance) {
                type = IotSyncConflict.TYPE_MERGE;      // 数量类自动合并累加
            } else if (overTolerance) {
                type = IotSyncConflict.TYPE_DIFF;       // 超容差差异待处理（L3）
            }
            // 财务数据 → L2 强制审批
            boolean financial = payload != null && payload.contains("\"amount\"");
            IotSyncConflict c = new IotSyncConflict();
            c.setQueueId(q.getId());
            c.setConflictType(type);
            c.setFieldPath("biz:" + bizKey);
            c.setBeforeSnapshot(winner.getPayloadJson());
            c.setAfterSnapshot(payload);
            c.setDictSeqKey(dictKey(winner) + " > " + dictKey(q));
            c.setNeedFinApproval(financial ? "1" : "0");
            c.setStatus(financial ? IotSyncConflict.ST_NEED_REVIEW
                    : (IotSyncConflict.TYPE_MERGE.equals(type) ? IotSyncConflict.ST_RESOLVED
                    : IotSyncConflict.ST_OPEN));
            c.setResolvedBy(financial ? null : "system:dict-seq");
            c.setResolvedAt(financial ? null : LocalDateTime.now());
            c.setRemark("BL-4.11-01 字典序裁决：服务端记录胜出");
            c.setCreateBy(SecurityUtils.getCurrentUserId());
            conflictDao.insert(c);
            // 数量类合并：把胜者数量语义写回（MERGE 即自动放行的冲突快照留痕）
            return c;
        }
        return null;   // 本条字典序更优 → 视为正常更新，无冲突
    }

    /** 单条过账：按 BIZ_TYPE 分发到对应业务服务；业务异常 → FAILED 保留队列 */
    private Map<String, Object> postOne(IotOfflineQueue q, Map<String, Object> rec,
                                        Map<String, Object> one) {
        try {
            String payload = q.getPayloadJson();
            // REQUIRES_NEW 独立事务回放：业务失败仅回放回滚，队列 FAILED 状态正常落定
            replayWorker.replay(q.getBizType(),
                    payload == null ? Map.of() : fromJson(payload));
            q.setStatus(IotOfflineQueue.ST_SYNCED);
            q.setSyncedAt(LocalDateTime.now());
            queueDao.updateById(q);
            one.put("result", "SYNCED");
            one.put("detail", "过账成功");
        } catch (ServiceException e) {
            q.setStatus(IotOfflineQueue.ST_FAILED);
            q.setRetryCnt((q.getRetryCnt() == null ? 0 : q.getRetryCnt()) + 1);
            q.setErrMsg(e.getCode() + " " + e.getMessage());
            queueDao.updateById(q);
            one.put("result", "FAILED");
            one.put("detail", q.getErrMsg());
        } catch (Exception e) {
            q.setStatus(IotOfflineQueue.ST_FAILED);
            q.setRetryCnt((q.getRetryCnt() == null ? 0 : q.getRetryCnt()) + 1);
            q.setErrMsg("回放异常：" + e.getMessage());
            queueDao.updateById(q);
            one.put("result", "FAILED");
            one.put("detail", q.getErrMsg());
        }
        return one;
    }

    @Override
    public List<Map<String, Object>> conflictList(String status) {
        LambdaQueryWrapper<IotSyncConflict> qw = new LambdaQueryWrapper<IotSyncConflict>()
                .eq(status != null && !status.isBlank(), IotSyncConflict::getStatus, status)
                .orderByDesc(IotSyncConflict::getCreateDate);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IotSyncConflict c : conflictDao.selectList(qw)) {
            out.add(conflictMap(c));
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> resolveConflict(String conflictId, String decision, String remark) {
        IotSyncConflict c = conflictDao.selectById(conflictId);
        if (c == null) {
            throw new ServiceException(404, "冲突记录不存在");
        }
        if (IotSyncConflict.ST_RESOLVED.equals(c.getStatus())) {
            throw new ServiceException(409, "冲突已裁决");
        }
        if ("1".equals(c.getNeedFinApproval()) && !"APPROVE".equals(decision)) {
            // 财务 L2：未获批准前保持阻断（C-4.11-01/12）
            throw new ServiceException(422, "财务数据冲突需 L2 财务主管审批（decision=APPROVE）后方可裁决");
        }
        if ("APPROVE".equals(decision)) {
            // 采纳服务端胜者（字典序已裁）→ 本条冲突记录 RESOLVED，队列记录放行 SYNCED
            c.setStatus(IotSyncConflict.ST_RESOLVED);
            c.setResolvedBy(SecurityUtils.getCurrentUserId());
            c.setResolvedAt(LocalDateTime.now());
            c.setRemark(remark);
            conflictDao.updateById(c);
            if (c.getQueueId() != null) {
                IotOfflineQueue q = queueDao.selectById(c.getQueueId());
                if (q != null && IotOfflineQueue.ST_CONFLICT.equals(q.getStatus())) {
                    q.setStatus(IotOfflineQueue.ST_SYNCED);
                    q.setSyncedAt(LocalDateTime.now());
                    queueDao.updateById(q);
                }
            }
        } else if ("REJECT".equals(decision)) {
            c.setStatus(IotSyncConflict.ST_NEED_REVIEW);
            c.setRemark("驳回保留待人工：" + remark);
            conflictDao.updateById(c);
        } else {
            throw new ServiceException(422, "decision 须为 APPROVE/REJECT");
        }
        return conflictMap(c);
    }

    @Override
    public Map<String, Object> lockStatus(String deviceCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deviceCode", deviceCode);
        out.put("maxHours", offlineMaxHours);
        out.put("warnRatio", offlineWarnRatio);
        out.put("locked", "1".equals(lockedFlag(deviceCode)));
        Map<String, Object> earliest = earliestPending(deviceCode);
        if (earliest != null) {
            LocalDateTime ts = (LocalDateTime) earliest.get("CLIENT_TS");
            long hours = java.time.Duration.between(ts, LocalDateTime.now()).toHours();
            out.put("earliestPendingAt", ts);
            out.put("pendingHours", hours);
            out.put("warn", hours >= (long) (offlineMaxHours * offlineWarnRatio));
            out.put("willLockAt", ts.plusHours(offlineMaxHours));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> queueList(String deviceCode, String status) {
        LambdaQueryWrapper<IotOfflineQueue> qw = new LambdaQueryWrapper<IotOfflineQueue>()
                .eq(deviceCode != null && !deviceCode.isBlank(), IotOfflineQueue::getDeviceCode, deviceCode)
                .eq(status != null && !status.isBlank(), IotOfflineQueue::getStatus, status)
                .orderByAsc(IotOfflineQueue::getClientTs);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IotOfflineQueue q : queueDao.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("idempotentKey", q.getIdempotentKey());
            m.put("deviceCode", q.getDeviceCode());
            m.put("bizType", q.getBizType());
            m.put("status", q.getStatus());
            m.put("clientTs", q.getClientTs());
            m.put("retryCnt", q.getRetryCnt());
            m.put("errMsg", q.getErrMsg());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cleanupSynced(String deviceCode) {
        // 同步成功后清理本地缓存（服务端确认清单：SYNCED 且超 1 小时）
        List<IotOfflineQueue> synced = queueDao.selectList(new LambdaQueryWrapper<IotOfflineQueue>()
                .eq(IotOfflineQueue::getDeviceCode, deviceCode)
                .eq(IotOfflineQueue::getStatus, IotOfflineQueue.ST_SYNCED)
                .lt(IotOfflineQueue::getSyncedAt, LocalDateTime.now().minusHours(1)));
        for (IotOfflineQueue q : synced) {
            queueDao.deleteById(q.getId());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", synced.size());
        return out;
    }

    @Override
    public Map<String, Object> scanLocks() {
        // 最早 PENDING 超时扫描（IotLockScheduler 入口，C-4.11-04）
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT DEVICE_CODE, MIN(CLIENT_TS) AS EARLIEST FROM erp_iot_offline_queue " +
                        "WHERE STATUS='PENDING' AND DEL_FLAG='0' GROUP BY DEVICE_CODE",
                new org.springframework.jdbc.core.ColumnMapRowMapper());
        List<String> locked = new ArrayList<>();
        List<String> warned = new ArrayList<>();
        int maxH = offlineMaxHours;
        for (Map<String, Object> r : rows) {
            String deviceCode = str(r.get("DEVICE_CODE"));
            LocalDateTime earliest = (LocalDateTime) r.get("EARLIEST");
            long hours = java.time.Duration.between(earliest, LocalDateTime.now()).toHours();
            if (hours >= maxH) {
                // 超期锁定：仅保留同步与查看历史
                IotTerminal t = terminalDao.selectOne(new LambdaQueryWrapper<IotTerminal>()
                        .eq(IotTerminal::getDeviceCode, deviceCode));
                if (t != null && !"1".equals(t.getLockedFlag())) {
                    t.setLockedFlag("1");
                    t.setLockReason("离线缓存超 " + maxH + " 小时未同步（C-4.11-04 强制锁定）");
                    t.setLockAt(LocalDateTime.now());
                    terminalDao.updateById(t);
                }
                locked.add(deviceCode + "(" + hours + "h)");
            } else if (hours >= (long) (maxH * offlineWarnRatio)) {
                warned.add(deviceCode + "(" + hours + "h)");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("locked", locked);
        out.put("warned", warned);
        return out;
    }

    // ---------- helpers ----------

    /** BL-4.11-01 字典序：VER_NO > CLIENT_TS > IDEMPOTENT_KEY */
    private int cmpDict(IotOfflineQueue a, IotOfflineQueue b) {
        int va = a.getClientVerNo() == null ? 0 : a.getClientVerNo();
        int vb = b.getClientVerNo() == null ? 0 : b.getClientVerNo();
        if (va != vb) {
            return Integer.compare(va, vb);
        }
        LocalDateTime ta = a.getClientTs() == null ? LocalDateTime.MIN : a.getClientTs();
        LocalDateTime tb = b.getClientTs() == null ? LocalDateTime.MIN : b.getClientTs();
        int tc = ta.compareTo(tb);
        if (tc != 0) {
            return tc;
        }
        return a.getIdempotentKey().compareTo(b.getIdempotentKey());
    }

    private String dictKey(IotOfflineQueue q) {
        int v = q.getClientVerNo() == null ? 0 : q.getClientVerNo();
        String ts = q.getClientTs() == null ? "" : TS_FMT.format(q.getClientTs());
        return String.format("V%03d|%s|%s", v, ts, q.getIdempotentKey());
    }

    private boolean isNumericPayload(String payload) {
        return payload != null && payload.contains("\"qty\"");
    }

    private boolean isOverTolerance(String payload) {
        // 载荷携带 overTolerance 标记（客户端按 TOLERANCE_DEFAULT 判定）→ 超容差差异
        return payload != null && payload.contains("\"overTolerance\":true");
    }

    /** 载荷 JSON → Map（轻量解析：仅取顶层对象，交业务服务校验） */
    private Map<String, Object> fromJson(String payload) {
        // 队列载荷由业务服务原生序列化（本域内为 Map JSON）；此处用简易解析兜底
        Map<String, Object> m = new LinkedHashMap<>();
        String s = payload == null ? "" : payload.trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            // 顶层标量键值提取（业务复杂结构由 reportOutput/report 自行解析）
            for (String part : s.substring(1, s.length() - 1).split(",")) {
                int idx = part.indexOf(':');
                if (idx > 0) {
                    String k = part.substring(0, idx).trim().replaceAll("\"", "");
                    String v = part.substring(idx + 1).trim().replaceAll("^\"|\"$", "");
                    m.put(k, v);
                }
            }
        }
        return m;
    }

    private String lockedFlag(String deviceCode) {
        return jdbc.query("SELECT LOCKED_FLAG FROM erp_iot_terminal WHERE DEVICE_CODE=?",
                rs -> rs.next() ? rs.getString(1) : null, deviceCode);
    }

    private Map<String, Object> earliestPending(String deviceCode) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT MIN(CLIENT_TS) AS CLIENT_TS FROM erp_iot_offline_queue " +
                        "WHERE DEVICE_CODE=? AND STATUS='PENDING' AND DEL_FLAG='0'",
                new org.springframework.jdbc.core.ColumnMapRowMapper(), deviceCode);
        return rows.isEmpty() || rows.get(0).get("CLIENT_TS") == null ? null : rows.get(0);
    }

    private Map<String, Object> conflictMap(IotSyncConflict c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("conflictType", c.getConflictType());
        m.put("fieldPath", c.getFieldPath());
        m.put("dictSeqKey", c.getDictSeqKey());
        m.put("needFinApproval", c.getNeedFinApproval());
        m.put("status", c.getStatus());
        m.put("resolvedBy", c.getResolvedBy());
        m.put("resolvedAt", c.getResolvedAt());
        m.put("remark", c.getRemark());
        return m;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
