package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.pm.PmProjectDao;
import com.erp.entity.pm.PmProject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 项目编号生成（PRJ+yyyy+4位流水）。
 * 年内 MAX 扫描 + UK(PROJECT_NO) 兜底重试一次（design D5 并发风险）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmProjectNoGenerator {

    private final PmProjectDao projectDao;

    public String next() {
        String prefix = "PRJ" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy"));
        for (int attempt = 0; attempt < 2; attempt++) {
            long max = projectDao.selectList(new LambdaQueryWrapper<PmProject>()
                            .likeRight(PmProject::getProjectNo, prefix)
                            .select(PmProject::getProjectNo)).stream()
                    .map(PmProject::getProjectNo)
                    .mapToInt(no -> {
                        try {
                            return Integer.parseInt(no.substring(prefix.length()));
                        } catch (Exception e) {
                            return 0;
                        }
                    }).max().orElse(0);
            String candidate = String.format("%s%04d", prefix, max + 1);
            Long clash = projectDao.selectCount(new LambdaQueryWrapper<PmProject>()
                    .eq(PmProject::getProjectNo, candidate));
            if (clash == null || clash == 0) {
                return candidate;
            }
            log.warn("project no clash, retry: {}", candidate);
        }
        // 兜底：时间戳后缀
        return prefix + String.valueOf(System.currentTimeMillis() % 10000);
    }
}
