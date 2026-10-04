package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmProfitCenter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MdmProfitCenterDao extends BaseMapper<MdmProfitCenter> {

    /** 最大编码，用于生成下一个 PC-XXXX（跨主体全局流水） */
    @Select("SELECT MAX(PC_CODE) FROM erp_mdm_profit_center")
    String selectMaxCode();
}
