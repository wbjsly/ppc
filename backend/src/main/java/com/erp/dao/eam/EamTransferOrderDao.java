package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamTransferOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EamTransferOrderDao extends BaseMapper<EamTransferOrder> {

    /**
     * 当日最大流水（TRF + yyyyMMdd + - 共 12 字符，后缀自第 13 位起；唯一索引兜底并发）。
     */
    @Select("SELECT MAX(CAST(SUBSTR(TRF_NO, 13) AS UNSIGNED)) FROM erp_eam_transfer_order " +
            "WHERE TRF_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /**
     * BR-22 折旧归属回溯（design D3）：某会计月内完成调拨的卡片集合 → 每卡最近一张完成单的调出成本中心。
     * 一条 SQL 批量取（不逐卡查询）；同卡同月多次完成取 COMPLETED_AT 最晚一张。
     */
    @Select("<script>" +
            "SELECT t.* FROM erp_eam_transfer_order t " +
            "JOIN (SELECT CARD_ID, MAX(COMPLETED_AT) AS MAX_AT FROM erp_eam_transfer_order " +
            "      WHERE STATUS = 'COMPLETED' AND DEL_FLAG = '0' " +
            "      AND DATE_FORMAT(COMPLETED_AT, '%Y%m') = #{periodTag} " +
            "      AND CARD_ID IN " +
            "      <foreach collection='cardIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "      GROUP BY CARD_ID) m " +
            "ON t.CARD_ID = m.CARD_ID AND t.COMPLETED_AT = m.MAX_AT " +
            "WHERE t.STATUS = 'COMPLETED' AND t.DEL_FLAG = '0' " +
            "AND DATE_FORMAT(t.COMPLETED_AT, '%Y%m') = #{periodTag}" +
            "</script>")
    List<EamTransferOrder> selectCompletedInPeriod(@Param("periodTag") String periodTag,
                                                   @Param("cardIds") List<String> cardIds);

    /**
     * C-4.13-10 月结卡控：CROSS_LE 已完成但内部交易单据/双凭证不齐的调拨单。
     */
    @Select("SELECT * FROM erp_eam_transfer_order WHERE TRANSFER_TYPE = 'CROSS_LE' " +
            "AND STATUS = 'COMPLETED' AND DEL_FLAG = '0' " +
            "AND (INTERCO_NO IS NULL OR INTERCO_NO = '' " +
            "     OR OUT_VOUCHER_ID IS NULL OR OUT_VOUCHER_ID = '' " +
            "     OR IN_VOUCHER_ID IS NULL OR IN_VOUCHER_ID = '')")
    List<EamTransferOrder> selectUnsettledInterco();

    /** 同资产进行中调拨单（重复发起阻断；excludeId 排除本单——submit 重跑前置时用） */
    @Select("SELECT * FROM erp_eam_transfer_order WHERE CARD_ID = #{cardId} AND DEL_FLAG = '0' " +
            "AND STATUS IN ('DRAFT','PENDING_APPROVE','APPROVED','HANDOVERED') " +
            "AND ID <> #{excludeId} LIMIT 1")
    EamTransferOrder selectInFlightByCard(@Param("cardId") String cardId,
                                          @Param("excludeId") String excludeId);

    /** 当日最大内部交易流水（ICR + yyyyMMdd + - 共 12 字符） */
    @Select("SELECT MAX(CAST(SUBSTR(INTERCO_NO, 13) AS UNSIGNED)) FROM erp_eam_transfer_order " +
            "WHERE INTERCO_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxIntercoSeq(@Param("prefix") String prefix);
}
