package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.DigitalQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DigitalQuotaDao extends BaseMapper<DigitalQuota> {

    /** 某期额度行 */
    @Select("SELECT * FROM erp_fin_digital_quota WHERE DEL_FLAG = '0' AND PERIOD = #{period} LIMIT 1")
    DigitalQuota selectByPeriod(@Param("period") String period);
}
