#!/bin/zsh
# 工作台可配置布局冒烟（change add-configurable-workbench，spec personal-workbench）
# 段1 表结构与 GLOBAL 基线；段2 布局读写与 401/403 矩阵；
# 段3-5 十个 widget 数据端点；段6 三层差量合并端到端（spec 四场景）。
# 约定：故意 403 全部记在专用零角色账号 smkdashop 名下，防 admin 触发 AUTHZ_BURST
#       （5 分钟窗口 10 次 403 会降权 7 天并连锁拦敏感四前缀）。
set -u
B=http://127.0.0.1:8090
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✘ $1"; }
C() { curl -s --noproxy '*' --max-time 60 "$@"; }
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
EQ() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望[$3] 实际[$2]）"; fi; }
CONTAINS() { case "$2" in *"$3"*) ok "$1";; *) bad "$1（未包含[$3]，实际[$2]）";; esac; }
HTTP() { curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 60 "$@"; }
export MYSQL_PWD=root123
SQL() { docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --force --default-character-set=utf8mb4 -uroot -N -B erp -e "$1" 2>/dev/null; }
A() { echo "Authorization: Bearer $1"; }

echo "== 段0 登录（admin + 专用零角色账号）=="
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "${TADM:-}" ] && ok "admin 登录" || bad "admin 登录失败"
# 专用低权账号（同 eam-smoke 段84 模式：复用 admin 密码散列、零角色）
SQL "DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON u.ID=ur.USER_ID WHERE u.USERNAME='smkdashop'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE USERNAME='smkdashop'" >/dev/null 2>&1
SQL "INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     SELECT 'smkdash-op','smkdashop',PASSWORD,'冒烟工作台零权','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
TOP=$(C -H 'Content-Type: application/json' -d '{"username":"smkdashop","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "${TOP:-}" ] && ok "零角色账号登录" || bad "零角色账号登录失败"

echo "== 段1 表结构与 GLOBAL 基线（迁移 209）=="
UK=$(SQL "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='erp_admin_dash_layout' AND INDEX_NAME='UK_DASH'")
EQ "UK_DASH(SCOPE,REF_ID) 唯一键存在" "$UK" "2"
GB=$(SQL "SELECT COUNT(*) FROM erp_admin_dash_layout WHERE SCOPE='GLOBAL' AND REF_ID='ALL' AND DEL_FLAG='0'")
EQ "GLOBAL 基线恰好 1 行" "$GB" "1"
GJ=$(SQL "SELECT CHAR_LENGTH(LAYOUT_JSON) FROM erp_admin_dash_layout WHERE SCOPE='GLOBAL' LIMIT 1")
[ "${GJ:-0}" -gt 100 ] && ok "GLOBAL 基线含布局 JSON（${GJ} 字节）" || bad "GLOBAL 基线 JSON 异常（$GJ）"
GB10=$(SQL "SELECT JSON_LENGTH(JSON_EXTRACT(LAYOUT_JSON,'\$.items')) FROM erp_admin_dash_layout WHERE SCOPE='GLOBAL' LIMIT 1")
EQ "GLOBAL 基线 10 个 widget" "$GB10" "10"

echo "== 段2 读写与 401/403 矩阵 =="
EQ "GET /mine 无 token 401" "$(HTTP $B/api/dash/layout/mine)" "401"
EQ "GET /layers 无 token 401" "$(HTTP $B/api/dash/layout/layers)" "401"
EQ "PUT /user 无 token 401" "$(HTTP -X PUT -H 'Content-Type: application/json' -d '{}' $B/api/dash/layout/user)" "401"
# 故意 403：记在零角色账号（smkdashop）名下，共 3 次 < 阈值 10
EQ "零角色 GET /layers 403" "$(HTTP -H "$(A $TOP)" $B/api/dash/layout/layers)" "403"
EQ "零角色 PUT /global 403" "$(HTTP -X PUT -H "$(A $TOP)" -H 'Content-Type: application/json' -d '{}' $B/api/dash/layout/global)" "403"
EQ "零角色 PUT /role 403" "$(HTTP -X PUT -H "$(A $TOP)" -H 'Content-Type: application/json' -d '{}' $B/api/dash/layout/role/ROLE_ADMIN)" "403"
# admin 正向读写
MINE=$(C -H "$(A $TADM)" $B/api/dash/layout/mine)
CONTAINS "GET /mine 返回 merged（含 items）" "$MINE" '"items"'
CONTAINS "GET /mine 标注 layers 来源" "$MINE" '"global"'
LAYERS=$(C -H "$(A $TADM)" $B/api/dash/layout/layers)
CONTAINS "ADMIN GET /layers 回显 global" "$LAYERS" '"global"'
URET=$(C -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"v":1,"items":[{"id":"todo","x":0,"y":0,"w":4,"h":4},{"id":"notice","x":4,"y":0,"w":4,"h":4}],"excluded":["kpi-alerts"],"shortcuts":[]}' \
  $B/api/dash/layout/user | J "d['code']")
EQ "PUT /user 保存成功" "$URET" "200"
MINE2=$(C -H "$(A $TADM)" $B/api/dash/layout/mine)
EQ "个人层 excluded 生效（kpi-alerts 不在画布）" "$(echo "$MINE2" | J "len([i for i in d['data']['items'] if i['id']=='kpi-alerts'])")" "0"
EQ "个人层两件都在画布（覆盖式合并）" "$(echo "$MINE2" | J "len([i for i in d['data']['items'] if i['id'] in ('todo','notice')])")" "2"
# 恢复默认：空 body 清个人层
C -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' -d '' $B/api/dash/layout/user >/dev/null
MINE3=$(C -H "$(A $TADM)" $B/api/dash/layout/mine)
EQ "恢复默认后回落 GLOBAL（10 件且无 excluded 残留）" "$(echo "$MINE3" | J "len(d['data']['items'])")" "10"

echo "== 段3 核心三件套 widget 数据端点 =="
for p in "/iot/todos" "/notices/unread-count" "/notices?size=5"; do
  EQ "GET $p = 200" "$(HTTP -H "$(A $TADM)" "$B/api$p")" "200"
done

echo "== 段4 指标四件 widget 数据端点 =="
for p in "/bi/kpi/summary" "/bi/kpi/trend" "/bi/kpi/alerts" "/fin/periods/current"; do
  EQ "GET $p = 200" "$(HTTP -H "$(A $TADM)" "$B/api$p")" "200"
done

echo "== 段5 告警三件 widget 数据端点 =="
for p in "/ops/alerts" "/iot/alarms" "/system/authz-incidents"; do
  EQ "GET $p = 200" "$(HTTP -H "$(A $TADM)" "$B/api$p")" "200"
done
# 越权告警对零角色账号：数据接口本身的后端兜底（widget 前端已 perm 预过滤不发请求）
EQ "零角色 /system/authz-incidents 403（后端兜底）" "$(HTTP -H "$(A $TOP)" $B/api/system/authz-incidents)" "403"

echo "== 段6 三层差量合并端到端（spec 四场景）=="
# 准备：写 ROLE 层（模拟角色模板：todo+period）
RRET=$(C -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"v":1,"items":[{"id":"todo","x":0,"y":0,"w":4,"h":4},{"id":"period","x":4,"y":0,"w":4,"h":4}],"excluded":[]}' \
  $B/api/dash/layout/role/ROLE_ADMIN | J "d['code']")
EQ "ADMIN 写入角色模板" "$RRET" "200"
# 场景4：空个人层回落角色模板（先清个人层）
C -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' -d '' $B/api/dash/layout/user >/dev/null
S4=$(C -H "$(A $TADM)" $B/api/dash/layout/mine | J "len([i for i in d['data']['items'] if i['id']=='period'])")
EQ "场景4 空个人层呈现角色模板组件" "$S4" "1"
# 场景3：个人覆盖优先（个人把 todo 挪到 x=7）
C -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"v":1,"items":[{"id":"todo","x":7,"y":0,"w":4,"h":4}],"excluded":[]}' $B/api/dash/layout/user >/dev/null
S3=$(C -H "$(A $TADM)" $B/api/dash/layout/mine | J "[i['x'] for i in d['data']['items'] if i['id']=='todo'][0]")
EQ "场景3 个人位置覆盖模板" "$S3" "7"
# 场景1：模板新增触达已配置用户（ROLE 层追加 iot-alarm，个人层不动它）
C -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"v":1,"items":[{"id":"todo","x":0,"y":0,"w":4,"h":4},{"id":"period","x":4,"y":0,"w":4,"h":4},{"id":"iot-alarm","x":8,"y":0,"w":4,"h":4}],"excluded":[]}' \
  $B/api/dash/layout/role/ROLE_ADMIN >/dev/null
