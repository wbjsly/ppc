package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 在线参数配置（spec price-monitoring：调整留痕、对新判定生效不追溯）。 */
@Getter
@Setter
@TableName("erp_bi_config")
public class BiConfig extends BaseEntity {

    private String configKey;
    private String configValue;
    private String remark;
    /** 调整历史 JSON [{old,new,by,at}] */
    private String historyJson;
}
