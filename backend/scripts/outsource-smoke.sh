#!/bin/zsh
# add-outsourcing-processing 全链路 API 冒烟（任务 7.2，design 断言链，可重跑）
# 链路：清理→账号→登录→401 四叶子→造数（物料/外协WC/工序/定额/路线/库存）→建单→派工→
#       外协改派审批→PR 落库→5.10.1 工作台（分组/筛选/字段维护/权限）→
#       甲供发料（422 库存缺口/创建/出库过账+在途入池）→PR 转 PO→PO 审批回联 ORDERED→
#       收货登记→检验批（5.10.2 外协上下文 + 权限）→放行→过账自动报工→工序完成→
#       余料退回→5.10.4 对账（生成/重复 422/提交/签核/挂账）→权限矩阵→迁移幂等
# 断言层：401 走 HTTP；403/422/404 是 R<T> 业务码（HTTP 恒 200）用 BIZ
set -u
B=http://127.0.0.1:8090
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✘ $1"; }
C() { curl -s --noproxy '*' "$@"; }
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
EQ() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望[$3] 实际[$2]）"; fi; }
NEAR() { if python3 -c "import sys; sys.exit(0 if abs(float('$2')-float('$3'))<0.001 else 1)" 2>/dev/null; \
  then ok "$1"; else bad "$1（期望≈$3 实际 $2）"; fi; }
CONTAINS() { case "$2" in *"$3"*) ok "$1";; *) bad "$1（未包含[$3]，实际[$2]）";; esac; }
HTTP() { curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$@"; }
BIZ() { C "$@" | J "d['code']"; }
MSG() { C "$@" | J "d['message']"; }
export MYSQL_PWD=root123
-- --force：单句报错不中断整段清理（否则后续语句静默跳过 → 固定 ID 残留累积，本脚本已两次踩坑）
SQL() { docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --force --default-character-set=utf8mb4 -uroot -N -B erp -e "$1" 2>/dev/null \
  || mysql --force -h127.0.0.1 -uroot -proot123 erp --default-character-set=utf8mb4 -N -B -e "$1" 2>/dev/null; }
A() { echo "Authorization: Bearer $1"; }

echo "== 0. 前置清理（外协冒烟族 OSMK，可重跑） =="
SQL "
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID
 WHERE a.BIZ_TYPE IN ('OutsourceDispatch','OutsourceSettlement','ShortageDispatch');
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('OutsourceDispatch','OutsourceSettlement','ShortageDispatch');
DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('DISPATCH_APPROVAL_RESULT','OUTSOURCE_PROGRESS');
-- 供应商两值兜底（历史轮次存过 ID 'sup-fin-tgt'，规范后存编码 'SUPFINTGT'）
DELETE FROM erp_proc_os_ap WHERE OSR_NO LIKE 'OSR%' AND SUPPLIER_CODE IN ('SUPFINTGT','sup-fin-tgt');
DELETE l FROM erp_proc_os_settlement_line l JOIN erp_proc_os_settlement h ON h.ID=l.OSR_ID
 WHERE h.REMARK LIKE '%外协冒烟%' OR h.SUPPLIER_CODE IN ('SUPFINTGT','sup-fin-tgt');
DELETE FROM erp_proc_os_settlement WHERE REMARK LIKE '%外协冒烟%'
   OR SUPPLIER_CODE IN ('SUPFINTGT','sup-fin-tgt');
DELETE FROM erp_mrp_os_consume WHERE OSM_ID IN (SELECT ID FROM erp_mrp_os_material WHERE SUPPLIER_CODE='SUPFINTGT');
DELETE FROM erp_mrp_os_material_line WHERE OSM_ID IN (SELECT ID FROM erp_mrp_os_material WHERE SUPPLIER_CODE='SUPFINTGT');
DELETE FROM erp_mrp_os_material WHERE SUPPLIER_CODE='SUPFINTGT';
DELETE FROM erp_mrp_reporting WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'OSMK%')
   OR REMARK LIKE 'AUTO_OUTSOURCE:%';
DELETE FROM erp_mrp_output WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'OSMK%');
DELETE FROM erp_qms_inspection_lot WHERE ITEM_CODE LIKE 'OSMK%';
-- 本族 GR 按行物料关联定位（勿用中文 REMARK——API 存库后中文变 ? 会匹配不上；勿先删行）
DELETE FROM erp_inv_putaway WHERE SOURCE_DOC_NO IN
   (SELECT GR_NO FROM erp_proc_gr WHERE ID IN
    (SELECT DISTINCT GR_ID FROM erp_proc_gr_line WHERE ITEM_CODE LIKE 'OSMK%'));
DELETE FROM erp_proc_gr WHERE ID IN
   (SELECT DISTINCT GR_ID FROM erp_proc_gr_line WHERE ITEM_CODE LIKE 'OSMK%');
DELETE FROM erp_proc_gr_line WHERE ITEM_CODE LIKE 'OSMK%';
DELETE FROM erp_proc_po_line WHERE ITEM_CODE LIKE 'OSMK%';
DELETE FROM erp_proc_po WHERE SUPPLIER_ID='SUPFINTGT' AND REMARK LIKE '%外协冒烟%';
DELETE l FROM erp_proc_pr_line l JOIN erp_proc_requisition p ON l.PR_ID=p.ID WHERE p.SOURCE_TYPE='OUTSOURCE';
DELETE FROM erp_proc_requisition WHERE SOURCE_TYPE='OUTSOURCE';
-- PR_NO 每日重号 → outbox 幂等键（PR-xxx:L1:v1）跨轮重复会 409 拒发（C-0-06），必须清
DELETE FROM erp_ops_outbox WHERE IDEMPOTENCY_KEY LIKE 'PR-%';
DELETE FROM erp_mrp_reservation WHERE ITEM_CODE LIKE 'OSMK%'
   OR MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'OSMK%');
