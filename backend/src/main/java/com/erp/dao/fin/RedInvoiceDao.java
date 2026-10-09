package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.RedInvoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RedInvoiceDao extends BaseMapper<RedInvoice> {

    /** 既有红字发票号（FPZ-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT RED_NO FROM erp_fin_red_invoice WHERE RED_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
