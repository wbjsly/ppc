package com.erp.dao.ops;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.ops.MdmOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MdmOutboxDao extends BaseMapper<MdmOutboxEvent> {

    /**
     * 尚未建投递行的接口类事件（PROC.* / VMI.*），change add-interface-integration design D3。
     * 非接口类事件（MDM.CUSTOMER.* 等）不走接口推送通道，保持 PENDING 桩口径。
     */
    @Select("SELECT * FROM erp_ops_outbox WHERE STATUS = 'PENDING' " +
            "AND (EVENT_TYPE LIKE 'PROC.%' OR EVENT_TYPE LIKE 'VMI.%') " +
            "AND NOT EXISTS (SELECT 1 FROM erp_intf_delivery d WHERE d.EVENT_ID = erp_ops_outbox.EVENT_ID " +
            "AND d.DEL_FLAG = '0') ORDER BY OCCURRED_AT ASC LIMIT #{limit}")
    List<MdmOutboxEvent> selectUndeliveredInterface(@Param("limit") int limit);
}
