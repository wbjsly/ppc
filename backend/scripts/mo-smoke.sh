#!/bin/zsh
# add-work-order-management 全链路 API 冒烟（任务 8.3）
# 链路：登录→造数（已发布 BOM + PMO 建议 + SO）→手工建单七卡控→PMO 幂等→非法迁移→
#       提交/403/通过/驳回→释放缺料→挂起恢复取消→拆分→完工关闭→in-process 契约→三角色矩阵
set -u
B=http://127.0.0.1:8090
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✘ $1"; }
C() { curl -s --noproxy '*' "$@"; }
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
EQ() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望[$3] 实际[$2]）"; fi; }
CONTAINS() { case "$2" in *"$3"*) ok "$1";; *) bad "$1（未包含[$3]，实际[$2]）";; esac; }
HTTP() { curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$@"; }
export MYSQL_PWD=root123
SQL() { docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --default-character-set=utf8mb4 -uroot -N -B erp -e "$1"; }

echo "== 0. 前置清理（可重跑）="
SQL "
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='MoApprove';
DELETE FROM erp_sys_approval WHERE BIZ_TYPE='MoApprove';
DELETE FROM erp_mrp_mo_shortage WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'MSMK%');
DELETE FROM erp_mrp_mo_bom WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'MSMK%');
DELETE FROM erp_mrp_mo_op WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'MSMK%');
DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE LIKE 'MSMK%';
DELETE FROM erp_mrp_mo_shortage WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_mrp_mo_bom WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_mrp_mo_op WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_sd_so_line WHERE SO_ID IN (SELECT ID FROM erp_sd_so WHERE SO_NO LIKE 'SO-RMSK%');
DELETE FROM erp_sd_so WHERE SO_NO LIKE 'SO-RMSK%';
DELETE FROM erp_mrp_suggestion WHERE RUN_ID IN (SELECT ID FROM erp_mrp_run WHERE RUN_NO LIKE 'RUN-RMSK%');
DELETE FROM erp_mrp_run WHERE RUN_NO LIKE 'RUN-RMSK%';
DELETE FROM erp_mrp_bom_item WHERE BOM_ID IN (SELECT ID FROM erp_mrp_bom WHERE PARENT_ITEM_CODE LIKE 'MSMK%');
DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE LIKE 'MSMK%';
DELETE FROM erp_mrp_bom_item WHERE BOM_ID NOT IN (SELECT ID FROM erp_mrp_bom);
DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'MSMK%';
DELETE FROM erp_mdm_item_category WHERE ID='cat-rmsk';
INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX)
VALUES ('cat-rmsk','RMSK','工单冒烟分类','RMSK');" >/dev/null 2>&1 \
  && ok "清理完成（分类 RMSK 就位）" || bad "清理失败"

