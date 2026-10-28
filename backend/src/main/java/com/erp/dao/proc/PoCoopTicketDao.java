package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.PoCoopTicket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * PO 协同异常工单 DAO。解锁留痕不可改删（spec：DONE 后 UNLOCK_* 字段不再变更），
 * 服务层仅 resolve 一次写入。
 */
@Mapper
public interface PoCoopTicketDao extends BaseMapper<PoCoopTicket> {

    /** 某供应商当前 OPEN 工单（锁定生成前查重 + unlocks 收紧判定用） */
    @Select("SELECT * FROM erp_po_coop_ticket " +
            "WHERE SUPPLIER_ID = #{supplierId} AND STATUS = 'OPEN' AND DEL_FLAG = '0' " +
            "ORDER BY LOCK_AT DESC LIMIT 1")
    PoCoopTicket selectOpenBySupplier(@Param("supplierId") String supplierId);
}
