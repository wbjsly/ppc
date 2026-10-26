package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinBalance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 科目余额 DAO（add-gl-management）。
 */
@Mapper
public interface FinBalanceDao extends BaseMapper<FinBalance> {

    /** 某科目某辅助键的余额行（UPSERT 前查询，design D3） */
    @Select("SELECT * FROM erp_fin_balance WHERE PERIOD=#{period} AND ACCOUNT_CODE=#{accountCode} "
            + "AND AUX_KEY=#{auxKey} AND DEL_FLAG='0' LIMIT 1")
    FinBalance selectByKey(@Param("period") String period,
                           @Param("accountCode") String accountCode,
                           @Param("auxKey") String auxKey);

    /** 原子累加：并发首写撞唯一键时回退为 UPDATE（gl 并发过账兜底） */
    @Update("UPDATE erp_fin_balance SET DR_TOTAL = DR_TOTAL + #{drAdd}, "
            + "CR_TOTAL = CR_TOTAL + #{crAdd}, UPDATE_BY = #{updateBy}, "
            + "VER_NO = VER_NO + 1, UPDATE_DATE = NOW() "
            + "WHERE PERIOD=#{period} AND ACCOUNT_CODE=#{accountCode} AND AUX_KEY=#{auxKey} "
            + "AND DEL_FLAG='0'")
    int accumulate(@Param("period") String period, @Param("accountCode") String accountCode,
                   @Param("auxKey") String auxKey, @Param("drAdd") java.math.BigDecimal drAdd,
                   @Param("crAdd") java.math.BigDecimal crAdd, @Param("updateBy") String updateBy);

    /** 幂等 journal 判存：该凭证行是否已回写（防重放双计） */
    @Select("SELECT COUNT(*) FROM erp_fin_balance_journal WHERE VOUCHER_ID=#{voucherId} "
            + "AND LINE_NO=#{lineNo} AND DIRECTION=#{direction}")
    long journalExists(@Param("voucherId") String voucherId,
                       @Param("lineNo") Integer lineNo,
                       @Param("direction") String direction);

    /** 余额四栏查询（期初=上期期末，按科目/辅助过滤，design D6） */
    @Select("<script>"
            + "SELECT b.ACCOUNT_CODE, a.ACCOUNT_NAME, b.AUX_KEY, b.PERIOD, "
            + " b.DR_TOTAL, b.CR_TOTAL, "
            + " (b.DR_TOTAL - b.CR_TOTAL) BALANCE "
            + "FROM erp_fin_balance b LEFT JOIN erp_fin_account a ON a.ACCOUNT_CODE=b.ACCOUNT_CODE "
            + "WHERE b.DEL_FLAG='0' AND b.PERIOD=#{period}"
            + " <if test='accountCode != null and accountCode != \"\"'> AND b.ACCOUNT_CODE=#{accountCode}</if>"
            + " <if test='auxKey != null and auxKey != \"\"'> AND b.AUX_KEY=#{auxKey}</if>"
            + " ORDER BY b.ACCOUNT_CODE, b.AUX_KEY"
            + "</script>")
    List<Map<String, Object>> queryBalances(@Param("period") String period,
                                            @Param("accountCode") String accountCode,
                                            @Param("auxKey") String auxKey);

    /** 试算平衡：期间全部 POSTED 凭证行借贷合计（结账检查②） */
    @Select("SELECT COALESCE(SUM(CASE WHEN l.DIRECTION='DR' THEN l.AMOUNT ELSE 0 END),0) DR_SUM, "
            + " COALESCE(SUM(CASE WHEN l.DIRECTION='CR' THEN l.AMOUNT ELSE 0 END),0) CR_SUM "
            + "FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
            + "WHERE v.PERIOD=#{period} AND v.STATUS='POSTED' AND v.DEL_FLAG='0' AND l.DEL_FLAG='0'")
    Map<String, Object> trialBalance(@Param("period") String period);

    /** 指定科目在指定期间及之前的累计余额（资产勾稽用：1601-1602 期末，design D4③） */
    @Select("<script>SELECT COALESCE(SUM(BALANCE),0) FROM ("
            + " SELECT (DR_TOTAL - CR_TOTAL) BALANCE FROM erp_fin_balance "
            + " WHERE ACCOUNT_CODE=#{accountCode} AND PERIOD &lt;= #{period} AND DEL_FLAG='0'"
            + ") t</script>")
    java.math.BigDecimal cumulativeBalance(@Param("accountCode") String accountCode,
                                           @Param("period") String period);
}
