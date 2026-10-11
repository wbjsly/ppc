#!/bin/zsh
# add-dispatch-management 全链路 API 冒烟（任务 9.1，参照 kitting-smoke 先例，可重跑）
# 链路：清理→账号→登录四角色→401→造数（物料/BOM/WC/工序/定额/路线/库存/双工单含缺料单）→
#       待派排序→生成（批量/防重）→列表→确认（403/通过+预扣）→取消（链式释放）→
#       L1 阻断+缺料派工申请审批→外协漏斗（403/候选/改派审批/PR 落库）→
#       负荷超限（调阈值）→日历班次（403+ADMIN 留痕）→到期未派通知（等调度一轮）→403 矩阵
# 依赖：后端 8090 启动建议带 --app.dispatch-due.sweep-ms=60000（步骤 13 只需等 ~65s）
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
export MYSQL_PWD=root123
SQL() { docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --default-character-set=utf8mb4 -uroot -N -B erp -e "$1" 2>/dev/null \
  || mysql -h127.0.0.1 -uroot -proot123 erp --default-character-set=utf8mb4 -N -B -e "$1"; }
A() { echo "Authorization: Bearer $1"; }

echo "== 0. 前置清理（dispatch 冒烟族，可重跑）="
SQL "
DELETE FROM erp_mrp_mo_shortage WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '派工冒烟%'));
DELETE FROM erp_mrp_mo_bom WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '派工冒烟%'));
DELETE FROM erp_mrp_mo_op WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '派工冒烟%'));
DELETE FROM erp_mrp_dispatch WHERE MO_NO LIKE 'MO-%' AND ID NOT IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE NOT LIKE 'RTSK%');
DELETE FROM erp_mrp_dispatch WHERE WC_CODE LIKE 'DPMWC%';
DELETE FROM erp_mrp_reservation WHERE MO_NO IN (SELECT MO_NO FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '派工冒烟%'));
DELETE FROM erp_mrp_mo_shortage WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'RTSK%');
DELETE FROM erp_mrp_mo_bom WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'RTSK%');
DELETE FROM erp_mrp_mo_op WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'RTSK%');
DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'RTSK%';
DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '派工冒烟%')
   OR PRODUCT_CODE NOT IN (SELECT ITEM_CODE FROM erp_mdm_item);
DELETE FROM erp_mrp_mo_shortage WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_mrp_mo_bom WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_mrp_mo_op WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE IN ('ShortageDispatch','OutsourceDispatch');
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('ShortageDispatch','OutsourceDispatch');
DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('DISPATCH_APPROVAL_RESULT','OUTSOURCE_PROGRESS','MO_DISPATCH_DUE');
DELETE l FROM erp_proc_pr_line l JOIN erp_proc_requisition p ON l.PR_ID=p.ID WHERE p.SOURCE_TYPE='OUTSOURCE';
DELETE FROM erp_proc_requisition WHERE SOURCE_TYPE='OUTSOURCE';
DELETE FROM erp_ops_outbox WHERE IDEMPOTENCY_KEY LIKE 'PR-%';
DELETE FROM erp_inv_stock WHERE ITEM_NAME LIKE '派工冒烟%';
DELETE FROM erp_mrp_op_wc_standard WHERE WC_CODE LIKE 'DPMWC%';
DELETE FROM erp_mrp_routing_op WHERE WC_CODE LIKE 'DPMWC%';
DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '派工冒烟%');
DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'DPMWC%';
DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'DPMOP%';
DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '派工冒烟%';
DELETE FROM erp_mrp_wc_shift WHERE WC_CODE LIKE 'DPMWC%';
DELETE FROM erp_admin_shift WHERE SHIFT_CODE IN ('DPMNIGHT','DPMHOUR');
DELETE FROM erp_mdm_item_category WHERE ID='cat-dpsk';
INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX)
VALUES ('cat-dpsk','DPSK','派工冒烟分类','DPSK');" >/dev/null 2>&1 \
  && ok "清理完成（DPSK 分类就位）" || bad "清理失败"

