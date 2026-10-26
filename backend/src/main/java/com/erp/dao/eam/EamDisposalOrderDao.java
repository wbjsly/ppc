package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamDisposalOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 处置单 DAO（add-asset-disposal）。
 */
@Mapper
public interface EamDisposalOrderDao extends BaseMapper<EamDisposalOrder> {

    /** 未结应收处置单（BR-35 关闭校验） */
    @Select("SELECT * FROM erp_eam_disposal_order WHERE DEL_FLAG = '0' "
            + "AND APP_STATUS IN ('EXECUTED','RECEIVABLE') AND RECEIVABLE_BAL > 0 LIMIT 1")
    EamDisposalOrder selectOpenReceivable();

    /** 同卡进行中处置单（重复发起阻断：同资产唯一处置链） */
    @Select("SELECT DISTINCT o.* FROM erp_eam_disposal_order o "
            + "JOIN erp_eam_disposal_line l ON l.ORDER_ID = o.ID AND l.DEL_FLAG = '0' "
            + "WHERE o.DEL_FLAG = '0' AND l.CARD_ID = #{cardId} "
            + "AND o.APP_STATUS IN ('DRAFT','PENDING_APPROVE','APPROVED','EXECUTED','RECEIVABLE') LIMIT 1")
    EamDisposalOrder selectInFlightByCard(@Param("cardId") String cardId);
}
