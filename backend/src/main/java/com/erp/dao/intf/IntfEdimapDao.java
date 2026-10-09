package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfEdimap;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntfEdimapDao extends BaseMapper<IntfEdimap> {

    @Select("SELECT * FROM erp_intf_edimap WHERE PARTNER_CODE = #{partnerCode} AND MSG_TYPE = #{msgType} " +
            "AND STATUS = 'ENABLED' AND DEL_FLAG = '0' LIMIT 1")
    IntfEdimap selectRule(@Param("partnerCode") String partnerCode, @Param("msgType") String msgType);
}
