package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfTicket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntfTicketDao extends BaseMapper<IntfTicket> {

    /** 工单号流水：IT+yyyyMMdd+000001（前缀 10 字符，seq 自第 11 位起） */
    @Select("SELECT MAX(CAST(SUBSTR(TICKET_NO, 11) AS UNSIGNED)) FROM erp_intf_ticket " +
            "WHERE TICKET_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    @Select("SELECT * FROM erp_intf_ticket WHERE REF_ID = #{refId} AND SOURCE = #{source} AND DEL_FLAG = '0' LIMIT 1")
    IntfTicket selectByRef(@Param("refId") String refId, @Param("source") String source);

    @Select("SELECT COUNT(*) FROM erp_intf_ticket WHERE STATUS IN ('OPEN','PROCESSING') AND DEL_FLAG = '0'")
    int countOpen();
}
