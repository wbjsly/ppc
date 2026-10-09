package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.PickScanLogDao;
import com.erp.dao.inv.PickTaskDao;
import com.erp.dao.inv.PickTaskLineDao;
import com.erp.dao.inv.InvSerialDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.entity.inv.InvSerial;
import com.erp.entity.inv.PickScanLog;
import com.erp.entity.inv.PickTask;
import com.erp.entity.inv.PickTaskLine;
import com.erp.entity.mdm.MdmItem;
import com.erp.service.inv.PickDiffService;
import com.erp.service.inv.PickScanService;
import com.erp.service.inv.PickTaskService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行级扫码确认实现（4.7.2，spec picking-review；档位 2，偏差 D1 不做逐码事件流）：
 * verify = 三码逐项比对（不符 FAIL 记录+422，批次不符附带 BATCH 差异登记）；
 * confirmLine = 放行前置 + 实拣一致 + 序列校验 → 行 PICKED → 聚合任务 PICKED。
 */
@Slf4j
@Service
public class PickScanServiceImpl implements PickScanService {

    private final PickTaskDao taskDao;
    private final PickTaskLineDao lineDao;
    private final PickScanLogDao scanLogDao;
    private final MdmItemDao itemDao;
    private final InvSerialDao serialDao;
    private final PickTaskService pickTaskService;
    private final PickDiffService pickDiffService;

    public PickScanServiceImpl(PickTaskDao taskDao, PickTaskLineDao lineDao,
                               PickScanLogDao scanLogDao, MdmItemDao itemDao,
                               InvSerialDao serialDao,
                               PickTaskService pickTaskService, PickDiffService pickDiffService) {
        this.taskDao = taskDao;
        this.lineDao = lineDao;
        this.scanLogDao = scanLogDao;
        this.itemDao = itemDao;
        this.serialDao = serialDao;
        this.pickTaskService = pickTaskService;
        this.pickDiffService = pickDiffService;
    }

    // ---------- verify（BR-4.4-25/26/27） ----------

