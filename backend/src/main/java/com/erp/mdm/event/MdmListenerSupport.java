package com.erp.mdm.event;

import com.erp.dao.mdm.MdmListenerLogDao;
import com.erp.dao.intf.IntfTicketDao;
import com.erp.entity.mdm.MdmListenerLog;
import com.erp.entity.intf.IntfTicket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MDM 监听执行台账支持：每次执行落 erp_mdm_listener_log；
 * 失败指数退避重试 ≤3 次，仍失败生成运维工单（erp_intf_ticket，SOURCE=MDM_LISTENER）。
 */
@Slf4j
@Component
public class MdmListenerSupport {

    private final MdmListenerLogDao logDao;
    private final IntfTicketDao ticketDao;

    public MdmListenerSupport(MdmListenerLogDao logDao, IntfTicketDao ticketDao) {
        this.logDao = logDao;
        this.ticketDao = ticketDao;
    }

    /**
     * 记录一次监听执行。status=FAILED 且 retryCnt>=maxRetry 时生成运维工单。
     * @return 本机执行的工单 ID（未超限为 null）
     */
    public String record(MdmChangedEvent e, String listenerName, String status,
                         int retryCnt, long execMs, String errorMsg, int maxRetry) {
        MdmListenerLog row = new MdmListenerLog();
        row.setEventId(e.getEventId());
        row.setEventType(e.getEventType());
        row.setEntityId(e.getEntityId());
        row.setListenerName(listenerName);
        row.setStatus(status);
        row.setRetryCnt(retryCnt);
        row.setExecMs((int) execMs);
        row.setErrorMsg(errorMsg == null ? null : errorMsg.substring(0, Math.min(errorMsg.length(), 480)));
        row.setCreateBy("system");
        row.setCreateDate(LocalDateTime.now());
        if (MdmListenerLog.ST_FAILED.equals(status) && retryCnt >= maxRetry) {
            IntfTicket t = new IntfTicket();
            t.setTicketNo("MDM-L-" + System.currentTimeMillis());
            t.setSource("MDM_LISTENER");
            t.setRefId(e.getEventId());
            t.setTitle("MDM 监听失败：" + listenerName + " 处理 " + e.getEventType() + " 重试 " + retryCnt + " 次仍失败");
            t.setDetail("事件 " + e.getEventId() + "（" + e.getEventType() + " entityId=" + e.getEntityId()
                    + "）监听器 " + listenerName + " 连续失败：" + errorMsg);
            t.setPriority("P2");
            t.setStatus("OPEN");
            t.setCreateBy("system");
            ticketDao.insert(t);
            row.setWorkTicketId(t.getId());
            log.warn("mdm listener failed after {} retries, ticket {}: {}", retryCnt, t.getTicketNo(), errorMsg);
        }
        logDao.insert(row);
        return row.getWorkTicketId();
    }
}
