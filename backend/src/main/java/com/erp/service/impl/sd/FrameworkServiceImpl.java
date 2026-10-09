package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.sd.FrameworkDao;
import com.erp.dao.sd.FrameworkLineDao;
import com.erp.dao.sd.FrameworkReleaseDao;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.sd.Framework;
import com.erp.entity.sd.FrameworkLine;
import com.erp.entity.sd.FrameworkRelease;
import com.erp.entity.sd.Shipment;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.sd.FrameworkService;
import com.erp.service.sd.ShipmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 销售框架协议实现（tasks 13.2~13.6，spec sales-framework-agreement，D11）。
 */
@Slf4j
@Service
public class FrameworkServiceImpl implements FrameworkService {

    private final FrameworkDao fwDao;
    private final FrameworkLineDao lineDao;
    private final FrameworkReleaseDao releaseDao;
    private final MdmCustomerGroupDao customerDao;
    private final MdmItemDao itemDao;
    private final ApprovalEngine approvalEngine;
    private final ShipmentService shipmentService;
    private final ObjectMapper mapper = new ObjectMapper();

    public FrameworkServiceImpl(FrameworkDao fwDao,
                               FrameworkLineDao lineDao,
                               FrameworkReleaseDao releaseDao,
                               MdmCustomerGroupDao customerDao,
                               MdmItemDao itemDao,
                               ApprovalEngine approvalEngine,
                               ShipmentService shipmentService) {
        this.fwDao = fwDao;
        this.lineDao = lineDao;
        this.releaseDao = releaseDao;
        this.customerDao = customerDao;
        this.itemDao = itemDao;
        this.approvalEngine = approvalEngine;
        this.shipmentService = shipmentService;
    }

    private static final String[] ROLES = {"ROLE_SALES", "ROLE_SALES_MGR",
            "ROLE_SALES_DIRECTOR", "ROLE_WAREHOUSE", "ROLE_ADMIN"};

    // ==================== 13.2 头行维护 ====================

    @Override
    public Page<Framework> page(long current, long size, String keyword, String status,
                                String customerId) {
        requireAny("查询框架协议");
        LambdaQueryWrapper<Framework> qw = new LambdaQueryWrapper<Framework>()
                .eq(isNotBlank(status), Framework::getStatus, status)
                .eq(isNotBlank(customerId), Framework::getCustomerId, customerId)
                .and(isNotBlank(keyword), w -> w.like(Framework::getFwNo, keyword)
                        .or().like(Framework::getCustomerName, keyword)
                        .or().like(Framework::getTitle, keyword))
                .orderByDesc(Framework::getCreateDate);
        return fwDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        requireAny("查询框架协议");
        Framework fw = require(id);
        refreshExpiry(fw);
        Map<String, Object> out = base(fw);
        out.put("releases", releasesOf(id));
        return out;
    }

    @Override
    @Transactional
    public Framework create(Map<String, Object> req) {
        requireAny("创建框架协议");
        String customerId = str(req.get("customerId"));
        if (isBlank(customerId)) {
            throw new ServiceException(422, "客户必填");
        }
        MdmCustomerGroup cust = customerDao.selectById(customerId);
        if (cust == null) {
            throw new ServiceException(404, "客户不存在：" + customerId);
        }
        LocalDate eff = req.get("effectiveDate") == null ? LocalDate.now()
                : LocalDate.parse(str(req.get("effectiveDate")));
        LocalDate exp = req.get("expireDate") == null ? null
                : LocalDate.parse(str(req.get("expireDate")));
        if (exp == null) {
            throw new ServiceException(422, "失效日期必填");
        }
        if (!exp.isAfter(eff)) {
            throw new ServiceException(422, "失效日期必须晚于生效日期");
        }
        List<Map<String, Object>> lines = listOf(req.get("lines"));
        if (lines.isEmpty()) {
            throw new ServiceException(422, "协议行必填（适用物料）");
        }

        Framework fw = new Framework();
        fw.setId(uuid());
        fw.setFwNo(nextNo("FW", fwDao::selectNosByPrefix));
        fw.setCustomerId(cust.getId());
        fw.setCustomerCode(cust.getCustomerCode());
        fw.setCustomerName(cust.getCustomerName());
        fw.setTitle(str(req.get("title")));
        fw.setEffectiveDate(eff);
        fw.setExpireDate(exp);
        fw.setStatus(Framework.ST_EFFECTIVE);
        fw.setRemark(str(req.get("remark")));
        fw.setCreateBy(currentUser());
        fw.setTotalQty(BigDecimal.ZERO);
        fw.setChangeLog("[]");

        BigDecimal total = BigDecimal.ZERO;
        int lineNo = 1;
        for (Map<String, Object> raw : lines) {
            FrameworkLine l = buildLine(fw.getId(), lineNo++, raw, null);
            total = total.add(l.getTotalQty());
            lineDao.insert(l);
        }
        fw.setTotalQty(total);
        fwDao.insert(fw);
        log.info("framework {} created (customer={}, total={})", fw.getFwNo(),
                cust.getCustomerName(), strip(total));
        return fw;
    }

