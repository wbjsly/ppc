package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamAssetCard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EamAssetCardDao extends BaseMapper<EamAssetCard> {

    /**
     * 当日最大流水（唯一索引兜底并发，卡号 ZC + yyyyMMdd + 4 位，共 14 字符，后缀自第 11 位起）。
     */
    @Select("SELECT MAX(CAST(SUBSTR(CARD_NO, 11) AS UNSIGNED)) FROM erp_eam_asset_card " +
            "WHERE CARD_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
