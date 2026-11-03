-- 209-workbench-dash.sql（add-configurable-workbench，spec personal-workbench）
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE，可重复执行。
-- ① erp_admin_dash_layout 单表承载三层配置（GLOBAL/ROLE/USER，D2：只 upsert 永不软删，
--    故唯一键不掺 DEL_FLAG 位；"恢复默认"= 清空 LAYOUT_JSON 而非删行）；
-- ② GLOBAL 基线布局（10 个 ready widget 的默认 12 列网格）。

-- ---- ① 三层布局表 ----
CREATE TABLE IF NOT EXISTS erp_admin_dash_layout (
    ID          varchar(36)   NOT NULL,
    SCOPE       varchar(8)    NOT NULL COMMENT 'GLOBAL/ROLE/USER',
    REF_ID      varchar(64)   NOT NULL COMMENT 'GLOBAL=ALL；ROLE=角色名；USER=用户ID',
    LAYOUT_JSON mediumtext    NULL COMMENT '{"v":1,"items":[{id,x,y,w,h}],"excluded":[id],"shortcuts":[...]}',
    CREATE_BY   varchar(64)   NULL,
    CREATE_DATE datetime      NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY   varchar(64)   NULL,
    UPDATE_DATE datetime      NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG    varchar(1)    NOT NULL DEFAULT '0',
    VER_NO      int           NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_DASH (SCOPE, REF_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工作台三层布局（全局/角色/个人）';

-- ---- ② GLOBAL 基线（10 widget 默认网格：12 列，行 0/4/8/12 四层） ----
INSERT IGNORE INTO erp_admin_dash_layout
    (ID, SCOPE, REF_ID, LAYOUT_JSON, CREATE_BY, CREATE_DATE, UPDATE_BY, UPDATE_DATE, DEL_FLAG, VER_NO)
VALUES
    ('DASH-GLOBAL-BASELINE', 'GLOBAL', 'ALL',
     '{"v":1,"items":[
        {"id":"todo","x":0,"y":0,"w":4,"h":4},
        {"id":"notice","x":4,"y":0,"w":4,"h":4},
        {"id":"shortcuts","x":8,"y":0,"w":4,"h":4},
        {"id":"kpi-summary","x":0,"y":4,"w":4,"h":4},
        {"id":"kpi-trend","x":4,"y":4,"w":8,"h":4},
        {"id":"period","x":0,"y":8,"w":4,"h":4},
        {"id":"kpi-alerts","x":4,"y":8,"w":4,"h":4},
        {"id":"ops-alert","x":8,"y":8,"w":4,"h":4},
        {"id":"iot-alarm","x":0,"y":12,"w":6,"h":4},
        {"id":"authz-alert","x":6,"y":12,"w":6,"h":4}
      ],
      "excluded":[],
      "shortcuts":[]}',
     'system', NOW(), 'system', NOW(), '0', 0);