echo "== 1. 登录三角色 =="
TPLAN=$(C -H 'Content-Type: application/json' -d '{"username":"planner-test","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TMGR=$(C -H 'Content-Type: application/json' -d '{"username":"plan-mgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TVIEW=$(C -H 'Content-Type: application/json' -d '{"username":"bom-viewer","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TPLAN" ] && [ -n "$TMGR" ] && [ -n "$TADM" ] && [ -n "$TVIEW" ] \
  && ok "planner/plan-mgr/admin/viewer 登录" || bad "登录失败"

echo "== 2. 未认证 401 =="
EQ "GET /api/mrp/mos 401" "$(HTTP $B/api/mrp/mos)" "401"
EQ "POST /api/mrp/mos 401" "$(HTTP -X POST $B/api/mrp/mos)" "401"
EQ "POST submit 401" "$(HTTP -X POST $B/api/mrp/mos/x/submit)" "401"

echo "== 3. 造数（产品+子件+已发布 BOM + PMO 建议 + SO）="
mkitem() {
  C -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
    -d "{\"itemName\":\"工单冒烟$1\",\"categoryCode\":\"RMSK\",\"baseUnit\":\"PC\",\"materialGroup\":\"STRUCT\",\"purchaseType\":\"$2\",\"storageCondition\":\"NORMAL\",\"batchFlag\":\"0\",\"packingSpec\":\"箱\",\"dupNote\":\"工单管理冒烟物料，非业务重复数据\"}" \
    "$B/api/mdm/items?forceCreate=true" | J "d['data']['itemCode']"
}
P1=$(mkitem 产品 MAKE); C1=$(mkitem 子件 BUY); P2=$(mkitem 超交产品 MAKE); P3=$(mkitem 无BOM产品 MAKE)
P4=$(mkitem PMO产品 MAKE); P5=$(mkitem 驳回链产品 MAKE)
[ -n "$P1" ] && [ -n "$P4" ] && [ -n "$P5" ] && ok "物料 P1=$P1 C1=$C1 P2=$P2 P4=$P4 P5=$P5" || bad "建物料失败"
# 发布 BOM（造数：create 后 JDBC 直推 PUBLISHED——BOM 无 publish HTTP 端点，发布走审批链，
# 冒烟不值得为此绕整条审批；快照读取只认 STATUS='PUBLISHED'）——P1/P2 先发（P3 留作无 BOM 断言）
publishBom() {
  local id=$(C -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
    -d "{\"head\":{\"parentItemCode\":\"$1\"},\"items\":[{\"itemCode\":\"$C1\",\"qty\":2,\"lossRate\":0.05}]}" \
    $B/api/mrp/boms | J "d['data']['id']")
  SQL "UPDATE erp_mrp_bom SET STATUS='PUBLISHED', PUBLISH_BY='smoke', PUBLISH_AT=NOW() WHERE ID='$id';" >/dev/null
  echo "$id"
}
BID=$(publishBom "$P1"); publishBom "$P2" >/dev/null; publishBom "$P4" >/dev/null; publishBom "$P5" >/dev/null
BB=$(SQL "SELECT STATUS FROM erp_mrp_bom WHERE ID='$BID'")
EQ "BOM 发布就绪（P1/P2/P4/P5，P3 留作无 BOM 断言）" "$BB" "PUBLISHED"
# PMO 建议
RID="run-rmsk-$(date +%s)"
SQL "INSERT INTO erp_mrp_run (ID, RUN_NO, SCOPE_TYPE, RUN_STATUS) VALUES ('$RID','RUN-RMSK-1','FULL','DONE');" >/dev/null
SID="sug-rmsk-1"
SQL "INSERT INTO erp_mrp_suggestion (ID, RUN_ID, ITEM_CODE, TYPE, NET_REQ, SUGGEST_QTY, STATUS, TARGET_NO, CONFIRM_QTY)
     VALUES ('$SID','$RID','$P4','PRODUCTION',20,20,'CONVERTED','PMO-RMSK-0001',20);" >/dev/null
# SO（超交源）
SQL "INSERT INTO erp_sd_so (ID, SO_NO, CUSTOMER_ID, STATUS) VALUES ('so-rmsk','SO-RMSK-1','CUST-X','CONFIRMED');
     INSERT INTO erp_sd_so_line (ID, SO_ID, LINE_NO, ITEM_CODE, QTY, SHIPPED_QTY, EXPECT_DELIVERY_DATE, LINE_STATUS)
     VALUES ('so-rmsk-l','so-rmsk',1,'$P2',100,0,DATE_ADD(CURDATE(), INTERVAL 10 DAY),'OPEN');" >/dev/null
ok "PMO 建议 + SO(100) 就绪"

FUT=$(date -v+7d +%F); PAS=$(date -v-1d +%F)

echo "== 4. 手工建单卡控（FR-4.5-3-2 / C-4.5-06 / BR-4.5-15 / 唯一在途）="
mkmo() { # product qty endDate [token]
  C -H "Authorization: Bearer ${4:-$TPLAN}" -H 'Content-Type: application/json' \
    -d "{\"head\":{\"productCode\":\"$1\",\"qty\":\"$2\",\"planStartDate\":\"$(date +%F)\",\"planEndDate\":\"$3\"}}" \
    $B/api/mrp/mos
}
RES=$(mkmo "$P1" 10 "$FUT")
MOID=$(echo "$RES" | J "d['data']['mo']['id']"); MONO=$(echo "$RES" | J "d['data']['mo']['moNo']")
WARN=$(echo "$RES" | J "' '.join(d['data']['warnings'])")
EQ "建单成功状态 PLANNED" "$(echo "$RES" | J "d['data']['mo']['status']")" "PLANNED"
CONTAINS "MO_NO 流水前缀 MO-" "$MONO" "MO-"
EQ "缺料预检打标" "$(echo "$RES" | J "d['data']['mo']['shortageFlag']")" "1"
CONTAINS "无路线警告（提示放行）" "$WARN" "无已发布工艺路线"
LINE1=$(SQL "SELECT ITEM_CODE FROM erp_mrp_mo_bom WHERE MO_ID='$MOID' LIMIT 1")
EQ "BOM 快照行落库" "$LINE1" "$C1"
RES=$(mkmo "$P1" 5 "$PAS")
EQ "完工日期过去 422" "$(echo "$RES" | J "d['code']")" "422"
CONTAINS "C-4.5-06 文案" "$(echo "$RES" | J "d['message']")" "早于当前日期"
RES=$(mkmo "$P1" 0 "$FUT")
EQ "数量为零 422" "$(echo "$RES" | J "d['code']")" "422"
RES=$(mkmo "$P1" 5 "$FUT")
EQ "唯一在途 422" "$(echo "$RES" | J "d['code']")" "422"
CONTAINS "在途文案" "$(echo "$RES" | J "d['message']")" "在途工单"
RES=$(mkmo "$P3" 5 "$FUT")
EQ "无已发布 BOM 422" "$(echo "$RES" | J "d['code']")" "422"
RES=$(mkmo "$P2" 110 "$FUT")
EQ "超交 5% 422" "$(echo "$RES" | J "d['code']")" "422"
CONTAINS "BR-4.5-15 文案" "$(echo "$RES" | J "d['message']")" "超交"
RES=$(mkmo "$P2" 105 "$FUT")
EQ "超交边界 105 放行" "$(echo "$RES" | J "d['code']")" "200"

echo "== 5. PMO 入口（D6 幂等回写）="
RES=$(C -H "Authorization: Bearer $TPLAN" -H 'Content-Type: application/json' \
  -d "{\"suggestId\":\"$SID\"}" $B/api/mrp/mos/from-pmo)
PMO_MO=$(echo "$RES" | J "d['data']['mo']['moNo']")
EQ "PMO 建单状态" "$(echo "$RES" | J "d['data']['mo']['status']")" "PLANNED"
EQ "PLANNED_MO_NO 留痕" "$(echo "$RES" | J "d['data']['mo']['plannedMoNo']")" "PMO-RMSK-0001"
EQ "MO_NO 回写建议行" "$(SQL "SELECT MO_NO FROM erp_mrp_suggestion WHERE ID='$SID'")" "$PMO_MO"
CAND=$(C -H "Authorization: Bearer $TPLAN" $B/api/mrp/mos/candidate-pmos | J "len([c for c in d['data'] if c['id']=='$SID'])")
EQ "已关联 PMO 不再可选" "$CAND" "0"
RES=$(C -H "Authorization: Bearer $TPLAN" -H 'Content-Type: application/json' \
  -d "{\"suggestId\":\"$SID\"}" $B/api/mrp/mos/from-pmo)
EQ "重复建单 422" "$(echo "$RES" | J "d['code']")" "422"

echo "== 6. 非法状态迁移（白名单）="
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MOID/release)
EQ "PLANNED 直接释放 422" "$(echo "$RES" | J "d['code']")" "422"
CONTAINS "非法迁移文案" "$(echo "$RES" | J "d['message']")" "仅已确认状态可释放"

echo "== 7. 审批：提交/重复/越权 403/通过/驳回 =="
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MOID/submit)
EQ "提交 → PENDING" "$(echo "$RES" | J "d['data']['status']")" "PENDING"
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MOID/submit)
EQ "重复提交 422" "$(echo "$RES" | J "d['code']")" "422"
TID=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='MoApprove' AND a.BIZ_ID='$MOID' AND t.STATUS='ACTIVE'")
EQ "HTTP 层 PLANNER 签署 403" "$(HTTP -X POST -H "Authorization: Bearer $TPLAN" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TID\",\"opinion\":\"我想通过\"}" $B/api/qms/approvals/pass)" "403"
RES=$(C -H "Authorization: Bearer $TMGR" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TID\",\"opinion\":\"同意发布\"}" $B/api/qms/approvals/pass)
EQ "计划主管通过 → APPROVED" "$(echo "$RES" | J "d['data']['status']")" "APPROVED"
EQ "工单 → CONFIRMED（回调链）" "$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$MOID'")" "CONFIRMED"
EQ "审批人留痕" "$(SQL "SELECT IF(APPROVE_BY IS NOT NULL AND APPROVE_BY<>'','yes','no') FROM erp_mrp_mo WHERE ID='$MOID'")" "yes"
# 驳回链
RES=$(mkmo "$P5" 6 "$FUT"); MO3ID=$(echo "$RES" | J "d['data']['mo']['id']")
C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MO3ID/submit >/dev/null
TID3=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='MoApprove' AND a.BIZ_ID='$MO3ID' AND t.STATUS='ACTIVE'")
C -H "Authorization: Bearer $TMGR" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TID3\",\"reason\":\"数量需复核\"}" $B/api/qms/approvals/reject >/dev/null
EQ "驳回 → 回 PLANNED" "$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$MO3ID'")" "PLANNED"
EQ "驳回意见回填" "$(SQL "SELECT REJECT_REASON FROM erp_mrp_mo WHERE ID='$MO3ID'")" "数量需复核"
C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MO3ID/cancel -H 'Content-Type: application/json' -d '{"reason":"冒烟完结"}' >/dev/null
EQ "PLANNED 取消（白名单）" "$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$MO3ID'")" "CANCELLED"

