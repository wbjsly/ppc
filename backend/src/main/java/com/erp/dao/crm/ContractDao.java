package com.erp.dao.crm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.crm.Contract;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ContractDao extends BaseMapper<Contract> {

    /** 既有合同编号（CT-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT CONTRACT_NO FROM erp_crm_contract WHERE CONTRACT_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 商机名下既有合同（报价转化自动建草稿防重，spec sales-contract 15.2） */
    @Select("SELECT * FROM erp_crm_contract WHERE OPP_ID = #{oppId} AND DEL_FLAG='0' "
            + "ORDER BY CREATE_DATE LIMIT 1")
    Contract selectByOppId(@Param("oppId") String oppId);
}
