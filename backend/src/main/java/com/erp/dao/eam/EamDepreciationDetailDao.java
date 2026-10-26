package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamDepreciationDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface EamDepreciationDetailDao extends BaseMapper<EamDepreciationDetail> {

    /**
     * 本期按成本中心×借方科目汇总（折旧凭证借方多行取数；BR-4.13-13 勾稽同口径）。
     */
    @Select("SELECT COST_CENTER_CODE AS costCenterCode, DEBIT_ACCOUNT AS debitAccount, " +
            "SUM(PERIOD_DEPR) AS amount, COUNT(*) AS cardCount " +
            "FROM erp_eam_depreciation_detail " +
            "WHERE RUN_ID = #{runId} AND DEL_FLAG = '0' " +
            "GROUP BY COST_CENTER_CODE, DEBIT_ACCOUNT")
    List<Map<String, Object>> sumByCostCenterAndAccount(@Param("runId") String runId);

    /**
     * 按凭证号反查明细（明细↔凭证反查）。
     */
    @Select("SELECT * FROM erp_eam_depreciation_detail WHERE VOUCHER_ID = #{voucherId} AND DEL_FLAG = '0'")
    List<EamDepreciationDetail> selectByVoucher(@Param("voucherId") String voucherId);
}
