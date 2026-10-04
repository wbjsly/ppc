package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmItemDict;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MdmItemDictDao extends BaseMapper<MdmItemDict> {

    /** 指定类型的有效字典项 */
    @Select("SELECT * FROM erp_mdm_item_dict WHERE DICT_TYPE = #{dictType} AND STATUS = '1' ORDER BY SORT_ORDER")
    List<MdmItemDict> selectByType(@Param("dictType") String dictType);

    /** 参照完整性校验：指定类型下编码是否存在 */
    @Select("SELECT COUNT(1) FROM erp_mdm_item_dict WHERE DICT_TYPE = #{dictType} AND DICT_CODE = #{code} AND STATUS = '1'")
    int countActiveCode(@Param("dictType") String dictType, @Param("code") String code);
}
