package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrCertCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrCertCategoryDao extends BaseMapper<HrCertCategory> {

    /** 按前缀取编码（nextCode 用）。 */
    @Select("SELECT CATE_CODE FROM hr_cert_category WHERE CATE_CODE LIKE CONCAT(#{prefix}, '%') AND DEL_FLAG='0'")
    List<String> selectCodesByPrefix(String prefix);
}
