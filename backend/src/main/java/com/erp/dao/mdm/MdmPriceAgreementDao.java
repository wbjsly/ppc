package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmPriceAgreement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MdmPriceAgreementDao extends BaseMapper<MdmPriceAgreement> {

    /** 同库最大协议编码（仅 PA-{4位纯数字流水}；PA-SEED-* 等带字母后缀的种子/手工编码不参与
     *  MAX 排序——字符串 MAX 会选中 'PA-SEED-*' 致 parseInt 失败回落 1 与既有 PA-0001 撞唯一键） */
    @Select("SELECT MAX(PA_CODE) FROM erp_mdm_price_agreement WHERE PA_CODE REGEXP '^PA-[0-9]+$'")
    String selectMaxCode();
}
