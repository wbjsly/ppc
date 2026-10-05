package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.ProcBudget;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface ProcBudgetDao extends BaseMapper<ProcBudget> {

    /**
     * 当年按品类的 PO 累计（口径：仅 APPROVED 已批准，含已下达；不含草稿/驳回/已关闭）。
     * 品类经 PO 行物料反查 erp_mdm_item.CATEGORY_CODE（spec purchase-budget「本年累计口径」）。
     */
    @Select("SELECT i.CATEGORY_CODE AS categoryCode, IFNULL(SUM(l.AMOUNT),0) AS usedAmt " +
            "FROM erp_proc_po_line l " +
            "JOIN erp_proc_po p ON p.ID = l.PO_ID AND p.DEL_FLAG = '0' AND p.STATUS = 'APPROVED' " +
            "JOIN erp_mdm_item i ON i.ITEM_CODE = l.ITEM_CODE " +
            "WHERE YEAR(p.CREATE_DATE) = #{year} " +
            "GROUP BY i.CATEGORY_CODE")
    List<Map<String, Object>> usageByCategory(@Param("year") int year);

    /** 单品类当年累计（价控第 3 级消费，BR-4.2-17） */
    @Select("SELECT IFNULL(SUM(l.AMOUNT),0) " +
            "FROM erp_proc_po_line l " +
            "JOIN erp_proc_po p ON p.ID = l.PO_ID AND p.DEL_FLAG = '0' AND p.STATUS = 'APPROVED' " +
            "JOIN erp_mdm_item i ON i.ITEM_CODE = l.ITEM_CODE " +
            "WHERE YEAR(p.CREATE_DATE) = #{year} AND i.CATEGORY_CODE = #{categoryCode}")
    BigDecimal usedForCategory(@Param("year") int year, @Param("categoryCode") String categoryCode);
}