    @Override
    // 不加事务：FAIL 记录/批次差异登记须在 422 抛出后仍持久（spec：写入失败明细并阻断），
    // 事务化会随 ServiceException 回滚；registerDiff 自身事务在正常返回时已提交
    public Map<String, Object> verify(String taskId, Integer lineNo, String binCode,
                                      String itemCode, String batchNo) {
        PickTask task = requireTask(taskId);
        if (!PickTask.ST_PICKING.equals(task.getStatus())) {
            throw new ServiceException(422, "任务未在拣货中（当前 " + task.getStatus()
                    + "），请先开始拣货");
        }
        PickTaskLine line = requireLine(taskId, lineNo);

        // 逐项比对（期望值为空的项跳过——未回写仓位/批次时不要求该码）；
        // 失败先落记录、批次不符再登记差异，最后统一 422（全部须在 422 后持久，故 verify 无事务）
        String fail = firstFail(taskId, line, binCode, itemCode, batchNo);
        if (fail != null) {
            if (fail.startsWith("BATCH:")) {
                // BR-4.4-26：实拣批次 ≠ 推荐批次 → 登记 BATCH 差异（幂等）+ 任务挂分支
                pickDiffService.registerDiff(taskId, lineNo,
                        com.erp.entity.inv.InvCheckDiff.K_BATCH, null, null,
                        "扫码批次不符：" + fail.substring(6));
            }
            throw new ServiceException(422, fail.substring(fail.indexOf(':') + 1));
        }

        // 放行标记
        line.setScanOkFlag("1");
        line.setVerNo(line.getVerNo());
        if (lineDao.updateById(line) == 0) {
            throw new ServiceException(409, "行更新冲突，请刷新重试");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verified", true);
        out.put("lineNo", lineNo);
        out.put("expect", Map.of("binCode", str(line.getBinCode()),
                "itemCode", line.getItemCode(), "batchNo", str(line.getBatchNo()),
                "qty", line.getQty()));
        return out;
    }

    /**
     * 顺序比对三码，返回第一个失败描述（"BIN:xxx"/"ITEM:xxx"/"BATCH:期望 X 实扫 Y" 或 null=通过）；
     * 未扫描（输入空）返回 "TYPE:请扫描..."；失败已落 FAIL 记录。
     */
    private String firstFail(String taskId, PickTaskLine line, String binCode,
                             String itemCode, String batchNo) {
        String f = one(taskId, line, PickScanLog.T_BIN, binCode, line.getBinCode());
        if (f != null) {
            return f;
        }
        f = one(taskId, line, PickScanLog.T_ITEM, itemCode, line.getItemCode());
        if (f != null) {
            return f;
        }
        return one(taskId, line, PickScanLog.T_BATCH, batchNo, line.getBatchNo());
    }

    private String one(String taskId, PickTaskLine line, String scanType,
                       String input, String expect) {
        if (expect == null || expect.trim().isEmpty()) {
            return null;   // 期望为空 → 该项不校验
        }
        String in = input == null ? "" : input.trim();
        String type = PickScanLog.T_BIN.equals(scanType) ? "BIN"
                : PickScanLog.T_ITEM.equals(scanType) ? "ITEM" : "BATCH";
        if (in.isEmpty()) {
            return type + ":请扫描" + typeName(scanType) + "（期望 " + expect + "）";
        }
        if (!expect.trim().equals(in)) {
            fail(taskId, line, scanType, in, expect);
            if (PickScanLog.T_BIN.equals(scanType)) {
                return "BIN:仓位码不符，已阻断到位确认（BR-4.4-25），正确仓位：" + expect;
            }
            if (PickScanLog.T_BATCH.equals(scanType)) {
                return "BATCH:" + "批次与推荐批次不一致，已阻断拣货确认（BR-4.4-26）：期望 "
                        + expect + " 实扫 " + in;
            }
            return "ITEM:物料不符，已阻断（BR-4.4-27）：期望 " + expect;
        }
        return null;
    }

    private void fail(String taskId, PickTaskLine line, String scanType,
                      String input, String expect) {
        PickScanLog l = new PickScanLog();
        l.setTaskId(taskId);
        l.setLineId(line.getId());
        l.setScanType(scanType);
        l.setInputCode(input);
        l.setExpectCode(expect);
        l.setResult(PickScanLog.R_FAIL);
        l.setFailReason(typeName(scanType) + "不符");
        l.setCreateBy(SecurityUtils.getCurrentUserId());
        scanLogDao.insert(l);
    }

    private static String typeName(String scanType) {
        return PickScanLog.T_BIN.equals(scanType) ? "仓位码"
                : PickScanLog.T_ITEM.equals(scanType) ? "物料码"
                : PickScanLog.T_BATCH.equals(scanType) ? "批次码" : "序列号";
    }

    // ---------- confirmLine（FR-4.4-4-5） ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmLine(String taskId, Integer lineNo, BigDecimal pickedQty,
                                           String serials) {
        PickTask task = requireTask(taskId);
        if (!PickTask.ST_PICKING.equals(task.getStatus())) {
            throw new ServiceException(422, "任务未在拣货中（当前 " + task.getStatus() + "）");
        }
        PickTaskLine line = requireLine(taskId, lineNo);
        if (!"1".equals(line.getScanOkFlag())) {
            throw new ServiceException(422, "请先完成码校验（本行尚未放行）");
        }
        if (pickedQty == null || pickedQty.signum() <= 0) {
            throw new ServiceException(422, "实拣数量必须大于 0");
        }
        if (line.getQty() != null && pickedQty.compareTo(line.getQty()) > 0) {
            throw new ServiceException(422, "实拣数量不得大于应拣数量（应拣 "
                    + strip(line.getQty()) + "）；多拣请走差异登记");
        }
        if (pickedQty.compareTo(line.getQty()) < 0) {
            throw new ServiceException(422, "实拣少于应拣（应拣 " + strip(line.getQty())
                    + " 实拣 " + strip(pickedQty) + "），请走短少差异登记");
        }
        // 序列物料：必填且在库可用（C-4.4-02 口径与引擎一致）
        MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, line.getItemCode()).last("LIMIT 1"));
        if (item != null && "1".equals(item.getSerialFlag())) {
            validateSerials(line, serials);
            line.setSerials(serials.trim());
        }

        line.setPickedQty(pickedQty);
        line.setLineStatus(PickTaskLine.LS_PICKED);
        line.setVerNo(line.getVerNo());
        if (lineDao.updateById(line) == 0) {
            throw new ServiceException(409, "行更新冲突，请刷新重试");
        }
        // 放行标记（spec：每行最终放行标记落扫码记录）
        logPass(taskId, line);

        // 全行 PICKED → 任务 PICKED
        Long unpicked = lineDao.selectCount(new LambdaQueryWrapper<PickTaskLine>()
                .eq(PickTaskLine::getTaskId, taskId)
                .ne(PickTaskLine::getId, line.getId())
                .ne(PickTaskLine::getLineStatus, PickTaskLine.LS_PICKED));
        if (unpicked == null || unpicked == 0) {
            pickTaskService.transition(taskId, PickTask.ST_PICKING, PickTask.ST_PICKED);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("line", lineDao.selectById(line.getId()));
        out.put("taskStatus", pickTaskService.detail(taskId).get("task"));
        return out;
    }

    /** 序列校验（C-4.4-02，与引擎 checkSerialsOut 同口径：存在 + 物料匹配 + 在库可用） */
    private void validateSerials(PickTaskLine line, String serials) {
        if (serials == null || serials.trim().isEmpty()) {
            throw new ServiceException(422, "请录入序列号（C-4.4-02）：" + line.getItemCode());
        }
        List<String> bad = new ArrayList<>();
        for (String sn : serials.split(",")) {
            if (sn.trim().isEmpty()) {
                continue;
            }
            InvSerial row = serialDao.selectOne(new LambdaQueryWrapper<InvSerial>()
                    .eq(InvSerial::getSerialNo, sn.trim()).last("LIMIT 1"));
            boolean usable = row != null
                    && line.getItemCode().equals(row.getItemCode())
                    && InvSerial.ST_IN_STOCK.equals(row.getStatus());
            if (!usable) {
                bad.add(sn.trim());
            }
        }
        if (!bad.isEmpty()) {
            throw new ServiceException(422, "序列号不可用（不在库或物料不符）：" + String.join(",", bad));
        }
    }

    private void logPass(String taskId, PickTaskLine line) {
        PickScanLog l = new PickScanLog();
        l.setTaskId(taskId);
        l.setLineId(line.getId());
        l.setScanType(PickScanLog.T_PASS);
        l.setResult(PickScanLog.R_OK);
        l.setCreateBy(SecurityUtils.getCurrentUserId());
        scanLogDao.insert(l);
    }

    // ---------- registerShort（BR-4.4-28） ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> registerShort(String taskId, Integer lineNo,
                                             BigDecimal actualQty, String reason) {
        PickTaskLine line = requireLine(taskId, lineNo);
        if (actualQty == null || actualQty.signum() < 0) {
            throw new ServiceException(422, "实拣数量非法");
        }
        if (actualQty.compareTo(line.getQty()) >= 0) {
            throw new ServiceException(422, "实拣未少于应拣，无需登记短少差异");
        }
        var diff = pickDiffService.registerDiff(taskId, lineNo,
                com.erp.entity.inv.InvCheckDiff.K_QTY, line.getQty(), actualQty, reason);

        line.setPickedQty(actualQty);
        line.setLineStatus(PickTaskLine.LS_SHORT);
        line.setVerNo(line.getVerNo());
        if (lineDao.updateById(line) == 0) {
            throw new ServiceException(409, "行更新冲突，请刷新重试");
        }
        log.info("pick short registered: task={} line={} expect={} actual={}",
                taskId, lineNo, line.getQty(), actualQty);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diff", diff);
        out.put("line", lineDao.selectById(line.getId()));
        return out;
    }

    // ---------- helpers ----------

    private PickTask requireTask(String taskId) {
        PickTask t = taskDao.selectById(taskId);
        if (t == null) {
            throw new ServiceException(404, "拣货任务不存在：" + taskId);
        }
        return t;
    }

    private PickTaskLine requireLine(String taskId, Integer lineNo) {
        PickTaskLine l = lineDao.selectOne(new LambdaQueryWrapper<PickTaskLine>()
                .eq(PickTaskLine::getTaskId, taskId)
                .eq(PickTaskLine::getLineNo, lineNo)
                .last("LIMIT 1"));
        if (l == null) {
            throw new ServiceException(404, "任务行不存在：" + lineNo);
        }
        return l;
    }

    private static String str(String v) {
        return v == null ? "" : v;
    }

    private static String strip(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
