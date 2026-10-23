package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.ArProvision;

import java.util.Map;

/** 坏账计提（8.2.3，spec ar-management：账龄组合比例法 试算→确认→下期自动冲回重提） */
public interface ArProvisionService {

    /** 试算（只算不落库）：段基数×比例，目标余额=Σ段计提额，本期应提=目标−已提（可负） */
    Map<String, Object> trial(String customerId, String period);

    /**
     * 确认计提（同事务）：科目断言 1232/6702 → 冲回上期（reverse 原计提凭证）→
     * 本期全额重提（借6702/贷1232）→ 台账 CONFIRMED 落库（六段快照+凭证锚）。
     */
    Map<String, Object> confirmProvision(String customerId, String period);

    /** 计提台账分页 */
    Page<ArProvision> provisionPage(long current, long size, String period, String customerId);

    /** 台账详情（六段快照 + 凭证链） */
    Map<String, Object> provisionDetail(String provisionId);
}
