#!/bin/zsh
# rework-standard-management 全链路 API 冒烟（任务 7.2，可重跑）
# 链路：清理→登录→401→建标准→建版本（五维度范围）→列表版本摘要（单请求）→
#       提交发布→6.1.2 待办平铺（双签状态）→双签（质量经理+技术负责人）→
#       发布回调（RELEASED/截断/current_version）→引用锁 422→复制放行→
#       未引用可改→已发布只读→退役→退役后 resolve 阻断→权限矩阵（计划线 403）
# 断言层：401 走 HTTP；403/422/404 是 R<T> 业务码（HTTP 恒 200）用 BIZ
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
BIZ() { C "$@" | J "d['code']"; }
MSG() { C "$@" | J "d['message']"; }
export MYSQL_PWD=root123
SQL() { docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --force --default-character-set=utf8mb4 -uroot -N -B erp -e "$1" 2>/dev/null; }
A() { echo "Authorization: Bearer $1"; }

STD_CODE=SMK-STD-001
PLAN_A=SMK-PLAN-A
PLAN_B=SMK-PLAN-B

echo "== 0. 前置清理（标准冒烟族 SMK，可重跑）=="
SQL "
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON a.ID=t.APPR_ID
 WHERE a.BIZ_TYPE='StandardPublish' AND a.TITLE LIKE '%SMK-STD%';
DELETE FROM erp_sys_approval WHERE BIZ_TYPE='StandardPublish' AND TITLE LIKE '%SMK-STD%';
DELETE c FROM erp_qms_characteristic c JOIN erp_qms_standard_version v ON v.ID=c.VERSION_ID
 WHERE v.STANDARD_ID IN (SELECT ID FROM (SELECT ID FROM erp_qms_standard WHERE STANDARD_CODE='SMK-STD-001') t)
    OR v.STANDARD_ID LIKE 'smk-std-%';
DELETE a FROM erp_qms_standard_applicability a JOIN erp_qms_standard_version v ON v.ID=a.VERSION_ID
 WHERE v.STANDARD_ID IN (SELECT ID FROM (SELECT ID FROM erp_qms_standard WHERE STANDARD_CODE='SMK-STD-001') t)
    OR v.STANDARD_ID LIKE 'smk-std-%';
DELETE v FROM erp_qms_standard_version v
 WHERE v.STANDARD_ID IN (SELECT ID FROM (SELECT ID FROM erp_qms_standard WHERE STANDARD_CODE='SMK-STD-001') t)
    OR v.STANDARD_ID LIKE 'smk-std-%';
DELETE FROM erp_qms_standard WHERE ID LIKE 'smk-std-%' OR STANDARD_CODE='SMK-STD-001';
DELETE FROM erp_qms_sampling_plan WHERE PLAN_CODE LIKE 'SMK-PLAN%';" >/dev/null 2>&1 \
  && ok "清理完成（SMK 族就位）" || bad "清理失败"

