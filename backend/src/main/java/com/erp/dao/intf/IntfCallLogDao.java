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

    /** P95 取样（伙伴维度，add-service-monitoring-console design D2）：单调用方窗口耗时升序 */
    @Select("SELECT COST_MS FROM erp_intf_call_log WHERE CALLER = #{caller} " +
            "AND CALL_AT >= #{from} AND DEL_FLAG = '0' ORDER BY COST_MS ASC")
    List<Integer> selectCostsForCaller(@Param("caller") String caller,
                                       @Param("from") java.time.LocalDateTime from);

    /** 窗口内活跃调用方（伙伴指标行与报告章节数据源，design D2） */
    @Select("SELECT DISTINCT CALLER FROM erp_intf_call_log WHERE CALL_AT >= #{from} " +
            "AND CALLER IS NOT NULL AND CALLER != '' AND DEL_FLAG = '0'")
    List<String> selectActiveCallers(@Param("from") java.time.LocalDateTime from);

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

    // ==================== 12.3 接口调用操作台（add-interface-call-console，全只读） ====================

    /**
     * 认证拒绝原因分组（spec 12.3.1，design D3 可观测四类）：
     * 400=时间戳超差 / SECURITY_EVENT*=签名与口令安全事件 / 403=Scope 拒绝 / 其余 401=凭证认证失败。
     */
    @Select("<script>" +
            "SELECT CALLER AS caller, " +
            "  CASE WHEN RESP_CODE = 400 THEN 'TIMESTAMP_SKEW' " +
            "       WHEN ERR_CODE LIKE 'SECURITY_EVENT%' THEN 'SECURITY_EVENT' " +
            "       WHEN RESP_CODE = 403 THEN 'SCOPE_DENIED' " +
            "       ELSE 'CREDENTIAL_AUTH_FAILED' END AS reason, " +
            "  COUNT(*) AS total " +
            "FROM erp_intf_call_log " +
            "WHERE DEL_FLAG = '0' AND RESP_CODE IN (400, 401, 403) " +
            "  AND (#{caller} IS NULL OR #{caller} = '' OR CALLER = #{caller}) " +
            "  AND (#{from} IS NULL OR CALL_AT &gt;= #{from}) " +
            "  AND (#{to} IS NULL OR CALL_AT &lt;= #{to}) " +
            "GROUP BY CALLER, reason ORDER BY CALLER, total DESC" +
            "</script>")
    List<java.util.Map<String, Object>> selectAuthRejectionGroups(@Param("caller") String caller,
                                                                  @Param("from") java.time.LocalDateTime from,
                                                                  @Param("to") java.time.LocalDateTime to);

    /** 429 趋势（spec 12.3.2）：按小时（≤48h）或按天聚合，分桶表达式固定二选一（非用户输入） */
    @Select("<script>" +
            "SELECT CASE WHEN #{hourBuckets} = 1 THEN DATE_FORMAT(CALL_AT, '%Y-%m-%d %H:00') " +
            "            ELSE DATE_FORMAT(CALL_AT, '%Y-%m-%d') END AS bucket, " +
            "  COUNT(*) AS total " +
            "FROM erp_intf_call_log " +
            "WHERE DEL_FLAG = '0' AND RESP_CODE = 429 AND CALL_AT &gt;= #{from} " +
            "  AND (#{caller} IS NULL OR #{caller} = '' OR CALLER = #{caller}) " +
            "GROUP BY bucket ORDER BY bucket" +
            "</script>")
    List<java.util.Map<String, Object>> selectRateTrend(@Param("from") java.time.LocalDateTime from,
                                                        @Param("caller") String caller,
                                                        @Param("hourBuckets") int hourBuckets);

    /** 趋势对账总数（spec 12.3.2：趋势总数 = 同条件明细行数） */
    @Select("<script>" +
            "SELECT COUNT(*) FROM erp_intf_call_log WHERE DEL_FLAG = '0' AND RESP_CODE = 429 " +
            "AND CALL_AT &gt;= #{from} AND (#{caller} IS NULL OR #{caller} = '' OR CALLER = #{caller})" +
            "</script>")
    long countRateTrend(@Param("from") java.time.LocalDateTime from, @Param("caller") String caller);

    /** Trace 下钻（spec 12.3.3）：按 X-Request-Id，含被拒请求 */
    @Select("SELECT * FROM erp_intf_call_log WHERE REQ_ID = #{reqId} AND DEL_FLAG = '0' " +
            "ORDER BY CALL_AT DESC LIMIT 50")
    List<com.erp.entity.intf.IntfCallLog> selectTrace(@Param("reqId") String reqId);

    /**
     * 审计聚合（spec 12.3.3）：调用方×接口×日 —— 调用量/成功率/4xx/5xx/P95。
     * P95 用窗口函数取组内第 ceil(n*0.95) 小耗时（MySQL 8）。
     */
    @Select("<script>" +
            "SELECT t.caller, t.apiPath, t.day, COUNT(*) AS total, " +
            "  SUM(CASE WHEN t.resp &lt; 400 THEN 1 ELSE 0 END) AS success, " +
            "  SUM(CASE WHEN t.resp &gt;= 400 AND t.resp &lt; 500 THEN 1 ELSE 0 END) AS e4xx, " +
            "  SUM(CASE WHEN t.resp &gt;= 500 THEN 1 ELSE 0 END) AS e5xx, " +
            "  MAX(CASE WHEN t.rn = t.p95rn THEN t.cost END) AS p95 " +
            "FROM (SELECT CALLER AS caller, API_PATH AS apiPath, DATE(CALL_AT) AS day, " +
            "        RESP_CODE AS resp, COST_MS AS cost, " +
            "        ROW_NUMBER() OVER (PARTITION BY CALLER, API_PATH, DATE(CALL_AT) ORDER BY COST_MS) AS rn, " +
            "        CAST(CEIL(COUNT(*) OVER (PARTITION BY CALLER, API_PATH, DATE(CALL_AT)) * 0.95) AS UNSIGNED) AS p95rn " +
            "      FROM erp_intf_call_log WHERE DEL_FLAG = '0' " +
            "        AND CALL_AT &gt;= #{from} AND CALL_AT &lt;= #{to} " +
            "        AND (#{caller} IS NULL OR #{caller} = '' OR CALLER = #{caller})) t " +
            "GROUP BY t.caller, t.apiPath, t.day ORDER BY t.day DESC, total DESC LIMIT 500" +
            "</script>")
    List<java.util.Map<String, Object>> selectAuditAggregates(@Param("from") java.time.LocalDateTime from,
                                                              @Param("to") java.time.LocalDateTime to,
                                                              @Param("caller") String caller);
}