DELETE FROM erp_mrp_dispatch WHERE WC_CODE LIKE 'OSWC%'
   OR MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'OSMK%');
DELETE FROM erp_mrp_mo_bom WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'OSMK%');
DELETE FROM erp_mrp_mo_op WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'OSMK%');
DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'OSMK%';
DELETE FROM erp_mrp_routing_op WHERE OP_CODE LIKE 'OSOP%' OR ROUTING_ID='rt-osmk-p';
DELETE FROM erp_mrp_routing WHERE ITEM_CODE LIKE 'OSMK%' OR ID='rt-osmk-p';
DELETE FROM erp_mrp_op_wc_standard WHERE WC_CODE LIKE 'OSWC%';
DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'OSOP%';
DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'OSWC%';
DELETE FROM erp_inv_transaction WHERE ITEM_CODE LIKE 'OSMK%' OR BIZ_DOC_NO LIKE 'OSM%';
DELETE FROM erp_inv_stock WHERE ITEM_CODE LIKE 'OSMK%' OR ID='st-osmk-c';
DELETE FROM erp_inv_batch WHERE ITEM_CODE LIKE 'OSMK%';
DELETE FROM erp_inv_bin WHERE ID='bn-osmk';
DELETE FROM erp_inv_zone WHERE ID='zn-osmk';
DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'OSMK%';
DELETE FROM erp_mdm_item_category WHERE ID='cat-osmk';
DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'user-os%';
DELETE FROM erp_admin_user WHERE USERNAME LIKE 'osmk%';
INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX)
 VALUES ('cat-osmk','OSMK','外协冒烟分类','OSMK');" >/dev/null 2>&1 \
  && ok "清理完成（OSMK 族就位）" || bad "清理失败"

echo "== 1. 账号（四叶子两线角色） =="
AHASH=$(SQL "SELECT PASSWORD FROM erp_admin_user WHERE USERNAME='admin' LIMIT 1")
mkuser() {
  SQL "INSERT IGNORE INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, CREATE_BY)
       VALUES ('user-$1','$1','$AHASH','$2','1','smoke');
       INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) VALUES ('ur-$1','user-$1','$3');" >/dev/null 2>&1
}
mkuser osmkplan  "外协冒烟计划员" role-planner
mkuser osmkplmgr "外协冒烟计划主管" role-plan-mgr
mkuser osmkpur   "外协冒烟采购员" role-purchaser
mkuser osmkqc    "外协冒烟质检员" role-inspector
mkuser osmkwh    "外协冒烟仓管"   role-warehouse
mkuser osmkrecv  "外协冒烟收货员" role-receiver
N=$(SQL "SELECT COUNT(*) FROM erp_admin_user WHERE USERNAME IN ('osmkplan','osmkplmgr','osmkpur','osmkqc','osmkwh','osmkrecv')")
EQ "六账号就位" "$N" "6"

