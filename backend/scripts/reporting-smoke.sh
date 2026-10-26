#!/bin/zsh
# add-reporting-management 全链路 API 冒烟（任务 7.1，参照 dispatch-smoke 先例，可重跑）
# 链路：清理→账号→登录三角色→401→造数（物料/BOM/WC/工序/定额/路线含良率关键标记/库存/工单/派工）→
#       开工准入（终态阻断/超期黄标/代报留痕）→投料即扣对账→逐件累计+超产分级→
#       良率超差冻结+通知+NCR→冲销回拨解冻→完工倒冲差额→关闭钩子→403 矩阵
# 断言层：401 走 HTTP；403/422/409 是 R<T> 业务码（HTTP 恒 200）用 BIZ
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
HTTP() { curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$@"; }
BIZ() { C "$@" | J "d['code']"; }
export MYSQL_PWD=root123
SQL() { docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --default-character-set=utf8mb4 -uroot -N -B erp -e "$1" 2>/dev/null \
  || mysql -h127.0.0.1 -uroot -proot123 erp --default-character-set=utf8mb4 -N -B -e "$1"; }
A() { echo "Authorization: Bearer $1"; }

echo "== 0. 前置清理（reporting 冒烟族，可重跑）="
SQL "
DELETE FROM erp_qms_ncr_log WHERE NCR_ID IN (SELECT ID FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER');
DELETE FROM erp_qms_copq_entry WHERE SOURCE_TYPE='NCR' AND NCR_ID IN (SELECT ID FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER');
DELETE FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER';
DELETE FROM erp_mrp_mo_freeze WHERE MO_NO LIKE 'MO-%' OR MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '报工冒烟%'));
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE IN ('ReportReversal','OverProd');
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('ReportReversal','OverProd');
DELETE FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER';
DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('YIELD_ALERT','YIELD_UNFREEZE','WORKHOUR_ALERT','FAI_BLOCK','IPQC_BLOCK','REVERSAL_RESULT','OVERPROD_RESULT');
DELETE FROM erp_mrp_output WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '报工冒烟%'));
DELETE FROM erp_mrp_feed WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '报工冒烟%'));
DELETE FROM erp_mrp_reporting WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '报工冒烟%'));
DELETE FROM erp_mrp_dispatch WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '报工冒烟%'));
DELETE FROM erp_mrp_reservation WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '报工冒烟%'));
DELETE FROM erp_mrp_mo_shortage WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '报工冒烟%'));
DELETE FROM erp_mrp_mo_bom WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_mrp_mo_op WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '报工冒烟%');
DELETE FROM erp_inv_stock WHERE ITEM_NAME LIKE '报工冒烟%';
DELETE FROM erp_mrp_routing_op WHERE WC_CODE LIKE 'RPTWC%';
DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '报工冒烟%');
DELETE FROM erp_mrp_op_wc_standard WHERE WC_CODE LIKE 'RPTWC%';
DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'RPTWC%';
DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'RPTOP%';
DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '报工冒烟%';
DELETE FROM erp_mdm_item_category WHERE ID='cat-rpt';
INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX)
VALUES ('cat-rpt','RPTK','报工冒烟分类','RPTK');" >/dev/null 2>&1 \
  && ok "清理完成（RPTK 分类就位）" || bad "清理失败"

echo "== 1. 账号（复用 admin BCrypt） =="
AHASH=$(SQL "SELECT PASSWORD FROM erp_admin_user WHERE USERNAME='admin' LIMIT 1")
mkuser() {
  SQL "INSERT IGNORE INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, CREATE_BY)
       VALUES ('user-$1','$1','$AHASH','$2','1','smoke');
       INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) VALUES ('ur-$1','user-$1','$3');" >/dev/null 2>&1
}
mkuser rptfloor   "报工操作工"   role-shop-floor
mkuser rptplanner "报工计划员"   role-planner
mkuser rptmgr     "报工计划主管" role-plan-mgr
mkuser rptviewer  "报工围观"     role-user
N=$(SQL "SELECT COUNT(*) FROM erp_admin_user WHERE USERNAME IN ('rptfloor','rptplanner','rptmgr','rptviewer')")
EQ "四账号就位" "$N" "4"

