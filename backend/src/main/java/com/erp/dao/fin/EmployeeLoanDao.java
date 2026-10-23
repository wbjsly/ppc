package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.EmployeeLoan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EmployeeLoanDao extends BaseMapper<EmployeeLoan> {

    /** 同期最大流水（借款单号 LN+yyyyMMdd 推号） */
    @Select("SELECT MAX(CAST(SUBSTRING(LOAN_NO, 11) AS UNSIGNED)) FROM erp_fin_employee_loan "
            + "WHERE LOAN_NO LIKE CONCAT(#{prefix}, '%') AND DEL_FLAG = '0'")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /**
     * 某员工已放款未核销借款（FIFO 升序，design D3）：
     * 账龄 = DATEDIFF(NOW(), PAID_DATE)，未核销余额 > 0
     */
    @Select("SELECT * FROM erp_fin_employee_loan WHERE EMPLOYEE_ID = #{employeeId} "
            + "AND STATUS = 'PAID' AND OUTSTANDING_BALANCE > 0 AND DEL_FLAG = '0' "
            + "ORDER BY PAID_DATE ASC, ID ASC")
    List<EmployeeLoan> selectOpenFifo(@Param("employeeId") String employeeId);

    /** 某员工最大账龄（天；无未核销已放款借款返回 NULL，决策⑥实时判定用） */
    @Select("SELECT MAX(DATEDIFF(CURDATE(), PAID_DATE)) FROM erp_fin_employee_loan "
            + "WHERE EMPLOYEE_ID = #{employeeId} AND STATUS = 'PAID' "
            + "AND OUTSTANDING_BALANCE > 0 AND DEL_FLAG = '0'")
    Integer selectMaxAgingDays(@Param("employeeId") String employeeId);
}
