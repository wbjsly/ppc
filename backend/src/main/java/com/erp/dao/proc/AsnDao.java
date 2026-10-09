package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.Asn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AsnDao extends BaseMapper<Asn> {

    /** ASN 号流水：ASN+yyyyMMdd+000001（前缀 11 字符，seq 从第 12 位起） */
    @Select("SELECT MAX(CAST(SUBSTR(ASN_NO, 12) AS UNSIGNED)) FROM erp_proc_asn " +
            "WHERE ASN_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
