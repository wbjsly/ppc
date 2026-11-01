package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 我的查询（add-adhoc-query-console，spec bi-adhoc-query）。
 * 即席查询定义保存复用，仅本人可见。
 */
@Getter
@Setter
@TableName("erp_bi_saved_query")
public class SavedQuery extends BaseEntity {

    private String name;
    private String userName;
    private String templateId;
    private String queryJson;
}
