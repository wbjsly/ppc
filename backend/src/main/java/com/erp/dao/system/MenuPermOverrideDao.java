package com.erp.dao.system;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.system.MenuPermOverride;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MenuPermOverrideDao extends BaseMapper<MenuPermOverride> {

    /** 指定角色的全部覆盖行（合并解析一次加载，行数 = 该角色实际改动格数） */
    @Select("SELECT * FROM erp_admin_menu_perm_override "
            + "WHERE DEL_FLAG = '0' AND ROLE_CODE = #{roleCode}")
    List<MenuPermOverride> selectByRole(@Param("roleCode") String roleCode);

    /** 一批角色的全部覆盖行（getUserMenus 合并用） */
    @Select("<script>"
            + "SELECT * FROM erp_admin_menu_perm_override WHERE DEL_FLAG = '0' "
            + "AND ROLE_CODE IN "
            + "<foreach collection='roleCodes' item='r' open='(' separator=',' close=')'>#{r}</foreach>"
            + "</script>")
    List<MenuPermOverride> selectByRoles(@Param("roleCodes") List<String> roleCodes);
}