echo "== 1. 账号（复用 admin BCrypt） =="
AHASH=$(SQL "SELECT PASSWORD FROM erp_admin_user WHERE USERNAME='admin' LIMIT 1")
mkuser() {
  SQL "INSERT IGNORE INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, CREATE_BY)
       VALUES ('user-$1','$1','$AHASH','$2','1','smoke');
       INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) VALUES ('ur-$1','user-$1','$3');" >/dev/null 2>&1
}
mkuser dplanner  "派工计划员"  role-planner
mkuser dfloor    "派工车间主管" role-shop-floor
mkuser dplanmgr  "派工计划主管" role-plan-mgr
mkuser dviewer   "派工围观"    role-user
N=$(SQL "SELECT COUNT(*) FROM erp_admin_user WHERE USERNAME IN ('dplanner','dfloor','dplanmgr','dviewer')")
EQ "四账号就位" "$N" "4"

echo "== 2. 登录四角色 =="
TPLAN=$(C -H 'Content-Type: application/json' -d '{"username":"dplanner","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TFLO=$(C -H 'Content-Type: application/json' -d '{"username":"dfloor","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TMRG=$(C -H 'Content-Type: application/json' -d '{"username":"dplanmgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TVIEW=$(C -H 'Content-Type: application/json' -d '{"username":"dviewer","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TPLAN" ] && [ -n "$TFLO" ] && [ -n "$TMRG" ] && [ -n "$TADM" ] && [ -n "$TVIEW" ] \
  && ok "planner/floor/planmgr/admin/viewer 登录" || bad "登录失败"

echo "== 3. 401 矩阵（HTTP 层） =="
EQ "GET /mrp/dispatch/pending 401" "$(HTTP $B/api/mrp/dispatch/pending)" "401"
EQ "GET /mrp/dispatch/list 401" "$(HTTP $B/api/mrp/dispatch/list)" "401"
EQ "POST /mrp/dispatch/generate 401" "$(HTTP -X POST $B/api/mrp/dispatch/generate)" "401"
EQ "POST /mrp/dispatch/confirm/x 401" "$(HTTP -X POST $B/api/mrp/dispatch/confirm/x)" "401"
EQ "GET /mrp/capacity/loads 401" "$(HTTP "$B/api/mrp/capacity/loads?from=2026-10-11&to=2026-10-12")" "401"
EQ "PUT /system/calendar/2026-10-11 401" "$(HTTP -X PUT -H 'Content-Type: application/json' -d '{"isWorkday":"1"}' $B/api/system/calendar/2026-10-11)" "401"

echo "== 4. 造数（物料/BOM/WC/工序/定额/路线/库存） =="
mkitem() {
  C -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"itemName\":\"派工冒烟$1\",\"categoryCode\":\"DPSK\",\"baseUnit\":\"PC\",\"materialGroup\":\"STRUCT\",\"purchaseType\":\"$2\",\"storageCondition\":\"NORMAL\",\"batchFlag\":\"0\",\"packingSpec\":\"箱\",\"dupNote\":\"派工管理冒烟物料，非业务重复数据\"}" \
    "$B/api/mdm/items?forceCreate=true" | J "d['data']['itemCode']"
}
P1=$(mkitem 产品A MAKE); C1=$(mkitem 子件A BUY); P2=$(mkitem 产品B MAKE); C2=$(mkitem 子件B BUY)
[ -n "$P1" ] && [ -n "$C1" ] && [ -n "$P2" ] && [ -n "$C2" ] && ok "物料 P1=$P1 C1=$C1 P2=$P2 C2=$C2" || bad "建物料失败"
publishBom() {
  local id=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"head\":{\"parentItemCode\":\"$1\"},\"items\":[{\"itemCode\":\"$2\",\"qty\":1,\"lossRate\":0}]}" \
    $B/api/mrp/boms | J "d['data']['id']")
  SQL "UPDATE erp_mrp_bom SET STATUS='PUBLISHED', PUBLISH_BY='smoke', PUBLISH_AT=NOW() WHERE ID='$id';" >/dev/null
  echo "$id"
}
BID1=$(publishBom "$P1" "$C1"); BID2=$(publishBom "$P2" "$C2")
BB=$(SQL "SELECT COUNT(*) FROM erp_mrp_bom WHERE ID IN ('$BID1','$BID2') AND STATUS='PUBLISHED'")
EQ "两产品 BOM 发布" "$BB" "2"

# WC（内部 + 外协）/ 工序 / 定额 / 路线（直插 PUBLISHED——绕审批链，冒烟惯例）
SQL "
INSERT IGNORE INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, CAL_HOURS, STATUS)
 VALUES ('wc-dpm-in','DPMWC-IN','派工冒烟内部中心','INTERNAL',8,'1'),
        ('wc-dpm-in2','DPMWC-IN2','派工冒烟内部中心2','INTERNAL',8,'1'),
        ('wc-dpm-os','DPMWC-OS','派工冒烟外协中心','OUTSOURCED',8,'1');
UPDATE erp_mrp_work_center SET SUPPLIER_CODE='SUPFINTGT' WHERE WC_CODE='DPMWC-OS';
INSERT IGNORE INTO erp_mrp_operation (ID, OP_CODE, OP_NAME, STATUS)
 VALUES ('op-dpm1','DPMOP10','派工冒烟工序10','1'), ('op-dpm2','DPMOP20','派工冒烟工序20','1');
INSERT IGNORE INTO erp_mrp_op_wc_standard (ID, OP_CODE, WC_CODE, SETUP_HOURS, RUN_HOURS)
 VALUES ('std-dpm1i','DPMOP10','DPMWC-IN',1,4), ('std-dpm1i2','DPMOP10','DPMWC-IN2',1,4),
        ('std-dpm1o','DPMOP10','DPMWC-OS',1,6), ('std-dpm2i','DPMOP20','DPMWC-IN2',1,3),
        ('std-dpm2o','DPMOP20','DPMWC-OS',1,5);
INSERT IGNORE INTO erp_mrp_routing (ID, ITEM_CODE, ITEM_NAME, VERSION_MAJOR, VERSION_MINOR, STATUS, PUBLISH_BY, PUBLISH_AT)
 VALUES ('rt-dpm-p1','${P1:-X}','派工冒烟产品A',1,0,'PUBLISHED','smoke',NOW()),
        ('rt-dpm-p2','${P2:-X}','派工冒烟产品B',1,0,'PUBLISHED','smoke',NOW());
INSERT IGNORE INTO erp_mrp_routing_op (ID, ROUTING_ID, OP_SEQ, OP_CODE, OP_NAME, WC_CODE, WC_NAME, LEAD_TIME)
 VALUES ('rto-p1-10','rt-dpm-p1',10,'DPMOP10','派工冒烟工序10','DPMWC-IN','派工冒烟内部中心',0),
        ('rto-p1-20','rt-dpm-p1',20,'DPMOP20','派工冒烟工序20','DPMWC-IN2','派工冒烟内部中心2',0),
        ('rto-p2-10','rt-dpm-p2',10,'DPMOP10','派工冒烟工序10','DPMWC-IN2','派工冒烟内部中心2',0);" >/dev/null 2>&1
RTN=$(SQL "SELECT COUNT(*) FROM erp_mrp_routing WHERE STATUS='PUBLISHED' AND ITEM_CODE IN ('$P1','$P2')")
EQ "两路线直插发布" "$RTN" "2"
STDN=$(SQL "SELECT COUNT(*) FROM erp_mrp_op_wc_standard WHERE WC_CODE LIKE 'DPMWC%'")
EQ "定额 5 行（含外协 DPMOP10/20）" "$STDN" "5"
SQL "INSERT IGNORE INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, QTY, AVAILABLE_QTY) VALUES
     ('st-dpm-c1','$C1','派工冒烟子件A',9999,9999);" >/dev/null  # C2 不给库存 → P2 缺料

echo "== 5. 建双工单（P1 齐套 / P2 缺料）并置 RELEASED =="
mkmo() {
  C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
    -d "{\"head\":{\"productCode\":\"$1\",\"qty\":10,\"planStartDate\":\"$(date +%F)\",\"planEndDate\":\"$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F)\",\"priority\":$2}}" \
    $B/api/mrp/mos | J "d['data']['mo']['id']"
}
MO1=$(mkmo "$P1" 3); MO2=$(mkmo "$P2" 1)
[ -n "$MO1" ] && [ -n "$MO2" ] && ok "双工单 MO1=$MO1(优先3) MO2=$MO2(优先1)" || bad "建单失败"
OPN=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_op WHERE MO_ID IN ('$MO1','$MO2')")
EQ "工序快照生成（P1 两序 + P2 一序）" "$OPN" "3"
FLAG2=$(SQL "SELECT SHORTAGE_FLAG FROM erp_mrp_mo WHERE ID='$MO2'")
EQ "P2 创建即缺料打标" "$FLAG2" "1"
SQL "UPDATE erp_mrp_mo SET STATUS='RELEASED' WHERE ID IN ('$MO1','$MO2');" >/dev/null
RELN=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo WHERE ID IN ('$MO1','$MO2') AND STATUS='RELEASED'")
EQ "双工单 RELEASED" "$RELN" "2"

echo "== 6. 待派列表（排序 =="
PEND=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/pending")
PC=$(echo "$PEND" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")
[ "$PC" -ge 3 ] && ok "待派 ≥3 行（本族三序在列，实际 $PC——dev 库其他 Released 单不隔离）" || bad "待派仅 $PC 行"
FIRSTMO=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/pending" | python3 -c "import sys,json;print(json.load(sys.stdin)['data'][0]['moNo'])")
M2NO=$(SQL "SELECT MO_NO FROM erp_mrp_mo WHERE ID='$MO2'")
EQ "排序：priority 1 的 P2 在前" "$FIRSTMO" "$M2NO"

echo "== 7. 生成派工单（齐套单批量 + 防重；MO2 缺料留到步骤 9 被 L1 挡） =="
GEN2=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"moId\":\"$MO1\",\"opSeq\":10,\"wcCode\":null},{\"moId\":\"$MO1\",\"opSeq\":20,\"wcCode\":null}]}" \
  $B/api/mrp/dispatch/generate)
G2N=$(J "len(d['data'])" <<<"$GEN2")
G1ST=$(J "d['data'][0]['status']" <<<"$GEN2")
EQ "批量 2 序出 2 单" "$G2N" "2"
EQ "生成态 PENDING_CONFIRM" "$G1ST" "PENDING_CONFIRM"
DUP=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"moId\":\"$MO1\",\"opSeq\":10,\"wcCode\":null}]}" $B/api/mrp/dispatch/generate)
EQ "同工序重复生成 422" "$DUP" "422"
LISTN=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/list" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")
EQ "派工单列表 2 张" "$LISTN" "2"
M1NO=$(SQL "SELECT MO_NO FROM erp_mrp_mo WHERE ID='$MO1'")
GID10=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/list" | python3 -c "
import sys,json
rows=json.load(sys.stdin)['data']
print(next(r['id'] for r in rows if r['moNo']=='$M1NO' and r['opSeq']==10))")

