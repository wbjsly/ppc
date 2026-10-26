package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.MrpReportingDao;
import com.erp.dao.mrp.TrialDataDao;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpReporting;
import com.erp.entity.mrp.TrialData;
import com.erp.service.mrp.TrialDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 试产数据采集实现（change add-trial-production-management，spec trial-execution
 * FR-4.5-8-5）。带出口径（偏差 D7）：该工序 5.7 CONFIRMED 报工 setup+run 合计（可改），
 * 采集表为权威数据源，不回写报工。
 */
@Slf4j
@Service
public class TrialDataServiceImpl implements TrialDataService {

    private final TrialDataDao dataDao;
    private final MrpMoDao moDao;
    private final MrpMoOpDao moOpDao;
    private final MrpReportingDao reportingDao;

    public TrialDataServiceImpl(TrialDataDao dataDao, MrpMoDao moDao,
                                MrpMoOpDao moOpDao, MrpReportingDao reportingDao) {
        this.dataDao = dataDao;
        this.moDao = moDao;
        this.moOpDao = moOpDao;
        this.reportingDao = reportingDao;
    }

    @Override
    public Map<String, Object> form(String moId, Integer opSeq) {
        requireTrialWriter("载入试产采集表单");
        requireOp(moId, opSeq);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moId", moId);
        out.put("opSeq", opSeq);
        TrialData existing = select(moId, opSeq);
        if (existing != null) {
            out.put("data", existing);
            out.put("prefilled", false);
            return out;
        }
        // 预填草稿：实测工时 = 该工序 CONFIRMED 报工 setup+run 合计（偏差 D7，无报工 → null）
        BigDecimal confirmedHours = confirmedHours(moId, opSeq);
        TrialData draft = new TrialData();
        draft.setMoId(moId);
        draft.setMoNo(moNoOf(moId));
        draft.setOpSeq(opSeq);
        MrpMoOp op = opOf(moId, opSeq);
        if (op != null) {
            draft.setOpCode(op.getOpCode());
            draft.setOpName(op.getOpName());
        }
        draft.setActualHours(confirmedHours);
        out.put("data", draft);
        out.put("prefilled", confirmedHours != null);
        return out;
    }

    @Override
    @Transactional
    public TrialData save(TrialData data) {
        requireTrialWriter("保存试产采集记录");
        if (data == null || data.getMoId() == null || data.getOpSeq() == null) {
            throw new ServiceException(422, "工单与工序必填");
        }
        requireOp(data.getMoId(), data.getOpSeq());
        MrpMoOp op = opOf(data.getMoId(), data.getOpSeq());
        if (op != null) {
            data.setOpCode(op.getOpCode());
            data.setOpName(op.getOpName());
        }
        data.setMoNo(moNoOf(data.getMoId()));
        TrialData existing = select(data.getMoId(), data.getOpSeq());
        if (existing != null) {
            data.setId(existing.getId());
            dataDao.updateById(data);
            log.info("试产采集更新：{} op={} 工时={}", data.getMoNo(), data.getOpSeq(),
                    data.getActualHours());
            return data;
        }
        dataDao.insert(data);
        log.info("试产采集新增：{} op={} 工时={}", data.getMoNo(), data.getOpSeq(),
                data.getActualHours());
        return data;
    }

    @Override
    public List<TrialData> listByMo(String moId) {
        requireTrialWriter("查询试产采集记录");
        return dataDao.selectList(new LambdaQueryWrapper<TrialData>()
                .eq(TrialData::getMoId, moId)
                .orderByAsc(TrialData::getOpSeq));
    }

    // ---------- 私有助手 ----------

    private TrialData select(String moId, Integer opSeq) {
        return dataDao.selectOne(new LambdaQueryWrapper<TrialData>()
                .eq(TrialData::getMoId, moId)
                .eq(TrialData::getOpSeq, opSeq)
                .last("LIMIT 1"));
    }

    private void requireOp(String moId, Integer opSeq) {
        if (opOf(moId, opSeq) == null) {
            throw new ServiceException(422, "工序快照不存在：" + moId + " op " + opSeq);
        }
    }

    private MrpMoOp opOf(String moId, Integer opSeq) {
        return moOpDao.selectOne(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId)
                .eq(MrpMoOp::getOpSeq, opSeq)
                .last("LIMIT 1"));
    }

    private String moNoOf(String moId) {
        MrpMo mo = moDao.selectById(moId);
        return mo == null ? null : mo.getMoNo();
    }

    /** 5.7 CONFIRMED 报工该工序 setup+run 合计（偏差 D7 带出源）；无 → null */
    private BigDecimal confirmedHours(String moId, Integer opSeq) {
        List<MrpReporting> rows = reportingDao.selectList(new LambdaQueryWrapper<MrpReporting>()
                .eq(MrpReporting::getMoId, moId)
                .eq(MrpReporting::getOpSeq, opSeq)
                .eq(MrpReporting::getStatus, MrpReporting.ST_CONFIRMED));
        if (rows.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (MrpReporting r : rows) {
            sum = sum.add(nvl(r.getSetupHours())).add(nvl(r.getRunHours()));
        }
        return sum;
    }

    /** 试产侧写权限（spec trial-order 菜单权限六角色） */
    private void requireTrialWriter(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String want : new String[]{"ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_PROCESS_ENG",
                "ROLE_PROCESS_MGR", "ROLE_PM", "ROLE_ADMIN"}) {
            if (auth.getAuthorities().contains(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(want))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
