#!/bin/zsh
# add-mrp-demand-planning 全链路 API 冒烟（任务 9.3）
# 链路：登录→真数据源造数（分类/SO/库存/ROP/MOQ/LT）→运行三范围→净算留痕→EXCESS/ROP/MOQ/逾期→
#       互斥→确认改量→取消→转正 PR/PMO→取代规则→异常处置→三角色矩阵（PLANNER/非计划员/ADMIN）
set -u
B=http://127.0.0.1:8090
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✘ $1"; }
C() { curl -s --noproxy '*' "$@"; }
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
EQ() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望[$3] 实际[$2]）"; fi; }
CONTAINS() { case "$2" in *"$3"*) ok "$1";; *) bad "$1（未包含[$3]，实际[$2]）";; esac; }
export MYSQL_PWD=root123
SQL() { docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --default-character-set=utf8mb4 -uroot -N -B erp -e "$1"; }

echo "== 0. 前置清理（可重跑） =="
SQL "
DELETE FROM erp_sd_so_line WHERE SO_ID IN (SELECT ID FROM erp_sd_so WHERE SO_NO LIKE 'SO-RPLAN%');
DELETE FROM erp_sd_so WHERE SO_NO LIKE 'SO-RPLAN%';
DELETE FROM erp_inv_stock WHERE ITEM_NAME LIKE '计划冒烟%';
DELETE FROM erp_proc_pr_line WHERE PR_ID IN (SELECT ID FROM erp_proc_requisition WHERE REQ_REASON LIKE 'MRP%');
DELETE FROM erp_ops_outbox WHERE IDEMPOTENCY_KEY LIKE 'PR-%';
DELETE FROM erp_proc_requisition WHERE REQ_REASON LIKE 'MRP%';
DELETE FROM erp_mrp_run;
DELETE FROM erp_mrp_suggestion;
DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '计划冒烟%';
DELETE FROM erp_mdm_item_category WHERE ID='cat-rsmoke';
INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX)
VALUES ('cat-rsmoke','RSK1','冒烟计划分类','RSK');" >/dev/null 2>&1 \
  && ok "清理完成（分类 RSK1 就位）" || bad "清理失败"

