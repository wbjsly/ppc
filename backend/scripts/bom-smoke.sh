#!/bin/zsh
# add-bom-management 全链路 API 冒烟（任务 7.x API 面 + 8.3）
set -u
B=http://127.0.0.1:8090
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✘ $1"; }
# curl 直连（绕过沙箱代理）
C() { curl -s --noproxy '*' "$@"; }
# JSON 取值: J 'expr'  (stdin=json, expr 可用 d)
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
# 断言两串相等
EQ() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望[$3] 实际[$2]）"; fi; }
CONTAINS() { case "$2" in *"$3"*) ok "$1";; *) bad "$1（未包含[$3]，实际[$2]）";; esac; }

echo "== 0. 前置清理（可重跑） =="
export MYSQL_PWD=root123
docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot erp -e "
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='BomPublish';
DELETE FROM erp_sys_approval WHERE BIZ_TYPE='BomPublish';
DELETE FROM erp_mrp_bom_item WHERE BOM_ID NOT IN (SELECT ID FROM erp_mrp_bom);
DELETE FROM erp_mrp_bom_substitute WHERE BOM_ITEM_ID NOT IN (SELECT ID FROM erp_mrp_bom_item);
DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN ('IT-E2E410-01','IT-E2E411-01','IT-E2E413-01');" >/dev/null 2>&1 \
  && ok "清理完成" || bad "清理失败"

echo "== 1. 登录 =="
TENG=$(C -H 'Content-Type: application/json' -d '{"username":"bom-eng","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TMGR=$(C -H 'Content-Type: application/json' -d '{"username":"bom-mgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TENG" ] && [ -n "$TMGR" ] && ok "bom-eng / bom-mgr 登录" || bad "登录失败"

echo "== 2. 未认证 401 =="
CODE=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' $B/api/mrp/boms)
EQ "GET /api/mrp/boms 未认证 401" "$CODE" "401"
CODE=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST $B/api/mrp/boms)
EQ "POST /api/mrp/boms 未认证 401" "$CODE" "401"

echo "== 3. 取物料与 MDM 候选关系 =="
ITEMS=$(C -H "Authorization: Bearer $TENG" "$B/api/mrp/items" 2>/dev/null)
ITEMS=$(C -H "Authorization: Bearer $TENG" "$B/api/mdm/items?current=1&size=8&status=1")
A=$(echo "$ITEMS" | J "[r['itemCode'] for r in d['data']['records'] if r['baseUnit']=='PCS'][:1][0]")
BB=$(echo "$ITEMS" | J "[r['itemCode'] for r in d['data']['records'] if r['baseUnit']=='PCS'][:2][1]")
CC=$(echo "$ITEMS" | J "[r['itemCode'] for r in d['data']['records'] if r['baseUnit']=='PCS'][:3][2]")
if [ -z "$A" ] || [ -z "$BB" ] || [ -z "$CC" ]; then bad "取 3 个 PCS 物料失败"; echo "== 结果: PASS=$PASS FAIL=$FAIL"; exit 1; fi
ok "物料 A=$A B=$BB C=$CC"
# MDM 替代关系：B.alt = C（候选带出读取）
export MYSQL_PWD=root123
docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot erp -e "UPDATE erp_mdm_item SET ALT_ITEM_CODE='$CC' WHERE ITEM_CODE='$BB';" >/dev/null 2>&1 && ok "MDM 设置 $BB→$CC 替代关系" || bad "MDM 替代关系设置失败"

echo "== 4. 创建 A→B（草稿 V1.0） =="
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$A\"},\"items\":[{\"itemCode\":\"$BB\",\"qty\":1,\"lossRate\":5}]}" \
  $B/api/mrp/boms)
CODE=$(echo "$RES" | J "d['code']")
if [ "$CODE" = "200" ]; then ok "创建成功"; else bad "创建失败: $RES"; fi
AID=$(echo "$RES" | J "d['data']['id']")
VER=$(echo "$RES" | J "d['data']['versionLabel']")
EQ "首版 V1.0" "$VER" "1.0"

echo "== 5. 循环阻断：创建 B→A =="
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$BB\"},\"items\":[{\"itemCode\":\"$A\",\"qty\":1,\"lossRate\":0}]}" \
  $B/api/mrp/boms)
CODE=$(echo "$RES" | J "d['code']")
MSG=$(echo "$RES" | J "d['message']")
EQ "成环 L1 阻断 code=422" "$CODE" "422"
CONTAINS "响应含环路径箭头" "$MSG" "→"