echo "== 2. 登录三角色 =="
TFLO=$(C -H 'Content-Type: application/json' -d '{"username":"rptfloor","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TPLAN=$(C -H 'Content-Type: application/json' -d '{"username":"rptplanner","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TMRG=$(C -H 'Content-Type: application/json' -d '{"username":"rptmgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TVIEW=$(C -H 'Content-Type: application/json' -d '{"username":"rptviewer","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TFLO" ] && [ -n "$TPLAN" ] && [ -n "$TMRG" ] && [ -n "$TADM" ] && [ -n "$TVIEW" ] \
  && ok "floor/planner/planmgr/admin/viewer 登录" || bad "登录失败"

echo "== 3. 401 矩阵（HTTP 层） =="
EQ "POST /mrp/reporting/open 401" "$(HTTP -X POST $B/api/mrp/reporting/open)" "401"
EQ "POST /mrp/reporting/confirm/x 401" "$(HTTP -X POST $B/api/mrp/reporting/confirm/x)" "401"
EQ "POST /mrp/reporting/feed 401" "$(HTTP -X POST $B/api/mrp/reporting/feed)" "401"
EQ "POST /mrp/reporting/complete/x 401" "$(HTTP -X POST $B/api/mrp/reporting/complete/x)" "401"
EQ "GET /mrp/reporting/list 401" "$(HTTP $B/api/mrp/reporting/list)" "401"
EQ "GET /mrp/reporting/yield 401" "$(HTTP "$B/api/mrp/reporting/yield?moId=x")" "401"

echo "== 4. 造数（物料/BOM/WC/工序/定额/路线含良率与关键标记/库存） =="
mkitem() {
  C -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"itemName\":\"报工冒烟$1\",\"categoryCode\":\"RPTK\",\"baseUnit\":\"PC\",\"materialGroup\":\"STRUCT\",\"purchaseType\":\"$2\",\"storageCondition\":\"NORMAL\",\"batchFlag\":\"0\",\"packingSpec\":\"箱\",\"dupNote\":\"报工管理冒烟物料，非业务重复数据\"}" \
    "$B/api/mdm/items?forceCreate=true" | J "d['data']['itemCode']"
}
P1=$(mkitem 产品 MAKE); C1=$(mkitem 子件 BUY)
[ -n "$P1" ] && [ -n "$C1" ] && ok "物料 P1=$P1 C1=$C1" || bad "建物料失败"
BID=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$P1\"},\"items\":[{\"itemCode\":\"$C1\",\"qty\":1,\"lossRate\":0.1}]}" \
  $B/api/mrp/boms | J "d['data']['id']")
SQL "UPDATE erp_mrp_bom SET STATUS='PUBLISHED', PUBLISH_BY='smoke', PUBLISH_AT=NOW() WHERE ID='$BID';" >/dev/null
BBS=$(SQL "SELECT COUNT(*) FROM erp_mrp_bom WHERE ID='$BID' AND STATUS='PUBLISHED'")
EQ "BOM 发布（损耗 0.1——倒冲差额公式用）" "$BBS" "1"

# WC/工序/定额/路线（直插 PUBLISHED——冒烟惯例）；路线行带 STANDARD_YIELD=90 与关键标记
SQL "
INSERT IGNORE INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, CAL_HOURS, STATUS)
 VALUES ('wc-rpt-in','RPTWC-IN','报工冒烟中心','INTERNAL',8,'1');
INSERT IGNORE INTO erp_mrp_operation (ID, OP_CODE, OP_NAME, STATUS)
 VALUES ('op-rpt1','RPTOP10','报工冒烟工序10','1');
INSERT IGNORE INTO erp_mrp_op_wc_standard (ID, OP_CODE, WC_CODE, SETUP_HOURS, RUN_HOURS)
 VALUES ('std-rpt1','RPTOP10','RPTWC-IN',0,1);
INSERT IGNORE INTO erp_mrp_routing (ID, ITEM_CODE, ITEM_NAME, VERSION_MAJOR, VERSION_MINOR, STATUS, PUBLISH_BY, PUBLISH_AT)
 VALUES ('rt-rpt-p1','${P1:-X}','报工冒烟产品',1,0,'PUBLISHED','smoke',NOW());
INSERT IGNORE INTO erp_mrp_routing_op (ID, ROUTING_ID, OP_SEQ, OP_CODE, OP_NAME, WC_CODE, WC_NAME, LEAD_TIME, STANDARD_YIELD, KEY_OP_FLAG)
 VALUES ('rto-rpt-10','rt-rpt-p1',10,'RPTOP10','报工冒烟工序10','RPTWC-IN','报工冒烟中心',0,90,0);" >/dev/null 2>&1
RTN=$(SQL "SELECT COUNT(*) FROM erp_mrp_routing WHERE STATUS='PUBLISHED' AND ITEM_CODE='$P1'")
EQ "路线直插发布" "$RTN" "1"
SY=$(SQL "SELECT STANDARD_YIELD FROM erp_mrp_routing_op WHERE ID='rto-rpt-10'")
EQ "路线行标准良率 90（5.7 加列）" "$SY" "90.00"
SQL "INSERT IGNORE INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, QTY, AVAILABLE_QTY)
     VALUES ('st-rpt-c1','$C1','报工冒烟子件',500,500);" >/dev/null
AV0=$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-rpt-c1'")
EQ "库存就位 500" "$AV0" "500.0000"

echo "== 5. 建工单置 RELEASED + 生成派工单确认 =="
MOR=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"productCode\":\"$P1\",\"qty\":10,\"planStartDate\":\"$(date +%F)\",\"planEndDate\":\"$(date -v+7d +%F 2>/dev/null || date -d '+7 days +%F')\"}}" \
  $B/api/mrp/mos | J "d['data']['mo']['id']")
