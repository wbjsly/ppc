package com.erp.dao.qms;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.qms.Ncr;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NcrDao extends BaseMapper<Ncr> {

    /**
     * 当月最大流水（忽略软删空洞，唯一索引兜底并发）。
     * 前缀 = NCR + yyyyMMdd + '-' 共 12 字符，后缀自第 13 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(NCR_NO, 13) AS UNSIGNED)) FROM erp_qms_ncr " +
            "WHERE NCR_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /** 关联退货单数（erp_proc_return 由 2.6.1 组 8 服务化，此处表级直查） */
    @Select("SELECT COUNT(*) FROM erp_proc_return WHERE NCR_ID = #{ncrId} AND DEL_FLAG = '0'")
    Long countReturnByNcr(@Param("ncrId") String ncrId);

    /** 关联退货单号列表 */
    @Select("SELECT RETURN_NO FROM erp_proc_return WHERE NCR_ID = #{ncrId} AND DEL_FLAG = '0' " +
            "ORDER BY CREATE_DATE DESC")
    java.util.List<String> selectReturnNosByNcr(@Param("ncrId") String ncrId);
}
