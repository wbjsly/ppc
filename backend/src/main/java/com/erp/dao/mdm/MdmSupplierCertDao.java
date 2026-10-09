package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmSupplierCert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MdmSupplierCertDao extends BaseMapper<MdmSupplierCert> {

    /** 供应商全部有效证照中最早到期日（无证照返回 null） */
    @Select("SELECT MIN(EXPIRE_DATE) FROM erp_mdm_supplier_cert " +
            "WHERE DEL_FLAG = '0' AND SUPPLIER_ID = #{supplierId}")
    LocalDate selectEarliestExpire(@Param("supplierId") String supplierId);

    /** 是否存在未来有效证照（核验解除前置） */
    @Select("SELECT COUNT(1) FROM erp_mdm_supplier_cert " +
            "WHERE DEL_FLAG = '0' AND SUPPLIER_ID = #{supplierId} AND EXPIRE_DATE > #{today}")
    int countValidCerts(@Param("supplierId") String supplierId, @Param("today") LocalDate today);

    /** 供应商过期证照清单（impact 展示） */
    @Select("SELECT * FROM erp_mdm_supplier_cert " +
            "WHERE DEL_FLAG = '0' AND SUPPLIER_ID = #{supplierId} AND EXPIRE_DATE < CURDATE() LIMIT 10")
    List<MdmSupplierCert> findExpired(@Param("supplierId") String supplierId);
}