echo "== 1. 登录（质量工程师/质量经理/技术负责人/计划线）=="
TENG=$(C -H 'Content-Type: application/json' -d '{"username":"qeng","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TMGR=$(C -H 'Content-Type: application/json' -d '{"username":"qmgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TTECH=$(C -H 'Content-Type: application/json' -d '{"username":"qtech","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TPLAN=$(C -H 'Content-Type: application/json' -d '{"username":"dplanner","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TENG" ] && [ -n "$TMGR" ] && [ -n "$TTECH" ] && [ -n "$TPLAN" ] \
  && ok "四角色登录" || bad "登录失败"

echo "== 2. 401 矩阵 =="
EQ "GET /qms/standards 401" "$(HTTP $B/api/qms/standards)" "401"
EQ "GET /qms/standards/pending-versions 401" "$(HTTP $B/api/qms/standards/pending-versions)" "401"
EQ "POST /qms/standards 401" "$(HTTP -X POST $B/api/qms/standards)" "401"
EQ "POST sampling-plans/{id}/copy 401" "$(HTTP -X POST $B/api/qms/standards/sampling-plans/x/copy)" "401"

echo "== 3. 计划线只读（写 403，GET 放行）=="
EQ "计划线 POST 标准 403" "$(HTTP -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d '{"standardCode":"X"}' "$B/api/qms/standards")" "403"
EQ "计划线 GET 列表 200" "$(HTTP -H "$(A $TPLAN)" "$B/api/qms/standards?current=1&size=5")" "200"

echo "== 4. 建标准 + 抽样方案 =="
EQ "建标准 200" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"standardCode\":\"$STD_CODE\",\"name\":\"冒烟检验标准\",\"scopeType\":\"ITEM\",\"riskLevel\":\"B\"}" \
  $B/api/qms/standards)" "200"
STD_ID=$(C -H "$(A $TENG)" "$B/api/qms/standards?current=1&size=50&keyword=$STD_CODE" | J "d['data']['records'][0]['id']")
[ -n "$STD_ID" ] && ok "标准 ID 取得（$STD_ID）" || bad "标准 ID 缺失"

EQ "重复编码 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"standardCode\":\"$STD_CODE\",\"name\":\"重复\"}" $B/api/qms/standards)" "422"

EQ "建方案A 200" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"planCode\":\"$PLAN_A\",\"name\":\"冒烟方案A\",\"inspectionLevel\":\"II\",\"aqlLevel\":\"GB/T 2828.1 一般检验水平 II\",\"sampleSizeRule\":\"n=32\",\"acceptRule\":\"Ac=1\",\"rejectRule\":\"Re=2\"}" \
  $B/api/qms/standards/sampling-plans)" "200"
PLAN_A_ID=$(C -H "$(A $TENG)" $B/api/qms/standards/sampling-plans | J "[p['id'] for p in d['data'] if p['planCode']=='$PLAN_A'][0]")
[ -n "$PLAN_A_ID" ] && ok "方案A ID 取得" || bad "方案A ID 缺失"

echo "== 5. 建版本（CTQ 特性 + 物料维度范围）=="
V1_BODY=$(cat <<EOF
{"samplingPlanId":"$PLAN_A_ID","aqlLevel":"GB/T 2828.1 一般检验水平 II","inspectionLevel":"II",
 "effectiveFrom":"$(date +%Y-%m-%d)","changeReason":"冒烟初始版本",
 "characteristics":[{"characteristicName":"外观","ctqFlag":"1","regulatoryFlag":"0","specType":"LOOK","methodName":"目视"},
                    {"characteristicName":"尺寸","ctqFlag":"0","regulatoryFlag":"0","specType":"NUMERIC","lowerLimit":"9.5","upperLimit":"10.5","unit":"mm","methodName":"卡尺"}],
 "applicabilities":[{"materialCode":"SMK-ITEM-1","priority":10},{"customerId":"SMK-CUST-1","priority":20}]}
EOF
)
V1_ID=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' -d "$V1_BODY" \
  "$B/api/qms/standards/$STD_ID/versions" | J "d['data']['version']['id']")
[ -n "$V1_ID" ] && ok "V1 草稿创建" || bad "V1 创建失败"

