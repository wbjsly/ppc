package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.TaxRefundApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaxRefundApplyDao extends BaseMapper<TaxRefundApply> {

    /** 既有留抵退税申请单号（RVAP-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT APPLY_NO FROM erp_fin_tax_refund_apply WHERE APPLY_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 申请前各月已获批留抵退税合计（到账核销冲减留抵用，S-4.14-15） */
    @Select("SELECT COALESCE(SUM(RECEIVED_AMOUNT),0) FROM erp_fin_tax_refund_apply "
            + "WHERE STATUS = 'PAID' AND DEL_FLAG = '0' AND PERIOD <= #{period}")
    java.math.BigDecimal selectPaidRefundSumUpTo(@Param("period") String period);
}