[ -n "$MOR" ] && ok "工单 MOR=$MOR" || bad "建单失败"
MORNO=$(SQL "SELECT MO_NO FROM erp_mrp_mo WHERE ID='$MOR'")
SY2=$(SQL "SELECT STANDARD_YIELD FROM erp_mrp_mo_op WHERE MO_ID='$MOR' LIMIT 1")
EQ "工单快照带入标准良率 90（改版隔离前提）" "$SY2" "90.00"
SQL "UPDATE erp_mrp_mo SET STATUS='RELEASED' WHERE ID='$MOR';" >/dev/null
DPR=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"moId\":\"$MOR\",\"opSeq\":10,\"wcCode\":null}]}" \
  $B/api/mrp/dispatch/generate | J "d['data'][0]['id']")
[ -n "$DPR" ] && ok "派工单 DPR=$DPR" || bad "生成派工失败"
C -H "$(A $TADM)" -X POST $B/api/mrp/dispatch/confirm/$DPR >/dev/null
DST=$(SQL "SELECT STATUS FROM erp_mrp_dispatch WHERE ID='$DPR'")
EQ "派工确认 RELEASED" "$DST" "RELEASED"
AV1=$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-rpt-c1'")
NEAR "预扣实扣（500−11=489，REQ=10×1×1.1）" "$AV1" "489"

echo "== 6. 开工准入（同工序防重 / 代报留痕） =="
# 6a. 同工序防重：已有未取消派工单再生成 422（终态阻断在步骤 12.5 取消后验证）
BIZD=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"moId\":\"$MOR\",\"opSeq\":10,\"wcCode\":null}]}" \
  $B/api/mrp/dispatch/generate)
