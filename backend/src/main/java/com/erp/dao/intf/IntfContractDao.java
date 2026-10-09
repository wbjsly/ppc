package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfContract;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntfContractDao extends BaseMapper<IntfContract> {

    @Select("SELECT MAX(CAST(SUBSTR(CONTRACT_NO, 11) AS UNSIGNED)) FROM erp_intf_contract " +
            "WHERE CONTRACT_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /** 已发布且未废弃的契约（凭证签发门禁 BR-5.5-01 / 报文版本比对） */
    @Select("SELECT * FROM erp_intf_contract WHERE PARTNER_CODE = #{partnerCode} AND STATUS = 'PUBLISHED' " +
            "AND DEL_FLAG = '0' ORDER BY CREATE_DATE DESC LIMIT 1")
    IntfContract selectPublished(@Param("partnerCode") String partnerCode);
}
