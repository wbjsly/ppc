package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinVoucherDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mrp.CostSummaryDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.CostSummary;
import com.erp.entity.mrp.MrpMo;
import com.erp.service.SysParamService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.mrp.CostVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 完工凭证实现（change add-cost-aggregation，spec completion-cost-voucher，design D4）。
 * 复用 gl-voucher：头行/编号/平衡/科目 ACTIVE/法人币种 L1 全部由 create 把守；
 * 本服务只负责三段结构、差异补平、幂等与期间卡控。
 */
@Slf4j
@Service
public class CostVoucherServiceImpl implements CostVoucherService {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    /** 借方产成品科目（既有 1405 库存商品） */
    private static final String ACC_GOODS = "1405";
    private static final String ACC_MATERIAL = "500101";
    private static final String ACC_LABOR = "500102";
    private static final String ACC_OVERHEAD = "500103";
    private static final String ACC_VARIANCE = "500191";

    private final CostSummaryDao summaryDao;
    private final MrpMoDao moDao;
    private final MdmItemDao mdmItemDao;
    private final FinVoucherDao voucherDao;
    private final com.erp.dao.fin.FinVoucherLineDao voucherLineDao;
    private final GlVoucherService glVoucherService;
    private final SysParamService sysParam;

    public CostVoucherServiceImpl(CostSummaryDao summaryDao,
                                  MrpMoDao moDao,
                                  MdmItemDao mdmItemDao,
                                  FinVoucherDao voucherDao,
                                  com.erp.dao.fin.FinVoucherLineDao voucherLineDao,
                                  GlVoucherService glVoucherService,
                                  SysParamService sysParam) {
        this.summaryDao = summaryDao;
        this.moDao = moDao;
        this.mdmItemDao = mdmItemDao;
        this.voucherDao = voucherDao;
        this.voucherLineDao = voucherLineDao;
        this.glVoucherService = glVoucherService;
        this.sysParam = sysParam;
    }

