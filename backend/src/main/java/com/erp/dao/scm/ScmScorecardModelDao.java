package com.erp.dao.scm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.scm.ScmScorecardModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ScmScorecardModelDao extends BaseMapper<ScmScorecardModel> {

    /** 生效模型：品类优先，否则通用（categoryCode IS NULL） */
    @Select("SELECT * FROM erp_scm_scorecard_model WHERE STATUS = 'ACTIVE' AND DEL_FLAG = '0' " +
            "AND (CATEGORY_CODE = #{category} OR CATEGORY_CODE IS NULL) " +
            "ORDER BY (CATEGORY_CODE = #{category}) DESC, VERSION DESC LIMIT 1")
    ScmScorecardModel selectEffective(@Param("category") String category);
}