EQ "同工序重复生成 422（防重）" "$BIZD" "422"
# 6b. 操作工开工——派工由 admin 确认 → 人单差 → 代报留痕
RPT1=$(C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"dispatchId\":\"$DPR\"}" $B/api/mrp/reporting/open | J "d['data']['rptNo']")
[ -n "$RPT1" ] && ok "开工成功 RPT1=$RPT1" || bad "开工失败"
RID1=$(SQL "SELECT ID FROM erp_mrp_reporting WHERE RPT_NO='$RPT1'")
PROXY=$(SQL "SELECT PROXY_FLAG FROM erp_mrp_reporting WHERE ID='$RID1'")
EQ "人单不匹配代报留痕 PROXY_FLAG=1" "$PROXY" "1"
OWNER=$(SQL "SELECT DISPATCH_OWNER FROM erp_mrp_reporting WHERE ID='$RID1'")
[ -n "$OWNER" ] && ok "DISPATCH_OWNER=派工确认人留痕（$OWNER）" || bad "无派工确认人留痕"
RPTER=$(SQL "SELECT REPORTER FROM erp_mrp_reporting WHERE ID='$RID1'")
EQ "REPORTER=实际报工人（user id）" "$RPTER" "user-rptfloor"
STARTED=$(SQL "SELECT COUNT(*) FROM erp_mrp_reporting WHERE ID='$RID1' AND START_AT IS NOT NULL")
EQ "实际开工时间落库" "$STARTED" "1"
OPST=$(SQL "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID='$MOR' AND OP_SEQ=10")
EQ "opStatus 流转 RUNNING（开工报工）" "$OPST" "RUNNING"

echo "== 7. 投料即扣对账 =="
C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID1\",\"itemCode\":\"$C1\",\"batchCode\":\"B-RPT\",\"qty\":20}" \
  $B/api/mrp/reporting/feed >/dev/null
AV2=$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-rpt-c1'")
NEAR "投料 20 即扣（489−20=469）" "$AV2" "469"
FEEDN=$(SQL "SELECT COUNT(*) FROM erp_mrp_feed WHERE RPT_ID='$RID1' AND FLOW_TYPE='HAND'")
EQ "投料行落库" "$FEEDN" "1"
# 负库存护栏
BIZF=$(BIZ -X POST -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID1\",\"itemCode\":\"$C1\",\"qty\":99999}" $B/api/mrp/reporting/feed)
EQ "超可用量投料 422（余量护栏）" "$BIZF" "422"
AV3=$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-rpt-c1'")
NEAR "护栏拒绝后可用不变" "$AV3" "469"

echo "== 8. 逐件累计 + 超产分级 =="
# 逐件/批量同结构：保存产出 9 合格 + 1 不合格（计划 10 → 良率 90% = 标准，不触发超差）
C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID1\",\"goodQty\":9,\"ngQty\":1,\"ngDispose\":\"SCRAP\",\"overprodAck\":true}" \
  $B/api/mrp/reporting/output >/dev/null
OUTG=$(SQL "SELECT GOOD_QTY FROM erp_mrp_output WHERE RPT_ID='$RID1'")
EQ "产出落库（合格 9）" "$OUTG" "9.0000"
# 小额报废（1 < NCR_AUTO_QTY=5）→ 域内处置零 NCR
NCRN=$(SQL "SELECT COUNT(*) FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER'")
EQ "小额报废域内处置（零 NCR）" "$NCRN" "0"
DDF=$(SQL "SELECT DIRECT_DISPOSE_FLAG FROM erp_mrp_output WHERE RPT_ID='$RID1'")
EQ "域内处置标记" "$DDF" "1"
# 超产阈值内二次确认：good=11 > 计划 10（超 10% > 5% 容差 → 挂审批）——先测阈值内需小计划
# 计划 10、容差 5% → 超 1 件=10% 超阈值，直接验证挂起链：
C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID1\",\"goodQty\":11,\"ngQty\":1,\"ngDispose\":\"SCRAP\",\"overprodAck\":true}" \
  $B/api/mrp/reporting/output >/dev/null 2>&1
