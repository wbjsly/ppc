package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/**
 * IP 归属协议维护（add-ip-management，FR-4.8-6-2 数据基座 + BR-4.8-24/25）。
 * 合作模式三选一；COOP 比例合计必须=100%；每项目一份生效（后签覆盖前签留痕）。
 * 维护写操作限 ROLE_LEGAL/ROLE_ADMIN（服务层单据级校验）。
 */
public interface PmIpAgreementService {

    /** 保存协议（isNew 显式；COOP 比例合计=100% L1；后签覆盖前签 EFFECTIVE 置 0 留痕） */
    Map<String, Object> save(Map<String, Object> payload);

    /** 项目生效协议（判定引擎/立项卡控共用；无则 null） */
    Map<String, Object> effectiveOf(String projectId);

    /** 协议列表（分页+项目过滤） */
    Map<String, Object> list(Map<String, Object> query);
}
