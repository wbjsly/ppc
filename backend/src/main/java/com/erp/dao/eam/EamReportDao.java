package com.erp.dao.eam;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 资产报表 DAO（add-asset-report，design D2/D4/D5）：只读查询，无实体。
 * 事件流水七路 UNION ALL 统一列：EVT_DATE/EVT_TYPE/CARD_NO/ASSET_NAME/
 * CATEGORY_CODE/DEPT_NAME/COST_CENTER/SOURCE_NO/AMOUNT_DELTA（+增/−减/0）/REMARK。
 */
@Mapper
public interface EamReportDao {

    /** 七路事件流水统一列（各子查询口径见 design D2；日期/金额已 CAST 对齐） */
    String EVENT_UNION =
            "SELECT CAST(c.EFFECTIVE_DATE AS DATE) EVT_DATE, 'CAPITALIZATION' EVT_TYPE, "
            + "       '' CARD_NO, c.ASSET_NAME, c.CATEGORY_CODE, COALESCE(c.DEPT_NAME,'') DEPT_NAME, "
            + "       COALESCE(c.COST_CENTER_CODE,'') COST_CENTER, c.CAP_NO SOURCE_NO, "
            + "       c.TOTAL_AMOUNT AMOUNT_DELTA, CONCAT('转资建卡 ', COALESCE(c.CARD_COUNT,1), ' 张') REMARK "
            + "FROM erp_eam_capitalization c WHERE c.DEL_FLAG='0' AND c.STATUS='COMPLETED' "
            + "UNION ALL "
            + "SELECT CAST(o.EXECUTED_AT AS DATE), 'DISPOSAL', l.CARD_NO, l.ASSET_NAME, l.CATEGORY_CODE, "
            + "       COALESCE(l.DEPT_NAME,''), COALESCE(l.COST_CENTER_CODE,''), o.DSP_NO, "
            + "       -l.ORIG_COST, CONCAT('处置出账（', o.DISPOSAL_TYPE, '）') "
            + "FROM erp_eam_disposal_order o JOIN erp_eam_disposal_line l ON l.ORDER_ID=o.ID AND l.DEL_FLAG='0' "
            + "WHERE o.DEL_FLAG='0' AND o.APP_STATUS IN ('EXECUTED','CLOSED') AND o.EXECUTED_AT IS NOT NULL "
            + "UNION ALL "
            + "SELECT r.ACCEPT_DATE, 'REWORK_REBUILD', nc.CARD_NO, r.ASSET_NAME, r.CATEGORY_CODE, "
            + "       COALESCE(r.DEPT_NAME,''), '', r.RWF_NO, r.NEW_COST, '技改重建新卡' "
            + "FROM erp_eam_rework_order r LEFT JOIN erp_eam_asset_card nc ON nc.ID=r.NEW_CARD_ID "
            + "WHERE r.DEL_FLAG='0' AND r.REWORK_STATUS='REBUILT' AND r.ACCEPT_DATE IS NOT NULL "
            + "UNION ALL "
            + "SELECT r.ACCEPT_DATE, 'REWORK_REBUILD', r.CARD_NO, r.ASSET_NAME, r.CATEGORY_CODE, "
            + "       COALESCE(r.DEPT_NAME,''), '', r.RWF_NO, -COALESCE(oc.ASSET_COST, r.ORIG_NBV), '技改原卡归档' "
            + "FROM erp_eam_rework_order r LEFT JOIN erp_eam_asset_card oc ON oc.ID=r.CARD_ID "
            + "WHERE r.DEL_FLAG='0' AND r.REWORK_STATUS='REBUILT' AND r.ACCEPT_DATE IS NOT NULL "
            + "UNION ALL "
            + "SELECT CAST(t.UPDATE_DATE AS DATE), 'TRANSFER', t.CARD_NO, COALESCE(c.ASSET_NAME,''), "
            + "       COALESCE(c.CATEGORY_CODE,''), COALESCE(t.TO_DEPT,''), COALESCE(t.TO_CC,''), t.TRF_NO, "
            + "       0, CONCAT('调拨完成（', t.TRANSFER_TYPE, '）') "
            + "FROM erp_eam_transfer_order t LEFT JOIN erp_eam_asset_card c ON c.ID=t.CARD_ID "
            + "WHERE t.DEL_FLAG='0' AND t.STATUS='COMPLETED' "
            + "UNION ALL "
            + "SELECT CAST(d.UPDATE_DATE AS DATE), 'COUNT_ADJUST', d.CARD_NO, COALESCE(d.SNAP_NAME,''), "
            + "       COALESCE(d.SNAP_CATEGORY,''), COALESCE(d.SNAP_DEPT,''), COALESCE(d.SNAP_CC,''), d.CNT_NO, "
            + "       CASE WHEN d.DIFF_TYPE='SHORT' THEN -COALESCE(d.SNAP_COST,0) ELSE COALESCE(d.DIFF_AMOUNT,0) END, "
            + "       CONCAT('盘点', CASE WHEN d.DIFF_TYPE='SHORT' THEN '盘亏核销' ELSE '盘盈建卡' END, "
            + "              COALESCE(CONCAT('（', d.REASON, '）'), '')) "
            + "FROM erp_eam_count_diff d "
            + "WHERE d.DEL_FLAG='0' AND d.STATUS='CLOSED' AND d.DIFF_TYPE IN ('SHORT','LONG') "
            + "UNION ALL "
            + "SELECT CAST(a.UPDATE_DATE AS DATE), 'IMPAIRMENT', a.CARD_NO, COALESCE(c.ASSET_NAME,''), "
            + "       COALESCE(c.CATEGORY_CODE,''), COALESCE(c.DEPT_NAME,''), COALESCE(c.COST_CENTER_CODE,''), "
            + "       a.CHANGE_NO, 0, CONCAT('减值 ¥', COALESCE(a.IMPAIR_AMOUNT,0), '（不改原值）') "
            + "FROM erp_eam_asset_change a LEFT JOIN erp_eam_asset_card c ON c.ID=a.CARD_ID "
            + "WHERE a.DEL_FLAG='0' AND a.CHANGE_TYPE='IMPAIRMENT' AND a.STATUS IN ('APPROVED','DONE') "
            + "UNION ALL "
            + "SELECT CAST(a.UPDATE_DATE AS DATE), 'POLICY', a.CARD_NO, COALESCE(c.ASSET_NAME,''), "
            + "       COALESCE(c.CATEGORY_CODE,''), COALESCE(c.DEPT_NAME,''), COALESCE(c.COST_CENTER_CODE,''), "
            + "       a.CHANGE_NO, 0, CONCAT('折旧政策变更 ', COALESCE(a.BEFORE_LIFE_YEARS,''), '年→', "
            + "              COALESCE(a.AFTER_LIFE_YEARS,''), '年（不改原值）') "
            + "FROM erp_eam_asset_change a LEFT JOIN erp_eam_asset_card c ON c.ID=a.CARD_ID "
            + "WHERE a.DEL_FLAG='0' AND a.CHANGE_TYPE='POLICY' AND a.STATUS IN ('APPROVED','DONE')";

