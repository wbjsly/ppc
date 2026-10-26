package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.ExportDeal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ExportDealDao extends BaseMapper<ExportDeal> {

    /** 既有档案号（EXP-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT DEAL_NO FROM erp_fin_export_deal WHERE DEAL_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 分级预警：截止日在 N 天内且未纳入任何退税申报的档案（FR-4.14-7 SOP 要点 60/30 天） */
    @Select("SELECT * FROM erp_fin_export_deal WHERE DEL_FLAG = '0' "
            + "AND REFUND_APPLY_NO IS NULL AND EXPIRE_DATE IS NOT NULL "
            + "AND EXPIRE_DATE <= #{before} AND EXPIRE_DATE >= #{today}")
    List<ExportDeal> selectExpiringSoon(@Param("today") LocalDate today,
                                        @Param("before") LocalDate before);
}