echo "== 6. 列表版本摘要（单请求带摘要）=="
C -H "$(A $TENG)" "$B/api/qms/standards?current=1&size=50&keyword=$STD_CODE" > /tmp/standard-smoke-list.json
SUM() { python3 -c "
import json,sys
d=json.load(open('/tmp/standard-smoke-list.json'))
r=[x for x in d['data']['records'] if x['standardCode']=='$STD_CODE'][0]
v=r.get('${1:-}')
if isinstance(v,bool): print(str(v).lower())
elif isinstance(v,dict): print(v.get('${2:-}'))
else: print(v)"; }
EQ "摘要 hasDraft=true" "$(SUM hasDraft)" "true"
EQ "摘要 pendingCount=0（尚未提交）" "$(SUM pendingCount)" "0"
EQ "摘要 latestVersion.no=1" "$(SUM latestVersion no)" "1"
EQ "摘要 latestVersion.status=DRAFT" "$(SUM latestVersion status)" "DRAFT"
HAS_DETAIL=$(python3 -c "
import json
d=json.load(open('/tmp/standard-smoke-list.json'))
r=[x for x in d['data']['records'] if x['standardCode']=='$STD_CODE'][0]
print('characteristics' in r or 'applicabilities' in r)")
EQ "列表行不含明细段（去 N+1）" "$HAS_DETAIL" "False"

echo "== 7. 提交发布 → 6.1.2 待办（双签状态）=="
EQ "提交发布 200" "$(BIZ -X POST -H "$(A $TENG)" $B/api/qms/standards/versions/$V1_ID/publish)" "200"
EQ "重复提交 422" "$(BIZ -X POST -H "$(A $TENG)" $B/api/qms/standards/versions/$V1_ID/publish)" "422"
PEND=$(C -H "$(A $TENG)" $B/api/qms/standards/pending-versions)
PEND_CNT=$(echo "$PEND" | J "len([p for p in d['data'] if p['versionId']=='$V1_ID'])")
EQ "待办含本版本" "$PEND_CNT" "1"
PEND_TOTAL=$(echo "$PEND" | J "[p for p in d['data'] if p['versionId']=='$V1_ID'][0]['doubleSign']['total']")
EQ "双签节点数=2" "$PEND_TOTAL" "2"
PEND_CODE=$(echo "$PEND" | J "[p for p in d['data'] if p['versionId']=='$V1_ID'][0]['standardCode']")
EQ "待办带标准编码" "$PEND_CODE" "$STD_CODE"
PEND_REASON=$(echo "$PEND" | J "[p for p in d['data'] if p['versionId']=='$V1_ID'][0]['changeReason']")
EQ "待办带变更原因" "$PEND_REASON" "冒烟初始版本"
# 发布提交后摘要翻转
C -H "$(A $TENG)" "$B/api/qms/standards?current=1&size=50&keyword=$STD_CODE" > /tmp/standard-smoke-list.json
EQ "摘要 pendingCount=1（提交后）" "$(SUM pendingCount)" "1"
EQ "摘要 status=PENDING_APPROVE" "$(SUM latestVersion status)" "PENDING_APPROVE"

echo "== 8. 引用锁（审批中版本引用 → 方案A 禁改）=="
EQ "锁定期改方案A 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"id\":\"$PLAN_A_ID\",\"planCode\":\"$PLAN_A\",\"name\":\"冒烟方案A\",\"acceptRule\":\"Ac=9\"}" \
  $B/api/qms/standards/sampling-plans)" "422"
LOCK_MSG=$(MSG -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"id\":\"$PLAN_A_ID\",\"planCode\":\"$PLAN_A\",\"name\":\"冒烟方案A\"}" \
  $B/api/qms/standards/sampling-plans)
CONTAINS "锁定提示含复制新建" "$LOCK_MSG" "复制新建"
RULE_NOW=$(SQL "SELECT ACCEPT_RULE FROM erp_qms_sampling_plan WHERE PLAN_CODE='$PLAN_A'")
EQ "规则未被改动" "$RULE_NOW" "Ac=1"

echo "== 9. 复制放行 =="
EQ "复制缺编码 422" "$(BIZ -X POST -H "$(A $TENG)" "$B/api/qms/standards/sampling-plans/$PLAN_A_ID/copy")" "422"
EQ "复制成功 200" "$(BIZ -X POST -H "$(A $TENG)" "$B/api/qms/standards/sampling-plans/$PLAN_A_ID/copy?newPlanCode=$PLAN_B")" "200"
PLAN_B_ID=$(C -H "$(A $TENG)" $B/api/qms/standards/sampling-plans | J "[p['id'] for p in d['data'] if p['planCode']=='$PLAN_B'][0]")
COPY_RULE=$(SQL "SELECT ACCEPT_RULE FROM erp_qms_sampling_plan WHERE PLAN_CODE='$PLAN_B'")
EQ "副本规则拷贝" "$COPY_RULE" "Ac=1"
EQ "副本可改 200" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"id\":\"$PLAN_B_ID\",\"planCode\":\"$PLAN_B\",\"name\":\"冒烟方案B副本\",\"acceptRule\":\"Ac=5\"}" \
  $B/api/qms/standards/sampling-plans)" "200"
