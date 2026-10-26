package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrCertCategoryDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrSkillMatrixDao;
import com.erp.entity.hr.HrCertCategory;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrEmployeeCert;
import com.erp.entity.hr.HrSkillMatrix;
import com.erp.service.hr.HrSkillMatrixService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 人员技能矩阵实现（skill-matrix，UK=EMP_ID+SKILL_CODE upsert，来源隔离失效）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrSkillMatrixServiceImpl implements HrSkillMatrixService {

    private final HrSkillMatrixDao matrixDao;
    private final HrCertCategoryDao cateDao;
    private final HrEmployeeDao empDao;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncFromCert(HrEmployeeCert cert) {
        HrCertCategory cate = cateDao.selectById(cert.getCateId());
        if (cate == null || cate.getSkillCode() == null || cate.getSkillCode().isBlank()) {
            return; // 类别未映射技能：不写矩阵
        }
        upsert(cert.getEmpId(), cate.getSkillCode(), cate.getSkillLevel(),
                HrSkillMatrix.SRC_CERT, cert.getId(), HrSkillMatrix.ST_ACTIVE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void expireFromCert(HrEmployeeCert cert) {
        HrSkillMatrix row = one(cert.getEmpId(), cert.getId(), HrSkillMatrix.SRC_CERT);
        if (row != null && !HrSkillMatrix.ST_EXPIRED.equals(row.getStatus())) {
            row.setStatus(HrSkillMatrix.ST_EXPIRED);
            row.setUpdateBy("system");
            row.setUpdateDate(LocalDateTime.now());
            matrixDao.updateById(row);
            log.info("skill matrix expire from cert: {} {} <- cert {}",
                    row.getEmpId(), row.getSkillCode(), cert.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncFromTraining(String empId, String skillCode, String skillLevel, String planId) {
        if (skillCode == null || skillCode.isBlank()) {
            return;
        }
        upsert(empId, skillCode, skillLevel, HrSkillMatrix.SRC_TRAIN, planId, HrSkillMatrix.ST_ACTIVE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void expireFromTraining(String empId, String skillCode, String planId) {
        HrSkillMatrix row = one(empId, planId, HrSkillMatrix.SRC_TRAIN);
        if (row != null && !HrSkillMatrix.ST_EXPIRED.equals(row.getStatus())) {
            row.setStatus(HrSkillMatrix.ST_EXPIRED);
            row.setUpdateBy("system");
            row.setUpdateDate(LocalDateTime.now());
            matrixDao.updateById(row);
            log.info("skill matrix expire from training: {} {} <- plan {}",
                    row.getEmpId(), row.getSkillCode(), planId);
        }
    }

    @Override
    public List<Map<String, Object>> queryBySkill(String skillCode, String level) {
        if (skillCode == null || skillCode.isBlank()) {
            throw new ServiceException(422, "技能编码必填");
        }
        LambdaQueryWrapper<HrSkillMatrix> qw = new LambdaQueryWrapper<HrSkillMatrix>()
                .eq(HrSkillMatrix::getDelFlag, "0")
                .eq(HrSkillMatrix::getSkillCode, skillCode)
                .eq(HrSkillMatrix::getStatus, HrSkillMatrix.ST_ACTIVE);
        if (level != null && !level.isBlank()) {
            qw.eq(HrSkillMatrix::getSkillLevel, level);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrSkillMatrix m : matrixDao.selectList(qw)) {
            HrEmployee e = empDao.selectById(m.getEmpId());
            if (e == null || !("PROBATION".equals(e.getEmpStatus()) || "REGULAR".equals(e.getEmpStatus()))) {
                continue; // 离职/停用排除
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("empId", m.getEmpId());
            row.put("empNo", e.getEmpNo());
            row.put("empName", e.getName());
            row.put("deptId", e.getDeptId());
            row.put("skillCode", m.getSkillCode());
            row.put("skillLevel", m.getSkillLevel());
            row.put("source", m.getSource());
            out.add(row);
        }
        return out;
    }

    // ================================================================
    // internals
    // ================================================================

    private void upsert(String empId, String skillCode, String skillLevel,
                        String source, String sourceDoc, String status) {
        HrSkillMatrix row = matrixDao.selectOne(new LambdaQueryWrapper<HrSkillMatrix>()
                .eq(HrSkillMatrix::getEmpId, empId)
                .eq(HrSkillMatrix::getSkillCode, skillCode)
                .last("LIMIT 1"));
        boolean isNew = row == null;
        if (isNew) {
            row = new HrSkillMatrix();
            row.setId(uuid());
            row.setEmpId(empId);
            row.setSkillCode(skillCode);
            row.setCreateBy("system");
            row.setCreateDate(LocalDateTime.now());
        }
        row.setSkillLevel(skillLevel);
        row.setSource(source);
        row.setSourceDoc(sourceDoc);
        row.setStatus(status);
        row.setUpdateBy("system");
        row.setUpdateDate(LocalDateTime.now());
        if (isNew) {
            matrixDao.insert(row);
        } else {
            matrixDao.updateById(row);
        }
        log.info("skill matrix upsert: {} {} <- {} {}", empId, skillCode, source, sourceDoc);
    }

    /** 按来源单据取矩阵行（同人同技能唯一，但按 sourceDoc 精确定位避免误伤） */
    private HrSkillMatrix one(String empId, String sourceDoc, String source) {
        return matrixDao.selectOne(new LambdaQueryWrapper<HrSkillMatrix>()
                .eq(HrSkillMatrix::getEmpId, empId)
                .eq(HrSkillMatrix::getSourceDoc, sourceDoc)
                .eq(HrSkillMatrix::getSource, source)
                .last("LIMIT 1"));
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
