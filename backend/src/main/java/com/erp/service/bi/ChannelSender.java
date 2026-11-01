package com.erp.service.bi;

/**
 * 日报推送通道（add-daily-report-subscription，spec bi-daily-report，design D3）。
 * NOTICE=真实站内信（erp_sys_notice TARGET_USER）；MAIL/WECOM=模拟通道（记录 SENT，
 * 后续接真网关只替换实现，引擎零改动）。
 */
public interface ChannelSender {

    /** 通道标识（MAIL/WECOM/NOTICE，与订阅 CHANNELS_JSON 对应） */
    String channel();

    /**
     * 发送一条日报推送。
     *
     * @param receiver 接收人用户名
     * @param title    标题
     * @param content  正文摘要（水印流水号/数据截止等）
     * @param bizId    业务标识（推送日志 ID）
     * @return true=送达成功；false=失败（引擎按重试策略处理）
     */
    boolean send(String receiver, String title, String content, String bizId);
}
