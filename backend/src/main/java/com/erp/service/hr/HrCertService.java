package com.erp.service.hr;

import com.erp.entity.hr.HrCertCategory;
import com.erp.entity.hr.HrEmployeeCert;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资格认证（qualification-certification）：类别字典 + 员工上传/HR 审核 + 状态机翻转 + 到期预警。
 * 状态仅由审核与每日翻转驱动（无 EXPIRED 人工直改通道，恢复须换证审核通过）。
 */
public interface HrCertService {

    // ---- 类别字典 ----

    List<HrCertCategory> categoryList();

    /** 新建类别（编码 CATE+流水自动生成；含类别默认 DEMO 种子外的自定义类） */
    HrCertCategory createCategory(Map<String, Object> payload);

    /** 更新类别（编码创建后不可改；停用后不可被新证书引用） */
    HrCertCategory updateCategory(String id, Map<String, Object> payload);

    // ---- 证书台账 ----

    /** 员工提交证书（PENDING；同类别存在 VALID/REVIEW_DUE 时 L1 拒绝，须先换证归档） */
    HrEmployeeCert submit(String empId, Map<String, Object> payload);

    /** 我的证书（含类别信息、附件、到期倒计时） */
    List<Map<String, Object>> myList(String empId);

    /** HR 台账（按状态/类别过滤） */
    List<HrEmployeeCert> ledger(String status, String cateId);

    /** 审核队列（PENDING） */
    List<HrEmployeeCert> pendingList();

    /** 审核：通过=同类别旧证归档→VALID→矩阵写入；驳回=REJECTED+意见 */
    HrEmployeeCert audit(String id, boolean pass, String opinion);

    /** 换证归档：非 ARCHIVED 记录置 ARCHIVED（矩阵 CERT 行联动失效） */
    HrEmployeeCert archive(String id);

    // ---- 每日翻转与预警 ----

    /** 每日幂等翻转：VALID→REVIEW_DUE（≤参数天）→EXPIRED（到期次日）；翻转日推送+hr_remind */
    Map<String, Object> dailyFlipScan();

    /** 到期预警页三清单：临期复审/已失效/带病排班 */
    Map<String, Object> alertLists();

    /** 排班门禁判定：给定员工集合中持关键类别 EXPIRED 证书者 */
    Set<String> expiredKeyCertEmpIds(Collection<String> empIds);

    /** 失效证书明细描述（门禁报错文案，含类别/编号/到期日） */
    Map<String, Object> expiredKeyCertDesc(String empId);
}
