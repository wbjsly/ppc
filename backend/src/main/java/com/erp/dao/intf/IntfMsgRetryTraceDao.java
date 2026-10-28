package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfMsgRetryTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 重试轨迹 DAO。轨迹不可改删（spec edi-message-processing）：本 DAO 仅暴露
 * INSERT（BaseMapper.insert）与只读查询，不提供 update/delete 调用路径。
 */
@Mapper
public interface IntfMsgRetryTraceDao extends BaseMapper<IntfMsgRetryTrace> {

    /** 某报文全量轨迹（尝试序号升序，detail 扩展与工单清单共用） */
    @Select("SELECT * FROM erp_intf_msg_retry_trace " +
            "WHERE MSG_ID = #{msgId} AND DEL_FLAG = '0' " +
            "ORDER BY ATTEMPT_NO ASC, ID ASC")
    List<IntfMsgRetryTrace> selectByMsgId(@Param("msgId") String msgId);
}
