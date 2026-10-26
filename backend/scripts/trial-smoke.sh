#!/bin/zsh
# add-trial-production-management 全链路 API 冒烟（任务 6.1，design D13 断言链，可重跑）
# 链路：清理→账号→登录→401→造数（物料/BOM/路线/库存/批次）→申请建档校验→申请审批→转开 TP- 编码→
#       建单卡控（未审批试产BOM/正式BOM确认/三池在途）→PVT diff 两分支→MRP 排除试产缺料（RW 并入）→
#       看板/转采购排除→样品批次优先/挪用双人确认/过账打标→完工入 QC 不入 AVAILABLE→
#       未放行入可用阻断→放行平移+冲回分录+幂等→财务确认（403 先行）→采集缺记录阻断关单→
#       总结指标自动算→阶段闸口/PVT 良率闸口→转量产复制+未评审阻断发布→剩余料 L2 关单→三叶 401/403
set -u
B=http://127.0.0.1:8090
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✘ $1"; }
C() { curl -s --noproxy '*' "$@"; }
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
EQ() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望[$3] 实际[$2]）"; fi; }
NEAR() { if python3 -c "import sys; sys.exit(0 if abs(float('$2')-float('$3'))<0.01 else 1)" 2>/dev/null; \
  then ok "$1"; else bad "$1（期望≈$3 实际 $2）"; fi; }
CONTAINS() { case "$2" in *"$3"*) ok "$1";; *) bad "$1（未包含[$3]，实际[$2]）";; esac; }
NOTCONTAINS() { case "$2" in *"$3"*) bad "$1（不应包含[$3]）";; *) ok "$1";; esac; }
HTTP() { curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$@"; }
BIZ() { C "$@" | J "d['code']"; }
MSG() { C "$@" | J "d['message']"; }
export MYSQL_PWD=root123
SQL() { docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --default-character-set=utf8mb4 -uroot -N -B erp -e "$1" 2>/dev/null; }
A() { echo "Authorization: Bearer $1"; }

echo "== 0. 前置清理（试产冒烟族 TRIK，可重跑） =="
SQL "
DELETE FROM erp_sys_approval_task t1 WHERE t1.APPR_ID IN (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN ('TrialApply','TrialBom','TrialRoute','TrialPvtDiff','TrialBorrow','TrialReview','TrialCostReverse','BomPublish','RoutingPublish'));
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('TrialApply','TrialBom','TrialRoute','TrialPvtDiff','TrialBorrow','TrialReview','TrialCostReverse');
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('BomPublish','RoutingPublish') AND BIZ_ID IN (SELECT ID FROM erp_mrp_bom WHERE PARENT_ITEM_CODE LIKE 'TRIK%');
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('BomPublish','RoutingPublish') AND BIZ_ID IN (SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE LIKE 'TRIK%');
DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'TRIAL_%' OR BIZ_TYPE LIKE '%TRIAL%';
DELETE FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER' AND REF_ID LIKE '%#TRIAL';
DELETE FROM erp_mrp_mo_freeze WHERE MO_NO LIKE 'TP-%' OR MO_NO='RW-TRI-001';
DELETE FROM erp_qms_ncr WHERE ITEM_CODE LIKE 'TRIK%';
DELETE FROM erp_mrp_trial_cost; DELETE FROM erp_mrp_trial_summary;
DELETE FROM erp_mrp_trial_data; DELETE FROM erp_mrp_trial_apply;
DELETE FROM erp_inv_material_issue_line WHERE ISSUE_ID IN (SELECT ID FROM erp_inv_material_issue WHERE WORK_ORDER_NO LIKE 'TP-%');
DELETE FROM erp_inv_material_issue WHERE WORK_ORDER_NO LIKE 'TP-%';
DELETE FROM erp_mrp_dispatch WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE MO_TYPE='TRIAL' OR MO_NO='RW-TRI-001');
DELETE FROM erp_mrp_reporting WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE MO_TYPE='TRIAL' OR MO_NO='RW-TRI-001');
DELETE FROM erp_mrp_feed WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE MO_TYPE='TRIAL' OR MO_NO='RW-TRI-001');
DELETE FROM erp_mrp_output WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE MO_TYPE='TRIAL' OR MO_NO='RW-TRI-001');
DELETE FROM erp_mrp_reservation WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE MO_TYPE='TRIAL' OR MO_NO='RW-TRI-001');
DELETE FROM erp_mrp_mo_shortage WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE MO_TYPE='TRIAL' OR MO_NO='RW-TRI-001');
DELETE FROM erp_mrp_mo_bom WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE MO_TYPE='TRIAL');
DELETE FROM erp_mrp_mo_op WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE MO_TYPE='TRIAL');
DELETE FROM erp_mrp_mo WHERE MO_TYPE='TRIAL' OR MO_NO='RW-TRI-001';
DELETE bi FROM erp_mrp_bom_item bi JOIN erp_mrp_bom b ON bi.BOM_ID=b.ID WHERE b.PARENT_ITEM_CODE LIKE 'TRIK%';
DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE LIKE 'TRIK%';
DELETE ro FROM erp_mrp_routing_op ro JOIN erp_mrp_routing r ON ro.ROUTING_ID=r.ID WHERE r.ITEM_CODE LIKE 'TRIK%';
DELETE FROM erp_mrp_routing WHERE ITEM_CODE LIKE 'TRIK%';
DELETE FROM erp_mrp_suggestion WHERE ITEM_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '试产冒烟%');
DELETE FROM erp_mrp_mo_shortage WHERE ITEM_NAME LIKE '试产冒烟%';
DELETE FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='TRIAL_STOCK_IN' OR (BIZ_DOC_TYPE='TRANSFER' AND BIZ_DOC_NO LIKE '%TRI%');
DELETE FROM erp_inv_stock WHERE ITEM_NAME LIKE '试产冒烟%';
DELETE FROM erp_inv_batch WHERE ITEM_NAME LIKE '试产冒烟%';
DELETE FROM erp_inv_transfer_order_line WHERE BATCH_NO LIKE 'B-TRI%';
DELETE FROM erp_inv_transfer_order WHERE ID NOT IN (SELECT ORDER_ID FROM erp_inv_transfer_order_line);
DELETE FROM erp_mrp_op_wc_standard WHERE WC_CODE LIKE 'TRIWC%';
DELETE FROM erp_mrp_routing_op WHERE OP_CODE LIKE 'TRIOP%';
DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'TRIOP%';
DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'TRIWC%';
DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '试产冒烟%';
DELETE FROM erp_mdm_item_category WHERE ID='cat-tri';
DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'user-tri%';
DELETE FROM erp_admin_user WHERE USERNAME LIKE 'tri%';
UPDATE erp_sys_param SET PARAM_VALUE='ON' WHERE PARAM_KEY IN ('FAI_ENFORCE','IPQC_ENFORCE');
INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX)
 VALUES ('cat-tri','TRIK','试产冒烟分类','TRIK');" >/dev/null 2>&1 \
  && ok "清理完成（TRIK 分类就位、FAI/IPQC 恢复 ON）" || bad "清理失败"

echo "== 1. 账号 =="
AHASH=$(SQL "SELECT PASSWORD FROM erp_admin_user WHERE USERNAME='admin' LIMIT 1")
mkuser() {
  SQL "INSERT IGNORE INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, CREATE_BY)
       VALUES ('user-$1','$1','$AHASH','$2','1','smoke');
       INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) VALUES ('ur-$1','user-$1','$3');" >/dev/null 2>&1
}
mkuser traplan   "试产计划员"   role-planner
mkuser traprm    "试产工艺主管" role-process-mgr
mkuser traplm    "试产计划主管" role-plan-mgr
mkuser triq      "试产质量主管" role-quality-mgr
mkuser trieng    "试产工艺员"   role-process-eng
mkuser trifin    "试产财务主管" role-fin-mgr
mkuser trifloor  "试产操作工"   role-shop-floor
mkuser triwh     "试产仓管"     role-warehouse
N=$(SQL "SELECT COUNT(*) FROM erp_admin_user WHERE USERNAME IN ('traplan','traprm','traplm','triq','trieng','trifin','trifloor','triwh')")
EQ "八账号就位" "$N" "8"

