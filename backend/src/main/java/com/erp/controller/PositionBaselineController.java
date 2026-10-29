package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.system.PositionBaselineService;
import com.erp.service.system.PrivilegedRoles;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位权限基线台（add-role-permission-console spec：岗位基线与特权清单硬阻断，16.2.3）。
 * 限 ADMIN（SecurityConfig 细规则）；保存统一走 PositionBaselineService L1 校验。
 */
@RestController
@RequestMapping("/api/system/position-baselines")
@RequiredArgsConstructor
public class PositionBaselineController {

    private final PositionBaselineService baselineService;
    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Operation(summary = "岗位基线列表（岗位 LEFT JOIN 基线，含违规角色标注）")
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword) {
        String like = keyword == null || keyword.isBlank() ? null : "%" + keyword + "%";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.ID AS posId, p.POS_NO AS posNo, p.NAME AS posName, p.STATUS AS status, "
                        + "d.NAME AS deptName, b.ROLE_CODES AS roleCodes "
                        + "FROM hr_position p "
                        + "LEFT JOIN erp_admin_position_baseline b ON b.POS_ID = p.ID AND b.DEL_FLAG = '0' "
                        + "LEFT JOIN hr_dept d ON d.ID = p.DEPT_ID "
                        + "WHERE p.DEL_FLAG = '0' "
                        + "AND (? IS NULL OR p.NAME LIKE ? OR p.POS_NO LIKE ?) "
                        + "ORDER BY p.POS_NO",
                like, like, like);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            Object codes = row.get("roleCodes");
            List<String> roleList = new ArrayList<>();
            if (codes != null && !String.valueOf(codes).isBlank()) {
                try {
                    roleList = MAPPER.readValue(String.valueOf(codes),
                            MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
                } catch (Exception ignored) {
                    // 脏数据按空处理，页面照常展示（保存时会规范化）
                }
            }
            m.put("roles", roleList);
            m.put("violations", PrivilegedRoles.violating(roleList));
            m.put("hasBaseline", codes != null);
            out.add(m);
        }
        return R.ok(out);
    }

    @Operation(summary = "保存岗位基线（L1 特权校验 422；roleCodes 为角色码数组）")
    @PutMapping("/{posId}")
    public R<Void> save(@PathVariable String posId, @RequestBody Map<String, Object> body) {
        Object raw = body.get("roleCodes");
        if (!(raw instanceof List)) {
            throw new ServiceException(422, "roleCodes 须为数组");
        }
        List<String> codes = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            codes.add(String.valueOf(o));
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(codes);
        } catch (Exception e) {
            throw new ServiceException(422, "角色集合序列化失败");
        }
        baselineService.upsert(posId, json, operator());
        return R.ok(null);
    }

    private String operator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
