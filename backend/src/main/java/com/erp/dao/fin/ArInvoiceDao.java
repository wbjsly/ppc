package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.ArInvoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface ArInvoiceDao extends BaseMapper<ArInvoice> {

    /** 未付应收余额（AMOUNT − RED − PAID，未结清部分）——BR-4.3-13 因子② */
    @Select("SELECT COALESCE(SUM(AMOUNT - RED_AMOUNT - PAID_AMOUNT), 0) FROM erp_fin_ar_invoice "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' AND STATUS IN ('UNPAID','PARTIAL')")
    BigDecimal selectOpenBalance(@Param("customerId") String customerId);

    /** 超期 90 天以上未付应收——BR-4.3-14 分子 */
    @Select("SELECT COALESCE(SUM(AMOUNT - RED_AMOUNT - PAID_AMOUNT), 0) FROM erp_fin_ar_invoice "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' AND STATUS IN ('UNPAID','PARTIAL') "
            + "AND DUE_DATE < DATE_SUB(CURDATE(), INTERVAL 90 DAY)")
    BigDecimal selectOver90Balance(@Param("customerId") String customerId);

    /** 既有应收流水（AR-YYYYMMDD-NNNN 前缀；种子单号为 AR-SEED-* 不参与） */
    @Select("SELECT AR_NO FROM erp_fin_ar_invoice WHERE AR_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** FIFO 匹配池：未清应收按发生日/单号升序（FR-4.3-7-5 最早应收优先） */
    @Select("SELECT * FROM erp_fin_ar_invoice WHERE CUSTOMER_ID = #{customerId} "
            + "AND DEL_FLAG='0' AND STATUS IN ('UNPAID','PARTIAL') "
            + "AND (AMOUNT - RED_AMOUNT - PAID_AMOUNT) > 0 "
            + "ORDER BY INVOICE_DATE, AR_NO")
    List<ArInvoice> selectFifoPool(@Param("customerId") String customerId);

    /** 某 SO 名下未清应收（计划达成对比：合同 → SO → 应收链路） */
    @Select("SELECT ID FROM erp_fin_ar_invoice WHERE SO_ID = #{soId} AND DEL_FLAG='0'")
    List<String> selectIdsBySo(@Param("soId") String soId);

    /** 某合同名下应收汇总（经 SO 关联）：笔数、金额（扣红冲）、已核销 */
    @Select("SELECT COUNT(DISTINCT a.ID) AS cnt, "
            + "COALESCE(SUM(a.AMOUNT - a.RED_AMOUNT),0) AS amt, COALESCE(SUM(a.PAID_AMOUNT),0) AS paid "
            + "FROM erp_fin_ar_invoice a JOIN erp_sd_so s ON a.SO_ID = s.ID "
            + "WHERE s.CONTRACT_ID = #{contractId} AND a.DEL_FLAG='0'")
    Map<String, Object> selectContractAgg(@Param("contractId") String contractId);

    /** 某合同名下应收（按 SO 关联），供逐笔展示 */
    @Select("SELECT a.* FROM erp_fin_ar_invoice a JOIN erp_sd_so s ON a.SO_ID = s.ID "
            + "WHERE s.CONTRACT_ID = #{contractId} AND a.DEL_FLAG='0' ORDER BY a.INVOICE_DATE, a.AR_NO")
    List<ArInvoice> selectByContract(@Param("contractId") String contractId);

    /** 月度对账：本期开票额 */
    @Select("SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_ar_invoice "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "AND DATE_FORMAT(INVOICE_DATE,'%Y%m') = #{period}")
    BigDecimal selectPeriodInvoiceAmt(@Param("customerId") String customerId,
                                      @Param("period") String period);

    /** 月度对账：期末余额口径（含本期开票与历史，剔除已核销与红冲） */
    @Select("SELECT COALESCE(SUM(AMOUNT - RED_AMOUNT - PAID_AMOUNT),0) FROM erp_fin_ar_invoice "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "AND INVOICE_DATE <= LAST_DAY(STR_TO_DATE(CONCAT(#{period},'01'),'%Y%m%d'))")
    BigDecimal selectBalanceAsOf(@Param("customerId") String customerId,
                                 @Param("period") String period);

    /** 月度对账：期初开票累计（本期之前发生额，期初余额 = 期初开票 − 期初核销） */
    @Select("SELECT COALESCE(SUM(AMOUNT - RED_AMOUNT),0) FROM erp_fin_ar_invoice "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "AND INVOICE_DATE < STR_TO_DATE(CONCAT(#{period},'01'),'%Y%m%d')")
    BigDecimal selectOpenBalanceBefore(@Param("customerId") String customerId,
                                       @Param("period") String period);

    /**
     * 返利基数（FR-4.3-8-1）：季度内开票确认额 − 退货退款额（红字冲减）。
     * 开票确认口径 = 应收开票金额；退货退款经红字发票回写 RED_AMOUNT 扣减。
     */
    @Select("SELECT COALESCE(SUM(AMOUNT - RED_AMOUNT),0) FROM erp_fin_ar_invoice "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "AND INVOICE_DATE >= #{from} AND INVOICE_DATE <= #{to}")
    BigDecimal selectQuarterBase(@Param("customerId") String customerId,
                                 @Param("from") java.time.LocalDate from,
                                 @Param("to") java.time.LocalDate to);
}
