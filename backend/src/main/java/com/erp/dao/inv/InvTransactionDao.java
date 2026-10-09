package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvTransaction;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出入库流水 DAO（spec stock-posting-engine）。
 * 只 insert + 查询（纯追加，无业务 update/delete）。
 */
public interface InvTransactionDao extends BaseMapper<InvTransaction> {

    /**
     * 当日流水计数（TXN_NO 生成：TX+yyMMdd+6位序列）。
     */
    @Select("SELECT COUNT(*) FROM erp_inv_transaction WHERE TXN_NO LIKE CONCAT(#{prefix}, '%')")
    long countByPrefix(@Param("prefix") String prefix);

    /**
     * 单据流水（作业台详情抽屉按 BIZ_DOC_TYPE+BIZ_DOC_NO 下钻）。
     */
    @Select("SELECT * FROM erp_inv_transaction WHERE BIZ_DOC_TYPE = #{bizDocType} "
            + "AND BIZ_DOC_NO = #{bizDocNo} AND DEL_FLAG = '0' ORDER BY CREATE_DATE, TXN_NO")
    List<InvTransaction> selectByBizDoc(@Param("bizDocType") String bizDocType,
                                        @Param("bizDocNo") String bizDocNo);

    /**
     * 维度流水（按时间正序，追溯/对账用）。
     */
    @Select("SELECT * FROM erp_inv_transaction WHERE ITEM_CODE = #{itemCode} "
            + "AND WAREHOUSE_CODE = #{warehouseCode} "
            + "AND (#{batchNo} IS NULL OR BATCH_NO = #{batchNo}) "
            + "AND CREATE_DATE >= #{from} AND CREATE_DATE < #{to} "
            + "AND DEL_FLAG = '0' ORDER BY CREATE_DATE, TXN_NO")
    List<InvTransaction> selectByDimension(@Param("itemCode") String itemCode,
                                           @Param("warehouseCode") String warehouseCode,
                                           @Param("batchNo") String batchNo,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to);
}
