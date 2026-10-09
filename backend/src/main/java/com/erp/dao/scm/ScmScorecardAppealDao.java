package com.erp.dao.scm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.scm.ScmScorecardAppeal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ScmScorecardAppealDao extends BaseMapper<ScmScorecardAppeal> {

    @Select("SELECT MAX(CAST(SUBSTR(APPEAL_NO, 11) AS UNSIGNED)) FROM erp_scm_scorecard_appeal " +
            "WHERE APPEAL_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    @Select("SELECT * FROM erp_scm_scorecard_appeal WHERE RESULT_ID = #{r} AND DEL_FLAG = '0' LIMIT 1")
    ScmScorecardAppeal selectByResult(@Param("r") String resultId);
}
