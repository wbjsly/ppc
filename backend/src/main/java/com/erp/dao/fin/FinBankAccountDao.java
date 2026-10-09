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
     * 余额条件扣减（BR-4.6-21，design D4）：余额充足才生效，返回 0 = 不足或并发冲突。
     * 调用方必须把 rows==0 当 422 处理（不透支、不产生负余额）。
     */
    @Update("UPDATE erp_fin_bank_account SET BALANCE = BALANCE - #{amount}, " +
            "UPDATE_DATE = NOW() " +
            "WHERE ID = #{id} AND DEL_FLAG = '0' AND STATUS = 'ACTIVE' AND BALANCE >= #{amount}")
    int deductBalance(@Param("id") String id, @Param("amount") BigDecimal amount);

    /** 余额回退（仅补偿路径使用，条件更新防并发） */
    @Update("UPDATE erp_fin_bank_account SET BALANCE = BALANCE + #{amount}, " +
            "UPDATE_DATE = NOW() WHERE ID = #{id} AND DEL_FLAG = '0'")
    int refundBalance(@Param("id") String id, @Param("amount") BigDecimal amount);
}
