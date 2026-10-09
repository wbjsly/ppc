package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.SdQuote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SdQuoteDao extends BaseMapper<SdQuote> {

    /** 既有草稿号流水（QD-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT DRAFT_NO FROM erp_sd_quote WHERE DRAFT_NO LIKE #{prefix}")
    List<String> selectDraftNosByPrefix(@Param("prefix") String prefix);

    /** 既有正式报价号流水（QT-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT QUOTE_NO FROM erp_sd_quote WHERE QUOTE_NO LIKE #{prefix}")
    List<String> selectQuoteNosByPrefix(@Param("prefix") String prefix);

    /** 同商机的最新已发布版本（新版本校验：旧版本不可转 SO） */
    @Select("SELECT * FROM erp_sd_quote WHERE OPP_ID = #{oppId} AND DEL_FLAG='0' "
            + "AND STATUS IN ('PUBLISHED','CONVERTED') ORDER BY VERSION_NO DESC LIMIT 1")
    SdQuote selectLatestPublishedByOpp(@Param("oppId") String oppId);
}
