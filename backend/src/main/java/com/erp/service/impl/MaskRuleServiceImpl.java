package com.erp.service.impl;

import com.erp.common.ServiceException;
import com.erp.service.MaskRuleService;
import com.erp.service.MaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MaskRuleServiceImpl implements MaskRuleService {

    private final JdbcTemplate jdbc;
    private final MaskService maskService;

    @Override
    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT ID AS id, CODE AS code, NAME AS name, PATTERN AS pattern, "
                        + "MASK_TEMPLATE AS maskTemplate, STATUS AS status, REMARK AS remark, "
                        + "UPDATE_DATE AS updateDate FROM erp_ops_mask_rule WHERE DEL_FLAG='0' ORDER BY CODE");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(String id, String code, String name, String pattern, String maskTemplate,
                     String status, String remark) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(name)) {
            throw new ServiceException(422, "规则编码与名称必填");
        }
        if (!StringUtils.hasText(pattern) || !StringUtils.hasText(maskTemplate)) {
            throw new ServiceException(422, "正则与掩码模板必填");
        }
        try {
            Pattern.compile(pattern);
        } catch (Exception e) {
            throw new ServiceException(422, "正则不合法：" + e.getMessage());
        }
        if (!StringUtils.hasText(status) || (!"0".equals(status) && !"1".equals(status))) {
            throw new ServiceException(422, "状态仅支持 0/1");
        }
        // 掩码模板引用的捕获组不得超出正则组数
        int groups = Pattern.compile(pattern).matcher("").groupCount();
        java.util.regex.Matcher ref = Pattern.compile("\\$(\\d+)").matcher(maskTemplate);
        while (ref.find()) {
            int idx = Integer.parseInt(ref.group(1));
            if (idx > groups) {
                throw new ServiceException(422,
                        "掩码模板引用捕获组 $" + idx + " 超出正则组数 " + groups);
            }
        }
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_ops_mask_rule WHERE CODE=? AND DEL_FLAG='0' "
                        + "AND (ID<>? OR ? IS NULL)", Integer.class, code, id, id);
        if (dup != null && dup > 0) {
            throw new ServiceException(409, "规则编码已存在：" + code);
        }
        if (StringUtils.hasText(id)) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_ops_mask_rule WHERE ID=? AND DEL_FLAG='0'",
                    Integer.class, id);
            if (exists == null || exists == 0) {
                throw new ServiceException(404, "规则不存在：" + id);
            }
            jdbc.update("UPDATE erp_ops_mask_rule SET CODE=?, NAME=?, PATTERN=?, MASK_TEMPLATE=?, "
                            + "STATUS=?, REMARK=?, UPDATE_DATE=? WHERE ID=?",
                    code, name, pattern, maskTemplate, status, remark, LocalDateTime.now(), id);
        } else {
            jdbc.update("INSERT INTO erp_ops_mask_rule (ID, CODE, NAME, PATTERN, MASK_TEMPLATE, "
                            + "STATUS, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?,?,?,?,?,?,?, ?, NOW(), '0', 0)",
                    UUID.randomUUID().toString().replace("-", ""),
                    code, name, pattern, maskTemplate, status, remark, currentOperator());
        }
        maskService.evictCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_ops_mask_rule WHERE ID=? AND DEL_FLAG='0'",
                Integer.class, id);
        if (exists == null || exists == 0) {
            throw new ServiceException(404, "规则不存在：" + id);
        }
        // 逻辑删（@TableLogic 无涉，直接 UPDATE）
        jdbc.update("UPDATE erp_ops_mask_rule SET DEL_FLAG='1', UPDATE_DATE=? WHERE ID=?",
                LocalDateTime.now(), id);
        maskService.evictCache();
    }

    private String currentOperator() {
        try {
            return com.erp.util.SecurityUtils.getCurrentUserId();
        } catch (Exception e) {
            return "system";
        }
    }
}