echo "== 1. 登录三角色 =="
TPLANNER=$(C -H 'Content-Type: application/json' -d '{"username":"planner-test","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TENG=$(C -H 'Content-Type: application/json' -d '{"username":"bom-eng","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TPLANNER" ] && [ -n "$TENG" ] && [ -n "$TADM" ] && ok "planner/eng/admin 登录" || bad "登录失败"

echo "== 2. 未认证 401 =="
EQ "GET /api/mrp/runs 401" "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' $B/api/mrp/runs)" "401"
EQ "POST /api/mrp/runs 401" "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST $B/api/mrp/runs)" "401"
EQ "GET /api/mrp/suggestions 401" "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' $B/api/mrp/suggestions)" "401"

echo "== 3. 真数据源造数（4 物料 + SO + 库存 + ROP/MOQ/LT） =="
mkitem() {
  C -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
    -d "{\"itemName\":\"计划冒烟$1\",\"categoryCode\":\"RSK1\",\"baseUnit\":\"PC\",\"materialGroup\":\"STRUCT\",\"purchaseType\":\"$2\",\"storageCondition\":\"NORMAL\",\"batchFlag\":\"0\",\"packingSpec\":\"箱\",\"dupNote\":\"需求计划冒烟物料，非业务重复数据\"}" \
    "$B/api/mdm/items?forceCreate=true" | J "d['data']['itemCode']"
}
I_NET=$(mkitem 净算件 BUY); I_EXC=$(mkitem 过量件 BUY); I_OVD=$(mkitem 逾期件 BUY); I_MKF=$(mkitem 自制件 MAKE)
if [ -z "$I_NET" ] || [ -z "$I_EXC" ] || [ -z "$I_OVD" ] || [ -z "$I_MKF" ]; then bad "建物料失败"; echo "== 结果: PASS=$PASS FAIL=$FAIL"; exit 1; fi
ok "物料 NET=$I_NET EXC=$I_EXC OVD=$I_OVD MKF=$I_MKF"
SQL "
INSERT INTO erp_sd_so (ID, SO_NO, CUSTOMER_ID, STATUS) VALUES
 ('so-rsk-1','SO-RPLAN-RSK1','CUST-RPLAN-TEST','CONFIRMED'),
 ('so-rsk-2','SO-RPLAN-RSK2','CUST-RPLAN-TEST','CONFIRMED'),
 ('so-rsk-3','SO-RPLAN-RSK3','CUST-RPLAN-TEST','CONFIRMED'),
 ('so-rsk-4','SO-RPLAN-RSK4','CUST-RPLAN-TEST','CONFIRMED');
INSERT INTO erp_sd_so_line (ID, SO_ID, LINE_NO, ITEM_CODE, QTY, SHIPPED_QTY, EXPECT_DELIVERY_DATE, LINE_STATUS) VALUES
 ('l1','so-rsk-1',1,'$I_NET',100,0,DATE_ADD(CURDATE(), INTERVAL 10 DAY),'OPEN'),
 ('l2','so-rsk-2',1,'$I_EXC',50,0,DATE_ADD(CURDATE(), INTERVAL 8 DAY),'OPEN'),
 ('l3','so-rsk-3',1,'$I_OVD',5,0,DATE_ADD(CURDATE(), INTERVAL 5 DAY),'OPEN'),
 ('l4','so-rsk-4',1,'$I_MKF',30,0,DATE_ADD(CURDATE(), INTERVAL 12 DAY),'OPEN');
INSERT INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, QTY, AVAILABLE_QTY) VALUES
 ('st-rsk-1','$I_EXC','计划冒烟过量',80,80),
 ('st-rsk-2','$I_NET','计划冒烟净算',30,30);
UPDATE erp_mdm_item SET ROP_QTY=50 WHERE ITEM_CODE='$I_NET';
UPDATE erp_mdm_item SET MIN_ORDER_QTY=50 WHERE ITEM_CODE='$I_OVD';
UPDATE erp_mdm_item SET LEAD_TIME_DAYS=30 WHERE ITEM_CODE='$I_OVD';" >/dev/null 2>&1 \
  && ok "SO×5 + 库存×2 + ROP/MOQ/LT 就位" || bad "造数失败"

echo "== 4. 运行（互斥 + 范围 + 统计） =="
SQL "INSERT INTO erp_mrp_run (ID,RUN_NO,SCOPE_TYPE,RUN_STATUS) VALUES ('run-mutex','RUN-MUTEX-X','FULL','RUNNING')" >/dev/null 2>&1
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{"scopeType":"CATEGORY","scopeValue":"RSK1"}' $B/api/mrp/runs)
EQ "互斥时运行 422" "$(echo $RES | J "d['code']")" "422"
CONTAINS "互斥提示" "$(echo $RES | J "d['message']")" "进行中"
SQL "DELETE FROM erp_mrp_run WHERE ID='run-mutex'" >/dev/null 2>&1

RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{"scopeType":"GROUP","scopeValue":"NO-SUCH-GRP"}' $B/api/mrp/runs)
EQ "空范围 422" "$(echo $RES | J "d['code']")" "422"
FAILED_N=$(SQL "SELECT COUNT(*) FROM erp_mrp_run WHERE RUN_STATUS='FAILED'")
EQ "失败回滚留 FAILED 痕" "$FAILED_N" "1"
SUG0=$(SQL "SELECT COUNT(*) FROM erp_mrp_suggestion")
EQ "失败无建议落库" "$SUG0" "0"

RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{"scopeType":"CATEGORY","scopeValue":"RSK1"}' $B/api/mrp/runs)
EQ "运行完成 200" "$(echo $RES | J "d['code']")" "200"
EQ "RUN_STATUS=DONE" "$(echo $RES | J "d['data']['runStatus']")" "DONE"
RUNNO=$(echo $RES | J "d['data']['runNo']")
CONTAINS "RUN_NO 按日流水前缀" "$RUNNO" "RUN-"
EQ "扫描数=4" "$(echo $RES | J "d['data']['statScanned']")" "4"
EQ "建议统计=3（EXCESS 不计）" "$(echo $RES | J "d['data']['statSuggested']")" "3"

echo "== 5. 净算留痕 / EXCESS / ROP / MOQ / 逾期 =="
NET_NET=$(SQL "SELECT NET_REQ FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_NET' AND TYPE='PURCHASE'")
EQ "净算留痕 (100+ROP50)−30=120" "$NET_NET" "120.0000"
IP=$(SQL "SELECT IN_PROCESS_QTY FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_NET' AND TYPE='PURCHASE'")
EQ "在制恒 0 桩" "$IP" "0.0000"
DEM=$(SQL "SELECT DEMAND_QTY FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_NET' AND TYPE='PURCHASE'")
EQ "需求留痕 = SO100 + ROP目标水位50" "$DEM" "150.0000"
EXC_TYPE=$(SQL "SELECT TYPE FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_EXC' AND TYPE='EXCESS'")
EQ "过量 → EXCESS 行" "$EXC_TYPE" "EXCESS"
EXC_NET=$(SQL "SELECT NET_REQ FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_EXC' AND TYPE='EXCESS'")
EQ "过量净为负" "$EXC_NET" "-30.0000"
MOQ_QTY=$(SQL "SELECT SUGGEST_QTY FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_OVD' AND TYPE='PURCHASE'")
EQ "MOQ 上调（净5→建议50）" "$MOQ_QTY" "50.0000"
MOQ_RMK=$(SQL "SELECT REMARK FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_OVD' AND TYPE='PURCHASE'")
CONTAINS "MOQ 备注" "$MOQ_RMK" "最小订购量"
OVD=$(SQL "SELECT OVERDUE_FLAG FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_OVD' AND TYPE='PURCHASE'")
EQ "逾期置位（LT30 vs +5d）" "$OVD" "1"
MKF_TYPE=$(SQL "SELECT TYPE FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_MKF' AND TYPE='PRODUCTION'")
EQ "自制件 → PRODUCTION" "$MKF_TYPE" "PRODUCTION"

echo "== 6. 异常视图（仅活跃态） =="
EXC_VIEW=$(C -H "Authorization: Bearer $TPLANNER" "$B/api/mrp/suggestions?exceptionsOnly=true")
CONTAINS "异常视图含过量件" "$(echo $EXC_VIEW | J "'$I_EXC' in [r['itemCode'] for r in d['data']]")" "True"
CONTAINS "异常视图含逾期件" "$(echo $EXC_VIEW | J "'$I_OVD' in [r['itemCode'] for r in d['data']]")" "True"

