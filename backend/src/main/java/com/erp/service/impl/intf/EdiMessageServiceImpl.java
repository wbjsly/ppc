package com.erp.service.impl.intf;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfEdimapDao;
import com.erp.dao.intf.IntfMessageDao;
import com.erp.dao.intf.IntfTicketDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.entity.intf.IntfEdimap;
import com.erp.entity.intf.IntfMessage;
import com.erp.entity.intf.IntfSlaAlert;
import com.erp.entity.intf.IntfTicket;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.security.IntfGuard;
import com.erp.service.fin.ThreeWayMatchService;
import com.erp.service.intf.EdiMessageService;
import com.erp.service.intf.OpenApiGatewayService;
import com.erp.service.proc.AsnService;
import com.erp.service.proc.PurchaseOrderService;
import com.erp.util.IntfCrypto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EDI 报文四级校验与入库（spec edi-message-processing，design D4/D6）。
 * 台账先独立落库（RECEIVED），业务入库在 TransactionTemplate 内原子执行，
 * 失败回滚业务但绝不回滚台账 —— 报文行是后续一切留痕与人工重放的锚点。
 */
@Slf4j
@Service
public class EdiMessageServiceImpl implements EdiMessageService {

    private static final Set<String> TYPES = Set.of("ORDERS", "DESADV", "INVOIC");
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern RULE_PATTERN = Pattern.compile("(C-\\d+\\.\\d+-\\d+)");

    private final IntfMessageDao messageDao;
    private final IntfEdimapDao edimapDao;
    private final IntfTicketDao ticketDao;
    private final PurchaseOrderDao purchaseOrderDao;
    private final PurchaseOrderService purchaseOrderService;
    private final AsnService asnService;
    private final ThreeWayMatchService threeWayMatchService;
    private final OpenApiGatewayService gateway;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper mapper;

    @Value("${app.intf.api-retry-max:3}") private int retryMax;

