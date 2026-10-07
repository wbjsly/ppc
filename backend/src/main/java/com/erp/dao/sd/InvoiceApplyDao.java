package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.InvoiceApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvoiceApplyDao extends BaseMapper<InvoiceApply> {

    /** 既有申请单号（IA-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT APPLY_NO FROM erp_sd_invoice_apply WHERE APPLY_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 同发货单是否已生成过申请（一一对应，spec 场景防重） */
    @Select("SELECT COUNT(*) FROM erp_sd_invoice_apply WHERE SHIP_ID = #{shipId} AND DEL_FLAG='0'")
    long countByShip(@Param("shipId") String shipId);
}
