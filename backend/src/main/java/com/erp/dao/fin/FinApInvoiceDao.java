package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinApInvoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FinApInvoiceDao extends BaseMapper<FinApInvoice> {

    /** FIFO 匹配池：未清应付按发票日/单号升序（FR-4.6-3-12 最早应付优先，统一核销内核 design D4） */
    @Select("SELECT * FROM erp_fin_ap_invoice WHERE SUPPLIER_ID = #{supplierId} "
            + "AND DEL_FLAG='0' AND STATUS = 'POSTED' "
            + "AND (TOTAL_AMOUNT - PAID_AMOUNT) > 0 "
            + "ORDER BY INVOICE_DATE, INVOICE_NO")
    List<FinApInvoice> selectFifoPool(@Param("supplierId") String supplierId);
}
