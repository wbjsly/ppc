package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvWarehouse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvWarehouseDao extends BaseMapper<InvWarehouse> {

    @Select("SELECT * FROM erp_inv_warehouse WHERE DEL_FLAG = '0' AND STATUS = '1' ORDER BY WH_CODE")
    List<InvWarehouse> selectEnabled();

    /** 既有流水编码（WH-NNNN），用于生成下一个编码 */
    @Select("SELECT WH_CODE FROM erp_inv_warehouse WHERE DEL_FLAG = '0' AND WH_CODE LIKE 'WH-%'")
    List<String> selectCodedWhs(@Param("prefix") String prefix);
}
