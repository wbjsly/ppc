package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmCustomerViewDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.sd.SoChangeDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.dao.sd.SoVersionDao;
import com.erp.dao.sd.SpecialPriceDao;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmCustomerView;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoChange;
import com.erp.entity.sd.SoLine;
import com.erp.entity.sd.SoVersion;
import com.erp.entity.sd.SpecialPrice;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.sd.CreditControlService;
import com.erp.service.sd.ReservationService;
import com.erp.service.sd.SoService;
import com.erp.service.SysParamService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 销售订单实现（tasks 7.2~7.10，spec sales-order）。
 *
 * 关键口径（探索第 23 题拍板 = 证据 A 主线）：
 *  - 建单即跑价格协议绑定（无协议 L1 阻断）；付款条件差异须销售主管确认后方可提交审批；
 *  - 提交审批按金额三档 + 两类链尾加签；小额高毛利免审直接确认；
 *  - 确认动作在 {@code SoConfirmSupport}：锁批次预留 + SO.CONFIRMED 事件，锁不足整体回滚；
 *  - 挂起态（信用冻结/变更中）解除回前一稳定状态（业务逻辑 6）；
 *  - 变更重跑信用（超限重新冻结），已发货/已开票行 L1 阻断。
 */
@Slf4j
@Service
public class SoServiceImpl implements SoService {

    /** 需求确认阶段的订单类型白名单（4.3：标准 / 寄售 / 样品） */
    private static final List<String> ORDER_TYPES = List.of("STANDARD", "CONSIGN", "SAMPLE");
    private static final BigDecimal MIN_MARGIN_DEFAULT = new BigDecimal("0.05");

    private final SoDao soDao;
    private final SoLineDao soLineDao;
    private final SoVersionDao versionDao;
    private final SoChangeDao changeDao;
    private final MdmItemDao itemDao;
    private final MdmCustomerGroupDao customerDao;
    private final MdmCustomerViewDao viewDao;
    private final SpecialPriceDao specialDao;
    private final com.erp.service.mdm.MdmCrossDomainService crossDomain;
    private final ApprovalEngine approvalEngine;
    private final SysParamService paramService;
    private final CreditControlService creditControlService;
    private final ReservationService reservationService;
    private final SoConfirmSupport confirmSupport;
    private final NoticeService noticeService;
    private final com.erp.dao.crm.ContractDao contractDao;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public SoServiceImpl(SoDao soDao,
                         SoLineDao soLineDao,
                         SoVersionDao versionDao,
                         SoChangeDao changeDao,
                         MdmItemDao itemDao,
                         MdmCustomerGroupDao customerDao,
                         MdmCustomerViewDao viewDao,
                         SpecialPriceDao specialDao,
                         com.erp.service.mdm.MdmCrossDomainService crossDomain,
                         ApprovalEngine approvalEngine,
                         SysParamService paramService,
                         CreditControlService creditControlService,
                         ReservationService reservationService,
                         SoConfirmSupport confirmSupport,
                         NoticeService noticeService,
                         com.erp.dao.crm.ContractDao contractDao) {
        this.soDao = soDao;
        this.soLineDao = soLineDao;
        this.versionDao = versionDao;
        this.changeDao = changeDao;
        this.itemDao = itemDao;
        this.customerDao = customerDao;
        this.viewDao = viewDao;
        this.specialDao = specialDao;
        this.crossDomain = crossDomain;
        this.approvalEngine = approvalEngine;
        this.paramService = paramService;
        this.creditControlService = creditControlService;
        this.reservationService = reservationService;
        this.confirmSupport = confirmSupport;
        this.noticeService = noticeService;
        this.contractDao = contractDao;
    }

