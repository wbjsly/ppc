package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrTrainPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrTrainPlanDao extends BaseMapper<HrTrainPlan> {

    /** 按前缀取计划号（nextNo 用）。 */
    @Select("SELECT PLAN_NO FROM hr_train_plan WHERE PLAN_NO LIKE CONCAT(#{prefix}, '%') AND DEL_FLAG='0'")
    List<String> selectNosByPrefix(String prefix);
}
