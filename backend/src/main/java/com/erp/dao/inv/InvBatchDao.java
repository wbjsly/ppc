package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvBatchDao extends BaseMapper<InvBatch> {

    /** 既有批次号（同物料判重与生成取流水用） */
    @Select("SELECT BATCH_NO FROM erp_inv_batch WHERE DEL_FLAG = '0' AND ITEM_CODE = #{itemCode}")
    List<String> selectBatchNosByItem(@Param("itemCode") String itemCode);

    /** 当日既有系统生成批次号（B+yyMMdd- 前缀，取最大流水） */
    @Select("SELECT BATCH_NO FROM erp_inv_batch WHERE DEL_FLAG = '0' AND BATCH_NO LIKE CONCAT(#{prefix}, '%')")
    List<String> selectBatchNosByPrefix(@Param("prefix") String prefix);
}