echo "== 7. 审核：确认改量 / 取消 / EXCESS 拒绝 =="
NET_ID=$(SQL "SELECT ID FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_NET' AND TYPE='PURCHASE'")
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{"confirmQty":60}' $B/api/mrp/suggestions/$NET_ID/confirm)
EQ "确认 200" "$(echo $RES | J "d['code']")" "200"
EQ "确认量 60" "$(echo $RES | J "d['data']['confirmQty']")" "60"
EQ "原建议量留痕=净120" "$(echo $RES | J "float(d['data']['origSuggestQty'])")" "120.0"
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{}' $B/api/mrp/suggestions/$NET_ID/confirm)
EQ "重复确认 422" "$(echo $RES | J "d['code']")" "422"
EXC_ID=$(SQL "SELECT ID FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_EXC' AND TYPE='EXCESS'")
RES=$(C -H "Authorization: Bearer $TPLANNER" -X POST $B/api/mrp/suggestions/$EXC_ID/confirm -H 'Content-Type: application/json' -d '{}')
EQ "EXCESS 拒绝确认 422" "$(echo $RES | J "d['code']")" "422"
OVD_ID=$(SQL "SELECT ID FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_OVD' AND TYPE='PURCHASE'")
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{"reason":"  "}' $B/api/mrp/suggestions/$OVD_ID/cancel)
EQ "取消缺原因 422" "$(echo $RES | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{"reason":"交期不可接受，暂缓采购"}' $B/api/mrp/suggestions/$OVD_ID/cancel)
EQ "取消成功" "$(echo $RES | J "d['code']")" "200"
EQ "取消状态落库" "$(SQL "SELECT STATUS FROM erp_mrp_suggestion WHERE ID='$OVD_ID'")" "CANCELLED"
CONTAINS "取消原因留痕" "$(SQL "SELECT CANCEL_REASON FROM erp_mrp_suggestion WHERE ID='$OVD_ID'")" "暂缓采购"

echo "== 8. 转正：停用阻断 → PR → 状态门 → PMO → 类型门 =="
SQL "UPDATE erp_mdm_item SET STATUS='0' WHERE ITEM_CODE='$I_NET'" >/dev/null 2>&1
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d "{\"ids\":[\"$NET_ID\"]}" $B/api/mrp/suggestions/convert-pr)
EQ "停用物料整批阻断 422" "$(echo $RES | J "d['code']")" "422"
CONTAINS "阻断透传语义" "$(echo $RES | J "d['message']")" "整批阻断"
EQ "阻断后状态不变" "$(SQL "SELECT STATUS FROM erp_mrp_suggestion WHERE ID='$NET_ID'")" "CONFIRMED"
SQL "UPDATE erp_mdm_item SET STATUS='1' WHERE ITEM_CODE='$I_NET'" >/dev/null 2>&1
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d "{\"ids\":[\"$NET_ID\"]}" $B/api/mrp/suggestions/convert-pr)
EQ "转正 200" "$(echo $RES | J "d['code']")" "200"
PRNO=$(echo $RES | J "d['data']['targetNo']")
CONTAINS "PR 单号前缀" "$PRNO" "PR-"
EQ "建议 CONVERTED" "$(SQL "SELECT STATUS FROM erp_mrp_suggestion WHERE ID='$NET_ID'")" "CONVERTED"
EQ "TARGET_NO=PR" "$(SQL "SELECT TARGET_NO FROM erp_mrp_suggestion WHERE ID='$NET_ID'")" "$PRNO"
EQ "PR 头落库（来源 MRP）" "$(SQL "SELECT SOURCE_TYPE FROM erp_proc_requisition WHERE PR_NO='$PRNO'")" "MRP"

MKF_ID=$(SQL "SELECT ID FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_MKF' AND TYPE='PRODUCTION'")
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d "{\"ids\":[\"$MKF_ID\"]}" $B/api/mrp/suggestions/convert-pr)
EQ "类型门：生产走采购转正 422" "$(echo $RES | J "d['code']")" "422"
CONTAINS "类型不符提示" "$(echo $RES | J "d['message']")" "类型不符"
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d "{\"ids\":[\"$MKF_ID\"]}" $B/api/mrp/suggestions/convert-mo)
EQ "未确认转 PMO 422（状态门）" "$(echo $RES | J "d['code']")" "422"
C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{}' $B/api/mrp/suggestions/$MKF_ID/confirm >/dev/null
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d "{\"ids\":[\"$MKF_ID\"]}" $B/api/mrp/suggestions/convert-mo)
EQ "转 PMO 200" "$(echo $RES | J "d['code']")" "200"
PMO=$(echo $RES | J "d['data']['targetNo']")
CONTAINS "PMO 单号格式" "$PMO" "PMO-"
EQ "建议 CONVERTED(PMO)" "$(SQL "SELECT STATUS FROM erp_mrp_suggestion WHERE ID='$MKF_ID'")" "CONVERTED"

