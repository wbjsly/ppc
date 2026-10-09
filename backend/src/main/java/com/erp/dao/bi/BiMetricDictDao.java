package com.erp.dao.bi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.bi.BiMetricDict;
import com.erp.entity.bi.BiMetricDictHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BiMetricDictDao extends BaseMapper<BiMetricDict> {

    /** 当前生效版本（IS_CURRENT=1） */
    @Select("SELECT * FROM erp_bi_metric_dict WHERE METRIC_KEY = #{metricKey} " +
            "AND IS_CURRENT = 1 AND DEL_FLAG = '0' LIMIT 1")
    BiMetricDict selectCurrent(@Param("metricKey") String metricKey);

    /** 全部版本（历史回溯，版本降序） */
    @Select("SELECT * FROM erp_bi_metric_dict WHERE METRIC_KEY = #{metricKey} " +
            "AND DEL_FLAG = '0' ORDER BY VERSION DESC")
    List<BiMetricDictHistory> selectHistory(@Param("metricKey") String metricKey);

    /** 同键最大版本号 */
    @Select("SELECT MAX(VERSION) FROM erp_bi_metric_dict WHERE METRIC_KEY = #{metricKey} AND DEL_FLAG = '0'")
    Integer selectMaxVersion(@Param("metricKey") String metricKey);

    /** 指标分页（仅当前版本） */
    // 使用 BaseMapper + Wrapper 即可（IS_CURRENT=1 过滤在服务层）
}