NEW_RULE=$(SQL "SELECT ACCEPT_RULE FROM erp_qms_sampling_plan WHERE PLAN_CODE='$PLAN_B'")
EQ "副本已改" "$NEW_RULE" "Ac=5"
# 未引用方案可改（方案A 仍锁、方案B 未被引用前也可改）——用方案B 再改一次
EQ "未引用方案再改 200" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"id\":\"$PLAN_B_ID\",\"planCode\":\"$PLAN_B\",\"name\":\"冒烟方案B副本\",\"acceptRule\":\"Ac=6\"}" \
  $B/api/qms/standards/sampling-plans)" "200"

echo "== 10. 双签（质量经理 + 技术负责人，按节点角色签）→ 发布回调 =="
# 节点 ID 为 UUID 无序 → 按 ROLE_REQUIRED 匹配 token（固定 token 签错角色会 403）
sign_as() { # $1=versionId $2=期望角色 $3=token $4=意见
  local tid
  tid=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=(SELECT APPROVAL_ID FROM erp_qms_standard_version WHERE ID='$1') AND STATUS='ACTIVE' AND ROLE_REQUIRED='$2' LIMIT 1")
  [ -z "$tid" ] && { bad "缺 $2 签署节点"; return; }
  EQ "$2 签署 200" "$(BIZ -X POST -H "$(A $3)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$tid\",\"opinion\":\"$4\"}" $B/api/qms/approvals/pass)" "200"
}
sign_as "$V1_ID" "ROLE_QUALITY_MGR" "$TMGR" "冒烟技术内容无误"
sign_as "$V1_ID" "ROLE_TECH_OWNER" "$TTECH" "冒烟会签通过"
V1_STATUS=$(SQL "SELECT STATUS FROM erp_qms_standard_version WHERE ID='$V1_ID'")
EQ "V1 RELEASED" "$V1_STATUS" "RELEASED"
STD_STATUS=$(SQL "SELECT STATUS FROM erp_qms_standard WHERE ID='$STD_ID'")
EQ "标准 ACTIVE" "$STD_STATUS" "ACTIVE"
CUR_VER=$(SQL "SELECT CURRENT_VERSION FROM erp_qms_standard WHERE ID='$STD_ID'")
EQ "current_version=1" "$CUR_VER" "1"
EQ "已发布版本只读 422" "$(BIZ -X PUT -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"changeReason":"想偷改"}' $B/api/qms/standards/versions/$V1_ID)" "422"
# 待办清空
PEND_AFTER=$(C -H "$(A $TENG)" $B/api/qms/standards/pending-versions | J "len([p for p in d['data'] if p['versionId']=='$V1_ID'])")
EQ "发布后待办移除" "$PEND_AFTER" "0"
# 已发布引用 → 方案A 仍锁
EQ "发布后方案A 仍锁 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"id\":\"$PLAN_A_ID\",\"planCode\":\"$PLAN_A\",\"name\":\"冒烟方案A\"}" \
  $B/api/qms/standards/sampling-plans)" "422"

