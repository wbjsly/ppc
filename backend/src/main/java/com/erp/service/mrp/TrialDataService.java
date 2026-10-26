package com.erp.service.mrp;

import com.erp.entity.mrp.TrialData;

import java.util.List;
import java.util.Map;

/**
 * 试产数据采集（change add-trial-production-management，spec trial-execution
 * 「逐工序数据采集与结案卡控」FR-4.5-8-5）。
 * 按（moId, opSeq）upsert；表单加载带出该工序 5.7 CONFIRMED 报工实测工时（可改，偏差 D7）；
 * 结案完整性卡控由 TrialCloseChecks 钩子消费（DONE 工序缺采集行 → 关单 L1 阻断）。
 */
public interface TrialDataService {

    /**
     * 表单载入（带出默认值）：已有采集行 → 返回之；无 → 按 5.7 CONFIRMED 报工工时
     * 生成预填草稿（实测工时 = 该工序 setup+run 合计，未报工则 0/null）。
     */
    Map<String, Object> form(String moId, Integer opSeq);

    /** 采集 upsert（UK MO_ID+OP_SEQ；参数/不良模式/实测工时落库，操作人留痕） */
    TrialData save(TrialData data);

    /** 某试产工单全部采集行（列表页/结案校验数据源） */
    List<TrialData> listByMo(String moId);
}