echo "== 2. 登录 =="
TPLAN=$(C -H 'Content-Type: application/json' -d '{"username":"traplan","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TPRM=$(C -H 'Content-Type: application/json' -d '{"username":"traprm","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TPLM=$(C -H 'Content-Type: application/json' -d '{"username":"traplm","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TQ=$(C -H 'Content-Type: application/json' -d '{"username":"triq","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TENG=$(C -H 'Content-Type: application/json' -d '{"username":"trieng","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TFIN=$(C -H 'Content-Type: application/json' -d '{"username":"trifin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TFLO=$(C -H 'Content-Type: application/json' -d '{"username":"trifloor","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TWH=$(C -H 'Content-Type: application/json' -d '{"username":"triwh","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TPLAN" ] && [ -n "$TPRM" ] && [ -n "$TQ" ] && [ -n "$TFLO" ] && [ -n "$TWH" ] \
  && ok "八角色+admin 登录" || bad "登录失败"

echo "== 3. 401 矩阵（HTTP 层） =="
EQ "POST /mrp/trial/apply/create 401" "$(HTTP -X POST $B/api/mrp/trial/apply/create)" "401"
EQ "POST /mrp/trial/apply/submit 401" "$(HTTP -X POST $B/api/mrp/trial/apply/submit)" "401"
EQ "POST /mrp/trial/convert 401" "$(HTTP -X POST $B/api/mrp/trial/convert)" "401"
EQ "GET /mrp/trial/list 401" "$(HTTP $B/api/mrp/trial/list)" "401"
EQ "GET /mrp/trial/phase/x 401" "$(HTTP $B/api/mrp/trial/phase/x)" "401"
EQ "GET /mrp/trial/cost/x 401" "$(HTTP $B/api/mrp/trial/cost/x)" "401"
EQ "POST /mrp/trial/summary/save 401" "$(HTTP -X POST $B/api/mrp/trial/summary/save)" "401"
EQ "POST /mrp/boms/x/submit-trial 401" "$(HTTP -X POST $B/api/mrp/boms/x/submit-trial)" "401"
EQ "POST /mrp/routings/x/submit-trial 401" "$(HTTP -X POST $B/api/mrp/routings/x/submit-trial)" "401"
EQ "POST /inv/issues 401" "$(HTTP -X POST $B/api/inv/issues)" "401"
EQ "POST /mrp/trial/remainder/ack 401" "$(HTTP -X POST $B/api/mrp/trial/remainder/ack)" "401"
EQ "POST /mrp/trial/promote 401" "$(HTTP -X POST $B/api/mrp/trial/promote)" "401"

echo "== 4. 造数（物料/BOM/路线/库存/批次） =="
mkitem() {
  C -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"itemName\":\"试产冒烟$1\",\"categoryCode\":\"TRIK\",\"baseUnit\":\"PC\",\"materialGroup\":\"STRUCT\",\"purchaseType\":\"$2\",\"storageCondition\":\"NORMAL\",\"batchFlag\":\"$3\",\"shelfLifeDays\":365,\"packingSpec\":\"箱\",\"dupNote\":\"试产管理冒烟物料，非业务重复数据\"}" \
    "$B/api/mdm/items?forceCreate=true" | J "d['data']['itemCode']"
}
P1=$(mkitem 产品 MAKE 0); C1=$(mkitem 子件 BUY 1)
P2=$(mkitem 卡控产品 MAKE 0); C2=$(mkitem 卡控子件 BUY 0)
P3=$(mkitem 正式产品 MAKE 0); C3=$(mkitem 正式子件 BUY 0)
PQ=$(mkitem PVT产品 MAKE 0);   CQ=$(mkitem PVT子件 BUY 0)
PB=$(mkitem 样品优先备料 BUY 1); PM=$(mkitem 挪用备料 BUY 1)
MX=$(mkitem MRP试产缺料 BUY 0); MY=$(mkitem MRP返工缺料 BUY 0)
[ -n "$P1" ] && [ -n "$C1" ] && [ -n "$PQ" ] && [ -n "$MX" ] && ok "八物料就位 P1=$P1" || bad "建物料失败"
SQL "UPDATE erp_mdm_item SET STANDARD_COST=50 WHERE ITEM_CODE='$P1';
     UPDATE erp_mdm_item SET STANDARD_COST=100 WHERE ITEM_CODE='$C1';
     UPDATE erp_mdm_item SET STANDARD_COST=60 WHERE ITEM_CODE='$PQ';" >/dev/null
# 量产 BOM（P1：损耗 0.1 供倒冲差额；PQ 试产版与量产版 qty 差异供 PVT diff）
B1=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$P1\"},\"items\":[{\"itemCode\":\"$C1\",\"qty\":1,\"lossRate\":0.1}]}" \
  $B/api/mrp/boms | J "d['data']['id']")
BQ=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$PQ\"},\"items\":[{\"itemCode\":\"$CQ\",\"qty\":1,\"lossRate\":0}]}" \
  $B/api/mrp/boms | J "d['data']['id']")
SQL "UPDATE erp_mrp_bom SET STATUS='PUBLISHED', PUBLISH_BY='smoke', PUBLISH_AT=NOW() WHERE ID IN ('$B1','$BQ');" >/dev/null
EQ "量产 BOM 双发布" "$(SQL "SELECT COUNT(*) FROM erp_mrp_bom WHERE STATUS='PUBLISHED' AND ID IN ('$B1','$BQ')")" "2"
# P2 卡控：仅未审批试产版 BOM（无已发布）
BP2=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$P2\",\"trialStage\":\"EVT\"},\"items\":[{\"itemCode\":\"$C2\",\"qty\":1,\"lossRate\":0}]}" \
  $B/api/mrp/boms | J "d['data']['id']")
[ -n "$BP2" ] && ok "P2 未审批试产版 BOM BP2=$BP2" || bad "试产版 BOM 失败"
# P1 试产版 BOM+路线（TRIAL 态供转开自动优选）
BT1=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$P1\",\"trialStage\":\"EVT\"},\"items\":[{\"itemCode\":\"$C1\",\"qty\":1,\"lossRate\":0}]}" \
  $B/api/mrp/boms | J "d['data']['id']")
# PVT 试产版 BOM（qty=9 与量产 qty=1 差异 → PVT diff）
BPQT=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$PQ\",\"trialStage\":\"PVT\"},\"items\":[{\"itemCode\":\"$CQ\",\"qty\":9,\"lossRate\":0}]}" \
  $B/api/mrp/boms | J "d['data']['id']")
# WC/工序/定额（TRI 前缀直插——冒烟惯例）
SQL "
INSERT IGNORE INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, CAL_HOURS, STATUS)
 VALUES ('wc-tri-in','TRIWC-IN','试产冒烟中心','INTERNAL',8,'1');
INSERT IGNORE INTO erp_mrp_operation (ID, OP_CODE, OP_NAME, STATUS)
 VALUES ('op-tri10','TRIOP10','试产冒烟工序10','1'),('op-tri20','TRIOP20','试产冒烟工序20','1');
INSERT IGNORE INTO erp_mrp_op_wc_standard (ID, OP_CODE, WC_CODE, SETUP_HOURS, RUN_HOURS)
 VALUES ('std-tri10','TRIOP10','TRIWC-IN',0.5,1),('std-tri20','TRIOP20','TRIWC-IN',0.5,1);" >/dev/null 2>&1
