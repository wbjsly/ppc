package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfChannel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IntfChannelDao extends BaseMapper<IntfChannel> {

    @Select("SELECT * FROM erp_intf_channel WHERE PARTNER_CODE = #{partnerCode} AND DEL_FLAG = '0' LIMIT 1")
    IntfChannel selectByPartnerCode(@Param("partnerCode") String partnerCode);

    /**
     * 解除熔断：显式置 NULL（MP updateById 忽略 null 字段，直接置 null 无效）。
     * CIRCUIT_SINCE 记为解封时刻 —— 解封后的失败率只统计该时刻之后的调用，
     * 否则解封瞬间会被此前的故障窗口立刻重新熔断。
     */
    @Update("UPDATE erp_intf_channel SET CIRCUIT_STATE = 'CLOSED', CIRCUIT_SINCE = #{since}, " +
            "CIRCUIT_RECOVER_AT = NULL, CIRCUIT_PROBE_OK = 0, PROBE_HITS = 0 WHERE ID = #{id}")
    int clearCircuit(@Param("id") String id, @Param("since") java.time.LocalDateTime since);
}
