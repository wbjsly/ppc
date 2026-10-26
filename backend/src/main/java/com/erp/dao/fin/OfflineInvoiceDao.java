package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.OfflineInvoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OfflineInvoiceDao extends BaseMapper<OfflineInvoice> {

    /** 既有离线单号（OFL 前缀流水） */
    @Select("SELECT OFFLINE_NO FROM erp_fin_offline_invoice WHERE OFFLINE_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