    /** 事件流水分页（期间/类型/分类/成本中心筛选 + 日期倒序，design D2） */
    @Select("<script>"
            + "SELECT * FROM (" + EVENT_UNION + ") u WHERE 1=1"
            + "  <if test='fromDate != null'> AND u.EVT_DATE &gt;= #{fromDate}</if>"
            + "  <if test='toDate != null'> AND u.EVT_DATE &lt;= #{toDate}</if>"
            + "  <if test='evtType != null and evtType != \"\"'> AND u.EVT_TYPE = #{evtType}</if>"
            + "  <if test='categoryCode != null and categoryCode != \"\"'> AND u.CATEGORY_CODE = #{categoryCode}</if>"
            + "  <if test='costCenter != null and costCenter != \"\"'> AND u.COST_CENTER = #{costCenter}</if>"
            + " ORDER BY u.EVT_DATE DESC, u.SOURCE_NO"
            + " LIMIT #{offset}, #{size}"
            + "</script>")
    List<Map<String, Object>> changeEvents(@Param("fromDate") String fromDate,
                                           @Param("toDate") String toDate,
                                           @Param("evtType") String evtType,
                                           @Param("categoryCode") String categoryCode,
                                           @Param("costCenter") String costCenter,
                                           @Param("offset") long offset,
                                           @Param("size") long size);

    /** 事件流水计数（导出上限判定 + 分页 total，design D6） */
    @Select("<script>"
            + "SELECT COUNT(*) FROM (" + EVENT_UNION + ") u WHERE 1=1"
            + "  <if test='fromDate != null'> AND u.EVT_DATE &gt;= #{fromDate}</if>"
            + "  <if test='toDate != null'> AND u.EVT_DATE &lt;= #{toDate}</if>"
            + "  <if test='evtType != null and evtType != \"\"'> AND u.EVT_TYPE = #{evtType}</if>"
            + "  <if test='categoryCode != null and categoryCode != \"\"'> AND u.CATEGORY_CODE = #{categoryCode}</if>"
            + "  <if test='costCenter != null and costCenter != \"\"'> AND u.COST_CENTER = #{costCenter}</if>"
            + "</script>")
    long changeEventCount(@Param("fromDate") String fromDate,
                          @Param("toDate") String toDate,
                          @Param("evtType") String evtType,
                          @Param("categoryCode") String categoryCode,
                          @Param("costCenter") String costCenter);

