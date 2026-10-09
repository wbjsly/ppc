package com.erp.dao.crm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.crm.Lead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LeadDao extends BaseMapper<Lead> {

    /** 查重：公司名称 + 联系人（FR-4.8-1-1） */
    @Select("SELECT * FROM erp_crm_lead WHERE DEL_FLAG = '0' "
            + "AND COMPANY_NAME = #{company} AND CONTACT_NAME = #{contact} LIMIT 5")
    List<Lead> selectDuplicates(@Param("company") String company, @Param("contact") String contact);

    /** 既有最大线索编号（LD-YYYYMMDD-NNNN 的流水部分） */
    @Select("SELECT LEAD_NO FROM erp_crm_lead WHERE LEAD_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
