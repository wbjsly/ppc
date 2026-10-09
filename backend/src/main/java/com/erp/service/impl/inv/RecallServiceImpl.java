package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBinDao;
import com.erp.dao.inv.TraceFlowDao;
import com.erp.dao.inv.TraceLogDao;
import com.erp.dao.inv.TraceOrderDao;
import com.erp.entity.inv.InvBin;
import com.erp.entity.inv.TraceFlow;
import com.erp.entity.inv.TraceLog;
import com.erp.entity.inv.TraceOrder;
import com.erp.service.inv.RecallService;
import com.erp.service.inv.StockPostingEngine;
import com.erp.service.inv.TraceService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 召回入库与处置回调实现（4.13.2，spec trace-recall；design D5/D6）。
 * RECALL_IN 走引擎 intoQc（量进 QC 冻结列——处置完成前天然冻结，BR-4.4-03/C-4.4-05 语义）；
 * 受限区校验（BIN_TYPE ∈ {RETURN,SCRAP}）是服务层 L1 主体（BR-4.4-50），与分配器 QC 分流双保险。
 */
@Slf4j
@Service
public class RecallServiceImpl implements RecallService {

    private final TraceOrderDao orderDao;
    private final TraceFlowDao flowDao;
    private final TraceLogDao logDao;
    private final InvBinDao binDao;
    private final StockPostingEngine engine;

    public RecallServiceImpl(TraceOrderDao orderDao, TraceFlowDao flowDao, TraceLogDao logDao,
                             InvBinDao binDao, StockPostingEngine engine) {
        this.orderDao = orderDao;
        this.flowDao = flowDao;
        this.logDao = logDao;
        this.binDao = binDao;
        this.engine = engine;
    }

    @Override
    @Transactional
    public Map<String, Object> receive(String traceId, String flowId, String warehouseCode,
                                       String binCode, BigDecimal qty) {
        requireWarehouse("召回受限区入库");
        TraceOrder o = isBlank(traceId) ? null : orderDao.selectById(traceId);
        if (o == null) {
            throw new ServiceException(422, "追溯单不存在");
        }
        TraceFlow f = isBlank(flowId) ? null : flowDao.selectById(flowId);
        if (f == null || !o.getId().equals(f.getTraceId())) {
            throw new ServiceException(422, "流向行不存在或不属于该追溯单");
        }
        if (isBlank(binCode)) {
            throw new ServiceException(422, "入库仓位必填");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "入库数量必填且大于 0");
        }
        // BR-4.4-50 L1：仅残次品区/退货区（STORE 等一律 422）
        InvBin bin = binDao.selectOne(new LambdaQueryWrapper<InvBin>()
                .eq(InvBin::getBinCode, binCode.trim()).last("LIMIT 1"));
        if (bin == null) {
            throw new ServiceException(422, "入库仓位不存在：" + binCode);
        }
        String bt = str(bin.getBinType());
        if (!"RETURN".equals(bt) && !"SCRAP".equals(bt)) {
            throw new ServiceException(422, "召回入库仅限残次品区/退货区（BR-4.4-50），"
                    + "仓位 " + binCode + " 类型为 " + bt + "，请改选受限区仓位");
        }
        String wh = isBlank(warehouseCode) ? str(f.getWarehouseCode()) : warehouseCode.trim();
        if (isBlank(wh)) {
            throw new ServiceException(422, "入库仓库必填（流向行未携带仓库）");
        }
        if (!str(bin.getWhCode()).equals(wh)) {
            throw new ServiceException(422, "仓位 " + binCode + " 不属于仓库 " + wh);
        }

        // 引擎过账：intoQc=true 量进 QC 冻结列，来源挂追溯单号
        StockPostingEngine.Line ln = new StockPostingEngine.Line();
        ln.warehouseCode = wh;
        ln.itemCode = f.getItemCode();
        ln.itemName = f.getItemName();
        ln.batchNo = str(f.getBatchNo());
        ln.binCode = binCode.trim();
        ln.qty = qty;
        ln.intoQc = true;
        StockPostingEngine.Result res = engine.post(StockPostingEngine.Request.of(
                "RECALL_IN", "TRACE", o.getTraceNo(), List.of(ln)));
        res.getClass();   // 引擎失败会抛 422/409，到达此处即成功

        TraceLog l = new TraceLog();
        l.setTraceId(o.getId());
        l.setActionUser(str(SecurityUtils.getCurrentUserId()));
        l.setAction(TraceService.A_RECEIVE);
        l.setObjectType("STOCK");
        l.setObjectId(binCode.trim());
        l.setBeforeVal("bin=" + binCode + " type=" + bt);
        l.setAfterVal("RECALL_IN QC+" + qty.toPlainString() + " wh=" + wh);
        l.setOpAt(LocalDateTime.now());
        logDao.insert(l);
        log.info("trace {} receive: {} x {} into {} ({}), intoQc",
                o.getTraceNo(), f.getItemCode(), qty, binCode, bt);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traceNo", o.getTraceNo());
        out.put("binCode", binCode.trim());
        out.put("binType", bt);
        out.put("qty", qty);
        out.put("txnNos", res.txnNos);
        return out;
    }

    @Override
    @Transactional
    public void markDisposed(String traceNo, Set<String> batchNos) {
        if (isBlank(traceNo) || batchNos == null || batchNos.isEmpty()) {
            return;   // 未带 TR 关联的报废不回调
        }
        TraceOrder o = orderDao.selectOne(new LambdaQueryWrapper<TraceOrder>()
                .eq(TraceOrder::getTraceNo, traceNo.trim()).last("LIMIT 1"));
        if (o == null) {
            log.warn("markDisposed: trace {} not found, skip", traceNo);
            return;
        }
        List<TraceFlow> hits = flowDao.selectList(new LambdaQueryWrapper<TraceFlow>()
                .eq(TraceFlow::getTraceId, o.getId())
                .in(TraceFlow::getBatchNo, batchNos)
                .in(TraceFlow::getStatus, TraceFlow.ST_FROZEN, TraceFlow.ST_RECEIVED));
        for (TraceFlow f : hits) {
            String before = f.getStatus();
            f.setStatus(TraceFlow.ST_DISPOSED);
            flowDao.updateById(f);
            TraceLog l = new TraceLog();
            l.setTraceId(o.getId());
            l.setActionUser(str(SecurityUtils.getCurrentUserId()));
            l.setAction(TraceService.A_DISPOSE);
            l.setObjectType("FLOW");
            l.setObjectId(f.getId());
            l.setBeforeVal(before);
            l.setAfterVal(TraceFlow.ST_DISPOSED);
            l.setOpAt(LocalDateTime.now());
            logDao.insert(l);
        }
        if (!hits.isEmpty()) {
            log.info("trace {} disposed {} flow row(s): batches={}", traceNo, hits.size(), batchNos);
        }
    }

    private void requireWarehouse(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r)
                || "ROLE_WAREHOUSE".equalsIgnoreCase(r))) {
            return;
        }
        throw new ServiceException(403, action + "：需要仓库主管或管理员角色（入库/处置为仓库动作）");
    }

    private static String str(Object s) {
        return s == null ? "" : String.valueOf(s);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
