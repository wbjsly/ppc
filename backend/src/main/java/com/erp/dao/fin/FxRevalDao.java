package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FxReval;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FxRevalDao extends BaseMapper<FxReval> {

    /** 期间已有重估记录数（同期重复生成阻断用） */
    @Select("SELECT COUNT(*) FROM erp_fin_fx_reval WHERE PERIOD = #{period} AND DEL_FLAG = '0'")
    long selectCountByPeriod(@Param("period") String period);

    /** 期间最新批次号后四位（REVAL-YYYYMM-NNNN 推号；无记录返回 0） */
    @Select("SELECT MAX(CAST(SUBSTRING(REVAL_NO, 14) AS UNSIGNED)) "
            + "FROM erp_fin_fx_reval WHERE PERIOD = #{period} AND DEL_FLAG = '0'")
    Integer selectMaxSeq(@Param("period") String period);

    /** 某期间某项目已有重估记录（同期重复生成阻断用） */
    @Select("SELECT COUNT(*) FROM erp_fin_fx_reval WHERE PERIOD = #{period} "
            + "AND ITEM_TYPE = #{itemType} AND ITEM_ID = #{itemId} AND DEL_FLAG = '0'")
    long selectCountByItem(@Param("period") String period, @Param("itemType") String itemType,
                           @Param("itemId") String itemId);

    /** 某期间全部明细（下钻用） */
    @Select("SELECT * FROM erp_fin_fx_reval WHERE PERIOD = #{period} AND DEL_FLAG = '0' "
            + "ORDER BY ITEM_TYPE, CURRENCY, ITEM_NO")
    List<FxReval> selectByPeriod(@Param("period") String period);
}