echo "== 6. MDM 候选带出 =="
RES=$(C -H "Authorization: Bearer $TENG" "$B/api/mrp/boms/substitute-candidates?itemCode=$BB")
N=$(echo "$RES" | J "len(d['data'])")
if [ "$N" = "1" ]; then ok "候选带出 1 条（$CC）"; else bad "候选数=$N: $RES"; fi

echo "== 7. 提交审核（ENG） =="
RES=$(C -X POST -H "Authorization: Bearer $TENG" $B/api/mrp/boms/$AID/submit)
CODE=$(echo "$RES" | J "d['code']")
ST=$(C -H "Authorization: Bearer $TENG" $B/api/mrp/boms/$AID | J "d['data']['status']")
EQ "提交后 PENDING" "$ST" "PENDING"

echo "== 8. 工程师签署被 403 =="
TASKID=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e \
  "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='BomPublish' AND a.BIZ_ID='$AID' AND t.STATUS='ACTIVE';")
HTTP=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TENG" \
  -H 'Content-Type: application/json' -d "{\"taskId\":\"$TASKID\",\"opinion\":\"我想发布\"}" $B/api/qms/approvals/pass)
EQ "ENG 签署 PROCESS_MGR 节点 403（Security 层）" "$HTTP" "403"

echo "== 9. 主管通过 → 发布 =="
RES=$(C -X POST -H "Authorization: Bearer $TMGR" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TASKID\",\"opinion\":\"同意发布\"}" $B/api/qms/approvals/pass)
CODE=$(echo "$RES" | J "d['code']")
[ "$CODE" = "200" ] && ok "签署通过" || bad "签署失败: $RES"
ST=$(C -H "Authorization: Bearer $TENG" $B/api/mrp/boms/$AID | J "d['data']['status']")
EQ "BOM 已发布" "$ST" "PUBLISHED"
PB=$(C -H "Authorization: Bearer $TENG" $B/api/mrp/boms/$AID | J "d['data']['publishBy']")
EQ "发布留痕 publishBy=主管 userId（平台约定：applyBy/signer 同为 userId）" "$PB" "user-bom-mgr"

echo "== 10. 复制已发布 → V1.1 草稿 =="
RES=$(C -X POST -H "Authorization: Bearer $TENG" $B/api/mrp/boms/$AID/copy)
CODE=$(echo "$RES" | J "d['code']")
CID=$(echo "$RES" | J "d['data']['id']")
CV=$(echo "$RES" | J "d['data']['versionLabel']")
CF=$(echo "$RES" | J "d['data']['copyFromId']")
[ "$CODE" = "200" ] && ok "复制成功" || bad "复制失败: $RES"
EQ "复制版本 V1.1" "$CV" "1.1"
EQ "copy_from_id 留痕" "$CF" "$AID"

echo "== 11. 在途阻断：再变更 =="
RES=$(C -X POST -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d '{"changeReason":"冒烟变更","upgradeMajor":false}' $B/api/mrp/boms/$AID/change)
CODE=$(echo "$RES" | J "d['code']")
MSG=$(echo "$RES" | J "d['message']")
EQ "存在在途（复制的 V1.1 草稿）→ 422" "$CODE" "422"
CONTAINS "提示在途" "$MSG" "在途"

echo "== 12. 复制的 V1.1 提交→主管通过 → 旧版 REVISED =="
RES=$(C -X POST -H "Authorization: Bearer $TENG" $B/api/mrp/boms/$CID/submit)
[ "$(echo "$RES" | J "d['code']")" = "200" ] && ok "V1.1 提交" || bad "V1.1 提交失败: $RES"
TASK2=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e \
  "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='BomPublish' AND a.BIZ_ID='$CID' AND t.STATUS='ACTIVE';")
RES=$(C -X POST -H "Authorization: Bearer $TMGR" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TASK2\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass)
[ "$(echo "$RES" | J "d['code']")" = "200" ] && ok "V1.1 通过发布" || bad "V1.1 签署失败: $RES"
ST1=$(C -H "Authorization: Bearer $TENG" $B/api/mrp/boms/$AID | J "d['data']['status']")
ST2=$(C -H "Authorization: Bearer $TENG" $B/api/mrp/boms/$CID | J "d['data']['status']")
EQ "旧版 V1.0 → REVISED" "$ST1" "REVISED"
EQ "新版 V1.1 → PUBLISHED" "$ST2" "PUBLISHED"
NP=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e \
  "SELECT COUNT(*) FROM erp_mrp_bom WHERE PARENT_ITEM_CODE='$A' AND STATUS='PUBLISHED';")
EQ "同父项唯一已发布" "$NP" "1"

