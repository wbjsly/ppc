package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpMoFreezeDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoFreeze;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.system.SysNotice;
import com.erp.service.SysParamService;
import com.erp.service.mrp.MoFreezeService;
import com.erp.service.mrp.ReportingStatsService;
import com.erp.service.qms.NcrService;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 良率冻结实现（design D4：活动行 upsert + 双挂点断言 + 回拨/NCR 关闭双解冻）。
 * 冻结作用域 YIELD_FREEZE_SCOPE（默认 MO——偏差 D6 不误伤同 WC 急单；WC=规格字面冻全线）。
 * NCR 联动：本变更报工来源 NCR 由 3.5 创建后回填 ncrId；NCR 关闭回调调 {@link #release}。
 */
@Slf4j
@Service
public class MoFreezeServiceImpl implements MoFreezeService {

    private final MrpMoFreezeDao freezeDao;
    private final MrpMoDao moDao;
    private final MrpMoOpDao moOpDao;
    private final ReportingStatsService statsService;
    private final NoticeService noticeService;
    private final SysParamService sysParam;
    /** 良率超差自动建 NCR（NcrServiceImpl 的 freeze 回调为 @Lazy——环已断） */
    private final NcrService ncrService;

    public MoFreezeServiceImpl(MrpMoFreezeDao freezeDao, MrpMoDao moDao, MrpMoOpDao moOpDao,
                               ReportingStatsService statsService, NoticeService noticeService,
                               SysParamService sysParam, NcrService ncrService) {
        this.freezeDao = freezeDao;
        this.moDao = moDao;
        this.moOpDao = moOpDao;
        this.statsService = statsService;
        this.noticeService = noticeService;
        this.sysParam = sysParam;
        this.ncrService = ncrService;
    }

    @Override
    @Transactional
    public Map<String, Object> evaluateAndFreeze(String moId, Integer opSeq, String rptNo) {
        Map<String, Object> d = statsService.deviationExceeded(moId, opSeq);
        boolean exceeded = Boolean.TRUE.equals(d.get("exceeded"));
        Map<String, Object> out = new HashMap<>();
        out.put("deviation", d.get("deviation"));
        out.put("actualYield", d.get("actualYield"));
        MrpMoFreeze active = activeOf(moId);

        if (!exceeded) {
            // 回拨解冻（冲销后良率回落 ≤ 阈值）——仅解 YIELD 原因冻结；
            // NCR 原因冻结（不合格产出触发）只能由 NCR 关闭/作废解（防误解放行）
            if (active != null && MrpMoFreeze.REASON_YIELD.equals(active.getReason())) {
                release(moId, "良率回拨至阈值内（偏差 " + d.get("deviation") + "%）", "SYSTEM");
                out.put("frozen", false);
                out.put("released", true);
            } else {
                out.put("frozen", active != null);
            }
            return out;
        }

        BigDecimal deviation = (BigDecimal) d.get("deviation");
        BigDecimal actual = (BigDecimal) d.get("actualYield");
        String scopeParam = sysParam.getValue("YIELD_FREEZE_SCOPE", "MO");
        if (active != null) {
            // 已冻结：幂等（偏差刷新留痕）
            out.put("frozen", true);
            out.put("scope", active.getScope());
            out.put("ncrId", active.getNcrId());
            return out;
        }

        MrpMo mo = moDao.selectById(moId);
        // 严重度分级：偏差 > 2×阈值 → MAJOR（否则 MINOR）——保证 YIELD_FREEZE_SCOPE=MO 默认口径
        // 有意义（小幅超差留工单级），大幅超差经「NCR ≥ Major 升 WC 级」自动升级（spec 分级冻结）
        BigDecimal tolerance = new BigDecimal(sysParam.getValue("YIELD_TOLERANCE", "10"));
        String severity = deviation != null
                && deviation.compareTo(tolerance.multiply(BigDecimal.valueOf(2))) > 0
                ? "MAJOR" : "MINOR";
        String scope = "WC".equalsIgnoreCase(scopeParam)
                ? MrpMoFreeze.SCOPE_WC : MrpMoFreeze.SCOPE_MO;
        // NCR ≥ Major → 冻结自动升 WC 级（spec「分级冻结」升级条款）
        if ("MAJOR".equals(severity) || "CRITICAL".equals(severity)) {
            scope = MrpMoFreeze.SCOPE_WC;
        }
        // 良率超差自动建 NCR（refId=moId#opSeq#YIELD——同 refId 幂等）
        String refId = moId + "#" + opSeq + "#YIELD";
        String itemCode = mo == null ? null : mo.getProductCode();
        String itemName = mo == null ? null : mo.getProductName();
        var ncr = ncrService.createForWorkOrder(moId, opSeq, refId, itemCode, itemName,
                BigDecimal.ZERO, null, severity,
                "良率超差触发：实际 " + actual + "%，标准 " + d.get("standardYield")
                        + "%，偏差 " + deviation + "% > 阈值 " + d.get("tolerance") + "%"
                        + (rptNo == null ? "" : "（触发报工单 " + rptNo + "）"));
        MrpMoFreeze row = new MrpMoFreeze();
        row.setMoId(moId);
        row.setMoNo(mo == null ? null : mo.getMoNo());
        row.setScope(scope);
        row.setReason(MrpMoFreeze.REASON_YIELD);
        row.setYieldValue(actual);
        row.setDeviationValue(deviation);
        row.setNcrId(ncr.getId());
        row.setFrozenBy("SYSTEM");
        row.setFrozenAt(LocalDateTime.now());
        row.setActiveFlag("1");
        // WC 级冻结需要 WC 编码（挂点断言按 WC 过滤）——取首道工序快照 WC
        if (MrpMoFreeze.SCOPE_WC.equals(scope)) {
            row.setWcCode(wcOfMo(moId));
        }
        // 通知接收方快照（解冻通知原接收人——spec「通知原接收人」）
        List<String> receivers = List.of("ROLE_SHOP_FLOOR", "ROLE_QUALITY_ENG");
        row.setNoticeIds(String.join(",", receivers));
        freezeDao.insert(row);

        // push YIELD_ALERT（幂等含接收方维度——NoticeService 自带去重）
        StringBuilder content = new StringBuilder("工单 " + (mo == null ? moId : mo.getMoNo())
                + " 良率超差：实际 " + actual + "%，标准 " + d.get("standardYield")
                + "%，偏差 " + deviation + "% > 阈值 " + d.get("tolerance")
                + "%，已冻结后续派工（作用域 " + scope + "）");
        if (rptNo != null) {
            content.append("，触发报工单 ").append(rptNo);
        }
        List<String> noticeIds = new ArrayList<>();
        for (String role : receivers) {
            SysNotice n = noticeService.push(role, null, "良率超差冻结",
                    content.toString(), "YIELD_ALERT", moId);
            if (n != null) {
                noticeIds.add(n.getId());
            }
        }
        if (!noticeIds.isEmpty()) {
            freezeDao.update(null, new LambdaUpdateWrapper<MrpMoFreeze>()
                    .eq(MrpMoFreeze::getId, row.getId())
                    .set(MrpMoFreeze::getNoticeIds, String.join(",", receivers)
                            + "|" + String.join(",", noticeIds)));
        }
        log.info("良率冻结 mo={} scope={} deviation={} rpt={}", moId, scope, deviation, rptNo);
        out.put("frozen", true);
        out.put("scope", scope);
        return out;
    }

    @Override
    public void assertNotFrozen(String moId, String wcCode) {
        MrpMoFreeze active = activeOf(moId);
        if (active == null && wcCode != null && !wcCode.isEmpty()) {
            // WC 级冻结按编码命中（spec「冻结该工作中心全部新派工」——非触发工单也挡）
            active = freezeDao.selectOne(new LambdaQueryWrapper<MrpMoFreeze>()
                    .eq(MrpMoFreeze::getScope, MrpMoFreeze.SCOPE_WC)
                    .eq(MrpMoFreeze::getActiveFlag, "1")
                    .eq(MrpMoFreeze::getWcCode, wcCode)
                    .last("LIMIT 1"));
        }
        if (active == null) {
            return;
        }
        if (MrpMoFreeze.SCOPE_WC.equals(active.getScope())) {
            // WC 级：仅冻结同 WC 的派工/完工
            if (wcCode != null && wcCode.equals(active.getWcCode())) {
                throw new ServiceException(422, "工作中心 " + wcCode
                        + " 良率超差冻结中（工单 " + active.getMoNo() + "），不可派工/完工");
            }
            return;
        }
        throw new ServiceException(422, "工单 " + active.getMoNo()
                + " 良率超差冻结中（偏差 " + active.getDeviationValue()
                + "%），不可派工/完工（NCR 关闭或冲销回拨后自动解冻）");
    }

    @Override
    public MrpMoFreeze activeOf(String moId) {
        if (moId == null) {
            return null;
        }
        return freezeDao.selectOne(new LambdaQueryWrapper<MrpMoFreeze>()
                .eq(MrpMoFreeze::getMoId, moId)
                .eq(MrpMoFreeze::getActiveFlag, "1")
                .last("LIMIT 1"));
    }

    @Override
    @Transactional
    public boolean release(String moId, String reason, String operator) {
        MrpMoFreeze active = activeOf(moId);
        if (active == null) {
            return false;
        }
        freezeDao.update(null, new LambdaUpdateWrapper<MrpMoFreeze>()
                .eq(MrpMoFreeze::getId, active.getId())
                .eq(MrpMoFreeze::getActiveFlag, "1")
                .set(MrpMoFreeze::getActiveFlag, null)
                .set(MrpMoFreeze::getReleasedBy, operator)
                .set(MrpMoFreeze::getReleasedAt, LocalDateTime.now())
                .set(MrpMoFreeze::getReleaseReason, reason));
        // 解冻通知原接收人（NOTICE_IDS 快照前段为角色列表，按 | 分隔）
        String snapshot = active.getNoticeIds();
        if (snapshot != null && !snapshot.isBlank()) {
            String rolesPart = snapshot.contains("|")
                    ? snapshot.substring(0, snapshot.indexOf('|')) : snapshot;
            for (String role : rolesPart.split(",")) {
                if (!role.isBlank()) {
                    noticeService.push(role.trim(), null, "良率冻结解除",
                            "工单 " + active.getMoNo() + " 冻结已解除：" + reason,
                            "YIELD_UNFREEZE", moId);
                }
            }
        }
        log.info("良率解冻 mo={} reason={} by {}", moId, reason, operator);
        return true;
    }

    /** WC 级冻结的 WC 取工单首道工序快照（同 WC 族场景足够；多 WC 工单以 MO 级为宜） */
    private String wcOfMo(String moId) {
        MrpMoOp first = moOpDao.selectOne(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId)
                .orderByAsc(MrpMoOp::getOpSeq)
                .last("LIMIT 1"));
        return first == null ? null : first.getWcCode();
    }

    @Override
    public MrpMoFreeze freezeForNcr(String moId, String ncrId) {
        MrpMoFreeze active = activeOf(moId);
        if (active != null) {
            if (active.getNcrId() == null && ncrId != null) {
                freezeDao.update(null, new LambdaUpdateWrapper<MrpMoFreeze>()
                        .eq(MrpMoFreeze::getId, active.getId())
                        .set(MrpMoFreeze::getNcrId, ncrId));
                active.setNcrId(ncrId);
            }
            return active;
        }
        MrpMo mo = moDao.selectById(moId);
        MrpMoFreeze row = new MrpMoFreeze();
        row.setMoId(moId);
        row.setMoNo(mo == null ? null : mo.getMoNo());
        row.setScope(MrpMoFreeze.SCOPE_MO);
        row.setReason("NCR");
        row.setNcrId(ncrId);
        row.setFrozenBy("SYSTEM");
        row.setFrozenAt(LocalDateTime.now());
        row.setActiveFlag("1");
        row.setNoticeIds("ROLE_SHOP_FLOOR,ROLE_QUALITY_ENG");
        freezeDao.insert(row);
        log.info("NCR 在制冻结 mo={} ncr={}（挡派工/完工，不冻库存）", moId, ncrId);
        return row;
    }

    @Override
    @Transactional
    public boolean releaseForNcr(String moId, String ncrId, String reason) {
        MrpMoFreeze active = activeOf(moId);
        if (active == null) {
            return false;
        }
        // 仅释放挂本 NCR（或未挂 NCR）的冻结——防误解冻 YIELD/他 NCR 原因的活动冻结
        if (active.getNcrId() != null && !active.getNcrId().equals(ncrId)) {
            return false;
        }
        return release(moId, reason, "SYSTEM");
    }
}
