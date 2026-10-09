package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.qms.ExemptDao;
import com.erp.entity.qms.Exempt;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.qms.ExemptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 免检申请（BR-4.12-12）：单签质量经理；未批准不生效。 */
@Service
public class ExemptServiceImpl implements ExemptService {

    private final ExemptDao exemptDao;
    private final com.erp.dao.mdm.MdmItemDao itemDao;
    private final ApprovalEngine approvalEngine;

    public ExemptServiceImpl(ExemptDao exemptDao,
                             com.erp.dao.mdm.MdmItemDao itemDao,
                             ApprovalEngine approvalEngine) {
        this.exemptDao = exemptDao;
        this.itemDao = itemDao;
        this.approvalEngine = approvalEngine;
    }

    @Override
    public List<Exempt> list(String status, String materialCode) {
        return exemptDao.selectList(new LambdaQueryWrapper<Exempt>()
                .eq(hasText(status), Exempt::getStatus, status)
                .eq(hasText(materialCode), Exempt::getMaterialCode, materialCode)
                .orderByDesc(Exempt::getCreateDate));
    }

    @Override
    @Transactional
    public Map<String, Object> apply(Exempt body) {
        if (!hasText(body.getMaterialCode())) {
            throw new ServiceException(422, "物料编码必填");
        }
        long pending = exemptDao.selectCount(new LambdaQueryWrapper<Exempt>()
                .eq(Exempt::getMaterialCode, body.getMaterialCode())
                .eq(hasText(body.getSupplierId()), Exempt::getSupplierId, body.getSupplierId())
                .in(Exempt::getStatus, Arrays.asList("PENDING", "ACTIVE")));
        if (pending > 0) {
            throw new ServiceException(422, "该物料/供方已存在在途或生效的免检申请");
        }
        Exempt e = new Exempt();
        // MATERIAL_ID 非空：按编码回查物料，查不到时以编码占位（免检仍按编码 + 供方命中）
        String materialId = body.getMaterialId();
        if (materialId == null || materialId.isBlank()) {
            com.erp.entity.mdm.MdmItem item = itemDao.selectOne(
                    new LambdaQueryWrapper<com.erp.entity.mdm.MdmItem>()
                            .eq(com.erp.entity.mdm.MdmItem::getItemCode, body.getMaterialCode())
                            .last("LIMIT 1"));
            materialId = item != null ? item.getId() : body.getMaterialCode();
        }
        e.setMaterialId(materialId);
        e.setMaterialCode(body.getMaterialCode());
        e.setMaterialName(body.getMaterialName());
        e.setSupplierId(body.getSupplierId());
        e.setSupplierName(body.getSupplierName());
        e.setStatus("PENDING");
        e.setRemark(body.getRemark());
        exemptDao.insert(e);

        List<List<ApprovalNodeSpec>> chain = List.of(
                List.of(ApprovalNodeSpec.sign("ROLE_QUALITY_MGR", "质量经理审批")));
        var inst = approvalEngine.submit("Exempt", e.getId(),
                "免检申请：" + e.getMaterialCode() + (hasText(e.getSupplierId()) ? " / " + e.getSupplierName() : ""),
                "ROLE_QUALITY_DIRECTOR", chain);
        e.setApprovalId(inst.getId());
        exemptDao.updateById(e);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exempt", e);
        out.put("apprNo", inst.getApprNo());
        return out;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
