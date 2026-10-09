package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmCustomerGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MdmCustomerGroupDao extends BaseMapper<MdmCustomerGroup> {

    /** 同库最大客户编码，用于生成下一个 CUST-{4位流水} */
    @Select("SELECT MAX(CUSTOMER_CODE) FROM erp_mdm_customer_group")
    String selectMaxCode();

    /** 同名（其它集团视图）税号占用检查：返回占用方编码（BR-4.1-30） */
    @Select("SELECT CUSTOMER_CODE FROM erp_mdm_customer_group " +
            "WHERE DEL_FLAG = '0' AND TAX_NO = #{taxNo} AND ID != #{selfId} LIMIT 1")
    String findTaxNoHolder(@Param("taxNo") String taxNo, @Param("selfId") String selfId);
}
