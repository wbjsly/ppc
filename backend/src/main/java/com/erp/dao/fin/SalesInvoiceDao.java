package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.SalesInvoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SalesInvoiceDao extends BaseMapper<SalesInvoice> {

    /** 既有发票号（FP-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT INVOICE_NO FROM erp_fin_sales_invoice WHERE INVOICE_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
