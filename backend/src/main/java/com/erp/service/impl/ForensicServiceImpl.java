package com.erp.service.impl;

import com.erp.common.ServiceException;
import com.erp.security.AuditLogWriter;
import com.erp.service.ForensicService;
import com.erp.service.MaskService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 取证导出实现（add-audit-log-console design D4/D5/D7）。
 * 取证包不落盘：生成时全表扫预计算封条（总条数/逐片 SHA-256），下载时按
 * 确定性排序（ORDER BY CHANGE_AT, ID）重查重算，比对不一致即拒绝（数据变更=封条失效）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ForensicServiceImpl implements ForensicService {

    private final JdbcTemplate jdbc;
    private final MaskService maskService;
    private final AuditLogWriter auditLogWriter;

    private static final int SHARD_SIZE = 10_000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> CSV_COLS = List.of(
            "ID", "EMP_ID", "FIELD_NAME", "OLD_VALUE", "NEW_VALUE", "REASON",
            "CHANGE_BY", "CHANGE_AT", "SOURCE_IP", "TERMINAL");
    private static final List<String> MASKED_COLS = List.of("OLD_VALUE", "NEW_VALUE", "REASON");

    // ---------------- 申请与双签（D5）----------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> apply(String purpose, String rangeFrom, String rangeTo,
                                     String actionFilter, String objectFilter) {
        if (!StringUtils.hasText(purpose) || purpose.trim().length() < 10) {
            throw new ServiceException(422, "调阅目的必填且不少于 10 字");
        }
        if (!StringUtils.hasText(rangeFrom) || !StringUtils.hasText(rangeTo)) {
            throw new ServiceException(422, "时间范围必填");
        }
        if (rangeFrom.compareTo(rangeTo) > 0) {
            throw new ServiceException(422, "时间范围起不得晚于止");
        }
        String me = operator();
        String id = uuid();
        jdbc.update("INSERT INTO erp_ops_forensic_request (ID, PURPOSE, RANGE_FROM, RANGE_TO, "
                        + "ACTION_FILTER, OBJECT_FILTER, STATUS, APPLY_BY, CREATE_BY, CREATE_DATE, "
                        + "DEL_FLAG, VER_NO) VALUES (?,?,?,?,?,?, 'APPLYING', ?, ?, NOW(), '0', 0)",
                id, purpose.trim(), rangeFrom, rangeTo,
                StringUtils.hasText(actionFilter) ? actionFilter.trim() : null,
                StringUtils.hasText(objectFilter) ? objectFilter.trim() : null,
                me, me);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", "APPLYING");
        return out;
    }

    @Override
    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT r.ID AS id, r.PURPOSE AS purpose, r.RANGE_FROM AS rangeFrom, "
                        + "r.RANGE_TO AS rangeTo, r.ACTION_FILTER AS actionFilter, "
                        + "r.OBJECT_FILTER AS objectFilter, r.STATUS AS status, "
                        + "r.APPLY_BY AS applyBy, r.AUDIT_SIGN_BY AS auditSignBy, "
                        + "r.AUDIT_SIGN_AT AS auditSignAt, r.DPO_SIGN_BY AS dpoSignBy, "
                        + "r.DPO_SIGN_AT AS dpoSignAt, r.CREATE_DATE AS createDate, "
                        + "p.ID AS packageId, p.STATUS AS packageStatus, p.TOTAL_ROWS AS totalRows, "
                        + "p.SHARD_COUNT AS shardCount, p.EXPIRE_AT AS expireAt, "
                        + "p.DOWNLOAD_COUNT AS downloadCount "
                        + "FROM erp_ops_forensic_request r "
                        + "LEFT JOIN erp_ops_forensic_package p ON p.REQUEST_ID=r.ID AND p.DEL_FLAG='0' "
                        + "WHERE r.DEL_FLAG='0' ORDER BY r.CREATE_DATE DESC");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void signAudit(String id, String opinion) {
        Map<String, Object> req = requireRequest(id);
        String me = operator();
        requireRole(me, "ROLE_UAR_AUDITOR", "内审签署");
        if (me.equals(req.get("APPLY_BY"))) {
            throw new ServiceException(403, "禁止自签：调阅申请人不可签署自己的申请（S-5.6-07 职责分离）");
        }
        if (req.get("AUDIT_SIGN_AT") != null) {
            throw new ServiceException(422, "内审已签署，不可重复");
        }
        jdbc.update("UPDATE erp_ops_forensic_request SET AUDIT_SIGN_BY=?, AUDIT_SIGN_AT=NOW(), "
                + "AUDIT_OPINION=?, UPDATE_DATE=NOW() WHERE ID=?", me, opinion, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void signDpo(String id, String opinion) {
        Map<String, Object> req = requireRequest(id);
        String me = operator();
        requireRole(me, "ROLE_DPO", "DPO 签署");
        if (me.equals(req.get("APPLY_BY"))) {
            throw new ServiceException(403, "禁止自签：调阅申请人不可签署自己的申请（S-5.6-07 职责分离）");
        }
        if (req.get("AUDIT_SIGN_AT") == null) {
            throw new ServiceException(422, "内审尚未签署，DPO 不可先签（S-5.6-07 双签顺序）");
        }
        if (req.get("DPO_SIGN_AT") != null) {
            throw new ServiceException(422, "DPO 已签署，不可重复");
        }
        // 双签齐备 → READY
        jdbc.update("UPDATE erp_ops_forensic_request SET DPO_SIGN_BY=?, DPO_SIGN_AT=NOW(), "
                        + "DPO_OPINION=?, STATUS='READY', UPDATE_DATE=NOW() WHERE ID=?",
                me, opinion, id);
    }

    // ---------------- 生成包与下载（D4/D7）----------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generatePackage(String requestId) {
        Map<String, Object> req = requireRequest(requestId);
        if (req.get("AUDIT_SIGN_AT") == null || req.get("DPO_SIGN_AT") == null) {
            throw new ServiceException(422, "双签未齐备，不可生成取证包（S-5.6-07）");
        }
        Map<String, Object> existing = jdbc.queryForList(
                "SELECT ID, STATUS FROM erp_ops_forensic_package WHERE REQUEST_ID=? AND DEL_FLAG='0'",
                requestId).stream().findFirst().orElse(null);
        if (existing != null && "GENERATED".equals(existing.get("STATUS"))) {
            throw new ServiceException(422, "该申请已有未销毁的取证包");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expire = now.plusDays(7);
        String filterSql = filterSql(req);
        List<Object> filterArgs = filterArgs(req);

        // 全表扫预计算：总条数 + 逐片 SHA-256（确定性排序 ORDER BY CHANGE_AT, ID）
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE " + filterSql, Integer.class,
                filterArgs.toArray());
        int totalRows = total == null ? 0 : total;
        int shardCount = totalRows == 0 ? 0 : (totalRows + SHARD_SIZE - 1) / SHARD_SIZE;
        List<Map<String, Object>> seals = new ArrayList<>();
        for (int s = 0; s < shardCount; s++) {
            List<Map<String, Object>> rows = queryShard(filterSql, filterArgs, s);
            byte[] csv = buildCsv(rows);
            Map<String, Object> seal = new LinkedHashMap<>();
            seal.put("shard", s);
            seal.put("rows", rows.size());
            seal.put("sha256", sha256Hex(csv));
            seals.add(seal);
        }
        String sealJson;
        try {
            Map<String, Object> seal = new LinkedHashMap<>();
            seal.put("generatedAt", now.format(TS));
            seal.put("generatedBy", operator());
            seal.put("totalRows", totalRows);
            seal.put("shardSize", SHARD_SIZE);
            seal.put("shards", seals);
            sealJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(seal);
        } catch (Exception e) {
            throw new ServiceException(500, "封条序列化失败");
        }

        String pkgId = uuid();
        String oldPkg = existing == null ? null : String.valueOf(existing.get("ID"));
        if (oldPkg != null) {
            // 替换：旧包逻辑删（申请仍 READY，可重新生成）
            jdbc.update("UPDATE erp_ops_forensic_package SET DEL_FLAG='1', UPDATE_DATE=NOW() WHERE ID=?",
                    oldPkg);
        }
        jdbc.update("INSERT INTO erp_ops_forensic_package (ID, REQUEST_ID, TOTAL_ROWS, SHARD_SIZE, "
                        + "SHARD_COUNT, SEAL_JSON, STATUS, GENERATED_BY, GENERATED_AT, EXPIRE_AT, "
                        + "DOWNLOAD_COUNT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?,?,?,?,?,?, 'GENERATED', ?, ?, ?, 0, ?, NOW(), '0', 0)",
                pkgId, requestId, totalRows, SHARD_SIZE, shardCount, sealJson,
                operator(), now, expire, operator());
        jdbc.update("UPDATE erp_ops_forensic_request SET STATUS='PACKAGED', UPDATE_DATE=NOW() WHERE ID=?",
                requestId);
        auditLogWriter.write(requestId, "FORENSIC_PACKAGE_GENERATED",
                null, pkgId, "取证包生成：总条数 " + totalRows + "，分片 " + shardCount);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("packageId", pkgId);
        out.put("totalRows", totalRows);
        out.put("shardCount", shardCount);
        out.put("expireAt", expire.format(TS));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> download(String packageId, int shard) {
        Map<String, Object> pkg = requirePackage(packageId);
        Map<String, Object> req = requireRequest(String.valueOf(pkg.get("REQUEST_ID")));
        // 三重校验：双签齐备 + GENERATED + 未过期
        if (req.get("AUDIT_SIGN_AT") == null || req.get("DPO_SIGN_AT") == null) {
            throw new ServiceException(422, "双签未齐备，不可下载");
        }
        if (!"GENERATED".equals(pkg.get("STATUS"))) {
            throw new ServiceException(422, "取证包已销毁，不可下载");
        }
        LocalDateTime expire = pkg.get("EXPIRE_AT") instanceof LocalDateTime dt ? dt : null;
        if (expire != null && expire.isBefore(LocalDateTime.now())) {
            throw new ServiceException(422, "取证包已过期（7 天有效期），不可下载（S-5.6-07）");
        }
        int shardCount = ((Number) pkg.get("SHARD_COUNT")).intValue();
        if (shard < 0 || shard >= shardCount) {
            throw new ServiceException(404, "分片不存在：" + shard + "（共 " + shardCount + " 片）");
        }

        String filterSql = filterSql(req);
        List<Object> filterArgs = filterArgs(req);
        List<Map<String, Object>> rows = queryShard(filterSql, filterArgs, shard);
        byte[] csv = buildCsv(rows);
        String actual = sha256Hex(csv);
        String expected = sealOfShard(String.valueOf(pkg.get("SEAL_JSON")), shard);
        if (expected == null || !expected.equals(actual)) {
            // 数据已变更 → 封条失效（防篡改语义），拒绝并告警留痕
            auditLogWriter.write(String.valueOf(pkg.get("REQUEST_ID")), "FORENSIC_SEAL_MISMATCH",
                    expected, actual, "取证包分片 " + shard + " 校验和与封条不一致，拒绝下载");
            throw new ServiceException(500, "分片校验和与封条不一致（数据已变更），下载被拒绝（BR-5.6-14）");
        }

        jdbc.update("UPDATE erp_ops_forensic_package SET DOWNLOAD_COUNT=DOWNLOAD_COUNT+1, "
                + "UPDATE_DATE=NOW() WHERE ID=?", packageId);
        // 下载动作自身留痕（审计的审计，门面写入带 IP）
        auditLogWriter.write(String.valueOf(pkg.get("REQUEST_ID")), "FORENSIC_DOWNLOAD",
                null, "shard=" + shard + " rows=" + rows.size(),
                "取证包分片下载（包 " + packageId + "）");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filename", "audit-forensic-" + packageId + "-shard" + shard + ".csv");
        out.put("content", csv);
        out.put("sha256", actual);
        out.put("rows", rows.size());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmUsed(String packageId) {
        Map<String, Object> pkg = requirePackage(packageId);
        if ("DESTROYED".equals(pkg.get("STATUS"))) {
            // 幂等
            return;
        }
        destroy(pkg, "使用完毕确认销毁");
    }

    @Override
    public Map<String, Object> sweepExpired() {
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT ID, REQUEST_ID FROM erp_ops_forensic_package "
                        + "WHERE STATUS='GENERATED' AND DEL_FLAG='0' AND EXPIRE_AT <= NOW()");
        int destroyed = 0;
        for (Map<String, Object> d : due) {
            try {
                Map<String, Object> pkg = requirePackage(String.valueOf(d.get("ID")));
                destroy(pkg, "7 天有效期届满自动销毁（S-5.6-07）");
                destroyed++;
            } catch (Exception e) {
                log.warn("forensic sweep destroy failed for {}: {}", d.get("ID"), e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("destroyed", destroyed);
        return out;
    }

    // ---------------- 内部 ----------------

    private void destroy(Map<String, Object> pkg, String reason) {
        jdbc.update("UPDATE erp_ops_forensic_package SET STATUS='DESTROYED', DESTROYED_AT=NOW(), "
                + "UPDATE_DATE=NOW() WHERE ID=?", pkg.get("ID"));
        auditLogWriter.write(String.valueOf(pkg.get("REQUEST_ID")), "FORENSIC_DESTROYED",
                String.valueOf(pkg.get("STATUS")), "DESTROYED", reason);
    }

    private Map<String, Object> requireRequest(String id) {
        Map<String, Object> req = jdbc.queryForList(
                "SELECT ID, PURPOSE, RANGE_FROM, RANGE_TO, ACTION_FILTER, OBJECT_FILTER, STATUS, "
                        + "APPLY_BY, AUDIT_SIGN_AT, DPO_SIGN_AT FROM erp_ops_forensic_request "
                        + "WHERE ID=? AND DEL_FLAG='0'", id).stream().findFirst().orElse(null);
        if (req == null) {
            throw new ServiceException(404, "调阅申请不存在：" + id);
        }
        return req;
    }

    private Map<String, Object> requirePackage(String id) {
        Map<String, Object> pkg = jdbc.queryForList(
                "SELECT ID, REQUEST_ID, TOTAL_ROWS, SHARD_COUNT, SEAL_JSON, STATUS, EXPIRE_AT "
                        + "FROM erp_ops_forensic_package WHERE ID=? AND DEL_FLAG='0'", id)
                .stream().findFirst().orElse(null);
        if (pkg == null) {
            throw new ServiceException(404, "取证包不存在：" + id);
        }
        return pkg;
    }

    /** 当前用户须持有指定角色（DB 校验，签署端点 URL 级为 authenticated） */
    private void requireRole(String userId, String roleCode, String action) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                        + "WHERE ur.USER_ID=? AND r.ROLE_CODE=?", Integer.class, userId, roleCode);
        if (cnt == null || cnt == 0) {
            throw new ServiceException(403, action + "需要 " + roleCode + " 角色");
        }
    }

    /** 申请过滤条件 → SQL 片段（确定性排序在 queryShard 内拼接） */
    private String filterSql(Map<String, Object> req) {
        StringBuilder sb = new StringBuilder("CHANGE_AT >= ? AND CHANGE_AT <= ? ");
        if (StringUtils.hasText(str(req.get("ACTION_FILTER")))) {
            sb.append("AND FIELD_NAME LIKE ? ");
        }
        if (StringUtils.hasText(str(req.get("OBJECT_FILTER")))) {
            sb.append("AND EMP_ID LIKE ? ");
        }
        return sb.toString();
    }

    private List<Object> filterArgs(Map<String, Object> req) {
        List<Object> args = new ArrayList<>();
        args.add(str(req.get("RANGE_FROM")));
        args.add(str(req.get("RANGE_TO")));
        if (StringUtils.hasText(str(req.get("ACTION_FILTER")))) {
            args.add("%" + str(req.get("ACTION_FILTER")) + "%");
        }
        if (StringUtils.hasText(str(req.get("OBJECT_FILTER")))) {
            args.add("%" + str(req.get("OBJECT_FILTER")) + "%");
        }
        return args;
    }

    private List<Map<String, Object>> queryShard(String filterSql, List<Object> filterArgs, int shard) {
        List<Object> args = new ArrayList<>(filterArgs);
        args.add(SHARD_SIZE);
        args.add(shard * SHARD_SIZE);
        return jdbc.queryForList(
                "SELECT ID, EMP_ID, FIELD_NAME, OLD_VALUE, NEW_VALUE, REASON, CHANGE_BY, "
                        + "CHANGE_AT, SOURCE_IP, TERMINAL FROM hr_change_log WHERE " + filterSql
                        + "ORDER BY CHANGE_AT, ID LIMIT ? OFFSET ?", args.toArray());
    }

    /** CSV 字节（确定性：固定列序 + 固定时间格式 + 脱敏应用于掩码列） */
    private byte[] buildCsv(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", CSV_COLS)).append('\n');
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>();
            for (String col : CSV_COLS) {
                Object v = r.get(col);
                String s;
                if (v == null) {
                    s = "";
                } else if (v instanceof LocalDateTime dt) {
                    s = dt.format(TS);
                } else {
                    s = String.valueOf(v);
                }
                if (MASKED_COLS.contains(col)) {
                    s = maskService.applyAll(s);
                }
                cells.add(csvEscape(s));
            }
            sb.append(String.join(",", cells)).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String csvEscape(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /** 封条 JSON 提取指定分片 SHA-256 */
    @SuppressWarnings("unchecked")
    private String sealOfShard(String sealJson, int shard) {
        try {
            Map<String, Object> seal = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(sealJson, Map.class);
            List<Map<String, Object>> shards = (List<Map<String, Object>>) seal.get("shards");
            for (Map<String, Object> s : shards) {
                if (shard == ((Number) s.get("shard")).intValue()) {
                    return String.valueOf(s.get("sha256"));
                }
            }
        } catch (Exception e) {
            log.warn("seal parse failed: {}", e.getMessage());
        }
        return null;
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new ServiceException(500, "SHA-256 计算失败");
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String operator() {
        String me = SecurityUtils.getCurrentUserId();
        return me == null ? "system" : me;
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
