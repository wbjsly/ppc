package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamDepreciationRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EamDepreciationRunDao extends BaseMapper<EamDepreciationRun> {

    /**
     * 按会计月取有效计提单（幂等锚：PERIOD_TAG 唯一，冲回后放行重提）。
     * 只取未冲回的行：冲回后 REVERSED_FLAG=1，唯一键 (PERIOD_TAG, REVERSED_FLAG) 不再冲突。
     */
    @Select("SELECT * FROM erp_eam_depreciation_run WHERE PERIOD_TAG = #{periodTag} " +
            "AND REVERSED_FLAG = '0' AND DEL_FLAG = '0' LIMIT 1")
    EamDepreciationRun selectByPeriod(@Param("periodTag") String periodTag);

    /**
     * 当月最大流水（RUN_NO = JZ + yyyyMM + '-' = 9 字符前缀，流水自第 10 位起；唯一索引兜底并发）。
     */
    @Select("SELECT MAX(CAST(SUBSTR(RUN_NO, 10) AS UNSIGNED)) FROM erp_eam_depreciation_run " +
            "WHERE RUN_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
