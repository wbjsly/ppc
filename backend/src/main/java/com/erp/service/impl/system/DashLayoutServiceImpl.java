package com.erp.service.impl.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.DashLayoutDao;
import com.erp.entity.system.DashLayout;
import com.erp.service.system.DashLayoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台三层布局实现（change add-configurable-workbench，spec personal-workbench）。
 * design D1：单表 upsert 永不软删（恢复默认=清空 LAYOUT_JSON）；
 * design D2：合并收敛在 {@link DashLayoutMerger} 单点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashLayoutServiceImpl implements DashLayoutService {

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_ROLE = "ROLE";
    public static final String SCOPE_USER = "USER";
    public static final String REF_ALL = "ALL";

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final DashLayoutDao dashLayoutDao;

    @Override
    public Map<String, Object> merged(String userId, List<String> roles) {
        String globalJson = layer(SCOPE_GLOBAL, REF_ALL);
        String roleJson = firstRoleLayout(roles);
        String userJson = layer(SCOPE_USER, userId);

        String mergedJson = DashLayoutMerger.merge(globalJson, roleJson, userJson);

        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = OM.readValue(mergedJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            out.putAll(parsed);
        } catch (Exception e) {
            throw new ServiceException(500, "工作台布局解析失败");
        }
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("global", globalJson != null);
        layers.put("role", roleJson != null);
        layers.put("user", userJson != null);
        out.put("layers", layers);
        return out;
    }

    @Override
    public Map<String, Object> layers() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("global", layer(SCOPE_GLOBAL, REF_ALL));
        Map<String, String> roles = new LinkedHashMap<>();
        List<DashLayout> rows = dashLayoutDao.selectList(
                new LambdaQueryWrapper<DashLayout>().eq(DashLayout::getScope, SCOPE_ROLE));
        for (DashLayout r : rows) {
            roles.put(r.getRefId(), r.getLayoutJson());
        }
        out.put("roles", roles);
        return out;
    }

    @Override
    public String layer(String scope, String refId) {
        if (!StringUtils.hasText(scope) || !StringUtils.hasText(refId)) {
            return null;
        }
        DashLayout row = dashLayoutDao.selectOne(new LambdaQueryWrapper<DashLayout>()
                .eq(DashLayout::getScope, scope)
                .eq(DashLayout::getRefId, refId)
                .last("LIMIT 1"));
        return row == null ? null : row.getLayoutJson();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveUser(String userId, String layoutJson) {
        if (!StringUtils.hasText(userId)) {
            throw new ServiceException(401, "未登录用户不能保存工作台布局");
        }
        upsert(SCOPE_USER, userId, normalize(layoutJson));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveRole(String role, String layoutJson) {
        if (!StringUtils.hasText(role)) {
            throw new ServiceException(422, "角色模板必须指定角色");
        }
        upsert(SCOPE_ROLE, role, normalize(layoutJson));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveGlobal(String layoutJson) {
        upsert(SCOPE_GLOBAL, REF_ALL, normalize(layoutJson));
    }

    // ---------------- 私有 ----------------

    /** 角色层：取用户第一个有布局的角色（多角色按 token 顺序首个命中）。 */
    private String firstRoleLayout(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        for (String role : roles) {
            String json = layer(SCOPE_ROLE, role);
            if (StringUtils.hasText(json)) {
                return json;
            }
        }
        return null;
    }

    /** upsert：行存在即 UPDATE（含"恢复默认"的空 JSON 清空），不存在 INSERT；永不删除。 */
    private void upsert(String scope, String refId, String json) {
        DashLayout existing = dashLayoutDao.selectOne(new LambdaQueryWrapper<DashLayout>()
                .eq(DashLayout::getScope, scope)
                .eq(DashLayout::getRefId, refId)
                .last("LIMIT 1"));
        if (existing == null) {
            DashLayout row = new DashLayout();
            row.setScope(scope);
            row.setRefId(refId);
            row.setLayoutJson(json);
            dashLayoutDao.insert(row);
        } else {
            // 显式 set：updateById 的默认 NOT_NULL 策略会跳过 null 字段，
            // 导致"恢复默认"清不掉层——必须走 UpdateWrapper.set 写入 null
            dashLayoutDao.update(null, new LambdaUpdateWrapper<DashLayout>()
                    .eq(DashLayout::getId, existing.getId())
                    .set(DashLayout::getLayoutJson, json));
        }
    }

    /** 空白视为空层（恢复默认把 JSON 置 null）；原样透传合法 JSON，结构校验交合并器降级。 */
    private String normalize(String layoutJson) {
        if (!StringUtils.hasText(layoutJson)) {
            return null;
        }
        return layoutJson;
    }
}
