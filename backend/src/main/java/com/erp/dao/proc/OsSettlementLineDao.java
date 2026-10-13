package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.OsSettlementLine;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OsSettlementLineDao extends BaseMapper<OsSettlementLine> {

    /**
     * 物理删除对账单行（spec「驳回/删除释放收货行引用」）。
     *
     * 必须物理删而非 @TableLogic 逻辑删：GR_LINE_ID 上有唯一键
     * UK_PROC_OSRL_GR_LINE，逻辑删只置 DEL_FLAG='1' 不释放唯一键占位，
     * 删除草稿后同收货行无法重新汇总（INSERT 撞唯一键）。
     */
    @Delete("DELETE FROM erp_proc_os_settlement_line WHERE OSR_ID = #{osrId}")
    int physicallyDeleteByOsrId(@Param("osrId") String osrId);
}
