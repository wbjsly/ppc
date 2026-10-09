package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvSerialDao;
import com.erp.dao.inv.InvSerialLogDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.entity.inv.InvSerial;
import com.erp.entity.inv.InvSerialLog;
import com.erp.entity.mdm.MdmItem;
import com.erp.service.inv.SerialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 序列号台账（4.2.2，spec serial-master，design D3/D4/D7）：
 * 序列号全局唯一（跨物料防串码）；状态机流转白名单单点强制，前端只发意图不传状态；
 * 流转即写 InvSerialLog（前后状态/原因/操作人）；判重查询只读、仅需认证。
 */
@Slf4j
@Service
public class SerialServiceImpl implements SerialService {

    /** 合法边白名单（design D3；OUT/SCRAPPED 为终态无出边） */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            InvSerial.ST_IN_STOCK, Set.of(InvSerial.ST_OUT, InvSerial.ST_FROZEN, InvSerial.ST_SCRAPPED),
            InvSerial.ST_FROZEN, Set.of(InvSerial.ST_IN_STOCK)
    );

    private final InvSerialDao serialDao;
    private final InvSerialLogDao logDao;
    private final MdmItemDao itemDao;

    public SerialServiceImpl(InvSerialDao serialDao, InvSerialLogDao logDao, MdmItemDao itemDao) {
        this.serialDao = serialDao;
        this.logDao = logDao;
        this.itemDao = itemDao;
    }

    @Override
    public List<InvSerial> query(String serialNo, String itemCode, String batchNo, String status) {
        LambdaQueryWrapper<InvSerial> qw = new LambdaQueryWrapper<InvSerial>()
                .eq(!isBlank(itemCode), InvSerial::getItemCode, itemCode)
                .eq(!isBlank(batchNo), InvSerial::getBatchNo, batchNo)
                .eq(!isBlank(status), InvSerial::getStatus, status)
                .like(!isBlank(serialNo), InvSerial::getSerialNo,
                        serialNo == null ? null : serialNo.trim())
                .orderByDesc(InvSerial::getCreateDate);
        return serialDao.selectList(qw);
    }

    @Override
    public InvSerial get(String id) {
        InvSerial serial = serialDao.selectById(id);
        if (serial == null) {
            throw new ServiceException(404, "序列号记录不存在：" + id);
        }
        return serial;
    }

    @Override
    @Transactional
    public InvSerial create(InvSerial serial) {
        requireRole("维护序列台账", "ROLE_WAREHOUSE");
        if (serial == null || isBlank(serial.getSerialNo()) || isBlank(serial.getItemCode())) {
            throw new ServiceException(422, "序列号与物料编码必填");
        }
        MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, serial.getItemCode().trim())
                .last("LIMIT 1"));
        if (item == null) {
            throw new ServiceException(422, "物料不存在：" + serial.getItemCode());
        }
        // 全局唯一预检（跨物料，串码口径 BR-4.11-15；UK_INV_SERIAL 兜底并发）
        Long exists = serialDao.selectCount(new LambdaQueryWrapper<InvSerial>()
                .eq(InvSerial::getSerialNo, serial.getSerialNo().trim()));
        if (exists != null && exists > 0) {
            throw new ServiceException(409, "序列号已存在（含历史记录），疑似串码：" + serial.getSerialNo());
        }
        InvSerial entity = new InvSerial();
        entity.setSerialNo(serial.getSerialNo().trim());
        entity.setItemCode(serial.getItemCode().trim());
        entity.setItemName(item.getItemName());
        entity.setBatchNo(serial.getBatchNo());
        entity.setStatus(InvSerial.ST_IN_STOCK);
        entity.setSourceDocNo(serial.getSourceDocNo());
        entity.setLocationRemark(serial.getLocationRemark());
        entity.setRemark(serial.getRemark());
        try {
            serialDao.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "序列号已存在（含历史记录），疑似串码：" + entity.getSerialNo());
        }
        appendLog(entity, InvSerial.ST_IN_STOCK, InvSerial.ST_IN_STOCK, "建档登记");
        return entity;
    }

    @Override
    @Transactional
    public InvSerial update(InvSerial serial) {
        requireRole("维护序列台账", "ROLE_WAREHOUSE");
        if (serial == null || isBlank(serial.getId())) {
            throw new ServiceException(422, "序列 ID 必填");
        }
        InvSerial stored = get(serial.getId());
        // 序列号创建后不可改（spec scenario：提交修改序列号 → 422）
        if (!isBlank(serial.getSerialNo()) && !stored.getSerialNo().equals(serial.getSerialNo())) {
            throw new ServiceException(422, "序列号创建后不可修改：" + stored.getSerialNo());
        }
        if (!isBlank(serial.getItemCode()) && !stored.getItemCode().equals(serial.getItemCode())) {
            throw new ServiceException(422, "序列所属物料不可修改：" + stored.getItemCode());
        }
        // 状态只经 transition 变更，忽略提交的状态字段
        if (serial.getBatchNo() != null) stored.setBatchNo(serial.getBatchNo());
        if (serial.getSourceDocNo() != null) stored.setSourceDocNo(serial.getSourceDocNo());
        if (serial.getLocationRemark() != null) stored.setLocationRemark(serial.getLocationRemark());
        if (serial.getRemark() != null) stored.setRemark(serial.getRemark());
        if (serialDao.updateById(stored) == 0) {
            throw new ServiceException(409, "序列已被其他用户修改，请刷新后重试");
        }
        return stored;
    }

    @Override
    @Transactional
    public InvSerial transition(String serialId, String toStatus, String reason, String locationRemark) {
        requireRole("维护序列台账", "ROLE_WAREHOUSE");
        if (isBlank(serialId) || isBlank(toStatus)) {
            throw new ServiceException(422, "序列 ID 与目标状态必填");
        }
        InvSerial stored = get(serialId);
        String from = stored.getStatus();
        if (toStatus.equals(from)) {
            throw new ServiceException(422, "状态未变化，无需流转");
        }
        // 合法边白名单（DC-11：终态无出边、禁跳态/逆转）
        Set<String> allowed = ALLOWED.get(from);
        if (allowed == null || !allowed.contains(toStatus)) {
            String msg = isTerminal(from)
                    ? "终态不可回退：" + statusName(from)
                    : "非法状态流转：" + statusName(from) + " → " + statusName(toStatus);
            throw new ServiceException(422, msg);
        }
        // 解冻（FROZEN→IN_STOCK）原因必填（spec serial-master 唯一显式必填项）；
        // 其余流转原因选填但随流转留痕（log.reason 可空）
        boolean needReason = InvSerial.ST_FROZEN.equals(from) && InvSerial.ST_IN_STOCK.equals(toStatus);
        if (needReason && isBlank(reason)) {
            throw new ServiceException(422, "解冻必须填写原因");
        }
        stored.setStatus(toStatus);
        if (locationRemark != null) {
            stored.setLocationRemark(locationRemark);
        }
        int updated = serialDao.updateById(stored);
        if (updated == 0) {
            throw new ServiceException(409, "序列已被其他用户修改，请刷新后重试");
        }
        appendLog(stored, from, toStatus, reason);
        return stored;
    }

    @Override
    public List<Map<String, Object>> logs(String serialId) {
        List<InvSerialLog> rows = logDao.selectList(new LambdaQueryWrapper<InvSerialLog>()
                .eq(InvSerialLog::getSerialId, serialId)
                .orderByDesc(InvSerialLog::getCreateDate));
        List<Map<String, Object>> result = new ArrayList<>();
        for (InvSerialLog l : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", l.getId());
            row.put("fromStatus", l.getFromStatus());
            row.put("toStatus", l.getToStatus());
            row.put("reason", l.getReason());
            row.put("createBy", l.getCreateBy());
            row.put("createDate", l.getCreateDate());
            result.add(row);
        }
        return result;
    }

    @Override
    public Map<String, Object> check(String itemCode, String serialNo) {
        // D4：仅需认证（SecurityConfig /api 需认证即可），只读不写
        Map<String, Object> result = new LinkedHashMap<>();
        if (isBlank(serialNo)) {
            throw new ServiceException(422, "序列号必填");
        }
        InvSerial hit = serialDao.selectOne(new LambdaQueryWrapper<InvSerial>()
                .eq(InvSerial::getSerialNo, serialNo.trim())
                .last("LIMIT 1"));
        result.put("exists", hit != null);
        if (hit != null) {
            result.put("status", hit.getStatus());
            result.put("itemCode", hit.getItemCode());
            result.put("batchNo", hit.getBatchNo());
            result.put("locationRemark", hit.getLocationRemark());
            // 物料一致性提示（判重主口径是序列号本身；物料不符即串码特征）
            result.put("itemMismatch", !isBlank(itemCode) && !itemCode.equals(hit.getItemCode()));
        }
        return result;
    }

    private void appendLog(InvSerial serial, String from, String to, String reason) {
        InvSerialLog l = new InvSerialLog();
        l.setSerialId(serial.getId());
        l.setSerialNo(serial.getSerialNo());
        l.setFromStatus(from);
        l.setToStatus(to);
        l.setReason(reason);
        l.setCreateBy(com.erp.util.SecurityUtils.getCurrentUserId());
        logDao.insert(l);
    }

    private boolean isTerminal(String status) {
        return InvSerial.ST_OUT.equals(status) || InvSerial.ST_SCRAPPED.equals(status);
    }

    private String statusName(String status) {
        switch (status) {
            case InvSerial.ST_IN_STOCK: return "在库";
            case InvSerial.ST_OUT: return "已出库";
            case InvSerial.ST_FROZEN: return "冻结";
            case InvSerial.ST_SCRAPPED: return "报废";
            default: return status;
        }
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
