package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmCostCenter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MdmCostCenterDao extends BaseMapper<MdmCostCenter> {

    /** 同类型最大编码，用于生成下一个 CC-XXX-NN（按类型独立计数） */
    @Select("SELECT MAX(CC_CODE) FROM erp_mdm_cost_center WHERE COST_TYPE = #{costType}")
    String selectMaxCodeByType(@Param("costType") String costType);

    /** 指定主体下同类型编码查重（C-4.1-07 展示近 3 条相似项） */
    @Select("SELECT * FROM erp_mdm_cost_center WHERE DEL_FLAG = '0' AND LEGAL_ENTITY_ID = #{legalEntityId} AND CC_CODE LIKE CONCAT(#{prefix}, '%') ORDER BY CC_CODE DESC LIMIT 3")
    List<MdmCostCenter> findSimilarByCode(@Param("legalEntityId") String legalEntityId, @Param("prefix") String prefix);

    /** 指定主体下全部子孙节点（自引用层级展开，深度上限 3 只需两跳） */
    @Select("SELECT * FROM erp_mdm_cost_center WHERE DEL_FLAG = '0' AND PARENT_ID = #{parentId}")
    List<MdmCostCenter> selectChildren(@Param("parentId") String parentId);
}
