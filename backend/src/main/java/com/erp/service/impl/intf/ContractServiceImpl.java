package com.erp.service.impl.intf;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfCallLogDao;
import com.erp.dao.intf.IntfChannelDao;
import com.erp.dao.intf.IntfContractDao;
import com.erp.dao.intf.IntfContractReviewDao;
import com.erp.dao.intf.IntfReleaseDao;
import com.erp.dao.intf.IntfSandboxCaseDao;
import com.erp.entity.intf.IntfCallLog;
import com.erp.entity.intf.IntfChannel;
import com.erp.entity.intf.IntfContract;
import com.erp.entity.intf.IntfContractReview;
import com.erp.entity.intf.IntfRelease;
import com.erp.entity.intf.IntfSandboxCase;
import com.erp.entity.intf.IntfSlaAlert;
import com.erp.security.IntfGuard;
import com.erp.service.intf.ContractService;
import com.erp.service.intf.CredentialService;
import com.erp.service.intf.OpenApiGatewayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 接入治理（spec interface-onboarding，SOP-5.5-A 步骤 1~8）。 */
@Slf4j
@Service
public class ContractServiceImpl implements ContractService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final IntfContractDao contractDao;
    private final IntfContractReviewDao reviewDao;
    private final IntfSandboxCaseDao caseDao;
    private final IntfReleaseDao releaseDao;
    private final IntfChannelDao channelDao;
    private final IntfCallLogDao callLogDao;
    private final OpenApiGatewayService gateway;
    private final CredentialService credentialService;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Value("${app.intf.contract-review-days:3}") private int reviewDays;
    @Value("${app.intf.observe-days:7}") private int observeDays;
    @Value("${app.intf.api-sunset-notice-days:30}") private int sunsetDays;
    @Value("${app.intf.keep-version-days:180}") private int keepVersionDays;

    public ContractServiceImpl(IntfContractDao contractDao, IntfContractReviewDao reviewDao,
                               IntfSandboxCaseDao caseDao, IntfReleaseDao releaseDao,
                               IntfChannelDao channelDao, IntfCallLogDao callLogDao,
                               OpenApiGatewayService gateway, CredentialService credentialService) {
        this.contractDao = contractDao;
        this.reviewDao = reviewDao;
        this.caseDao = caseDao;
        this.releaseDao = releaseDao;
        this.channelDao = channelDao;
        this.callLogDao = callLogDao;
        this.gateway = gateway;
        this.credentialService = credentialService;
    }

    // ------------------------------------------------------------ 契约登记与评审

    @Override
    public Map<String, Object> create(Map<String, Object> p) {
        String name = str(p.get("name"));
        if (name == null) {
            throw new ServiceException(400, "契约名称必填");
        }
        if (p.get("desensitizeRule") == null || String.valueOf(p.get("desensitizeRule")).trim().isEmpty()) {
            // C-5.5-11：存在敏感字段却未声明脱敏规则 → 阻断发布
            throw new ServiceException(422, "存在未声明脱敏规则的敏感字段，请补充脱敏规则后方可保存");
        }
        IntfContract c = new IntfContract();
        c.setContractNo(nextNo("IC"));
        c.setName(name);
        c.setPartnerCode(str(p.get("partnerCode")));
        c.setProtocol(str(p.get("protocol")) == null ? "REST" : str(p.get("protocol")));
        c.setMsgStandard(str(p.get("msgStandard")));
        c.setEndpointPath(str(p.get("endpointPath")));
        c.setFieldsJson(json(p.get("fieldsJson")));
        c.setErrorCodes(str(p.get("errorCodes")));
        c.setRateTier(str(p.get("rateTier")));
        c.setIdempotencyRule(str(p.get("idempotencyRule")));
        c.setDesensitizeRule(json(p.get("desensitizeRule")));
        c.setVersion("v1.0.0");
        c.setMajorVersion(1);
        c.setStatus(IntfContract.ST_DRAFT);
        c.setRejectCount(0);
        contractDao.insert(c);
        return Map.of("id", c.getId(), "contractNo", c.getContractNo(), "version", c.getVersion(),
                "status", c.getStatus());
    }

    @Override
    public Map<String, Object> submitReview(String contractId) {
        IntfContract c = require(contractId);
        if (IntfContract.ST_REVIEWING.equals(c.getStatus())) {
            throw new ServiceException(422, "契约已在评审中");
        }
        IntfContractReview r = new IntfContractReview();
        r.setContractId(c.getId());
        r.setReviewer(IntfGuard.currentUser());
        r.setResult("PENDING");
        r.setOpinion("");
        r.setDueAt(addWorkdays(LocalDateTime.now(), reviewDays));
        reviewDao.insert(r);
        c.setStatus(IntfContract.ST_REVIEWING);
        c.setReviewDueAt(r.getDueAt());
        contractDao.updateById(c);
        return Map.of("id", c.getId(), "status", c.getStatus(), "reviewDueAt", r.getDueAt());
    }

    @Override
    public Map<String, Object> review(String contractId, String result, String opinion) {
        IntfContract c = require(contractId);
        if (opinion == null || opinion.trim().length() < 2) {
            throw new ServiceException(400, "审核意见必填（不少于 2 字）");
        }
        IntfContractReview r = new IntfContractReview();
        r.setContractId(c.getId());
        r.setReviewer(IntfGuard.currentUser());
        r.setResult("PASS".equalsIgnoreCase(result) ? "PASS" : "REJECT");
        r.setOpinion(opinion);
        r.setReviewAt(LocalDateTime.now());
        reviewDao.insert(r);

        if ("PASS".equalsIgnoreCase(result)) {
            c.setStatus(IntfContract.ST_PUBLISHED);
            c.setPublishAt(LocalDateTime.now());
        } else {
            int rejects = (c.getRejectCount() == null ? 0 : c.getRejectCount()) + 1;
            c.setRejectCount(rejects);
            if (rejects >= 3) {
                // 驳回 ≥3 次自动关闭（SOP-5.5-A 步骤2）
                c.setStatus(IntfContract.ST_DRAFT);
                c.setCloseReason("累计驳回 3 次，需求自动关闭");
                c.setStatus("CLOSED");
            } else {
                c.setStatus(IntfContract.ST_DRAFT);
            }
        }
        contractDao.updateById(c);
        return Map.of("id", c.getId(), "status", c.getStatus(), "rejectCount",
                c.getRejectCount() == null ? 0 : c.getRejectCount());
    }

    // ------------------------------------------------------------ 版本治理

    @Override
    public Map<String, Object> changeVersion(String contractId, Map<String, Object> p) {
        IntfContract c = require(contractId);
        boolean incompatible = Boolean.TRUE.equals(p.get("incompatible"));
        String newVersion = str(p.get("version"));
        int major = c.getMajorVersion() == null ? 1 : c.getMajorVersion();
        if (incompatible) {
            // BR-5.5-12：不兼容变更必须升 Major，旧版本保留 ≥180 天
            int expected = major + 1;
            if (newVersion == null || !newVersion.startsWith("v" + expected + ".")) {
                throw new ServiceException(422, "不兼容变更必须升 Major 版本号（当前 v" + major
                        + ".x → 应为 v" + expected + ".x），并保留旧版本 ≥" + keepVersionDays + " 天");
            }
            c.setPrevVersion(c.getVersion());
            c.setMajorVersion(expected);
            c.setCompatLevel("INCOMPATIBLE");
            c.setKeepUntil(LocalDate.now().plusDays(keepVersionDays));
            c.setSunsetAt(LocalDate.now().plusDays(sunsetDays));
        } else {
            c.setCompatLevel("COMPATIBLE");
        }
        if (newVersion != null) {
            c.setVersion(newVersion);
        }
        if (p.get("fieldsJson") != null) {
            c.setFieldsJson(json(p.get("fieldsJson")));
        }
        if (p.get("desensitizeRule") != null) {
            c.setDesensitizeRule(json(p.get("desensitizeRule")));
        }
        c.setStatus(IntfContract.ST_PUBLISHED);
        c.setPublishAt(LocalDateTime.now());
        contractDao.updateById(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.getId());
        out.put("version", c.getVersion());
        out.put("compatLevel", c.getCompatLevel());
        out.put("keepUntil", c.getKeepUntil());
        out.put("sunsetAt", c.getSunsetAt());
        return out;
    }

    // ------------------------------------------------------------ 沙箱与放行

    @Override
    public Map<String, Object> passRate(String contractId) {
        int total = caseDao.countTotal(contractId);
        int passed = caseDao.countPassed(contractId);
        BigDecimal rate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(passed).multiply(HUNDRED).divide(BigDecimal.valueOf(total), 2,
                RoundingMode.HALF_UP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contractId", contractId);
        out.put("total", total);
        out.put("passed", passed);
        out.put("failed", total - passed);
        out.put("passRate", rate);
        out.put("eligible", total > 0 && passed == total);
        return out;
    }

    @Override
    public Map<String, Object> requestRelease(Map<String, Object> p) {
        String contractId = str(p.get("contractId"));
        if (contractId == null) {
            throw new ServiceException(400, "contractId 必填");
        }
        IntfContract c = require(contractId);
        Map<String, Object> rate = passRate(contractId);
        BigDecimal passRate = (BigDecimal) rate.get("passRate");
        if (!Boolean.TRUE.equals(rate.get("eligible"))) {
            // C-5.5-07 / BR-5.5-02：通过率 < 100% 硬阻断并逐条列出未通过用例
            List<Map<String, Object>> failedCases = new ArrayList<>();
            for (IntfSandboxCase cs : caseDao.selectList(new LambdaQueryWrapper<IntfSandboxCase>()
                    .eq(IntfSandboxCase::getContractId, contractId)
                    .eq(IntfSandboxCase::getResult, "FAIL"))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("caseNo", cs.getCaseNo());
                m.put("fieldPath", cs.getFieldPath());
                m.put("errorLoc", cs.getErrorLoc());
                m.put("actual", cs.getActual());
                failedCases.add(m);
            }
            throw new ServiceException(422, "沙箱联调通过率 " + passRate + "% 未达 100%，不可生成生产放行单；"
                    + "未通过用例 " + failedCases.size() + " 条：" + toJson(failedCases));
        }

        IntfRelease rel = new IntfRelease();
        rel.setReleaseNo(nextNo("RL"));
        rel.setContractId(contractId);
        rel.setPartnerCode(c.getPartnerCode());
        IntfChannel ch = c.getPartnerCode() == null ? null : channelDao.selectByPartnerCode(c.getPartnerCode());
        rel.setChannelId(ch == null ? null : ch.getId());
        rel.setPassRate(passRate);
        rel.setSecurityNote(str(p.get("securityNote")));
        rel.setReceiptSample(str(p.get("receiptSample")));
        rel.setStatus(IntfRelease.ST_PENDING);
        rel.setInitiatedBy(IntfGuard.currentUser());
        rel.setInitiatedAt(LocalDateTime.now());
        rel.setReviewDueAt(LocalDateTime.now().plusDays(2));
        releaseDao.insert(rel);
        return Map.of("id", rel.getId(), "releaseNo", rel.getReleaseNo(), "status", rel.getStatus(),
                "passRate", rel.getPassRate(), "initiatedBy", rel.getInitiatedBy());
    }

    @Override
    public Map<String, Object> reviewRelease(String releaseId) {
        // 高危动作：服务端二次校验 ADMIN（design D5）
        IntfGuard.requireAdmin("生产放行会签");
        IntfRelease rel = requireRelease(releaseId);
        if (!IntfRelease.ST_PENDING.equals(rel.getStatus())) {
            throw new ServiceException(422, "放行单状态非待复核：" + rel.getStatus());
        }
        if (rel.getInitiatedBy() != null && rel.getInitiatedBy().equals(IntfGuard.currentUser())) {
            // C-0-03：复核人与发起人不得为同一人
            throw new ServiceException(422, "复核人不得与发起人相同（发起人 " + rel.getInitiatedBy() + "）");
        }
        rel.setStatus(IntfRelease.ST_APPROVED);
        rel.setReviewedBy(IntfGuard.currentUser());
        rel.setReviewedAt(LocalDateTime.now());
        LocalDate today = LocalDate.now();
        rel.setTrialStart(today);
        rel.setTrialEnd(today.plusDays(observeDays));
        rel.setTrialErrRate(BigDecimal.ZERO);
        rel.setTrialLimitHits(0);

        // 签发生产凭证 + 通道切 TRIAL（SOP-5.5-A 步骤7）
        Map<String, Object> cred = credentialService.issue(rel.getPartnerCode(),
                "EDI_WRITE,PO_READ,SLA_READ", "NORMAL", "PROD", null, "生产放行 " + rel.getReleaseNo() + " 签发");
        rel.setProdKeyId(str(cred.get("id")));
        if (rel.getChannelId() != null) {
            IntfChannel ch = channelDao.selectById(rel.getChannelId());
            if (ch != null) {
                ch.setStatus(IntfChannel.ST_TRIAL);
                channelDao.updateById(ch);
            }
        }
        releaseDao.updateById(rel);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rel.getId());
        out.put("status", rel.getStatus());
        out.put("reviewedBy", rel.getReviewedBy());
        out.put("trialEnd", rel.getTrialEnd());
        out.put("prodApiKey", cred.get("apiKey"));
        out.put("prodSecret", cred.get("secret"));
        return out;
    }

    @Override
    public Map<String, Object> completeObservation(String releaseId) {
        IntfRelease rel = requireRelease(releaseId);
        if (!IntfRelease.ST_APPROVED.equals(rel.getStatus())
                && !IntfRelease.ST_TRIAL.equals(rel.getStatus())) {
            throw new ServiceException(422, "放行单不在观察期：" + rel.getStatus());
        }
        if (rel.getTrialEnd() != null && LocalDate.now().isBefore(rel.getTrialEnd())) {
            throw new ServiceException(422, "观察期未满（截止 " + rel.getTrialEnd() + "），不可转正式运行");
        }
        Map<String, Object> daily = observationDaily(releaseId);
        if (new BigDecimal(String.valueOf(daily.get("errorRate"))).compareTo(new BigDecimal("1")) > 0) {
            // 观察期内错误率 > 1% 触发复盘
            throw new ServiceException(422, "观察期错误率 " + daily.get("errorRate")
                    + "% 超过 1%，须先完成复盘（SOP-5.5-A 步骤8）");
        }
        rel.setStatus(IntfRelease.ST_FORMAL);
        rel.setRemark("观察期完成，试运行转正式运行");
        releaseDao.updateById(rel);
        if (rel.getChannelId() != null) {
            IntfChannel ch = channelDao.selectById(rel.getChannelId());
            if (ch != null) {
                ch.setStatus(IntfChannel.ST_PROD);
                channelDao.updateById(ch);
            }
        }
        return Map.of("id", rel.getId(), "status", rel.getStatus());
    }

    @Override
    public Map<String, Object> archive(String releaseId) {
        IntfRelease rel = requireRelease(releaseId);
        if (!IntfRelease.ST_FORMAL.equals(rel.getStatus())) {
            throw new ServiceException(422, "仅正式运行状态可归档，当前 " + rel.getStatus());
        }
        rel.setStatus(IntfRelease.ST_ARCHIVED);
        rel.setArchiveAt(LocalDateTime.now());
        releaseDao.updateById(rel);
        return Map.of("id", rel.getId(), "status", rel.getStatus(), "archiveAt", rel.getArchiveAt());
    }

    @Override
    public Map<String, Object> observationDaily(String releaseId) {
        IntfRelease rel = requireRelease(releaseId);
        List<IntfCallLog> rows = callLogDao.selectList(new LambdaQueryWrapper<IntfCallLog>()
                .eq(IntfCallLog::getPartnerCode, rel.getPartnerCode())
                .ge(IntfCallLog::getCallAt, rel.getTrialStart() == null ? LocalDate.now().atStartOfDay()
                        : rel.getTrialStart().atStartOfDay()));
        int total = rows.size();
        int failed = 0;
        int rateHits = 0;
        List<Integer> costs = new ArrayList<>();
        for (IntfCallLog r : rows) {
            if (r.getRespCode() != null && r.getRespCode() >= 400) {
                failed++;
            }
            if (Boolean.TRUE.equals(r.getRateHit())) {
                rateHits++;
            }
            if (r.getCostMs() != null) {
                costs.add(r.getCostMs());
            }
        }
        costs.sort(Integer::compareTo);
        int p95 = costs.isEmpty() ? 0 : costs.get((int) Math.ceil(costs.size() * 0.95) - 1);
        BigDecimal errRate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(failed).multiply(HUNDRED).divide(BigDecimal.valueOf(total), 2,
                RoundingMode.HALF_UP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("releaseNo", rel.getReleaseNo());
        out.put("trialStart", rel.getTrialStart());
        out.put("trialEnd", rel.getTrialEnd());
        out.put("totalCalls", total);
        out.put("failed", failed);
        out.put("errorRate", errRate);
        out.put("p95", p95);
        out.put("rateHits", rateHits);
        out.put("review", errRate.compareTo(new BigDecimal("1")) > 0 ? "错误率>1% 须复盘"
                : (rateHits > 10 ? "限流触发>10 次须重评配额" : "观察指标正常"));
        return out;
    }

    @Override
    public int sweep() {
        int n = 0;
        LocalDateTime now = LocalDateTime.now();
        // 1) 评审超时催办（3 个工作日）
        for (IntfContractReview r : reviewDao.selectList(new LambdaQueryWrapper<IntfContractReview>()
                .eq(IntfContractReview::getResult, "PENDING")
                .isNotNull(IntfContractReview::getDueAt)
                .lt(IntfContractReview::getDueAt, now)
                .isNull(IntfContractReview::getRemindAt))) {
            r.setRemindAt(now);
            reviewDao.updateById(r);
            gateway.raiseAlert("CONTRACT_REVIEW_OVERDUE:" + r.getContractId(), "ONBOARDING",
                    IntfSlaAlert.LVL_WARNING, "接口契约评审已超 " + reviewDays + " 个工作日，自动催办并抄送部门负责人", null);
            n++;
        }
        // 2) 废弃倒计时预告（≤ API_SUNSET_NOTICE_DAYS，按日推送，BR-5.5-13）
        for (IntfContract c : contractDao.selectList(new LambdaQueryWrapper<IntfContract>()
                .eq(IntfContract::getStatus, IntfContract.ST_PUBLISHED)
                .isNotNull(IntfContract::getSunsetAt)
                .le(IntfContract::getSunsetAt, LocalDate.now().plusDays(sunsetDays)))) {
            long left = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), c.getSunsetAt());
            if (left >= 0) {
                gateway.raiseAlert("CONTRACT_SUNSET:" + c.getId() + ":" + LocalDate.now(), "ONBOARDING",
                        IntfSlaAlert.LVL_WARNING,
                        "接口版本 " + c.getVersion() + " 距废弃日剩 " + left + " 天，已向存量调用方推送预告（响应头 Sunset）", null);
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------ 查询

    @Override
    public Page<IntfContract> contractPage(long current, long size, String partnerCode, String status) {
        LambdaQueryWrapper<IntfContract> qw = new LambdaQueryWrapper<>();
        qw.eq(partnerCode != null && !partnerCode.trim().isEmpty(), IntfContract::getPartnerCode, partnerCode == null ? "" : partnerCode.trim());
        qw.eq(status != null && !status.trim().isEmpty(), IntfContract::getStatus, status == null ? "" : status.trim());
        qw.orderByDesc(IntfContract::getCreateDate);
        return contractDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Page<IntfRelease> releasePage(long current, long size, String status) {
        LambdaQueryWrapper<IntfRelease> qw = new LambdaQueryWrapper<>();
        qw.eq(status != null && !status.trim().isEmpty(), IntfRelease::getStatus, status == null ? "" : status.trim());
        qw.orderByDesc(IntfRelease::getCreateDate);
        return releaseDao.selectPage(new Page<>(current, size), qw);
    }

    // ------------------------------------------------------------ helpers

    private IntfContract require(String id) {
        IntfContract c = contractDao.selectById(id);
        if (c == null) {
            throw new ServiceException(404, "接口契约不存在");
        }
        return c;
    }

    private IntfRelease requireRelease(String id) {
        IntfRelease r = releaseDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "生产放行单不存在");
        }
        return r;
    }

    private String nextNo(String prefix2) {
        String prefix = prefix2 + LocalDateTime.now().format(NO_FMT);
        Integer max = prefix2.equals("IC") ? contractDao.selectMaxSeq(prefix) : releaseDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private static LocalDateTime addWorkdays(LocalDateTime from, int days) {
        LocalDateTime t = from;
        int added = 0;
        while (added < days) {
            t = t.plusDays(1);
            if (t.getDayOfWeek().getValue() < 6) {
                added++;
            }
        }
        return t;
    }

    private String json(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
