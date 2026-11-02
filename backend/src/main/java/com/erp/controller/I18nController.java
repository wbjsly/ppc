package com.erp.controller;

import com.erp.common.R;
import com.erp.service.i18n.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 17 域 i18n：语言包键值/版本发布/缺口台账/区域格式/合规字段包/SLA 报告。
 */
@Tag(name = "本地适配 i18n")
@RestController
@RequestMapping("/api/i18n")
public class I18nController {

    private final I18nKeyService keyService;
    private final I18nPackService packService;
    private final I18nGapService gapService;
    private final LocaleConfigService localeService;
    private final ComplianceFieldService complianceService;
    private final I18nSlaService slaService;

    public I18nController(I18nKeyService keyService, I18nPackService packService,
                          I18nGapService gapService, LocaleConfigService localeService,
                          ComplianceFieldService complianceService, I18nSlaService slaService) {
        this.keyService = keyService;
        this.packService = packService;
        this.gapService = gapService;
        this.localeService = localeService;
        this.complianceService = complianceService;
        this.slaService = slaService;
    }

    // ---------- 17.1.1 键值维护 ----------
    @Operation(summary = "翻译键列表（源语+目标语联查）")
    @GetMapping("/keys")
    public R<List<Map<String, Object>>> keys(
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return R.ok(keyService.list(locale, module, priority, keyword, page, size));
    }

    @Operation(summary = "保存键值（源语/目标语，占位符校验 BR-5.7-03）")
    @PostMapping("/keys")
    public R<Map<String, Object>> saveKey(@RequestBody Map<String, Object> body) {
        return R.ok(keyService.save(body));
    }

    @Operation(summary = "删除键（源语级联）")
    @DeleteMapping("/keys/{id}")
    public R<Void> deleteKey(@PathVariable String id) {
        keyService.delete(id);
        return R.ok();
    }

    @Operation(summary = "导入译文 CSV（占位符不匹配 422 拒绝整文件 BR-5.7-03）")
    @PostMapping("/keys/import")
    public R<Map<String, Object>> importKeys(@RequestParam String locale,
                                             @RequestBody Map<String, String> body) {
        int n = keyService.importCsv(locale, body.getOrDefault("csv", ""));
        return R.ok(Map.of("imported", n));
    }

    @Operation(summary = "导出键清单 CSV")
    @GetMapping("/keys/export")
    public R<Map<String, Object>> exportKeys(
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String module) {
        return R.ok(Map.of("csv", keyService.exportCsv(locale, module)));
    }

    // ---------- 17.1.3 版本发布（SOP-5.7-B） ----------
    @Operation(summary = "构建语言包版本（不可变）")
    @PostMapping("/packs/build")
    public R<Map<String, Object>> buildPack(@RequestBody Map<String, Object> body) {
        return R.ok(packService.build(str(body.get("locale")), str(body.get("packNo")),
                str(body.get("remark"))));
    }

    @Operation(summary = "发布前预检（P0/P1=100%+总体≥参数 BR-5.7-01/02）")
    @GetMapping("/packs/precheck")
    public R<Map<String, Object>> precheck(@RequestParam String locale) {
        return R.ok(packService.precheck(locale));
    }

    @Operation(summary = "审批（预检不通过 422 阻断）")
    @PostMapping("/packs/{id}/approve")
    public R<Map<String, Object>> approvePack(@PathVariable String id) {
        return R.ok(packService.approve(id));
    }

    @Operation(summary = "启动灰度（回退策略前置）")
    @PostMapping("/packs/{id}/canary")
    public R<Map<String, Object>> startCanary(@PathVariable String id,
                                              @RequestParam(required = false) Integer pct) {
        return R.ok(packService.startCanary(id, pct));
    }

    @Operation(summary = "灰度检查（异常率>0.1% 自动回退 BR-5.7-07）")
    @PostMapping("/packs/{id}/canary-check")
    public R<Map<String, Object>> checkCanary(@PathVariable String id) {
        return R.ok(packService.checkCanary(id));
    }

    @Operation(summary = "归档（版本锁定 C-0-05）")
    @PostMapping("/packs/{id}/archive")
    public R<Map<String, Object>> archivePack(@PathVariable String id) {
        return R.ok(packService.archive(id));
    }

    @Operation(summary = "版本列表")
    @GetMapping("/packs")
    public R<List<Map<String, Object>>> packs(
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String status) {
        return R.ok(packService.list(locale, status));
    }

    @Operation(summary = "当前生效语言包 JSON（前端加载器消费）")
    @GetMapping("/packs/{locale}/current")
    public R<Map<String, Object>> currentPack(@PathVariable String locale) {
        return R.ok(packService.currentPack(locale));
    }

