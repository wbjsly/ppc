package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IntfDeliveryDao extends BaseMapper<IntfDelivery> {

    /** 已投递成功的事件 ID（用于跳过重复建行） */
    @Select("SELECT EVENT_ID FROM erp_intf_delivery WHERE EVENT_ID = #{eventId} LIMIT 1")
    String selectExistsEvent(@Param("eventId") String eventId);

    /** 到期待投递（PENDING/RETRYING/STAGED 且 NEXT_AT 已到或为空） */
    @Select("SELECT * FROM erp_intf_delivery WHERE STATUS IN ('PENDING','RETRYING','STAGED') " +
            "AND (NEXT_AT IS NULL OR NEXT_AT <= #{now}) AND DEL_FLAG = '0' " +
            "ORDER BY CREATE_DATE ASC LIMIT #{limit}")
    List<IntfDelivery> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 卡死在 DELIVERING 超过 30 秒的行（无 ACK 判失败，BR-4.9-14） */
    @Select("SELECT * FROM erp_intf_delivery WHERE STATUS = 'DELIVERING' AND UPDATE_DATE <= #{stale} " +
            "AND DEL_FLAG = '0' LIMIT #{limit}")
    List<IntfDelivery> selectStale(@Param("stale") LocalDateTime stale, @Param("limit") int limit);

    /** 暂存队列条数（PENDING/RETRYING/STAGED 视为在途，达上限告警 BR-4.9-16） */
    @Select("SELECT COUNT(*) FROM erp_intf_delivery WHERE STATUS IN ('PENDING','RETRYING','STAGED') AND DEL_FLAG = '0'")
    int countStaged();

    @Select("SELECT COUNT(*) FROM erp_intf_delivery WHERE STATUS = 'DEAD' AND DEL_FLAG = '0'")
    int countDead();

    /** 死信后新增同事件的重复投递保护 */
    @Update("UPDATE erp_ops_outbox SET STATUS = #{status}, CONSUMER_NOTE = #{note} WHERE EVENT_ID = #{eventId}")
    int updateOutboxStatus(@Param("eventId") String eventId, @Param("status") String status,
                           @Param("note") String note);

    @Select("SELECT * FROM erp_intf_delivery WHERE STATUS = 'DEAD' AND TICKET_ID IS NULL AND DEL_FLAG = '0'")
    List<IntfDelivery> selectDeadWithoutTicket();

    /** 端到端延迟 = 业务事件发生时间戳 → 消费方 ACK 时间戳（FR-4.9-5 口径），单位秒 */
    @Select("SELECT AVG(TIMESTAMPDIFF(SECOND, o.OCCURRED_AT, d.ACK_AT)) FROM erp_intf_delivery d " +
            "JOIN erp_ops_outbox o ON o.EVENT_ID = d.EVENT_ID " +
            "WHERE d.STATUS = 'DELIVERED' AND d.ACK_AT IS NOT NULL AND d.ACK_AT >= #{from} AND d.DEL_FLAG = '0'")
    Double selectAvgAckSeconds(@Param("from") java.time.LocalDateTime from);

    @Select("SELECT COUNT(*) FROM erp_intf_delivery WHERE STATUS = 'DELIVERED' AND ACK_AT >= #{from} AND DEL_FLAG = '0'")
    int countDeliveredSince(@Param("from") java.time.LocalDateTime from);
}