    @Override
    @Transactional
    public Framework update(String id, Map<String, Object> req) {
        requireAny("维护框架协议");
        Framework fw = require(id);
        if (!Framework.ST_EFFECTIVE.equals(fw.getStatus())) {
            throw new ServiceException(422, "仅生效中的协议可维护（当前：" + fw.getStatus() + "）");
        }
        if (isNotBlank(str(req.get("fwNo"))) && !fw.getFwNo().equals(str(req.get("fwNo")))) {
            // spec：协议编号生成后不可改
            throw new ServiceException(422, "协议编号生成后不可修改（spec 编码锁定）");
        }
        if (isNotBlank(str(req.get("expireDate")))) {
            LocalDate exp = LocalDate.parse(str(req.get("expireDate")));
            if (!exp.isAfter(fw.getEffectiveDate())) {
                throw new ServiceException(422, "失效日期必须晚于生效日期");
            }
            fw.setExpireDate(exp);
        }
        if (req.get("title") != null) {
            fw.setTitle(str(req.get("title")));
        }
        if (req.get("remark") != null) {
            fw.setRemark(str(req.get("remark")));
        }

        // 行维护：仅未下达行可改量/价；已下达行不可删改（13.2）
        if (req.get("lines") != null) {
            List<Map<String, Object>> lines = listOf(req.get("lines"));
            Map<String, FrameworkLine> existing = new LinkedHashMap<>();
            for (FrameworkLine l : linesOf(id)) {
                existing.put(l.getId(), l);
            }
            Map<String, Boolean> seen = new LinkedHashMap<>();
            int maxNo = existing.values().stream()
                    .mapToInt(l -> l.getLineNo() == null ? 0 : l.getLineNo()).max().orElse(0);
            BigDecimal total = BigDecimal.ZERO;
            for (Map<String, Object> raw : lines) {
                String lineId = str(raw.get("lineId"));
                FrameworkLine old = isBlank(lineId) ? null : existing.get(lineId);
                if (old != null) {
                    BigDecimal released = nvl(old.getReleasedQty());
                    BigDecimal newQty = num(raw.get("totalQty"));
                    if (released.signum() > 0 && newQty.signum() > 0
                            && newQty.compareTo(nvl(old.getTotalQty())) != 0) {
                        throw new ServiceException(422, "行 " + old.getLineNo()
                                + " 已下达 " + strip(released) + "，已下达行不可修改总量"
                                + "（调整总量请走变更审批 13.5）");
                    }
                    if (released.signum() > 0 && raw.get("unitPrice") != null
                            && num(raw.get("unitPrice")).compareTo(nvl(old.getUnitPrice())) != 0) {
                        throw new ServiceException(422, "行 " + old.getLineNo()
                                + " 已下达，锁定单价不可直接修改（单价重谈请走变更审批 13.5）");
                    }
                    if (raw.get("totalQty") != null && num(raw.get("totalQty")).signum() > 0) {
                        old.setTotalQty(num(raw.get("totalQty")));
                    }
                    if (raw.get("unitPrice") != null) {
                        old.setUnitPrice(num(raw.get("unitPrice")));
                    }
                    if (raw.get("warehouseCode") != null) {
                        old.setWarehouseCode(str(raw.get("warehouseCode")));
                    }
                    lineDao.updateById(old);
                    seen.put(old.getId(), true);
                    total = total.add(nvl(old.getTotalQty()));
                } else {
                    FrameworkLine l = buildLine(id, ++maxNo, raw, null);
                    lineDao.insert(l);
                    seen.put(l.getId(), true);
                    total = total.add(l.getTotalQty());
                }
            }
            for (Map.Entry<String, FrameworkLine> e : existing.entrySet()) {
                if (!seen.containsKey(e.getKey())) {
                    if (nvl(e.getValue().getReleasedQty()).signum() > 0) {
                        throw new ServiceException(422, "行 " + e.getValue().getLineNo()
                                + " 已下达，不可删除");
                    }
                    lineDao.deleteById(e.getKey());
                }
            }
            fw.setTotalQty(total);
        }
        fwDao.updateById(fw);
        return fw;
    }