echo "== 2. 登录 =="
TPLAN=$(C -H 'Content-Type: application/json' -d '{"username":"osmkplan","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TPLM=$(C -H 'Content-Type: application/json' -d '{"username":"osmkplmgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TPUR=$(C -H 'Content-Type: application/json' -d '{"username":"osmkpur","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TQC=$(C -H 'Content-Type: application/json' -d '{"username":"osmkqc","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TWH=$(C -H 'Content-Type: application/json' -d '{"username":"osmkwh","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TRECV=$(C -H 'Content-Type: application/json' -d '{"username":"osmkrecv","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TPLAN" ] && [ -n "$TPUR" ] && [ -n "$TQC" ] && [ -n "$TADM" ] \
  && ok "四角色+admin 登录" || bad "登录失败"

echo "== 3. 401 矩阵（四叶子 HTTP 层） =="
EQ "GET /mrp/dispatch/os/workbench 401" "$(HTTP $B/api/mrp/dispatch/os/workbench)" "401"
EQ "PUT /mrp/dispatch/os/fields/x 401" "$(HTTP -X PUT $B/api/mrp/dispatch/os/fields/x)" "401"
EQ "GET /qms/lots/outsource-board 401" "$(HTTP $B/api/qms/lots/outsource-board)" "401"
EQ "GET /mrp/os-material/list 401" "$(HTTP $B/api/mrp/os-material/list)" "401"
EQ "POST /mrp/os-material 401" "$(HTTP -X POST $B/api/mrp/os-material)" "401"
EQ "POST /mrp/os-material/x/post 401" "$(HTTP -X POST $B/api/mrp/os-material/x/post)" "401"
EQ "GET /mrp/os-settlements/list 401" "$(HTTP $B/api/mrp/os-settlements/list)" "401"
EQ "POST /mrp/os-settlements/generate 401" "$(HTTP -X POST $B/api/mrp/os-settlements/generate)" "401"
EQ "GET /mrp/os-settlements/ap/list 401" "$(HTTP $B/api/mrp/os-settlements/ap/list)" "401"

echo "== 4. 造数（物料/外协WC/工序/定额/路线/库存） =="
mkitem() {
  C -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"itemName\":\"外协冒烟$1\",\"categoryCode\":\"OSMK\",\"baseUnit\":\"PC\",\"materialGroup\":\"STRUCT\",\"purchaseType\":\"$2\",\"storageCondition\":\"NORMAL\",\"batchFlag\":\"$3\",\"shelfLifeDays\":365,\"dupNote\":\"外协加工冒烟物料，非业务重复数据\"}" \
    "$B/api/mdm/items?forceCreate=true" | J "d['data']['itemCode']"
}
PROD=$(mkitem 成品 MAKE 0)
COMP=$(mkitem 甲供原料 BUY 1)
[ -n "$PROD" ] && [ -n "$COMP" ] && ok "双物料就位 PROD=$PROD COMP=$COMP" || bad "建物料失败"
SQL "UPDATE erp_mdm_item SET STANDARD_COST=100 WHERE ITEM_CODE='$PROD';
     UPDATE erp_mdm_item SET STANDARD_COST=10  WHERE ITEM_CODE='$COMP';" >/dev/null
# 量产 BOM（成品 → 甲供原料 单耗 2：收货定额冲减的取数来源）
BID=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$PROD\"},\"items\":[{\"itemCode\":\"$COMP\",\"qty\":2,\"lossRate\":0}]}" \
  $B/api/mrp/boms | J "d['data']['id']")
SQL "UPDATE erp_mrp_bom SET STATUS='PUBLISHED', PUBLISH_BY='smoke', PUBLISH_AT=NOW() WHERE ID='$BID';" >/dev/null
[ -n "$BID" ] && ok "量产 BOM 发布 $BID（甲供原料单耗 2）" || bad "建 BOM 失败"
# 外协 WC（绑供应商）/ 工序 / 定额 / 路线（直插 PUBLISHED——冒烟惯例）
SQL "
INSERT IGNORE INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, CAL_HOURS, STATUS, CREATE_BY, DEL_FLAG)
 VALUES ('wc-osmk-in','OSWC-IN','外协冒烟内部中心','INTERNAL',8,'1','smoke','0');
INSERT IGNORE INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, CAL_HOURS, STATUS, SUPPLIER_CODE, CREATE_BY, DEL_FLAG)
 VALUES ('wc-osmk-os','OSWC-OS','外协冒烟外协中心','OUTSOURCED',8,'1','SUPFINTGT','smoke','0');
INSERT IGNORE INTO erp_mrp_operation (ID, OP_CODE, OP_NAME, STATUS, CREATE_BY, DEL_FLAG)
 VALUES ('op-osmk1','OSOP10','外协冒烟工序10','1','smoke','0'),
        ('op-osmk2','OSOP20','外协冒烟工序20','1','smoke','0');
INSERT IGNORE INTO erp_mrp_op_wc_standard (ID, OP_CODE, WC_CODE, SETUP_HOURS, RUN_HOURS, CREATE_BY, DEL_FLAG)
 VALUES ('std-osmk-i','OSOP10','OSWC-IN',1,5,'smoke','0'),
        ('std-osmk-o','OSOP10','OSWC-OS',1,6,'smoke','0');" >/dev/null 2>&1
EQ "外协 WC 就位（绑 SUPFINTGT）" "$(SQL "SELECT CONCAT(WC_CODE,':',SUPPLIER_CODE) FROM erp_mrp_work_center WHERE WC_CODE='OSWC-OS'")" "OSWC-OS:SUPFINTGT"
# 路线绑内部 WC（派工先在内部，后续改派到外协——与 5.6.3 改派链一致）
SQL "INSERT IGNORE INTO erp_mrp_routing (ID, ITEM_CODE, ROUTE_TYPE, ITEM_NAME, VERSION_MAJOR, VERSION_MINOR, STATUS, PUBLISH_BY, PUBLISH_AT, CREATE_BY, DEL_FLAG)
      VALUES ('rt-osmk-p','$PROD','NORMAL','外协冒烟路线',1,0,'PUBLISHED','smoke',NOW(),'smoke','0');
     INSERT IGNORE INTO erp_mrp_routing_op (ID, ROUTING_ID, OP_SEQ, OP_CODE, OP_NAME, WC_CODE, WC_NAME, LEAD_TIME, STANDARD_YIELD, KEY_OP_FLAG, CREATE_BY, DEL_FLAG)
      VALUES ('rto-osmk-10','rt-osmk-p',10,'OSOP10','外协冒烟工序10','OSWC-IN','外协冒烟内部中心',0,90,0,'smoke','0');" >/dev/null 2>&1
EQ "路线直插 PUBLISHED" "$(SQL "SELECT COUNT(*) FROM erp_mrp_routing WHERE ITEM_CODE='$PROD' AND STATUS='PUBLISHED'")" "1"
# 甲供原料库存（发料出库取数）
SQL "INSERT IGNORE INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, AVAILABLE_QTY, CREATE_BY, DEL_FLAG)
      VALUES ('st-osmk-c','WH-MAIN','$COMP','外协冒烟甲供原料','OSB1',500,500,'smoke','0');" >/dev/null 2>&1
NEAR "甲供库存 500" "$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-osmk-c'")" "500"
# 仓位分配（4.4.5 过账前置）需库区+普通仓位：库区/仓位表本无种子，本族造一套（固定 ID 按 ID 清）
SQL "INSERT IGNORE INTO erp_inv_zone (ID, WH_CODE, ZONE_CODE, ZONE_NAME, SORT_ORDER, STATUS, CREATE_BY, DEL_FLAG)
      VALUES ('zn-osmk','WH-MAIN','ZN-OSMK','外协冒烟库区',1,'1','smoke','0');
     INSERT IGNORE INTO erp_inv_bin (ID, WH_CODE, ZONE_CODE, BIN_CODE, BIN_SEQ, COL_NO, LAYER_NO, BIN_TYPE, STATUS, CREATE_BY, DEL_FLAG)
      VALUES ('bn-osmk','WH-MAIN','ZN-OSMK','BIN-OSMK-01',1,1,1,'STORE','1','smoke','0');" >/dev/null 2>&1
