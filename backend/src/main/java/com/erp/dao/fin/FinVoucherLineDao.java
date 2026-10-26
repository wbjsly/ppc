package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinVoucherLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface FinVoucherLineDao extends BaseMapper<FinVoucherLine> {

    /**
     * 某会计期间指定科目借方发生额合计（仅已过账凭证）。
     * 供 BR-4.13-13 折旧勾稽：折旧明细本期折旧合计 vs 总账折旧费用科目本期借方发生额。
     *
     * @param period      会计期间 yyyyMM（erp_fin_voucher.PERIOD）
     * @param accountCode 折旧费用科目（借方）
     */
    @Select("SELECT COALESCE(SUM(l.AMOUNT), 0) FROM erp_fin_voucher_line l " +
            "JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID AND v.DEL_FLAG = '0' " +
            "WHERE v.PERIOD = #{period} AND v.STATUS = 'POSTED' AND l.DEL_FLAG = '0' " +
            "AND l.ACCOUNT_CODE = #{accountCode} AND l.DIRECTION = 'DR'")
    BigDecimal sumDrByPeriodAndAccount(@Param("period") String period,
                                       @Param("accountCode") String accountCode);

    /**
     * 某会计期间指定来源单据（sourceType）的借方发生额合计（仅已过账凭证）。
     * 供 BR-4.13-13 折旧勾稽：折旧凭证借方合计 vs 明细合计，避免同科目其他分录干扰。
     *
     * @param period     会计期间 yyyyMM（erp_fin_voucher.PERIOD）
     * @param sourceType 来源类型（如 DEPRECIATION）
     */
    @Select("SELECT COALESCE(SUM(l.AMOUNT), 0) FROM erp_fin_voucher_line l " +
            "JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID AND v.DEL_FLAG = '0' " +
            "WHERE v.PERIOD = #{period} AND v.STATUS = 'POSTED' AND l.DEL_FLAG = '0' " +
            "AND v.SOURCE_TYPE = #{sourceType} AND l.DIRECTION = 'DR'")
    BigDecimal sumDrByPeriodAndSourceType(@Param("period") String period,
                                          @Param("sourceType") String sourceType);
}
