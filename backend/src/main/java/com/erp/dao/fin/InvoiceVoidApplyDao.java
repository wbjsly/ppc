package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.InvoiceVoidApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvoiceVoidApplyDao extends BaseMapper<InvoiceVoidApply> {

    /** 既有作废申请单号（VSAP-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT APPLY_NO FROM erp_fin_invoice_void_apply WHERE APPLY_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