# 量产路线（P1/PQ/P3 直插 PUBLISHED，两道、标准良率90、非关键——FAI 另由参数关闭）
mkroute() {
  SQL "INSERT IGNORE INTO erp_mrp_routing (ID, ITEM_CODE, ITEM_NAME, VERSION_MAJOR, VERSION_MINOR, STATUS, PUBLISH_BY, PUBLISH_AT)
       VALUES ('rt-$1-p','${2:-X}','试产冒烟路线',1,0,'PUBLISHED','smoke',NOW());
       INSERT IGNORE INTO erp_mrp_routing_op (ID, ROUTING_ID, OP_SEQ, OP_CODE, OP_NAME, WC_CODE, WC_NAME, LEAD_TIME, STANDARD_YIELD, KEY_OP_FLAG)
       VALUES ('rto-$1-10','rt-$1-p',10,'TRIOP10','试产冒烟工序10','TRIWC-IN','试产冒烟中心',0,90,0),
              ('rto-$1-20','rt-$1-p',20,'TRIOP20','试产冒烟工序20','TRIWC-IN','试产冒烟中心',0,90,0);" >/dev/null 2>&1
}
mkroute p1 "$P1"; mkroute pq "$PQ"; mkroute p3 "$P3"; mkroute p2 "$P2"
# P1 试产路线（TRIAL 态）→ API 建 + submit-trial + 签
RT1=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"itemCode\":\"$P1\",\"routeType\":\"NORMAL\",\"trialStage\":\"EVT\"},\"ops\":[{\"opCode\":\"TRIOP10\",\"wcCode\":\"TRIWC-IN\",\"leadTime\":0,\"standardYield\":90,\"keyOpFlag\":\"0\"},{\"opCode\":\"TRIOP20\",\"wcCode\":\"TRIWC-IN\",\"leadTime\":0,\"standardYield\":90,\"keyOpFlag\":\"0\"}]}" \
  $B/api/mrp/routings | J "d['data']['id']")
[ -n "$RT1" ] && ok "P1 试产路线草稿 RT1=$RT1" || bad "试产路线失败"
# 库存：子件（齐套+倒冲）、样品备料双批次、挪用单批次
SQL "INSERT IGNORE INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, AVAILABLE_QTY)
     VALUES ('st-tri-c1','WH-MAIN','$C1','试产冒烟子件',NULL,9999,9999),
            ('st-tri-cq','WH-MAIN','$CQ','试产冒烟PVT子件',NULL,9999,9999),
            ('st-tri-pb1','WH-MAIN','$PB','试产冒烟样品备料','SAMPLE-TRI',10,10),
            ('st-tri-pb0','WH-MAIN','$PB','试产冒烟样品备料','MASS-TRI',20,20),
            ('st-tri-pm0','WH-MAIN','$PM','试产冒烟挪用备料','MASS-TRIM',20,20);
     UPDATE erp_inv_stock SET CREATE_DATE='2026-01-01 08:00:00' WHERE ID='st-tri-pb0';
     UPDATE erp_inv_stock SET CREATE_DATE='2026-06-01 08:00:00' WHERE ID='st-tri-pb1';
     INSERT IGNORE INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, TRIAL_FLAG, STATUS, CREATE_BY)
     VALUES ('bt-tri-mass','MASS-TRI','$PB','试产冒烟样品备料','0','1','smoke'),
            ('bt-tri-sample','SAMPLE-TRI','$PB','试产冒烟样品备料','1','1','smoke'),
            ('bt-tri-pm','MASS-TRIM','$PM','试产冒烟挪用备料','0','1','smoke');" >/dev/null 2>&1
NEAR "子件库存 9999" "$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-tri-c1'")" "9999"
# 注：FAI/IPQC 开关为进程内缓存（SysParamServiceImpl），SQL 直改不生效——
# 冒烟改为报工后模拟首件检验合格放行（置批 RELEASED；完整检验链由单测与段 13/14 覆盖）

echo "== 5. 申请建档校验（FR-4.5-8-1） =="
BADS=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"XXX\",\"productCode\":\"$P1\",\"qty\":5,\"goal\":\"缺阶段\"}" $B/api/mrp/trial/apply/create)
EQ "缺阶段 422" "$BADS" "422"
BADG=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"EVT\",\"productCode\":\"$P1\",\"qty\":5,\"goal\":\"  \"}" $B/api/mrp/trial/apply/create)
EQ "缺验证目标 422" "$BADG" "422"
BADQ=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"EVT\",\"productCode\":\"$P1\",\"qty\":0,\"goal\":\"数量零\"}" $B/api/mrp/trial/apply/create)
EQ "数量非正 422" "$BADQ" "422"
BADP=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"EVT\",\"productCode\":\"$C1\",\"qty\":5,\"goal\":\"非自制件\"}" $B/api/mrp/trial/apply/create)
EQ "非 MAKE 422" "$BADP" "422"

echo "== 6. 试产版审批 + 申请审批 → 转开 TP- 编码 =="
# 试产 BOM 特别审批（TENG 发起 → TPRM 签）
BT1R=$(BIZ -X POST -H "$(A $TENG)" $B/api/mrp/boms/$BT1/submit-trial)
EQ "试产 BOM 提交特别审批 200" "$BT1R" "200"
T1=$(C -H "$(A $TPRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='TrialBom' and t.get('bizId')=='$BT1'][0]" 2>/dev/null)
[ -n "$T1" ] && ok "TrialBom 待办 T1=$T1" || bad "无 TrialBom 待办"
EQ "TrialBom 签署 200" "$(BIZ -X POST -H "$(A $TPRM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$T1\",\"opinion\":\"试产BOM同意\"}" $B/api/qms/approvals/pass)" "200"
# 试产路线特别审批
RTR=$(BIZ -X POST -H "$(A $TENG)" $B/api/mrp/routings/$RT1/submit-trial)
EQ "试产路线提交特别审批 200" "$RTR" "200"
T2=$(C -H "$(A $TPRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='TrialRoute' and t.get('bizId')=='$RT1'][0]" 2>/dev/null)
[ -n "$T2" ] && ok "TrialRoute 待办 T2=$T2" || bad "无 TrialRoute 待办"
EQ "TrialRoute 签署 200" "$(BIZ -X POST -H "$(A $TPRM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$T2\",\"opinion\":\"试产路线同意\"}" $B/api/qms/approvals/pass)" "200"
EQ "试产 BOM 入 TRIAL 态" "$(SQL "SELECT STATUS FROM erp_mrp_bom WHERE ID='$BT1'")" "TRIAL"
EQ "试产路线入 TRIAL 态" "$(SQL "SELECT STATUS FROM erp_mrp_routing WHERE ID='$RT1'")" "TRIAL"
# PVT 试产版 BOM 入 TRIAL（段 17 diff 触发前提——resolveBom 只优选 TRIAL 态）
BPQTR=$(BIZ -X POST -H "$(A $TENG)" $B/api/mrp/boms/$BPQT/submit-trial)
EQ "PVT 试产版提交特别审批 200" "$BPQTR" "200"
TBQ=$(C -H "$(A $TPRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='TrialBom' and t.get('bizId')=='$BPQT'][0]" 2>/dev/null)
[ -n "$TBQ" ] && ok "PVT TrialBom 待办 TBQ=$TBQ" || bad "无 PVT TrialBom 待办"
EQ "PVT 试产版签署 200" "$(BIZ -X POST -H "$(A $TPRM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TBQ\",\"opinion\":\"PVT试产BOM同意\"}" $B/api/qms/approvals/pass)" "200"
EQ "PVT 试产版入 TRIAL 态" "$(SQL "SELECT STATUS FROM erp_mrp_bom WHERE ID='$BPQT'")" "TRIAL"
# P1 EVT 申请 → 审批 → 转开（TRIAL 版自动优选，无需确认）
TA=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"EVT\",\"productCode\":\"$P1\",\"qty\":20,\"goal\":\"冒烟验证散热\"}" \
  $B/api/mrp/trial/apply/create | J "d['data']['trialNo']")
