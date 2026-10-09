package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvStock;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InvStockDao extends BaseMapper<InvStock> {

    /**
     * 物理删除库存行（仅上架合并用，change add-bin-assignment design D6）：
     * 软删行仍占四维唯一键，会让后续同维未分配入库 upsert 撞键（历史已踩 @TableLogic 占键坑）；
     * 数量已并入目标行且有台账留痕，物理删除安全。
     */
    @Delete("DELETE FROM erp_inv_stock WHERE ID = #{id}")
    int physicalDelete(@Param("id") String id);
}