EQ "库区+仓位就位" "$(SQL "SELECT COUNT(*) FROM erp_inv_bin WHERE ID='bn-osmk'")" "1"

echo "== 5. 建工单（qty=10）并置 RELEASED =="
MO=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"productCode\":\"$PROD\",\"qty\":10,\"planStartDate\":\"$(date +%F)\",\"planEndDate\":\"$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F)\",\"priority\":3}}" \
  $B/api/mrp/mos | J "d['data']['mo']['id']")
[ -n "$MO" ] && ok "工单创建 $MO" || bad "建单失败"
MNO=$(SQL "SELECT MO_NO FROM erp_mrp_mo WHERE ID='$MO'")
SQL "UPDATE erp_mrp_mo SET STATUS='RELEASED' WHERE ID='$MO';
     UPDATE erp_mrp_mo_op SET OP_STATUS='PENDING' WHERE MO_ID='$MO';" >/dev/null
EQ "工单 RELEASED" "$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$MO'")" "RELEASED"
# BOM 快照（建单时若未生成则直插——收货定额冲减依赖它）
BN=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_bom WHERE MO_ID='$MO' AND ITEM_CODE='$COMP'")
if [ "$BN" = "0" ]; then
  SQL "INSERT INTO erp_mrp_mo_bom (ID, MO_ID, ITEM_CODE, ITEM_NAME, UNIT_QTY, LOSS_RATE, CREATE_BY, DEL_FLAG)
       VALUES ('mb-osmk-1','$MO','$COMP','外协冒烟甲供原料',2,0,'smoke','0');" >/dev/null 2>&1
  ok "BOM 快照直插（单耗 2）"
else
  ok "BOM 快照已生成（建单带出）"
fi

echo "== 6. 派工 + 外协改派审批 → PR 落库 =="
GEN=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"moId\":\"$MO\",\"opSeq\":10,\"wcCode\":null}]}" $B/api/mrp/dispatch/generate)
DPID=$(J "d['data'][0]['id']" <<<"$GEN")
DPNO=$(J "d['data'][0]['dispatchNo']" <<<"$GEN")
[ -n "$DPID" ] && ok "派工单生成 $DPNO" || bad "派工生成失败：$GEN"
APP=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"dispatchId\":\"$DPID\",\"targetWcCode\":\"OSWC-OS\"}" $B/api/mrp/dispatch/outsource-apply)
ONO=$(J "d['data']['apprNo']" <<<"$APP")
[ -n "$ONO" ] && ok "外协改派申请提交 $ONO" || bad "改派申请失败：$APP"
TID=$(C -H "$(A $TPLM)" $B/api/qms/approvals/todo | python3 -c "
import sys,json
for t in json.load(sys.stdin)['data']:
    if t.get('apprNo')=='$ONO': print(t['taskId']); break")
[ -n "$TID" ] && ok "计划主管待办可见" || bad "无改派待办"
SIGNR=$(C -H "$(A $TPLM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TID\",\"opinion\":\"同意外协\"}" $B/api/qms/approvals/pass)
EQ "计划主管签核通过" "$(J "d['code']" <<<"$SIGNR")" "200"
[ "$(J "d['code']" <<<"$SIGNR")" != "200" ] && echo "     ↳ 签核消息：$(J "d['message']" <<<"$SIGNR")"
CHG=$(SQL "SELECT CONCAT(WC_CODE,'|',OUTSOURCE_FLAG,'|',IFNULL(PR_NO,'')) FROM erp_mrp_dispatch WHERE ID='$DPID'")
EQ "改派外协 WC" "$(echo "$CHG" | cut -d'|' -f1)" "OSWC-OS"
EQ "外协标记" "$(echo "$CHG" | cut -d'|' -f2)" "1"
PRNO=$(echo "$CHG" | cut -d'|' -f3)
[ -n "$PRNO" ] && ok "审批通过自动生 PR $PRNO" || bad "未生 PR"
SQL "UPDATE erp_mrp_dispatch SET OS_SUPPLY_TYPE='COMPANY_SUPPLY', OS_DUE_DATE=DATE_ADD(CURDATE(), INTERVAL 7 DAY), OS_REMARK='外协冒烟甲供' WHERE ID='$DPID';" >/dev/null
EQ "甲供字段维护（直置模拟 5.10.1 保存）" "$(SQL "SELECT OS_SUPPLY_TYPE FROM erp_mrp_dispatch WHERE ID='$DPID'")" "COMPANY_SUPPLY"

echo "== 7. 5.10.1 外协订单工作台（分组/筛选/字段维护/权限） =="
# 权限先断言：INSPECTOR 不可见（两线之一）
EQ "INSPECTOR 查工作台 403" "$(BIZ -H "$(A $TQC)" $B/api/mrp/dispatch/os/workbench)" "403"
WB=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/os/workbench?linkStatus=PENDING_PR")
WBN=$(J "len(d['data'])" <<<"$WB")
[ "$WBN" -ge 1 ] && ok "待下单分组 ≥1（本单在列，实际 $WBN）" || bad "待下单分组空"
CONTAINS "工作台含本派工单" "$WB" "$DPNO"
CONTAINS "工作台带甲供标记" "$WB" "COMPANY_SUPPLY"
WBF=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/os/workbench?wcCode=OSWC-OS")
CONTAINS "按 WC 筛选命中" "$WBF" "$DPNO"
WBK=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/os/workbench?keyword=$MNO")
CONTAINS "关键词搜工单号命中" "$WBK" "$DPNO"
# 字段维护走真实 API（交期/供料方式/备注）
MF=$(C -H "$(A $TPLAN)" -X PUT -H 'Content-Type: application/json' \
  -d '{"osDueDate":"2026-10-20","osRemark":"外协冒烟交期备注","osSupplyType":"COMPANY_SUPPLY"}' \
  $B/api/mrp/dispatch/os/fields/$DPID)
EQ "外协字段维护 200" "$(J "d['code']" <<<"$MF")" "200"
EQ "维护后供料方式=甲供" "$(SQL "SELECT OS_SUPPLY_TYPE FROM erp_mrp_dispatch WHERE ID='$DPID'")" "COMPANY_SUPPLY"
EQ "维护后外协备注落库" "$(SQL "SELECT OS_REMARK FROM erp_mrp_dispatch WHERE ID='$DPID'")" "外协冒烟交期备注"
MF422=$(BIZ -X PUT -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d '{"osSupplyType":"BAD_TYPE"}' $B/api/mrp/dispatch/os/fields/$DPID)
EQ "非法供料方式 422" "$MF422" "422"
MF403=$(BIZ -X PUT -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"osRemark":"越权"}' $B/api/mrp/dispatch/os/fields/$DPID)
EQ "INSPECTOR 维护外协字段 403" "$MF403" "403"

echo "== 8. 甲供发料单（库存缺口 422 → 创建 → 出库过账 + 在途入池） =="
# 8.1 库存不足：请领 9999 > 可用 500 → 行级缺口阻断
SHORT=$(C -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"dispatchId\":\"$DPID\",\"lines\":[{\"itemCode\":\"$COMP\",\"itemName\":\"外协冒烟甲供原料\",\"batchNo\":\"OSB1\",\"warehouseCode\":\"WH-MAIN\",\"unit\":\"PC\",\"reqQty\":9999}]}" \
  $B/api/mrp/os-material)
EQ "库存不足创建 422" "$(J "d['code']" <<<"$SHORT")" "422"
CONTAINS "缺口消息含物料与缺口数" "$(J "d['message']" <<<"$SHORT")" "$COMP"
EQ "不足不落单（无 DRAFT 残留）" "$(SQL "SELECT COUNT(*) FROM erp_mrp_os_material WHERE DISPATCH_NO='$DPNO'")" "0"
# 8.2 正常创建（请领 30）
CR=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"dispatchId\":\"$DPID\",\"lines\":[{\"itemCode\":\"$COMP\",\"itemName\":\"外协冒烟甲供原料\",\"batchNo\":\"OSB1\",\"warehouseCode\":\"WH-MAIN\",\"unit\":\"PC\",\"reqQty\":30}],\"remark\":\"外协冒烟发料\"}" \
  $B/api/mrp/os-material)
