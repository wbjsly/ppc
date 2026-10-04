package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.FrameworkAgreement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FrameworkAgreementDao extends BaseMapper<FrameworkAgreement> {

    /**
     * 当日最大流水。前缀 FA-YYYYMMDD- 共 12 字符，后缀自第 13 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(AGREEMENT_NO, 13) AS UNSIGNED)) FROM erp_proc_framework_agreement " +
            "WHERE AGREEMENT_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
