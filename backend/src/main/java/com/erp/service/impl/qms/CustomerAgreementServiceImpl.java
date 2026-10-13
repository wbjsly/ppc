package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.qms.AgreementLogDao;
import com.erp.dao.qms.CustomerAgreementDao;
import com.erp.entity.qms.AgreementLog;
import com.erp.entity.qms.CustomerAgreement;
import com.erp.service.qms.CustomerAgreementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户质量协议实现（spec customer-quality-agreement）。
 * 偏差 D-2：投诉域未建，COMPLAINT_STREAK 由质量工程师手工登记/清零（留痕）。
 */
@Slf4j
@Service
public class CustomerAgreementServiceImpl implements CustomerAgreementService {

    private final CustomerAgreementDao agreeDao;
    private final AgreementLogDao logDao;

    public CustomerAgreementServiceImpl(CustomerAgreementDao agreeDao, AgreementLogDao logDao) {
        this.agreeDao = agreeDao;
        this.logDao = logDao;
    }

    @Override
    public List<CustomerAgreement> list(String customerId, String status) {
        return agreeDao.selectList(new LambdaQueryWrapper<CustomerAgreement>()
                .eq(hasText(customerId), CustomerAgreement::getCustomerId, customerId)
                .eq(hasText(status), CustomerAgreement::getStatus, status)
                .orderByDesc(CustomerAgreement::getCreateDate));
    }

