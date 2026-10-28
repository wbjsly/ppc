package com.erp.service.intf;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.intf.IntfEventType;

import java.util.Map;

/**
 * 事件定义注册表（add-event-subscription，FR-4.9-2-1）。
 * 写操作限 ROLE_INTF_ARCH / ADMIN（服务层兜底 403），读 INTF_OPS+。
 * 发布挂点 validateForPublish：未注册/停用/必填缺失 → 422 拒发；类型不符 → 警告放行。
 */
public interface EventTypeService {

    Page<IntfEventType> page(long current, long size, String eventType, String status);

    /** 注册（命名规范 422 / 重复 409） */
    Map<String, Object> create(Map<String, Object> body);

    /** 修改（Schema 变更版本比对：删必填/改类型/收紧未升 Major → 422） */
    Map<String, Object> update(String id, Map<String, Object> body);

    /** 启停（停用阻断该类型新事件发布） */
    Map<String, Object> toggle(String id, String status);

    /**
     * 发布前校验（OutboxPublisher 挂点，仅接口推送通道事件走此路径）。
     *
     * @return 校验结果（信封版本 Major.Minor + L4 类型警告清单，警告落 CONSUMER_NOTE）
     * @throws com.erp.common.ServiceException 422 未注册/停用/必填缺失
     */
    ValidateResult validateForPublish(String eventType, Map<String, Object> payload);

    /** 发布校验结果：version 供信封，warnings 供 CONSUMER_NOTE 留痕（L4 放行） */
    record ValidateResult(String version, java.util.List<String> warnings) {
    }
}
