package com.erp.dao.system;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.system.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserRoleDao extends BaseMapper<SysUserRole> {

    @Select("SELECT r.ROLE_CODE FROM erp_admin_role r " +
            "INNER JOIN erp_admin_user_role ur ON r.ID = ur.ROLE_ID " +
            "WHERE ur.USER_ID = #{userId} AND r.STATUS = '1' AND r.DEL_FLAG = '0'")
    List<String> getUserRoleCodes(String userId);

    /** 指定角色的启用账号用户名（add-lead-auto-assign：ROLE_SALES 全集） */
    @Select("SELECT u.USERNAME FROM erp_admin_user u " +
            "INNER JOIN erp_admin_user_role ur ON ur.USER_ID = u.ID " +
            "INNER JOIN erp_admin_role r ON r.ID = ur.ROLE_ID " +
            "WHERE r.ROLE_CODE = #{roleCode} AND r.STATUS = '1' AND r.DEL_FLAG = '0' " +
            "AND u.STATUS = '1' AND u.DEL_FLAG = '0' ORDER BY u.USERNAME")
    List<String> selectUsernamesByRoleCode(String roleCode);
}
