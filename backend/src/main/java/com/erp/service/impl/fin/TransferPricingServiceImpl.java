package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.TpAssessmentDao;
import com.erp.dao.fin.TpDocumentDao;
import com.erp.dao.fin.TpPartyDao;
import com.erp.entity.fin.TpAssessment;
import com.erp.entity.fin.TpDocument;
import com.erp.entity.fin.TpParty;
import com.erp.service.SysParamService;
import com.erp.service.fin.TransferPricingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 关联交易定价管理实现（transfer-pricing，S-4.14-08）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferPricingServiceImpl implements TransferPricingService {

    private final TpPartyDao partyDao;
    private final TpDocumentDao docDao;
    private final TpAssessmentDao assessDao;
    private final SysParamService sysParamService;

    private static final Set<String> PRICING_METHODS = Set.of("CUP", "CPM", "RPM", "TNMM", "OTHER");
    private static final Set<String> RISK_ITEMS = Set.of(
            "PRICING_NOT_ARMS_LENGTH", "DOCUMENTS_INCOMPLETE",
            "LOCAL_FILE_LATE", "RELATED_DISCLOSURE_ERROR", "NONE");

    // ================================================================
    // 6.1 关联方 + 文档台账
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TpParty registerParty(Map<String, Object> payload) {
        String name = str(payload.get("name"));
        String taxNo = str(payload.get("taxNo"));
        String relType = str(payload.get("relType"));
        require(name, "关联方名称");
        require(taxNo, "纳税人识别号/统一社会信用代码");
        require(relType, "关系类型");
        if (!List.of(TpParty.REL_PARENT, TpParty.REL_COMMON_CONTROL, TpParty.REL_OTHER).contains(relType)) {
            throw new ServiceException(422, "关系类型非法：" + relType);
        }
        if (partyDao.selectCount(new LambdaQueryWrapper<TpParty>()
                .eq(TpParty::getDelFlag, "0")
                .eq(TpParty::getName, name)
                .eq(TpParty::getTaxNo, taxNo)) > 0) {
            throw new ServiceException(422, "重复关联方：" + name);
        }
        TpParty p = new TpParty();
        p.setId(uuid());
        p.setTpNo(nextNo("TPP", partyDao::selectNosByPrefix));
        p.setName(name);
        p.setTaxNo(taxNo);
        p.setRelType(relType);
        p.setOverseasFlag(Boolean.parseBoolean(String.valueOf(payload.getOrDefault("overseas", "false")))
                || "1".equals(String.valueOf(payload.get("overseasFlag"))) ? "1" : "0");
        p.setNote(str(payload.get("note")));
        p.setCreateBy(currentUser());
        partyDao.insert(p);
        log.info("tp party registered: {} {} overseas={}", p.getTpNo(), name, p.getOverseasFlag());
        return p;
    }

    @Override
    public List<TpParty> listParties() {
        return partyDao.selectList(new LambdaQueryWrapper<TpParty>()
                .eq(TpParty::getDelFlag, "0").orderByDesc(TpParty::getCreateDate));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TpDocument registerDocument(Map<String, Object> payload) {
        String partyId = str(payload.get("partyId"));
        String docType = str(payload.get("docType"));
        String coverYear = str(payload.get("coverYear"));
        String method = str(payload.get("pricingMethod"));
        LocalDate validUntil = date(payload.get("validUntil"));
        TpParty party = partyDao.selectById(partyId);
        if (party == null) {
            throw new ServiceException(404, "关联方不存在：" + partyId);
        }
        require(docType, "文档类型");
        require(coverYear, "覆盖年度");
        require(method, "定价方法");
        if (validUntil == null) {
            throw new ServiceException(422, "备查有效期必填");
        }
        if (!List.of(TpDocument.DOC_LOCAL, TpDocument.DOC_MASTER, TpDocument.DOC_COUNTRY).contains(docType)) {
            throw new ServiceException(422, "文档类型非法（LOCAL/MASTER/COUNTRY）：" + docType);
        }
        if (!PRICING_METHODS.contains(method)) {
            throw new ServiceException(422, "定价方法须符合独立交易原则方法族（CUP/CPM/RPM/TNMM/OTHER）：" + method);
        }
        TpDocument d = new TpDocument();
        d.setId(uuid());
        d.setDocNo(nextNo("TPD" + coverYear, docDao::selectNosByPrefix));
        d.setPartyId(party.getId());
        d.setPartyName(party.getName());
        d.setDocType(docType);
        d.setCoverYear(coverYear);
        d.setPricingMethod(method);
        d.setStoragePath(str(payload.get("storagePath")));
        d.setValidUntil(validUntil);
        d.setStatus(validUntil.isBefore(LocalDate.now()) ? TpDocument.ST_EXPIRED : TpDocument.ST_READY);
        d.setCreateBy(currentUser());
        docDao.insert(d);
        log.info("tp document registered: {} {} {} {}", d.getDocNo(), party.getName(), docType, coverYear);
        return d;
    }

    @Override
    public List<TpDocument> listDocuments(String partyId, String coverYear) {
        LambdaQueryWrapper<TpDocument> qw = new LambdaQueryWrapper<TpDocument>()
                .eq(TpDocument::getDelFlag, "0").orderByDesc(TpDocument::getCoverYear);
        if (partyId != null && !partyId.isBlank()) {
            qw.eq(TpDocument::getPartyId, partyId);
        }
        if (coverYear != null && !coverYear.isBlank()) {
            qw.eq(TpDocument::getCoverYear, coverYear);
        }
        return docDao.selectList(qw);
    }

    // ================================================================
    // 6.2 提醒（备查临期 + 年度断档）
    // ================================================================

    @Override
    public Map<String, Object> reminders() {
        int warnDays = intParam("PREF_EXPIRE_WARNING_DAYS", 90);
        LocalDate today = LocalDate.now();
        int currentYear = today.getYear();
        List<Map<String, Object>> expiring = new ArrayList<>();
        for (TpDocument d : docDao.selectList(new LambdaQueryWrapper<TpDocument>()
                .eq(TpDocument::getDelFlag, "0")
                .ne(TpDocument::getStatus, TpDocument.ST_EXPIRED))) {
            long days = d.getValidUntil() == null ? Long.MAX_VALUE
                    : ChronoUnit.DAYS.between(today, d.getValidUntil());
            if (days <= warnDays) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("document", d);
                row.put("daysToExpire", days);
                row.put("suggestedAction", "更新备查文档（独立交易原则备查要求）");
                expiring.add(row);
            }
        }
        // 年度断档：有历史文档的关联方，当年无任何文档
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (TpParty p : listParties()) {
            long hasAny = docDao.selectCount(new LambdaQueryWrapper<TpDocument>()
                    .eq(TpDocument::getDelFlag, "0")
                    .eq(TpDocument::getPartyId, p.getId()));
            if (hasAny == 0) {
                continue;
            }
            long current = docDao.selectCount(new LambdaQueryWrapper<TpDocument>()
                    .eq(TpDocument::getDelFlag, "0")
                    .eq(TpDocument::getPartyId, p.getId())
                    .eq(TpDocument::getCoverYear, String.valueOf(currentYear)));
            if (current == 0) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("party", p);
                row.put("year", currentYear);
                row.put("suggestedAction", currentYear + " 年度定价文档断档，按本地文档/主体文档/国别报告义务补齐");
                gaps.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expiring", expiring);
        out.put("expiringCount", expiring.size());
        out.put("yearGaps", gaps);
        out.put("yearGapCount", gaps.size());
        out.put("warnDays", warnDays);
        return out;
    }

    // ================================================================
    // 年度自评（留痕不阻断）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TpAssessment submitAssessment(Map<String, Object> payload) {
        String year = str(payload.get("year"));
        require(year, "自评年度");
        if (assessDao.selectCount(new LambdaQueryWrapper<TpAssessment>()
                .eq(TpAssessment::getDelFlag, "0")
                .eq(TpAssessment::getAssessYear, year)) > 0) {
            throw new ServiceException(422, "自评年度已存在：" + year);
        }
        Object itemsRaw = payload.get("riskItems");
        List<String> items = new ArrayList<>();
        if (itemsRaw instanceof List) {
            for (Object o : (List<?>) itemsRaw) {
                String item = String.valueOf(o);
                if (!RISK_ITEMS.contains(item)) {
                    throw new ServiceException(422, "风险项非法：" + item);
                }
                items.add(item);
            }
        }
        boolean clean = items.isEmpty() || items.contains("NONE");
        TpAssessment a = new TpAssessment();
        a.setId(uuid());
        a.setAssessNo("TPA" + year + "-" + String.format("%04d",
                assessDao.selectNosByPrefix("TPA" + year + "%").size() + 1));
        a.setAssessYear(year);
        try {
            a.setRiskItemsJson(new ObjectMapper().writeValueAsString(items));
        } catch (Exception e) {
            throw new ServiceException(500, "风险项序列化失败");
        }
        a.setConclusion(clean ? TpAssessment.CONCLUSION_CLEAN : TpAssessment.CONCLUSION_IMPROVE);
        a.setAssessBy(currentUser());
        a.setAssessAt(LocalDateTime.now());
        a.setCreateBy(currentUser());
        assessDao.insert(a);
        log.info("tp assessment submitted: {} {} conclusion {}", a.getAssessNo(), year, a.getConclusion());
        return a;
    }

    @Override
    public List<TpAssessment> listAssessments() {
        return assessDao.selectList(new LambdaQueryWrapper<TpAssessment>()
                .eq(TpAssessment::getDelFlag, "0").orderByDesc(TpAssessment::getAssessYear));
    }

    // ================================================================
    // 内部
    // ================================================================

    private String nextNo(String prefix, java.util.function.Function<String, List<String>> nosFn) {
        List<String> existing = nosFn.apply(prefix + "%");
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
