package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.TpParty;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TpPartyDao extends BaseMapper<TpParty> {

    /** 既有关联方编号（TPP 前缀流水） */
    @Select("SELECT TP_NO FROM erp_fin_tp_party WHERE TP_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
