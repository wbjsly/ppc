package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.CashDoc;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CashDocDao extends BaseMapper<CashDoc> {

    /** 既有单号前缀（RC/PY+yyyyMMdd 流水；迁移存量单号同前缀参与排号） */
    @Select("SELECT DOC_NO FROM erp_fin_cash_doc WHERE DOC_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 待人工核销/挂起队列（IN 未清余额 > 0 或跨期挂起） */
    @Select("SELECT * FROM erp_fin_cash_doc WHERE DIRECTION = #{direction} AND DEL_FLAG='0' "
            + "AND STATUS IN ('POSTED','RECONCILED') AND WRITEOFF_STATE IN ('NONE','PENDING') "
            + "ORDER BY PAY_DATE, DOC_NO")
    List<CashDoc> selectPendingSettle(@Param("direction") String direction);

    /** 某申请累计执行付款额（C-4.6-09 超审批金额判定基准，按申请金额口径） */
    @Select("SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_cash_doc "
            + "WHERE REQ_ID = #{reqId} AND DEL_FLAG='0' AND STATUS IN ('POSTED','RECONCILED')")
    java.math.BigDecimal selectExecutedByReq(@Param("reqId") String reqId);
}
