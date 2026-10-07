package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.CreditFreeze;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CreditFreezeDao extends BaseMapper<CreditFreeze> {

    /** 既有解冻确认单流水（UF-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT UNFREEZE_NO FROM erp_sd_credit_freeze WHERE UNFREEZE_NO LIKE #{prefix}")
    List<String> selectUnfreezeNosByPrefix(@Param("prefix") String prefix);
}
