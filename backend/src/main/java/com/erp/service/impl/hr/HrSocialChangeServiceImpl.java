package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrSocialChangeDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrSocialChange;
import com.erp.service.hr.HrSocialChangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 社保增减员/转移单（social-insurance-ops）。
 * 防重：同员工同类型存在 DRAFT/DECLARED 在途单不重复自动开立（VOID/DONE 不挡）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrSocialChangeServiceImpl implements HrSocialChangeService {

    private final HrSocialChangeDao changeDao;
    private final HrEmployeeDao empDao;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSocialChange autoOpen(String empId, String chgType, String srcType, String srcId,
                                   String targetPlace, LocalDate changeDate) {
        HrEmployee e = empDao.selectById(empId);
        if (e == null) {
            log.warn("social change auto-open skip: employee not found {}", empId);
            return null;
        }
        if (inFlightExists(empId, chgType)) {
            log.info("social change auto-open dedup: {} {} in-flight", e.getEmpNo(), chgType);
            return null;
        }
        HrSocialChange c = new HrSocialChange();
        c.setId(uuid());
        c.setChgNo(nextNo());
        c.setChgType(chgType);
        c.setEmpId(e.getId());
        c.setEmpName(e.getName());
        c.setEmpNo(e.getEmpNo());
        c.setTargetPlace(targetPlace != null ? targetPlace : e.getSocialPlace());
        c.setSrcType(srcType);
        c.setSrcId(srcId);
        c.setChangeDate(changeDate);
        c.setStatus(HrSocialChange.ST_DRAFT);
        c.setCreateBy("system");
        c.setCreateDate(LocalDateTime.now());
        changeDao.insert(c);
        log.info("social change auto-opened: {} {} {} src={}", c.getChgNo(), chgType, e.getEmpNo(), srcType);
        return c;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSocialChange manualOpen(Map<String, Object> payload) {
        String empId = str(payload.get("empId"));
        HrEmployee e = empDao.selectById(empId);
        if (e == null) {
            throw new ServiceException(404, "员工档案不存在");
        }
        String chgType = str(payload.get("chgType"));
        if (!List.of(HrSocialChange.TYPE_ADD, HrSocialChange.TYPE_REMOVE, HrSocialChange.TYPE_TRANSFER)
                .contains(chgType)) {
            throw new ServiceException(422, "单据类型非法（ADD/REMOVE/TRANSFER）");
        }
        String targetPlace = str(payload.get("targetPlace"));
        if (!HrSocialChange.TYPE_REMOVE.equals(chgType) && (targetPlace == null || targetPlace.isBlank())) {
            throw new ServiceException(422, "增员/转移须填写目标缴纳地");
        }
        HrSocialChange c = new HrSocialChange();
        c.setId(uuid());
        c.setChgNo(nextNo());
        c.setChgType(chgType);
        c.setEmpId(e.getId());
        c.setEmpName(e.getName());
        c.setEmpNo(e.getEmpNo());
        c.setTargetPlace(targetPlace);
        c.setSrcType(HrSocialChange.SRC_MANUAL);
        c.setSrcId(null);
        c.setChangeDate(date(payload.get("changeDate")));
        c.setStatus(HrSocialChange.ST_DRAFT);
        c.setNote(str(payload.get("note")));
        c.setCreateBy(currentUser());
        c.setCreateDate(LocalDateTime.now());
        changeDao.insert(c);
        log.info("social change manually opened: {} {} {}", c.getChgNo(), chgType, e.getEmpNo());
        return c;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSocialChange declare(String chgId, Map<String, Object> payload) {
        HrSocialChange c = require(chgId);
        if (HrSocialChange.ST_DONE.equals(c.getStatus())) {
            throw new ServiceException(422, "单据已申报完成");
        }
        if (HrSocialChange.ST_VOID.equals(c.getStatus())) {
            throw new ServiceException(422, "已作废单据不可申报");
        }
        c.setDeclareDate(LocalDateTime.now());
        c.setReceiptNo(str(payload.get("receiptNo")));
        c.setOperator(str(payload.get("operator")) != null ? str(payload.get("operator")) : currentUser());
        // 生效日期：未填时 REMOVE 缺省取离职日（SRC=EXIT 时查离职单）
        if (c.getChangeDate() == null && HrSocialChange.TYPE_REMOVE.equals(c.getChgType())) {
            c.setChangeDate(LocalDate.now());
        }
        String note = str(payload.get("note"));
        if (note != null) {
            c.setNote(note);
        }
        c.setStatus(HrSocialChange.ST_DONE);
        c.setUpdateBy(currentUser());
        c.setUpdateDate(LocalDateTime.now());
        changeDao.updateById(c);
        log.info("social change declared done: {} {} receipt={}", c.getChgNo(), c.getChgType(), c.getReceiptNo());
        return c;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSocialChange voidChange(String chgId, String note) {
        HrSocialChange c = require(chgId);
        if (HrSocialChange.ST_DONE.equals(c.getStatus())) {
            throw new ServiceException(422, "已申报完成单据不可作废");
        }
        c.setStatus(HrSocialChange.ST_VOID);
        c.setNote((c.getNote() == null ? "" : c.getNote() + "；") + "作废：" + note);
        c.setUpdateBy(currentUser());
        c.setUpdateDate(LocalDateTime.now());
        changeDao.updateById(c);
        return c;
    }

    @Override
    public List<HrSocialChange> list(String empId, String chgType, String status) {
        LambdaQueryWrapper<HrSocialChange> qw = new LambdaQueryWrapper<HrSocialChange>()
                .eq(HrSocialChange::getDelFlag, "0")
                .orderByDesc(HrSocialChange::getCreateDate);
        if (empId != null && !empId.isBlank()) {
            qw.eq(HrSocialChange::getEmpId, empId);
        }
        if (chgType != null && !chgType.isBlank()) {
            qw.eq(HrSocialChange::getChgType, chgType);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(HrSocialChange::getStatus, status);
        }
        return changeDao.selectList(qw);
    }

    @Override
    public boolean hasDoneChange(String empId, String chgType, LocalDate asOfMonthEnd) {
        LambdaQueryWrapper<HrSocialChange> qw = new LambdaQueryWrapper<HrSocialChange>()
                .eq(HrSocialChange::getDelFlag, "0")
                .eq(HrSocialChange::getEmpId, empId)
                .eq(HrSocialChange::getChgType, chgType)
                .eq(HrSocialChange::getStatus, HrSocialChange.ST_DONE)
                .isNotNull(HrSocialChange::getChangeDate);
        if (asOfMonthEnd != null) {
            qw.le(HrSocialChange::getChangeDate, asOfMonthEnd);
        }
        return changeDao.selectCount(qw) > 0;
    }

    @Override
    public Map<String, Object> detail(String chgId) {
        HrSocialChange c = require(chgId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("change", c);
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    private boolean inFlightExists(String empId, String chgType) {
        return changeDao.selectCount(new LambdaQueryWrapper<HrSocialChange>()
                .eq(HrSocialChange::getDelFlag, "0")
                .eq(HrSocialChange::getEmpId, empId)
                .eq(HrSocialChange::getChgType, chgType)
                .in(HrSocialChange::getStatus, HrSocialChange.ST_DRAFT, HrSocialChange.ST_DECLARED)) > 0;
    }

    private HrSocialChange require(String chgId) {
        HrSocialChange c = changeDao.selectById(chgId);
        if (c == null) {
            throw new ServiceException(404, "社保单据不存在：" + chgId);
        }
        return c;
    }

    private String nextNo() {
        String prefix = "SC" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        List<String> existing = changeDao.selectNosByPrefix(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(no.lastIndexOf('-') + 1)));
            } catch (Exception ignored) {
                // skip
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    private static LocalDate date(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        return LocalDate.parse(String.valueOf(v).trim().substring(0, 10));
    }

    private static String currentUser() {
        try {
            return org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
