package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.Reservation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批次预留 DAO（spec sales-atp-reservation）。
 */
public interface ReservationDao extends BaseMapper<Reservation> {

    /**
     * ATP 的 Reserved 因子：按 SKU + 仓库汇总 ACTIVE 预留量（BR-4.3-19 公式）。
     */
    @Select("SELECT COALESCE(SUM(QTY), 0) FROM erp_sd_reservation "
            + "WHERE ITEM_CODE = #{itemCode} AND WAREHOUSE_CODE = #{warehouseCode} "
            + "AND STATUS = 'ACTIVE' AND DEL_FLAG = '0'")
    BigDecimal sumActive(@Param("itemCode") String itemCode,
                         @Param("warehouseCode") String warehouseCode);

    /**
     * 同批次已存在的 ACTIVE 预留量（可锁量 = 库存可用 − 本值，8.6 先到先得防超卖）。
     */
    @Select("SELECT COALESCE(SUM(QTY), 0) FROM erp_sd_reservation "
            + "WHERE WAREHOUSE_CODE = #{warehouseCode} AND ITEM_CODE = #{itemCode} "
            + "AND BATCH_NO = #{batchNo} AND STATUS = 'ACTIVE' AND DEL_FLAG = '0'")
    BigDecimal sumActiveOnBatch(@Param("warehouseCode") String warehouseCode,
                                @Param("itemCode") String itemCode,
                                @Param("batchNo") String batchNo);

    /**
     * SO 的 ACTIVE 预留行（关闭/取消释放用）。
     */
    @Select("SELECT * FROM erp_sd_reservation WHERE SO_ID = #{soId} "
            + "AND STATUS = 'ACTIVE' AND DEL_FLAG = '0'")
    List<Reservation> selectActiveBySo(@Param("soId") String soId);

    /**
     * 行的 ACTIVE 预留量（变更减量、行取消释放用）。
     */
    @Select("SELECT COALESCE(SUM(QTY), 0) FROM erp_sd_reservation "
            + "WHERE LINE_ID = #{lineId} AND STATUS = 'ACTIVE' AND DEL_FLAG = '0'")
    BigDecimal sumActiveByLine(@Param("lineId") String lineId);
}