echo "== 8. 确认（403→通过+预扣 / 取消链式释放） =="
C403=$(BIZ -X POST -H "$(A $TPLAN)" $B/api/mrp/dispatch/confirm/$GID10)
EQ "PLANNER 确认 403" "$C403" "403"
CONF=$(C -H "$(A $TFLO)" -X POST $B/api/mrp/dispatch/confirm/$GID10)
CST=$(J "d['data']['status']" <<<"$CONF")
EQ "SHOP_FLOOR 确认通过 RELEASED" "$CST" "RELEASED"
RSVN=$(SQL "SELECT COUNT(*) FROM erp_mrp_reservation WHERE MO_NO='$M1NO' AND STATUS='ACTIVE'")
[ "$RSVN" -ge 1 ] && ok "确认触发预扣（活动行 $RSVN）" || bad "无预扣行"
C422=$(BIZ -X POST -H "$(A $TFLO)" $B/api/mrp/dispatch/confirm/$GID10)
EQ "重复确认 422" "$C422" "422"
CN422=$(BIZ -X PUT -H "$(A $TFLO)" -H 'Content-Type: application/json' -d '{"reason":""}' $B/api/mrp/dispatch/cancel/$GID10)
EQ "取消无原因 422" "$CN422" "422"
CN=$(C -H "$(A $TFLO)" -X PUT -H 'Content-Type: application/json' \
  -d '{"reason":"冒烟取消验证"}' $B/api/mrp/dispatch/cancel/$GID10)
