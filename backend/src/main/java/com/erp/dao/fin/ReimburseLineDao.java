package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.ReimburseLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 报销行 DAO（C-4.6-13 发票重复校验查询在此）。
 */
@Mapper
public interface ReimburseLineDao extends BaseMapper<ReimburseLine> {

    /**
     * 历史报销行中同发票号的占用（C-4.6-13 L1 阻断，spec 异常表字面「发票号码已存在」）：
     * 返回原报销单号（提示需展示），无占用返回 NULL。销方仅作展示辅助，不参与判定。
     */
    @Select("SELECT l.EXP_NO FROM erp_fin_reimburse_line l "
            + "WHERE l.DEL_FLAG = '0' AND l.INVOICE_NO = #{invoiceNo} "
            + "AND l.ID <> COALESCE(#{excludeLineId}, '') LIMIT 1")
    String selectOccupiedByInvoiceNo(@Param("invoiceNo") String invoiceNo,
                                     @Param("excludeLineId") String excludeLineId);
}
