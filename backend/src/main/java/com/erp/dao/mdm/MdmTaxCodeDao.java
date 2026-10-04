package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmTaxCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MdmTaxCodeDao extends BaseMapper<MdmTaxCode> {

    /**
     * 同序列（税码编号）记录按生效日排序（IntervalRules 判定输入）。
     * excludeId 用于编辑时排除自身。
     */
    @Select("<script>" +
            "SELECT * FROM erp_mdm_tax_code WHERE DEL_FLAG = '0' " +
            "AND TAX_CODE = #{taxCode} " +
            "<if test='excludeId != null and excludeId != \"\"'> AND ID != #{excludeId} </if>" +
            "ORDER BY EFFECTIVE_DATE" +
            "</script>")
    List<MdmTaxCode> selectSequence(@Param("taxCode") String taxCode,
                                    @Param("excludeId") String excludeId);

    /** 试算命中：目标日期落在闭区间内 */
    @Select("SELECT * FROM erp_mdm_tax_code WHERE DEL_FLAG = '0' " +
            "AND TAX_CODE = #{taxCode} " +
            "AND EFFECTIVE_DATE <= #{date} AND EXPIRE_DATE >= #{date} " +
            "ORDER BY EFFECTIVE_DATE DESC LIMIT 1")
    MdmTaxCode selectHit(@Param("taxCode") String taxCode, @Param("date") LocalDate date);

    /** 某税码是否存在任何记录（试算缺失原因区分用） */
    @Select("SELECT COUNT(1) FROM erp_mdm_tax_code WHERE DEL_FLAG = '0' AND TAX_CODE = #{taxCode}")
    int countCode(@Param("taxCode") String taxCode);

    /** 被指定政策文号引用的税码数（政策删除校验，design D4） */
    @Select("SELECT COUNT(1) FROM erp_mdm_tax_code WHERE DEL_FLAG = '0' AND POLICY_NO = #{policyNo}")
    int countByPolicy(@Param("policyNo") String policyNo);
}
