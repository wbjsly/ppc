package com.erp.service.scm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/** CPFR 协同协议（spec cpfr-collaboration R1）。 */
public interface CpfrAgreementService {

    Page<Map<String, Object>> page(long current, long size, String keyword);

    Map<String, Object> get(String id);

    /** 创建/更新协议（参数校验在 enable 时执行，BR-4.9-31） */
    Map<String, Object> save(Map<String, Object> payload);

    /** 启用：5 项必备参数任一缺失 → 422 + 缺失清单（BR-4.9-31，L1） */
    Map<String, Object> enable(String id);

    /** 停用（手工，留痕） */
    Map<String, Object> disable(String id);
}
