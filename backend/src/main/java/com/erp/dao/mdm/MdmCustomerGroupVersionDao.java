package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmCustomerGroupVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MdmCustomerGroupVersionDao extends BaseMapper<MdmCustomerGroupVersion> {

    /** 该客户集团当前最大版本号（信用变更台账续号用） */
    @org.apache.ibatis.annotations.Select("SELECT MAX(VERSION_NO) FROM erp_mdm_customer_group_version "
            + "WHERE ENTITY_ID = #{entityId}")
    Integer selectMaxVersion(@org.apache.ibatis.annotations.Param("entityId") String entityId);
}
