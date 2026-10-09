package com.erp.dao.bi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.bi.BiExtractTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface BiExtractTaskDao extends BaseMapper<BiExtractTask> {

    @Select("SELECT * FROM erp_bi_extract_task WHERE BATCH_DATE = #{d} AND DEL_FLAG = '0' ORDER BY SEQ")
    List<BiExtractTask> selectBatch(@Param("d") LocalDate d);

    @Select("SELECT * FROM erp_bi_extract_task WHERE BATCH_DATE = #{d} AND TASK_TYPE = #{type} AND DEL_FLAG = '0' LIMIT 1")
    BiExtractTask selectBatchType(@Param("d") LocalDate d, @Param("type") String type);

    /** 最近一个 COST_SNAPSHOT READY 批次（快照查询的 READY 前置） */
    @Select("SELECT * FROM erp_bi_extract_task WHERE TASK_TYPE = 'COST_SNAPSHOT' AND STATUS = 'READY' " +
            "AND DEL_FLAG = '0' ORDER BY BATCH_DATE DESC LIMIT 1")
    BiExtractTask selectLatestReadySnapshot();

    /** 前一日同类型批次行数（波动对照） */
    @Select("SELECT ROW_COUNT FROM erp_bi_extract_task WHERE BATCH_DATE = #{d} AND TASK_TYPE = #{type} " +
            "AND STATUS = 'READY' AND DEL_FLAG = '0' LIMIT 1")
    Long selectPrevRowCount(@Param("d") LocalDate d, @Param("type") String type);

    /** 源表计数（波动对照口径：近 1 个自然月） */
    @Select("SELECT COUNT(*) FROM erp_proc_po_line l JOIN erp_proc_po p ON p.ID = l.PO_ID " +
            "WHERE p.DEL_FLAG='0' AND p.CREATE_DATE >= #{a} AND p.CREATE_DATE < #{b}")
    Long countPo(@Param("a") String a, @Param("b") String b);

    @Select("SELECT COUNT(*) FROM erp_proc_gr WHERE DEL_FLAG='0' " +
            "AND CREATE_DATE >= #{a} AND CREATE_DATE < #{b}")
    Long countGr(@Param("a") String a, @Param("b") String b);

    @Select("SELECT COUNT(*) FROM erp_fin_ap_invoice_line il " +
            "JOIN erp_fin_ap_invoice i ON i.ID = il.INVOICE_ID " +
            "WHERE i.DEL_FLAG='0' AND il.DEL_FLAG='0' AND i.INVOICE_DATE >= #{a} AND i.INVOICE_DATE < #{b}")
    Long countAp(@Param("a") String a, @Param("b") String b);
}
