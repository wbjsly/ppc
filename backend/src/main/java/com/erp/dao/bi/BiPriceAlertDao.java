package com.erp.dao.bi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.bi.BiPriceAlert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BiPriceAlertDao extends BaseMapper<BiPriceAlert> {

    /**
     * 异动判定 upsert（tasks 3.5）：环比超阈即写；INSERT IGNORE 语义由唯一键 uk_bi_alert_key 保证——
     * 已存在的行（含已处置）不覆盖（spec：补算 MUST NOT 覆盖已处置记录）。
     * 以 SELECT ... ON DUPLICATE KEY UPDATE 仅回写数值、绝不触碰 STATUS/HANDLE_* 实现"不覆盖处置"。
     */
    @Update("INSERT INTO erp_bi_price_alert " +
            "(ID, ALERT_NO, MONTH_TAG, ITEM_CODE, CATEGORY_CODE, ALERT_TYPE, PCT, THRESHOLD_PCT, " +
            " PREV_PRICE, CURR_PRICE, SOURCE, STATUS, CREATE_BY) " +
            "SELECT UUID(), CONCAT('PA', DATE_FORMAT(NOW(), '%Y%m%d'), UPPER(SUBSTRING(UUID(), 1, 6))), c.MONTH_TAG, " +
            "  c.ITEM_CODE, MAX(c.CATEGORY_CODE), 'TREND_SPIKE', c.PCT, #{threshold}, " +
            "  c.PREV_PRICE, c.CURR_PRICE, #{source}, 'OPEN', 'bi-extract' " +
            "FROM ( " +
            "  SELECT a.MONTH_TAG, a.ITEM_CODE, a.CATEGORY_CODE, " +
            "    (a.AVG_PRICE - b.AVG_PRICE) / NULLIF(b.AVG_PRICE, 0) * 100 AS PCT, " +
            "    b.AVG_PRICE AS PREV_PRICE, a.AVG_PRICE AS CURR_PRICE " +
            "  FROM (SELECT MONTH_TAG, ITEM_CODE, MAX(CATEGORY_CODE) CATEGORY_CODE, " +
            "          SUM(PO_AMT) / NULLIF(SUM(PO_QTY),0) AS AVG_PRICE " +
            "        FROM erp_bi_cost_snapshot WHERE PO_QTY > 0 AND DEL_FLAG='0' " +
            "        GROUP BY MONTH_TAG, ITEM_CODE) a " +
            "  JOIN (SELECT MONTH_TAG, ITEM_CODE, SUM(PO_AMT) / NULLIF(SUM(PO_QTY),0) AS AVG_PRICE " +
            "        FROM erp_bi_cost_snapshot WHERE PO_QTY > 0 AND DEL_FLAG='0' " +
            "        GROUP BY MONTH_TAG, ITEM_CODE) b " +
            "    ON b.ITEM_CODE = a.ITEM_CODE " +
            "   AND b.MONTH_TAG = DATE_FORMAT(DATE_SUB(STR_TO_DATE(CONCAT(a.MONTH_TAG,'01'), '%Y%m%d'), INTERVAL 1 MONTH), '%Y%m') " +
            ") c " +
            "WHERE ABS(c.PCT) > #{threshold} AND c.MONTH_TAG = #{monthTag} " +
            "GROUP BY c.MONTH_TAG, c.ITEM_CODE, c.PCT, c.PREV_PRICE, c.CURR_PRICE " +
            "ON DUPLICATE KEY UPDATE PCT = VALUES(PCT), THRESHOLD_PCT = VALUES(THRESHOLD_PCT), " +
            "  PREV_PRICE = VALUES(PREV_PRICE), CURR_PRICE = VALUES(CURR_PRICE)")
    int detectMonth(@Param("monthTag") String monthTag,
                    @Param("threshold") java.math.BigDecimal threshold,
                    @Param("source") String source);

    @Select("SELECT * FROM erp_bi_price_alert WHERE MONTH_TAG = #{m} AND ITEM_CODE = #{item} LIMIT 1")
    BiPriceAlert selectKey(@Param("m") String monthTag, @Param("item") String item);

    @Select("SELECT * FROM erp_bi_price_alert WHERE DEL_FLAG = '0' "
            + "AND (#{monthTag} IS NULL OR MONTH_TAG = #{monthTag}) "
            + "AND (#{status} IS NULL OR STATUS = #{status}) "
            + "AND (#{itemCode} IS NULL OR ITEM_CODE = #{itemCode}) "
            + "ORDER BY CREATE_DATE DESC")
    List<BiPriceAlert> selectForManage(@Param("monthTag") String monthTag, @Param("status") String status,
                                       @Param("itemCode") String itemCode);
}