CNST=$(J "d['data']['status']" <<<"$CN")
EQ "取消后 CANCELLED" "$CNST" "CANCELLED"
RSV1=$(SQL "SELECT COUNT(*) FROM erp_mrp_reservation WHERE MO_NO='$M1NO' AND STATUS='ACTIVE'")
EQ "首张取消保留预扣（20 序派工单仍在，D2 多工序保留锁）" "$RSV1" "1"
OPST=$(SQL "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID='$MO1' AND OP_SEQ=10")
EQ "工序回 PENDING" "$OPST" "PENDING"
# （末张取消释放放步骤 11 后——20 序须先确认进负荷账）

echo "== 9. L1 阻断 + 缺料派工申请审批 =="
BLK=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"moId\":\"$MO2\",\"opSeq\":10,\"wcCode\":null}]}" $B/api/mrp/dispatch/generate)
EQ "缺料工单生成被 L1 阻断 422" "$BLK" "422"
BLKMSG=$(C -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"moId\":\"$MO2\",\"opSeq\":10,\"wcCode\":null}]}" $B/api/mrp/dispatch/generate | J "d['message']")
CONTAINS "阻断消息含 C-4.5-11 与缺料清单指引" "$BLKMSG" "C-4.5-11"
APP=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$MO2\",\"opSeq\":10}" $B/api/mrp/dispatch/shortage-apply)
APNO=$(J "d['data']['apprNo']" <<<"$APP")
[ -n "$APNO" ] && ok "缺料派工申请提交 $APNO" || bad "申请失败：$APP"
APPD=$(BIZ -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$MO2\",\"opSeq\":10}" $B/api/mrp/dispatch/shortage-apply)
EQ "同工序重复申请 422" "$APPD" "422"
TID=$(C -H "$(A $TMRG)" $B/api/qms/approvals/todo | python3 -c "
import sys,json
for t in json.load(sys.stdin)['data']:
    if t.get('apprNo')=='$APNO': print(t['taskId']); break")
[ -n "$TID" ] && ok "计划主管待办可见" || bad "待办缺任务"
PASSED=$(C -H "$(A $TMRG)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TID\",\"opinion\":\"同意缺料先行\"}" $B/api/qms/approvals/pass | J "d['code']")
EQ "签核通过（业务码 200）" "$PASSED" "200"
SF=$(SQL "SELECT SHORTAGE_FIRST_FLAG FROM erp_mrp_dispatch WHERE MO_ID='$MO2' ORDER BY CREATE_DATE DESC LIMIT 1")
EQ "生成缺料先行派工单" "$SF" "1"
NR=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='DISPATCH_APPROVAL_RESULT'")
[ "$NR" -ge 1 ] && ok "审批结果通知已推（$NR 条）" || bad "无审批结果通知"

echo "== 10. 外协漏斗（403→候选→改派审批→PR 落库） =="
OSWC=$(SQL "SELECT WC_CODE FROM erp_mrp_work_center WHERE WC_CODE='DPMWC-OS'")
EQ "外协 WC 就位" "$OSWC" "DPMWC-OS"
OF403=$(BIZ -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d '{"dispatchId":"x","targetWcCode":"DPMWC-OS"}' $B/api/mrp/dispatch/outsource-apply)
EQ "SHOP_FLOOR 外协申请 403" "$OF403" "403"
# 用 P1 的派工单（DPMWC-IN 上）改派外协
DPID=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/list" | python3 -c "
import sys,json
rows=json.load(sys.stdin)['data']
print(next(r['id'] for r in rows if r['moNo']=='$M1NO' and r['opSeq']==20 and r['status']!='CANCELLED'))")
OAPP=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"dispatchId\":\"$DPID\",\"targetWcCode\":\"DPMWC-OS\"}" $B/api/mrp/dispatch/outsource-apply)
ONO=$(J "d['data']['apprNo']" <<<"$OAPP")
[ -n "$ONO" ] && ok "外协改派申请提交 $ONO" || bad "外协申请失败：$OAPP"
# 候选（按派工单解析工序）
CAND=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/outsource-candidates?dispatchId=$DPID")
CANDN=$(J "len(d['data'])" <<<"$CAND")
[ "$CANDN" -ge 1 ] && ok "外协候选产出 $CANDN 个（含 DPMWC-OS）" || bad "候选为空：$CAND"
CONTAINS "候选含外协 WC" "$CAND" "DPMWC-OS"
PTY=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/outsource-candidates?dispatchId=$DPID" \
  | python3 -c "
