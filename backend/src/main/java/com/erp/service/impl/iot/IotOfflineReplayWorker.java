package com.erp.service.impl.iot;

import com.erp.common.ServiceException;
import com.erp.service.iot.IotExceptionService;
import com.erp.service.iot.IotReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 离线业务回放 worker（add-iot-mobile 13.5）：
 * REQUIRES_NEW 独立事务——回放业务抛异常仅回放事务自身回滚，
 * 不污染 uploadBatch 共享事务（否则 FAILED 状态落定后提交抛 UnexpectedRollback）。
 */
@Slf4j
@Service
public class IotOfflineReplayWorker {

    private final IotReportService reportService;
    private final IotExceptionService exceptionService;

    public IotOfflineReplayWorker(IotReportService reportService,
                                  IotExceptionService exceptionService) {
        this.reportService = reportService;
        this.exceptionService = exceptionService;
    }

    /**
     * 按 BIZ_TYPE 回放过账（独立事务）。
     *
     * @throws ServiceException 业务校验失败（由调用方转 FAILED 状态）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void replay(String bizType, Map<String, Object> body) {
        switch (bizType) {
            case "REPORT" -> reportService.reportOutput(body);
            case "EXCEPTION" -> exceptionService.report(body);
            default -> log.info("[iot] offline replay bizType={}（状态回放）", bizType);
        }
    }
}
