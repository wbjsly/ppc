package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.mdm.MdmItem;
import com.erp.service.inv.BatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 批次台账（4.2.1，spec batch-master，design D2/D5/D7）：
 * 批次号自动生成 `B+yyMMdd+-+4位当日流水`（全局日流水，防追溯歧义）或手工录入；
 * 同物料唯一（UK + 预检 409）；批次管理物料效期必填且须晚于生产日期（BR-4.1-08）。
 */
@Slf4j
@Service
public class BatchServiceImpl implements BatchService {

    private static final String ST_ENABLED = "1";
    private static final String ST_DISABLED = "0";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final InvBatchDao batchDao;
    private final MdmItemDao itemDao;

    public BatchServiceImpl(InvBatchDao batchDao, MdmItemDao itemDao) {
        this.batchDao = batchDao;
        this.itemDao = itemDao;
    }

    @Override
    public List<InvBatch> query(String itemCode, String keyword, String supplierBatchNo,
                                LocalDate expiryFrom, LocalDate expiryTo, String status) {
        LambdaQueryWrapper<InvBatch> qw = new LambdaQueryWrapper<InvBatch>()
                .eq(!isBlank(itemCode), InvBatch::getItemCode, itemCode)
                .eq(!isBlank(supplierBatchNo), InvBatch::getSupplierBatchNo, supplierBatchNo)
                .eq(!isBlank(status), InvBatch::getStatus, status)
                .ge(expiryFrom != null, InvBatch::getExpiryDate, expiryFrom)
                .le(expiryTo != null, InvBatch::getExpiryDate, expiryTo)
                .and(!isBlank(keyword), w -> w
                        .like(InvBatch::getBatchNo, keyword.trim())
                        .or().like(InvBatch::getSupplierBatchNo, keyword.trim())
                        .or().like(InvBatch::getItemCode, keyword.trim()))
                .orderByAsc(InvBatch::getExpiryDate)
                .orderByAsc(InvBatch::getBatchNo);
        return batchDao.selectList(qw);
    }

    @Override
    public InvBatch get(String id) {
        InvBatch batch = batchDao.selectById(id);
        if (batch == null) {
            throw new ServiceException(404, "批次不存在：" + id);
        }
        return batch;
    }