    // ---------- 7.2/7.3/7.4 录入 ----------

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> header, List<Map<String, Object>> lineMaps) {
        requireAny("创建订单", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        MdmCustomerGroup customer = requireCustomer(str(header.get("customerId")));
        String orderType = nvl(str(header.get("orderType")), "STANDARD");
        if (!ORDER_TYPES.contains(orderType)) {
            throw new ServiceException(422, "订单类型仅支持 标准/寄售/样品（" + orderType + "）");
        }

        So so = new So();
        so.setSoNo(nextNo("SO"));
        so.setSourceType(So.SRC_MANUAL);
        so.setOppId(str(header.get("oppId")));
        so.setCustomerId(customer.getId());
        so.setCustomerCode(customer.getCustomerCode());
        so.setCustomerName(customer.getCustomerName());
        so.setOrderType(orderType);
        so.setPaymentTerms(str(header.get("paymentTerms")));
        so.setTradeTerms(str(header.get("tradeTerms")));
        so.setStatus(So.ST_DRAFT);
        so.setRemark(str(header.get("remark")));
        so.setEventVersion(0);
        // 14.3 合同 ↔ SO 关联（D10 链路）：SIGNED 合同方可下达，回写 SO_COUNT
        String contractId = str(header.get("contractId"));
        com.erp.entity.crm.Contract contract = null;
        if (isNotBlank(contractId)) {
            contract = contractDao.selectById(contractId);
            if (contract == null) {
                throw new ServiceException(404, "关联合同不存在：" + contractId);
            }
            if (!com.erp.entity.crm.Contract.ST_SIGNED.equals(contract.getStatus())) {
                throw new ServiceException(422, "仅已签订合同可下达 SO（当前："
                        + contract.getStatus() + "）");
            }
            if (!customer.getId().equals(contract.getCustomerId())) {
                throw new ServiceException(422, "合同客户与订单客户不一致，禁止关联合同");
            }
            so.setContractId(contract.getId());
            so.setContractNo(contract.getContractNo());
        }
        soDao.insert(so);
        if (contract != null) {
            com.erp.service.impl.crm.ContractServiceImpl.bumpSoCount(contractDao,
                    contract.getId());
        }

        List<SoLine> lines = buildLines(so, lineMaps);
        recalc(so, lines);
        soDao.updateById(so);
        saveVersion(so, SoVersion.OP_CREATE, "手工创建（含价格协议绑定）");
        // 6.5 建单即检（拍板甲：含本次金额；未过 → SO 置信用冻结挂起，不可提交审批）
        try {
            Map<String, Object> credit = creditControlService.checkOnSoCreate(so.getId());
            if (Boolean.TRUE.equals(credit.get("frozen"))) {
                log.info("SO {} frozen on create (gap={})", so.getSoNo(), credit.get("gap"));
            }
        } catch (Exception e) {
            log.warn("credit check on SO create failed: {}", e.getMessage());
        }
        log.info("SO {} created with {} lines, amount={}", so.getSoNo(),
                lines.size(), so.getTotalAmount());
        return detail(so.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> update(String soId, Map<String, Object> header,
                                      List<Map<String, Object>> lineMaps) {
        requireAny("修改订单", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        So so = require(soId);
        if (!So.ST_DRAFT.equals(so.getStatus())) {
            throw new ServiceException(422, "仅草稿订单可修改（当前：" + so.getStatus() + "）");
        }
        if (str(header.get("paymentTerms")) != null) {
            so.setPaymentTerms(str(header.get("paymentTerms")));
        }
        if (str(header.get("tradeTerms")) != null) {
            so.setTradeTerms(str(header.get("tradeTerms")));
        }
        if (str(header.get("remark")) != null) {
            so.setRemark(str(header.get("remark")));
        }
        // 草稿改行：删除重建（重新取价与卡控，同 create 口径）
        soLineDao.delete(new LambdaQueryWrapper<SoLine>().eq(SoLine::getSoId, soId));
        List<SoLine> lines = buildLines(so, lineMaps);
        recalc(so, lines);
        soDao.updateById(so);
        saveVersion(so, SoVersion.OP_CREATE, "草稿修改（重新取价）");
        return detail(so.getId());
    }

    // ---------- 7.5 手动改价锁行 ----------

    @Override
    @Transactional
    public SoLine changePrice(String lineId, BigDecimal newPrice, String reason) {
        requireAny("修改订单价格", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        if (newPrice == null || newPrice.signum() <= 0) {
            throw new ServiceException(422, "新单价须大于 0");
        }
        if (isBlank(reason)) {
            throw new ServiceException(422, "改价原因必填（BR-4.3-26）");
        }
        SoLine line = soLineDao.selectById(lineId);
        if (line == null) {
            throw new ServiceException(404, "订单行不存在");
        }
        So so = require(line.getSoId());
        if (!So.ST_DRAFT.equals(so.getStatus())) {
            throw new ServiceException(422, "仅草稿订单可改价（审批/确认后须走订单变更）");
        }
        if (line.getOriginalUnitPrice() == null) {
            line.setOriginalUnitPrice(line.getUnitPrice());
        }
        BigDecimal oldPrice = line.getUnitPrice();
        line.setUnitPrice(newPrice);
        line.setPriceLocked("1");
        soLineDao.updateById(line);

        // 锁行生效：审批任务在 submit 时挂到主链「价格变更复核」加签节点（BR-4.3-26），
        // 通过前 SoConfirmSupport 拒绝确认——避免独立实例与主链并行的死锁时序
        soLineDao.updateById(line);

        recalc(so, soLines(so.getId()));
        soDao.updateById(so);
        recordChange(so.getId(), line.getLineNo(), "UNIT_PRICE",
                String.valueOf(oldPrice), String.valueOf(newPrice),
                null, "手动改价：" + reason + "（锁行待价格变更审批）");
        log.info("SO {} line {} price changed {} -> {} (locked)", so.getSoNo(),
                line.getLineNo(), oldPrice, newPrice);
        return line;
    }

    // ---------- 7.6 金额三档审批 + 两类链尾加签 ----------

    @Override
    @Transactional
    public Map<String, Object> submit(String soId) {
        requireAny("提交订单审批", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        So so = require(soId);
        if (So.ST_CREDIT_FREEZE.equals(so.getStatus())) {
            throw new ServiceException(422, "订单处于信用冻结挂起态，请先完成预收到账"
                    + "或信用特批解冻后再提交（拍板甲：冻结卡在审批前）");
        }
        if (!So.ST_DRAFT.equals(so.getStatus())) {
            throw new ServiceException(422, "仅草稿订单可提交审批（当前：" + so.getStatus() + "）");
        }
        List<SoLine> lines = soLines(soId);
        if (lines.isEmpty()) {
            throw new ServiceException(422, "订单无明细行");
        }
        // 7.4 付款条件差异未确认 → 拦截（BR-4.3-25）
        Map<String, Object> diff = payTermsDiff(so);
        if (Boolean.TRUE.equals(diff.get("differs")) && isBlank(so.getPayConfirmBy())) {
            throw new ServiceException(422, "付款条件与客户协议不一致（"
                    + so.getPaymentTerms() + " vs 协议 " + diff.get("expected")
                    + "），须销售主管确认后方可提交（BR-4.3-25）");
        }
        // 毛利阈值：低于最低毛利且无有效特批 → 阻断（C-4.3-03 口径）
        BigDecimal minMargin = paramService.getRate("MIN_MARGIN_RATE", MIN_MARGIN_DEFAULT);
        BigDecimal margin = so.getMarginRate() == null ? BigDecimal.ZERO : so.getMarginRate();
        boolean lowMargin = margin.compareTo(minMargin) < 0;
        boolean hasSpecial = lowMargin && hasValidSpecial(so, lines);

        BigDecimal autoLimit = paramService.getAmount("SO_APPROVAL_AUTO", new BigDecimal("100000"));
        BigDecimal majorLimit = paramService.getAmount("SO_APPROVAL_MAJOR", new BigDecimal("2000000"));
        boolean hasPriceChange = lines.stream()
                .anyMatch(l -> "1".equals(l.getPriceLocked()));
        String chainBasis = "金额 " + so.getTotalAmount()
                + (hasPriceChange ? " + 改价行" : "")
                + (lowMargin ? " + 低毛利(" + margin + ")" : "");

        // 自动档：小额 且 无改价 且 毛利达标 → 免审直接确认（同一确认动作，7.7）
        if (so.getTotalAmount().compareTo(autoLimit) < 0 && !hasPriceChange && !lowMargin) {
            confirmSupport.confirm(soId, currentUser());
            saveVersion(so, SoVersion.OP_APPROVE, "小额自动确认（" + chainBasis + "）");
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("so", detail(soId));
            res.put("mode", "AUTO");
            res.put("chain", List.of());
            res.put("basis", chainBasis);
            return res;
        }
        if (lowMargin && !hasSpecial) {
            throw new ServiceException(422, "毛利率 " + margin.multiply(new BigDecimal("100"))
                    + "% 低于最低毛利阈值，须先取得有效《特殊价格审批单》再提交（C-4.3-03 / S-4.3-08）");
        }

        // 三档基础链
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        if (so.getTotalAmount().compareTo(majorLimit) > 0) {
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售经理审批")));
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_SALES_DIRECTOR", "销售总监审批")));
        } else {
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售经理审批")));
        }
        // 链尾加签 ①：低毛利且已持有效特批单 → 财务节点
        if (lowMargin && hasSpecial) {
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "低毛利财务复核（持特批单）")));
        }
        // 链尾加签 ②：存在行级手动改价 → 价格变更复核（BR-4.3-26）
        if (hasPriceChange) {
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "价格变更复核")));
        }

        var instance = approvalEngine.submit("So", soId,
                "销售订单审批 " + so.getSoNo() + "（" + chainBasis + "）",
                null, chain);
        so.setStatus(So.ST_PENDING);
        soDao.updateById(so);
        soDao.update(null, new LambdaUpdateWrapper<So>()
                .eq(So::getId, soId)
                .set(So::getApprovalId, instance.getId()));
        // 锁行回填主链审批实例（展示与追溯用；解锁发生在主链通过的回调里）
        if (hasPriceChange) {
            soLineDao.update(null, new LambdaUpdateWrapper<SoLine>()
                    .eq(SoLine::getSoId, soId)
                    .eq(SoLine::getPriceLocked, "1")
                    .set(SoLine::getPriceApprovalId, instance.getId()));
        }
        saveVersion(so, SoVersion.OP_APPROVE, "提交审批：" + chainBasis
                + "，节点数 " + chain.size());
        recordChange(soId, null, "APPROVAL", "DRAFT", "PENDING", null,
                "提交审批（" + chainBasis + "，" + chain.size() + " 段节点）");

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("so", detail(soId));
        res.put("mode", "APPROVAL");
        res.put("chain", chain);
        res.put("basis", chainBasis);
        return res;
    }

    // ---------- 7.4 付款条件确认 / 交期确认 ----------

    @Override
    @Transactional
    public So confirmPaymentTerms(String soId, String reason) {
        requireAny("确认付款条件差异", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR", "ROLE_ADMIN");
        if (isBlank(reason)) {
            throw new ServiceException(422, "确认原因必填（BR-4.3-25）");
        }
        So so = require(soId);
        so.setPayConfirmBy(currentUser());
        so.setPayConfirmAt(LocalDateTime.now());
        so.setPayConfirmReason(reason);
        soDao.updateById(so);
        recordChange(soId, null, "PAYMENT_TERMS", null, so.getPaymentTerms(), null,
                "付款条件差异经销售主管确认：" + reason);
        return so;
    }

    @Override
    @Transactional
    public SoLine ackDelivery(String lineId) {
        requireAny("交期客户确认回执", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        SoLine line = soLineDao.selectById(lineId);
        if (line == null) {
            throw new ServiceException(404, "订单行不存在");
        }
        line.setDeliveryPending("0");
        soLineDao.updateById(line);
        recordChange(line.getSoId(), line.getLineNo(), "DELIVERY_ACK", "待确认", "客户已确认",
                null, "交期客户确认回执（BR-4.3-28）");
        return line;
    }

    // ---------- 7.9 SO 变更 ----------

    @Override
    @Transactional
    public Map<String, Object> change(String soId, List<Map<String, Object>> lineMaps,
                                      String reason) {
        requireAny("变更订单", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR");
        if (isBlank(reason)) {
            throw new ServiceException(422, "变更原因必填");
        }
        So so = require(soId);
        if (!So.ST_CONFIRMED.equals(so.getStatus())) {
            throw new ServiceException(422, "仅已确认订单可变更（草稿请直接修改；当前："
                    + so.getStatus() + "）");
        }
        String stableStatus = so.getStatus();   // 挂起前的稳定状态（业务逻辑 6）

        // 挂起态：变更中（同步流程内留痕，事务结束即回终态）
        so.setPrevStatus(stableStatus);
        so.setStatus(So.ST_CHANGING);
        soDao.updateById(so);

        for (Map<String, Object> m : lineMaps) {
            String lineId = str(m.get("id"));
            SoLine line = soLineDao.selectById(lineId);
            if (line == null || !soId.equals(line.getSoId())) {
                throw new ServiceException(404, "订单行不存在：" + lineId);
            }
            boolean executed = nvl(line.getShippedQty()).signum() > 0
                    || nvl(line.getInvoicedQty()).signum() > 0;
            boolean cancel = Boolean.TRUE.equals(m.get("cancel")) || "1".equals(str(m.get("cancel")));

            if (cancel) {
                // C-4.3-08：已发货/已开票行不可变更（含取消）
                if (executed) {
                    throw new ServiceException(422, "已执行行不可变更（行 " + line.getLineNo()
                            + " 已发/已开票），变更仅作用于未执行部分（C-4.3-08）");
                }
                line.setLineStatus(SoLine.LS_CANCELLED);
                reservationService.releaseByLine(line.getId(), BigDecimal.ZERO, "行取消");
                soLineDao.updateById(line);
                recordChange(soId, line.getLineNo(), "LINE_CANCEL", "OPEN", "CANCELLED",
                        null, reason);
                continue;
            }

            // 数量变更
            Object qtyObj = m.get("qty");
            if (qtyObj != null) {
                BigDecimal newQty = dec(qtyObj);
                if (newQty == null || newQty.signum() <= 0) {
                    throw new ServiceException(422, "变更后数量须大于 0");
                }
                if (executed && newQty.compareTo(line.getShippedQty() == null
                        ? BigDecimal.ZERO : line.getShippedQty()) < 0) {
                    throw new ServiceException(422, "已执行行不可变更（行 " + line.getLineNo()
                            + " 已发量 " + line.getShippedQty() + "），变更仅作用于未执行部分（C-4.3-08）");
                }
                BigDecimal oldQty = line.getQty();
                line.setQty(newQty);
                soLineDao.updateById(line);
                if (newQty.compareTo(oldQty) > 0) {
                    // 增量：重锁批次（锁不足 → 422 整体回滚 = ATP 重试算硬约束）
                    reservationService.reserveForSo(so, List.of(line));
                } else {
                    reservationService.releaseByLine(line.getId(), newQty, "变更减量");
                }
                recordChange(soId, line.getLineNo(), "QTY",
                        String.valueOf(oldQty), String.valueOf(newQty), null, reason);
            }

            // 交期变更
            Object plan = m.get("planShipDate");
            Object expect = m.get("customerExpectDate");
            if (plan != null || expect != null) {
                LocalDate newPlan = plan != null ? LocalDate.parse(str(plan)) : line.getPlanShipDate();
                LocalDate oldPlan = line.getPlanShipDate();
                if (expect != null) {
                    line.setCustomerExpectDate(LocalDate.parse(str(expect)));
                }
                line.setPlanShipDate(newPlan);
                line.setDeliveryPending(computePending(line, null));
                soLineDao.updateById(line);
                recordChange(soId, line.getLineNo(), "PLAN_SHIP_DATE",
                        String.valueOf(oldPlan), String.valueOf(newPlan), null, reason);
            }
        }

        // 重跑信用（先回稳定状态再跑，冻结时 PREV_STATUS 才记得到"已确认"——业务逻辑 6）
        so = require(soId);
        so.setStatus(stableStatus);
        so.setPrevStatus(null);
        soDao.updateById(so);
        Map<String, Object> credit;
        try {
            credit = creditControlService.recheckSo(soId);
        } catch (Exception e) {
            throw new ServiceException(422, "变更后信用重跑失败：" + e.getMessage());
        }
        String result = str(credit.get("result"));
        String rerun = SoChange.RERUN_PASS;
        if ("FROZEN".equals(result)) {
            rerun = SoChange.RERUN_FROZEN;
        } else if ("NEED_APPROVAL".equals(result)) {
            rerun = SoChange.RERUN_NEED_APPROVAL;
        }
        recordChange(soId, null, "CREDIT_RERUN", stableStatus,
                "FROZEN".equals(result) ? So.ST_CREDIT_FREEZE : stableStatus, rerun,
                reason + "（变更重跑信用：" + result + "，BR-4.3-30）");
        saveVersion(so, SoVersion.OP_CHANGE, "订单变更：" + reason + "，信用重跑 " + rerun);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("so", detail(soId));
        res.put("creditRerun", rerun);
        res.put("frozen", "FROZEN".equals(result));
        res.put("status", require(soId).getStatus());
        return res;
    }

    // ---------- 7.10 订单关闭 ----------

    @Override
    @Transactional
    public So close(String soId, String reason) {
        // 手动关闭必填原因并经销售经理确认（当前操作人即确认人）
        requireAny("关闭订单", "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR", "ROLE_ADMIN");
        if (isBlank(reason)) {
            throw new ServiceException(422, "关闭原因必填（3.5.4 手动关闭）");
        }
        So so = require(soId);
        if (So.ST_CLOSED.equals(so.getStatus()) || So.ST_CANCELLED.equals(so.getStatus())) {
            throw new ServiceException(422, "订单已" + (So.ST_CLOSED.equals(so.getStatus()) ? "关闭" : "取消"));
        }
        // 未执行余量显式处理：逐行记录剩余未发量（留痕到变更台账）
        List<SoLine> lines = soLines(soId);
        BigDecimal remainTotal = BigDecimal.ZERO;
        for (SoLine l : lines) {
            if (SoLine.LS_CANCELLED.equals(l.getLineStatus())) {
                continue;
            }
            BigDecimal remain = nvl(l.getQty()).subtract(nvl(l.getShippedQty()));
            if (remain.signum() > 0) {
                remainTotal = remainTotal.add(remain);
                recordChange(soId, l.getLineNo(), "CLOSE_REMAIN",
                        String.valueOf(l.getQty()), String.valueOf(remain), null,
                        "关闭余量处理（未发 " + remain + " " + nvlStr(l.getBaseUnit()) + "）：" + reason);
            }
        }
        // 释放未消耗预留（ATP 回升）
        int released = reservationService.releaseBySo(soId, "订单关闭");

        so.setStatus(So.ST_CLOSED);
        so.setCloseReason(reason);
        so.setClosedBy(currentUser());
        so.setClosedAt(LocalDateTime.now());
        so.setCloseConfirmBy(currentUser());
        so.setPrevStatus(null);
        soDao.updateById(so);
        soDao.update(null, new LambdaUpdateWrapper<So>()
                .eq(So::getId, soId)
                .set(So::getApprovalId, null));
        for (SoLine l : lines) {
            if (!SoLine.LS_CANCELLED.equals(l.getLineStatus())
                    && nvl(l.getQty()).subtract(nvl(l.getShippedQty())).signum() > 0) {
                l.setLineStatus(SoLine.LS_CANCELLED);
                soLineDao.updateById(l);
            }
        }
        saveVersion(so, SoVersion.OP_CLOSE, "关闭：" + reason + "（释放预留 " + released + " 条）");
        log.info("SO {} closed by {}, remain={}, released={}", so.getSoNo(),
                currentUser(), remainTotal, released);
        return so;
    }

    @Override
    public void reopen(String soId) {
        // 7.8：已关闭 SO 仅可查询不可重启
        So so = require(soId);
        if (So.ST_CLOSED.equals(so.getStatus())) {
            throw new ServiceException(422, "已关闭订单不可重启，请新建 SO 并关联原单号 "
                    + so.getSoNo() + "（业务逻辑 6）");
        }
        throw new ServiceException(422, "仅已关闭订单存在此语义，当前状态：" + so.getStatus());
    }

    // ---------- 查询 ----------

    @Override
    public Page<So> page(long current, long size, String keyword, String status,
                         String customerId) {
        LambdaQueryWrapper<So> w = new LambdaQueryWrapper<So>()
                .eq(isNotBlank(status), So::getStatus, status)
                .eq(isNotBlank(customerId), So::getCustomerId, customerId)
                .and(isNotBlank(keyword), x -> x
                        .like(So::getSoNo, keyword)
                        .or().like(So::getCustomerName, keyword))
                .orderByDesc(So::getCreateDate);
        Page<So> p = soDao.selectPage(new Page<>(current, size), w);
        for (So so : p.getRecords()) {
            decorate(so);
        }
        return p;
    }

    @Override
    public Map<String, Object> detail(String soId) {
        So so = require(soId);
        List<SoLine> lines = soLines(soId);
        Map<String, Object> out = new LinkedHashMap<>();
        decorate(so);
        out.put("so", so);
        out.put("lines", lines);
        // 《价格协议匹配记录》（FR-4.3-4-3：优先级输出命中清单）
        List<Map<String, Object>> priceMatch = new ArrayList<>();
        for (SoLine l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineNo", l.getLineNo());
            m.put("itemCode", l.getItemCode());
            m.put("qty", l.getQty());
            m.put("unitPrice", l.getUnitPrice());
            m.put("priceSource", l.getPriceSource());
            m.put("paCode", l.getPaCode());
            m.put("matched", l.getPaCode() != null);
            m.put("priceLocked", "1".equals(l.getPriceLocked()));
            priceMatch.add(m);
        }
        out.put("priceMatch", priceMatch);
        out.put("payTermsDiff", payTermsDiff(so));
        // 活跃冻结摘要
        out.put("suspended", So.SUSPENDED.contains(so.getStatus()));
        return out;
    }

    @Override
    public Map<String, Object> approvalLogs(String soId) {
        require(soId);
        return approvalEngine.logs("So", soId);
    }

    // ---------- internals ----------

    /** 逐行取价与卡控（7.3 协议绑定 + 7.4 交期标记） */
    private List<SoLine> buildLines(So so, List<Map<String, Object>> lineMaps) {
        if (lineMaps == null || lineMaps.isEmpty()) {
            throw new ServiceException(422, "订单明细不能为空");
        }
        List<SoLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (Map<String, Object> m : lineMaps) {
            String itemCode = str(m.get("itemCode"));
            BigDecimal qty = dec(m.get("qty"));
            if (isBlank(itemCode)) {
                throw new ServiceException(422, "行 " + lineNo + "：SKU 必填");
            }
            if (qty == null || qty.signum() <= 0) {
                throw new ServiceException(422, "行 " + lineNo + "：数量须大于 0");
            }
            MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, itemCode).last("LIMIT 1"));
            if (item == null) {
                throw new ServiceException(422, "行 " + lineNo + "：物料不存在 " + itemCode);
            }
            // 7.3 价格协议绑定：客户专属 > 量价阶梯 > 时间促销（trial 内部优先级）
            Map<String, Object> trial;
            try {
                trial = crossDomain.trial(so.getCustomerId(), null, itemCode, qty, LocalDate.now());
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                throw new ServiceException(422, "行 " + lineNo + "：取价失败（" + e.getMessage() + "）");
            }
            if (!Boolean.TRUE.equals(trial.get("matched"))) {
                throw new ServiceException(422, "行 " + lineNo + "：" + itemCode
                        + " 无生效价格协议，无法建单，请先维护价格协议或提交特殊价格申请（BR-4.3-27 / FR-4.3-4-3）");
            }
            BigDecimal unitPrice = dec(trial.get("unitPrice"));
            if (unitPrice == null) {
                throw new ServiceException(422, "行 " + lineNo + "：取价结果不可解析");
            }

            SoLine line = new SoLine();
            line.setId(uuid());
            line.setSoId(so.getId());
            line.setLineNo(lineNo++);
            line.setItemCode(itemCode);
            line.setItemName(item.getItemName());
            line.setQty(qty);
            line.setBaseUnit(item.getBaseUnit());
            line.setUnitPrice(unitPrice);
            line.setOriginalUnitPrice(unitPrice);
            line.setPriceSource(str(trial.get("agreementType")));
            line.setPaCode(str(trial.get("paCode")));
            line.setAmount(unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP));
            line.setPriceLocked("0");
            line.setLineStatus(SoLine.LS_OPEN);
            line.setShippedQty(BigDecimal.ZERO);
            line.setInvoicedQty(BigDecimal.ZERO);
            line.setReservedQty(BigDecimal.ZERO);
            line.setWarehouseCode(nvl(str(m.get("warehouseCode")), "WH-MAIN"));
            line.setPlanShipDate(dateOrNull(m.get("planShipDate")));
            line.setRemark(str(m.get("remark")));

            // 7.4 交期：计划发货晚于客户期望且未确认 → 标记待确认 + 推送提醒
            String expect = str(m.get("customerExpectDate"));
            if (expect != null) {
                line.setCustomerExpectDate(LocalDate.parse(expect));
            }
            line.setDeliveryPending(computePending(line, expect));
            if ("1".equals(line.getDeliveryPending())) {
                noticeService.push("ROLE_SALES", null,
                        "交期协商提醒：订单 " + so.getSoNo() + " 行 " + line.getLineNo(),
                        "计划发货 " + line.getPlanShipDate() + " 晚于客户期望交期 "
                                + line.getCustomerExpectDate() + "，请与客户确认（BR-4.3-28）",
                        "SO_DELIVERY_PENDING", line.getId());
            }
            soLineDao.insert(line);
            lines.add(line);
        }
        return lines;
    }

    private String computePending(SoLine line, String expectRaw) {
        LocalDate expect = line.getCustomerExpectDate();
        if (line.getPlanShipDate() != null && expect != null
                && line.getPlanShipDate().isAfter(expect)) {
            return "1";
        }
        return "0";
    }

    /** 金额与毛利率重算（7.6 链档输入） */
    private void recalc(So so, List<SoLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (SoLine l : lines) {
            total = total.add(nvl(l.getAmount()));
            MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, l.getItemCode()).last("LIMIT 1"));
            BigDecimal unitCost = item == null || item.getStandardCost() == null
                    ? BigDecimal.ZERO : item.getStandardCost();
            cost = cost.add(unitCost.multiply(nvl(l.getQty())).setScale(2, RoundingMode.HALF_UP));
        }
        so.setTotalAmount(total);
        if (total.signum() > 0) {
            so.setMarginRate(total.subtract(cost).divide(total, 4, RoundingMode.HALF_UP));
        } else {
            so.setMarginRate(BigDecimal.ZERO);
        }
    }

    /** 付款条件差异对比（BR-4.3-25）：客户法人视图协议付款条件 vs 订单录入值 */
    private Map<String, Object> payTermsDiff(So so) {
        Map<String, Object> out = new LinkedHashMap<>();
        MdmCustomerView view = viewDao.selectOne(new LambdaQueryWrapper<MdmCustomerView>()
                .eq(MdmCustomerView::getGroupId, so.getCustomerId())
                .last("LIMIT 1"));
        String expected = view == null ? null : view.getPaymentTerms();
        String actual = so.getPaymentTerms();
        boolean differs = isNotBlank(expected) && isNotBlank(actual)
                && !expected.trim().equals(actual.trim());
        out.put("expected", expected);
        out.put("actual", actual);
        out.put("differs", differs);
        out.put("confirmed", isNotBlank(so.getPayConfirmBy()));
        out.put("confirmedBy", so.getPayConfirmBy());
        out.put("confirmReason", so.getPayConfirmReason());
        return out;
    }

    /** 有效特批单（低毛利链尾加签的前置）：APPROVED 且有效期内、覆盖订单行 SKU */
    private boolean hasValidSpecial(So so, List<SoLine> lines) {
        List<SpecialPrice> list = specialDao.selectList(new LambdaQueryWrapper<SpecialPrice>()
                .eq(SpecialPrice::getCustomerId, so.getCustomerId())
                .eq(SpecialPrice::getStatus, SpecialPrice.ST_APPROVED)
                .le(SpecialPrice::getValidFrom, LocalDate.now())
                .ge(SpecialPrice::getValidTo, LocalDate.now()));
        if (list.isEmpty()) {
            return false;
        }
        for (SpecialPrice sp : list) {
            if (isBlank(sp.getItemCode())) {
                return true; // 客户级特批覆盖全部 SKU
            }
            if (lines.stream().anyMatch(l -> sp.getItemCode().equals(l.getItemCode()))) {
                return true;
            }
        }
        return false;
    }

    private void decorate(So so) {
        if (So.SUSPENDED.contains(so.getStatus())) {
            so.setSuspended(true);
            if (So.ST_CREDIT_FREEZE.equals(so.getStatus())) {
                so.setSuspendReason("信用冻结（挂起前状态 " + so.getPrevStatus() + "）");
            } else {
                so.setSuspendReason("变更中（挂起前状态 " + so.getPrevStatus() + "）");
            }
        } else {
            so.setSuspended(false);
        }
    }

    private void saveVersion(So so, String opType, String remark) {
        SoVersion v = new SoVersion();
        v.setId(uuid());
        v.setSoId(so.getId());
        long max = versionDao.selectCount(new LambdaQueryWrapper<SoVersion>()
                .eq(SoVersion::getSoId, so.getId()));
        v.setVersionNo((int) max + 1);
        v.setOpType(opType);
        v.setSnapshotJson(toJson(so));
        v.setOperatorId(currentUser());
        v.setOperateAt(LocalDateTime.now());
        v.setRemark(remark);
        versionDao.insert(v);
    }

    private void recordChange(String soId, Integer lineNo, String field,
                              String oldValue, String newValue, String creditRerun,
                              String reason) {
        SoChange c = new SoChange();
        c.setId(uuid());
        c.setSoId(soId);
        c.setLineNo(lineNo);
        c.setFieldName(field);
        c.setOldValue(oldValue);
        c.setNewValue(newValue);
        c.setCreditRerun(creditRerun);
        c.setReason(reason);
        c.setOperatorId(currentUser());
        c.setOperateAt(LocalDateTime.now());
        changeDao.insert(c);
    }

    private So require(String soId) {
        So so = soDao.selectById(soId);
        if (so == null) {
            throw new ServiceException(404, "销售订单不存在：" + soId);
        }
        return so;
    }

    private List<SoLine> soLines(String soId) {
        return soLineDao.selectList(new LambdaQueryWrapper<SoLine>()
                .eq(SoLine::getSoId, soId).orderByAsc(SoLine::getLineNo));
    }

    private MdmCustomerGroup requireCustomer(String customerId) {
        if (isBlank(customerId)) {
            throw new ServiceException(422, "客户必填");
        }
        MdmCustomerGroup c = customerDao.selectById(customerId);
        if (c == null) {
            throw new ServiceException(422, "客户不存在：" + customerId + "，请先建档（FR-4.3-4-2）");
        }
        if ("2".equals(c.getStatus())) {
            throw new ServiceException(422, "客户已冻结，不可下单（FR-4.3-4-2）");
        }
        return c;
    }

    private String nextNo(String kind) {
        String prefix = kind + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : soDao.selectNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private void requireAny(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : roles) {
                if (want.equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "无权" + action + "（需 "
                + String.join("/", allowed).replace("ROLE_", "") + "）");
    }

    private String currentUser() {
        String id = SecurityUtils.getCurrentUserId();
        return id == null ? "system" : id;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    private static String nvl(String s, String def) {
        return isBlank(s) ? def : s;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nvlStr(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate dateOrNull(Object o) {
        String s = str(o);
        return isBlank(s) ? null : LocalDate.parse(s);
    }
}
