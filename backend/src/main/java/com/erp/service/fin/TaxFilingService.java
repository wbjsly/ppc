package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.TaxReturn;

import java.util.Map;

/**
 * 纳税申报服务（M9-5-1/2，spec tax-filing，design D2/D3）。
 * 增值税链：汇总（按税率/认证态）→ 勾稽（C-4.14-04 L1 + BR-4.14-30 栏次级）
 * → 申报表生成（主表+附表1/2+附加税，BR-32/34）→ 三级审批 → 锁定 → 缴款回执 → 逾期事件。
 * 所得税预缴（TAX_TYPE=CIT）：累计利润×税率+调整项，同审批/缴款链。
 * 锁定期修改走跨期调整单（BR-4.14-31 快照，TaxFilingAdj 审批）。
 */
public interface TaxFilingService {

    /** 进销项数据汇总（BR-4.14-31 所属期归集 + 跨期提示） */
    Map<String, Object> summarize(String period);

    /**
     * 勾稽校验（C-4.14-04 / BR-4.14-30）：申报数 vs 账载 POSTED 净发生额。
     * 返回 rows（栏次/申报数/账载数/差额）+ passed + 待过账提示；generate 内部调用时差异>0 抛 422。
     */
    Map<String, Object> reconcile(String period);

    /** 增值税申报表生成（主表+附表1/2+附加税联动；每期一单，重申报走调整） */
    Map<String, Object> generate(String period);

    /** 生成增值税申报单（含优惠适用数据：即征即退金额/备案范围收入，BR-4.14-05 校验） */
    Map<String, Object> generate(String period, java.util.Map<String, Object> payload);

    /** 企业所得税预缴申报生成（citBase 可空=总账取数；账载差异同口径阻断） */
    Map<String, Object> generateCit(String period, Map<String, Object> payload);

    /** 提交三级审批（税务专员复核 → 税务主管审核 → 财务经理终审；通过即锁定） */
    Map<String, Object> submit(String returnId);

    /** 缴款回执离线登记（SUBMIT_DATE/RECEIPT_NO → FILE_DATE → PAY_*，FR-4.14-5-6） */
    Map<String, Object> registerStatus(String returnId, Map<String, Object> payload);

    /** 逾期申报事件单（滞纳金估算 + 升级财务经理，FR-4.14-5-6 异常） */
    Map<String, Object> markOverdue(String returnId, String faultNote);

    /** 跨期调整（BR-4.14-31：快照 + 审批；仅锁定期可用） */
    Map<String, Object> adjust(String returnId, Map<String, Object> payload);

    /** 申报单分页 */
    Page<TaxReturn> page(long current, long size, String period, String taxType, String status);

    /** 详情（申报单 + 栏次 + 审批轨迹 + 调整记录） */
    Map<String, Object> detail(String returnId);

    /**
     * 申报归档（FR-4.14-5-7，9.5.4）：按 PERIOD 聚合各税种申报表 + 栏次 + 回执/缴款 + 调整记录。
     * 仅归集已完成申报（FILED/PAID）的产物，未申报期返回空（不产生空归档占位）；
     * 保存期限标注 ≥10 年（design D1：查询聚合不落副本）。
     */
    Map<String, Object> archive(String period);
}
