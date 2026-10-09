package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.ArWriteoff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface ArWriteoffDao extends BaseMapper<ArWriteoff> {

    /** 既有核销单号（WO-YYYYMMDD-NNNN 前缀；种子单号不参与） */
    @Select("SELECT WO_NO FROM erp_fin_ar_writeoff WHERE WO_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 返利冲抵明细（remark 含结算单号，3.9.3 兑现页查看冲抵核销明细） */
    @Select("SELECT * FROM erp_fin_ar_writeoff WHERE DEL_FLAG='0' "
            + "AND REMARK LIKE CONCAT('%', #{settleNo}, '%') "
            + "ORDER BY PAY_DATE, WO_NO")
    List<ArWriteoff> selectBySettle(@Param("settleNo") String settleNo);

    /** 近 12 个月回款笔数（及时率分母） */
    @Select("SELECT COUNT(*) FROM erp_fin_ar_writeoff "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "AND PAY_DATE >= DATE_SUB(CURDATE(), INTERVAL 12 MONTH)")
    long selectCount12m(@Param("customerId") String customerId);

    /** 近 12 个月按时回款笔数（及时率分子，BR-4.3-15） */
    @Select("SELECT COUNT(*) FROM erp_fin_ar_writeoff "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' AND ON_TIME = '1' "
            + "AND PAY_DATE >= DATE_SUB(CURDATE(), INTERVAL 12 MONTH)")
    long selectOnTimeCount12m(@Param("customerId") String customerId);

    /**
     * 最近 N 次回款是否全部逾期（按付款时间升序取最近 N 笔）——BR-4.3-16 连续 3 次判定。
     * 返回最近 N 笔中逾期的笔数，等于 N 即为连续 N 次逾期。
     */
    @Select("SELECT COUNT(*) FROM ("
            + "SELECT ON_TIME FROM erp_fin_ar_writeoff "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "ORDER BY PAY_DATE DESC LIMIT #{n}) t WHERE t.ON_TIME = '0'")
    long selectRecentOverdueCount(@Param("customerId") String customerId, @Param("n") int n);

    /** 月度对账：本期核销额（PAY_DATE 落在期间） */
    @Select("SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_ar_writeoff "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "AND DATE_FORMAT(PAY_DATE,'%Y%m') = #{period}")
    BigDecimal selectPeriodWriteoffAmt(@Param("customerId") String customerId,
                                       @Param("period") String period);

    /** 期初已核销（本期之前）——期初余额 = 期初开票 − 期初核销 */
    @Select("SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_ar_writeoff "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "AND PAY_DATE < STR_TO_DATE(CONCAT(#{period},'01'),'%Y%m%d')")
    BigDecimal selectWriteoffBefore(@Param("customerId") String customerId,
                                    @Param("period") String period);

    /** 某应收累计核销 */
    @Select("SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_ar_writeoff "
            + "WHERE AR_ID = #{arId} AND DEL_FLAG='0'")
    BigDecimal selectSumByAr(@Param("arId") String arId);

    /** 某应收本期核销（对账单明细行「本期核销」口径） */
    @Select("SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_ar_writeoff "
            + "WHERE AR_ID = #{arId} AND DEL_FLAG='0' "
            + "AND DATE_FORMAT(PAY_DATE,'%Y%m') = #{period}")
    BigDecimal selectSumByArPeriod(@Param("arId") String arId, @Param("period") String period);
}
