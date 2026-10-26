package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamCapitalization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EamCapitalizationDao extends BaseMapper<EamCapitalization> {

    /**
     * 当日最大流水（唯一索引兜底并发）。
     * 前缀 = ZT + yyyyMMdd + '-' 共 11 字符，后缀自第 12 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(CAP_NO, 12) AS UNSIGNED)) FROM erp_eam_capitalization " +
            "WHERE CAP_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /** 来源单据是否已有转资单（重复转资阻断，FR-4.13-1-2）：排除已驳回可重提 */
    @Select("SELECT COUNT(*) FROM erp_eam_capitalization WHERE SOURCE_ID = #{sourceId} " +
            "AND STATUS IN ('DRAFT', 'PENDING_APPROVE', 'COMPLETED') AND DEL_FLAG = '0'")
    int countBySource(@Param("sourceId") String sourceId);
}
