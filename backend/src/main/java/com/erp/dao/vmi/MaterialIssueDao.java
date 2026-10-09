package com.erp.dao.vmi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.vmi.MaterialIssue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MaterialIssueDao extends BaseMapper<MaterialIssue> {

    /** 领料单号流水：MI+yyyyMM+-000001 */
    @Select("SELECT MAX(CAST(SUBSTR(ISSUE_NO, 10) AS UNSIGNED)) FROM erp_inv_material_issue " +
            "WHERE ISSUE_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /** 《寄售转自有凭证》流水：VT+yyyyMM+-000001（VMI 过账生成） */
    @Select("SELECT MAX(CAST(SUBSTR(TRANSFER_DOC_NO, 10) AS UNSIGNED)) FROM erp_inv_material_issue " +
            "WHERE TRANSFER_DOC_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxTransferSeq(@Param("prefix") String prefix);
}