[ -n "$TA" ] && ok "申请建档 $TA" || bad "建档失败"
EQ "申请提交 200" "$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialNo\":\"$TA\"}" $B/api/mrp/trial/apply/submit)" "200"
TT=$(C -H "$(A $TPRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='TrialApply' and t.get('bizId')=='$TA'][0]" 2>/dev/null)
EQ "TrialApply 签署 200" "$(BIZ -X POST -H "$(A $TPRM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TT\",\"opinion\":\"同意试产\"}" $B/api/qms/approvals/pass)" "200"
EQ "申请 APPROVED" "$(SQL "SELECT STATUS FROM erp_mrp_trial_apply WHERE TRIAL_NO='$TA'")" "APPROVED"
CONV=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialNo\":\"$TA\",\"ackFormalBom\":false}" $B/api/mrp/trial/convert)
TP1ID=$(echo "$CONV" | J "d['data']['mo']['id']")
TP1NO=$(echo "$CONV" | J "d['data']['mo']['moNo']")
[ -n "$TP1ID" ] && ok "转开成功 $TP1NO（TRIAL 版免确认）" || bad "转开失败：$(echo $CONV | head -c 200)"
case "$TP1NO" in TP-*) ok "TP- 编码分池 $TP1NO";; *) bad "编码非 TP-：$TP1NO";; esac
EQ "MO_TYPE=TRIAL" "$(SQL "SELECT MO_TYPE FROM erp_mrp_mo WHERE ID='$TP1ID'")" "TRIAL"
EQ "阶段带出 EVT" "$(SQL "SELECT TRIAL_STAGE FROM erp_mrp_mo WHERE ID='$TP1ID'")" "EVT"
EQ "申请 CONVERTED 回写" "$(SQL "SELECT STATUS FROM erp_mrp_trial_apply WHERE TRIAL_NO='$TA'")" "CONVERTED"
EQ "BOM 快照行 1（BT1 单子件）" "$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_bom WHERE MO_ID='$TP1ID'")" "1"
EQ "工序快照行 2" "$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_op WHERE MO_ID='$TP1ID'")" "2"
CONVD=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialNo\":\"$TA\",\"ackFormalBom\":true}" $B/api/mrp/trial/convert)
EQ "重复转开 422（1:1）" "$CONVD" "422"

echo "== 7. 建单卡控（C-4.5-14 / 三池在途 / 阶段闸口） =="
# ① P2：未审批试产版 BOM 阻断
TA2=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"EVT\",\"productCode\":\"$P2\",\"qty\":5,\"goal\":\"卡控验证\"}" \
  $B/api/mrp/trial/apply/create | J "d['data']['trialNo']")
C -H "$(A $TPLAN)" -X POST -H 'Content-Type: application/json' -d "{\"trialNo\":\"$TA2\"}" $B/api/mrp/trial/apply/submit >/dev/null
T2b=$(C -H "$(A $TPRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='TrialApply' and t.get('bizId')=='$TA2'][0]" 2>/dev/null)
C -H "$(A $TPRM)" -X POST -H 'Content-Type: application/json' -d "{\"taskId\":\"$T2b\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass >/dev/null
B2MSG=$(MSG -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialNo\":\"$TA2\",\"ackFormalBom\":true}" $B/api/mrp/trial/convert)
CONTAINS "未审批试产 BOM 阻断（C-4.5-14）" "$B2MSG" "试产 BOM 未审批"
EQ "卡控失败零工单" "$(SQL "SELECT COUNT(*) FROM erp_mrp_mo WHERE SRC_NO='$TA2'")" "0"
# ② P3：正式 BOM 确认提示 → ack 放行
B3=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$P3\"},\"items\":[{\"itemCode\":\"$C3\",\"qty\":1,\"lossRate\":0}]}" \
  $B/api/mrp/boms | J "d['data']['id']")
SQL "UPDATE erp_mrp_bom SET STATUS='PUBLISHED', PUBLISH_BY='smoke', PUBLISH_AT=NOW() WHERE ID='$B3';" >/dev/null
TA3=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"EVT\",\"productCode\":\"$P3\",\"qty\":5,\"goal\":\"正式确认\"}" \
  $B/api/mrp/trial/apply/create | J "d['data']['trialNo']")
C -H "$(A $TPLAN)" -X POST -H 'Content-Type: application/json' -d "{\"trialNo\":\"$TA3\"}" $B/api/mrp/trial/apply/submit >/dev/null
T3=$(C -H "$(A $TPRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='TrialApply' and t.get('bizId')=='$TA3'][0]" 2>/dev/null)
C -H "$(A $TPRM)" -X POST -H 'Content-Type: application/json' -d "{\"taskId\":\"$T3\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass >/dev/null
B3MSG=$(MSG -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialNo\":\"$TA3\",\"ackFormalBom\":false}" $B/api/mrp/trial/convert)
CONTAINS "正式 BOM 确认提示" "$B3MSG" "确认继续"
TP3NO=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialNo\":\"$TA3\",\"ackFormalBom\":true}" $B/api/mrp/trial/convert | J "d['data']['mo']['moNo']")
case "$TP3NO" in TP-*) ok "ack 后正式 BOM 放行 $TP3NO";; *) bad "ack 转开失败：$TP3NO";; esac
# ③ 同产品同阶段第二张 → 在途阻断；DVT 未评审 → 阶段闸口
TA4=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"EVT\",\"productCode\":\"$P1\",\"qty\":5,\"goal\":\"在途隔离\"}" \
  $B/api/mrp/trial/apply/create | J "d['data']['trialNo']")
C -H "$(A $TPLAN)" -X POST -H 'Content-Type: application/json' -d "{\"trialNo\":\"$TA4\"}" $B/api/mrp/trial/apply/submit >/dev/null
T4=$(C -H "$(A $TPRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='TrialApply' and t.get('bizId')=='$TA4'][0]" 2>/dev/null)
C -H "$(A $TPRM)" -X POST -H 'Content-Type: application/json' -d "{\"taskId\":\"$T4\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass >/dev/null
INFL=$(MSG -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialNo\":\"$TA4\",\"ackFormalBom\":true}" $B/api/mrp/trial/convert)
CONTAINS "同阶段在途阻断（三池隔离）" "$INFL" "在途"
DVTA=$(MSG -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"DVT\",\"productCode\":\"$P1\",\"qty\":5,\"goal\":\"DVT 闸口\"}" $B/api/mrp/trial/apply/create)
CONTAINS "阶段闸口（EVT 评审未过）" "$DVTA" "阶段闸口"

echo "== 8. MRP/看板/转采购排除（试产排除、RW 并入——D6 核对结论） =="
SQL "INSERT IGNORE INTO erp_mrp_mo (ID, MO_NO, PRODUCT_CODE, PRODUCT_NAME, MO_TYPE, STATUS, QTY, PRIORITY, DEL_FLAG, VER_NO)
     VALUES ('mo-rw-tri','RW-TRI-001','$P1','试产冒烟产品','RW','RELEASED',10,5,'0',0);
     INSERT IGNORE INTO erp_mrp_mo_shortage (ID, MO_ID, ITEM_CODE, ITEM_NAME, REQ_QTY, AVAIL_QTY, SHORT_QTY, RESOLVED_FLAG, DEL_FLAG, VER_NO)
     VALUES ('sh-tri-mx','$TP1ID','$MX','试产冒烟MRP试产缺料',10,0,10,'0','0',0),
            ('sh-rw-my','mo-rw-tri','$MY','试产冒烟MRP返工缺料',10,0,10,'0','0',0);" >/dev/null 2>&1
