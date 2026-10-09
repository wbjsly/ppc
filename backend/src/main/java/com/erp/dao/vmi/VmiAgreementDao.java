package com.erp.dao.vmi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.vmi.VmiAgreement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VmiAgreementDao extends BaseMapper<VmiAgreement> {

    /** 协议编号流水：VM+yyyyMM+-000001（SUBSTR 10 = 'VMyyyyMM-' 9 字符后取序号） */
    @Select("SELECT MAX(CAST(SUBSTR(AGREE_NO, 10) AS UNSIGNED)) FROM erp_proc_vmi_agreement " +
            "WHERE AGREE_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
