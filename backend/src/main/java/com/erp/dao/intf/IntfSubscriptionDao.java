package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfSubscription;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntfSubscriptionDao extends BaseMapper<IntfSubscription> {

    @Select("SELECT * FROM erp_intf_subscription WHERE PARTNER_CODE = #{partnerCode} " +
            "AND EVENT_TYPE = #{eventType} AND DEL_FLAG = '0' LIMIT 1")
    IntfSubscription selectByPartnerAndType(@Param("partnerCode") String partnerCode,
                                            @Param("eventType") String eventType);
}
