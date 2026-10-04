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
}
