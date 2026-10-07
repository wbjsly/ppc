package com.erp.dao.bi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.bi.BiExportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BiExportTaskDao extends BaseMapper<BiExportTask> {

    /** 同用户进行中任务数（并行 ≤3） */
    @Select("SELECT COUNT(*) FROM erp_bi_export_task WHERE TASK_TYPE = 'EXPORT' " +
            "AND USER_NAME = #{user} AND STATUS IN ('QUEUED','RUNNING') AND DEL_FLAG = '0'")
    int countActiveExports(@Param("user") String user);

    /** 待执行导出（超量行仅取审批通过的） */
    @Select("SELECT * FROM erp_bi_export_task WHERE TASK_TYPE = 'EXPORT' AND STATUS = 'QUEUED' " +
            "AND (APPROVAL_STATUS IS NULL OR APPROVAL_STATUS = 'APPROVED') " +
            "AND DEL_FLAG = '0' ORDER BY CREATE_DATE ASC LIMIT 5")
    List<BiExportTask> selectDueExports();

    /** 用户导出任务列表（站内通知数据源） */
    @Select("SELECT * FROM erp_bi_export_task WHERE TASK_TYPE = 'EXPORT' AND USER_NAME = #{user} " +
            "AND DEL_FLAG = '0' ORDER BY CREATE_DATE DESC LIMIT 50")
    List<BiExportTask> selectUserExports(@Param("user") String user);

    @Select("SELECT * FROM erp_bi_export_task WHERE TASK_TYPE = 'EXPORT' " +
            "AND APPROVAL_STATUS = 'PENDING' AND DEL_FLAG = '0' ORDER BY CREATE_DATE DESC")
    List<BiExportTask> selectPendingApproval();
}