    /** 事件口径期间合计（按类型分组，service 汇总增/减，design D3 事件侧） */
    @Select("<script>"
            + "SELECT u.EVT_TYPE, SUM(u.AMOUNT_DELTA) TOTAL FROM (" + EVENT_UNION + ") u WHERE 1=1"
            + "  <if test='fromDate != null'> AND u.EVT_DATE &gt;= #{fromDate}</if>"
            + "  <if test='toDate != null'> AND u.EVT_DATE &lt;= #{toDate}</if>"
            + " GROUP BY u.EVT_TYPE"
            + "</script>")
    List<Map<String, Object>> changeEventTotals(@Param("fromDate") String fromDate,
                                                @Param("toDate") String toDate);

    /** 快照口径：当前在册合计（排除 DISPOSED/REPLACED——已出账/已移交）+ 期间增减重算（勾对快照侧，design D3） */
    @Select("SELECT "
            + " (SELECT COALESCE(SUM(ASSET_COST),0) FROM erp_eam_asset_card WHERE DEL_FLAG='0' "
            + "   AND STATUS NOT IN ('DISPOSED','REPLACED')) BOOK_COST, "
            + " (SELECT COALESCE(SUM(ACCUM_DEPR),0) FROM erp_eam_asset_card WHERE DEL_FLAG='0' "
            + "   AND STATUS NOT IN ('DISPOSED','REPLACED')) BOOK_ACCUM, "
            + " (SELECT COALESCE(SUM(ASSET_COST),0) FROM erp_eam_asset_card WHERE DEL_FLAG='0' "
            + "   AND STATUS <> 'REPLACED' AND EFFECTIVE_DATE BETWEEN #{fromDate} AND #{toDate}) SNAP_INC, "
            + " (SELECT COALESCE(SUM(ASSET_COST),0) FROM erp_eam_asset_card WHERE DEL_FLAG='0' "
            + "   AND STATUS='DISPOSED' AND DISPOSED_AT IS NOT NULL "
            + "   AND DATE(DISPOSED_AT) BETWEEN #{fromDate} AND #{toDate}) SNAP_DEC_DISPOSAL, "
            + " (SELECT COALESCE(SUM(ASSET_COST),0) FROM erp_eam_asset_card WHERE DEL_FLAG='0' "
            + "   AND STATUS='REPLACED' AND UPDATE_DATE BETWEEN #{fromDate} AND #{toDate}) SNAP_DEC_REPLACED")
    Map<String, Object> bookSnapshot(@Param("fromDate") String fromDate,
                                     @Param("toDate") String toDate);

    /** 折旧汇总：单期按维度分组（CATEGORY 维 JOIN 卡片取分类；COST_CENTER 维用明细列，design D4） */
    @Select("<script>"
            + "SELECT dim, SUM(AMT) AMT FROM ("
            + " SELECT "
            + " <if test='byCostCenter'> COALESCE(NULLIF(d.COST_CENTER_CODE,''),'(未分配)')</if>"
            + " <if test='!byCostCenter'> COALESCE(NULLIF(c.CATEGORY_CODE,''),'(未知)')</if>"
            + "  dim, COALESCE(d.PERIOD_DEPR,0) AMT "
            + " FROM erp_eam_depreciation_detail d "
            + " JOIN erp_eam_depreciation_run r ON r.ID=d.RUN_ID "
            + " LEFT JOIN erp_eam_asset_card c ON c.ID=d.CARD_ID "
            + " WHERE r.PERIOD_TAG=#{periodTag} AND d.DEL_FLAG='0' AND d.REVERSED='0'"
            + ") t GROUP BY dim"
            + "</script>")
    List<Map<String, Object>> depreciationSummaryBy(@Param("periodTag") String periodTag,
                                                    @Param("byCostCenter") boolean byCostCenter);

    /** 折旧明细行（报表直读，design D4） */
    @Select("SELECT d.CARD_NO, d.ASSET_NAME, COALESCE(c.CATEGORY_CODE,'') CATEGORY_CODE, "
            + " COALESCE(d.COST_CENTER_CODE,'') COST_CENTER, d.ASSET_COST, "
            + " d.BEGIN_NBV, d.PERIOD_DEPR, d.END_ACCUM_DEPR, d.END_NBV "
            + "FROM erp_eam_depreciation_detail d "
            + "JOIN erp_eam_depreciation_run r ON r.ID=d.RUN_ID "
            + "LEFT JOIN erp_eam_asset_card c ON c.ID=d.CARD_ID "
            + "WHERE r.PERIOD_TAG=#{periodTag} AND d.DEL_FLAG='0' AND d.REVERSED='0' ORDER BY d.CARD_NO")
    List<Map<String, Object>> depreciationDetails(@Param("periodTag") String periodTag);