echo "== 8. 释放 + 缺料清单（FR-4.5-3-6 / L2496）="
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MOID/release)
EQ "释放 → RELEASED" "$(echo "$RES" | J "d['data']['status']")" "RELEASED"
EQ "释放后缺料标记" "$(echo "$RES" | J "d['data']['shortageFlag']")" "1"
RES=$(C -H "Authorization: Bearer $TPLAN" $B/api/mrp/mos/$MOID/shortages)
EQ "缺料行数 1" "$(echo "$RES" | J "len(d['data'])")" "1"
REQ=$(echo "$RES" | J "float(d['data'][0]['reqQty'])")
python3 -c "import sys; sys.exit(0 if abs($REQ-21.0)<0.01 else 1)" && ok "需求=10×2×1.05=21" || bad "需求错：$REQ"
EQ "释放留痕" "$(SQL "SELECT IF(RELEASE_BY IS NOT NULL AND RELEASE_BY<>'','yes','no') FROM erp_mrp_mo WHERE ID='$MOID'")" "yes"

echo "== 9. 挂起/恢复/取消 =="
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MOID/hold -H 'Content-Type: application/json' -d '{"reason":"  "}')
EQ "缺挂起原因 422" "$(echo "$RES" | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MOID/hold -H 'Content-Type: application/json' -d '{"reason":"设备检修"}')
EQ "挂起 → HOLD" "$(echo "$RES" | J "d['data']['status']")" "HOLD"
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MOID/resume)
EQ "恢复 → RELEASED" "$(echo "$RES" | J "d['data']['status']")" "RELEASED"
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MOID/cancel -H 'Content-Type: application/json' -d '{"reason":""}')
EQ "缺取消原因 422" "$(echo "$RES" | J "d['code']")" "422"

