package com.erp.sd;

import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.ArWriteoffDao;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmCustomerGroupVersionDao;
import com.erp.dao.mdm.MdmCustomerViewDao;
import com.erp.dao.ops.MdmOutboxDao;
import com.erp.dao.sd.CreditCheckDao;
import com.erp.dao.sd.CreditFreezeDao;
import com.erp.dao.sd.PrepaymentNoticeDao;
import com.erp.dao.sd.SoDao;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmCustomerView;
import com.erp.entity.sd.CreditCheck;
import com.erp.entity.sd.CreditFreeze;
import com.erp.entity.sd.So;
import com.erp.ops.OutboxPublisher;
import com.erp.service.SysParamService;
import com.erp.service.impl.sd.CreditControlServiceImpl;
import com.erp.service.system.NoticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 17.2 规则单测：信用四因子可用额度、账龄超比/及时率/连续逾期处置、
 * 冻结解冻状态回退、特批上限（spec customer-credit-control，BR-4.3-13~18）。
 */
class CreditRulesUnitTest {

    private CreditControlServiceImpl credit;
    private CreditCheckDao checkDao;
    private CreditFreezeDao freezeDao;
    private ArInvoiceDao arDao;
    private ArWriteoffDao writeoffDao;
    private SoDao soDao;
    private MdmCustomerGroupDao customerDao;
    private MdmCustomerViewDao viewDao;
    private MdmCustomerGroupVersionDao versionDao;
    private NoticeService noticeService;
    private SysParamService paramService;

