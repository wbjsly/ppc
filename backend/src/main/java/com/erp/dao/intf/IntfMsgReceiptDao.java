package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfMsgReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 处理回执留存 DAO。回执快照不可改删（spec edi-message-processing 回执留存）：
 * 仅暴露 INSERT 与只读查询，不提供 update/delete 调用路径。
 */
@Mapper
public interface IntfMsgReceiptDao extends BaseMapper<IntfMsgReceipt> {

    /** 某报文回执历史（生成时间倒序，含重复跳过回执） */
    @Select("SELECT * FROM erp_intf_msg_receipt " +
            "WHERE MSG_ID = #{msgId} AND DEL_FLAG = '0' " +
            "ORDER BY RECEIPT_AT DESC, ID DESC")
    List<IntfMsgReceipt> selectByMsgId(@Param("msgId") String msgId);
}
