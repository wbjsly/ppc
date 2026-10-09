package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvPutaway;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface InvPutawayDao extends BaseMapper<InvPutaway> {

    /** 某来源单行的已确认分配合计（GR 过账强前置校验用） */
    @Select("SELECT COALESCE(SUM(QTY), 0) FROM erp_inv_putaway "
            + "WHERE SOURCE_DOC_NO = #{docNo} AND STATUS = 'CONFIRMED' AND DEL_FLAG = '0' "
            + "AND SOURCE_LINE_NO = #{lineNo}")
    BigDecimal sumConfirmedQty(@Param("docNo") String docNo, @Param("lineNo") Integer lineNo);

    /** 未分配库存行候选（Tab B 数据源，按创建时间倒序分页） */
    @Select("<script>"
            + "SELECT * FROM erp_inv_stock WHERE DEL_FLAG = '0' AND BIN_CODE = '' "
            + "<if test='keyword != null and keyword != \"\"'>"
            + "AND (ITEM_CODE LIKE CONCAT('%', #{keyword}, '%') "
            + "OR BATCH_NO LIKE CONCAT('%', #{keyword}, '%'))"
            + "</if>"
            + "ORDER BY CREATE_DATE DESC LIMIT #{offset}, #{limit}"
            + "</script>")
    List<com.erp.entity.inv.InvStock> pageUnassigned(@Param("keyword") String keyword,
                                                     @Param("offset") long offset,
                                                     @Param("limit") long limit);

    @Select("<script>"
            + "SELECT COUNT(1) FROM erp_inv_stock WHERE DEL_FLAG = '0' AND BIN_CODE = '' "
            + "<if test='keyword != null and keyword != \"\"'>"
            + "AND (ITEM_CODE LIKE CONCAT('%', #{keyword}, '%') "
            + "OR BATCH_NO LIKE CONCAT('%', #{keyword}, '%'))"
            + "</if>"
            + "</script>")
    long countUnassigned(@Param("keyword") String keyword);
}
