package com.erp.service.impl.intf;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfEventTypeDao;
import com.erp.entity.intf.IntfEventType;
import com.erp.service.intf.EventTypeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 事件定义注册表实现（add-event-subscription，design D4 / spec event-subscription 1.1~1.2）。
 * 命名规范 {对象}_{动作}（可带域前缀 PROC./VMI.）；Schema 仅校验必填清单（字段+类型）；
 * Schema 语义变更（删必填/改类型/新增必填=收紧）未升 Major → 422。
 */
@Slf4j
@Service
public class EventTypeServiceImpl implements EventTypeService {

    /** 命名 {对象}_{动作}：可选大写域前缀（可多段点分），对象与动作至少两段（_ 或 . 分隔），禁驼峰/小写 */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^(?:[A-Z][A-Z0-9]*\\.)*[A-Z][A-Z0-9]*(?:[._][A-Z][A-Z0-9]*)+$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IntfEventTypeDao dao;

    public EventTypeServiceImpl(IntfEventTypeDao dao) {
        this.dao = dao;
    }

    @Override
    public Page<IntfEventType> page(long current, long size, String eventType, String status) {
        LambdaQueryWrapper<IntfEventType> qw = new LambdaQueryWrapper<>();
        qw.like(eventType != null && !eventType.trim().isEmpty(),
                IntfEventType::getEventType, eventType == null ? "" : eventType.trim());
        qw.eq(status != null && !status.trim().isEmpty(),
                IntfEventType::getStatus, status == null ? "" : status.trim());
        qw.orderByAsc(IntfEventType::getEventType);
        return dao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> create(Map<String, Object> body) {
        requireArch("注册事件类型");
        String type = str(body.get("eventType"));
        String name = str(body.get("typeName"));
        if (type == null || name == null) {
            throw new ServiceException(400, "eventType 与 typeName 必填");
        }
        if (!NAME_PATTERN.matcher(type).matches()) {
            throw new ServiceException(422, "事件类型命名须为 {业务对象}_{动作}（如 PO_CREATED / PROC.PO_PUSHED）");
        }
        if (dao.selectByType(type) != null) {
            throw new ServiceException(409, "事件类型已存在：" + type);
        }
        String schema = normalizeSchema(str(body.get("schemaJson")));
        IntfEventType e = new IntfEventType();
        e.setEventType(type);
        e.setTypeName(name);
        e.setSchemaJson(schema);
        e.setVersionMajor(intOr(body.get("versionMajor"), 1));
        e.setVersionMinor(intOr(body.get("versionMinor"), 0));
        e.setStatus(IntfEventType.ST_ENABLED);
        e.setRemark(str(body.get("remark")));
        dao.insert(e);
        return Map.of("id", e.getId(), "eventType", e.getEventType(),
                "version", e.getVersionMajor() + "." + e.getVersionMinor());
    }

    @Override
    public Map<String, Object> update(String id, Map<String, Object> body) {
        requireArch("修改事件类型");
        IntfEventType e = require(id);
        String name = str(body.get("typeName"));
        if (name != null) {
            e.setTypeName(name);
        }
        String schema = normalizeSchema(str(body.get("schemaJson")));
        int newMajor = intOr(body.get("versionMajor"), e.getVersionMajor());
        int newMinor = intOr(body.get("versionMinor"), e.getVersionMinor());
        if (schema != null && !schema.equals(e.getSchemaJson())) {
            // 语义比对：required 清单（字段+类型）为契约本体，任何差异视为删/改/收紧 → 必须升 Major
            if (newMajor <= e.getVersionMajor()) {
                throw new ServiceException(422, "Schema 变更须升 Major 版本（当前 "
                        + e.getVersionMajor() + "." + e.getVersionMinor()
                        + "，新版本不得低于 " + (e.getVersionMajor() + 1) + ".0）");
            }
            e.setSchemaJson(schema);
        }
        e.setVersionMajor(newMajor);
        e.setVersionMinor(newMinor);
        if (body.containsKey("status")) {
            String st = str(body.get("status"));
            if (!IntfEventType.ST_ENABLED.equals(st) && !IntfEventType.ST_DISABLED.equals(st)) {
                throw new ServiceException(400, "status 仅支持 ENABLED/DISABLED");
            }
            e.setStatus(st);
        }
        if (body.containsKey("remark")) {
            e.setRemark(str(body.get("remark")));
        }
        dao.updateById(e);
        return Map.of("id", e.getId(), "eventType", e.getEventType(),
                "version", e.getVersionMajor() + "." + e.getVersionMinor(), "status", e.getStatus());
    }

    @Override
    public Map<String, Object> toggle(String id, String status) {
        requireArch("启停事件类型");
        IntfEventType e = require(id);
        if (!IntfEventType.ST_ENABLED.equals(status) && !IntfEventType.ST_DISABLED.equals(status)) {
            throw new ServiceException(400, "status 仅支持 ENABLED/DISABLED");
        }
        e.setStatus(status);
        dao.updateById(e);
        return Map.of("id", e.getId(), "eventType", e.getEventType(), "status", status);
    }

    @Override
    public ValidateResult validateForPublish(String eventType, Map<String, Object> payload) {
        IntfEventType e = dao.selectByType(eventType);
        if (e == null) {
            throw new ServiceException(422, "事件类型未注册：" + eventType
                    + "，请先在 12.2.1 事件定义注册（FR-4.9-2-1）");
        }
        if (!IntfEventType.ST_ENABLED.equals(e.getStatus())) {
            throw new ServiceException(422, "事件类型已停用：" + eventType + "，拒绝发布新事件");
        }
        List<Map<String, Object>> required = parseRequired(e.getSchemaJson());
        List<String> missing = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        for (Map<String, Object> r : required) {
            String field = String.valueOf(r.get("field"));
            String expect = String.valueOf(r.get("type"));
            Object v = payload == null ? null : payload.get(field);
            if (v == null) {
                missing.add(field);
            } else if (!typeMatches(expect, v)) {
                mismatched.add(field + "(期望 " + expect + "，实际 " + v.getClass().getSimpleName() + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "事件 " + eventType + " 载荷校验失败，必填字段缺失："
                    + String.join(", ", missing) + "（L3 拒发，FR-4.9-2-2）");
        }
        if (!mismatched.isEmpty()) {
            // L4 警告放行：不卡发布主链（spec 1.2）
            log.warn("event {} payload type mismatch (L4 warn, passed): {}", eventType, mismatched);
        }
        return new ValidateResult(e.getVersionMajor() + "." + e.getVersionMinor(), mismatched);
    }

    // ---------------------------------------------------------------- helpers

    private IntfEventType require(String id) {
        IntfEventType e = dao.selectById(id);
        if (e == null) {
            throw new ServiceException(404, "事件类型不存在");
        }
        return e;
    }

    /** 解析 {"required":[{"field":"poNo","type":"string"},...]}；空/非法 JSON 返回空清单（不卡存量） */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> parseRequired(String schemaJson) {
        if (schemaJson == null || schemaJson.trim().isEmpty()) {
            return List.of();
        }
        try {
            Map<String, Object> root = MAPPER.readValue(schemaJson, Map.class);
            Object req = root.get("required");
            if (req instanceof List) {
                return (List<Map<String, Object>>) req;
            }
            return List.of();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static boolean typeMatches(String expect, Object v) {
        if (expect == null) {
            return true;
        }
        return switch (expect.toLowerCase()) {
            case "string" -> v instanceof String;
            case "number" -> v instanceof Number;
            case "boolean" -> v instanceof Boolean;
            case "array" -> v instanceof List;
            case "object" -> v instanceof Map;
            default -> true;
        };
    }

    /** 规范化：仅接受 {"required":[...]} 结构（其余键忽略），字段名去重保序 */
    static String normalizeSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.trim().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> required;
        try {
            Map<String, Object> root = MAPPER.readValue(schemaJson, Map.class);
            Object req = root.get("required");
            if (!(req instanceof List)) {
                throw new ServiceException(400, "Schema 须为 {\"required\":[{\"field\":\"x\",\"type\":\"string\"},...]} 结构");
            }
            required = (List<Map<String, Object>>) req;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceException(400, "Schema JSON 解析失败：" + ex.getMessage());
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> cleaned = new ArrayList<>();
        for (Map<String, Object> r : required) {
            Object f = r.get("field");
            if (f == null || String.valueOf(f).trim().isEmpty()) {
                continue;
            }
            String field = String.valueOf(f).trim();
            if (!seen.add(field)) {
                continue;
            }
            Map<String, Object> one = new java.util.LinkedHashMap<>();
            one.put("field", field);
            one.put("type", r.get("type") == null ? "string" : String.valueOf(r.get("type")));
            cleaned.add(one);
        }
        try {
            return MAPPER.writeValueAsString(Map.of("required", cleaned));
        } catch (Exception ex) {
            throw new ServiceException(500, "Schema 序列化失败");
        }
    }

    private void requireArch(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        boolean ok = auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_INTF_ARCH".equals(a.getAuthority()));
        if (!ok) {
            throw new ServiceException(403, action + " 仅限集成架构师/管理员（ROLE_INTF_OPS 只读）");
        }
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).trim().isEmpty() ? null : String.valueOf(o).trim();
    }

    private static int intOr(Object o, int def) {
        if (o == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
