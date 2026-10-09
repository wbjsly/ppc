package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.SpecialPrice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SpecialPriceDao extends BaseMapper<SpecialPrice> {

    /** 既有特批单流水（SP-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT SP_NO FROM erp_sd_special_price WHERE SP_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
