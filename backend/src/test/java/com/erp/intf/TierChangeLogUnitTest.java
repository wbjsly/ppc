package com.erp.intf;

import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfTierChangeLogDao;
import com.erp.entity.intf.IntfTierChangeLog;
import com.erp.service.impl.intf.TierChangeLogServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务 2.1：TierChangeLogService.record 参数组装与必填校验（纯单元，无 Spring 上下文）。
 */
class TierChangeLogUnitTest {

    private IntfTierChangeLogDao dao;
    private TierChangeLogServiceImpl service;

    @BeforeEach
    void setUp() {
        dao = mock(IntfTierChangeLogDao.class);
        when(dao.insert(any(IntfTierChangeLog.class))).thenReturn(1);
        service = new TierChangeLogServiceImpl(dao);
    }

    @Test
    void recordAssemblesAllFieldsForAutoDowngrade() {
        service.record("API-KEY-1", "P001", IntfTierChangeLog.TYPE_AUTO,
                "NORMAL", "LOWEST", 100, 30,
                "连续 3 个统计窗口触发 429", IntfTierChangeLog.OPERATOR_SYSTEM);

        ArgumentCaptor<IntfTierChangeLog> cap = ArgumentCaptor.forClass(IntfTierChangeLog.class);
        verify(dao).insert(cap.capture());
        IntfTierChangeLog row = cap.getValue();
        assertEquals("API-KEY-1", row.getCaller());
        assertEquals("P001", row.getPartnerCode());
        assertEquals(IntfTierChangeLog.TYPE_AUTO, row.getChangeType());
        assertEquals("NORMAL", row.getTierFrom());
        assertEquals("LOWEST", row.getTierTo());
        assertEquals(100, row.getLimitFrom());
        assertEquals(30, row.getLimitTo());
        assertEquals("连续 3 个统计窗口触发 429", row.getTriggerReason());
        assertEquals(IntfTierChangeLog.OPERATOR_SYSTEM, row.getOperator());
    }

    @Test
    void recordAcceptsManualChangeWithOperator() {
        service.record("API-KEY-2", null, IntfTierChangeLog.TYPE_MANUAL,
                "NORMAL", "STRATEGIC", 100, 500, "管理员调整", "admin");
        ArgumentCaptor<IntfTierChangeLog> cap = ArgumentCaptor.forClass(IntfTierChangeLog.class);
        verify(dao).insert(cap.capture());
        assertEquals(IntfTierChangeLog.TYPE_MANUAL, cap.getValue().getChangeType());
        assertEquals("admin", cap.getValue().getOperator());
    }

    @Test
    void recordRejectsMissingRequiredFields() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.record("", "P001", IntfTierChangeLog.TYPE_MANUAL,
                        "NORMAL", "NEW", 100, 30, "r", "admin"));
        assertEquals(400, e.getCode());

        assertThrows(ServiceException.class,
                () -> service.record("API-KEY-1", null, IntfTierChangeLog.TYPE_MANUAL,
                        "", "NEW", 100, 30, "r", "admin"));

        assertThrows(ServiceException.class,
                () -> service.record("API-KEY-1", null, IntfTierChangeLog.TYPE_MANUAL,
                        "NORMAL", "NEW", 100, 30, "r", ""));
    }

    @Test
    void recordRejectsUnknownChangeType() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.record("API-KEY-1", null, "WHATEVER",
                        "NORMAL", "NEW", 100, 30, "r", "admin"));
        assertEquals(400, e.getCode());
    }
}
