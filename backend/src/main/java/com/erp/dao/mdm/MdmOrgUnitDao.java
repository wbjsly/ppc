package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmOrgUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MdmOrgUnitDao extends BaseMapper<MdmOrgUnit> {

    /** 同类型最大编码，用于生成下一个 OU-XXX-NN（按类型独立计数） */
    @Select("SELECT MAX(OU_CODE) FROM erp_mdm_org_unit WHERE OU_TYPE = #{ouType}")
    String selectMaxCodeByType(@Param("ouType") String ouType);

    /** 直接子节点（深度上限 3 只需逐层展开） */
    @Select("SELECT * FROM erp_mdm_org_unit WHERE DEL_FLAG = '0' AND PARENT_ID = #{parentId}")
    List<MdmOrgUnit> selectChildren(@Param("parentId") String parentId);
}
