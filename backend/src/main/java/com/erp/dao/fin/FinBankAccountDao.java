package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinBankAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface FinBankAccountDao extends BaseMapper<FinBankAccount> {

    /**
     * 余额条件扣减（BR-4.6-21，design D4；add-fx-revaluation task 3.1 三列余额模型）：
     * amount 为账户币种额（外币账户=外币额）；守卫查 FC_BALANCE，双列同步维护——
     * FC_BALANCE ± amount，BALANCE ± amount×BOOKING_RATE（CNY 账户 rate=1，行为与旧版等价）。
     * 余额充足才生效，返回 0 = 不足或并发冲突；调用方必须把 rows==0 当 422 处理（不透支）。
     */
    @Update("UPDATE erp_fin_bank_account SET " +
            "FC_BALANCE = FC_BALANCE - #{amount}, " +
            "BALANCE = BALANCE - #{amount} * COALESCE(BOOKING_RATE, 1), " +
            "UPDATE_DATE = NOW() " +
            "WHERE ID = #{id} AND DEL_FLAG = '0' AND STATUS = 'ACTIVE' " +
            "AND FC_BALANCE >= #{amount}")
    int deductBalance(@Param("id") String id, @Param("amount") BigDecimal amount);

    /** 余额回退（仅补偿路径使用，条件更新防并发；双列同步，语义同上） */
    @Update("UPDATE erp_fin_bank_account SET " +
            "FC_BALANCE = FC_BALANCE + #{amount}, " +
            "BALANCE = BALANCE + #{amount} * COALESCE(BOOKING_RATE, 1), " +
            "UPDATE_DATE = NOW() WHERE ID = #{id} AND DEL_FLAG = '0'")
    int refundBalance(@Param("id") String id, @Param("amount") BigDecimal amount);

    /**
     * 余额条件入账（收款侧，spec bank-account ADDED，BR-4.6-21 对称；双列同步同上）：
     * 账户 ACTIVE 才入账，返回 0 = 账户停用/不存在（422 处理，同事务回滚）。
     */
    @Update("UPDATE erp_fin_bank_account SET " +
            "FC_BALANCE = FC_BALANCE + #{amount}, " +
            "BALANCE = BALANCE + #{amount} * COALESCE(BOOKING_RATE, 1), " +
            "UPDATE_DATE = NOW() " +
            "WHERE ID = #{id} AND DEL_FLAG = '0' AND STATUS = 'ACTIVE'")
    int addBalance(@Param("id") String id, @Param("amount") BigDecimal amount);
}
