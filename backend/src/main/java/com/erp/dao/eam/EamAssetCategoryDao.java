package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamAssetCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EamAssetCategoryDao extends BaseMapper<EamAssetCategory> {

    /** 生效分类（ACTIVE，供转资带入默认折旧政策） */
    @Select("SELECT * FROM erp_eam_asset_category WHERE CATEGORY_CODE = #{code} " +
            "AND STATUS = 'ACTIVE' AND DEL_FLAG = '0' LIMIT 1")
    EamAssetCategory selectActiveByCode(@Param("code") String code);
}
