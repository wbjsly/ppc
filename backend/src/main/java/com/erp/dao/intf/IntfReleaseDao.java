package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfRelease;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntfReleaseDao extends BaseMapper<IntfRelease> {

    @Select("SELECT MAX(CAST(SUBSTR(RELEASE_NO, 11) AS UNSIGNED)) FROM erp_intf_release " +
            "WHERE RELEASE_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
