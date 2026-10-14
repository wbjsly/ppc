package com.erp.service.qms;

import com.erp.entity.qms.SamplingRecord;

import java.util.List;
import java.util.Map;

/**
 * 检验批抽样执行记录（6.3.1，spec inspection-lot 抽样执行记录）。
 * 可选留痕：缺失不阻断录入/判定/放行（探索 D2）。
 */
public interface SamplingRecordService {

    /** 批内抽样记录（按 SEQ 升序），详情/报告用 */
    List<Map<String, Object>> listByLot(String lotId);

    /**
     * 新增抽样记录：校验批存在、方法枚举、数量>0、触发原因枚举；
     * 抽样人默认当前登录；SEQ = 批内已有条数 + 1；
     * 插入后同事务更新 lot.SAMPLED_FLAG='1'（design D1 冗余标记）。
     */
    Map<String, Object> create(String lotId, Map<String, Object> body);
}
