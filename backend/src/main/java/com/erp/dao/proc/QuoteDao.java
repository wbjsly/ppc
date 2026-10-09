package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.Quote;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QuoteDao extends BaseMapper<Quote> {
}
