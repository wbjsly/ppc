package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvScrapOrderLine;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScrapOrderLineDao extends BaseMapper<InvScrapOrderLine> {
}
