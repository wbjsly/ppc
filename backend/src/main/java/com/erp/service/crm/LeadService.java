package com.erp.service.crm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.crm.Lead;
import com.erp.entity.crm.LeadFollowup;
import com.erp.entity.crm.LeadPool;

import java.util.List;
import java.util.Map;

/** 线索域服务（spec crm-lead-management，FR-4.8-1-1~1-4 + BR-4.8-07/08）。 */
public interface LeadService {

    Page<Lead> page(long current, long size, String keyword, String status, String grade, String ownerId);

    Lead get(String id);

    /** 查重（公司名称 + 联系人）：命中返回候选，供 L4 提示与关联选择 */
    List<Lead> checkDuplicate(String companyName, String contactName);

    /** 录入：必填 L1 硬阻断、自动生成编号、可带 DUPLICATE_OF 关联既有线索 */
    Lead create(Lead lead);

    /** 变更：已转化/已关闭的线索不可改 */
    Lead update(Lead lead);

    /** 分配 / 改派（销售经理或管理员）；已有负责人时即改派，留痕分配时间 */
    void assign(String leadId, String ownerId, String ownerName);

    /** 认领：线索池或无负责人的线索；已有他人负责 → 拒绝（BR-4.8-08 单人锁定） */
    void claim(String leadId);

    /** 五维评分（缺项按 0 分计并返回缺失维度清单）；返回总分、等级与提示 */
    Map<String, Object> score(String leadId, Integer need, Integer budget, Integer chain,
                              Integer urgency, Integer compete);

    List<LeadFollowup> followups(String leadId);

    /** 追加跟进（仅追加：不提供改/删），更新最后跟进时间与下次计划日 */
    LeadFollowup addFollowup(LeadFollowup followup);

    List<LeadPool> poolList(String status);

    /** D 级或手工入池（线索置 RECYCLED、清空负责人） */
    void toPool(String leadId, String reason, String remark);

    /** 线索池指派（销售经理） */
    void assignFromPool(String poolId, String ownerId, String ownerName);

    /** 调度入口：3 工作日未跟进提醒 + 30 天未跟进回收（返回处理条数） */
    Map<String, Integer> sweep();
}