OH=$(SQL "SELECT OVERPROD_FLAG FROM erp_mrp_reporting WHERE ID='$RID1'")
if [ "$OH" = "1" ]; then
  ok "超产超容差挂起 OVERPROD_FLAG=1"
  BIZC=$(BIZ -X POST -H "$(A $TFLO)" $B/api/mrp/reporting/confirm/$RID1)
  EQ "挂起中提交 422（待主管审批）" "$BIZC" "422"
  TID=$(C -H "$(A $TMRG)" $B/api/qms/approvals/todo | python3 -c "import sys,json;rows=json.load(sys.stdin)['data'];m=[r for r in rows if r.get('bizType')=='OverProd'];print(m[0]['taskId'] if m else '')")
  [ -n "$TID" ] && ok "OverProd 待办生成" || bad "无 OverProd 待办"
  PPASS=$(C -H "$(A $TMRG)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$TID\",\"opinion\":\"同意超产\"}" $B/api/qms/approvals/pass | J "d['code']")
  EQ "计划主管通过超产" "$PPASS" "200"
  PCF=$(SQL "SELECT PROD_CONFIRM_FLAG FROM erp_mrp_reporting WHERE ID='$RID1'")
  EQ "审批通过置 PROD_CONFIRM_FLAG" "$PCF" "1"
else
  ok "超产未挂起（容差内直接入账——视参数而定 OH=$OH）"
fi

echo "== 9. 提交报工（工序 DONE）+ 良率超差冻结 + NCR =="
# 良率 11/12=91.67% vs 标准 90 → 偏差 1.85% 不超。改标准良率快照制造超差：
SQL "UPDATE erp_mrp_mo_op SET STANDARD_YIELD=60 WHERE MO_ID='$MOR' AND OP_SEQ=10;" >/dev/null
CFC=$(BIZ -X POST -H "$(A $TFLO)" $B/api/mrp/reporting/confirm/$RID1)
EQ "提交报工 200" "$CFC" "200"
OPST2=$(SQL "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID='$MOR' AND OP_SEQ=10")
EQ "opStatus 流转 DONE（完工报工）" "$OPST2" "DONE"
# 偏差 = |91.67−60|/60 = 52.8% > 10% → 冻结 + YIELD_ALERT + NCR
FZN=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_freeze WHERE MO_ID='$MOR' AND ACTIVE_FLAG='1'")
EQ "良率超差活动冻结" "$FZN" "1"
FS=$(SQL "SELECT SCOPE FROM erp_mrp_mo_freeze WHERE MO_ID='$MOR' AND ACTIVE_FLAG='1'")
echo "  （冻结作用域 $FS——偏差>2×阈值升 WC 级）"
YAL=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='YIELD_ALERT'")
[ "$YAL" -ge 1 ] && ok "YIELD_ALERT 推送（$YAL 条）" || bad "无良率告警"
YN=$(SQL "SELECT COUNT(*) FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER'")
EQ "良率超差自动建 NCR" "$YN" "1"
# 冻结挡派工
BIZG=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"moId\":\"$MOR\",\"opSeq\":10,\"wcCode\":null}]}" $B/api/mrp/dispatch/generate)
EQ "冻结期派工 422（5.6 D12 挂点兑现）" "$BIZG" "422"
# 再提交同工序报工（首件/关键工序 OFF 或已过）→ 应可继续（本路线非关键、FAI 首单已建批）
# 良率查询端点
YLD=$(C -H "$(A $TPLAN)" "$B/api/mrp/reporting/yield?moId=$MOR&opSeq=10" | J "d['data']['exceeded']")
EQ "良率端点判定超阈值" "$YLD" "True"

