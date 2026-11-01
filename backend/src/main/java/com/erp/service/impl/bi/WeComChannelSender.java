package com.erp.service.impl.bi;

import com.erp.service.SysParamService;
import com.erp.service.bi.ChannelSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 企业微信通道（模拟：本系统零企微网关基础设施，记录送达状态；接真 webhook 时替换本实现）。
 * 测试/演练开关：系统参数 REPORT_SIMULATE_FAIL 含 WECOM → 本通道返回失败（验证重试与降级链路）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeComChannelSender implements ChannelSender {

    private final SysParamService sysParam;

    @Override
    public String channel() {
        return "WECOM";
    }

    @Override
    public boolean send(String receiver, String title, String content, String bizId) {
        if (simulatedFail()) {
            log.debug("wecom channel simulated failure: receiver={} bizId={}", receiver, bizId);
            return false;
        }
        // 模拟通道：直接记 SENT（偏差声明见 proposal）
        log.debug("wecom channel simulated send: receiver={} bizId={}", receiver, bizId);
        return true;
    }

    private boolean simulatedFail() {
        String fail = sysParam.getValue("REPORT_SIMULATE_FAIL", "");
        return fail != null && fail.toUpperCase().contains("WECOM");
    }
}
