package com.erp.service.impl.intf;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfSubscriptionDao;
import com.erp.entity.intf.IntfSubscription;
import com.erp.service.intf.EventSubscriptionService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 事件订阅配置实现（add-event-subscription，design D4 / spec event-subscription 1.3）。
 * 投递路由按两级回落：启用订阅 URL 快照 → 通道 URL → 台账确认（DeliveryServiceImpl）。
 */
@Service
public class EventSubscriptionServiceImpl implements EventSubscriptionService {

    private final IntfSubscriptionDao dao;

    public EventSubscriptionServiceImpl(IntfSubscriptionDao dao) {
        this.dao = dao;
    }

    @Override
    public Page<IntfSubscription> page(long current, long size, String partnerCode,
                                       String eventType, String status) {
        LambdaQueryWrapper<IntfSubscription> qw = new LambdaQueryWrapper<>();
        qw.like(has(partnerCode), IntfSubscription::getPartnerCode, trim(partnerCode));
        qw.like(has(eventType), IntfSubscription::getEventType, trim(eventType));
        qw.eq(has(status), IntfSubscription::getStatus, trim(status));
        qw.orderByDesc(IntfSubscription::getCreateDate);
        return dao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> create(Map<String, Object> body) {
        String partnerCode = str(body.get("partnerCode"));
        String eventType = str(body.get("eventType"));
        if (partnerCode == null || eventType == null) {
            throw new ServiceException(400, "partnerCode 与 eventType 必填");
        }
        if (dao.selectByPartnerAndType(partnerCode, eventType) != null) {
            throw new ServiceException(409, "该伙伴已订阅事件类型：" + partnerCode + " × " + eventType);
        }
        IntfSubscription s = new IntfSubscription();
        s.setPartnerCode(partnerCode);
        s.setEventType(eventType);
        s.setCallbackUrl(str(body.get("callbackUrl"))); // 空 = 回落通道 URL（零断流）
        s.setRetryMax(retryMaxOf(body, 3));
        s.setStatus(IntfSubscription.ST_ENABLED);
        s.setRemark(str(body.get("remark")));
        dao.insert(s);
        return Map.of("id", s.getId(), "partnerCode", s.getPartnerCode(),
                "eventType", s.getEventType(), "retryMax", s.getRetryMax(), "status", s.getStatus());
    }

    @Override
    public Map<String, Object> update(String id, Map<String, Object> body) {
        IntfSubscription s = require(id);
        if (body.containsKey("callbackUrl")) {
            s.setCallbackUrl(str(body.get("callbackUrl")));
        }
        if (body.containsKey("retryMax")) {
            s.setRetryMax(retryMaxOf(body, s.getRetryMax()));
        }
        if (body.containsKey("remark")) {
            s.setRemark(str(body.get("remark")));
        }
        dao.updateById(s);
        return Map.of("id", s.getId(), "callbackUrl", s.getCallbackUrl() == null ? "" : s.getCallbackUrl(),
                "retryMax", s.getRetryMax(), "status", s.getStatus());
    }

    @Override
    public Map<String, Object> toggle(String id, String status) {
        if (!IntfSubscription.ST_ENABLED.equals(status) && !IntfSubscription.ST_DISABLED.equals(status)) {
            throw new ServiceException(400, "status 仅支持 ENABLED/DISABLED");
        }
        IntfSubscription s = require(id);
        s.setStatus(status);
        dao.updateById(s);
        return Map.of("id", s.getId(), "status", status);
    }

    // ---------------------------------------------------------------- helpers

    private IntfSubscription require(String id) {
        IntfSubscription s = dao.selectById(id);
        if (s == null) {
            throw new ServiceException(404, "订阅配置不存在");
        }
        return s;
    }

    private static Integer retryMaxOf(Map<String, Object> body, int def) {
        Object o = body.get("retryMax");
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return def;
        }
        int v;
        try {
            v = Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException ex) {
            throw new ServiceException(422, "RETRY_MAX 须为 1~5 的整数");
        }
        if (v < 1 || v > 5) {
            throw new ServiceException(422, "RETRY_MAX 须为 1~5（订阅级死信阈值，覆盖全局 API_RETRY_MAX）");
        }
        return v;
    }

    private static boolean has(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).trim().isEmpty() ? null : String.valueOf(o).trim();
    }
}