    @Override
    public Map<String, Object> detail(String id) {
        CustomerAgreement a = require(id);
        List<AgreementLog> logs = logDao.selectList(new LambdaQueryWrapper<AgreementLog>()
                .eq(AgreementLog::getAgreementId, id)
                .orderByDesc(AgreementLog::getCreateDate)
                .last("LIMIT 50"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agreement", a);
        out.put("logs", logs);
        return out;
    }

    @Override
    @Transactional
    public CustomerAgreement save(CustomerAgreement body) {
        if (!hasText(body.getAgreeCode())) {
            throw new ServiceException(422, "协议编码必填");
        }
        if (!hasText(body.getCustomerId())) {
            throw new ServiceException(422, "客户必填");
        }
        if (!"ITEM".equals(body.getScopeType()) && !"CATEGORY".equals(body.getScopeType())) {
            throw new ServiceException(422, "适用对象类型须为 ITEM 或 CATEGORY");
        }
        if (!hasText(body.getScopeValue())) {
            throw new ServiceException(422, "适用物料/品类必填");
        }
        CustomerAgreement existing = body.getId() == null ? null : agreeDao.selectById(body.getId());
        if (existing == null) {
            long dup = agreeDao.selectCount(new LambdaQueryWrapper<CustomerAgreement>()
                    .eq(CustomerAgreement::getAgreeCode, body.getAgreeCode()));
            if (dup > 0) {
                throw new ServiceException(422, "协议编码已存在");
            }
            body.setId(null);
            body.setStatus("DRAFT");
            if (body.getComplaintStreak() == null) {
                body.setComplaintStreak(0);
            }
            agreeDao.insert(body);
            return body;
        }
        // 编码不可改；ACTIVE 后仅备注可改（要素冻结——规则变更须新协议）
        if (body.getAgreeCode() != null && !body.getAgreeCode().equals(existing.getAgreeCode())) {
            throw new ServiceException(422, "协议编码创建后不可修改");
        }
        if ("ACTIVE".equals(existing.getStatus()) || "STOPPED".equals(existing.getStatus())) {
            throw new ServiceException(422, "已生效协议不可修改，请新建协议（" + existing.getStatus() + "）");
        }
        existing.setCustomerId(body.getCustomerId());
        existing.setCustomerName(body.getCustomerName());
        existing.setScopeType(body.getScopeType());
        existing.setScopeValue(body.getScopeValue());
        existing.setTightCtqJson(body.getTightCtqJson());
        existing.setSampleLevelOverride(body.getSampleLevelOverride());
        existing.setEffectiveFrom(body.getEffectiveFrom());
        existing.setEffectiveTo(body.getEffectiveTo());
        existing.setRemark(body.getRemark());
        if (agreeDao.updateById(existing) == 0) {
            throw new ServiceException(422, "更新冲突，请刷新重试");
        }
        return existing;
    }

    @Override
    @Transactional
    public CustomerAgreement activate(String id) {
        CustomerAgreement a = require(id);
        if (!"DRAFT".equals(a.getStatus())) {
            throw new ServiceException(422, "仅草稿协议可生效（当前 " + a.getStatus() + "）");
        }
        if (a.getEffectiveFrom() == null) {
            throw new ServiceException(422, "生效起日期必填");
        }
        // 生效区间重叠：同客户同对象已有 ACTIVE 且区间相交 → 422（spec 协议维护与要素校验）
        List<CustomerAgreement> actives = agreeDao.selectList(new LambdaQueryWrapper<CustomerAgreement>()
                .eq(CustomerAgreement::getStatus, "ACTIVE")
                .eq(CustomerAgreement::getCustomerId, a.getCustomerId())
                .eq(CustomerAgreement::getScopeType, a.getScopeType())
                .eq(CustomerAgreement::getScopeValue, a.getScopeValue()));
        for (CustomerAgreement b : actives) {
            boolean overlap = (a.getEffectiveTo() == null || b.getEffectiveTo() == null
                    || !a.getEffectiveTo().isBefore(b.getEffectiveFrom()))
                    && (b.getEffectiveTo() == null || a.getEffectiveFrom() == null
                    || !a.getEffectiveFrom().isAfter(b.getEffectiveTo()));
            if (overlap) {
                throw new ServiceException(422, "生效区间与既有协议 " + b.getAgreeCode() + " 重叠（冲突）");
            }
        }
        a.setStatus("ACTIVE");
        if (agreeDao.updateById(a) == 0) {
            throw new ServiceException(422, "更新冲突，请刷新重试");
        }
        return a;
    }

    @Override
    @Transactional
    public CustomerAgreement stop(String id) {
        CustomerAgreement a = require(id);
        a.setStatus("STOPPED"); // 停用仅阻止新批命中，历史批快照不变；不物理删除
        if (agreeDao.updateById(a) == 0) {
            throw new ServiceException(422, "更新冲突，请刷新重试");
        }
        return a;
    }

    @Override
    @Transactional
    public CustomerAgreement logComplaint(String id, String reason) {
        if (!hasText(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "投诉原因必填（至少 2 字）");
        }
        CustomerAgreement a = require(id);
        a.setComplaintStreak((a.getComplaintStreak() == null ? 0 : a.getComplaintStreak()) + 1);
        if (agreeDao.updateById(a) == 0) {
            throw new ServiceException(422, "更新冲突，请刷新重试");
        }
        appendLog(a.getId(), "COMPLAINT", a.getComplaintStreak(), reason.trim());
        return a;
    }

    @Override
    @Transactional
    public CustomerAgreement clearStreak(String id, String reason) {
        CustomerAgreement a = require(id);
        a.setComplaintStreak(0);
        if (agreeDao.updateById(a) == 0) {
            throw new ServiceException(422, "更新冲突，请刷新重试");
        }
        appendLog(a.getId(), "CLEAR", 0, reason == null ? "计数清零" : reason.trim());
        return a;
    }

    @Override
    public Map<String, Object> hitFor(String customerId, String itemCode, String categoryCode) {
        // 客户空 → 未命中（spec：发货来源无客户信息 MUST NOT 误命中）
        if (!hasText(customerId)) {
            return Map.of();
        }
        LocalDate today = LocalDate.now();
        List<CustomerAgreement> candidates = agreeDao.selectList(new LambdaQueryWrapper<CustomerAgreement>()
                .eq(CustomerAgreement::getCustomerId, customerId)
                .eq(CustomerAgreement::getStatus, "ACTIVE"));
        CustomerAgreement best = null;
        for (CustomerAgreement a : candidates) {
            if (a.getEffectiveFrom() != null && a.getEffectiveFrom().isAfter(today)) {
                continue;
            }
            if (a.getEffectiveTo() != null && a.getEffectiveTo().isBefore(today)) {
                continue;
            }
            boolean match = "ITEM".equals(a.getScopeType())
                    ? hasText(itemCode) && itemCode.equals(a.getScopeValue())
                    : hasText(categoryCode) && categoryCode.equals(a.getScopeValue());
            if (!match) {
                continue;
            }
            // 同客户同对象多命中：协议区间已由激活校验保证不重叠，取首个即唯一
            best = a;
            break;
        }
        if (best == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agreement", best);
        out.put("tightCtq", best.getTightCtqJson());
        out.put("sampleOverride", best.getSampleLevelOverride());
        boolean fullInspect = best.getComplaintStreak() != null && best.getComplaintStreak() >= 2;
        out.put("fullInspect", fullInspect);
        return out;
    }

    private void appendLog(String agreementId, String action, int streakAfter, String reason) {
        AgreementLog l = new AgreementLog();
        l.setAgreementId(agreementId);
        l.setAction(action);
        l.setStreakAfter(streakAfter);
        l.setReason(reason);
        logDao.insert(l);
    }

    private CustomerAgreement require(String id) {
        CustomerAgreement a = agreeDao.selectById(id);
        if (a == null) {
            throw new ServiceException(404, "客户质量协议不存在");
        }
        return a;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