import sys,json
for r in json.load(sys.stdin)['data']:
    if r['wcCode']=='DPMWC-OS': print(r.get('priceText')); break")
[ -n "$PTY" ] && ok "协议价文案字段在（$PTY）" || bad "无 priceText"
OTID=$(C -H "$(A $TMRG)" $B/api/qms/approvals/todo | python3 -c "
import sys,json
for t in json.load(sys.stdin)['data']:
    if t.get('apprNo')=='$ONO': print(t['taskId']); break")
[ -n "$OTID" ] && ok "外协审批待办可见" || bad "外协待办缺任务"
OPASS=$(C -H "$(A $TMRG)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$OTID\",\"opinion\":\"同意外协\"}" $B/api/qms/approvals/pass | J "d['code']")
EQ "外协签核通过" "$OPASS" "200"
CHG=$(SQL "SELECT CONCAT(WC_CODE,'|',OUTSOURCE_FLAG,'|',IFNULL(PR_NO,'')) FROM erp_mrp_dispatch WHERE ID='$DPID'")
WCNOW=$(echo "$CHG" | cut -d'|' -f1); OSF=$(echo "$CHG" | cut -d'|' -f2); PRNO=$(echo "$CHG" | cut -d'|' -f3)
EQ "改派外协 WC" "$WCNOW" "DPMWC-OS"
EQ "外协标记" "$OSF" "1"
[ -n "$PRNO" ] && ok "审批通过自动生 PR $PRNO（回联 PENDING_PR）" || bad "未生 PR"
PRH=$(SQL "SELECT CONCAT(SOURCE_TYPE,'|',STATUS) FROM erp_proc_requisition WHERE PR_NO='$PRNO'")
EQ "PR 头 SOURCE=OUTSOURCE" "$(echo "$PRH" | cut -d'|' -f1)" "OUTSOURCE"
EQ "PR 状态 PENDING_CONFIRM（既有漏斗入口）" "$(echo "$PRH" | cut -d'|' -f2)" "PENDING_CONFIRM"
PRSRC=$(SQL "SELECT SOURCE_DOC_NO FROM erp_proc_pr_line l JOIN erp_proc_requisition p ON l.PR_ID=p.ID WHERE p.PR_NO='$PRNO' LIMIT 1")
DSNO=$(SQL "SELECT DISPATCH_NO FROM erp_mrp_dispatch WHERE ID='$DPID'")
EQ "行 sourceDocNo=派工单号（回联锚点）" "$PRSRC" "$DSNO"

echo "== 11. 负荷超限（确认 20 序入账 + 绑 1h 班次造真实超限——阈值改由单测覆盖，SysParam 缓存经 setValue 才失效故不走 SQL 改） =="
GID20=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/list" | python3 -c "
import sys,json
rows=json.load(sys.stdin)['data']
print(next(r['id'] for r in rows if r['moNo']=='$M1NO' and r['opSeq']==20))")
# 20 序单已在外协 OS 上（步骤 10 改派），确认其入负荷账（4h）
CONF20=$(C -H "$(A $TFLO)" -X POST $B/api/mrp/dispatch/confirm/$GID20 | J "d['data']['status']")
EQ "确认 20 序入负荷账" "$CONF20" "RELEASED"
# 默认口径：4h / 8h = 50%，不超 110
LDS0=$(C -H "$(A $TPLAN)" "$B/api/mrp/capacity/loads?from=$(date +%F)&to=$(date -v+3d +%F 2>/dev/null || date -d '+3 days' +%F)&wcCode=DPMWC-OS")
ASGN0=$(J "sum(r['assignedHours'] for r in d['data'])" <<<"$LDS0")
OVER0=$(J "sum(1 for r in d['data'] if r['overLimit'])" <<<"$LDS0")
EQ "默认口径占用 $ASGN0 h 不超限（阈值 110）" "$OVER0" "0"
# 绑 1h 班次 → 可用 1h → 4h/1h=400% > 110 → overLimit（班次影响负荷的端到端验证）
HOUR=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"shiftCode":"DPMHOUR","shiftName":"派工冒烟一小时班","startTime":"08:00","endTime":"09:00","hours":1,"status":"1"}' \
  $B/api/system/calendar/shifts | J "d['data']['shiftCode']")