echo "== 10. 拆分（C-4.5-15 / BR-4.5-05）="
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MOID/split -H 'Content-Type: application/json' \
  -d '{"childQtys":[6,7],"reason":"合计不等测试"}')
EQ "合计不等 422" "$(echo "$RES" | J "d['code']")" "422"
CONTAINS "C-4.5-15 文案" "$(echo "$RES" | J "d['message']")" "合计"
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MOID/split -H 'Content-Type: application/json' \
  -d '{"childQtys":[6,4],"reason":"分批交付"}')
CH=$(echo "$RES" | J "len(d['data']['children'])")
EQ "拆分两张子单" "$CH" "2"
EQ "原单减至 0" "$(echo "$RES" | J "float(d['data']['parent']['qty'])")" "0.0"
CH1=$(echo "$RES" | J "d['data']['children'][0]['moNo']")
EQ "子单拆分链留痕" "$(SQL "SELECT SPLIT_FROM_MO FROM erp_mrp_mo WHERE MO_NO='$CH1'")" "$MONO"
CHB=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_bom WHERE MO_ID=(SELECT ID FROM erp_mrp_mo WHERE MO_NO='$CH1')")
EQ "子单继承 BOM 快照" "$CHB" "1"
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$MOID/split -H 'Content-Type: application/json' -d '{"childQtys":[1]}')
EQ "已无剩余拆分 422" "$(echo "$RES" | J "d['code']")" "422"

