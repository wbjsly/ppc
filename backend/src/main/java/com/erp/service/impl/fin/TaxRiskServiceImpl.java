package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.OfflineInvoiceDao;
import com.erp.dao.fin.TaxCertDao;
import com.erp.entity.fin.OfflineInvoice;
import com.erp.entity.fin.TaxCert;
import com.erp.service.SysParamService;
import com.erp.service.fin.TaxRiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 税控/数字证书 + 离线开票实现（C-4.14-14，L3）。
 * 到期判定为查询/校验时惰性计算（design D4，不建 cron）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxRiskServiceImpl implements TaxRiskService {

    private final TaxCertDao certDao;
    private final OfflineInvoiceDao offlineDao;
    private final SysParamService sysParamService;

    // ================================================================
    // 3.1 证书台账 + 到期预警
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaxCert registerCert(Map<String, Object> payload) {
        String certType = str(payload.get("certType"));
        String certName = str(payload.get("certName"));
        String certNo = str(payload.get("certNo"));
        LocalDate issueDate = date(payload.get("issueDate"));
        LocalDate expireDate = date(payload.get("expireDate"));
        require(certType, "证书类型");
        require(certName, "名称");
        require(certNo, "证书/设备编号");
        if (expireDate == null) {
            throw new ServiceException(422, "到期日必填");
        }
        if (issueDate == null) {
            issueDate = LocalDate.now();
        }
        if (certDao.selectCount(new LambdaQueryWrapper<TaxCert>()
                .eq(TaxCert::getDelFlag, "0").eq(TaxCert::getCertNo, certNo)) > 0) {
            throw new ServiceException(422, "证书/设备编号已存在：" + certNo);
        }
        TaxCert c = new TaxCert();
        c.setId(uuid());
        c.setCertType(certType);
        c.setCertName(certName);
        c.setCertNo(certNo);
        c.setIssueDate(issueDate);
        c.setExpireDate(expireDate);
        c.setStatus(expireDate.isBefore(LocalDate.now()) ? TaxCert.ST_EXPIRED : TaxCert.ST_VALID);
        c.setChannel(str(payload.get("channel")));
        c.setCreateBy(currentUser());
        certDao.insert(c);
        log.info("tax cert registered: {} expire {} channel {}", certNo, expireDate, c.getChannel());
        return c;
    }

    @Override
    public List<Map<String, Object>> listCerts(String status) {
        LambdaQueryWrapper<TaxCert> qw = new LambdaQueryWrapper<TaxCert>()
                .eq(TaxCert::getDelFlag, "0")
                .orderByAsc(TaxCert::getExpireDate);
        if (status != null && !status.isBlank()) {
            qw.eq(TaxCert::getStatus, status);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaxCert c : certDao.selectList(qw)) {
            lazyExpireIfDue(c);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cert", c);
            row.put("daysToExpire", c.getExpireDate() == null ? null
                    : Duration.between(LocalDate.now().atStartOfDay(),
                            c.getExpireDate().atStartOfDay()).toDays());
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaxCert updateCert(String certId, Map<String, Object> payload) {
        TaxCert c = certDao.selectById(certId);
        if (c == null) {
            throw new ServiceException(404, "证书/设备不存在：" + certId);
        }
        LocalDate newExpire = date(payload.get("expireDate"));
        if (newExpire == null) {
            throw new ServiceException(422, "新到期日必填");
        }
        c.setIssueDate(LocalDate.now());
        c.setExpireDate(newExpire);
        c.setStatus(TaxCert.ST_VALID);
        if (payload.get("channel") != null) {
            c.setChannel(str(payload.get("channel")));
        }
        c.setUpdateBy(currentUser());
        c.setUpdateDate(LocalDateTime.now());
        certDao.updateById(c);
        log.info("tax cert updated: {} new expire {} → VALID（通道阻断解除）", c.getCertNo(), newExpire);
        return c;
    }

    @Override
    public Map<String, Object> certWarnings() {
        int warnDays = intParam("TAX_CERT_WARNING_DAYS", 30);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> expiring = new ArrayList<>();
        List<Map<String, Object>> unusable = new ArrayList<>();
        for (TaxCert c : certDao.selectList(new LambdaQueryWrapper<TaxCert>()
                .eq(TaxCert::getDelFlag, "0")
                .ne(TaxCert::getStatus, TaxCert.ST_DISABLED))) {
            boolean expired = lazyExpireIfDue(c);
            long days = c.getExpireDate() == null ? Long.MAX_VALUE
                    : Duration.between(today.atStartOfDay(), c.getExpireDate().atStartOfDay()).toDays();
            if (expired || TaxCert.ST_EXPIRED.equals(c.getStatus())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cert", c);
                row.put("suggestedAction", "更新证书/改用数电票通道（C-4.14-14）");
                unusable.add(row);
            } else if (days <= warnDays) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cert", c);
                row.put("daysToExpire", days);
                row.put("suggestedAction", "到期前完成证书更新与税控设备发行");
                expiring.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expiring", expiring);
        out.put("expiringCount", expiring.size());
        out.put("unusable", unusable);
        out.put("unusableCount", unusable.size());
        out.put("warnDays", warnDays);
        return out;
    }

    @Override
    public void assertChannelUsable(String channel) {
        if (channel == null || channel.isBlank()) {
            return; // 未绑定税控通道（数电主通道不受 C-4.14-14 卡控，design D6）
        }
        List<TaxCert> certs = certDao.selectList(new LambdaQueryWrapper<TaxCert>()
                .eq(TaxCert::getDelFlag, "0")
                .eq(TaxCert::getChannel, channel)
                .ne(TaxCert::getStatus, TaxCert.ST_DISABLED));
        for (TaxCert c : certs) {
            boolean expired = lazyExpireIfDue(c);
            if (expired || TaxCert.ST_EXPIRED.equals(c.getStatus())) {
                throw new ServiceException(422,
                        "C-4.14-14 通道不可用：证书/设备 " + c.getCertNo()
                                + " 已于 " + c.getExpireDate() + " 过期，开票请求转待开队列；"
                                + "请更新证书后补开或改用数电票通道");
            }
        }
    }

    // ================================================================
    // 3.3 离线开票（48h 补传告警）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OfflineInvoice registerOffline(Map<String, Object> payload) {
        LocalDateTime openTime = dateTime(payload.get("openTime"));
        String reason = str(payload.get("reason"));
        String channel = str(payload.get("channel"));
        if (openTime == null) {
            openTime = LocalDateTime.now();
        }
        require(reason, "离线原因");
        // C-4.14-14：绑定通道证书过期 → 阻断（不落入可用通道）
        assertChannelUsable(channel);

        OfflineInvoice o = new OfflineInvoice();
        o.setId(uuid());
        o.setOfflineNo(nextNo());
        o.setInvoiceApplyNo(str(payload.get("invoiceApplyNo")));
        o.setOpenTime(openTime);
        o.setReason(reason);
        o.setAlertFlag("0");
        o.setCreateBy(currentUser());
        offlineDao.insert(o);
        log.info("offline invoice registered: {} channel {}", o.getOfflineNo(), channel);
        return o;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OfflineInvoice uploadOffline(String offlineId) {
        OfflineInvoice o = offlineDao.selectById(offlineId);
        if (o == null) {
            throw new ServiceException(404, "离线开票单不存在：" + offlineId);
        }
        o.setUploadTime(LocalDateTime.now());
        o.setAlertFlag("0");
        o.setUpdateBy(currentUser());
        o.setUpdateDate(LocalDateTime.now());
        offlineDao.updateById(o);
        log.info("offline invoice uploaded: {} alert cleared", o.getOfflineNo());
        return o;
    }

    @Override
    public List<OfflineInvoice> listOffline() {
        return offlineDao.selectList(new LambdaQueryWrapper<OfflineInvoice>()
                .eq(OfflineInvoice::getDelFlag, "0")
                .orderByDesc(OfflineInvoice::getOpenTime));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> offlineAlerts() {
        int hours = intParam("OFFLINE_INVOICE_UPLOAD_HOURS", 48);
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (OfflineInvoice o : offlineDao.selectList(new LambdaQueryWrapper<OfflineInvoice>()
                .eq(OfflineInvoice::getDelFlag, "0")
                .isNull(OfflineInvoice::getUploadTime))) {
            long elapsed = Duration.between(o.getOpenTime(), now).toHours();
            if (elapsed > hours) {
                if (!"1".equals(o.getAlertFlag())) {
                    o.setAlertFlag("1");
                    o.setUpdateBy("system");
                    o.setUpdateDate(now);
                    offlineDao.updateById(o);
                    log.info("offline invoice L4 alert: {} elapsed {}h", o.getOfflineNo(), elapsed);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("offlineNo", o.getOfflineNo());
                row.put("openTime", o.getOpenTime());
                row.put("elapsedHours", elapsed);
                row.put("overHours", elapsed - hours);
                row.put("level", "L4");
                row.put("suggestedAction", "通道恢复后及时补传离线开票数据");
                alerts.add(row);
            }
        }
        return alerts;
    }

    // ================================================================
    // 内部
    // ================================================================

    /** 惰性过期（design D4）：止日已过 → EXPIRED 回写 */
    private boolean lazyExpireIfDue(TaxCert c) {
        boolean due = c.getExpireDate() != null && c.getExpireDate().isBefore(LocalDate.now());
        if (due && !TaxCert.ST_EXPIRED.equals(c.getStatus())) {
            c.setStatus(TaxCert.ST_EXPIRED);
            c.setUpdateBy("system");
            c.setUpdateDate(LocalDateTime.now());
            certDao.updateById(c);
        }
        return due;
    }

    private String nextNo() {
        String prefix = "OFL" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMM"));
        List<String> existing = offlineDao.selectNosByPrefix(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length() + 1)));
            } catch (Exception ignored) {
                // 流水后缀非数字跳过
            }
        }
        return prefix + "-" + String.format("%04d", max + 1);
    }

    private int intParam(String key, int dflt) {
        String v = sysParamService.getValue(key);
        try {
            return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ServiceException(422, field + "必填");
        }
    }

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        return LocalDate.parse(String.valueOf(o).trim().substring(0, 10));
    }

    private static LocalDateTime dateTime(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.length() == 10 ? LocalDateTime.parse(s + "T00:00:00") : LocalDateTime.parse(s);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
