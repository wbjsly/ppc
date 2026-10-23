package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamCountLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EamCountLineDao extends BaseMapper<EamCountLine> {

    /** 未盘行（覆盖率分子补集，BR-26 结案列示）：实盘状态为空或 UNKNOWN 待确认 */
    @Select("SELECT * FROM erp_eam_count_line WHERE PLAN_ID = #{planId} AND DEL_FLAG = '0' " +
            "AND (ACTUAL_STATUS IS NULL OR ACTUAL_STATUS = '') " +
            "ORDER BY CARD_NO LIMIT #{limit}")
    List<EamCountLine> selectUnCounted(@Param("planId") String planId, @Param("limit") int limit);

    /** 未盘行数（覆盖率校验） */
    @Select("SELECT COUNT(*) FROM erp_eam_count_line WHERE PLAN_ID = #{planId} AND DEL_FLAG = '0' " +
            "AND (ACTUAL_STATUS IS NULL OR ACTUAL_STATUS = '')")
    int countUnCounted(@Param("planId") String planId);

    /** 快照内覆盖的分类数（抽样全类别校验 BR-26） */
    @Select("SELECT COUNT(DISTINCT SNAP_CATEGORY) FROM erp_eam_count_line " +
            "WHERE PLAN_ID = #{planId} AND DEL_FLAG = '0' AND SNAP_CATEGORY IS NOT NULL")
    int countDistinctCategory(@Param("planId") String planId);

    /** 全量分类数（抽样全类别校验 BR-26 分母） */
    @Select("SELECT COUNT(*) FROM erp_eam_asset_category WHERE DEL_FLAG = '0'")
    int countAllCategory();

    /** 按快照分类统计快照行数（抽样算法类内配额） */
    @Select("SELECT SNAP_CATEGORY AS category, COUNT(*) AS cnt FROM erp_eam_count_line " +
            "WHERE PLAN_ID = #{planId} AND DEL_FLAG = '0' AND SNAP_CATEGORY IS NOT NULL " +
            "GROUP BY SNAP_CATEGORY")
    List<java.util.Map<String, Object>> sumByCategory(@Param("planId") String planId);

    /** 基准日后新增卡片（BR-29 单独列示） */
    @Select("SELECT ID, CARD_NO, ASSET_NAME, EFFECTIVE_DATE FROM erp_eam_asset_card " +
            "WHERE DEL_FLAG = '0' AND EFFECTIVE_DATE > #{baseDate} " +
            "ORDER BY EFFECTIVE_DATE DESC LIMIT #{limit}")
    List<java.util.Map<String, Object>> selectCardsAfterBaseDate(@Param("baseDate") java.time.LocalDate baseDate,
                                                                 @Param("limit") int limit);
}