    /** 净值台账：当前卡片口径（排除 DISPOSED/IN_REWORK/REPLACED，design D4） */
    @Select("<script>"
            + "SELECT c.CARD_NO, c.ASSET_NAME, c.CATEGORY_CODE, c.DEPT_NAME, c.COST_CENTER_CODE, "
            + " c.ASSET_COST, c.ACCUM_DEPR, COALESCE(c.IMPAIRMENT,0) IMPAIRMENT, "
            + " (c.ASSET_COST - c.ACCUM_DEPR - COALESCE(c.IMPAIRMENT,0)) NET_VALUE, "
            + " c.LIFE_YEARS, c.DEPR_STATUS, c.EFFECTIVE_DATE "
            + "FROM erp_eam_asset_card c WHERE c.DEL_FLAG='0' "
            + " AND c.STATUS NOT IN ('DISPOSED','IN_REWORK','REPLACED')"
            + " <if test='categoryCode != null and categoryCode != \"\"'> AND c.CATEGORY_CODE=#{categoryCode}</if>"
            + " <if test='costCenter != null and costCenter != \"\"'> AND c.COST_CENTER_CODE=#{costCenter}</if>"
            + " <if test='deprStatus != null and deprStatus != \"\"'> AND c.DEPR_STATUS=#{deprStatus}</if>"
            + " ORDER BY c.CARD_NO LIMIT #{offset}, #{size}"
            + "</script>")
    List<Map<String, Object>> netAssetLedger(@Param("categoryCode") String categoryCode,
                                             @Param("costCenter") String costCenter,
                                             @Param("deprStatus") String deprStatus,
                                             @Param("offset") long offset,
                                             @Param("size") long size);

    @Select("<script>"
            + "SELECT COUNT(*) FROM erp_eam_asset_card c WHERE c.DEL_FLAG='0' "
            + " AND c.STATUS NOT IN ('DISPOSED','IN_REWORK','REPLACED')"
            + " <if test='categoryCode != null and categoryCode != \"\"'> AND c.CATEGORY_CODE=#{categoryCode}</if>"
            + " <if test='costCenter != null and costCenter != \"\"'> AND c.COST_CENTER_CODE=#{costCenter}</if>"
            + " <if test='deprStatus != null and deprStatus != \"\"'> AND c.DEPR_STATUS=#{deprStatus}</if>"
            + "</script>")
    long netAssetLedgerCount(@Param("categoryCode") String categoryCode,
                             @Param("costCenter") String costCenter,
                             @Param("deprStatus") String deprStatus);

    /** 盘点报告四类差异分布（design D5） */
    @Select("SELECT DIFF_TYPE, COUNT(*) CNT, COALESCE(SUM(ABS(COALESCE(DIFF_AMOUNT,0))),0) AMT "
            + "FROM erp_eam_count_diff WHERE PLAN_ID=#{planId} AND DEL_FLAG='0' "
            + "GROUP BY DIFF_TYPE")
    List<Map<String, Object>> countDiffByType(@Param("planId") String planId);

    /** 盘点报告 TOP（维度=SNAP_CATEGORY/SNAP_DEPT） */
    @Select("<script>"
            + "SELECT COALESCE(NULLIF(<if test='byDept'>SNAP_DEPT</if>"
            + "<if test='!byDept'>SNAP_CATEGORY</if>,''),'(未分类)') DIM, "
            + " COUNT(*) CNT, COALESCE(SUM(ABS(COALESCE(DIFF_AMOUNT,0))),0) AMT "
            + "FROM erp_eam_count_diff WHERE PLAN_ID=#{planId} AND DEL_FLAG='0' "
            + "GROUP BY DIM ORDER BY AMT DESC LIMIT 10"
            + "</script>")
    List<Map<String, Object>> countDiffTop(@Param("planId") String planId,
                                           @Param("byDept") boolean byDept);

    /** 盘点报告原因聚合 */
    @Select("SELECT COALESCE(NULLIF(REASON,''),'(未填原因)') REASON, COUNT(*) CNT "
            + "FROM erp_eam_count_diff WHERE PLAN_ID=#{planId} AND DEL_FLAG='0' "
            + "GROUP BY COALESCE(NULLIF(REASON,''),'(未填原因)') ORDER BY CNT DESC")
    List<Map<String, Object>> countDiffReasons(@Param("planId") String planId);
}