    public EdiMessageServiceImpl(IntfMessageDao messageDao, IntfEdimapDao edimapDao,
                                 IntfTicketDao ticketDao, PurchaseOrderDao purchaseOrderDao,
                                 PurchaseOrderService purchaseOrderService, AsnService asnService,
                                 ThreeWayMatchService threeWayMatchService,
                                 OpenApiGatewayService gateway,
                                 PlatformTransactionManager transactionManager) {
        this.messageDao = messageDao;
        this.edimapDao = edimapDao;
        this.ticketDao = ticketDao;
        this.purchaseOrderDao = purchaseOrderDao;
        this.purchaseOrderService = purchaseOrderService;
        this.asnService = asnService;
        this.threeWayMatchService = threeWayMatchService;
        this.gateway = gateway;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ================================================================ 接收

    @Override
    public Map<String, Object> receive(String source, String partnerCode, String msgType, String rawJson) {
        if (msgType == null || !TYPES.contains(msgType.toUpperCase())) {
            throw new ServiceException(400, "报文类型仅支持 ORDERS / DESADV / INVOIC");
        }
        if (rawJson == null || rawJson.trim().isEmpty()) {
            throw new ServiceException(400, "报文内容不能为空");
        }
        String type = msgType.toUpperCase();
        String hash = IntfCrypto.sha256Hex(rawJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> root = parseQuietly(rawJson);
        String key = resolveKey(root, partnerCode, type);

        IntfMessage existing = messageDao.selectByIdempotencyKey(key);
        if (existing != null) {
            return handleExisting(existing, hash, root, rawJson);
        }

        IntfMessage msg = new IntfMessage();
        msg.setMsgNo(nextMsgNo());
        msg.setPartnerCode(partnerCode);
        msg.setMsgType(type);
        msg.setMsgVersion(str(root == null ? null : root.get("version")));
        msg.setIdempotencyKey(key);
        msg.setRawPayload(rawJson);
        msg.setPayloadHash(hash);
        msg.setSource(source);
        msg.setStatus(IntfMessage.ST_RECEIVED);
        msg.setRetryCount(0);
        msg.setLineTotal(root == null ? 0 : linesOf(root).size());
        msg.setLineOk(0);
        msg.setLineFail(0);
        msg.setReceivedAt(LocalDateTime.now());
        msg.setReceiptSent(false);
        messageDao.insert(msg);   // 独立事务（本服务不开事务）：先 insert 后 throw 也留痕

        return process(msg, root);
    }

    /** 幂等键命中：同载荷且已处理 → 重复跳过；同载荷未处理 → 继续处理；异载荷 → 409 + 工单（BR-4.9-26） */
    private Map<String, Object> handleExisting(IntfMessage existing, String hash,
                                               Map<String, Object> root, String rawJson) {
        boolean processed = IntfMessage.ST_PROCESSED.equals(existing.getStatus())
                || IntfMessage.ST_PARTIAL.equals(existing.getStatus());
        if (hash != null && existing.getPayloadHash() != null && hash.equals(existing.getPayloadHash())) {
            if (processed) {
                return receiptOf(existing, true, "重复跳过：幂等键命中，返回首次处理结果");
            }
            // 同载荷但未处理成功：允许按重发/重放语义继续
            return process(existing, root);
        }
        IntfTicket t = newTicket(IntfTicket.SRC_CONFLICT, existing.getId(),
                "幂等键冲突：同键不同载荷（" + existing.getMsgNo() + "）",
                "原载荷哈希 " + existing.getPayloadHash() + " ≠ 本次 " + hash,
                existing.getIdempotencyKey(), existing.getPartnerCode());
        gateway.raiseAlert("EDI_CONFLICT:" + existing.getMsgNo(), "EDI", IntfSlaAlert.LVL_WARNING,
                "报文幂等键同键异载荷，已转人工判定（" + existing.getMsgNo() + "）", existing.getPartnerCode());
        throw new ServiceException(409, "幂等键冲突（同键不同载荷），已生成人工处理工单 " + t.getTicketNo());
    }

    // ================================================================ 四级校验与入库

    private Map<String, Object> process(IntfMessage msg, Map<String, Object> root) {
        // ---- 语法层 ----
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> lines = root == null ? new ArrayList<>() : linesOf(root);
        checkHeader(msg.getMsgType(), root, errors);
        int lineFail = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (checkLine(msg.getMsgType(), lines.get(i), i + 1, errors)) {
                lineFail++;
            }
        }
        msg.setLineTotal(lines.size());
        msg.setLineFail(lineFail);
        if (!errors.isEmpty()) {
            msg.setLineOk(lines.size() - lineFail);
            msg.setSyntaxResult(toJson(errors));
            msg.setRetryCount((msg.getRetryCount() == null ? 0 : msg.getRetryCount()) + 1);
            if (msg.getRetryCount() >= retryMax) {
                msg.setStatus(IntfMessage.ST_MANUAL);
                msg.setRemark("语法层校验重发已达 " + retryMax + " 次，终止自动重试转人工");
                messageDao.updateById(msg);
                newTicket(IntfTicket.SRC_EDI_MANUAL, msg.getId(),
                        "报文语法校验失败待人工：" + msg.getMsgNo(), toJson(errors), msg.getIdempotencyKey(),
                        msg.getPartnerCode());
            } else {
                msg.setStatus(IntfMessage.ST_REJECTED);
                msg.setRemark("语法层校验失败，已向对方发起第 " + msg.getRetryCount() + " 次重发请求");
                messageDao.updateById(msg);
            }
            msg.setFinishedAt(LocalDateTime.now());
            return finish(msg, 400, "INVALID_ARGUMENT", errors);
        }

        // ---- 版本校验（BR-5.5-08：与契约登记版本不一致即阻断转人工） ----
        String versionIssue = checkVersion(msg);
        if (versionIssue != null) {
            msg.setStatus(IntfMessage.ST_REJECTED);
            msg.setRemark(versionIssue);
            msg.setSyntaxResult(toJson(List.of(Map.of("line", 0, "seg", "UNB", "field", "version",
                    "message", versionIssue))));
            messageDao.updateById(msg);
            newTicket(IntfTicket.SRC_VERSION, msg.getId(),
                    "报文版本未登记：" + msg.getMsgNo(), versionIssue, msg.getIdempotencyKey(), msg.getPartnerCode());
            msg.setFinishedAt(LocalDateTime.now());
            return finish(msg, 400, "INVALID_ARGUMENT",
                    List.of(Map.of("line", 0, "seg", "UNB", "field", "version", "message", versionIssue)));
        }

        // ---- 映射层（BR-4.9-25：规则缺失 L1 硬阻断） ----
        IntfEdimap rule = edimapDao.selectRule(msg.getPartnerCode(), msg.getMsgType());
        if (rule == null) {
            String msgText = "未找到映射规则，请联系管理员配置（伙伴 " + msg.getPartnerCode()
                    + " × " + msg.getMsgType() + "）";
            msg.setStatus(IntfMessage.ST_REJECTED);
            msg.setRemark(msgText);
            msg.setMapResult(toJson(Map.of("blocked", true, "reason", msgText)));
            messageDao.updateById(msg);
            gateway.raiseAlert("EDI_MAP_MISSING:" + msg.getPartnerCode() + ":" + msg.getMsgType(),
                    "EDI_MAPPING", IntfSlaAlert.LVL_CRITICAL, msgText, msg.getPartnerCode());
            msg.setFinishedAt(LocalDateTime.now());
            return finish(msg, 422, "BUSINESS_RULE_VIOLATION",
                    List.of(Map.of("line", 0, "seg", "MAP", "field", "-", "message", msgText)));
        }

        Map<String, Object> mapped = applyMapping(root, rule);
        List<Map<String, Object>> mappedLines = linesOf(mapped);

        // ---- 业务层：直调既有服务（继承价控/预算/审批/超收等全部卡控） ----
        BizOutcome outcome;
        try {
            outcome = txTemplate.execute(status -> runBusiness(msg, mapped, mappedLines));
        } catch (ServiceException e) {
            outcome = BizOutcome.fail(e.getCode(), e.getMessage(), extractRule(e.getMessage()));
        } catch (Exception e) {
            log.warn("edi business failed: {}", e.getMessage());
            outcome = BizOutcome.fail(500, "业务入库失败：" + e.getMessage(), null);
        }

        msg.setLineOk(lines.size() - lineFail);
        msg.setBizResult(toJson(outcome.asMap()));
        if (outcome.success) {
            msg.setBizType(outcome.bizType);
            msg.setBizNo(outcome.bizNo);
            msg.setStatus(lineFail > 0 ? IntfMessage.ST_PARTIAL : IntfMessage.ST_PROCESSED);
            msg.setMapResult(toJson(Map.of("applied", true, "ruleVersion", rule.getVersion())));
        } else {
            msg.setStatus(IntfMessage.ST_FAILED);
            msg.setRemark(outcome.error);
        }
        msg.setFinishedAt(LocalDateTime.now());
        messageDao.updateById(msg);
        return finish(msg, outcome.success ? 200 : (outcome.httpCode == 400 ? 400 : outcome.httpCode),
                outcome.success ? null : (outcome.rule != null ? "BUSINESS_RULE_VIOLATION" : "BUSINESS_RULE_VIOLATION"),
                outcome.errors);
    }

    /** 业务入库（必须在事务内；抛出即整体回滚，台账不受影响） */
    private BizOutcome runBusiness(IntfMessage msg, Map<String, Object> mapped,
                                   List<Map<String, Object>> lines) {
        Map<String, Object> header = headerOf(mapped);
        switch (msg.getMsgType()) {
            case IntfMessage.TYPE_ORDERS: {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("supplierId", str(header.get("supplierId")));
                payload.put("poType", "NORMAL");
                payload.put("taxRate", header.get("taxRate"));
                payload.put("taxCode", header.get("taxCode"));
                payload.put("remark", "EDI 报文 " + msg.getMsgNo() + "（幂等键 " + msg.getIdempotencyKey() + "）");
                payload.put("lines", lines);
                Map<String, Object> po = purchaseOrderService.createManual(payload);
                String poId = extractId(po, "po");
                String poNo = extractNo(po, "poNo");
                purchaseOrderService.submit(poId, null);
                // 第四入口溯源：来源标记 EDI + 报文编号/幂等键（spec purchase-order ADDED）
                PurchaseOrder entity = purchaseOrderDao.selectById(poId);
                if (entity != null) {
                    entity.setSource("EDI");
                    entity.setEdiMsgNo(msg.getMsgNo());
                    entity.setEdiIdemKey(msg.getIdempotencyKey());
                    purchaseOrderDao.updateById(entity);
                }
                return BizOutcome.ok("PO", poNo, null);
            }
            case IntfMessage.TYPE_DESADV: {
                Map<String, Object> payload = new LinkedHashMap<>();
                String poId = str(header.get("poId"));
                if (poId == null && header.get("poNo") != null) {
                    PurchaseOrder po = purchaseOrderDao.selectOne(new LambdaQueryWrapper<PurchaseOrder>()
                            .eq(PurchaseOrder::getPoNo, str(header.get("poNo"))).last("LIMIT 1"));
                    if (po != null) {
                        poId = po.getId();
                    }
                }
                payload.put("poId", poId);
                payload.put("logisticsNo", header.get("logisticsNo"));
                payload.put("expectArrival", header.get("expectArrival"));
                payload.put("lines", lines);
                Map<String, Object> asn = asnService.create(payload);
                return BizOutcome.ok("ASN", extractNo(asn, "asnNo"), null);
            }
            case IntfMessage.TYPE_INVOIC: {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("invoiceNo", header.get("invoiceNo"));
                payload.put("invoiceDate", str(header.get("invoiceDate")));
                payload.put("supplierId", header.get("supplierId"));
                payload.put("supplierName", header.get("supplierName"));
                payload.put("currency", header.get("currency"));
                payload.put("poNo", header.get("poNo"));
                payload.put("remark", "EDI 报文 " + msg.getMsgNo());
                payload.put("lines", lines);
                BigDecimal total = BigDecimal.ZERO;
                for (Map<String, Object> l : lines) {
                    BigDecimal amt = dec(l.get("amount"));
                    if (amt == null && l.get("qty") != null && l.get("unitPrice") != null) {
                        amt = dec(l.get("qty")).multiply(dec(l.get("unitPrice")));
                    }
                    if (amt != null) {
                        total = total.add(amt);
                    }
                }
                payload.put("totalAmount", total);
                Object inv = threeWayMatchService.createInvoice(payload);
                String no;
                if (inv instanceof com.erp.entity.fin.FinApInvoice fi) {
                    no = fi.getInvoiceNo();
                } else if (inv instanceof Map<?, ?> mm) {
                    no = extractNo((Map<String, Object>) mm, "invoiceNo");
                } else {
                    no = String.valueOf(inv);
                }
                return BizOutcome.ok("INVOICE", no, null);
            }
            default:
                return BizOutcome.fail(400, "不支持的报文类型 " + msg.getMsgType(), null);
        }
    }

    /** 从 {po: 实体} / {data: {...}} 之类的返回里取 ID */
    @SuppressWarnings("unchecked")
    private String extractId(Map<String, Object> result, String entityKey) {
        if (result == null) {
            return null;
        }
        Object e = result.get(entityKey);
        if (e instanceof PurchaseOrder po) {
            return po.getId();
        }
        if (e instanceof Map<?, ?> m && m.get("id") != null) {
            return String.valueOf(m.get("id"));
        }
        for (String k : new String[]{"id", "poId", "asnId"}) {
            if (result.get(k) != null) {
                return String.valueOf(result.get(k));
            }
        }
        return null;
    }

    /** 从 {po: 实体} / {asn: 实体} / {data: {...}} 之类的返回里取单号 */
    @SuppressWarnings("unchecked")
    private String extractNo(Map<String, Object> result, String field) {
        if (result == null) {
            return null;
        }
        for (String entityKey : new String[]{"po", "asn", "invoice", "data"}) {
            Object e = result.get(entityKey);
            if (e instanceof PurchaseOrder po && "poNo".equals(field)) {
                return po.getPoNo();
            }
            if (e instanceof com.erp.entity.proc.Asn a && "asnNo".equals(field)) {
                return a.getAsnNo();
            }
            if (e instanceof com.erp.entity.fin.FinApInvoice fi && "invoiceNo".equals(field)) {
                return fi.getInvoiceNo();
            }
            if (e instanceof Map<?, ?> m && m.get(field) != null) {
                return String.valueOf(m.get(field));
            }
        }
        if (result.get(field) != null) {
            return String.valueOf(result.get(field));
        }
        return null;
    }

    // ================================================================ 校验细则

    private void checkHeader(String type, Map<String, Object> root, List<Map<String, Object>> errors) {
        if (root == null) {
            errors.add(err(0, "UNH", "-", "报文不是合法 JSON 结构"));
            return;
        }
        Map<String, Object> header = headerOf(root);
        if (header.isEmpty()) {
            errors.add(err(0, "UNH", "-", "缺少 header 段"));
            return;
        }
        switch (type) {
            case IntfMessage.TYPE_ORDERS:
                require(errors, 0, "UNH", "supplierId", header.get("supplierId"));
                require(errors, 0, "UNH", "orderDate", header.get("orderDate"));
                break;
            case IntfMessage.TYPE_DESADV:
                if (isBlank(header.get("poId")) && isBlank(header.get("poNo"))) {
                    errors.add(err(0, "UNH", "poNo", "DESADV 必须携带 poId 或 poNo"));
                }
                break;
            case IntfMessage.TYPE_INVOIC:
                require(errors, 0, "UNH", "invoiceNo", header.get("invoiceNo"));
                require(errors, 0, "UNH", "supplierId", header.get("supplierId"));
                require(errors, 0, "UNH", "invoiceDate", header.get("invoiceDate"));
                break;
            default:
                break;
        }
    }

    /** 返回该行是否存在错误（存在则该行不入库） */
    private boolean checkLine(String type, Map<String, Object> line, int lineNo,
                              List<Map<String, Object>> errors) {
        boolean bad = false;
        String seg = IntfMessage.TYPE_DESADV.equals(type) ? "QTY"
                : IntfMessage.TYPE_INVOIC.equals(type) ? "LIN" : "PO1";
        if (isBlank(line.get("itemCode"))) {
            errors.add(err(lineNo, seg, "01", "物料编码必填"));
            bad = true;
        }
        BigDecimal qty = dec(line.get("qty"));
        if (qty == null) {
            errors.add(err(lineNo, seg, "02", "数量不是合法数值"));
            bad = true;
        } else if (qty.signum() <= 0) {
            errors.add(err(lineNo, seg, "02", "数量必须大于 0"));
            bad = true;
        }
        if (isBlank(line.get("unit"))) {
            errors.add(err(lineNo, seg, "03", "计量单位必填"));
            bad = true;
        }
        if (!IntfMessage.TYPE_DESADV.equals(type)) {
            BigDecimal price = dec(line.get("unitPrice"));
            if (price == null) {
                errors.add(err(lineNo, seg, "04", "单价不是合法数值"));
                bad = true;
            } else if (price.signum() < 0) {
                errors.add(err(lineNo, seg, "04", "单价不能为负"));
                bad = true;
            }
        }
        return bad;
    }

    private void require(List<Map<String, Object>> errors, int line, String seg, String field, Object v) {
        if (isBlank(v)) {
            errors.add(err(line, seg, field, field + " 必填"));
        }
    }

    private Map<String, Object> err(int line, String seg, String field, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", line);
        m.put("seg", seg);
        m.put("field", field);
        m.put("message", message);
        return m;
    }

    /** 报文版本与契约登记版本不一致 → 阻断（BR-5.5-08 / C-5.5-08） */
    private String checkVersion(IntfMessage msg) {
        IntfEdimap rule = edimapDao.selectRule(msg.getPartnerCode(), msg.getMsgType());
        if (rule == null) {
            return null;   // 映射层会单独阻断
        }
        // 契约版本以映射规则版本为准（同一伙伴×报文类型唯一登记版本）
        if (msg.getMsgVersion() != null && !msg.getMsgVersion().trim().isEmpty()
                && rule.getVersion() != null && !rule.getVersion().equals(msg.getMsgVersion())) {
            return "报文版本未登记：报文声明 " + msg.getMsgVersion() + "，契约登记 " + rule.getVersion();
        }
        return null;
    }

    // ================================================================ 映射

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyMapping(Map<String, Object> root, IntfEdimap rule) {
        Map<String, Object> mapped = deepCopy(root);
        Map<String, Object> cfg = parseQuietly(rule.getRuleJson());
        if (cfg == null) {
            return mapped;
        }
        Map<String, Object> header = headerOf(mapped);
        // 默认值（如 currency=CNY）
        Object defaults = cfg.get("defaults");
        if (defaults instanceof Map<?, ?> dm) {
            for (Map.Entry<?, ?> e : dm.entrySet()) {
                header.putIfAbsent(String.valueOf(e.getKey()), e.getValue());
            }
        }
        // 日期归一：YYYYMMDD → YYYY-MM-DD，时区统一按 UTC+8 口径落库（偏差表已记）
        Object dates = cfg.get("dateFields");
        if (dates instanceof List<?> dl) {
            for (Object d : dl) {
                String p = String.valueOf(d);
                if (p.startsWith("header.")) {
                    String f = p.substring(7);
                    header.put(f, normalizeDate(header.get(f)));
                } else if (p.startsWith("line.")) {
                    String f = p.substring(5);
                    for (Map<String, Object> l : linesOf(mapped)) {
                        l.put(f, normalizeDate(l.get(f)));
                    }
                }
            }
        }
        // 单位换算（代码表转换）
        Object units = cfg.get("unitMap");
        if (units instanceof Map<?, ?> um) {
            for (Map<String, Object> l : linesOf(mapped)) {
                String u = str(l.get("unit"));
                if (u != null && um.containsKey(u)) {
                    l.put("unit", um.get(u));
                }
            }
        }
        mapped.put("header", header);
        return mapped;
    }

    private Object normalizeDate(Object v) {
        String s = str(v);
        if (s == null) {
            return null;
        }
        if (s.matches("\\d{8}")) {
            return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
        }
        return s;
    }

    // ================================================================ 回执与台账

    private Map<String, Object> finish(IntfMessage msg, int httpCode, String errCode,
                                       List<Map<String, Object>> errors) {
        msg.setReceiptSent(true);
        msg.setReceiptSentAt(LocalDateTime.now());
        messageDao.updateById(msg);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("msgNo", msg.getMsgNo());
        receipt.put("status", msg.getStatus());
        receipt.put("httpCode", httpCode);
        if (errCode != null) {
            receipt.put("code", errCode);
        }
        receipt.put("errors", errors == null ? List.of() : errors);
        receipt.put("bizType", msg.getBizType());
        receipt.put("bizNo", msg.getBizNo());
        receipt.put("lineTotal", msg.getLineTotal());
        receipt.put("lineOk", msg.getLineOk());
        receipt.put("lineFail", msg.getLineFail());
        receipt.put("idempotencyKey", msg.getIdempotencyKey());
        receipt.put("processedAt", msg.getFinishedAt());
        receipt.put("receiptWithinSeconds", 60);
        return receipt;
    }

    private Map<String, Object> receiptOf(IntfMessage msg, boolean skipped, String note) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("skipped", skipped);
        r.put("msgNo", msg.getMsgNo());
        r.put("status", msg.getStatus());
        r.put("httpCode", 200);
        r.put("bizType", msg.getBizType());
        r.put("bizNo", msg.getBizNo());
        r.put("idempotencyKey", msg.getIdempotencyKey());
        r.put("message", note);
        return r;
    }