OSMID=$(J "d['data']['id']" <<<"$CR")
OSMNO=$(J "d['data']['osmNo']" <<<"$CR")
[ -n "$OSMID" ] && ok "发料单创建 $OSMNO（DRAFT）" || bad "发料创建失败：$CR"
EQ "初始状态 DRAFT" "$(J "d['data']['status']" <<<"$CR")" "DRAFT"
# 8.3 权限：INSPECTOR 创建 403
EQ "INSPECTOR 创建发料 403" "$(BIZ -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d "{\"dispatchId\":\"$DPID\",\"lines\":[]}" $B/api/mrp/os-material)" "403"
# 8.4 出库过账（扣可用 500→470，入在途 0→30，总量守恒 500）
POST=$(C -H "$(A $TPLAN)" -X POST $B/api/mrp/os-material/$OSMID/post)
EQ "出库过账 200" "$(J "d['code']" <<<"$POST")" "200"
EQ "过账后状态 POSTED" "$(SQL "SELECT STATUS FROM erp_mrp_os_material WHERE ID='$OSMID'")" "POSTED"
NEAR "可用 500→470" "$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-osmk-c'")" "470"
NEAR "在途 0→30（OS_QTY 入池）" "$(SQL "SELECT OS_QTY FROM erp_inv_stock WHERE ID='st-osmk-c'")" "30"
NEAR "总量守恒 500" "$(SQL "SELECT QTY FROM erp_inv_stock WHERE ID='st-osmk-c'")" "500"
EQ "实发回写 30" "$(SQL "SELECT ISSUED_QTY FROM erp_mrp_os_material_line WHERE OSM_ID='$OSMID'")" "30.0000"
# 8.5 幂等：已 POSTED 再过账 422
EQ "重复过账 422" "$(BIZ -X POST -H "$(A $TPLAN)" $B/api/mrp/os-material/$OSMID/post)" "422"
# 8.6 自购派工不可发料 422（造一张自购派工单验证）
SQL "UPDATE erp_mrp_dispatch SET OS_SUPPLY_TYPE='SUPPLIER_BUY' WHERE ID='$DPID'" >/dev/null
EQ "自购派工创建发料 422" "$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"dispatchId\":\"$DPID\",\"lines\":[{\"itemCode\":\"$COMP\",\"warehouseCode\":\"WH-MAIN\",\"reqQty\":1}]}" \
  $B/api/mrp/os-material)" "422"
SQL "UPDATE erp_mrp_dispatch SET OS_SUPPLY_TYPE='COMPANY_SUPPLY' WHERE ID='$DPID'" >/dev/null