S1=$(C -H "$(A $TADM)" $B/api/dash/layout/mine | J "len([i for i in d['data']['items'] if i['id']=='iot-alarm'])")
EQ "场景1 模板新增触达已配置用户" "$S1" "1"
# 场景2：用户移除不回补（个人 excluded 加 period，模板仍有）
C -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"v":1,"items":[{"id":"todo","x":7,"y":0,"w":4,"h":4}],"excluded":["period"]}' $B/api/dash/layout/user >/dev/null
S2=$(C -H "$(A $TADM)" $B/api/dash/layout/mine | J "len([i for i in d['data']['items'] if i['id']=='period'])")
EQ "场景2 用户移除不回补" "$S2" "0"

echo "== 清理（恢复现场：清个人/角色测试层，保 GLOBAL 基线）=="
C -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' -d '' $B/api/dash/layout/user >/dev/null 2>&1
SQL "DELETE FROM erp_admin_dash_layout WHERE SCOPE='ROLE' AND REF_ID='ROLE_ADMIN'" >/dev/null 2>&1
SQL "DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON u.ID=ur.USER_ID WHERE u.USERNAME='smkdashop'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE USERNAME='smkdashop'" >/dev/null 2>&1
ok "清理完成"

echo ""
echo "==============================="
echo "dash-smoke 结果：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN"; else echo "HAS FAILURES"; exit 1; fi