EQ "缺料行双插" "$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_shortage WHERE ID IN ('sh-tri-mx','sh-rw-my')")" "2"
AGG_MX=$(C -H "$(A $TPLAN)" "$B/api/mrp/kitting/shortages?itemCode=$MX" | J "len(d['data'])")
EQ "看板排除试产缺料（MX 0 行）" "${AGG_MX:-x}" "0"
AGG_MY=$(C -H "$(A $TPLAN)" "$B/api/mrp/kitting/shortages?itemCode=$MY" | J "len(d['data'])")
EQ "看板含 RW 缺料（MY 1 行）" "${AGG_MY:-x}" "1"
FS_MX=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"shortageIds\":[\"sh-tri-mx\"]}" $B/api/mrp/suggestions/from-shortage)
EQ "试产缺料转采购 422" "$FS_MX" "422"
FS_MY=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"shortageIds\":[\"sh-rw-my\"]}" $B/api/mrp/suggestions/from-shortage)
EQ "RW 缺料转采购 200（保留）" "$FS_MY" "200"
MRPR=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d '{"scopeType":"FULL"}' $B/api/mrp/runs)
EQ "MRP 运行 200" "$MRPR" "200"
NEAR "MRP 无试产物料建议（MX）" "$(SQL "SELECT COUNT(*) FROM erp_mrp_suggestion WHERE ITEM_CODE='$MX' AND STATUS IN ('PENDING','CONFIRMED')")" "0"
MYN=$(SQL "SELECT COUNT(*) FROM erp_mrp_suggestion WHERE ITEM_CODE='$MY' AND STATUS IN ('PENDING','CONFIRMED')")
if python3 -c "import sys; sys.exit(0 if int('$MYN')>=1 else 1)" 2>/dev/null; then ok "MRP 含 RW 物料建议（MY=$MYN≥1）"; else bad "MRP 缺 RW 建议 MY=$MYN"; fi

echo "== 9. 试产备料三挂点（样品优先/挪用双人/打标） =="
# 9a 样品批次优先（TRIAL_FLAG=1 前置于更早的量产批次）
SI=$(C -H "$(A $TWH)" -H 'Content-Type: application/json' \
  -d "{\"issueType\":\"OWN\",\"workOrderNo\":\"$TP1NO\",\"lines\":[{\"itemCode\":\"$PB\",\"qty\":5}]}" \
  $B/api/inv/issues)
SI_BATCH=$(echo "$SI" | J "d['data']['lines'][0]['batchNo']")
EQ "样品批次优先分配" "$SI_BATCH" "SAMPLE-TRI"
SIID=$(echo "$SI" | J "d['data']['issue']['id']")
EQ "样品批次领料过账 200" "$(BIZ -X POST -H "$(A $TWH)" $B/api/inv/issues/$SIID/postings)" "200"
EQ "过账打标（SAMPLE 批次置1）" "$(SQL "SELECT TRIAL_FLAG FROM erp_inv_batch WHERE BATCH_NO='SAMPLE-TRI'")" "1"
# 9b 挪用量产批次 → 双人确认
BI=$(C -H "$(A $TWH)" -H 'Content-Type: application/json' \
  -d "{\"issueType\":\"OWN\",\"workOrderNo\":\"$TP1NO\",\"lines\":[{\"itemCode\":\"$PM\",\"qty\":6}]}" \
  $B/api/inv/issues)
BIID=$(echo "$BI" | J "d['data']['issue']['id']")
BI_NO=$(echo "$BI" | J "d['data']['issue']['issueNo']")
BORROW_MSG=$(MSG -X POST -H "$(A $TWH)" $B/api/inv/issues/$BIID/postings)
CONTAINS "挪用双人确认阻断" "$BORROW_MSG" "双人确认"
EQ "未确认批次未打标" "$(SQL "SELECT TRIAL_FLAG FROM erp_inv_batch WHERE BATCH_NO='MASS-TRIM'")" "0"
EQ "挪用发起（TrialBorrow）" "$(BIZ -X POST -H "$(A $TPLAN)" $B/api/inv/issues/$BIID/borrow-approval)" "200"
TB=$(C -H "$(A $TPRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='TrialBorrow' and t.get('bizId')=='$BI_NO'][0]" 2>/dev/null)
[ -n "$TB" ] && ok "TrialBorrow 待办 TB=$TB" || bad "无 TrialBorrow 待办"
EQ "挪用签署（工艺主管）200" "$(BIZ -X POST -H "$(A $TPRM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TB\",\"opinion\":\"同意挪用\"}" $B/api/qms/approvals/pass)" "200"
EQ "审批后过账 200" "$(BIZ -X POST -H "$(A $TWH)" $B/api/inv/issues/$BIID/postings)" "200"
EQ "过账打标（挪用批次置1）" "$(SQL "SELECT TRIAL_FLAG FROM erp_inv_batch WHERE BATCH_NO='MASS-TRIM'")" "1"
NEAR "挪用库存实扣（20−6=14）" "$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-tri-pm0'")" "14"

echo "== 10. 派工分池 + 报工（5.5-5.7 复用） =="
SQL "UPDATE erp_mrp_mo SET STATUS='RELEASED' WHERE ID='$TP1ID';" >/dev/null
PEND_E=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/pending?moType=TRIAL" | J "1 if any(r.get('moNo')=='$TP1NO' for r in d['data']) else 0")
EQ "待派工 TRIAL 池含本单" "${PEND_E:-x}" "1"
PEND_N=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/pending?moType=NORMAL" | J "1 if any(r.get('moNo')=='$TP1NO' for r in d['data']) else 0")
EQ "待派工 NORMAL 池不含试产" "${PEND_N:-x}" "0"
DROWS=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"moId\":\"$TP1ID\",\"opSeq\":10,\"wcCode\":null},{\"moId\":\"$TP1ID\",\"opSeq\":20,\"wcCode\":null}]}" \
  $B/api/mrp/dispatch/generate | J "len(d['data'])")
EQ "派工双工序生成" "${DROWS:-x}" "2"
for D in $(SQL "SELECT ID FROM erp_mrp_dispatch WHERE MO_ID='$TP1ID'"); do
  C -H "$(A $TFLO)" -X POST $B/api/mrp/dispatch/confirm/$D >/dev/null
done
EQ "派工全确认 RELEASED" "$(SQL "SELECT COUNT(*) FROM erp_mrp_dispatch WHERE MO_ID='$TP1ID' AND STATUS='RELEASED'")" "2"
# 报工：op10 good20、op20 good18 ng2（良率 90=标准90 不冻结）+ 工时 setup/run/machine
for OP in 10 20; do
  DP=$(SQL "SELECT ID FROM erp_mrp_dispatch WHERE MO_ID='$TP1ID' AND OP_SEQ=$OP")
  RID=$(C -H "$(A $TFLO)" -H 'Content-Type: application/json' -d "{\"dispatchId\":\"$DP\"}" $B/api/mrp/reporting/open | J "d['data']['id']")
  C -H "$(A $TFLO)" -X POST -H 'Content-Type: application/json' \
    -d "{\"rptId\":\"$RID\",\"setupHours\":0.5,\"runHours\":1,\"machineHours\":2}" $B/api/mrp/reporting/hours >/dev/null
  if [ "$OP" = "10" ]; then G=20; NG=0; else G=18; NG=2; fi
  C -H "$(A $TFLO)" -X POST -H 'Content-Type: application/json' \
    -d "{\"rptId\":\"$RID\",\"goodQty\":$G,\"ngQty\":$NG,\"ngDispose\":\"SCRAP\",\"overprodAck\":false}" \
    $B/api/mrp/reporting/output >/dev/null
  OB=$(BIZ -X POST -H "$(A $TFLO)" $B/api/mrp/reporting/confirm/$RID)
  [ "$OB" = "200" ] && ok "报工 op$OP 确认 200" || bad "报工 op$OP 确认 $OB"
