package com.erp.dao.mrp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mrp.CostLine;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 工单成本归集明细 DAO（change add-cost-aggregation，迁移 127）。
 */
public interface CostLineDao extends BaseMapper<CostLine> {

    /** 物理删除工单归集明细（重建式派生数据，与 summary 同步硬删防堆积） */
    @Delete("DELETE FROM erp_mrp_cost_line WHERE MO_ID = #{moId}")
    int hardDeleteByMoId(@Param("moId") String moId);
}