    // ==================== 13.3 下达 ====================

    @Override
    @Transactional
    public FrameworkRelease release(String id, String lineId, BigDecimal qty,
                                    LocalDate deliverDate) {
        requireAny("下达框架订单");
        Framework fw = require(id);
        validateActive(fw);
        FrameworkLine line = requireLine(lineId, id);
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "下达数量必须大于 0");
        }
        BigDecimal remain = nvl(line.getTotalQty()).subtract(nvl(line.getReleasedQty()));
        if (qty.compareTo(remain) > 0) {
            // C-4.3-10 L1 硬阻断：不允许部分越量放行
            throw new ServiceException(422, "超出协议剩余可下达量（C-4.3-10）：剩余 "
                    + strip(remain) + "，本次下达 " + strip(qty) + "，超出部分须走补充协议");
        }
        FrameworkRelease r = new FrameworkRelease();
        r.setId(uuid());
        r.setReleaseNo(nextNo("FWRL", releaseDao::selectNosByPrefix));
        r.setFrameworkId(fw.getId());
        r.setFwLineId(line.getId());
        r.setLineNo(line.getLineNo());
        r.setCustomerId(fw.getCustomerId());
        r.setCustomerCode(fw.getCustomerCode());
        r.setCustomerName(fw.getCustomerName());
        r.setItemCode(line.getItemCode());
        r.setItemName(line.getItemName());
        r.setQty(qty);
        r.setShippedQty(BigDecimal.ZERO);
        r.setDeliverDate(deliverDate);
        r.setStatus(FrameworkRelease.ST_OPEN);
        r.setCreateBy(currentUser());
        releaseDao.insert(r);

        line.setReleasedQty(nvl(line.getReleasedQty()).add(qty));
        lineDao.updateById(line);
        log.info("framework {} release {} qty={} (released={})", fw.getFwNo(),
                r.getReleaseNo(), strip(qty), strip(line.getReleasedQty()));
        return r;
    }

    @Override
    @Transactional
    public FrameworkRelease cancelRelease(String releaseId, String reason) {
        requireAny("取消框架下达单");
        FrameworkRelease r = requireRelease(releaseId);
        if (nvl(r.getShippedQty()).signum() > 0) {
            throw new ServiceException(422, "下达单已部分发货，不可取消（已发 "
                    + strip(r.getShippedQty()) + "）");
        }
        if (FrameworkRelease.ST_CANCELLED.equals(r.getStatus())) {
            throw new ServiceException(422, "下达单已取消");
        }
        if (isBlank(reason)) {
            throw new ServiceException(422, "取消原因必填");
        }
        r.setStatus(FrameworkRelease.ST_CANCELLED);
        r.setRemark(reason);
        releaseDao.updateById(r);
        FrameworkLine line = lineDao.selectById(r.getFwLineId());
        if (line != null) {
            line.setReleasedQty(nvl(line.getReleasedQty()).subtract(r.getQty()).max(BigDecimal.ZERO));
            lineDao.updateById(line);
        }
        return r;
    }

    // ==================== 13.6 分批发货 + 13.4 超量阻断 ====================

    @Override
    @Transactional
    public Map<String, Object> shipFromRelease(String releaseId, BigDecimal qty) {
        requireAny("框架分批发货");
        FrameworkRelease r = requireRelease(releaseId);
        if (FrameworkRelease.ST_CANCELLED.equals(r.getStatus())) {
            throw new ServiceException(422, "下达单已取消，不可发货");
        }
        if (FrameworkRelease.ST_SHIPPED.equals(r.getStatus())) {
            throw new ServiceException(422, "下达单已发完，无可发余量");
        }
        Framework fw = require(r.getFrameworkId());
        // 终止冻结 / 过期 / 未生效 → 阻断后续发货（13.5 / spec 状态机）
        validateActive(fw);
        FrameworkLine line = requireLine(r.getFwLineId(), fw.getId());
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "发货数量必须大于 0");
        }
        BigDecimal releaseRemain = nvl(r.getQty()).subtract(nvl(r.getShippedQty()));
        if (qty.compareTo(releaseRemain) > 0) {
            throw new ServiceException(422, "超下达单剩余可发量（C-4.3-10）：剩余 "
                    + strip(releaseRemain) + "，本次发货 " + strip(qty));
        }
        BigDecimal lineRemain = nvl(line.getReleasedQty()).subtract(nvl(line.getShippedQty()));
        if (qty.compareTo(lineRemain) > 0) {
            throw new ServiceException(422, "超协议行剩余可发量（C-4.3-10）：剩余 "
                    + strip(lineRemain) + "，本次发货 " + strip(qty));
        }

        // 生成 FRAMEWORK 发货单并同事务过账（FIFO 锁批，库存不足 422 阻断）
        Map<String, Object> gen = shipmentService.frameworkShip(r, fw, line, qty);
        @SuppressWarnings("unchecked")
        Shipment ship = (Shipment) gen.get("shipment");

        // 回写已发量（13.6 过账后回写）
        r.setShippedQty(nvl(r.getShippedQty()).add(qty));
        r.setStatus(nvl(r.getShippedQty()).compareTo(nvl(r.getQty())) >= 0
                ? FrameworkRelease.ST_SHIPPED : FrameworkRelease.ST_PARTIAL);
        r.setLastShipId(ship.getId());
        r.setLastShipNo(ship.getShipNo());
        releaseDao.updateById(r);
        line.setShippedQty(nvl(line.getShippedQty()).add(qty));
        lineDao.updateById(line);

        log.info("framework {} release {} ship {} qty={} (lineShipped={})", fw.getFwNo(),
                r.getReleaseNo(), ship.getShipNo(), strip(qty), strip(line.getShippedQty()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shipment", ship);
        out.put("release", r);
        out.put("line", line);
        return out;
    }

    // ==================== 13.5 变更与终止（S-4.3-11） ====================

    @Override
    @Transactional
    public Framework change(String id, Map<String, Object> payload) {
        requireAny("发起协议变更");
        Framework fw = require(id);
        if (!Framework.ST_EFFECTIVE.equals(fw.getStatus())) {
            throw new ServiceException(422, "仅生效中的协议可变更（当前：" + fw.getStatus() + "）");
        }
        if (isNotBlank(fw.getPendingChange())) {
            throw new ServiceException(422, "已有变更在审批中，请先等待审批结果");
        }
        String type = str(payload.get("type"));
        if (!Framework.CHG_TOTAL.equals(type) && !Framework.CHG_PRICE.equals(type)
                && !Framework.CHG_TERMINATE.equals(type)) {
            throw new ServiceException(422, "变更类型 TOTAL/PRICE/TERMINATE 必选");
        }
        String reason = str(payload.get("reason"));
        if (isBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "变更原因必填（S-4.3-11 留痕）");
        }
        List<Map<String, Object>> lines = payload.get("lines") == null ? List.of()
                : listOf(payload.get("lines"));
        List<Map<String, Object>> before = new ArrayList<>();

        if (Framework.CHG_TOTAL.equals(type)) {
            if (lines.isEmpty()) {
                throw new ServiceException(422, "总量调整须给出协议行与新总量");
            }
            for (Map<String, Object> raw : lines) {
                String lineId = str(raw.get("lineId"));
                BigDecimal newQty = num(raw.get("totalQty"));
                FrameworkLine l = requireLine(lineId, id);
                if (newQty.signum() <= 0) {
                    throw new ServiceException(422, "新总量必须大于 0（行 " + l.getLineNo() + "）");
                }
                // 调减后不得低于已发量（spec scenario）
                if (newQty.compareTo(nvl(l.getShippedQty())) < 0) {
                    throw new ServiceException(422, "行 " + l.getLineNo()
                            + " 调整后总量 " + strip(newQty) + " 不得低于已发量 "
                            + strip(l.getShippedQty()));
                }
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("lineId", l.getId());
                b.put("lineNo", l.getLineNo());
                b.put("before", nvl(l.getTotalQty()));
                b.put("after", newQty);
                before.add(b);
            }
        } else if (Framework.CHG_PRICE.equals(type)) {
            if (lines.isEmpty()) {
                throw new ServiceException(422, "单价重谈须给出协议行与新单价");
            }
            for (Map<String, Object> raw : lines) {
                String lineId = str(raw.get("lineId"));
                BigDecimal newPrice = num(raw.get("unitPrice"));
                FrameworkLine l = requireLine(lineId, id);
                if (newPrice.signum() <= 0) {
                    throw new ServiceException(422, "新单价必须大于 0（行 " + l.getLineNo() + "）");
                }
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("lineId", l.getId());
                b.put("lineNo", l.getLineNo());
                b.put("before", nvl(l.getUnitPrice()));
                b.put("after", newPrice);
                before.add(b);
            }
        }

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("type", type);
        change.put("reason", reason.trim());
        change.put("lines", lines);
        change.put("before", before);
        change.put("at", LocalDateTime.now().toString());
        fw.setPendingChange(toJson(change));

        // S-4.3-11：变更与终止经销售总监 L2 审批（销售经理 → 销售总监）
        String title = "框架协议变更：" + fw.getFwNo() + " / " + fw.getCustomerName()
                + " / " + ("TOTAL".equals(type) ? "总量调整"
                : "PRICE".equals(type) ? "单价重谈" : "提前终止");
        var inst = approvalEngine.submit("Framework", fw.getId(), title, null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售经理审批")),
                        List.of(ApprovalNodeSpec.sign("ROLE_SALES_DIRECTOR", "销售总监审批"))));
        fw.setApprovalId(inst.getId());
        if (fwDao.updateById(fw) == 0) {
            throw new ServiceException(409, "协议状态更新冲突");
        }
        log.info("framework {} change {} submitted", fw.getFwNo(), type);
        return fw;
    }

    // ==================== 13.6 执行视图 ====================

    @Override
    public Map<String, Object> execution(String id) {
        requireAny("查询执行视图");
        Framework fw = require(id);
        refreshExpiry(fw);
        Map<String, Object> out = base(fw);
        out.put("releases", releasesOf(id));
        return out;
    }

    // ==================== 私有工具 ====================

    /** 状态机校验：终止/过期/未生效 → 阻断下达与发货（过期状态由读路径惰性回写，避免随异常回滚） */
    private void validateActive(Framework fw) {
        if (Framework.ST_TERMINATED.equals(fw.getStatus())) {
            throw new ServiceException(422, "协议已终止，冻结后续下达与发货（S-4.3-11）："
                    + fw.getFwNo());
        }
        LocalDate today = LocalDate.now();
        if (today.isAfter(fw.getExpireDate())) {
            throw new ServiceException(422, "协议已过期（失效日 "
                    + fw.getExpireDate() + "），不可再下达/发货");
        }
        if (today.isBefore(fw.getEffectiveDate())) {
            throw new ServiceException(422, "协议未到生效期（" + fw.getEffectiveDate() + "）");
        }
    }

    /** 惰性过期标记：在无事务的读路径回写 EXPIRED（写路径随 422 回滚故不写） */
    private void refreshExpiry(Framework fw) {
        if (Framework.ST_EFFECTIVE.equals(fw.getStatus())
                && LocalDate.now().isAfter(fw.getExpireDate())) {
            fw.setStatus(Framework.ST_EXPIRED);
            fwDao.updateById(fw);
        }
    }

    /** 详情/视图基础载荷：头 + 行（三量与余量）+ 变更历史 */
    private Map<String, Object> base(Framework fw) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FrameworkLine l : linesOf(fw.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("line", l);
            BigDecimal released = nvl(l.getReleasedQty());
            BigDecimal shipped = nvl(l.getShippedQty());
            row.put("remainRelease", nvl(l.getTotalQty()).subtract(released).max(BigDecimal.ZERO));
            row.put("remainShip", released.subtract(shipped).max(BigDecimal.ZERO));
            row.put("executeRate", nvl(l.getTotalQty()).signum() <= 0 ? BigDecimal.ZERO
                    : shipped.divide(nvl(l.getTotalQty()), 4, RoundingMode.HALF_UP));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("framework", fw);
        out.put("lines", rows);
        out.put("changeLog", readJsonList(fw.getChangeLog()));
        if (isNotBlank(fw.getApprovalId())) {
            out.put("approval", approvalEngine.getInstance(fw.getApprovalId()));
        }
        out.put("approvalLogs", approvalEngine.logs("Framework", fw.getId()));
        return out;
    }

    private FrameworkLine buildLine(String fwId, int lineNo, Map<String, Object> raw,
                                    String ignored) {
        String itemCode = str(raw.get("itemCode"));
        if (isBlank(itemCode)) {
            throw new ServiceException(422, "协议行物料必填");
        }
        BigDecimal qty = num(raw.get("totalQty"));
        if (qty.signum() <= 0) {
            throw new ServiceException(422, "行 " + lineNo + " 协议总量必须大于 0");
        }
        MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, itemCode).last("LIMIT 1"));
        if (item == null) {
            throw new ServiceException(404, "物料不存在：" + itemCode);
        }
        FrameworkLine l = new FrameworkLine();
        l.setId(uuid());
        l.setFrameworkId(fwId);
        l.setLineNo(lineNo);
        l.setItemCode(item.getItemCode());
        l.setItemName(item.getItemName());
        l.setBaseUnit(item.getBaseUnit());
        l.setTotalQty(qty);
        l.setReleasedQty(BigDecimal.ZERO);
        l.setShippedQty(BigDecimal.ZERO);
        l.setUnitPrice(raw.get("unitPrice") == null ? null : num(raw.get("unitPrice")));
        l.setWarehouseCode(raw.get("warehouseCode") == null ? "WH-MAIN"
                : str(raw.get("warehouseCode")));
        l.setRemark(str(raw.get("remark")));
        l.setCreateBy(currentUser());
        return l;
    }

    private List<Map<String, Object>> releasesOf(String fwId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<FrameworkRelease> rs = releaseDao.selectList(new LambdaQueryWrapper<FrameworkRelease>()
                .eq(FrameworkRelease::getFrameworkId, fwId)
                .orderByAsc(FrameworkRelease::getDeliverDate)
                .orderByAsc(FrameworkRelease::getReleaseNo));
        for (FrameworkRelease r : rs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("release", r);
            m.put("remainShip", nvl(r.getQty()).subtract(nvl(r.getShippedQty())).max(BigDecimal.ZERO));
            out.add(m);
        }
        return out;
    }

    private Framework require(String id) {
        Framework fw = fwDao.selectById(id);
        if (fw == null) {
            throw new ServiceException(404, "框架协议不存在：" + id);
        }
        return fw;
    }

    private FrameworkRelease requireRelease(String id) {
        FrameworkRelease r = releaseDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "框架下达单不存在：" + id);
        }
        return r;
    }

    private FrameworkLine requireLine(String lineId, String fwId) {
        FrameworkLine l = isBlank(lineId) ? null : lineDao.selectById(lineId);
        if (l == null || !fwId.equals(l.getFrameworkId())) {
            throw new ServiceException(404, "协议行不存在：" + lineId);
        }
        return l;
    }

    private List<FrameworkLine> linesOf(String fwId) {
        return lineDao.selectList(new LambdaQueryWrapper<FrameworkLine>()
                .eq(FrameworkLine::getFrameworkId, fwId)
                .orderByAsc(FrameworkLine::getLineNo));
    }

    private String nextNo(String kind, Function<String, List<String>> query) {
        String prefix = kind + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-";
        int max = 0;
        for (String no : query.apply(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 跳过非规范编号
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException(500, "变更序列化失败：" + e.getMessage());
        }
    }

    private List<?> readJsonList(String json) {
        if (isBlank(json)) {
            return List.of();
        }
        try {
            return mapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void requireAny(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = List.of(ROLES);
        boolean ok = auth.getAuthorities().stream()
                .anyMatch(a -> roles.contains(a.getAuthority()));
        if (!ok) {
            throw new ServiceException(403, "无权限" + action);
        }
    }

    private String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "system" : auth.getName();
    }

    private static List<Map<String, Object>> listOf(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            return new ObjectMapper().convertValue(raw,
                    new ObjectMapper().getTypeFactory()
                            .constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal num(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String strip(BigDecimal v) {
        return nvl(v).stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
