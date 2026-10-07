package com.erp.dao.crm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.crm.LeadScoreModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LeadScoreModelDao extends BaseMapper<LeadScoreModel> {

    /** 当前生效模型（行业/产品线优先，其次 DEFAULT） */
    @Select("SELECT * FROM erp_crm_lead_score_model WHERE DEL_FLAG = '0' AND STATUS = 'ACTIVE' "
            + "AND IS_CURRENT = 1 ORDER BY (MODEL_KEY = 'DEFAULT'), "
            + "(INDUSTRY IS NOT NULL AND INDUSTRY = #{industry}) DESC, "
            + "(PRODUCT_LINE IS NOT NULL AND PRODUCT_LINE = #{productLine}) DESC LIMIT 1")
    LeadScoreModel selectActive(@Param("industry") String industry, @Param("productLine") String productLine);

    @Select("SELECT * FROM erp_crm_lead_score_model WHERE DEL_FLAG = '0' "
            + "AND MODEL_KEY = #{key} ORDER BY VERSION DESC")
    List<LeadScoreModel> selectVersions(@Param("key") String key);
}
