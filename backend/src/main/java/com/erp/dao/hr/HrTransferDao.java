package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrTransfer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrTransferDao extends BaseMapper<HrTransfer> {
    @Select("SELECT TRF_NO FROM hr_transfer WHERE TRF_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