    @Override
    @Transactional
    public InvBatch create(InvBatch batch) {
        requireRole("维护批次台账", "ROLE_WAREHOUSE");
        if (batch == null || isBlank(batch.getItemCode())) {
            throw new ServiceException(422, "物料编码必填");
        }
        MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, batch.getItemCode().trim())
                .last("LIMIT 1"));
        if (item == null) {
            throw new ServiceException(422, "物料不存在：" + batch.getItemCode());
        }
        validateExpiry(item, batch.getProductionDate(), batch.getExpiryDate());

        String batchNo = isBlank(batch.getBatchNo()) ? nextBatchNo() : batch.getBatchNo().trim();
        // 同物料唯一预检（UK_INV_BATCH 兜底并发）
        Long exists = batchDao.selectCount(new LambdaQueryWrapper<InvBatch>()
                .eq(InvBatch::getItemCode, batch.getItemCode().trim())
                .eq(InvBatch::getBatchNo, batchNo));
        if (exists != null && exists > 0) {
            throw new ServiceException(409, "批次号在该物料下已存在：" + batchNo);
        }
        InvBatch entity = new InvBatch();
        entity.setBatchNo(batchNo);
        entity.setItemCode(batch.getItemCode().trim());
        entity.setItemName(item.getItemName());
        entity.setProductionDate(batch.getProductionDate());
        entity.setExpiryDate(batch.getExpiryDate());
        entity.setSupplierBatchNo(batch.getSupplierBatchNo());
        entity.setSourceDocNo(batch.getSourceDocNo());
        entity.setStatus(ST_ENABLED);
        entity.setRemark(batch.getRemark());
        try {
            batchDao.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "批次号在该物料下已存在：" + batchNo);
        }
        return entity;
    }

    @Override
    @Transactional
    public InvBatch update(InvBatch batch) {
        requireRole("维护批次台账", "ROLE_WAREHOUSE");
        if (batch == null || isBlank(batch.getId())) {
            throw new ServiceException(422, "批次 ID 必填");
        }
        InvBatch stored = get(batch.getId());
        // 批次号创建后不可改（spec scenario）
        if (!isBlank(batch.getBatchNo()) && !stored.getBatchNo().equals(batch.getBatchNo())) {
            throw new ServiceException(422, "批次号创建后不可修改：" + stored.getBatchNo());
        }
        if (!isBlank(batch.getItemCode()) && !stored.getItemCode().equals(batch.getItemCode())) {
            throw new ServiceException(422, "批次所属物料不可修改：" + stored.getItemCode());
        }
        MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, stored.getItemCode())
                .last("LIMIT 1"));
        LocalDate production = batch.getProductionDate() != null
                ? batch.getProductionDate() : stored.getProductionDate();
        LocalDate expiry = batch.getExpiryDate() != null ? batch.getExpiryDate() : stored.getExpiryDate();
        validateExpiry(item, production, expiry);

        stored.setProductionDate(production);
        stored.setExpiryDate(expiry);
        if (batch.getSupplierBatchNo() != null) stored.setSupplierBatchNo(batch.getSupplierBatchNo());
        if (batch.getSourceDocNo() != null) stored.setSourceDocNo(batch.getSourceDocNo());
        if (batch.getRemark() != null) stored.setRemark(batch.getRemark());
        if (batchDao.updateById(stored) == 0) {
            throw new ServiceException(409, "批次已被其他用户修改，请刷新后重试");
        }
        return stored;
    }

    @Override
    @Transactional
    public void enable(String id) {
        requireRole("维护批次台账", "ROLE_WAREHOUSE");
        InvBatch stored = get(id);
        if (ST_ENABLED.equals(stored.getStatus())) {
            return;
        }
        stored.setStatus(ST_ENABLED);
        if (batchDao.updateById(stored) == 0) {
            throw new ServiceException(409, "批次已被其他用户修改，请刷新后重试");
        }
    }

    @Override
    @Transactional
    public void disable(String id) {
        requireRole("维护批次台账", "ROLE_WAREHOUSE");
        InvBatch stored = get(id);
        if (ST_DISABLED.equals(stored.getStatus())) {
            return;
        }
        // 关闭 = 退出后续可选范围，不影响既有引用（同停用仓模式）
        stored.setStatus(ST_DISABLED);
        if (batchDao.updateById(stored) == 0) {
            throw new ServiceException(409, "批次已被其他用户修改，请刷新后重试");
        }
    }

    /**
     * 效期校验（D5，spec batch-master：BR-4.1-08 口径）：
     * 批次管理物料（batchFlag=1）有效期至必填；有效期至须晚于生产日期；非批次管理物料可空。
     */
    private void validateExpiry(MdmItem item, LocalDate productionDate, LocalDate expiryDate) {
        boolean batchManaged = item != null && "1".equals(item.getBatchFlag());
        if (batchManaged && expiryDate == null) {
            throw new ServiceException(422, "批次管理物料必须填写有效期至（保质期）：" + item.getItemName());
        }
        if (expiryDate != null && productionDate != null && !expiryDate.isAfter(productionDate)) {
            throw new ServiceException(422, "有效期至必须晚于生产日期");
        }
    }

    /** 系统生成：B + yyMMdd + '-' + 4位当日流水（全局日流水，design D2） */
    private String nextBatchNo() {
        String prefix = "B" + LocalDate.now().format(DAY) + "-";
        int max = 0;
        for (String code : batchDao.selectBatchNosByPrefix(prefix)) {
            if (code == null || !code.startsWith(prefix)) {
                continue;
            }
            String tail = code.substring(prefix.length());
            if (tail.length() != 4) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(tail));
            } catch (NumberFormatException ignore) {
                // 手工录入的同前缀非流水值跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : roles) {
                if (want.equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "无权" + action);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
