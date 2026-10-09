package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvTransferOrderLine;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TransferOrderLineDao extends BaseMapper<InvTransferOrderLine> {
}