    // ================================================================ 查询与人工动作

    @Override
    public Page<IntfMessage> page(long current, long size, String msgType, String status, String partnerCode) {
        LambdaQueryWrapper<IntfMessage> qw = new LambdaQueryWrapper<>();
        qw.eq(msgType != null && !msgType.trim().isEmpty(), IntfMessage::getMsgType, msgType == null ? "" : msgType.trim().toUpperCase());
        qw.eq(status != null && !status.trim().isEmpty(), IntfMessage::getStatus, status == null ? "" : status.trim());
        qw.eq(partnerCode != null && !partnerCode.trim().isEmpty(), IntfMessage::getPartnerCode, partnerCode == null ? "" : partnerCode.trim());
        qw.orderByDesc(IntfMessage::getReceivedAt);
        return messageDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        IntfMessage msg = messageDao.selectById(id);
        if (msg == null) {
            throw new ServiceException(404, "报文不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", msg);
        m.put("syntax", parseQuietly(msg.getSyntaxResult()));
        m.put("mapping", parseQuietly(msg.getMapResult()));
        m.put("business", parseQuietly(msg.getBizResult()));
        return m;
    }

    @Override
    public Map<String, Object> replay(String id) {
        IntfMessage msg = messageDao.selectById(id);
        if (msg == null) {
            throw new ServiceException(404, "报文不存在");
        }
        if (IntfMessage.ST_PROCESSED.equals(msg.getStatus())) {
            throw new ServiceException(422, "报文已成功入库，无需重放");
        }
        // 保留原幂等键与原始报文重放（C-4.9-04）
        msg.setStatus(IntfMessage.ST_RECEIVED);
        messageDao.updateById(msg);
        return process(msg, parseQuietly(msg.getRawPayload()));
    }

    @Override
    public Page<IntfEdimap> mapRules(long current, long size, String partnerCode, String msgType) {
        LambdaQueryWrapper<IntfEdimap> qw = new LambdaQueryWrapper<>();
        qw.eq(partnerCode != null && !partnerCode.trim().isEmpty(), IntfEdimap::getPartnerCode, partnerCode == null ? "" : partnerCode.trim());
        qw.eq(msgType != null && !msgType.trim().isEmpty(), IntfEdimap::getMsgType, msgType == null ? "" : msgType.trim().toUpperCase());
        qw.orderByDesc(IntfEdimap::getCreateDate);
        return edimapDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> saveMapRule(Map<String, Object> payload) {
        String partnerCode = str(payload.get("partnerCode"));
        String msgType = str(payload.get("msgType"));
        Object ruleJson = payload.get("ruleJson");
        if (partnerCode == null || msgType == null || ruleJson == null) {
            throw new ServiceException(400, "partnerCode、msgType、ruleJson 必填");
        }
        IntfEdimap exist = edimapDao.selectRule(partnerCode, msgType.toUpperCase());
        String json = ruleJson instanceof String ? (String) ruleJson : toJson(ruleJson);
        if (exist != null) {
            exist.setRuleJson(json);
            exist.setVersion(str(payload.get("version")) == null ? exist.getVersion() : str(payload.get("version")));
            exist.setRemark(str(payload.get("remark")));
            edimapDao.updateById(exist);
            return Map.of("id", exist.getId(), "partnerCode", partnerCode, "msgType", msgType.toUpperCase());
        }
        IntfEdimap rule = new IntfEdimap();
        rule.setPartnerCode(partnerCode);
        rule.setMsgType(msgType.toUpperCase());
        rule.setRuleJson(json);
        rule.setStatus("ENABLED");
        rule.setVersion(str(payload.get("version")) == null ? "v1" : str(payload.get("version")));
        rule.setRemark(str(payload.get("remark")));
        edimapDao.insert(rule);
        return Map.of("id", rule.getId(), "partnerCode", partnerCode, "msgType", msgType.toUpperCase());
    }

    // ================================================================ helpers

    private IntfTicket newTicket(String source, String refId, String title, String detail,
                                 String idemKey, String partnerCode) {
        IntfTicket t = new IntfTicket();
        t.setTicketNo(nextTicketNo());
        t.setSource(source);
        t.setRefId(refId);
        t.setPartnerCode(partnerCode);
        t.setIdempotencyKey(idemKey);
        t.setTitle(title);
        t.setDetail(detail);
        t.setPriority("P2");
        t.setStatus(IntfTicket.ST_OPEN);
        t.setAssignTo("intf-ops01");
        t.setDueAt(LocalDateTime.now().plusHours(24));
        ticketDao.insert(t);
        return t;
    }

    private String nextMsgNo() {
        String prefix = "IM" + LocalDateTime.now().format(NO_FMT);
        Integer max = messageDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private String nextTicketNo() {
        String prefix = "IT" + LocalDateTime.now().format(NO_FMT);
        Integer max = ticketDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private String resolveKey(Map<String, Object> root, String partnerCode, String type) {
        String provided = root == null ? null : str(root.get("idempotencyKey"));
        if (provided != null && !provided.trim().isEmpty()) {
            return provided.trim();
        }
        Map<String, Object> h = headerOf(root);
        String biz = str(h.get("invoiceNo"));
        if (biz == null) {
            biz = str(h.get("poNo"));
        }
        if (biz == null) {
            biz = str(h.get("orderNo"));
        }
        if (biz == null) {
            biz = "NObiz";
        }
        String version = root == null || root.get("version") == null ? "v1" : String.valueOf(root.get("version"));
        return partnerCode + ":" + type + ":" + biz + ":" + version;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> headerOf(Map<String, Object> root) {
        if (root == null) {
            return new LinkedHashMap<>();
        }
        Object h = root.get("header");
        return h instanceof Map ? (Map<String, Object>) h : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> linesOf(Map<String, Object> root) {
        if (root == null) {
            return new ArrayList<>();
        }
        Object l = root.get("lines");
        if (!(l instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }

    private Map<String, Object> parseQuietly(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> src) {
        try {
            return mapper.readValue(mapper.writeValueAsString(src), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>(src);
        }
    }

    private static String extractRule(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = RULE_PATTERN.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isBlank(Object v) {
        return v == null || String.valueOf(v).trim().isEmpty();
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static final class BizOutcome {
        boolean success;
        int httpCode = 200;
        String bizType;
        String bizNo;
        String error;
        String rule;
        List<Map<String, Object>> errors = new ArrayList<>();

        static BizOutcome ok(String bizType, String bizNo, String rule) {
            BizOutcome o = new BizOutcome();
            o.success = true;
            o.bizType = bizType;
            o.bizNo = bizNo;
            o.rule = rule;
            return o;
        }

        static BizOutcome fail(int code, String error, String rule) {
            BizOutcome o = new BizOutcome();
            o.success = false;
            o.httpCode = code;
            o.error = error;
            o.rule = rule;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("line", 0);
            e.put("seg", "BIZ");
            e.put("field", rule == null ? "-" : rule);
            e.put("message", error);
            o.errors.add(e);
            return o;
        }

        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", success);
            m.put("bizType", bizType);
            m.put("bizNo", bizNo);
            m.put("error", error);
            m.put("violatedRule", rule);
            return m;
        }
    }
}
