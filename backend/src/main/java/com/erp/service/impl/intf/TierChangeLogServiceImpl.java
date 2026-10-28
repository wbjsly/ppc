package com.erp.service.impl.intf;

import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfTierChangeLogDao;
import com.erp.entity.intf.IntfTierChangeLog;
import com.erp.service.intf.TierChangeLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 档位变更历史单入口（design D1）：三处写入点（自动降档、2.8.3 调档、12.1.3 调档）
 * 共用 record，保证历史口径一致且只 INSERT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TierChangeLogServiceImpl implements TierChangeLogService {

    private final IntfTierChangeLogDao dao;

    @Override
    public void record(String caller, String partnerCode, String changeType,
                       String tierFrom, String tierTo, Integer limitFrom, Integer limitTo,
                       String triggerReason, String operator) {
        if (!StringUtils.hasText(caller) || !StringUtils.hasText(tierFrom)
                || !StringUtils.hasText(tierTo) || !StringUtils.hasText(changeType)) {
            throw new ServiceException(400, "档位变更历史必填：caller/changeType/tierFrom/tierTo");
        }
        if (!IntfTierChangeLog.TYPE_AUTO.equals(changeType)
                && !IntfTierChangeLog.TYPE_MANUAL.equals(changeType)) {
            throw new ServiceException(400, "changeType 仅支持 AUTO_DOWNGRADE / MANUAL");
        }
        if (!StringUtils.hasText(operator)) {
            throw new ServiceException(400, "operator 必填（自动降档传 SYSTEM）");
        }
        IntfTierChangeLog row = new IntfTierChangeLog();
        row.setCaller(caller);
        row.setPartnerCode(partnerCode);
        row.setChangeType(changeType);
        row.setTierFrom(tierFrom);
        row.setTierTo(tierTo);
        row.setLimitFrom(limitFrom);
        row.setLimitTo(limitTo);
        row.setTriggerReason(triggerReason);
        row.setOperator(operator);
        dao.insert(row);
        log.info("tier change recorded: caller={} {} {} -> {} by {}",
                caller, changeType, tierFrom, tierTo, operator);
    }

    @Override
    public Map<String, Object> history(String caller, long current, long size) {
        long cur = Math.max(current, 1);
        long sz = Math.min(Math.max(size, 1), 200);
        List<IntfTierChangeLog> rows = dao.selectHistory(caller, (cur - 1) * sz, sz);
        long total = dao.countHistory(caller);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        out.put("total", total);
        out.put("current", cur);
        out.put("size", sz);
        return out;
    }
}
