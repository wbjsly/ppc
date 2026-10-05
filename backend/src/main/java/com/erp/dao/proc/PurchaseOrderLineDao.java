package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.PurchaseOrderLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface PurchaseOrderLineDao extends BaseMapper<PurchaseOrderLine> {

    /**
     * 该物料最近 3 次已下达（APPROVED）PO 的单价（价控第 2 级历史基准，BR-4.2-16）。
     * 不足 3 次按实际返回，0 次由服务层按 NO_HISTORY 放行（偏差 D2）。
     */
    @Select("SELECT l.UNIT_PRICE FROM erp_proc_po_line l " +
            "JOIN erp_proc_po p ON p.ID = l.PO_ID AND p.DEL_FLAG = '0' AND p.STATUS = 'APPROVED' " +
            "WHERE l.ITEM_CODE = #{itemCode} AND l.PO_ID <> #{excludePoId} " +
            "ORDER BY l.CREATE_DATE DESC LIMIT 3")
    List<BigDecimal> historyPrices(@Param("itemCode") String itemCode,
                                   @Param("excludePoId") String excludePoId);
}