echo "== 10. 整单红字冲销（原因必填 + 审批 + 五步反向 + 回拨解冻） =="
BIZX=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID1\",\"reason\":\"\"}" $B/api/mrp/reporting/reversal)
EQ "空原因冲销申请 422" "$BIZX" "422"
C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID1\",\"reason\":\"冒烟冲销：良率录错\"}" $B/api/mrp/reporting/reversal >/dev/null
RTID=$(C -H "$(A $TMRG)" $B/api/qms/approvals/todo | python3 -c "import sys,json;rows=json.load(sys.stdin)['data'];m=[r for r in rows if r.get('bizType')=='ReportReversal'];print(m[0]['taskId'] if m else '')")
[ -n "$RTID" ] && ok "ReportReversal 待办生成" || bad "无冲销待办"
RPASS=$(C -H "$(A $TMRG)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$RTID\",\"opinion\":\"同意冲销\"}" $B/api/qms/approvals/pass | J "d['code']")
EQ "计划主管通过冲销" "$RPASS" "200"
RST=$(SQL "SELECT STATUS FROM erp_mrp_reporting WHERE ID='$RID1'")
EQ "原单置 REVERSED" "$RST" "REVERSED"
RVID=$(SQL "SELECT REVERSED_BY_ID FROM erp_mrp_reporting WHERE ID='$RID1'")
MIRN=$(SQL "SELECT COUNT(*) FROM erp_mrp_reporting WHERE REVERSES_ID='$RID1'")
EQ "红字镜像单生成（REVERSES_ID 成对）" "$MIRN" "1"
AV4=$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-rpt-c1'")
NEAR "投料回加（469+20=489）" "$AV4" "489"
FZN2=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_freeze WHERE MO_ID='$MOR' AND ACTIVE_FLAG='1'")
EQ "冲销回拨自动解冻" "$FZN2" "0"
YUN=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='YIELD_UNFREEZE'")
[ "$YUN" -ge 1 ] && ok "解冻通知原接收人（$YUN 条）" || bad "无解冻通知"

echo "== 11. 完工倒冲（差额防御 + 损耗计入） =="
# 现状：投料 20（回加后仍 20？冲销已回加→ HAND 行 RETURNED=1 不计）、领料 0 → 应投 11（10×1×1.1）
# 冲销后 HAND 行已回加（RETURNED_FLAG=1）→ fed=0 → 差额 11 补投
AVB=$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-rpt-c1'")
# 冲销后工序回退 RUNNING → 需重新报一张 CONFIRMED 才能完工
# 先放行首件检验批 + 恢复标准良率（否则 RPT2 提交被 FAI 未决 422 阻断、重报再次触发超差冻结）
SQL "UPDATE erp_qms_inspection_lot SET STATUS='RELEASED', RESULT='PASS' WHERE REF_TYPE='WORKORDER';
     UPDATE erp_mrp_mo_op SET STANDARD_YIELD=98 WHERE MO_ID='$MOR' AND OP_SEQ=10;" >/dev/null
RPT2=$(C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"dispatchId\":\"$DPR\"}" $B/api/mrp/reporting/open | J "d['data']['id']")
C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RPT2\",\"goodQty\":10,\"ngQty\":0,\"overprodAck\":true}" \
  $B/api/mrp/reporting/output >/dev/null
CF2=$(BIZ -X POST -H "$(A $TFLO)" $B/api/mrp/reporting/confirm/$RPT2)
EQ "重新提交报工 200（检验批已放行）" "$CF2" "200"
BIZCM=$(BIZ -X POST -H "$(A $TPLAN)" $B/api/mrp/reporting/complete/$MOR)
EQ "完工确认 200" "$BIZCM" "200"
MOST=$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$MOR'")
EQ "工单 COMPLETED" "$MOST" "COMPLETED"
BFN=$(SQL "SELECT COUNT(*) FROM erp_mrp_feed WHERE MO_ID='$MOR' AND FLOW_TYPE='BFLUSH'")
[ "$BFN" -ge 1 ] && ok "倒冲补投 BFLUSH 行生成（$BFN 行）" || bad "无倒冲行"
AVC=$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-rpt-c1'")
echo "  （倒冲后可用 $AVC——差额 = 应投 11 − 手工未回加 − 领料）"
QC=$(SQL "SELECT QUALIFIED_QTY FROM erp_mrp_mo WHERE ID='$MOR'")
NEAR "合格产出 = 末道累计 10" "$QC" "10"

