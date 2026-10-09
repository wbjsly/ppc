package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmSupplier;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MdmSupplierDao extends BaseMapper<MdmSupplier> {

    /** 同库最大供应商编码，用于生成下一个 SUP-{4位流水} */
    /** 同库最大供应商编码（仅 SUP-{4位纯数字流水}；SUPTEST001/SUPVMI001 等无连字符的
     *  种子码在字符串 MAX 中排最后，会使 startsWith("SUP-") 判定失败回落序号 1 → 撞唯一键） */
    @Select("SELECT MAX(SUPPLIER_CODE) FROM erp_mdm_supplier WHERE SUPPLIER_CODE REGEXP '^SUP-[0-9]+$'")
    String selectMaxCode();

    /** 税号占用检查：返回占用方编码（非自身），C-4.1-07 同款 */
    @Select("SELECT SUPPLIER_CODE FROM erp_mdm_supplier " +
            "WHERE DEL_FLAG = '0' AND TAX_NO = #{taxNo} AND ID != #{selfId} LIMIT 1")
    String findTaxNoHolder(@Param("taxNo") String taxNo, @Param("selfId") String selfId);

    /**
     * 无任何有效证照（EXPIRE_DATE > 今天）的供应商——懒巡检候选。
     * 语义：全部证照过期（或无证照）→ 受限；仍持有效证照则不受限（与核验解除前置自洽，防解除被打回死循环）。
     */
    @Select("SELECT s.* FROM erp_mdm_supplier s " +
            "WHERE s.DEL_FLAG = '0' AND s.STATUS IN ('QUALIFIED','FROZEN','DISABLED') " +
            "AND EXISTS (SELECT 1 FROM erp_mdm_supplier_cert c " +
            "WHERE c.SUPPLIER_ID = s.ID AND c.DEL_FLAG = '0') " +
            "AND NOT EXISTS (SELECT 1 FROM erp_mdm_supplier_cert c " +
            "WHERE c.SUPPLIER_ID = s.ID AND c.DEL_FLAG = '0' AND c.EXPIRE_DATE > CURDATE()) " +
            "LIMIT 200")
    java.util.List<MdmSupplier> findCertExpiredCandidates();
}