EQ "建 1h 班次 DPMHOUR" "$HOUR" "DPMHOUR"
BIND=$(C -H "$(A $TADM)" -X PUT -H 'Content-Type: application/json' \
  -d '{"wcCode":"DPMWC-OS","shiftCode":"DPMHOUR"}' $B/api/system/calendar/wc-shifts | J "d['data']['shiftCode']")
EQ "绑定 DPMWC-OS → 1h 班" "$BIND" "DPMHOUR"
LDS=$(C -H "$(A $TPLAN)" "$B/api/mrp/capacity/loads?from=$(date +%F)&to=$(date -v+3d +%F 2>/dev/null || date -d '+3 days' +%F)&wcCode=DPMWC-OS")
OVER=$(J "max((r['loadRate'] for r in d['data']), default=0)" <<<"$LDS")
OVN=$(J "sum(1 for r in d['data'] if r['overLimit'])" <<<"$LDS")
[ "$OVN" -ge 1 ] && ok "绑 1h 班后超限（峰值负荷 $OVER%）" || bad "无超限行（峰值 $OVER%）：$LDS"
# 解绑恢复默认白班 → 50% 不再超限
UNB=$(SQL "SELECT ID FROM erp_mrp_wc_shift WHERE WC_CODE='DPMWC-OS'")
[ -n "$UNB" ] && C -H "$(A $TADM)" -X DELETE "$B/api/system/calendar/wc-shifts/$UNB" >/dev/null
LDS2=$(C -H "$(A $TPLAN)" "$B/api/mrp/capacity/loads?from=$(date +%F)&to=$(date -v+3d +%F 2>/dev/null || date -d '+3 days' +%F)&wcCode=DPMWC-OS")
OVER2=$(J "sum(1 for r in d['data'] if r['overLimit'])" <<<"$LDS2")
EQ "解绑回默认白班后无超限" "$OVER2" "0"
# 末张（20 序，已确认）取消 → 释放预扣（D2 完整链）
GID20=$(C -H "$(A $TPLAN)" "$B/api/mrp/dispatch/list" | python3 -c "
import sys,json
rows=json.load(sys.stdin)['data']
print(next(r['id'] for r in rows if r['moNo']=='$M1NO' and r['opSeq']==20))")
C20=$(C -H "$(A $TFLO)" -X PUT -H 'Content-Type: application/json' \
  -d '{"reason":"冒烟取消末张"}' $B/api/mrp/dispatch/cancel/$GID20 | J "d['data']['status']")
EQ "末张取消 CANCELLED" "$C20" "CANCELLED"
RSV0=$(SQL "SELECT COUNT(*) FROM erp_mrp_reservation WHERE MO_NO='$M1NO' AND STATUS='ACTIVE'")
EQ "末张取消释放预扣（活动行 0）" "$RSV0" "0"

echo "== 12. 日历/班次（PLANNER 写 403 → ADMIN 写留痕） =="
D1=$(date -v+40d +%F 2>/dev/null || date -d '+40 days' +%F)
CAL403=$(BIZ -X PUT -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d '{"isWorkday":"0","remark":"冒烟"}' $B/api/system/calendar/$D1)
EQ "PLANNER 写日历 403" "$CAL403" "403"
CALW=$(C -H "$(A $TADM)" -X PUT -H 'Content-Type: application/json' \
  -d '{"isWorkday":"0","remark":"派工冒烟休息"}' $B/api/system/calendar/$D1 | J "d['data']['isWorkday']")
EQ "ADMIN 写日历成功（休息日）" "$CALW" "0"
CALR=$(SQL "SELECT UPDATE_BY FROM erp_admin_work_calendar WHERE CAL_DATE='$D1'")
[ -n "$CALR" ] && ok "留痕维护人 $CALR" || bad "无留痕"
SH403=$(BIZ -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d '{"shiftCode":"DPMNIGHT","hours":8}' $B/api/system/calendar/shifts)
EQ "PLANNER 建班次 403" "$SH403" "403"
SHW=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"shiftCode":"DPMNIGHT","shiftName":"派工冒烟夜班","startTime":"20:00","endTime":"04:00","hours":8,"status":"1"}' \
  $B/api/system/calendar/shifts | J "d['data']['shiftCode']")
