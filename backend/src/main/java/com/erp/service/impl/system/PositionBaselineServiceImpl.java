package com.erp.service.impl.system;

import com.erp.common.ServiceException;
import com.erp.dao.system.PositionBaselineDao;
import com.erp.entity.system.PositionBaseline;
import com.erp.service.system.PositionBaselineService;
import com.erp.service.system.PrivilegedRoles;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionBaselineServiceImpl implements PositionBaselineService {

    private final PositionBaselineDao baselineDao;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<String> rolesOfPos(String posId) {
        if (posId == null || posId.isBlank()) {
            return List.of();
        }
        PositionBaseline b = baselineDao.selectByPosId(posId);
        if (b == null || b.getRoleCodes() == null || b.getRoleCodes().isBlank()) {
            return List.of();
        }
        return parseArray(b.getRoleCodes());
    }

    @Override
    public PositionBaseline get(String posId) {
        return posId == null || posId.isBlank() ? null : baselineDao.selectByPosId(posId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void upsert(String posId, String roleCodesJson, String operator) {
        if (posId == null || posId.isBlank()) {
            throw new ServiceException(422, "岗位 ID 不能为空");
        }
        List<String> roles = roleCodesJson == null || roleCodesJson.isBlank()
                ? List.of() : parseArrayStrict(roleCodesJson);

        // L1 硬阻断（design D3，无例外）
        List<String> bad = PrivilegedRoles.violating(roles);
        if (!bad.isEmpty()) {
            throw new ServiceException(422,
                    "岗位基线含特权角色（L1 硬阻断，C-5.6-06）：" + String.join(", ", bad));
        }

        String canonical;
        try {
            canonical = MAPPER.writeValueAsString(roles);
        } catch (Exception e) {
            throw new ServiceException(422, "角色集合序列化失败");
        }

        PositionBaseline b = baselineDao.selectByPosId(posId);
        if (b == null) {
            b = new PositionBaseline();
            b.setId(UUID.randomUUID().toString().replace("-", ""));
            b.setPosId(posId);
            b.setRoleCodes(canonical);
            b.setCreateBy(operator);
            baselineDao.insert(b);
        } else {
            b.setRoleCodes(canonical);
            b.setUpdateBy(operator);
            b.setUpdateDate(LocalDateTime.now());
            baselineDao.updateById(b);
        }
        log.info("position baseline upserted: pos={} roles={} by={}", posId, canonical, operator);
    }

    /** 宽松解析（读路径）：非法 JSON → 空集合，不让脏数据打断建号 */
    private List<String> parseArray(String json) {
        try {
            List<String> list = parseArrayStrict(json);
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 严格解析（写路径）：非数组 / 非字符串元素 → 422 */
    private List<String> parseArrayStrict(String json) {
        Object parsed;
        try {
            parsed = MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new ServiceException(422, "岗位角色映射须为 JSON 数组");
        }
        if (!(parsed instanceof List)) {
            throw new ServiceException(422, "岗位角色映射须为 JSON 数组");
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) parsed) {
            if (!(o instanceof String) || ((String) o).isBlank()) {
                throw new ServiceException(422, "角色码须为非空字符串数组");
            }
            out.add((String) o);
        }
        return out;
    }
}