done
EQ "无良率冻结" "$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_freeze WHERE MO_ID='$TP1ID' AND ACTIVE_FLAG='1'")" "0"
# 模拟首件检验合格放行（FAI 批创建不受开关控制，SQL 改开关又不触发缓存——直接置批 RELEASED）
SQL "UPDATE erp_qms_inspection_lot SET STATUS='RELEASED' WHERE REF_TYPE='WORKORDER' AND REF_ID LIKE CONCAT('$TP1ID','#%') AND STATUS NOT IN ('RELEASED','CANCELLED','SKIPPED');" >/dev/null
FAIN=$(SQL "SELECT COUNT(*) FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER' AND REF_ID LIKE CONCAT('$TP1ID','#%') AND STATUS='RELEASED'")
if python3 -c "import sys; sys.exit(0 if int('$FAIN')>=1 else 1)" 2>/dev/null; then ok "首件检验批已放行（$FAIN 批）"; else bad "无已放行检验批"; fi
# 采集数据两工序（结案前置 + 总结工时口径）
EQ "采集 op10 保存 200" "$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$TP1ID\",\"opSeq\":10,\"actualHours\":1.5,\"defectModes\":\"散热不良\"}" $B/api/mrp/trial/data/save)" "200"
EQ "采集 op20 保存 200" "$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$TP1ID\",\"opSeq\":20,\"actualHours\":1.5}" $B/api/mrp/trial/data/save)" "200"
EQ "采集行 2" "$(SQL "SELECT COUNT(*) FROM erp_mrp_trial_data WHERE MO_ID='$TP1ID'")" "2"

echo "== 11. 总结指标自动算 + 跨部门会签 + DVT 闸口放行 =="
SUM=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$TP1ID\",\"issues\":\"散热不良 2 台；建议散热片变更\",\"yieldPct\":1,\"hoursAchievePct\":999}" \
  $B/api/mrp/trial/summary/save)
SUMNO=$(echo "$SUM" | J "d['data']['summaryNo']")
YLD=$(echo "$SUM" | J "d['data']['yieldPct']")
HRS=$(echo "$SUM" | J "d['data']['hoursAchievePct']")
[ -n "$SUMNO" ] && ok "总结建档 $SUMNO" || bad "总结建档失败"
NEAR "良率服务端算 90（客户端伪造 1 无效）" "$YLD" "90"
NEAR "工时达成服务端算 100（3.0/3.0）" "$HRS" "100"
EQ "总结提交 200" "$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"summaryNo\":\"$SUMNO\"}" $B/api/mrp/trial/summary/submit)" "200"
NREV=$(SQL "SELECT COUNT(*) FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='TrialReview' AND a.BIZ_ID='$SUMNO' AND t.STATUS='ACTIVE'")
EQ "三并行评审节点" "$NREV" "3"
for R in "ROLE_PROCESS_MGR:$TPRM" "ROLE_QUALITY_MGR:$TQ" "ROLE_PLAN_MGR:$TPLM"; do
  ROLE="${R%%:*}"; TOK="${R##*:}"
  RTASK=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='TrialReview' AND a.BIZ_ID='$SUMNO' AND t.STATUS='ACTIVE' AND t.ROLE_REQUIRED='$ROLE' LIMIT 1")
  EQ "$ROLE 签署 200" "$(BIZ -X POST -H "$(A $TOK)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$RTASK\",\"opinion\":\"会签同意\"}" $B/api/qms/approvals/pass)" "200"
done
EQ "总结 APPROVED" "$(SQL "SELECT STATUS FROM erp_mrp_trial_summary WHERE SUMMARY_NO='$SUMNO'")" "APPROVED"
# 阶段闸口放行：EVT 评审过后 DVT 申请建档成功
DVTA2=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"DVT\",\"productCode\":\"$P1\",\"qty\":5,\"goal\":\"DVT 放行\"}" $B/api/mrp/trial/apply/create)
EQ "评审过后 DVT 申请放行 200" "$DVTA2" "200"

echo "== 12. 完工入 QC 不入 AVAILABLE + 归集 + 阶段推导 =="
CMP=$(BIZ -X POST -H "$(A $TPLAN)" $B/api/mrp/reporting/complete/$TP1ID)
EQ "完工接管 200" "$CMP" "200"
NEAR "样品仓 QC=18" "$(SQL "SELECT QC_QTY FROM erp_inv_stock WHERE ITEM_CODE='$P1' AND WAREHOUSE_CODE='WH-SAMPLE'")" "18"
NEAR "AVAILABLE=0（不产生可销售库存）" "$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE='$P1' AND WAREHOUSE_CODE='WH-SAMPLE'")" "0"
EQ "WIP_IN 流水 1 行" "$(SQL "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='TRIAL_STOCK_IN'")" "1"
LOTN=$(SQL "SELECT COUNT(*) FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER' AND REF_ID='$TP1ID#TRIAL'")
EQ "OQC 批 refId=#TRIAL" "$LOTN" "1"
EQ "归集三行（料工费）" "$(SQL "SELECT COUNT(DISTINCT COST_TYPE) FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE IN ('MATERIAL','LABOR','OVERHEAD')")" "3"
NEAR "材料归集 = 倒冲20×子件100 = 2000" "$(SQL "SELECT AMOUNT FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE='MATERIAL'")" "2000"
NEAR "工费归集 = 3.0h×60 = 180" "$(SQL "SELECT AMOUNT FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE='LABOR'")" "180"
NEAR "制费归集 = 4h×100 = 400" "$(SQL "SELECT AMOUNT FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE='OVERHEAD'")" "400"
EQ "倒冲补投 BFLUSH 20" "$(SQL "SELECT IFNULL(SUM(QTY),0) FROM erp_mrp_feed WHERE MO_ID='$TP1ID' AND FLOW_TYPE='BFLUSH'")" "20.0000"
EQ "阶段推导 PENDING_INSP" "$(C -H "$(A $TPLAN)" $B/api/mrp/trial/phase/$TP1ID | J "d['data']")" "PENDING_INSP"

echo "== 13. 未放行入可用阻断（C-4.5-14 后半，经调拨入库段过账） =="
TBATCH=$(SQL "SELECT BATCH_NO FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='TRIAL_STOCK_IN' LIMIT 1")
SQL "INSERT IGNORE INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, AVAILABLE_QTY)
     VALUES ('st-tri-twh','WH-MAIN','$P1','试产冒烟产品','$TBATCH',5,5);" >/dev/null
TR=$(C -H "$(A $TWH)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"outWhCode\":\"WH-MAIN\",\"inWhCode\":\"WH-02\"},\"lines\":[{\"itemCode\":\"$P1\",\"batchNo\":\"$TBATCH\",\"qty\":3,\"internalPrice\":10}]}" \
  $B/api/inv/transfers)
TRID=$(echo "$TR" | J "d['data']['order']['id']")
[ -n "$TRID" ] && ok "调拨单创建 TRID=$TRID" || bad "调拨创建失败：$(echo $TR | head -c 200)"
EQ "调拨出库段 200" "$(BIZ -X POST -H "$(A $TWH)" $B/api/inv/transfers/$TRID/post-out)" "200"
TRIN_MSG=$(MSG -X POST -H "$(A $TWH)" $B/api/inv/transfers/$TRID/post-in)
CONTAINS "未放行批次禁入可用（C-4.5-14）" "$TRIN_MSG" "未放行"
NEAR "出库段已扣（5−3=2）" "$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-tri-twh'")" "2"

