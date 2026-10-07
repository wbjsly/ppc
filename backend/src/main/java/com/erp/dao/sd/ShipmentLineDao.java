package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.ShipmentLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface ShipmentLineDao extends BaseMapper<ShipmentLine> {

    /**
     * 该 SO 行的在途发货占用（9.5：可发量 = 未发余量 − 本值）。
     * 在途 = 未取消发货单的 DRAFT/POSTED 行——CONFIRMED 及之后的单已回写 SHIPPED_QTY，
     * 若计入会与未发余量构成双计（缺陷修复：签收前后可发量应一致）。
     */
    @Select("SELECT COALESCE(SUM(l.QTY), 0) FROM erp_sd_shipment_line l "
            + "JOIN erp_sd_shipment s ON l.SHIP_ID = s.ID "
            + "WHERE l.SO_LINE_ID = #{soLineId} AND l.LINE_STATUS <> 'CANCELLED' "
            + "AND s.DEL_FLAG = '0' AND s.STATUS IN ('DRAFT','POSTED')")
    BigDecimal selectInFlightBySoLine(@Param("soLineId") String soLineId);
}