echo "== 11. V2 区间冲突 + 发布截断 =="
V2_SAME=$(cat <<EOF
{"aqlLevel":"GB/T 2828.1 一般检验水平 II","inspectionLevel":"II",
 "effectiveFrom":"$(date +%Y-%m-%d)","changeReason":"同日生效冲突",
 "characteristics":[{"characteristicName":"外观","ctqFlag":"1","regulatoryFlag":"0","specType":"LOOK","methodName":"目视"}],
 "applicabilities":[{"materialCode":"SMK-ITEM-1","priority":10}]}
EOF
)
V2_ID=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' -d "$V2_SAME" \
  "$B/api/qms/standards/$STD_ID/versions" | J "d['data']['version']['id']")
EQ "同日生效提交发布 422（区间冲突）" "$(BIZ -X POST -H "$(A $TENG)" $B/api/qms/standards/versions/$V2_ID/publish)" "422"
# 改生效日 → 明日
SQL "UPDATE erp_qms_standard_version SET EFFECTIVE_FROM=DATE_ADD(CURDATE(), INTERVAL 1 DAY) WHERE ID='$V2_ID'" >/dev/null
EQ "改期后提交 200" "$(BIZ -X POST -H "$(A $TENG)" $B/api/qms/standards/versions/$V2_ID/publish)" "200"
sign_as "$V2_ID" "ROLE_QUALITY_MGR" "$TMGR" "同意"
sign_as "$V2_ID" "ROLE_TECH_OWNER" "$TTECH" "同意"
V2_STATUS=$(SQL "SELECT STATUS FROM erp_qms_standard_version WHERE ID='$V2_ID'")
EQ "V2 RELEASED" "$V2_STATUS" "RELEASED"
V1_TO=$(SQL "SELECT EFFECTIVE_TO FROM erp_qms_standard_version WHERE ID='$V1_ID'")
[ -n "$V1_TO" ] && [ "$V1_TO" != "NULL" ] && ok "V1 生效止被截断（$V1_TO）" || bad "V1 未截断"
CUR2=$(SQL "SELECT CURRENT_VERSION FROM erp_qms_standard WHERE ID='$STD_ID'")
EQ "current_version=2" "$CUR2" "2"

echo "== 12. 五维度命中（客户维度单独配置）=="
HIT=$(C -H "$(A $TENG)" "$B/api/qms/standards/resolve?customerId=SMK-CUST-1" | J "'1' if d['data'].get('version') else '0'")
EQ "客户维度命中" "$HIT" "1"
HIT_MAT=$(C -H "$(A $TENG)" "$B/api/qms/standards/resolve?materialCode=SMK-ITEM-1" | J "'1' if d['data'].get('version') else '0'")
EQ "物料维度命中" "$HIT_MAT" "1"
HIT_MISS=$(C -H "$(A $TENG)" "$B/api/qms/standards/resolve?customerId=NOBODY" | J "'1' if d['data'].get('version') else '0'")
EQ "非配置客户不命中" "$HIT_MISS" "0"

echo "== 13. 退役 → 新命中阻断 =="
EQ "退役 200" "$(BIZ -X POST -H "$(A $TENG)" $B/api/qms/standards/$STD_ID/retire)" "200"
STD_ST=$(SQL "SELECT STATUS FROM erp_qms_standard WHERE ID='$STD_ID'")
EQ "标准 RETIRED" "$STD_ST" "RETIRED"
HIT_AFTER=$(C -H "$(A $TENG)" "$B/api/qms/standards/resolve?materialCode=SMK-ITEM-1" | J "'1' if d['data'].get('version') else '0'")
EQ "退役后新命中阻断（BR-4.12-58）" "$HIT_AFTER" "0"
VER_CNT=$(SQL "SELECT COUNT(*) FROM erp_qms_standard_version WHERE STANDARD_ID='$STD_ID'")
EQ "历史版本不删" "$VER_CNT" "2"

echo ""
echo "=============================="
echo "标准冒烟：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN"; else echo "HAS FAILURES"; exit 1; fi