echo "== 14. 放行平移 + 冲回分录 + 幂等 + 财务确认 =="
LID=$(SQL "SELECT ID FROM erp_qms_inspection_lot WHERE REF_ID='$TP1ID#TRIAL'")
SQL "UPDATE erp_qms_inspection_lot SET RESULT='PASS', STANDARD_ID='std-smoke' WHERE ID='$LID';" >/dev/null
EQ "确认放行 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/qms/lots/$LID/release)" "200"
NEAR "平移 QC 18→0" "$(SQL "SELECT QC_QTY FROM erp_inv_stock WHERE ITEM_CODE='$P1' AND WAREHOUSE_CODE='WH-SAMPLE'")" "0"
NEAR "平移 AVAILABLE 0→18" "$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE='$P1' AND WAREHOUSE_CODE='WH-SAMPLE'")" "18"
NEAR "QTY 守恒 18" "$(SQL "SELECT QTY FROM erp_inv_stock WHERE ITEM_CODE='$P1' AND WAREHOUSE_CODE='WH-SAMPLE'")" "18"
NEAR "REVERSAL 分录 = 18×50 = 900" "$(SQL "SELECT AMOUNT FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE='REVERSAL'")" "900"
EQ "分录 PENDING_FINANCE" "$(SQL "SELECT FIN_STATUS FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE='REVERSAL'")" "PENDING_FINANCE"
EQ "借贷方向留痕" "$(SQL "SELECT DR_CR FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE='REVERSAL'")" "DR存货/CR研发费用"
# 幂等：二次放行 → 422 且无重复分录无二次平移
REL2=$(BIZ -X POST -H "$(A $TADM)" $B/api/qms/lots/$LID/release)
EQ "二次放行 422（放行不可撤回）" "$REL2" "422"
EQ "无重复分录（仍 1 条）" "$(SQL "SELECT COUNT(*) FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE='REVERSAL'")" "1"
NEAR "无二次平移（QC 仍 0）" "$(SQL "SELECT QC_QTY FROM erp_inv_stock WHERE ITEM_CODE='$P1' AND WAREHOUSE_CODE='WH-SAMPLE'")" "0"
# 财务确认：工艺员签 → 403；财务签 → CONFIRMED
RCOST=$(SQL "SELECT ENTRY_NO FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE='REVERSAL'")
RID2=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='TrialCostReverse' AND BIZ_ID=(SELECT ID FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE='REVERSAL')")
FTASK=$(SQL "SELECT t.ID FROM erp_sys_approval_task t WHERE t.APPR_ID='$RID2' AND t.STATUS='ACTIVE'")
EQ "工艺员签财务确认 403（HTTP 层拦截）" "$(HTTP -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$FTASK\",\"opinion\":\"越权尝试\"}" $B/api/qms/approvals/pass)" "403"
EQ "财务主管签 200" "$(BIZ -X POST -H "$(A $TFIN)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$FTASK\",\"opinion\":\"确认冲回\"}" $B/api/qms/approvals/pass)" "200"
EQ "冲回 CONFIRMED" "$(SQL "SELECT FIN_STATUS FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE='REVERSAL'")" "CONFIRMED"
EQ "签署人留痕" "$(SQL "SELECT CONFIRM_BY FROM erp_mrp_trial_cost WHERE MO_ID='$TP1ID' AND COST_TYPE='REVERSAL'")" "user-trifin"
# 放行后调拨入库段放行（唯一合法路径已开）
EQ "放行后调拨入库段 200" "$(BIZ -X POST -H "$(A $TWH)" $B/api/inv/transfers/$TRID/post-in)" "200"
NEAR "入库段入账（AVAILABLE 18+3=21）" "$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE='$P1' AND WAREHOUSE_CODE='WH-02'")" "3"
# 成本汇总：net = 归集 − 已确认冲回
COSTNET=$(C -H "$(A $TPLAN)" $B/api/mrp/trial/cost/$TP1ID | J "float(d['data']['netTotal'])")
COLL=$(C -H "$(A $TPLAN)" $B/api/mrp/trial/cost/$TP1ID | J "float(d['data']['collectedTotal'])")
NEAR "汇总 net = 归集 − 已确认冲回" "$COSTNET" "$(python3 -c "print($COLL-900)")"
EQ "阶段推导（放行+分录+评审过=待关闭）" "$(C -H "$(A $TPLAN)" $B/api/mrp/trial/phase/$TP1ID | J "d['data']")" "PENDING_CLOSE"

echo "== 15. 剩余料 L2 关单（先阻断后确认） =="
REM=$(C -H "$(A $TPLAN)" $B/api/mrp/trial/remainder/$TP1ID | J "len(d['data'])")
if python3 -c "import sys; sys.exit(0 if int('$REM')>=2 else 1)" 2>/dev/null; then ok "剩余料明细 ≥2 项（$REM）"; else bad "剩余料明细 $REM 项（期望≥2）"; fi
CL1_MSG=$(MSG -X POST -H "$(A $TPLAN)" $B/api/mrp/mos/$TP1ID/close)
CONTAINS "剩余料未确认阻断（列批次）" "$CL1_MSG" "确认留痕"
EQ "确认剩余料留痕 200" "$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$TP1ID\",\"note\":\"余量已退库\"}" $B/api/mrp/trial/remainder/ack)" "200"
EQ "关单 200" "$(BIZ -X POST -H "$(A $TPLAN)" $B/api/mrp/mos/$TP1ID/close)" "200"
EQ "关单终态 CLOSED" "$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$TP1ID'")" "CLOSED"
EQ "关闭留痕 closeBy" "$(SQL "SELECT IFNULL(CLOSE_BY,'') FROM erp_mrp_mo WHERE ID='$TP1ID'")" "user-traplan"

echo "== 16. 转量产复制 + 未评审阻断发布 =="
PROM=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$TP1ID\"}" $B/api/mrp/trial/promote)
PBOM=$(echo "$PROM" | J "d['data']['bomId']")
PRT=$(echo "$PROM" | J "d['data']['routingId']")
PBV=$(echo "$PROM" | J "d['data']['bomVersion']")
[ -n "$PBOM" ] && ok "转量产复制 BOM V$PBV / 路线成功" || bad "promote 失败：$(echo $PROM | head -c 200)"
EQ "量产草稿 SRC_TRIAL_NO 留痕" "$(SQL "SELECT SRC_TRIAL_NO FROM erp_mrp_bom WHERE ID='$PBOM'")" "$TP1NO"
EQ "量产草稿 TRIAL_STAGE 清空" "$(SQL "SELECT IFNULL(TRIAL_STAGE,'NULL') FROM erp_mrp_bom WHERE ID='$PBOM'")" "NULL"
EQ "量产草稿状态 DRAFT" "$(SQL "SELECT STATUS FROM erp_mrp_bom WHERE ID='$PBOM'")" "DRAFT"
EQ "原试产版零改动仍 TRIAL" "$(SQL "SELECT STATUS FROM erp_mrp_bom WHERE ID='$BT1'")" "TRIAL"
# 评审回退 → 发布阻断 → 恢复后发布回归
SQL "UPDATE erp_mrp_trial_summary SET STATUS='REJECTED' WHERE SUMMARY_NO='$SUMNO';" >/dev/null
PB_MSG=$(MSG -X POST -H "$(A $TENG)" $B/api/mrp/boms/$PBOM/submit)
CONTAINS "评审未通过阻断发布" "$PB_MSG" "评审未通过"
PR_MSG=$(MSG -X POST -H "$(A $TENG)" $B/api/mrp/routings/$PRT/submit)
CONTAINS "路线未评审阻断发布" "$PR_MSG" "评审未通过"
SQL "UPDATE erp_mrp_trial_summary SET STATUS='APPROVED' WHERE SUMMARY_NO='$SUMNO';" >/dev/null
EQ "评审恢复后提交发布 200" "$(BIZ -X POST -H "$(A $TENG)" $B/api/mrp/boms/$PBOM/submit)" "200"
PT=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='BomPublish' AND BIZ_ID='$PBOM' AND STATUS='PENDING'")
PFT=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$PT' AND STATUS='ACTIVE' LIMIT 1")
EQ "发布签署 200" "$(BIZ -X POST -H "$(A $TPRM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$PFT\",\"opinion\":\"发布同意\"}" $B/api/qms/approvals/pass)" "200"
EQ "量产发布 PUBLISHED（发布回归）" "$(SQL "SELECT STATUS FROM erp_mrp_bom WHERE ID='$PBOM'")" "PUBLISHED"

