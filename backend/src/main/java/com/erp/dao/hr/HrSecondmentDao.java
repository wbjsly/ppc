package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrSecondment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrSecondmentDao extends BaseMapper<HrSecondment> {

    /** 按前缀取单号（nextNo 用）。 */
    @Select("SELECT SEC_NO FROM hr_secondment WHERE SEC_NO LIKE CONCAT(#{prefix}, '%') AND DEL_FLAG='0'")
    List<String> selectNosByPrefix(String prefix);
}