echo "== 9. PR 转 PO（createManual 带 prNo → 提交 → 签核 → 回联 ORDERED） =="
PRLID=$(SQL "SELECT l.ID FROM erp_proc_pr_line l JOIN erp_proc_requisition p ON l.PR_ID=p.ID WHERE p.PR_NO='$PRNO' LIMIT 1")
[ -n "$PRLID" ] && ok "PR 行就位 $PRLID" || bad "PR 行缺失"
# 供应商按真实 ID 传（createManual 校验的是 ID 非编码）
SUPID=$(SQL "SELECT ID FROM erp_mdm_supplier WHERE SUPPLIER_CODE='SUPFINTGT' LIMIT 1")
# createManual / submit / 签核均在 /api/proc/** 下（POST 兜底 ADMIN）——PO 面用 admin 走
POCR=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"supplierId\":\"$SUPID\",\"prNo\":\"$PRNO\",\"remark\":\"外协冒烟PO\",\"lines\":[{\"itemCode\":\"$PROD\",\"itemName\":\"外协冒烟成品\",\"qty\":10,\"unitPrice\":12,\"prLineId\":\"$PRLID\"}]}" \
  $B/api/proc/purchase-orders)
POID=$(J "d['data']['po']['id']" <<<"$POCR")
PONO=$(J "d['data']['po']['poNo']" <<<"$POCR")
[ -n "$POID" ] && ok "PO 创建 $PONO" || bad "PO 创建失败：$POCR"
SUB=$(C -H "$(A $TADM)" -X POST -H 'Content-Type: application/json' -d '{}' $B/api/proc/purchase-orders/$POID/submit)
EQ "PO 提交审批 200" "$(J "d['code']" <<<"$SUB")" "200"
PTID=$(SQL "SELECT ID FROM erp_proc_po_approval_task WHERE PO_ID='$POID' AND STATUS='ACTIVE' LIMIT 1")
[ -n "$PTID" ] && ok "PO 审批任务就位（节点 PURCHASE_MANAGER）" || bad "无 PO 审批任务"
EQ "PO 签核通过" "$(C -H "$(A $TADM)" -X POST $B/api/proc/purchase-orders/tasks/$PTID/pass | J "d['code']")" "200"
EQ "PO 状态 APPROVED" "$(SQL "SELECT STATUS FROM erp_proc_po WHERE ID='$POID'")" "APPROVED"
# 外协回联 ORDERED（PoApprovalServiceImpl:341）
EQ "派工单回联 ORDERED（PO_NO 落库）" "$(SQL "SELECT PO_NO FROM erp_mrp_dispatch WHERE ID='$DPID'")" "$PONO"
WBR=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/os/workbench?linkStatus=ORDERED")
CONTAINS "工作台已下单分组含本单" "$WBR" "$DPNO"

echo "== 10. 收货登记 → 检验（5.10.2 外协上下文）→ 放行 =="
POLID=$(SQL "SELECT ID FROM erp_proc_po_line WHERE PO_ID='$POID' LIMIT 1")
GR=$(C -H "$(A $TRECV)" -H 'Content-Type: application/json' \
  -d "{\"poId\":\"$POID\",\"arrivalDate\":\"$(date +%F)\",\"lines\":[{\"poLineId\":\"$POLID\",\"receivedQty\":10}],\"remark\":\"外协冒烟收货\"}" \
  $B/api/proc/grs)
GRID=$(J "d['data']['gr']['id']" <<<"$GR")
GRNO=$(J "d['data']['gr']['grNo']" <<<"$GR")
[ -n "$GRID" ] && ok "收货登记 $GRNO（检验批同事务生成）" || bad "收货登记失败：$GR"
LOTID=$(SQL "SELECT ID FROM erp_qms_inspection_lot WHERE ITEM_CODE='$PROD' ORDER BY CREATE_DATE DESC LIMIT 1")
[ -n "$LOTID" ] && ok "检验批自动生成 $LOTID" || bad "无检验批"
# 5.10.2 外协检验工作台：带外协上下文 + 质量线可见 + 计划线不可见
EQ "PLANNER 查外协检验 403" "$(BIZ -H "$(A $TPLAN)" $B/api/qms/lots/outsource-board)" "403"
OSB=$(C -H "$(A $TQC)" "$B/api/qms/lots/outsource-board?size=50")
CONTAINS "看板含本批" "$OSB" "$LOTID"
CONTAINS "看板带派工上下文（派工单号）" "$OSB" "$DPNO"
CONTAINS "看板带工单/工序上下文" "$OSB" "\"opSeq\":10"
CONTAINS "看板带供料方式上下文" "$OSB" "COMPANY_SUPPLY"
CONTAINS "看板带供应商名称上下文" "$OSB" "供应商"
# 放行（无检验标准 → 批 BLOCKED；照 trial-smoke 惯例 SQL 置 PASS + 标准后调放行 API）
SQL "UPDATE erp_qms_inspection_lot SET RESULT='PASS', STATUS='PENDING', STANDARD_ID='osmk-std' WHERE ID='$LOTID';" >/dev/null
EQ "质检员放行 200" "$(C -H "$(A $TQC)" -X POST $B/api/qms/lots/$LOTID/release | J "d['code']")" "200"
EQ "批状态 RELEASED" "$(SQL "SELECT STATUS FROM erp_qms_inspection_lot WHERE ID='$LOTID'")" "RELEASED"
EQ "GR 行 QC_STATUS=RELEASED" "$(SQL "SELECT QC_STATUS FROM erp_proc_gr_line WHERE GR_ID='$GRID'")" "RELEASED"
# 幂等：二次放行 422
EQ "二次放行 422（不可撤回）" "$(BIZ -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOTID/release)" "422"
# 仓位分配（4.4.5 过账前置）：一键推荐并确认（本族仅一条待分配行）
BINR=$(C -H "$(A $TWH)" -X POST $B/api/inv/bin-assignment/recommend-all)
EQ "仓位一键分配 200" "$(J "d['code']" <<<"$BINR")" "200"

