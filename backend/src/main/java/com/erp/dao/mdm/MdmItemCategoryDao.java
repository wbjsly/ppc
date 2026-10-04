package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmItemCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MdmItemCategoryDao extends BaseMapper<MdmItemCategory> {

    /** 全部启用分类（物料表单与编码校验共用，平铺） */
    @Select("SELECT * FROM erp_mdm_item_category WHERE DEL_FLAG = '0' AND STATUS = '1' ORDER BY LEVEL, CATEGORY_CODE")
    List<MdmItemCategory> selectAllActive();

    /** 启用中的直接子分类（停用双重阻断：子孙清单） */
    @Select("SELECT * FROM erp_mdm_item_category WHERE DEL_FLAG = '0' AND PARENT_ID = #{parentId} AND STATUS = '1' ORDER BY CATEGORY_CODE")
    List<MdmItemCategory> selectActiveChildren(@Param("parentId") String parentId);

    /** 该分类下启用物料总数 */
    @Select("SELECT COUNT(1) FROM erp_mdm_item WHERE DEL_FLAG = '0' AND CATEGORY_CODE = #{categoryCode} AND STATUS = '1'")
    int countActiveItems(@Param("categoryCode") String categoryCode);

    /** 该分类下全部物料数（含停用，合并影响面统计） */
    @Select("SELECT COUNT(1) FROM erp_mdm_item WHERE DEL_FLAG = '0' AND CATEGORY_CODE = #{categoryCode}")
    int countAllItems(@Param("categoryCode") String categoryCode);

    /** 该分类下启用物料编码前 10 条（停用阻断清单） */
    @Select("SELECT ITEM_CODE FROM erp_mdm_item WHERE DEL_FLAG = '0' AND CATEGORY_CODE = #{categoryCode} AND STATUS = '1' ORDER BY ITEM_CODE LIMIT 10")
    List<String> selectActiveItemCodes(@Param("categoryCode") String categoryCode);
}
