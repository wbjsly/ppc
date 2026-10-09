package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvTransferOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TransferOrderDao extends BaseMapper<InvTransferOrder> {

    /** 单号流水（TR-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT TRANSFER_NO FROM erp_inv_transfer_order WHERE TRANSFER_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 行锁读取（过账/状态迁移前锁头，design D3） */
    @Select("SELECT * FROM erp_inv_transfer_order WHERE ID = #{id} AND DEL_FLAG = '0' FOR UPDATE")
    InvTransferOrder selectByIdForUpdate(@Param("id") String id);
}
