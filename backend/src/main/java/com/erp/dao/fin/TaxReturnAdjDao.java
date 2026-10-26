package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.TaxReturnAdj;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaxReturnAdjDao extends BaseMapper<TaxReturnAdj> {

    /** 既有调整单号（TADJ-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT ADJ_NO FROM erp_fin_tax_return_adj WHERE ADJ_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
