package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamCipCost;
import com.erp.entity.eam.EamCipProject;

import java.util.List;
import java.util.Map;

/**
 * 在建工程（CIP）台账（add-asset-capitalization，spec asset-capitalization）。
 * 项目 + 成本归集明细（工程款/安装费/资本化利息三类，BR-4.13-08）。
 */
public interface EamCipService {

    /** 项目分页（状态/关键字） */
    Page<EamCipProject> page(long current, long size, String keyword, String status);

    /** 新建项目（编号自动生成 CIP+yyyyMMdd+4 位流水） */
    EamCipProject createProject(EamCipProject project);

    /** 修改项目（仅 OPEN；乐观锁） */
    EamCipProject updateProject(EamCipProject project);

    /** 归集明细录入（类别三选一 422；项目须 OPEN） */
    EamCipCost addCost(EamCipCost cost);

    /** 项目详情：项目 + 归集明细列表 + 按类别合计 */
    Map<String, Object> detail(String projectId);

    /** 归集明细按类别合计（BR-4.13-08 差额检查与转资原值取数） */
    List<Map<String, Object>> costSummary(String projectId);

    /** 完工验收：置 COMPLETED + 记录完工验收日期（超期预警基准日，BR-4.13-06） */
    EamCipProject complete(String projectId, java.time.LocalDate date);
}
