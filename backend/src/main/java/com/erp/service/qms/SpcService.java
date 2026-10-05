package com.erp.service.qms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.qms.SpcAlert;
import com.erp.entity.qms.SpcSample;

import java.util.List;
import java.util.Map;

/**
 * SPC 过程控制（spec spc-monitoring，tasks 10.8，6.10）。
 *
 * 采样组录入（自标准带规格：缺 spec 时按物料现行标准首个计量特性带出）；
 * 控制限：≥25 组 X̄±3σ（NORMAL），否则规格折算并标「预控制」（PRE_CONTROL）；
 * 三规则告警：R1 超控制限 / R2 连续 7 点同侧 / R3 连续 7 点上升或下降，
 * 同特性同规则 OPEN 幂等；告警处置闭环（OPEN → HANDLED）。
 */
public interface SpcService {

    /**
     * 采样组录入（tasks 10.8）并计算控制限 + 三规则评估。
     * body: charCode/charName/materialCode/groupNo/sampleQty/meanValue/minValue/maxValue/
     *       specLower/specUpper/specTarget（可缺省，按物料标准带出）
     */
    SpcSample recordSample(Map<String, Object> body);

    /** 特性采样序列（控制限随每组快照存储，前端按序画图） */
    List<SpcSample> samples(String charCode, int limit);

    /** 告警分页（状态/特性） */
    Page<SpcAlert> alerts(long current, long size, String charCode, String status);

    /** 告警处置闭环（OPEN → HANDLED + 处置结论） */
    SpcAlert handleAlert(String id, String result);

    /** 趋势汇总（6.10.2）：各特性最新点、控制限、OPEN 告警数 */
    List<Map<String, Object>> trends();
}