    /**
     * REQUIRES_NEW：完工钩子同事务调用时，凭证失败（缺科目等 L1）只回滚凭证自身，
     * 不把完工事务标 rollbackOnly——完工照常提交（spec：L1 阻断的是凭证不是完工）。
     * summary 由钩子传入：本独立事务不回读完工事务未提交数据（goodQty=0 连锁错误教训）。
     */
    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public FinVoucher generateOnAggregation(String moId, CostSummary summary) {
        if (summary == null) {
            return null; // 归集缺失（完工钩子已告警），不生成凭证
        }
        String moNo = summary.getMoNo();
        // 幂等锚：同工单只生成一张（重跑/重触发直接跳过——spec「重复归集不重复生成」）
        Long exists = voucherDao.selectCount(new LambdaQueryWrapper<FinVoucher>()
                .eq(FinVoucher::getSourceType, SOURCE_TYPE)
                .eq(FinVoucher::getSourceDocNo, moNo));
        if (exists != null && exists > 0) {
            return null;
        }
        // 三段结构（金额 2 位小数，gl-voucher 精确到分）：
        // 借 产成品 = 标准×合格产出；贷 生产成本三明细 = 实际；差额 → 生产成本差异判向补平
        BigDecimal dr = nvl(summary.getStdTotalCost()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal crMaterial = nvl(summary.getMaterialCost()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal crLabor = nvl(summary.getLaborCost()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal crOverhead = nvl(summary.getOverheadCost()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal baseCr = crMaterial.add(crLabor).add(crOverhead);
        if (dr.signum() == 0 && baseCr.signum() == 0) {
            return null; // 零金额不生成
        }
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        if (dr.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACC_GOODS, "DR", dr,
                    "完工入库 " + moNo));
        }
        if (crMaterial.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACC_MATERIAL, "CR", crMaterial,
                    "生产成本-直接材料 " + moNo));
        }
        if (crLabor.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACC_LABOR, "CR", crLabor,
                    "生产成本-直接人工 " + moNo));
        }
        if (crOverhead.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACC_OVERHEAD, "CR", crOverhead,
                    "生产成本-制造费用 " + moNo));
        }
        // 差额行判向补平（借产成品=标准，贷三明细=实际）：
        // 标准 > 实际 → 贷差异（差异贷方补足借方）；实际 > 标准 → 借差异
        BigDecimal diff = dr.subtract(baseCr);
        if (diff.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACC_VARIANCE, "CR", diff,
                    "生产成本差异（标准 > 实际）" + moNo));
        } else if (diff.signum() < 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACC_VARIANCE, "DR", diff.abs(),
                    "生产成本差异（实际 > 标准）" + moNo));
        }
        // 期间卡控（design D4/OQ①：COST_CLOSED_PERIODS 命中归集期次 → DRAFT 待下期过账）
        boolean draft = isPeriodClosed(summary.getPeriod());
        FinVoucher v = glVoucherService.create(TYPE_CVC, LocalDate.now(),
                "完工入库结转 " + moNo, SOURCE_TYPE, moNo, null, lines, draft);
        log.info("完工凭证 {} 工单={} 状态={} 期间{}{}",
                v.getVoucherNo(), moNo, v.getStatus(), summary.getPeriod(),
                draft ? "（已关闭 → DRAFT）" : "");
        return v;
    }

    @Override
    public List<Map<String, Object>> list(String status, String period) {
        requireRead("完工凭证查询");
        LambdaQueryWrapper<FinVoucher> qw = new LambdaQueryWrapper<FinVoucher>()
                .eq(FinVoucher::getSourceType, SOURCE_TYPE)
                .eq(hasText(status), FinVoucher::getStatus, trim(status))
                .eq(hasText(period), FinVoucher::getPeriod, trim(period))
                .orderByDesc(FinVoucher::getVoucherDate)
                .orderByDesc(FinVoucher::getVoucherNo);
        List<Map<String, Object>> out = new ArrayList<>();
        for (FinVoucher v : voucherDao.selectList(qw)) {
            out.add(brief(v));
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String voucherId) {
        requireRead("完工凭证明细");
        return glVoucherService.detail(voucherId);
    }

    @Override
    public List<Map<String, Object>> draftList() {
        requireRead("待过账清单");
        List<Map<String, Object>> out = new ArrayList<>();
        for (FinVoucher v : voucherDao.selectList(new LambdaQueryWrapper<FinVoucher>()
                .eq(FinVoucher::getSourceType, SOURCE_TYPE)
                .eq(FinVoucher::getStatus, FinVoucher.STATUS_DRAFT)
                .orderByAsc(FinVoucher::getVoucherDate))) {
            out.add(brief(v));
        }
        return out;
    }

    @Override
    @Transactional
    public void postDraft(String voucherId) {
        requireAnyRole("完工凭证过账", "ROLE_COST_ACCOUNTANT", "ROLE_ADMIN");
        FinVoucher v = voucherId == null ? null : voucherDao.selectById(voucherId);
        if (v == null || !SOURCE_TYPE.equals(v.getSourceType())) {
            throw new ServiceException(422, "完工凭证不存在：" + voucherId);
        }
        if (!FinVoucher.STATUS_DRAFT.equals(v.getStatus())) {
            throw new ServiceException(422, "仅待过账（DRAFT）凭证可过账，当前 " + v.getStatus());
        }
        v.setStatus(FinVoucher.STATUS_POSTED);
        voucherDao.updateById(v);
        log.info("完工凭证 {} 下期过账 POSTED（工单 {}）", v.getVoucherNo(), v.getSourceDocNo());
    }

    /** 月度转销凭证来源类型（幂等锚 = sourceDocNo 期次） */
    static final String SOURCE_VAR_TRANSFER = "MO_VAR_TRANSFER";

    @Override
    @Transactional
    public FinVoucher transferVariance(String period) {
        requireAnyRole("差异转销", "ROLE_COST_ACCOUNTANT", "ROLE_ADMIN");
        if (period == null || !period.matches("\\d{6}")) {
            throw new ServiceException(422, "转销期次须为 yyyyMM 格式");
        }
        // 同期幂等（spec：重复转销 422）
        Long exists = voucherDao.selectCount(new LambdaQueryWrapper<FinVoucher>()
                .eq(FinVoucher::getSourceType, SOURCE_VAR_TRANSFER)
                .eq(FinVoucher::getSourceDocNo, period));
        if (exists != null && exists > 0) {
            throw new ServiceException(422, "该月差异已转销（BR-4.5-29）：" + period);
        }
        // 该期完工凭证中生产成本差异行净额（贷方为正 = 节约差异）
        List<String> voucherIds = voucherDao.selectList(new LambdaQueryWrapper<FinVoucher>()
                        .select(FinVoucher::getId)
                        .eq(FinVoucher::getSourceType, SOURCE_TYPE)
                        .eq(FinVoucher::getPeriod, period))
                .stream().map(FinVoucher::getId).toList();
        if (voucherIds.isEmpty()) {
            return null; // 该期无完工凭证 → 零差异
        }
        List<com.erp.entity.fin.FinVoucherLine> varLines = voucherLineDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.fin.FinVoucherLine>()
                        .eq(com.erp.entity.fin.FinVoucherLine::getAccountCode, ACC_VARIANCE)
                        .in(com.erp.entity.fin.FinVoucherLine::getVoucherId, voucherIds));
        if (varLines.isEmpty()) {
            return null; // 零差异：无差异行不生成（spec）
        }
        BigDecimal net = BigDecimal.ZERO; // 贷方为正
        for (com.erp.entity.fin.FinVoucherLine l : varLines) {
            BigDecimal amt = l.getAmount() == null ? BigDecimal.ZERO : l.getAmount();
            net = "CR".equals(l.getDirection()) ? net.add(amt) : net.subtract(amt);
        }
        net = net.setScale(2, RoundingMode.HALF_UP);
        if (net.signum() == 0) {
            return null; // 净额为 0 不生成（spec 零差异跳过）
        }
        // 转销：贷方余额（节约）→ 借差异/贷销售成本；借方余额（超支）→ 借销售成本/贷差异
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        if (net.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACC_VARIANCE, "DR", net,
                    "月度差异转销 " + period));
            lines.add(GlVoucherService.FinVoucherLineSpec.of("6401", "CR", net,
                    "生产成本差异转主营业务成本 " + period));
        } else {
            BigDecimal abs = net.abs();
            lines.add(GlVoucherService.FinVoucherLineSpec.of("6401", "DR", abs,
                    "主营业务成本承担超支差异 " + period));
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACC_VARIANCE, "CR", abs,
                    "月度差异转销 " + period));
        }
        FinVoucher v = glVoucherService.create("CVT", LocalDate.now(),
                "生产成本差异月度转销 " + period, SOURCE_VAR_TRANSFER, period, null, lines);
        log.info("差异转销凭证 {} 期次={} 净额={}（转销售成本 6401）", v.getVoucherNo(), period, net);
        return v;
    }

    // ---------- helpers ----------

    /** 会计期间已关：COST_CLOSED_PERIODS（逗号分隔 yyyyMM）命中归集期次 */
    private boolean isPeriodClosed(String period) {
        if (!hasText(period)) {
            return false;
        }
        String raw = sysParam.getValue("COST_CLOSED_PERIODS", "");
        if (!hasText(raw)) {
            return false;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim).anyMatch(period::equals);
    }

    private Map<String, Object> brief(FinVoucher v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("voucherNo", v.getVoucherNo());
        m.put("voucherType", v.getVoucherType());
        m.put("voucherDate", v.getVoucherDate());
        m.put("period", v.getPeriod());
        m.put("summary", v.getSummary());
        m.put("sourceDocNo", v.getSourceDocNo());
        m.put("status", v.getStatus());
        m.put("totalDr", v.getTotalDr());
        m.put("totalCr", v.getTotalCr());
        return m;
    }

    private void requireRead(String action) {
        requireAnyRole(action, "ROLE_COST_ACCOUNTANT", "ROLE_FINANCE",
                "ROLE_FINANCE_MGR", "ROLE_ADMIN");
    }

    private void requireAnyRole(String action, String... allowed) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String want : allowed) {
            if (auth.getAuthorities().contains(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(want))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action + "（需 " + String.join("/", allowed) + "）");
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
