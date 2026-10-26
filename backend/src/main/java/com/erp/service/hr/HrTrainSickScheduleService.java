package com.erp.service.hr;

import java.util.List;
import java.util.Map;

/**
 * 带病排班清单与处置（attendance-core 认证失效门禁的存量处置面）：
 * 清单 = 认证失效员工 × 未来排班；HR 与本部门主管可取消/改派，处置留痕。
 */
public interface HrTrainSickScheduleService {

    /** 带病排班清单（员工/日期/班次/失效证书） */
    List<Map<String, Object>> list();

    /** 处置：CANCEL=取消排班 / REASSIGN=改派他人（新员工过门禁校验），留痕 */
    Map<String, Object> dispose(String scheduleId, String action, String newEmpId);
}