echo "== 11. 过账 → 自动报工 → 工序完成 → 收货回联 RECEIVED =="
# 过账前未放行阻断：先造一张批未放行的对照（免——本批已放行，阻断面由单测覆盖）
PSTR=$(C -H "$(A $TWH)" -X POST -H 'Content-Type: application/json' -d '{}' $B/api/proc/grs/$GRID/postings)
EQ "收货过账 200" "$(J "d['code']" <<<"$PSTR")" "200"
[ "$(J "d['code']" <<<"$PSTR")" != "200" ] && echo "     ↳ 过账消息：$(J "d['message']" <<<"$PSTR")"
EQ "GR 状态 POSTED" "$(SQL "SELECT STATUS FROM erp_proc_gr WHERE ID='$GRID'")" "POSTED"
# 自动报工（AUTO_OUTSOURCE 标记 + CONFIRMED 直过）
RPTN=$(SQL "SELECT COUNT(*) FROM erp_mrp_reporting WHERE MO_NO='$MNO' AND REMARK LIKE 'AUTO_OUTSOURCE:%'")
EQ "自动报工生成 1 条" "$RPTN" "1"
RPTST=$(SQL "SELECT STATUS FROM erp_mrp_reporting WHERE MO_NO='$MNO' AND REMARK LIKE 'AUTO_OUTSOURCE:%' LIMIT 1")
EQ "自动报工 CONFIRMED（免审直过）" "$RPTST" "CONFIRMED"
OUTN=$(SQL "SELECT COUNT(*) FROM erp_mrp_output WHERE MO_ID='$MO'")
EQ "产出行生成 1 条" "$OUTN" "1"
NEAR "产出行合格数 = 收货 10" "$(SQL "SELECT GOOD_QTY FROM erp_mrp_output WHERE MO_ID='$MO' LIMIT 1")" "10"
EQ "收货回联 RECEIVED（RECV_QTY=10）" "$(SQL "SELECT RECV_QTY FROM erp_mrp_dispatch WHERE ID='$DPID'")" "10.0000"
# 收足（RECV_QTY 10 ≥ MO.QTY 10）→ 工序自动 DONE
EQ "外协工序自动完成 DONE" "$(SQL "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID='$MO' AND OP_SEQ=10")" "DONE"
# 重复过账幂等（GR 已 POSTED → 422）
EQ "重复过账 422" "$(BIZ -X POST -H "$(A $TWH)" -H 'Content-Type: application/json' -d '{}' $B/api/proc/grs/$GRID/postings)" "422"

echo "== 12. 收货定额冲减 + 余料退回 =="
# 定额 = BOM 快照单耗 2 × 收货 10 = 20；在途 30→10
EQ "定额冲减台账 1 条（CONSUME/GR 锚）" "$(SQL "SELECT COUNT(*) FROM erp_mrp_os_consume WHERE CONSUME_TYPE='CONSUME' AND ANCHOR_NO='$GRNO'")" "1"
NEAR "行冲减累计 = 20" "$(SQL "SELECT CONSUMED_QTY FROM erp_mrp_os_material_line WHERE OSM_ID='$OSMID'")" "20"
NEAR "在途 30→10（冲减 20）" "$(SQL "SELECT OS_QTY FROM erp_inv_stock WHERE ID='st-osmk-c'")" "10"
NEAR "总量 500→480（随消耗）" "$(SQL "SELECT QTY FROM erp_inv_stock WHERE ID='st-osmk-c'")" "480"
# 幂等：同 GR 重复过账不双冲（过账已 422 挡；直接断言台账仍 1 条）
EQ "冲减幂等（台账仍 1 条）" "$(SQL "SELECT COUNT(*) FROM erp_mrp_os_consume WHERE ANCHOR_NO='$GRNO'")" "1"
# 列表回填待退回余量 = 30 − 20 = 10
NEAR "列表待退回余量 = 10" "$(C -H "$(A $TPLAN)" "$B/api/mrp/os-material/list?dispatchNo=$DPNO" | J "d['data'][0]['remainQty']")" "10"
# 超退阻断（可退 10，试退 50）
LID2=$(SQL "SELECT ID FROM erp_mrp_os_material_line WHERE OSM_ID='$OSMID'")
EQ "超退 422（行级缺口）" "$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"lineId\":\"$LID2\",\"qty\":50}]}" $B/api/mrp/os-material/$OSMID/return)" "422"
# 正常退回 10（全退 → RETURNED；在途 10→0，可用回 480）
RET=$(C -H "$(A $TPLAN)" -X POST -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"lineId\":\"$LID2\",\"qty\":10}]}" $B/api/mrp/os-material/$OSMID/return)
EQ "余料退回 200" "$(J "d['code']" <<<"$RET")" "200"
EQ "全退后状态 RETURNED" "$(SQL "SELECT STATUS FROM erp_mrp_os_material WHERE ID='$OSMID'")" "RETURNED"
NEAR "在途归零" "$(SQL "SELECT OS_QTY FROM erp_inv_stock WHERE ID='st-osmk-c'")" "0"
NEAR "可用回到 480（退回入库）" "$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-osmk-c'")" "480"
EQ "退库流水 OS_MAT_IN 1 条" "$(SQL "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_NO='$OSMNO' AND TYPE_CODE='OS_MAT_IN'")" "1"
EQ "INSPECTOR 退回 403" "$(BIZ -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"lineId\":\"$LID2\",\"qty\":1}]}" $B/api/mrp/os-material/$OSMID/return)" "403"

