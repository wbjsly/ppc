package com.erp.dao.scm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.scm.ScmScorecardRectify;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ScmScorecardRectifyDao extends BaseMapper<ScmScorecardRectify> {

    @Select("SELECT MAX(CAST(SUBSTR(RECTIFY_NO, 11) AS UNSIGNED)) FROM erp_scm_scorecard_rectify " +
            "WHERE RECTIFY_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /** 供应商是否存在冻结中的整改（PO 创建阻断依据，BR-4.9-05） */
    @Select("SELECT COUNT(*) FROM erp_scm_scorecard_rectify WHERE SUPPLIER_ID = #{s} " +
            "AND FROZEN = 1 AND STATUS = 'OPEN' AND DEL_FLAG = '0'")
    int countFrozen(@Param("s") String supplierId);

    @Select("SELECT * FROM erp_scm_scorecard_rectify WHERE SUPPLIER_ID = #{s} AND STATUS = 'OPEN' " +
            "AND FROZEN = 1 AND DEL_FLAG = '0' LIMIT 1")
    ScmScorecardRectify selectFrozenOpen(@Param("s") String supplierId);
}
