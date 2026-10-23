package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.BankReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface BankReceiptDao extends BaseMapper<BankReceipt> {

    /**
     * 自动匹配候选（design D8）：同账户 + 方向映射 + 金额相等 + 日期 ±3 天，且未匹配。
     */
    @Select("SELECT * FROM erp_fin_bank_receipt WHERE DEL_FLAG='0' "
            + "AND MATCH_STATUS = 'UNMATCHED' AND BANK_ACCOUNT_ID = #{bankAccountId} "
            + "AND DIRECTION = #{direction} AND AMOUNT = #{amount} "
            + "AND TRADE_DATE BETWEEN DATE_SUB(#{payDate}, INTERVAL 3 DAY) "
            + "AND DATE_ADD(#{payDate}, INTERVAL 3 DAY) "
            + "ORDER BY TRADE_DATE, RECEIPT_NO LIMIT 1")
    BankReceipt selectAutoCandidate(@Param("bankAccountId") String bankAccountId,
                                    @Param("direction") String direction,
                                    @Param("amount") BigDecimal amount,
                                    @Param("payDate") LocalDate payDate);
}
