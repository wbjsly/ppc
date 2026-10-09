package com.erp.dao.scm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.scm.ScmScorecardResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ScmScorecardResultDao extends BaseMapper<ScmScorecardResult> {

    @Select("SELECT * FROM erp_scm_scorecard_result WHERE MONTH_TAG = #{m} AND SUPPLIER_ID = #{s} " +
            "AND DEL_FLAG = '0' ORDER BY VERSION_TAG ASC")
    java.util.List<ScmScorecardResult> selectSupplierMonth(@Param("m") String monthTag,
                                                           @Param("s") String supplierId);

    /** 供应商最近一个已计算月份（连续 C 判定用） */
    @Select("SELECT * FROM erp_scm_scorecard_result WHERE SUPPLIER_ID = #{s} AND MONTH_TAG < #{m} " +
            "AND DEL_FLAG = '0' ORDER BY MONTH_TAG DESC LIMIT 1")
    ScmScorecardResult selectPrevMonth(@Param("s") String supplierId, @Param("m") String monthTag);
}
