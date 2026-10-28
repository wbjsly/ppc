package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfChannelDao;
import com.erp.dao.intf.IntfSlaReportDao;
import com.erp.entity.intf.IntfChannel;
import com.erp.entity.intf.IntfSlaReport;
import com.erp.security.PortalContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门户「服务等级」栏目（add-service-monitoring-console，spec service-monitoring-console
 * 「门户服务等级栏目与章节级可见」）：
 * - 仅 PUBLISHED 报告可见（未发布 404，C-0-01）；
 * - 章节级过滤（design D5 方案 B）：登录供应商 → 关联通道（erp_intf_channel.SUPPLIER_ID）
 *   的 PARTNER_CODE 集合 → chapters 仅保留集合内伙伴码；无 partner 入参，
 *   指定他伙伴参数天然无效（身份一律取 PortalContext，C-0-07 行级隔离）。
 */
@Tag(name = "门户-服务等级")
@RestController
@RequestMapping("/api/portal/sla-reports")
public class PortalSlaController {

    private final IntfSlaReportDao reportDao;
    private final IntfChannelDao channelDao;
    private final PortalContext portalContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PortalSlaController(IntfSlaReportDao reportDao, IntfChannelDao channelDao,
                               PortalContext portalContext) {
        this.reportDao = reportDao;
        this.channelDao = channelDao;
        this.portalContext = portalContext;
    }

    @Operation(summary = "我的服务等级报告（仅已发布）")
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        portalContext.requireSupplierId();
        List<IntfSlaReport> reports = reportDao.selectList(
                new LambdaQueryWrapper<IntfSlaReport>()
                        .eq(IntfSlaReport::getStatus, IntfSlaReport.ST_PUBLISHED)
                        .orderByDesc(IntfSlaReport::getMonthTag));
        List<Map<String, Object>> out = new ArrayList<>();
        for (IntfSlaReport r : reports) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("monthTag", r.getMonthTag());
            m.put("title", r.getTitle());
            m.put("version", r.getVersion());
            m.put("publishAt", r.getPublishAt());
            out.add(m);
        }
        return R.ok(out);
    }

    @Operation(summary = "报告详情（总览 + 仅本人章节，服务端过滤）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        String supplierId = portalContext.requireSupplierId();
        IntfSlaReport r = reportDao.selectById(id);
        if (r == null || !IntfSlaReport.ST_PUBLISHED.equals(r.getStatus())) {
            // 未发布报告对任何伙伴不可见（spec：未发布报告不出现在栏目）
            throw new ServiceException(404, "报告不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("monthTag", r.getMonthTag());
        out.put("title", r.getTitle());
        out.put("version", r.getVersion());
        out.put("publishAt", r.getPublishAt());

        // 关联通道的伙伴码集合（结构映射，design D5 方案 B）
        List<String> myCodes = channelDao.selectList(new LambdaQueryWrapper<IntfChannel>()
                        .eq(IntfChannel::getSupplierId, supplierId)
                        .select(IntfChannel::getPartnerCode))
                .stream().map(IntfChannel::getPartnerCode).toList();

        JsonNode root = parseQuietly(r.getMetricsJson());
        out.put("overview", root != null && root.has("overview") ? root.get("overview") : null);
        List<Map<String, Object>> mine = new ArrayList<>();
        if (root != null && root.path("chapters").isArray()) {
            for (JsonNode ch : root.get("chapters")) {
                String code = ch.path("partnerCode").asText("");
                if (myCodes.contains(code)) {
                    mine.add(objectMapper.convertValue(ch, Map.class));
                }
            }
        }
        out.put("chapters", mine);
        out.put("myPartnerCodes", myCodes);
        return R.ok(out);
    }

    private JsonNode parseQuietly(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
