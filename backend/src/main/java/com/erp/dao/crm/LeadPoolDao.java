package com.erp.dao.crm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.crm.LeadPool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LeadPoolDao extends BaseMapper<LeadPool> {

    /** 该线索最近一条未指派的入池记录 */
    @Select("SELECT * FROM erp_crm_lead_pool WHERE DEL_FLAG = '0' AND LEAD_ID = #{leadId} "
            + "AND STATUS = 'PENDING' ORDER BY POOLED_AT DESC LIMIT 1")
    LeadPool selectPendingByLead(@Param("leadId") String leadId);
}
