package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfCallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IntfCallLogDao extends BaseMapper<IntfCallLog> {

    /** 滚动窗口内某调用方的失败统计（熔断判定，C-5.5-09） */
    @Select("SELECT COUNT(*) FROM erp_intf_call_log WHERE CALLER = #{caller} " +
            "AND CALL_AT >= #{from} AND DEL_FLAG = '0'")
    int countFrom(@Param("caller") String caller, @Param("from") java.time.LocalDateTime from);

    /** 熔断次数统计（24 小时内 503 且 err_code=CIRCUIT_OPEN 的触发记录由通道自身维护，此处供 SLA 错误率） */
    @Select("SELECT COUNT(*) FROM erp_intf_call_log WHERE CALL_AT >= #{from} AND DEL_FLAG = '0' " +
            "AND (#{caller} IS NULL OR CALLER = #{caller})")
    int countAll(@Param("caller") String caller, @Param("from") java.time.LocalDateTime from);

    @Select("SELECT COUNT(*) FROM erp_intf_call_log WHERE CALL_AT >= #{from} AND DEL_FLAG = '0' " +
            "AND RESP_CODE >= 400 AND (#{caller} IS NULL OR CALLER = #{caller})")
    int countAllFailed(@Param("caller") String caller, @Param("from") java.time.LocalDateTime from);

    /** P95 取样：窗口内全部耗时升序 */
    @Select("SELECT COST_MS FROM erp_intf_call_log WHERE CALL_AT >= #{from} AND DEL_FLAG = '0' ORDER BY COST_MS ASC")
    List<Integer> selectCosts(@Param("from") java.time.LocalDateTime from);

    /** 泄露追溯：近 30 天调用样本（SOP-5.5-B 步骤6） */
    @Select("SELECT IP AS ip, API_PATH AS apiPath, RESP_CODE AS respCode, REQ_ID AS reqId, " +
            "USER_AGENT AS userAgent, CALL_AT AS callAt FROM erp_intf_call_log " +
            "WHERE CALLER = #{partnerCode} AND CALL_AT >= #{from} AND DEL_FLAG = '0' " +
            "ORDER BY CALL_AT DESC LIMIT 500")
    List<java.util.Map<String, Object>> traceRows(@Param("partnerCode") String partnerCode,
                                                  @Param("from") java.time.LocalDateTime from);

    /** 滚动窗口失败统计：4xx 及以上均计为失败（spec S-5.5-03：4xx 批量失败同样触发熔断） */
    @Select("SELECT COUNT(*) FROM erp_intf_call_log WHERE CALLER = #{caller} " +
            "AND CALL_AT >= #{from} AND RESP_CODE >= 400 AND DEL_FLAG = '0'")
    int countFailedFrom(@Param("caller") String caller, @Param("from") java.time.LocalDateTime from);
}
