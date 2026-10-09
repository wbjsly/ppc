package com.erp.dao.bi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.bi.BiConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BiConfigDao extends BaseMapper<BiConfig> {

    @Select("SELECT * FROM erp_bi_config WHERE CONFIG_KEY = #{k} AND DEL_FLAG = '0' LIMIT 1")
    BiConfig selectByKey(@Param("k") String key);
}
