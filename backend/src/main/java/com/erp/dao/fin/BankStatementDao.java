package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.BankStatement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BankStatementDao extends BaseMapper<BankStatement> {

    /** 某账户某期间对账单（查重/锁定守卫用） */
    @Select("SELECT * FROM erp_fin_bank_statement WHERE BANK_ACCOUNT_ID = #{accountId} "
            + "AND PERIOD = #{period} AND DEL_FLAG = '0' LIMIT 1")
    BankStatement selectByAccountPeriod(@Param("accountId") String accountId,
                                        @Param("period") String period);

    /** 某期间已 AUDITED 的对账单覆盖的账户 ID 集合（回单匹配锁定守卫 D6） */
    @Select("SELECT BANK_ACCOUNT_ID FROM erp_fin_bank_statement WHERE PERIOD = #{period} "
            + "AND STATUS = 'AUDITED' AND DEL_FLAG = '0'")
    List<String> selectAuditedAccountIds(@Param("period") String period);

    /** 结账⑧：某期间存在对账单的 ACTIVE 账户数与未 AUDITED 数（按需阻断判定） */
    @Select("SELECT COUNT(*) FROM erp_fin_bank_statement s "
            + "JOIN erp_fin_bank_account a ON a.ID = s.BANK_ACCOUNT_ID "
            + "WHERE s.PERIOD = #{period} AND s.DEL_FLAG = '0' AND a.STATUS = 'ACTIVE' AND a.DEL_FLAG = '0'")
    long countStatementsForActive(@Param("period") String period);

    @Select("SELECT COUNT(*) FROM erp_fin_bank_statement s "
            + "JOIN erp_fin_bank_account a ON a.ID = s.BANK_ACCOUNT_ID "
            + "WHERE s.PERIOD = #{period} AND s.DEL_FLAG = '0' AND s.STATUS <> 'AUDITED' "
            + "AND a.STATUS = 'ACTIVE' AND a.DEL_FLAG = '0'")
    long countUnauditedForActive(@Param("period") String period);
}
