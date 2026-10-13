package com.erp.dao.mrp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mrp.CostSummary;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 工单成本归集汇总 DAO（change add-cost-aggregation，迁移 127）。
 */
public interface CostSummaryDao extends BaseMapper<CostSummary> {

    /**
     * 物理删除工单归集汇总（重建式派生数据）：UK(MO_ID) 会被逻辑删行占位，
     * 重建必须硬删（5.8 TRIAL_COST entry_no 同款教训）。
     */
    @Delete("DELETE FROM erp_mrp_cost_summary WHERE MO_ID = #{moId}")
    int hardDeleteByMoId(@Param("moId") String moId);
}
