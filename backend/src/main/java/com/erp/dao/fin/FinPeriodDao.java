package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinPeriod;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 会计期间 DAO（add-gl-management）。
 */
@Mapper
public interface FinPeriodDao extends BaseMapper<FinPeriod> {

    @Select("SELECT * FROM erp_fin_period WHERE PERIOD=#{period} AND DEL_FLAG='0' LIMIT 1")
    FinPeriod selectByPeriod(@Param("period") String period);
}