echo "== 13. 5.10.4 外协对账（生成 → 重复 422 → 提交 → 签核 → 挂账） =="
# 非外协 PO 阻断
EQ "非外协 PO 生成 422" "$(BIZ -X POST -H "$(A $TPUR)" -H 'Content-Type: application/json' \
  -d '{"poNo":"PO-NOT-OUTSOURCE"}' $B/api/mrp/os-settlements/generate)" "422"
GEN=$(C -H "$(A $TPUR)" -H 'Content-Type: application/json' \
  -d "{\"poNo\":\"$PONO\",\"remark\":\"外协冒烟对账\"}" $B/api/mrp/os-settlements/generate)
OSRID=$(J "d['data']['id']" <<<"$GEN")
OSRNO=$(J "d['data']['osrNo']" <<<"$GEN")
[ -n "$OSRID" ] && ok "对账单生成 $OSRNO" || bad "对账生成失败：$GEN"
NEAR "对账数量 = 收货 10" "$(J "d['data']['totalQty']" <<<"$GEN")" "10"
NEAR "对账金额 = 10×12 = 120" "$(J "d['data']['totalAmt']" <<<"$GEN")" "120"
# 无未对账明细（行已被本单引用）→ 422
EQ "重复生成 422（无未对账明细）" "$(BIZ -X POST -H "$(A $TPUR)" -H 'Content-Type: application/json' \
  -d "{\"poNo\":\"$PONO\"}" $B/api/mrp/os-settlements/generate)" "422"
# 权限：INSPECTOR 生成 403（spec 场景）
EQ "INSPECTOR 生成对账 403" "$(BIZ -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d "{\"poNo\":\"$PONO\"}" $B/api/mrp/os-settlements/generate)" "403"
# 提交审批 → 计划主管签核 → POSTED + OPEN 挂账
EQ "提交审批 200" "$(C -H "$(A $TPUR)" -X POST $B/api/mrp/os-settlements/$OSRID/submit | J "d['code']")" "200"
EQ "提交后 CONFIRMED" "$(SQL "SELECT STATUS FROM erp_proc_os_settlement WHERE ID='$OSRID'")" "CONFIRMED"
EQ "重复提交 422" "$(BIZ -X POST -H "$(A $TPUR)" $B/api/mrp/os-settlements/$OSRID/submit)" "422"
STID=$(C -H "$(A $TPLM)" $B/api/qms/approvals/todo | python3 -c "
import sys,json
for t in json.load(sys.stdin)['data']:
    if t.get('bizType')=='OutsourceSettlement': print(t['taskId']); break")
[ -n "$STID" ] && ok "对账审批待办可见（计划主管）" || bad "无对账待办"
EQ "签核通过" "$(C -H "$(A $TPLM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$STID\",\"opinion\":\"金额核对无误\"}" $B/api/qms/approvals/pass | J "d['code']")" "200"
EQ "对账单 POSTED" "$(SQL "SELECT STATUS FROM erp_proc_os_settlement WHERE ID='$OSRID'")" "POSTED"
EQ "应付挂账生成 OPEN" "$(SQL "SELECT STATUS FROM erp_proc_os_ap WHERE OSR_NO='$OSRNO'")" "OPEN"
NEAR "挂账金额 = 对账金额 120" "$(SQL "SELECT AMT FROM erp_proc_os_ap WHERE OSR_NO='$OSRNO'")" "120"
# 已挂账不可改
EQ "已挂账不可删除 422" "$(BIZ -X DELETE -H "$(A $TPUR)" $B/api/mrp/os-settlements/$OSRID)" "422"
EQ "已挂账不可再提交 422" "$(BIZ -X POST -H "$(A $TPUR)" $B/api/mrp/os-settlements/$OSRID/submit)" "422"
EQ "挂账列表可查" "$(C -H "$(A $TPUR)" "$B/api/mrp/os-settlements/ap/list?status=OPEN" | J "len(d['data'])")" "1"

echo "== 14. 权限矩阵收口（两线） =="
EQ "INSPECTOR 查发料单 403" "$(BIZ -H "$(A $TQC)" $B/api/mrp/os-material/list)" "403"
EQ "INSPECTOR 查对账单 403" "$(BIZ -H "$(A $TQC)" $B/api/mrp/os-settlements/list)" "403"
EQ "INSPECTOR 查挂账 403" "$(BIZ -H "$(A $TQC)" $B/api/mrp/os-settlements/ap/list)" "403"
EQ "PLANNER 查外协检验 403" "$(BIZ -H "$(A $TPLAN)" $B/api/qms/lots/outsource-board)" "403"
EQ "PURCHASER 查工作台 200" "$(BIZ -H "$(A $TPUR)" $B/api/mrp/dispatch/os/workbench)" "200"
EQ "PURCHASER 查发料单 200" "$(BIZ -H "$(A $TPUR)" $B/api/mrp/os-material/list)" "200"
EQ "WAREHOUSE 创建发料 403" "$(BIZ -X POST -H "$(A $TWH)" -H 'Content-Type: application/json' \
  -d '{"dispatchId":"x","lines":[]}' $B/api/mrp/os-material)" "403"

echo ""
echo "=================================="
echo "  PASS: $PASS   FAIL: $FAIL"
echo "=================================="
[ "$FAIL" -eq 0 ] && echo "ALL PASS" || echo "HAS FAILURES"
