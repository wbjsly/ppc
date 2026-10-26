package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrLeaveBalanceBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrLeaveBalanceBatchDao extends BaseMapper<HrLeaveBalanceBatch> {
    @Select("SELECT BATCH_NO FROM hr_leave_balance_batch WHERE BATCH_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
