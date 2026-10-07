package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.PrepaymentNotice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PrepaymentNoticeDao extends BaseMapper<PrepaymentNotice> {

    /** 既有预收通知单流水（PN-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT NOTICE_NO FROM erp_sd_prepayment_notice WHERE NOTICE_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
