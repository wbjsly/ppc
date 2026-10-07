package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfSandboxCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntfSandboxCaseDao extends BaseMapper<IntfSandboxCase> {

    @Select("SELECT COUNT(*) FROM erp_intf_sandbox_case WHERE CONTRACT_ID = #{contractId} AND DEL_FLAG = '0'")
    int countTotal(@Param("contractId") String contractId);

    @Select("SELECT COUNT(*) FROM erp_intf_sandbox_case WHERE CONTRACT_ID = #{contractId} " +
            "AND RESULT = 'PASS' AND DEL_FLAG = '0'")
    int countPassed(@Param("contractId") String contractId);
}
