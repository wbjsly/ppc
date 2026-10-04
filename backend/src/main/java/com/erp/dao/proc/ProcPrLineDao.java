package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.ProcPrLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ProcPrLineDao extends BaseMapper<ProcPrLine> {

    /**
     * 按物料品类汇总本年 PR 预估金额 Σ(qty × estUnitPrice)（BR-4.2-44 门槛判级口径，
     * 与 ProcApprovalService 的判级金额同源；偏差 D7：当前无 PO 表，以 PR 预估额近似采购额）。
     *
     * @param itemCodes 本次 PR 涉及的物料编码集合
     * @return 每个品类一行：{categoryCode, amount}
     */
    @Select("<script>"
            + "SELECT i.CATEGORY_CODE AS categoryCode, "
            + "       IFNULL(SUM(l.QTY * IFNULL(l.EST_UNIT_PRICE, 0)), 0) AS amount "
            + "FROM erp_proc_pr_line l "
            + "JOIN erp_proc_requisition r ON r.ID = l.PR_ID AND r.DEL_FLAG = '0' "
            + "JOIN erp_mdm_item i ON i.ITEM_CODE = l.ITEM_CODE AND i.DEL_FLAG = '0' "
            + "WHERE YEAR(r.CREATE_DATE) = YEAR(NOW()) "
            + "AND i.CATEGORY_CODE IS NOT NULL "
            + "AND i.ITEM_CODE IN "
            + "<foreach item='c' collection='itemCodes' open='(' separator=',' close=')'>#{c}</foreach> "
            + "GROUP BY i.CATEGORY_CODE"
            + "</script>")
    List<Map<String, Object>> sumYearPrAmountByItemCodes(@Param("itemCodes") List<String> itemCodes);
}