echo "== 17. PVT diff 审批两分支 + PVT 良率闸口 =="
PVTA=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"PVT\",\"productCode\":\"$PQ\",\"qty\":100,\"goal\":\"PVT 一致性验证\"}" \
  $B/api/mrp/trial/apply/create | J "d['data']['trialNo']")
C -H "$(A $TPLAN)" -X POST -H 'Content-Type: application/json' -d "{\"trialNo\":\"$PVTA\"}" $B/api/mrp/trial/apply/submit >/dev/null
TPV=$(C -H "$(A $TPRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='TrialApply' and t.get('bizId')=='$PVTA'][0]" 2>/dev/null)
C -H "$(A $TPRM)" -X POST -H 'Content-Type: application/json' -d "{\"taskId\":\"$TPV\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass >/dev/null
# diff 分支1：差异清单发起（PVT 试产版 qty9 vs 量产 qty1）
D1=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialNo\":\"$PVTA\",\"ackFormalBom\":true}" $B/api/mrp/trial/convert)
EQ "PVT diff 发起 pendingDiff" "$(echo "$D1" | J "d['data']['pendingDiff']")" "True"
DIFFN=$(echo "$D1" | J "d['data']['diffCount']")
if python3 -c "import sys; sys.exit(0 if int('$DIFFN')>=1 else 1)" 2>/dev/null; then ok "差异清单 ≥1 项（$DIFFN）"; else bad "diffCount=$DIFFN"; fi
EQ "零工单（diff 未过不建单）" "$(SQL "SELECT COUNT(*) FROM erp_mrp_mo WHERE SRC_NO='$PVTA'")" "0"
# diff 分支2：驳回 → 重新发起
DT=$(C -H "$(A $TPRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='TrialPvtDiff' and t.get('bizId')=='$PVTA'][0]" 2>/dev/null)
EQ "diff 驳回 200" "$(BIZ -X POST -H "$(A $TPRM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$DT\",\"reason\":\"差异未说明清楚\"}" $B/api/qms/approvals/reject)" "200"
D2=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialNo\":\"$PVTA\",\"ackFormalBom\":true}" $B/api/mrp/trial/convert)
EQ "驳回后重新发起 pendingDiff" "$(echo "$D2" | J "d['data']['pendingDiff']")" "True"
# diff 通过 → 建单
DT2=$(C -H "$(A $TPRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='TrialPvtDiff' and t.get('bizId')=='$PVTA'][0]" 2>/dev/null)
EQ "diff 通过 200" "$(BIZ -X POST -H "$(A $TPRM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$DT2\",\"opinion\":\"差异逐项确认通过\"}" $B/api/qms/approvals/pass)" "200"
PVTP2=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"trialNo\":\"$PVTA\",\"ackFormalBom\":true}" $B/api/mrp/trial/convert)
TPVNO=$(echo "$PVTP2" | J "d['data']['mo']['moNo']")
case "$TPVNO" in TP-*) ok "diff 过后转开 $TPVNO（PVT）";; *) bad "PVT 转开失败：$(echo $PVTP2 | head -c 200)";; esac
# PVT 良率闸口：<95 提交评审阻断
SQL "UPDATE erp_mrp_mo SET STATUS='RELEASED' WHERE MO_NO='$TPVNO';" >/dev/null
PVMO=$(SQL "SELECT ID FROM erp_mrp_mo WHERE MO_NO='$TPVNO'")
for OP in 10 20; do
  DPV=$(SQL "SELECT ID FROM erp_mrp_dispatch WHERE MO_ID='$PVMO' AND OP_SEQ=$OP" 2>/dev/null)
  if [ -z "$DPV" ]; then
    DPV=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
      -d "{\"rows\":[{\"moId\":\"$PVMO\",\"opSeq\":$OP,\"wcCode\":null}]}" \
      $B/api/mrp/dispatch/generate | J "d['data'][0]['id']")
    C -H "$(A $TFLO)" -X POST $B/api/mrp/dispatch/confirm/$DPV >/dev/null
  fi
  RIDV=$(C -H "$(A $TFLO)" -H 'Content-Type: application/json' -d "{\"dispatchId\":\"$DPV\"}" $B/api/mrp/reporting/open | J "d['data']['id']")
  if [ "$OP" = "10" ]; then G=90; NG=4; else G=75; NG=4; fi
  C -H "$(A $TFLO)" -X POST -H 'Content-Type: application/json' \
    -d "{\"rptId\":\"$RIDV\",\"goodQty\":$G,\"ngQty\":$NG,\"ngDispose\":\"SCRAP\",\"overprodAck\":false}" \
    $B/api/mrp/reporting/output >/dev/null
  C -H "$(A $TFLO)" -X POST $B/api/mrp/reporting/confirm/$RIDV >/dev/null
done
SUMV=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$PVMO\",\"issues\":\"PVT 良率低于目标\"}" $B/api/mrp/trial/summary/save)
SUMVNO=$(echo "$SUMV" | J "d['data']['summaryNo']")
NEAR "PVT 良率 94.94（<95）" "$(echo "$SUMV" | J "d['data']['yieldPct']")" "94.9367"
YMSG=$(MSG -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"summaryNo\":\"$SUMVNO\"}" $B/api/mrp/trial/summary/submit)
CONTAINS "PVT 良率闸口（<95 阻断）" "$YMSG" "良率未达标"
EQ "被闸口拦下不入 PENDING" "$(SQL "SELECT STATUS FROM erp_mrp_trial_summary WHERE SUMMARY_NO='$SUMVNO'")" "DRAFT"

echo "== 18. 权限矩阵（业务码 403） =="
EQ "操作工建申请 403" "$(BIZ -X POST -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"trialStage\":\"EVT\",\"productCode\":\"$P1\",\"qty\":1,\"goal\":\"越权\"}" $B/api/mrp/trial/apply/create)" "403"
EQ "操作工查列表 403" "$(BIZ -H "$(A $TFLO)" $B/api/mrp/trial/list)" "403"
EQ "操作工查成本 403" "$(BIZ -H "$(A $TFLO)" $B/api/mrp/trial/cost/$TP1ID)" "403"
EQ "操作工转量产 403" "$(BIZ -X POST -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$TP1ID\"}" $B/api/mrp/trial/promote)" "403"
EQ "操作工剩余料确认 403" "$(BIZ -X POST -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$TP1ID\",\"note\":\"x\"}" $B/api/mrp/trial/remainder/ack)" "403"
EQ "计划员提交试产版审批 403（工艺面）" "$(BIZ -X POST -H "$(A $TPLAN)" $B/api/mrp/boms/$BT1/submit-trial)" "403"
EQ "计划员查申请列表 200" "$(BIZ -H "$(A $TPLAN)" $B/api/mrp/trial/list)" "200"
EQ "工艺员查工单列表 200" "$(BIZ -H "$(A $TENG)" $B/api/mrp/trial/list)" "200"
EQ "仓管发起挪用 403（计划面）" "$(BIZ -X POST -H "$(A $TWH)" $B/api/inv/issues/$BIID/borrow-approval)" "403"
EQ "未认证申请建档 401" "$(HTTP -X POST $B/api/mrp/trial/apply/create)" "401"

echo "== 19. 收尾清理 =="
EQ "FAI 开关保持 ON（冒烟不改进程缓存参数）" "$(SQL "SELECT PARAM_VALUE FROM erp_sys_param WHERE PARAM_KEY='FAI_ENFORCE'")" "ON"

echo ""
echo "==================== PASS=$PASS FAIL=$FAIL ===================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