echo "== 13. 发起变更（已发布→草稿，原因必填/缺原因 422） =="
RES=$(C -X POST -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d '{"changeReason":"  ","upgradeMajor":false}' $B/api/mrp/boms/$CID/change)
CODE=$(echo "$RES" | J "d['code']")
EQ "缺变更原因 422" "$CODE" "422"
RES=$(C -X POST -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d '{"changeReason":"冒烟结构升级","upgradeMajor":true}' $B/api/mrp/boms/$CID/change)
CODE=$(echo "$RES" | J "d['code']")
DID=$(echo "$RES" | J "d['data']['id']")
DV=$(echo "$RES" | J "d['data']['versionLabel']")
DR=$(echo "$RES" | J "d['data']['changeReason']")
DF=$(echo "$RES" | J "d['data']['changeFromId']")
[ "$CODE" = "200" ] && ok "变更成功（升级主版本）" || bad "变更失败: $RES"
EQ "升级主版本 V2.0" "$DV" "2.0"
EQ "变更原因落库" "$DR" "冒烟结构升级"
EQ "change_from_id 变更链" "$DF" "$CID"

echo "== 14. 废止（ENG 403 / MGR 成功） =="
RES=$(C -X POST -H "Authorization: Bearer $TENG" $B/api/mrp/boms/$AID/obsolete)
CODE=$(echo "$RES" | J "d['code']")
EQ "ENG 废止 403" "$CODE" "403"
RES=$(C -X POST -H "Authorization: Bearer $TMGR" $B/api/mrp/boms/$AID/obsolete)
[ "$(echo "$RES" | J "d['code']")" = "200" ] && ok "MGR 废止 REVISED 版本" || bad "MGR 废止失败: $RES"
ST=$(C -H "Authorization: Bearer $TENG" $B/api/mrp/boms/$AID | J "d['data']['status']")
EQ "V1.0 → OBSOLETE" "$ST" "OBSOLETE"

echo "== 15. 循环校验扫描 =="
RES=$(C -H "Authorization: Bearer $TENG" "$B/api/mrp/boms/scan")
P=$(echo "$RES" | J "d['data']['passed']")
K=$(echo "$RES" | J "'scannedBoms' in d['data'] and 'cycles' in d['data'] and 'depthExceeded' in d['data']")
[ "$P" = "True" ] && ok "全量扫描 passed=true" || bad "全量扫描 passed=$P"
EQ "报告结构完整" "$K" "True"

echo "== 16. 版本列表留痕字段 =="
RES=$(C -H "Authorization: Bearer $TENG" "$B/api/mrp/boms?parentItemCode=$A")
K=$(echo "$RES" | J "all(k in r for r in d['data'] for k in ['changeReason','changeFromId','copyFromId','publishBy','publishAt','rejectReason'])")
EQ "列表含六类留痕字段" "$K" "True"

echo "== 17. ADMIN 全通过（创建→提交→签署→废止） =="
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
RES=$(C -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$CC\"},\"items\":[{\"itemCode\":\"$A\",\"qty\":2,\"lossRate\":0}]}" \
  $B/api/mrp/boms)
CODE=$(echo "$RES" | J "d['code']")
CID2=$(echo "$RES" | J "d['data']['id']")
[ "$CODE" = "200" ] && ok "ADMIN 创建" || bad "ADMIN 创建失败: $RES"
RES=$(C -X POST -H "Authorization: Bearer $TADM" $B/api/mrp/boms/$CID2/submit)
[ "$(echo "$RES" | J "d['code']")" = "200" ] && ok "ADMIN 提交" || bad "ADMIN 提交失败: $RES"
TASK3=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e \
  "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='BomPublish' AND a.BIZ_ID='$CID2' AND t.STATUS='ACTIVE';")
HTTP=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TADM" \
  -H 'Content-Type: application/json' -d "{\"taskId\":\"$TASK3\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass)
EQ "ADMIN 签署放行 200" "$HTTP" "200"
RES=$(C -X POST -H "Authorization: Bearer $TADM" $B/api/mrp/boms/$CID2/obsolete)
[ "$(echo "$RES" | J "d['code']")" = "200" ] && ok "ADMIN 废止" || bad "ADMIN 废止失败: $RES"

# 清理 MDM 替代关系（还原冒烟痕迹）
docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot erp -e "UPDATE erp_mdm_item SET ALT_ITEM_CODE=NULL WHERE ITEM_CODE='$BB';" >/dev/null 2>&1

echo ""
echo "== 结果: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" = "0" ] && exit 0 || exit 1
