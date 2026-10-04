package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmPriceAgreement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MdmPriceAgreementDao extends BaseMapper<MdmPriceAgreement> {

    /** 同库最大协议编码，用于生成下一个 PA-{4位流水} */
    @Select("SELECT MAX(PA_CODE) FROM erp_mdm_price_agreement")
    String selectMaxCode();
}