    @BeforeEach
    void auth() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(
                        new SimpleGrantedAuthority("ROLE_CREDIT_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_FINANCE_MGR"),
                        new SimpleGrantedAuthority("ROLE_SALES"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));
        checkDao = mock(CreditCheckDao.class);
        freezeDao = mock(CreditFreezeDao.class);
        arDao = mock(ArInvoiceDao.class);
        writeoffDao = mock(ArWriteoffDao.class);
        soDao = mock(SoDao.class);
        customerDao = mock(MdmCustomerGroupDao.class);
        viewDao = mock(MdmCustomerViewDao.class);
        versionDao = mock(MdmCustomerGroupVersionDao.class);
        noticeService = mock(NoticeService.class);
        paramService = mock(SysParamService.class);
        credit = new CreditControlServiceImpl(checkDao, freezeDao, mock(PrepaymentNoticeDao.class),
                arDao, writeoffDao, soDao, customerDao, viewDao, versionDao,
                noticeService, paramService, mock(OutboxPublisher.class));
    }

    private MdmCustomerGroup customer(String rating) {
        MdmCustomerGroup c = new MdmCustomerGroup();
        c.setId("C1");
        c.setCustomerCode("CUST-1");
        c.setCustomerName("测试客户");
        c.setCreditLimitTotal(new BigDecimal("100000"));
        c.setCreditRating(rating);
        return c;
    }

    private MdmCustomerView view(BigDecimal tempLimit) {
        MdmCustomerView v = new MdmCustomerView();
        v.setGroupId("C1");
        v.setTempCreditLimit(tempLimit);
        v.setTempExpireDate(LocalDate.now().plusDays(30));
        return v;
    }

    private void baseStubs(MdmCustomerGroup c, MdmCustomerView v) {
        when(customerDao.selectById("C1")).thenReturn(c);
        when(viewDao.selectOne(any())).thenReturn(v);
        when(arDao.selectOpenBalance("C1")).thenReturn(new BigDecimal("50000"));
        when(arDao.selectOver90Balance("C1")).thenReturn(BigDecimal.ZERO);
        when(soDao.selectOpenReserved(eq("C1"), any())).thenReturn(BigDecimal.ZERO);
        when(writeoffDao.selectCount12m("C1")).thenReturn(0L);
        when(writeoffDao.selectRecentOverdueCount(eq("C1"), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0L);
    }

    // ---------- 四因子 ----------

    @Test
    void fourFactorsComputeAvailable() {
        MdmCustomerGroup c = customer("A");
        baseStubs(c, view(new BigDecimal("20000"))); // 额度 100000 + 临时 20000 = 120000
        Map<String, Object> out = credit.check("C1", new BigDecimal("30000"), null, null, false);
        assertEquals(CreditCheck.R_PASS, out.get("result"));
        @SuppressWarnings("unchecked")
        Map<String, Object> f = (Map<String, Object>) out.get("factors");
        assertEquals(0, new BigDecimal("120000").compareTo((BigDecimal) f.get("creditLimit")));
        assertEquals(0, new BigDecimal("50000").compareTo((BigDecimal) f.get("arBalance")));
        // available = 120000 - 50000 - 0 - 30000 = 40000
        assertEquals(0, new BigDecimal("40000").compareTo((BigDecimal) f.get("available")));
        assertEquals(CreditCheck.R_PASS, out.get("result"));
    }

    @Test
    void negativeAvailableIsFrozenWithGap() {
        MdmCustomerGroup c = customer("A");
        baseStubs(c, null);
        // 100000 - 50000 - 0 - 60000 = -10000
        Map<String, Object> out = credit.check("C1", new BigDecimal("60000"), null, null, false);
        assertEquals(CreditCheck.R_FROZEN, out.get("result"));
        assertEquals(0, new BigDecimal("10000").compareTo((BigDecimal) out.get("gap")));
        assertTrue(String.valueOf(out.get("reason")).contains("缺口"));
    }

    @Test
    void over90RatioAbove20PercentNeedsApproval() {
        MdmCustomerGroup c = customer("A");
        baseStubs(c, null);
        when(arDao.selectOver90Balance("C1")).thenReturn(new BigDecimal("30000"));
        // 30% > 20% → NEED_APPROVAL + 推送信用管理员
        Map<String, Object> out = credit.check("C1", BigDecimal.ZERO, null, null, false);
        assertEquals(CreditCheck.R_NEED_APPROVAL, out.get("result"));
        verify(noticeService).push(eq("ROLE_CREDIT_ADMIN"), any(), anyString(), anyString(),
                eq("CREDIT_AGING"), any());
    }

    // ---------- 及时率与连续逾期 ----------

    @Test
    void lowOnTimeRateDowngradesRating() {
        MdmCustomerGroup c = customer("A");
        baseStubs(c, view(null));
        when(writeoffDao.selectCount12m("C1")).thenReturn(5L);
        when(writeoffDao.selectOnTimeCount12m("C1")).thenReturn(3L); // 60% < 80%
        Map<String, Object> out = credit.check("C1", BigDecimal.ZERO, null, null, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> aging = (Map<String, Object>) out.get("aging");
        assertEquals(0, new BigDecimal("0.6000").compareTo((BigDecimal) aging.get("onTimeRate")));
        // 评级 A → 下调（BR-4.3-15）并写变更台账
        assertNotEquals("A", c.getCreditRating());
        verify(versionDao).insert(any());
        @SuppressWarnings("unchecked")
        List<String> actions = (List<String>) out.get("actions");
        assertTrue(actions.stream().anyMatch(a -> a.contains("评级下调")), String.valueOf(actions));
    }

    @Test
    void overdueStreak3CutsTempLimitByHalf() {
        MdmCustomerGroup c = customer("A");
        MdmCustomerView v = view(null); // 载体存在但无临时额度 → 首次割（alreadyCut=false）
        baseStubs(c, v);
        when(writeoffDao.selectRecentOverdueCount(eq("C1"), org.mockito.ArgumentMatchers.anyInt())).thenReturn(3L);
        Map<String, Object> out = credit.check("C1", BigDecimal.ZERO, null, null, false);
        // 基数 = 集团额度 100000 → 割半 50000（BR-4.3-16）
        assertEquals(0, new BigDecimal("50000.00").compareTo(v.getTempCreditLimit()));
        assertNotNull(v.getTempExpireDate());
        verify(viewDao).updateById(v);
        @SuppressWarnings("unchecked")
        List<String> actions = (List<String>) out.get("actions");
        assertTrue(actions.stream().anyMatch(a -> a.contains("下调 50%")), String.valueOf(actions));
        @SuppressWarnings("unchecked")
        Map<String, Object> f = (Map<String, Object>) out.get("factors");
        // 重算额度 = 100000 + 50000 = 150000；available = 150000 - 50000 = 100000
        assertEquals(0, new BigDecimal("150000").compareTo((BigDecimal) f.get("creditLimit")));
        assertEquals(0, new BigDecimal("100000").compareTo((BigDecimal) f.get("available")));
    }

    // ---------- 冻结解冻状态回退 ----------

    @Test
    void unfreezeRestoresPreviousSoStatus() {
        CreditFreeze f = new CreditFreeze();
        f.setId("F1");
        f.setSoId("SO1");
        f.setSoNo("SO-1");
        f.setStatus(CreditFreeze.ST_FROZEN);
        f.setPrevStatus(So.ST_CONFIRMED);
        when(freezeDao.selectById("F1")).thenReturn(f);
        So so = new So();
        so.setId("SO1");
        so.setStatus(So.ST_CREDIT_FREEZE);
        when(soDao.selectById("SO1")).thenReturn(so);
        when(freezeDao.selectList(any())).thenReturn(List.of());

        CreditFreeze done = credit.unfreeze("F1", "PREPAY", "预收到账");
        assertEquals(CreditFreeze.ST_UNFROZEN, done.getStatus());
        assertNotNull(done.getUnfreezeNo());
        // SO 回前一稳定状态 CONFIRMED（不重跑审批）
        assertEquals(So.ST_CONFIRMED, so.getStatus());
        verify(soDao).updateById(so);

        // 幂等：再次解冻不改状态
        CreditFreeze again = credit.unfreeze("F1", "PREPAY", "重复");
        assertEquals(CreditFreeze.ST_UNFROZEN, again.getStatus());
    }

    @Test
    void unfreezeWithoutPrevFallsBackToDraft() {
        CreditFreeze f = new CreditFreeze();
        f.setId("F2");
        f.setSoId("SO2");
        f.setStatus(CreditFreeze.ST_FROZEN);
        f.setPrevStatus(null);
        when(freezeDao.selectById("F2")).thenReturn(f);
        So so = new So();
        so.setId("SO2");
        so.setStatus(So.ST_CREDIT_FREEZE);
        when(soDao.selectById("SO2")).thenReturn(so);
        when(freezeDao.selectList(any())).thenReturn(List.of());
        credit.unfreeze("F2", "SPECIAL", null);
        assertEquals(So.ST_DRAFT, so.getStatus());
    }

    // ---------- 特批上限 ----------

    private CreditFreeze frozen(String id) {
        CreditFreeze f = new CreditFreeze();
        f.setId(id);
        f.setSoId("SO1");
        f.setSoNo("SO-1");
        f.setStatus(CreditFreeze.ST_FROZEN);
        return f;
    }

    @Test
    void specialApproveEnforcesLimitAndReason() {
        when(freezeDao.selectById("F1")).thenReturn(frozen("F1"));
        when(paramService.getAmount(eq("CREDIT_SPECIAL_LIMIT"), any()))
                .thenReturn(new BigDecimal("500000"));

        // 超上限 422（BR-4.3-18）
        ServiceException over = assertThrows(ServiceException.class,
                () -> credit.specialApprove("F1", new BigDecimal("600000"), "大额特批"));
        assertTrue(over.getMessage().contains("授权上限"), over.getMessage());

        // 缺理由 422
        ServiceException noReason = assertThrows(ServiceException.class,
                () -> credit.specialApprove("F1", new BigDecimal("100000"), "  "));
        assertTrue(noReason.getMessage().contains("理由"), noReason.getMessage());

        // 上限内成功 → 冻结单解除 + 永久留痕（操作人/金额/理由写入解冻备注）
        CreditFreeze ok = credit.specialApprove("F1", new BigDecimal("300000"), "临时周转");
        assertEquals(CreditFreeze.ST_UNFROZEN, ok.getStatus());
        assertEquals(CreditFreeze.M_SPECIAL, ok.getUnfreezeMethod());
        assertTrue(ok.getUnfreezeRemark().contains("信用特批"));
        assertTrue(ok.getUnfreezeRemark().contains("临时周转"));
        verify(freezeDao).updateById(ok);

        // 冻结单不存在 404
        assertThrows(ServiceException.class,
                () -> credit.specialApprove("F-none", BigDecimal.ONE, "x"));
        verify(freezeDao, never()).updateById(argThatSameId("F-none"));
    }

    private CreditFreeze argThatSameId(String id) {
        return org.mockito.ArgumentMatchers.argThat(f -> f != null && id.equals(f.getId()));
    }
}
