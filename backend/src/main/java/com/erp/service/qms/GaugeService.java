package com.erp.service.qms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.qms.Gauge;
import com.erp.entity.qms.GaugeCalibration;
import com.erp.entity.qms.SuspectLot;

import java.util.List;
import java.util.Map;

/**
 * 计量器具与校准（spec gauge-calibration，tasks 10.6~10.7，6.9）。
 *
 * 台账：CTQ 器具校准周期 ≤12 个月；30 天/7 天到期预警扫描。
 * 校准三态：PASS → 更新下次校准日；FAIL → 器具停用（INVALID）
 *   + 反向追溯自上次合格校准日以来用该器具的检验批 → 可疑批次清单（PENDING_EVAL）。
 * 评估时限 5 工作日；未评估（PENDING_EVAL）阻断放行（C-4.12-12，confirmRelease 已校验）。
 */
public interface GaugeService {

    /** 台账新增/编辑（CTQ 周期 ≤12 月校验 422） */
    Gauge save(Map<String, Object> body);

    /**
     * 校准执行（tasks 10.6）：result ∈ PASS / FAIL / LIMITED。
     * PASS → gauge VALID + lastCal/nextCal 推进；FAIL → 停用 + 可疑批次反向追溯（tasks 10.7）。
     */
    GaugeCalibration calibrate(String gaugeId, Map<String, Object> body);

    /** 到期预警扫描（tasks 10.6）：30 天内 + 7 天内清单，返回计数并记日志 */
    Map<String, Object> sweepDue();

    /** 到期预警清单（6.9.3）：due30 / due7 分组 */
    Map<String, Object> warningList();

    /** 可疑批次清单（含评估超期标记：创建 +7 自然天未评估） */
    List<Map<String, Object>> suspectLots(String gaugeCode);

    /** 可疑批次评估（结论必填 → EVALUATED；评估前该批放行被阻断） */
    SuspectLot evalSuspect(String id, String conclusion);

    /** 追溯：按器具 + 时间窗反查用过它的检验批（校准失败时自动生成可疑清单的查询口径） */
    List<Map<String, Object>> traceLots(String gaugeCode, String fromDate);

    Page<Map<String, Object>> page(long current, long size, String keyword, String status);

    /** 器具详情（台账 + 校准历史） */
    Map<String, Object> detail(String id);

    List<GaugeCalibration> calibrations(String gaugeId);
}
