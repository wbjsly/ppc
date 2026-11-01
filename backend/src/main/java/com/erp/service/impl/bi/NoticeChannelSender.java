package com.erp.service.impl.bi;

import com.erp.service.bi.ChannelSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 站内信通道（真实送达，erp_sys_notice TARGET_USER 精准通知）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoticeChannelSender implements ChannelSender {

    private final JdbcTemplate jdbc;

    @Override
    public String channel() {
        return "NOTICE";
    }

    @Override
    public boolean send(String receiver, String title, String content, String bizId) {
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, ?, 'DailyReport', ?, ?, ?, '0', 'system', NOW(), '0', 0)",
                    UUID.randomUUID().toString().replace("-", ""),
                    receiver, bizId, title, content);
            return true;
        } catch (Exception e) {
            log.warn("notice channel send failed: receiver={}: {}", receiver, e.getMessage());
            return false;
        }
    }
}
