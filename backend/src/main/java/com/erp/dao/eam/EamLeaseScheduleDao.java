package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamLeaseSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface EamLeaseScheduleDao extends BaseMapper<EamLeaseSchedule> {

    /** 合同当前计划行（SNAPSHOT_SEQ=0），按月序升序 */
    @Select("SELECT * FROM erp_eam_lease_schedule WHERE CONTRACT_ID = #{contractId} " +
            "AND SNAPSHOT_SEQ = 0 AND DEL_FLAG = '0' ORDER BY SEQ ASC")
    List<EamLeaseSchedule> selectByContract(@Param("contractId") String contractId);

    /** 合同快照行（重计量前归档） */
    @Select("SELECT * FROM erp_eam_lease_schedule WHERE CONTRACT_ID = #{contractId} " +
            "AND SNAPSHOT_SEQ > 0 AND DEL_FLAG = '0' ORDER BY SNAPSHOT_SEQ DESC, SEQ ASC")
    List<EamLeaseSchedule> selectSnapshots(@Param("contractId") String contractId);

    /** 当月已处理行（幂等锚：任一行 PROCESSED_FLAG=1 即该合同该月已处理） */
    @Select("SELECT COUNT(*) FROM erp_eam_lease_schedule WHERE CONTRACT_ID = #{contractId} " +
            "AND PERIOD_TAG = #{periodTag} AND SNAPSHOT_SEQ = 0 AND DEL_FLAG = '0' " +
            "AND PROCESSED_FLAG = '1'")
    int countProcessed(@Param("contractId") String contractId, @Param("periodTag") String periodTag);

    /** 付款累计（核销差异校验 BR-4.13-43） */
    @Select("SELECT COALESCE(SUM(PAYMENT), 0) FROM erp_eam_lease_schedule " +
            "WHERE CONTRACT_ID = #{contractId} AND SNAPSHOT_SEQ = 0 AND DEL_FLAG = '0' " +
            "AND PROCESSED_FLAG = '1'")
    BigDecimal sumProcessedPayment(@Param("contractId") String contractId);
}
