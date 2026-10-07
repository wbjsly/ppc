package com.erp.sd;

import com.erp.common.ServiceException;
import com.erp.dao.crm.ContractDao;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmCustomerViewDao;
import com.erp.dao.sd.FrameworkDao;
import com.erp.dao.sd.FrameworkLineDao;
import com.erp.dao.sd.FrameworkReleaseDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.dao.sd.SoChangeDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.dao.sd.SoVersionDao;
import com.erp.dao.sd.SpecialPriceDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.sd.Framework;
import com.erp.entity.sd.FrameworkLine;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoLine;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.sd.CreditControlService;
import com.erp.service.sd.ReservationService;
import com.erp.service.impl.sd.SoConfirmSupport;
import com.erp.service.impl.sd.SoServiceImpl;
import com.erp.service.impl.sd.FrameworkServiceImpl;
import com.erp.service.mdm.MdmCrossDomainService;
import com.erp.service.system.NoticeService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 17.4 规则单测：SO 三档审批链与链尾加签、信用冻结挂起拦截、
 * 已执行行变更阻断（C-4.3-08）、框架协议超量下达阻断（C-4.3-10）。
 */
class SoFrameworkRulesUnitTest {

    /** MP lambda 缓存初始化：单测直调含 LambdaUpdateWrapper 的路径需要 TableInfo */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, So.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, SoLine.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.erp.entity.sd.Shipment.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.erp.entity.sd.ShipmentLine.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.erp.entity.inv.InvStock.class);
    }

    private SoServiceImpl soService;
    private SoDao soDao;
    private SoLineDao soLineDao;
    private ApprovalEngine approvalEngine;
    private SoConfirmSupport confirmSupport;
    private SysParamService paramService;

    @BeforeEach
    void auth() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(
                        new SimpleGrantedAuthority("ROLE_SALES"),
                        new SimpleGrantedAuthority("ROLE_SALES_MGR"),
                        new SimpleGrantedAuthority("ROLE_SALES_DIRECTOR"),
                        new SimpleGrantedAuthority("ROLE_WAREHOUSE"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));
        soDao = mock(SoDao.class);
        soLineDao = mock(SoLineDao.class);
        approvalEngine = mock(ApprovalEngine.class);
        confirmSupport = mock(SoConfirmSupport.class);
        paramService = mock(SysParamService.class);
        when(paramService.getRate(eq("MIN_MARGIN_RATE"), any())).thenReturn(new BigDecimal("0.05"));
        when(paramService.getAmount(eq("SO_APPROVAL_AUTO"), any())).thenReturn(new BigDecimal("100000"));
        when(paramService.getAmount(eq("SO_APPROVAL_MAJOR"), any())).thenReturn(new BigDecimal("2000000"));
        when(soDao.update(any(), any())).thenReturn(1);
        when(soLineDao.update(any(), any())).thenReturn(1);
    }

    /** 统一构造（beforeEach 只做 mock 字段与参数 stub） */
    private void buildService() {
        soService = new SoServiceImpl(soDao, soLineDao, mock(SoVersionDao.class),
                mock(SoChangeDao.class), mock(com.erp.dao.mdm.MdmItemDao.class),
                mock(MdmCustomerGroupDao.class), mock(MdmCustomerViewDao.class),
                mock(SpecialPriceDao.class), mock(MdmCrossDomainService.class),
                approvalEngine, paramService, mock(CreditControlService.class),
                mock(ReservationService.class), confirmSupport, mock(NoticeService.class),
                mock(ContractDao.class));
    }

    private So so(String status, String amount) {
        So s = new So();
        s.setId("SO1");
        s.setSoNo("SO-T1");
        s.setStatus(status);
        s.setTotalAmount(new BigDecimal(amount));
        s.setMarginRate(new BigDecimal("0.30"));
        s.setCustomerId("C1");
        return s;
    }

    private SoLine line(String priceLocked) {
        SoLine l = new SoLine();
        l.setId("L1");
        l.setSoId("SO1");
        l.setLineNo(1);
        l.setItemCode("RM1");
        l.setQty(new BigDecimal("100"));
        l.setPriceLocked(priceLocked);
        return l;
    }

    private void stubSo(So s, SoLine... lines) {
        when(soDao.selectById("SO1")).thenReturn(s);
        List<SoLine> ls = new ArrayList<>(List.of(lines));
        when(soLineDao.selectList(any())).thenReturn(ls);
        when(soDao.updateById(any(So.class))).thenReturn(1);
        ApprovalInstance inst = new ApprovalInstance();
        inst.setId("AP-1");
        when(approvalEngine.submit(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(inst);
    }

    // ---------- 三档审批链与加签 ----------

    @Test
    void smallOrderAutoConfirmsWithoutChain() {
        buildService();
        stubSo(so(So.ST_DRAFT, "50000"), line(null));
        Map<String, Object> res = soService.submit("SO1");
        assertEquals("AUTO", res.get("mode"));
        verify(confirmSupport).confirm(eq("SO1"), anyString());
        // 自动档不进审批引擎
        verify(approvalEngine, org.mockito.Mockito.never())
                .submit(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void midOrderSingleNodeChain() {
        buildService();
        stubSo(so(So.ST_DRAFT, "500000"), line(null));
        Map<String, Object> res = soService.submit("SO1");
        @SuppressWarnings("unchecked")
        List<List<ApprovalNodeSpec>> chain = (List<List<ApprovalNodeSpec>>) res.get("chain");
        assertEquals(1, chain.size());
        assertEquals(1, chain.get(0).size());
        assertTrue(String.valueOf(res.get("basis")).contains("金额"),
                String.valueOf(res.get("basis")));
        verify(approvalEngine).submit(eq("So"), eq("SO1"), anyString(), eq(null), any());
    }

    @Test
    void majorOrderTwoStageChain() {
        buildService();
        stubSo(so(So.ST_DRAFT, "3000000"), line(null));
        Map<String, Object> res = soService.submit("SO1");
        @SuppressWarnings("unchecked")
        List<List<ApprovalNodeSpec>> chain = (List<List<ApprovalNodeSpec>>) res.get("chain");
        // 双节点：销售经理 → 销售总监
        assertEquals(2, chain.size());
        assertEquals(1, chain.get(0).size());
        assertEquals(1, chain.get(1).size());
        assertTrue(String.valueOf(res.get("basis")).contains("金额"),
                String.valueOf(res.get("basis")));
    }

    @Test
    void priceChangeLineAddsReviewNode() {
        buildService();
        stubSo(so(So.ST_DRAFT, "500000"), line("1"));
        Map<String, Object> res = soService.submit("SO1");
        @SuppressWarnings("unchecked")
        List<List<ApprovalNodeSpec>> chain = (List<List<ApprovalNodeSpec>>) res.get("chain");
        // 基础 1 段 + 价格变更复核加签 1 段（BR-4.3-26）
        assertEquals(2, chain.size());
        assertTrue(String.valueOf(res.get("basis")).contains("改价行"),
                String.valueOf(res.get("basis")));
    }

    @Test
    void creditFrozenOrderCannotSubmit() {
        buildService();
        stubSo(so(So.ST_CREDIT_FREEZE, "50000"), line(null));
        ServiceException e = assertThrows(ServiceException.class, () -> soService.submit("SO1"));
        assertTrue(e.getMessage().contains("信用冻结"), e.getMessage());
        verify(approvalEngine, org.mockito.Mockito.never())
                .submit(anyString(), anyString(), anyString(), any(), any());
    }

    // ---------- 已执行行变更阻断（C-4.3-08） ----------

    @Test
    void executedLineChangeIsBlocked() {
        buildService();
        So s = so(So.ST_CONFIRMED, "100000");
        stubSo(s);
        SoLine executed = line(null);
        executed.setShippedQty(new BigDecimal("50"));
        when(soLineDao.selectById("L1")).thenReturn(executed);

        // 取消已执行行 → 422
        ServiceException cancel = assertThrows(ServiceException.class,
                () -> soService.change("SO1",
                        List.of(Map.of("id", "L1", "cancel", true)), "试取消"));
        assertTrue(cancel.getMessage().contains("已执行行不可变更"), cancel.getMessage());

        // 改量到已发量以下 → 422（变更仅作用于未执行部分）
        // 上一次调用已把 mock so 状态改为 CHANGING（单测无事务回滚）→ 重建 CONFIRMED 态
        stubSo(so(So.ST_CONFIRMED, "100000"));
        when(soLineDao.selectById("L1")).thenReturn(executed);
        ServiceException shrink = assertThrows(ServiceException.class,
                () -> soService.change("SO1",
                        List.of(Map.of("id", "L1", "qty", 30)), "试减量"));
        assertTrue(shrink.getMessage().contains("已执行行不可变更"), shrink.getMessage());
    }

    @Test
    void changeRequiresReasonAndConfirmedStatus() {
        buildService();
        stubSo(so(So.ST_DRAFT, "100000"));
        ServiceException noReason = assertThrows(ServiceException.class,
                () -> soService.change("SO1", List.of(), " "));
        assertTrue(noReason.getMessage().contains("原因"), noReason.getMessage());

        stubSo(so(So.ST_DRAFT, "100000"));
        ServiceException notConfirmed = assertThrows(ServiceException.class,
                () -> soService.change("SO1", List.of(), "正常变更"));
        assertTrue(notConfirmed.getMessage().contains("仅已确认"), notConfirmed.getMessage());
    }

    // ---------- 框架协议超量下达（C-4.3-10） ----------

    @Test
    void frameworkReleaseOverRemainBlocked() {
        FrameworkDao fwDao = mock(FrameworkDao.class);
        FrameworkLineDao lineDao = mock(FrameworkLineDao.class);
        FrameworkServiceImpl fwService = new FrameworkServiceImpl(fwDao, lineDao,
                mock(FrameworkReleaseDao.class), mock(MdmCustomerGroupDao.class),
                mock(MdmItemDao.class), mock(ApprovalEngine.class),
                mock(com.erp.service.sd.ShipmentService.class));

        Framework fw = new Framework();
        fw.setId("FW1");
        fw.setFwNo("FW-T1");
        fw.setStatus(Framework.ST_EFFECTIVE);
        fw.setEffectiveDate(LocalDate.of(2026, 1, 1));
        fw.setExpireDate(LocalDate.of(2026, 12, 31));
        fw.setCustomerId("C1");
        when(fwDao.selectById("FW1")).thenReturn(fw);

        FrameworkLine ln = new FrameworkLine();
        ln.setId("FL1");
        ln.setFrameworkId("FW1");
        ln.setLineNo(1);
        ln.setItemCode("RM1");
        ln.setTotalQty(new BigDecimal("1000"));
        ln.setReleasedQty(new BigDecimal("950")); // 剩余 50
        when(lineDao.selectById("FL1")).thenReturn(ln);

        // 100 > 50 → C-4.3-10 硬阻断（不允许部分越量放行）
        ServiceException e = assertThrows(ServiceException.class,
                () -> fwService.release("FW1", "FL1", new BigDecimal("100"),
                        LocalDate.of(2026, 11, 1)));
        assertTrue(e.getMessage().contains("C-4.3-10"), e.getMessage());
        assertTrue(e.getMessage().contains("剩余 50"), e.getMessage());

        // 负数/零量拒绝
        ServiceException zero = assertThrows(ServiceException.class,
                () -> fwService.release("FW1", "FL1", BigDecimal.ZERO, null));
        assertTrue(zero.getMessage().contains("大于 0"), zero.getMessage());
    }
}
