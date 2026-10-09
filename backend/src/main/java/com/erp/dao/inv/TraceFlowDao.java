package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.TraceFlow;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TraceFlowDao extends BaseMapper<TraceFlow> {

    /**
     * 调拨在途流向（BR-4.4-48）：行批次匹配 + 头状态 OUT_POSTED（未 IN_POSTED）。
     * 一次集合查询（design D3 防 N+1）。
     */
    @Select("SELECT l.ID AS LINE_ID, l.ITEM_CODE, l.ITEM_NAME, l.BATCH_NO, l.QTY, "
            + "t.TRANSFER_NO AS DOC_NO, t.OUT_WH_CODE AS WH_CODE, t.IN_WH_CODE "
            + "FROM erp_inv_transfer_order_line l "
            + "JOIN erp_inv_transfer_order t ON t.ID = l.ORDER_ID AND t.DEL_FLAG = '0' "
            + "WHERE l.BATCH_NO = #{batchNo} AND l.DEL_FLAG = '0' "
            + "AND t.STATUS = 'OUT_POSTED'")
    List<Map<String, Object>> selectInTransitByBatch(@Param("batchNo") String batchNo);

    /**
     * 发运流向（BR-4.4-48）：行批次匹配 + 头状态 POSTED/CONFIRMED（未签收）/SIGNED（已签收）。
     * 一次集合查询。
     */
    @Select("SELECT l.ID AS LINE_ID, l.ITEM_CODE, l.ITEM_NAME, l.BATCH_NO, l.QTY, "
            + "s.SHIP_NO AS DOC_NO, s.STATUS AS SHIP_STATUS, s.CUSTOMER_CODE, s.CUSTOMER_NAME, "
            + "s.WAREHOUSE_CODE, s.SIGN_AT "
            + "FROM erp_sd_shipment_line l "
            + "JOIN erp_sd_shipment s ON s.ID = l.SHIP_ID AND s.DEL_FLAG = '0' "
            + "WHERE l.BATCH_NO = #{batchNo} AND l.DEL_FLAG = '0' "
            + "AND s.STATUS IN ('POSTED','CONFIRMED','SIGNED')")
    List<Map<String, Object>> selectShipmentByBatch(@Param("batchNo") String batchNo);

    /**
     * 数据缺口（spec 流向需求）：同物料、发运行批次字段缺失的活跃行 →
     * 照建流向行并标 CHECK_FLAG=1 待人工核查，不静默丢弃。
     */
    @Select("SELECT l.ID AS LINE_ID, l.ITEM_CODE, l.ITEM_NAME, l.QTY, l.BATCH_NO, "
            + "s.SHIP_NO AS DOC_NO, s.STATUS AS SHIP_STATUS, s.CUSTOMER_CODE, s.CUSTOMER_NAME, "
            + "s.WAREHOUSE_CODE, s.SIGN_AT "
            + "FROM erp_sd_shipment_line l "
            + "JOIN erp_sd_shipment s ON s.ID = l.SHIP_ID AND s.DEL_FLAG = '0' "
            + "WHERE l.ITEM_CODE = #{itemCode} AND l.DEL_FLAG = '0' "
            + "AND (l.BATCH_NO IS NULL OR l.BATCH_NO = '') "
            + "AND s.STATUS IN ('POSTED','CONFIRMED','SIGNED')")
    List<Map<String, Object>> selectShipmentGapByItem(@Param("itemCode") String itemCode);
}
