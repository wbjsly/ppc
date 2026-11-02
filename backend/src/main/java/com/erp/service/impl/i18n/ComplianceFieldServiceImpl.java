package com.erp.service.impl.i18n;

import com.erp.common.ServiceException;
import com.erp.service.i18n.ComplianceFieldService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地合规字段包引擎实现（add-i18n-l10n，BR-5.7-13 禁硬编码 / C-5.7-09 阻断）。
 * 字段挂既有列（TAXPAYER_NO 等），引擎管包定义、正则规则与注册地映射。
 */
@Slf4j
@Service
public class ComplianceFieldServiceImpl implements ComplianceFieldService {

    private final JdbcTemplate jdbc;

    public ComplianceFieldServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Map<String, Object>> list(String packCode, String regPlace) {
        StringBuilder sql = new StringBuilder(
                "SELECT ID AS id, PACK_CODE AS packCode, REG_PLACE AS regPlace, FIELD_NAME AS fieldName, "
                        + "COLUMN_NAME AS columnName, FORMAT_REGEX AS formatRegex, REQUIRED AS required, "
                        + "ERROR_HINT AS errorHint, SORT_NO AS sortNo "
                        + "FROM erp_i18n_compliance_field WHERE DEL_FLAG='0'");
        List<Object> args = new ArrayList<>();
        if (packCode != null && !packCode.isBlank()) {
            sql.append(" AND PACK_CODE=?");
            args.add(packCode.trim());
        }
        if (regPlace != null && !regPlace.isBlank()) {
            sql.append(" AND REG_PLACE=?");
            args.add(regPlace.trim());
        }
        sql.append(" ORDER BY PACK_CODE, SORT_NO");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Map<String, Object> body) {
        String packCode = str(body.get("packCode"));
        String fieldName = str(body.get("fieldName"));
        if (packCode == null || fieldName == null) {
            throw new ServiceException(422, "包编码与字段名必填");
        }
        String regex = str(body.get("formatRegex"));
        if (regex != null && !regex.isBlank()) {
            try {
                java.util.regex.Pattern.compile(regex);
            } catch (Exception e) {
                throw new ServiceException(422, "正则不合法：" + e.getMessage());
            }
        }
        Map<String, Object> exist = null;
        try {
            exist = jdbc.queryForMap(
                    "SELECT ID FROM erp_i18n_compliance_field WHERE PACK_CODE=? AND FIELD_NAME=? AND DEL_FLAG='0'",
                    packCode, fieldName);
        } catch (Exception e) {
            exist = null;
        }
        if (exist == null) {
            jdbc.update("INSERT INTO erp_i18n_compliance_field (ID, PACK_CODE, REG_PLACE, FIELD_NAME, "
                            + "COLUMN_NAME, FORMAT_REGEX, REQUIRED, ERROR_HINT, SORT_NO, CREATE_BY, "
                            + "CREATE_DATE, DEL_FLAG, VER_NO) VALUES (UUID(), ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "'i18n-admin', NOW(), '0', 0)",
                    packCode, str(body.get("regPlace")), fieldName, str(body.getOrDefault("columnName", "TAXPAYER_NO")),
                    regex, str(body.getOrDefault("required", "1")), str(body.get("errorHint")),
                    body.get("sortNo") == null ? 0 : Integer.parseInt(String.valueOf(body.get("sortNo"))));
        } else {
            jdbc.update("UPDATE erp_i18n_compliance_field SET REG_PLACE=?, COLUMN_NAME=?, FORMAT_REGEX=?, "
                            + "REQUIRED=?, ERROR_HINT=?, SORT_NO=?, UPDATE_BY='i18n-admin', UPDATE_DATE=NOW() "
                            + "WHERE ID=?",
                    str(body.get("regPlace")), str(body.getOrDefault("columnName", "TAXPAYER_NO")),
                    regex, str(body.getOrDefault("required", "1")), str(body.get("errorHint")),
                    body.get("sortNo") == null ? 0 : Integer.parseInt(String.valueOf(body.get("sortNo"))),
                    exist.get("ID"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("packCode", packCode);
        out.put("fieldName", fieldName);
        return out;
    }

    @Override
    public List<Map<String, Object>> loadByRegPlace(String regPlace) {
        // 17.5.3：注册地 → 包映射；无注册地或无包 → 空清单（无关法人无冗余字段）
        if (regPlace == null || regPlace.isBlank()) {
            return new ArrayList<>();
        }
        return jdbc.queryForList(
                "SELECT ID AS id, PACK_CODE AS packCode, FIELD_NAME AS fieldName, COLUMN_NAME AS columnName, "
                        + "FORMAT_REGEX AS formatRegex, REQUIRED AS required, ERROR_HINT AS errorHint "
                        + "FROM erp_i18n_compliance_field WHERE DEL_FLAG='0' AND REG_PLACE=? "
                        + "ORDER BY SORT_NO", regPlace.trim());
    }

    @Override
    public Map<String, Object> validate(String regPlace, Map<String, String> values) {
        List<Map<String, Object>> fields = loadByRegPlace(regPlace);
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            String name = String.valueOf(f.get("fieldName"));
            boolean required = "1".equals(String.valueOf(f.getOrDefault("required", "1")));
            String regex = f.get("formatRegex") == null ? null : String.valueOf(f.get("formatRegex"));
            String hint = f.get("errorHint") == null ? "格式不正确" : String.valueOf(f.get("errorHint"));
            String v = values == null ? null : values.get(name);
            if (v == null || v.isBlank()) {
                if (required) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("field", name);
                    err.put("hint", hint);
                    errors.add(err);
                }
                continue;
            }
            if (regex != null && !regex.isBlank() && !v.matches(regex)) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("field", name);
                err.put("hint", hint + "（当前值格式不匹配）");
                errors.add(err);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pass", errors.isEmpty());
        out.put("errors", errors);
        out.put("packCount", fields.size());
        return out;
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
