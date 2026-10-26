package com.erp.service.hr;

import com.erp.entity.hr.HrCompTimeBatch;

import java.util.List;
import java.util.Map;

/** 调休额度生命周期（M10-4-2，overtime-comp-time：到期提醒/清零留痕/离职折算；FEFO 扣减走统一余额池）。 */
public interface HrCompTimeService {

    /** 调休批次清单（按员工）。 */
    List<HrCompTimeBatch> list(String empId);

    /** 员工可用调休小时数（ACTIVE 批次 remain 合计）。 */
    Map<String, Object> balance(String empId);

    /**
     * 到期扫描（每日）：①到期前 COMP_TIME_REMIND_DAYS 生成提醒（同批同日防重）；
     * ②过期批次自动清零（REMAIN→0 + EXPIRED + 留痕）。返回 {reminded, expired}。
     */
    Map<String, Object> expiryScan();

    /** 离职折算（登记留桩：余额 × 小时基数 × COMP_TIME_RESIGN_RATE，供薪酬结算）。 */
    Map<String, Object> resignSettlement(String empId);
}