EQ "ADMIN 建班次 DPMNIGHT" "$SHW" "DPMNIGHT"

echo "== 13. 到期未派通知（等调度一轮 ≤65s；后端 --app.dispatch-due.sweep-ms=60000） =="
# MO1 的 20 序未派 + planStartDate=今日 → 命中；MO2 已有活动派工（10 序已派）但 无其他 PENDING？
# MO2 的 10 序已取消回 PENDING → 也命中；两单都推
echo "  … 等待调度器（65s）…"
sleep 65
DUEN=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='MO_DISPATCH_DUE'")
[ "$DUEN" -ge 2 ] && ok "到期未派通知产生（$DUEN 条，计划域两方×工单）" || bad "无到期通知（$DUEN）——确认 sweep-ms≤60000"
# 日节流：直接再触发一轮（用 SQL 改短 fixedDelay 不现实——断言 bizId 含今日）
DUEDAY=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='MO_DISPATCH_DUE' AND BIZ_ID LIKE '%$(date +%Y%m%d)'")
[ "$DUEDAY" -eq "$DUEN" ] && ok "bizId 带当日日键（日节流基础）" || bad "日键异常"

echo "== 14. 403 矩阵（业务码） =="
EQ "viewer 查待派 403" "$(BIZ -H "$(A $TVIEW)" $B/api/mrp/dispatch/pending)" "403"
EQ "viewer 查列表 403" "$(BIZ -H "$(A $TVIEW)" $B/api/mrp/dispatch/list)" "403"
EQ "viewer 查候选 403" "$(BIZ -H "$(A $TVIEW)" "$B/api/mrp/dispatch/outsource-candidates?opCode=DPMOP10")" "403"
EQ "viewer 写日历 403" "$(BIZ -X PUT -H "$(A $TVIEW)" -H 'Content-Type: application/json' -d '{"isWorkday":"1"}' $B/api/system/calendar/$D1)" "403"
EQ "planner 外协申请目标非外协 422" "$(BIZ -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d "{\"dispatchId\":\"$DPID\",\"targetWcCode\":\"DPMWC-IN\"}" $B/api/mrp/dispatch/outsource-apply)" "422"
# 通知中心读取（三视图数据源）
NRD=$(C -H "$(A $TPLAN)" $B/api/notices | J "len(d['data'])")
[ "$NRD" -ge 1 ] && ok "planner 通知中心可读（$NRD 条）" || bad "通知中心空"

echo ""
echo "==================== PASS=$PASS FAIL=$FAIL ===================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
