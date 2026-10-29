package com.erp.service.impl.approval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 职责分离违规留痕（add-permission-approval-flow design D2/D3，BR-5.6-01 / C-5.6-07）。
 * REQUIRES_NEW 独立事务：违规记录必须在 throw 回滚主事务后仍然落库
 * （同事务写日志再抛异常会被一并回滚——首版实现的坑）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViolationAuditWriter {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSelfSignViolation(String applyBy, String bizType, String bizId,
                                        String signer, String action) {
        log.warn("SOD self-sign violation: bizType={} bizId={} applyBy={} signer={} action={}",
                bizType, bizId, applyBy, signer, action);
        jdbc.update("INSERT INTO hr_change_log (ID, EMP_ID, FIELD_NAME, OLD_VALUE, NEW_VALUE, REASON, "
                        + "CHANGE_BY, CHANGE_AT) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString().replace("-", ""), applyBy,
                "SOD_SELF_SIGN_VIOLATION", applyBy, signer,
                "发起人尝试自签 " + bizType + "（" + action + "）", signer, LocalDateTime.now());
    }
}
