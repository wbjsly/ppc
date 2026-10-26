package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.TaxReturnLine;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaxReturnLineDao extends BaseMapper<TaxReturnLine> {

    /** 重新生成栏次前清旧行（幂等） */
    @Delete("DELETE FROM erp_fin_tax_return_line WHERE RETURN_ID = #{returnId}")
    int deleteByReturnId(@Param("returnId") String returnId);
}
