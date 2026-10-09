package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvScrapOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ScrapOrderDao extends BaseMapper<InvScrapOrder> {

    /** 单号流水（SC-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT SCRAP_NO FROM erp_inv_scrap_order WHERE SCRAP_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 行锁读取（过账/核销前锁头，design D3 同款） */
    @Select("SELECT * FROM erp_inv_scrap_order WHERE ID = #{id} AND DEL_FLAG = '0' FOR UPDATE")
    InvScrapOrder selectByIdForUpdate(@Param("id") String id);
}
