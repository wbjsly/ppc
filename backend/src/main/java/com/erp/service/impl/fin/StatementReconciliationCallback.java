package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinSupplierStatementDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.fin.FinSupplierStatement;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 对账差异双签回调（BIZ_TYPE = StatementReconcile，spec supplier-statement-reconciliation）。
 * 通过 → 双签字段落库 + `CLOSED` 解冻；驳回 → 维持 `EXCEPTION` 继续冻结。与审批同事务。
 */
@Slf4j
@Component
public class StatementReconciliationCallback implements ApprovalCallback {

    private final FinSupplierStatementDao stmtDao;
    private final ApprovalTaskDao taskDao;

    public StatementReconciliationCallback(FinSupplierStatementDao stmtDao, ApprovalTaskDao taskDao) {
        this.stmtDao = stmtDao;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("StatementReconcile");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        FinSupplierStatement s = stmtDao.selectById(instance.getBizId());
        if (s == null) {
            log.warn("statement reconcile approved but record missing: {}", instance.getBizId());
            return;
        }
        if (FinSupplierStatement.ST_CLOSED.equals(s.getStatus())) {
            return; // 幂等
        }
        List<ApprovalTask> tasks = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "PASSED"));
        for (ApprovalTask t : tasks) {
            if ("ROLE_PM".equalsIgnoreCase(t.getRoleRequired())) {
                s.setPmConfirmBy(t.getSigner());
                s.setPmConfirmAt(t.getOpTime());
                s.setPmOpinion(t.getOpinion());
            } else if ("ROLE_FINANCE_MGR".equalsIgnoreCase(t.getRoleRequired())) {
                s.setFinConfirmBy(t.getSigner());
                s.setFinConfirmAt(t.getOpTime());
                s.setFinOpinion(t.getOpinion());
            }
        }
        s.setStatus(FinSupplierStatement.ST_CLOSED);
        if (stmtDao.updateById(s) == 0) {
            throw new ServiceException(422, "对账单状态更新冲突");
        }
        log.info("statement {} double-signed (PM={} / FIN={}) → CLOSED 解冻",
                s.getStmtNo(), s.getPmConfirmBy(), s.getFinConfirmBy());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        FinSupplierStatement s = stmtDao.selectById(instance.getBizId());
        if (s == null || FinSupplierStatement.ST_CLOSED.equals(s.getStatus())) {
            return;
        }
        ApprovalTask t = taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED")
                .last("LIMIT 1"));
        // 驳回 → 维持 EXCEPTION（继续冻结），意见入备注留痕
        s.setRemark("双签驳回：" + (t != null && t.getOpinion() != null ? t.getOpinion() : "未填写意见"));
        stmtDao.updateById(s);
        log.info("statement {} double-sign rejected, 冻结维持：{}", s.getStmtNo(), s.getRemark());
    }
}
