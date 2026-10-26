package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrPosition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrPositionDao extends BaseMapper<HrPosition> {
    @Select("SELECT POS_NO FROM hr_position WHERE POS_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
