package com.erp.dao.system;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.system.PositionBaseline;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PositionBaselineDao extends BaseMapper<PositionBaseline> {

    /** 按岗位查基线（唯一键 POS_ID，最多一行） */
    @Select("SELECT * FROM erp_admin_position_baseline "
            + "WHERE POS_ID = #{posId} AND DEL_FLAG = '0' LIMIT 1")
    PositionBaseline selectByPosId(@Param("posId") String posId);
}
