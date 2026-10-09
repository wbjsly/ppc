package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmSupplierMergeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MdmSupplierMergeLogDao extends BaseMapper<MdmSupplierMergeLog> {

    /** 同库最大日志单号，用于生成下一个 MG-{4位流水}（并发由 LOG_NO 唯一索引兜底） */
    @Select("SELECT MAX(LOG_NO) FROM erp_mdm_supplier_merge_log")
    String selectMaxLogNo();
}
