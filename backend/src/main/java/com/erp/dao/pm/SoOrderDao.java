package com.erp.dao.pm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.pm.SoOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SoOrderDao extends BaseMapper<SoOrder> {

    /** SO+yyyyMMdd-序号 取最大流水 */
    @Select("SELECT COUNT(*) FROM erp_pm_service_order WHERE ORDER_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