echo "== 9. 重跑取代规则 =="
SQL "DELETE FROM erp_mrp_run WHERE RUN_STATUS='RUNNING'" >/dev/null 2>&1
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{"scopeType":"CATEGORY","scopeValue":"RSK1"}' $B/api/mrp/runs)
EQ "二次运行完成" "$(echo $RES | J "d['data']['runStatus']")" "DONE"
RUN2=$(echo $RES | J "d['data']['runNo']")
SUP=$(SQL "SELECT COUNT(*) FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_EXC' AND TYPE='EXCESS' AND STATUS='SUPERSEDED'")
EQ "旧活跃建议被取代" "$SUP" "1"
SUPBY=$(SQL "SELECT SUPERSEDED_BY_RUN FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_EXC' AND TYPE='EXCESS' AND STATUS='SUPERSEDED'")
EQ "记录取代运行号" "$SUPBY" "$RUN2"
EXC_VIEW2=$(C -H "Authorization: Bearer $TPLANNER" "$B/api/mrp/suggestions?exceptionsOnly=true")
ACTIVE_EXC=$(echo $EXC_VIEW2 | J "len([r for r in d['data'] if r['status'] not in ('PENDING','CONFIRMED')])")
EQ "异常视图不含历史态行" "$ACTIVE_EXC" "0"

echo "== 10. 异常处置 =="
EXC_NEW=$(SQL "SELECT ID FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_EXC' AND TYPE='EXCESS' AND STATUS='PENDING'")
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{"note":" "}' $B/api/mrp/suggestions/$EXC_NEW/handle)
EQ "处置缺备注 422" "$(echo $RES | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{"note":"已与采购确认推迟在途到货"}' $B/api/mrp/suggestions/$EXC_NEW/handle)
EQ "处置成功" "$(echo $RES | J "d['code']")" "200"
EQ "已处理标记" "$(SQL "SELECT HANDLED_FLAG FROM erp_mrp_suggestion WHERE ID='$EXC_NEW'")" "1"
CONTAINS "处理备注留痕" "$(SQL "SELECT HANDLED_NOTE FROM erp_mrp_suggestion WHERE ID='$EXC_NEW'")" "推迟在途"
NET_NEW=$(SQL "SELECT ID FROM erp_mrp_suggestion WHERE ITEM_CODE='$I_NET' AND TYPE='PURCHASE' AND STATUS IN ('PENDING','CONFIRMED') LIMIT 1")
if [ -n "$NET_NEW" ]; then
  RES=$(C -H "Authorization: Bearer $TPLANNER" -H 'Content-Type: application/json' -d '{"note":"随意"}' $B/api/mrp/suggestions/$NET_NEW/handle)
  EQ "非异常行拒绝处置 422" "$(echo $RES | J "d['code']")" "422"
fi

echo "== 11. 三角色矩阵 =="
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"scopeType":"FULL"}' $B/api/mrp/runs)
EQ "非计划员运行 403" "$(echo $RES | J "d['code']")" "403"
SQL "DELETE FROM erp_mrp_run WHERE RUN_STATUS='RUNNING'" >/dev/null 2>&1
RES=$(C -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"scopeType":"CATEGORY","scopeValue":"RSK1"}' $B/api/mrp/runs)
EQ "ADMIN 运行全通过" "$(echo $RES | J "d['data']['runStatus']")" "DONE"

echo "== 12. 运行历史 =="
RES=$(C -H "Authorization: Bearer $TPLANNER" $B/api/mrp/runs)
HAS=$(echo $RES | J "all(k in r for r in d['data'] for k in ['runNo','scopeType','statScanned','runBy','runStatus'])")
EQ "历史含六类字段" "$HAS" "True"

echo ""
echo "== 结果: PASS=$PASS FAIL=$FAIL =="
[ $FAIL -eq 0 ]
