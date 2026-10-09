package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvDailyBalance;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 日结余额快照 DAO（spec stock-snapshot）。
 * 约定：不提供业务 update —— 插入即终态（BR-4.4-17 只读语义）；
 * 仅 insert / 按日存在性查询 / （同日重跑前）按日清空。
 */
public interface InvDailyBalanceDao extends BaseMapper<InvDailyBalance> {

    /**
     * 指定结算日是否已有快照（存在 → 日结跳过，幂等关键）。
     */
    @Select("SELECT COUNT(*) FROM erp_inv_daily_balance WHERE BAL_DATE = #{balDate} "
            + "AND DEL_FLAG = '0'")
    long countByBalDate(@Param("balDate") LocalDate balDate);
}
