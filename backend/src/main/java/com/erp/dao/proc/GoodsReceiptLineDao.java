package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.erp.entity.proc.GoodsReceiptLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface GoodsReceiptLineDao extends BaseMapper<GoodsReceiptLine> {

    /**
     * 待分配队列（4.4.5 Tab A，spec bin-assignment）：
     * CREATED 收货单 × 待过账行（PENDING、核销量>0）× 检验放行（RELEASED/SKIPPED/CONCESSION，
     * 与 receipt-posting ① 前置校验同口径）；GR 无仓库维度，统一落 DEFAULT_WH（WH-MAIN）。
     */
    @Select("<script>"
            + "SELECT gr.ID AS GR_ID, gr.GR_NO AS GR_NO, gr.BATCH_NO AS BATCH_NO, "
            + "gr.PO_NO AS PO_NO, gr.SOURCE_TYPE AS SOURCE_TYPE, "
            + "ln.ID AS LINE_ID, ln.LINE_NO AS LINE_NO, ln.ITEM_CODE AS ITEM_CODE, "
            + "ln.ITEM_NAME AS ITEM_NAME, ln.WITHIN_TOLERANCE_QTY AS QTY, ln.QC_STATUS AS QC_STATUS "
            + "FROM erp_proc_gr gr "
            + "JOIN erp_proc_gr_line ln ON ln.GR_ID = gr.ID "
            + "WHERE gr.STATUS = 'CREATED' AND ln.STATUS = 'PENDING' "
            + "AND ln.WITHIN_TOLERANCE_QTY &gt; 0 "
            + "AND ln.QC_STATUS IN ('RELEASED', 'SKIPPED', 'CONCESSION') "
            + "<if test='keyword != null and keyword != \"\"'>"
            + "AND (gr.GR_NO LIKE CONCAT('%', #{keyword}, '%') "
            + "OR ln.ITEM_CODE LIKE CONCAT('%', #{keyword}, '%') "
            + "OR gr.BATCH_NO LIKE CONCAT('%', #{keyword}, '%') "
            + "OR gr.PO_NO LIKE CONCAT('%', #{keyword}, '%'))"
            + "</if>"
            + "ORDER BY gr.CREATE_DATE DESC, ln.LINE_NO ASC"
            + "</script>")
    IPage<Map<String, Object>> selectAssignPending(IPage<?> page, @Param("keyword") String keyword);
}