    // ---------- 17.1.2 缺口报告（BR-5.7-05 批量回传） ----------
    @Operation(summary = "缺口批量回传（UPSERT 累加）")
    @PostMapping("/gaps/batch")
    public R<Map<String, Object>> reportGaps(@RequestBody List<Map<String, Object>> items) {
        int n = gapService.reportBatch(items);
        return R.ok(Map.of("processed", n));
    }

    @Operation(summary = "缺口台账")
    @GetMapping("/gaps")
    public R<List<Map<String, Object>>> gaps(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(defaultValue = "100") int limit) {
        return R.ok(gapService.list(status, priority, limit));
    }

    @Operation(summary = "关闭缺口（补译完成）")
    @PostMapping("/gaps/{id}/close")
    public R<Void> closeGap(@PathVariable String id) {
        gapService.close(id);
        return R.ok();
    }

    @Operation(summary = "覆盖率统计（分级 P0~P3）")
    @GetMapping("/gaps/coverage")
    public R<Map<String, Object>> coverage(@RequestParam(required = false) String locale) {
        return R.ok(gapService.coverage(locale));
    }

    // ---------- 17.2 区域配置 ----------
    @Operation(summary = "三语 Locale 格式配置")
    @GetMapping("/locales")
    public R<List<Map<String, Object>>> locales() {
        return R.ok(localeService.list());
    }

    @Operation(summary = "保存格式配置")
    @PostMapping("/locales")
    public R<Map<String, Object>> saveLocale(@RequestBody Map<String, Object> body) {
        return R.ok(localeService.save(body));
    }

    @Operation(summary = "输入格式校验（C-5.7-08）")
    @PostMapping("/locales/validate")
    public R<Map<String, Object>> validateLocale(@RequestParam String locale,
                                                 @RequestBody Map<String, String> fields) {
        return R.ok(localeService.validate(locale, fields));
    }

    @Operation(summary = "格式回退事件（C-5.7-02）")
    @GetMapping("/locales/fallback-events")
    public R<List<Map<String, Object>>> fallbackEvents(@RequestParam(defaultValue = "50") int limit) {
        return R.ok(localeService.fallbackEvents(limit));
    }

    // ---------- 17.5 合规字段包 ----------
    @Operation(summary = "字段包列表")
    @GetMapping("/compliance")
    public R<List<Map<String, Object>>> compliance(
            @RequestParam(required = false) String packCode,
            @RequestParam(required = false) String regPlace) {
        return R.ok(complianceService.list(packCode, regPlace));
    }

    @Operation(summary = "保存字段包定义（BR-5.7-13 禁硬编码）")
    @PostMapping("/compliance")
    public R<Map<String, Object>> saveCompliance(@RequestBody Map<String, Object> body) {
        return R.ok(complianceService.save(body));
    }

    @Operation(summary = "按注册地加载字段（17.5.3 无冗余）")
    @GetMapping("/compliance/load")
    public R<List<Map<String, Object>>> loadCompliance(@RequestParam String regPlace) {
        return R.ok(complianceService.loadByRegPlace(regPlace));
    }

    @Operation(summary = "合规校验（C-5.7-09 阻断）")
    @PostMapping("/compliance/validate")
    public R<Map<String, Object>> validateCompliance(@RequestParam String regPlace,
                                                     @RequestBody Map<String, String> values) {
        return R.ok(complianceService.validate(regPlace, values));
    }

    // ---------- 17.7 i18n SLA 报告（BR-5.7-14） ----------
    @Operation(summary = "生成月度报告")
    @PostMapping("/sla-reports/{period}/generate")
    public R<Map<String, Object>> generateSla(@PathVariable String period) {
        return R.ok(slaService.generate(period));
    }

    @Operation(summary = "报告快照")
    @GetMapping("/sla-reports/{period}")
    public R<Map<String, Object>> slaReport(@PathVariable String period) {
        return R.ok(slaService.report(period));
    }

    @Operation(summary = "归档（DRAFT→ARCHIVED）")
    @PostMapping("/sla-reports/{period}/archive")
    public R<Map<String, Object>> archiveSla(@PathVariable String period) {
        return R.ok(slaService.archive(period));
    }

    @Operation(summary = "对外发布（未归档 422 阻断留痕 BR-5.7-14）")
    @PostMapping("/sla-reports/{period}/publish")
    public R<Map<String, Object>> publishSla(@PathVariable String period,
                                             @RequestParam(required = false) String scope) {
        return R.ok(slaService.publish(period, scope));
    }

    @Operation(summary = "报告列表")
    @GetMapping("/sla-reports")
    public R<List<Map<String, Object>>> slaList() {
        return R.ok(slaService.list());
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
