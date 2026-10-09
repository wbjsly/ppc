package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmLegalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MdmLegalEntityDao extends BaseMapper<MdmLegalEntity> {

    /** 编码查重（C-4.1-07 展示近 3 条相似项） */
    @Select("SELECT * FROM erp_mdm_legal_entity WHERE DEL_FLAG = '0' AND LE_CODE LIKE CONCAT(#{prefix}, '%') ORDER BY LE_CODE DESC LIMIT 3")
    List<MdmLegalEntity> findSimilarByCode(@Param("prefix") String prefix);

    /** 税号查重：排除自身 */
    @Select("SELECT * FROM erp_mdm_legal_entity WHERE DEL_FLAG = '0' AND USCC = #{uscc} AND ID <> #{id} LIMIT 3")
    List<MdmLegalEntity> findByUsccExcludingSelf(@Param("uscc") String uscc, @Param("id") String id);

    /** 最大编码流水，用于生成下一个 LE-XXXX */
    @Select("SELECT MAX(LE_CODE) FROM erp_mdm_legal_entity WHERE LE_CODE LIKE 'LE-%'")
    String selectMaxCode();
}
