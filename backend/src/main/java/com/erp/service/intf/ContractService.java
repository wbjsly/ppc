package com.erp.service.intf;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.intf.IntfContract;
import com.erp.entity.intf.IntfRelease;

import java.util.Map;

/** 接入治理与生产放行（spec interface-onboarding，SOP-5.5-A）。 */
public interface ContractService {

    Map<String, Object> create(Map<String, Object> payload);

    /** 提交评审：dueAt = 登记后 3 个工作日（BR：超时自动催办） */
    Map<String, Object> submitReview(String contractId);

    /** 评审动作：PASS → PUBLISHED；REJECT → 累计 3 次自动关闭 */
    Map<String, Object> review(String contractId, String result, String opinion);

    /** 版本变更：不兼容变更强制 Major+1、旧版本保留 ≥180 天（BR-5.5-12） */
    Map<String, Object> changeVersion(String contractId, Map<String, Object> payload);

    /** 沙箱联调通过率（用例维度） */
    Map<String, Object> passRate(String contractId);

    /** 申请生产放行：通过率 < 100% 硬阻断并列未通过用例（C-5.5-07） */
    Map<String, Object> requestRelease(Map<String, Object> payload);

    /** 双人会签：发起人 ≠ 复核人（C-0-03），通过后切通道 TRIAL + 签发生产凭证 */
    Map<String, Object> reviewRelease(String releaseId);

    /** 观察期满：试运行 → 正式运行（通道 PROD） */
    Map<String, Object> completeObservation(String releaseId);

    /** 归档（内容只读，生成归档时间戳） */
    Map<String, Object> archive(String releaseId);

    /** 观察期日报（调用量/错误率/P95/限流次数，按日） */
    Map<String, Object> observationDaily(String releaseId);

    /** 调度：评审超时催办 + 废弃倒计时预告 + 观察期异常复盘 */
    int sweep();

    Page<IntfContract> contractPage(long current, long size, String partnerCode, String status);

    Page<IntfRelease> releasePage(long current, long size, String status);
}