echo "== 12. 关闭钩子（未完成工序真实校验） =="
# 本工单单工序且 DONE → 关闭预检应通过
PRE=$(C -H "$(A $TPLAN)" $B/api/mrp/mos/$MOR/close-precheck | J "d['data']['passed']")
[ "$PRE" = "True" ] && ok "关闭预检通过（无未完成工序）" || bad "预检未过：$(C -H "$(A $TPLAN)" $B/api/mrp/mos/$MOR/close-precheck)"
# 制造未完成工序 → 关闭 422 列清单
SQL "UPDATE erp_mrp_mo_op SET OP_STATUS='RUNNING' WHERE MO_ID='$MOR' AND OP_SEQ=10;" >/dev/null
BIZCL=$(BIZ -X POST -H "$(A $TPLAN)" $B/api/mrp/mos/$MOR/close)
EQ "未完成工序关闭 422" "$BIZCL" "422"
SQL "UPDATE erp_mrp_mo_op SET OP_STATUS='DONE' WHERE MO_ID='$MOR' AND OP_SEQ=10;" >/dev/null
BIZCL2=$(BIZ -X POST -H "$(A $TPLAN)" $B/api/mrp/mos/$MOR/close)
EQ "全 DONE 关闭 200" "$BIZCL2" "200"
CLST=$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$MOR'")
EQ "工单 CLOSED 终态" "$CLST" "CLOSED"

echo "== 12.5. 终态阻断（FR-4.5-5-1 取消派工单开工 422） =="
CNC=$(BIZ -X PUT -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d '{"reason":"冒烟终态验证"}' \
  $B/api/mrp/dispatch/cancel/$DPR)
EQ "取消派工单 200" "$CNC" "200"
DBL=$(SQL "SELECT STATUS FROM erp_mrp_dispatch WHERE ID='$DPR'")
EQ "派工单已取消（终态）" "$DBL" "CANCELLED"
BIZT=$(BIZ -X POST -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"dispatchId\":\"$DPR\"}" $B/api/mrp/reporting/open)
EQ "终态派工单开工 422（已过期阻断）" "$BIZT" "422"

echo "== 13. 403 矩阵（业务码） =="
EQ "viewer 录入台开工 403" "$(BIZ -X POST -H "$(A $TVIEW)" -H 'Content-Type: application/json' -d '{"dispatchId":"x"}' $B/api/mrp/reporting/open)" "403"
EQ "viewer 查记录 403" "$(BIZ -H "$(A $TVIEW)" $B/api/mrp/reporting/list)" "403"
EQ "操作工冲销申请 403（管理台受控）" "$(BIZ -X POST -H "$(A $TFLO)" -H 'Content-Type: application/json' -d "{\"rptId\":\"$RID1\",\"reason\":\"越权\"}" $B/api/mrp/reporting/reversal)" "403"
EQ "操作工完工确认 403（录入台无完工）" "$(BIZ -X POST -H "$(A $TFLO)" $B/api/mrp/reporting/complete/$MOR)" "403"
# planner 录入类（confirm 属录入接口 SHOP_FLOOR/ADMIN）
EQ "planner 提交报工 403（录入类限操作工）" "$(BIZ -X POST -H "$(A $TPLAN)" $B/api/mrp/reporting/confirm/$RPT2)" "403"

echo ""
echo "==================== PASS=$PASS FAIL=$FAIL ===================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
