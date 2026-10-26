package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.InputInvoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InputInvoiceDao extends BaseMapper<InputInvoice> {

    /** 某 PO 已认证发票累计金额（三单匹配的未开票余额口径，D2 实时聚合零新列；
     *  显式 IN——排除 PENDING_INSPECT（查验前不占 PO 余额，add-input-certification D1 同款收窄，
     *  否则 inspect(PASS) 执行匹配时行自身被误计入） */
    @Select("SELECT IFNULL(SUM(AMOUNT), 0) FROM erp_fin_input_invoice "
            + "WHERE DEL_FLAG = '0' AND PO_ID = #{poId} "
            + "AND CERT_STATUS IN ('CERTIFIED', 'NOT_DEDUCTIBLE')")
    java.math.BigDecimal sumCertifiedByPo(@Param("poId") String poId);

    /** 某期已认证未生成凭证的行（批量进项凭证幂等扫描；
     *  显式 IN 收窄——排除 PENDING_INSPECT/INVALID（D1 防御性，查验闸口新态） */
    @Select("SELECT * FROM erp_fin_input_invoice WHERE DEL_FLAG = '0' "
            + "AND CERT_PERIOD = #{period} "
            + "AND CERT_STATUS IN ('CERTIFIED', 'NOT_DEDUCTIBLE') AND VOUCHER_ID IS NULL")
    List<InputInvoice> selectPendingVoucherByPeriod(@Param("period") String period);

    /** 临期未认证预警（开票日距今剩余 ≤ warnDays，视图语义 D3） */
    @Select("SELECT * FROM erp_fin_input_invoice WHERE DEL_FLAG = '0' "
            + "AND CERT_STATUS = 'PENDING_CERT' "
            + "AND DATEDIFF(DATE_ADD(INVOICE_DATE, INTERVAL #{certifyDays} DAY), CURDATE()) <= #{warnDays} "
            + "AND DATEDIFF(DATE_ADD(INVOICE_DATE, INTERVAL #{certifyDays} DAY), CURDATE()) >= 0")
    List<InputInvoice> selectNearingExpiry(@Param("certifyDays") int certifyDays,
                                           @Param("warnDays") int warnDays);
}
