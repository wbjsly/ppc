package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.ArReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ArReceiptDao extends BaseMapper<ArReceipt> {

    /** 既有回款单号（RC-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT RCPT_NO FROM erp_fin_ar_receipt WHERE RCPT_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
