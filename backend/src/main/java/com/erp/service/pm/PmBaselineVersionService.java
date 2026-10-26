package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 基线版本查询（add-project-change，BR-4.8-31 永久只读）。 */
public interface PmBaselineVersionService {

    /** 项目全部版本（版本号倒序，含 CURRENT_FLAG） */
    List<Map<String, Object>> list(String projectId);

    /** 单版本全量快照（按版本标签，如 V1.0；只读，无 UPDATE 入口） */
    Map<String, Object> snapshot(String projectId, String verNoLabel);

    /** 当前生效版本（CURRENT_FLAG=1） */
    Map<String, Object> current(String projectId);
}
