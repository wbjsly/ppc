package com.erp.system;

import com.erp.service.system.PrivilegedRoles;
import com.erp.service.system.SensitiveRoles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感清单守卫（add-permission-approval-flow design D9）：
 * 成员与数据 Owner 均须存在于角色表；成员与特权清单不相交（风险取最高口径不许含糊）。
 */
@SpringBootTest
class SensitiveRolesScanTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sensitiveMembersExistInRoleTable() {
        Set<String> members = SensitiveRoles.allMembers();
        assertTrue(members.size() >= 3, "敏感清单不得为空壳：" + members);
        for (String role : members) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_admin_role WHERE ROLE_CODE=? AND DEL_FLAG='0'",
                    Integer.class, role);
            assertEquals(1, cnt, "敏感清单角色不存在于角色表：" + role);
        }
    }

    @Test
    void ownerRolesExistInRoleTable() {
        for (String owner : new HashSet<>(SensitiveRoles.OWNER_BY_ROLE.values())) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_admin_role WHERE ROLE_CODE=? AND DEL_FLAG='0'",
                    Integer.class, owner);
            assertEquals(1, cnt, "数据 Owner 角色不存在于角色表：" + owner);
        }
    }

    @Test
    void sensitiveAndPrivilegedDisjoint() {
        Set<String> overlap = new HashSet<>(SensitiveRoles.allMembers());
        overlap.retainAll(PrivilegedRoles.ALL);
        assertTrue(overlap.isEmpty(),
                "敏感清单成员与特权清单重叠（风险取最高口径将含糊）：" + overlap);
    }
}
