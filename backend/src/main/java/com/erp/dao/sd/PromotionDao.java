package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.Promotion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PromotionDao extends BaseMapper<Promotion> {

    /** 既有活动流水（PM-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT PROMO_NO FROM erp_sd_promotion WHERE PROMO_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
