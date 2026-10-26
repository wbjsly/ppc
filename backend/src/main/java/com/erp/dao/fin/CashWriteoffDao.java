package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.CashWriteoff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface CashWriteoffDao extends BaseMapper<CashWriteoff> {

    @Select("SELECT WO_NO FROM erp_fin_cash_writeoff WHERE WO_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 单据累计已核销额（回单对账/详情展示） */
    @Select("SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_cash_writeoff "
            + "WHERE DOC_ID = #{docId} AND DEL_FLAG='0'")
    BigDecimal selectDocWriteoffTotal(@Param("docId") String docId);

    /** 某发票全部核销记录（CRM 收款进度/对账回看） */
    @Select("SELECT * FROM erp_fin_cash_writeoff WHERE TARGET_TYPE = #{targetType} "
            + "AND TARGET_ID = #{targetId} AND DEL_FLAG='0' ORDER BY WRITEOFF_DATE, WO_NO")
    List<CashWriteoff> selectByTarget(@Param("targetType") String targetType,
                                      @Param("targetId") String targetId);

    // ---- 承接旧 ArWriteoffDao 口径（信用控制 BR-4.3-15/16、返利冲抵、月度对账）----

    /** 返利冲抵明细（remark 含结算单号，3.9.3 兑现页） */
    @Select("SELECT * FROM erp_fin_cash_writeoff WHERE DEL_FLAG='0' "
            + "AND REMARK LIKE CONCAT('%', #{settleNo}, '%') "
            + "ORDER BY WRITEOFF_DATE, WO_NO")
    List<CashWriteoff> selectBySettle(@Param("settleNo") String settleNo);

    /** 近 12 个月回款笔数（及时率分母） */
    @Select("SELECT COUNT(*) FROM erp_fin_cash_writeoff "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "AND WRITEOFF_DATE >= DATE_SUB(CURDATE(), INTERVAL 12 MONTH)")
    long selectCount12m(@Param("customerId") String customerId);

    /** 近 12 个月按时回款笔数（及时率分子，BR-4.3-15） */
    @Select("SELECT COUNT(*) FROM erp_fin_cash_writeoff "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' AND ON_TIME = '1' "
            + "AND WRITEOFF_DATE >= DATE_SUB(CURDATE(), INTERVAL 12 MONTH)")
    long selectOnTimeCount12m(@Param("customerId") String customerId);

    /** 最近 N 次回款中逾期笔数（BR-4.3-16 连续逾期判定，等于 N 即连续 N 次逾期） */
    @Select("SELECT COUNT(*) FROM ("
            + "SELECT ON_TIME FROM erp_fin_cash_writeoff "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "ORDER BY WRITEOFF_DATE DESC LIMIT #{n}) t WHERE t.ON_TIME = '0'")
    long selectRecentOverdueCount(@Param("customerId") String customerId, @Param("n") int n);

    /** 月度对账：本期核销额（WRITEOFF_DATE 落在期间） */
    @Select("SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_cash_writeoff "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "AND DATE_FORMAT(WRITEOFF_DATE,'%Y%m') = #{period}")
    BigDecimal selectPeriodWriteoffAmt(@Param("customerId") String customerId,
                                       @Param("period") String period);

    /** 月度对账：期初已核销（本期之前） */
    @Select("SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_cash_writeoff "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "AND WRITEOFF_DATE < STR_TO_DATE(CONCAT(#{period},'01'),'%Y%m%d')")
    BigDecimal selectWriteoffBefore(@Param("customerId") String customerId,
                                    @Param("period") String period);

    /** 某应收累计核销 */
    @Select("SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_cash_writeoff "
            + "WHERE TARGET_ID = #{arId} AND TARGET_TYPE = 'AR' AND DEL_FLAG='0'")
    BigDecimal selectSumByAr(@Param("arId") String arId);

    /** 某应收本期核销（对账单明细行「本期核销」口径） */
    @Select("SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_cash_writeoff "
            + "WHERE TARGET_ID = #{arId} AND TARGET_TYPE = 'AR' AND DEL_FLAG='0' "
            + "AND DATE_FORMAT(WRITEOFF_DATE,'%Y%m') = #{period}")
    BigDecimal selectSumByArPeriod(@Param("arId") String arId, @Param("period") String period);
}
