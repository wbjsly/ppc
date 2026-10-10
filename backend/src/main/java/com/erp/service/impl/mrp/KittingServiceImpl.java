package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpMoBomDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpMoShortageDao;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpMoShortage;
import com.erp.service.mrp.KittingService;
import com.erp.service.mrp.MoDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 齐套计算实现（change add-work-order-management，spec「库存预检与缺料清单」/ BR-4.5-04；
 * proposal D5 全系统唯一算法，5.5 复用）。
 * 缺料行重建式维护（派生数据）：每次 check 删除旧清单后按当前库存/在途重算——
 * 在途到货后重新 check 齐套恢复 100% → 自动清「缺料待料」标记（spec 场景）。
 */
@Slf4j
@Service
public class KittingServiceImpl implements KittingService {

    private final MrpMoDao moDao;
    private final MrpMoBomDao bomDao;
    private final MrpMoShortageDao shortageDao;
    private final MoDataSource moDataSource;

    public KittingServiceImpl(MrpMoDao moDao, MrpMoBomDao bomDao,
                              MrpMoShortageDao shortageDao, MoDataSource moDataSource) {
        this.moDao = moDao;
        this.bomDao = bomDao;
        this.shortageDao = shortageDao;
        this.moDataSource = moDataSource;
    }

    @Override
    @Transactional
    public BigDecimal check(String moId) {
        MrpMo mo = moDao.selectById(moId);
        if (mo == null) {
            throw new ServiceException(404, "工单不存在：" + moId);
        }
        List<MrpMoBom> lines = bomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                .eq(MrpMoBom::getMoId, moId)
                .orderByAsc(MrpMoBom::getLineNo));
        // 清单重建（旧缺料行是派生数据）
        shortageDao.delete(new LambdaQueryWrapper<MrpMoShortage>()
                .eq(MrpMoShortage::getMoId, moId));

        if (lines.isEmpty()) {
            setShortageFlag(mo, "0");
            return BigDecimal.valueOf(100);
        }
        Set<String> codes = lines.stream().map(MrpMoBom::getItemCode)
                .filter(c -> c != null && !c.isEmpty()).collect(Collectors.toSet());
        Map<String, BigDecimal> onHand = moDataSource.base().onHand(codes);
        Map<String, BigDecimal> inTransit = moDataSource.base().inTransit(codes);

        BigDecimal moQty = mo.getQty() == null ? BigDecimal.ZERO : mo.getQty();
        int satisfied = 0;
        int shortCount = 0;
        for (MrpMoBom line : lines) {
            BigDecimal usage = line.getUnitQty() == null ? BigDecimal.ZERO : line.getUnitQty();
            BigDecimal loss = line.getLossRate() == null ? BigDecimal.ZERO : line.getLossRate();
            // 需求 = 工单数量 × 单位用量 × (1 + 损耗率)（BR-4.5-04 公式）
            BigDecimal req = moQty.multiply(usage)
                    .multiply(BigDecimal.ONE.add(loss))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal avail = onHand.getOrDefault(line.getItemCode(), BigDecimal.ZERO)
                    .add(inTransit.getOrDefault(line.getItemCode(), BigDecimal.ZERO));
            BigDecimal shortQty = req.subtract(avail);
            if (shortQty.signum() > 0) {
                shortCount++;
                MrpMoShortage row = new MrpMoShortage();
                row.setMoId(moId);
                row.setBomLine(line.getLineNo());
                row.setItemCode(line.getItemCode());
                row.setItemName(line.getItemName());
                row.setReqQty(req);
                row.setAvailQty(avail);
                row.setShortQty(shortQty);
                row.setResolvedFlag("0");
                shortageDao.insert(row);
            } else {
                satisfied++;
            }
        }
        BigDecimal rate = BigDecimal.valueOf(satisfied * 100L)
                .divide(BigDecimal.valueOf(lines.size()), 1, RoundingMode.HALF_UP);
        setShortageFlag(mo, shortCount > 0 ? "1" : "0");
        log.info("工单齐套计算：{} 齐套率={}%% 缺料行={}/{}",
                mo.getMoNo(), rate, shortCount, lines.size());
        return rate;
    }

    @Override
    public List<MrpMoShortage> shortages(String moId) {
        return shortageDao.selectList(new LambdaQueryWrapper<MrpMoShortage>()
                .eq(MrpMoShortage::getMoId, moId)
                .orderByAsc(MrpMoShortage::getBomLine));
    }

    /** 置/清「缺料待料」标记（变化时才写，避免无谓 ver_no 递增） */
    private void setShortageFlag(MrpMo mo, String flag) {
        if (flag.equals(mo.getShortageFlag())) {
            return;
        }
        moDao.update(null, new LambdaUpdateWrapper<MrpMo>()
                .eq(MrpMo::getId, mo.getId())
                .set(MrpMo::getShortageFlag, flag));
        mo.setShortageFlag(flag);
    }
}
