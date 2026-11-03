package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 工作台三层布局（change add-configurable-workbench，spec personal-workbench，迁移 209）。
 * 单表承载 GLOBAL/ROLE/USER 三层（design D1）：
 * - SCOPE=GLOBAL  REF_ID='ALL'
 * - SCOPE=ROLE    REF_ID=角色名
 * - SCOPE=USER    REF_ID=用户ID
 * 业务约定：只 upsert 永不软删（恢复默认=清空 LAYOUT_JSON），
 * 唯一键 UK_DASH(SCOPE,REF_ID) 不掺 DEL_FLAG 位——吸取 erp_fin_balance 幽灵唯一键教训。
 */
@Getter
@Setter
@TableName("erp_admin_dash_layout")
public class DashLayout extends BaseEntity {

    /** GLOBAL / ROLE / USER */
    private String scope;
    /** GLOBAL=ALL；ROLE=角色名；USER=用户ID */
    private String refId;
    /** {"v":1,"items":[{id,x,y,w,h}],"excluded":[id],"shortcuts":[...]} */
    private String layoutJson;
}
