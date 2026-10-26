package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrLeaveTypeDao;
import com.erp.entity.hr.HrLeaveType;
import com.erp.service.hr.HrLeaveTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 假期类型字典（8 类种子；规则参数化：扣减系数/法定天数/计薪标志）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrLeaveTypeServiceImpl implements HrLeaveTypeService {

    private final HrLeaveTypeDao leaveTypeDao;

    @Override
    public List<HrLeaveType> list() {
        return leaveTypeDao.selectList(new LambdaQueryWrapper<HrLeaveType>()
                .eq(HrLeaveType::getDelFlag, "0")
                .eq(HrLeaveType::getStatus, "1")
                .orderByAsc(HrLeaveType::getSortNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrLeaveType update(String typeCode, Map<String, Object> payload) {
        HrLeaveType t = requireByCode(typeCode);
        if (payload.containsKey("dailyDeductionRate")) {
            BigDecimal rate = dec(payload.get("dailyDeductionRate"));
            if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                throw new ServiceException(422, "日工资扣减系数须在 0~1 之间");
            }
            t.setDailyDeductionRate(rate);
        }
        if (payload.containsKey("statutoryDays")) {
            BigDecimal days = dec(payload.get("statutoryDays"));
            if (days != null && days.signum() < 0) {
                throw new ServiceException(422, "法定天数不可为负");
            }
            t.setStatutoryDays(days);
        }
        if (payload.containsKey("paidFlag")) {
            t.setPaidFlag(String.valueOf(payload.get("paidFlag")));
        }
        if (payload.containsKey("needBalance")) {
            t.setNeedBalance(String.valueOf(payload.get("needBalance")));
        }
        t.setUpdateBy(currentUser());
        t.setUpdateDate(LocalDateTime.now());
        leaveTypeDao.updateById(t);
        log.info("leave type {} updated: rate={} days={}", typeCode, t.getDailyDeductionRate(), t.getStatutoryDays());
        return t;
    }

    @Override
    public HrLeaveType requireByCode(String typeCode) {
        HrLeaveType t = leaveTypeDao.selectOne(new LambdaQueryWrapper<HrLeaveType>()
                .eq(HrLeaveType::getTypeCode, typeCode)
                .eq(HrLeaveType::getDelFlag, "0"));
        if (t == null) {
            throw new ServiceException(404, "假期类型不存在：" + typeCode);
        }
        return t;
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "数值格式非法：" + o);
        }
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