echo "== 11. 完工与关闭（6.3 + 钩子占位）="
CH1ID=$(SQL "SELECT ID FROM erp_mrp_mo WHERE MO_NO='$CH1'")
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$CH1ID/close)
EQ "RELEASED 关闭 422（未完工）" "$(echo "$RES" | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$CH1ID/complete -H 'Content-Type: application/json' -d '{"qualifiedQty":null}')
EQ "缺合格产出 422" "$(echo "$RES" | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$CH1ID/complete -H 'Content-Type: application/json' -d '{"qualifiedQty":6}')
EQ "完工 → COMPLETED" "$(echo "$RES" | J "d['data']['status']")" "COMPLETED"
RES=$(C -H "Authorization: Bearer $TPLAN" $B/api/mrp/mos/$CH1ID/close-precheck)
EQ "预检通过（状态+占位钩子）" "$(echo "$RES" | J "d['data']['passed']")" "True"
CHK=$(echo "$RES" | J "len(d['data']['checks'])")
[ "$CHK" -ge 2 ] && ok "校验项 ≥2（含占位钩子）" || bad "校验项数 $CHK"
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$CH1ID/close)
EQ "关闭 → CLOSED" "$(echo "$RES" | J "d['data']['status']")" "CLOSED"
RES=$(C -H "Authorization: Bearer $TPLAN" -X POST $B/api/mrp/mos/$CH1ID/close)
EQ "终态再关闭 422" "$(echo "$RES" | J "d['code']")" "422"

echo "== 12. 在制供给契约（下游 5.3 桩）="
# P1 在制 = RELEASED/未关闭剩余：父单已减至 0（不计）+ 子单1 已完工合格 6（余 0 不计）+ 子单2 RELEASED 4
RES=$(C -H "Authorization: Bearer $TPLAN" "$B/api/mrp/mos/in-process?itemCodes=$P1")
IP=$(echo "$RES" | J "float(d['data'].get('$P1',0))")
python3 -c "import sys; sys.exit(0 if abs($IP-4.0)<0.01 else 1)" \
  && ok "在制 = 子单2 未完工 4（父单 0 / 已完工不计）" || bad "在制值：$IP"

echo "== 13. 权限矩阵 =="
# P3 此时补发 BOM（第 4 节已用它验证「无 BOM 阻断」），供 ADMIN 直通断言
publishBom "$P3" >/dev/null
# requireAny 先于业务校验：任何写操作对 ROLE_USER 一律 403
RES=$(C -H "Authorization: Bearer $TVIEW" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"productCode\":\"$P3\",\"qty\":1,\"planEndDate\":\"$FUT\"}}" $B/api/mrp/mos)
EQ "ROLE_USER 创建 403" "$(echo "$RES" | J "d['code']")" "403"
RES=$(C -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"productCode\":\"$P3\",\"qty\":3,\"planEndDate\":\"$FUT\"}}" $B/api/mrp/mos)
EQ "ADMIN 直通创建" "$(echo "$RES" | J "d['data']['mo']['status']")" "PLANNED"
PERM=$(SQL "SELECT COUNT(*) FROM erp_admin_menu WHERE ID LIKE 'M5-4%' AND PERM LIKE '%ROLE_PLAN_MGR%'")
EQ "M5-4 菜单权限回填 6 行" "$PERM" "6"
ROLE=$(SQL "SELECT COUNT(*) FROM erp_admin_role WHERE ROLE_CODE='ROLE_PLAN_MGR'")
EQ "ROLE_PLAN_MGR 角色就位" "$ROLE" "1"

echo ""
echo "==== 结果：$PASS 过 / $FAIL 败（共 $((PASS+FAIL)) 断言）===="
[ "$FAIL" -eq 0 ]
