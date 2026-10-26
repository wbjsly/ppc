#!/bin/zsh
# add-kitting-check 全链路 API 冒烟（任务 8.2，参照 mo-smoke 先例，可重跑）
# 链路：建号→登录四角色→401 矩阵→造数（物料+发布 BOM+替代+库存）→建单初始缺料→
#       5.5.1 单校验/明细（替代候选/可抵量）→批量校验（去重）→聚合（同料多单/筛选）→
#       转建议（成功/5.3.1 可见/422 防重/处置后放行）→权限矩阵（业务码 403）→
#       置 RELEASED → 调度器恢复（清标记+恢复通知）与反向新缺料→通知读取端点正名
# 依赖：后端 8090 启动带 --app.kitting.sweep-ms=15000（否则调度轮 5 分钟，步骤 11 超时）
# 断言层说明：401 走 HTTP 状态（JWT 过滤器）；403/422/404 是 R<T> 业务码（HTTP 恒 200），用 BIZ
set -u
B=http://127.0.0.1:8090
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✘ $1"; }
C() { curl -s --noproxy '*' "$@"; }
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
EQ() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望[$3] 实际[$2]）"; fi; }
# 数值近似比较（JSON 数值经 python float 序列化，20.0000 → 20.0）
NEAR() { if python3 -c "import sys; sys.exit(0 if abs(float('$2')-float('$3'))<0.001 else 1)" 2>/dev/null; \
  then ok "$1"; else bad "$1（期望≈$3 实际 $2）"; fi; }
CONTAINS() { case "$2" in *"$3"*) ok "$1";; *) bad "$1（未包含[$3]，实际[$2]）";; esac; }
HTTP() { curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$@"; }
# 业务码（R<T> 包装：HTTP 恒 200，403/422/404 在 body）
BIZ() { C "$@" | J "d['code']"; }
export MYSQL_PWD=root123
SQL() { docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --default-character-set=utf8mb4 -uroot -N -B erp -e "$1"; }

echo "== 0. 前置清理（可重跑：按物料名 + 孤儿产品清 MO 族）="
SQL "
DELETE FROM erp_mrp_mo_shortage WHERE MO_ID IN (
  SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '齐套冒烟%')
     OR PRODUCT_CODE NOT IN (SELECT ITEM_CODE FROM erp_mdm_item));
DELETE FROM erp_mrp_mo_bom WHERE MO_ID IN (
  SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '齐套冒烟%')
     OR PRODUCT_CODE NOT IN (SELECT ITEM_CODE FROM erp_mdm_item));
DELETE FROM erp_mrp_mo_op WHERE MO_ID IN (
  SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '齐套冒烟%')
     OR PRODUCT_CODE NOT IN (SELECT ITEM_CODE FROM erp_mdm_item));
DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '齐套冒烟%')
   OR PRODUCT_CODE NOT IN (SELECT ITEM_CODE FROM erp_mdm_item);
DELETE FROM erp_mrp_mo_shortage WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_mrp_mo_bom WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_mrp_mo_op WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_mrp_suggestion WHERE REMARK LIKE '%[SHORTAGE:%';
DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('MO_SHORTAGE','MO_KITTING_RESTORED');
DELETE FROM erp_inv_stock WHERE ITEM_NAME LIKE '齐套冒烟%';
DELETE FROM erp_mrp_bom_item WHERE BOM_ID IN (SELECT ID FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '齐套冒烟%'));
DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '齐套冒烟%');
DELETE FROM erp_mrp_bom_item WHERE BOM_ID NOT IN (SELECT ID FROM erp_mrp_bom);
DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '齐套冒烟%';
DELETE FROM erp_mdm_item_category WHERE ID='cat-rtsk';
INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX)
VALUES ('cat-rtsk','RTSK','齐套冒烟分类','RTSK');" >/dev/null 2>&1 \
  && ok "清理完成（分类 RTSK 就位）" || bad "清理失败"

echo "== 1. 冒烟账号（复用 admin 的 BCrypt，口令 Admin@123）="
AHASH=$(SQL "SELECT PASSWORD FROM erp_admin_user WHERE USERNAME='admin' LIMIT 1")
mkuser() { # $1=用户名 $2=角色ID $3=昵称
  SQL "INSERT IGNORE INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, CREATE_BY)
       VALUES ('user-$1','$1','$AHASH','$3','1','smoke');
       INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) VALUES ('ur-$1','user-$1','$2');" >/dev/null 2>&1
}
mkuser ktplanner role-planner   "齐套计划员"
mkuser ktwh      role-warehouse "齐套仓管"
mkuser ktviewer  role-user      "齐套围观"
SQL "SELECT COUNT(*) FROM erp_admin_user WHERE USERNAME IN ('ktplanner','ktwh','ktviewer')" | grep -q '^3$' \
  && ok "三账号就位 ktplanner/ktwh/ktviewer" || bad "建号失败"

echo "== 2. 登录四角色 =="
TPLAN=$(C -H 'Content-Type: application/json' -d '{"username":"ktplanner","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TWH=$(C -H 'Content-Type: application/json' -d '{"username":"ktwh","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TVIEW=$(C -H 'Content-Type: application/json' -d '{"username":"ktviewer","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TPLAN" ] && [ -n "$TWH" ] && [ -n "$TADM" ] && [ -n "$TVIEW" ] \
  && ok "planner/warehouse/admin/viewer 登录" || bad "登录失败"
A() { echo "Authorization: Bearer $1"; }

echo "== 3. 未认证 401（四端点，HTTP 层）="
EQ "POST /mrp/kitting/checks 401" "$(HTTP -X POST $B/api/mrp/kitting/checks)" "401"
EQ "GET /mrp/kitting/shortages 401" "$(HTTP $B/api/mrp/kitting/shortages)" "401"
EQ "GET kitting-detail 401" "$(HTTP $B/api/mrp/mos/x/kitting-detail)" "401"
EQ "POST from-shortage 401" "$(HTTP -X POST $B/api/mrp/suggestions/from-shortage)" "401"

echo "== 4. 造数（物料+发布 BOM+替代关系）="
mkitem() {
  C -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"itemName\":\"齐套冒烟$1\",\"categoryCode\":\"RTSK\",\"baseUnit\":\"PC\",\"materialGroup\":\"STRUCT\",\"purchaseType\":\"$2\",\"storageCondition\":\"NORMAL\",\"batchFlag\":\"0\",\"packingSpec\":\"箱\",\"dupNote\":\"齐套检查冒烟物料，非业务重复数据\"}" \
    "$B/api/mdm/items?forceCreate=true" | J "d['data']['itemCode']"
}
P1=$(mkitem 产品A MAKE); C1=$(mkitem 子件A BUY); ALT=$(mkitem 替代料A BUY)
P2=$(mkitem 产品B MAKE)
[ -n "$P1" ] && [ -n "$C1" ] && [ -n "$ALT" ] && [ -n "$P2" ] \
  && ok "物料 P1=$P1 C1=$C1 ALT=$ALT P2=$P2" || bad "建物料失败"
publishBom() {
  local id=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"head\":{\"parentItemCode\":\"$1\"},\"items\":[{\"itemCode\":\"$2\",\"qty\":$3,\"lossRate\":0}]}" \
    $B/api/mrp/boms | J "d['data']['id']")
  SQL "UPDATE erp_mrp_bom SET STATUS='PUBLISHED', PUBLISH_BY='smoke', PUBLISH_AT=NOW() WHERE ID='$id';" >/dev/null
  echo "$id"
}
BID1=$(publishBom "$P1" "$C1" 2)
BID2=$(publishBom "$P2" "$C1" 3)
BB=$(SQL "SELECT COUNT(*) FROM erp_mrp_bom WHERE ID IN ('$BID1','$BID2') AND STATUS='PUBLISHED'")
EQ "两产品 BOM 发布" "$BB" "2"
CI1=$(SQL "SELECT ID FROM erp_mrp_bom_item WHERE BOM_ID='$BID1' AND ITEM_CODE='$C1'")
SQL "INSERT IGNORE INTO erp_mrp_bom_substitute (ID, BOM_ITEM_ID, SUBSTITUTE_ITEM_CODE, SUBSTITUTE_ITEM_NAME, RATIO, PRIORITY)
     VALUES ('sub-ksk-$CI1','$CI1','$ALT','齐套冒烟替代料A',1.2,1);" >/dev/null
SUBN=$(SQL "SELECT COUNT(*) FROM erp_mrp_bom_substitute WHERE BOM_ITEM_ID='$CI1'")
EQ "替代关系挂接" "$SUBN" "1"

echo "== 5. 建单（初始缺料；保持 PLANNED 以免调度器中途重建缺料行）="
mkmo() {
  C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
    -d "{\"head\":{\"productCode\":\"$1\",\"qty\":10,\"planStartDate\":\"$(date +%F)\",\"planEndDate\":\"$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F)\",\"priority\":5}}" \
    $B/api/mrp/mos | J "d['data']['mo']['id']"
}
MO1=$(mkmo "$P1"); MO2=$(mkmo "$P2")
[ -n "$MO1" ] && [ -n "$MO2" ] && ok "两工单创建 MO1=$MO1 MO2=$MO2" || bad "建单失败"
FLAG1=$(SQL "SELECT SHORTAGE_FLAG FROM erp_mrp_mo WHERE ID='$MO1'")
EQ "创建即缺料打标（无库存）" "$FLAG1" "1"
ST1=$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$MO1'")
EQ "初始 PLANNED（步骤 11 才置 RELEASED，避开调度重建竞争）" "$ST1" "PLANNED"

echo "== 6. 5.5.1 单校验 + 逐行明细（替代候选/可抵量）="
R1=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d "{\"moIds\":[\"$MO1\"]}" $B/api/mrp/kitting/checks | J "d['data'][0]['rate']")
NEAR "单校验返回齐套率 0（无库存）" "$R1" "0"
DET=$(C -H "$(A $TPLAN)" "$B/api/mrp/mos/$MO1/kitting-detail")
DLINE=$(echo "$DET" | J "len(d['data']['lines'])")
EQ "明细行数 = BOM 1 行" "$DLINE" "1"
DREQ=$(echo "$DET" | J "d['data']['lines'][0]['reqQty']")
NEAR "需求 = 10×2 = 20" "$DREQ" "20"
DRATE=$(echo "$DET" | J "d['data']['rate']")
NEAR "明细齐套率 = 0" "$DRATE" "0"
DSUB=$(echo "$DET" | J "len(d['data']['lines'][0]['substitutes'])")
EQ "缺料行返回替代候选 1 条" "$DSUB" "1"
DCOV=$(echo "$DET" | J "d['data']['lines'][0]['substitutes'][0]['coverableQty']")
NEAR "可抵量 = 可用÷比例（暂无库存=0）" "$DCOV" "0"
SQL "INSERT INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, QTY, AVAILABLE_QTY) VALUES ('st-ksk-alt','$ALT','齐套冒烟替代料A',80,80);" >/dev/null
DET2=$(C -H "$(A $TPLAN)" "$B/api/mrp/mos/$MO1/kitting-detail")
DCOV2=$(echo "$DET2" | J "d['data']['lines'][0]['substitutes'][0]['coverableQty']")
NEAR "替代库存 80 → 可抵 80÷1.2≈66.6667" "$DCOV2" "66.6667"
SQL "DELETE FROM erp_inv_stock WHERE ID='st-ksk-alt';" >/dev/null

echo "== 7. 批量校验（串行 + 同批去重）="
BR=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"moIds\":[\"$MO1\",\"$MO1\",\"$MO2\",\"no-such-mo\"]}" $B/api/mrp/kitting/checks)
BECH=$(echo "$BR" | J "len(d['data'])")
EQ "同批重复 ID 去重 + 不存在跳过 → 2 条" "$BECH" "2"
BS1=$(echo "$BR" | J "d['data'][0]['shortageFlag']")
EQ "批量结果含缺料标记" "$BS1" "1"

echo "== 8. 5.5.2 缺料聚合（同料多单 + 筛选）="
AGG=$(C -H "$(A $TPLAN)" "$B/api/mrp/kitting/shortages")
AGN=$(echo "$AGG" | J "len(d['data'])")
[ "$AGN" -ge 1 ] && ok "聚合返回 $AGN 组（同料 C1 跨两单）" || bad "聚合为空"
AGC=$(echo "$AGG" | J "[g for g in d['data'] if g['itemCode']=='$C1'][0]['moCount']")
EQ "共用子件缺料工单数 = 2" "$AGC" "2"
AGT=$(echo "$AGG" | J "[g for g in d['data'] if g['itemCode']=='$C1'][0]['totalShortQty']")
NEAR "总缺料量 = 20+30 = 50" "$AGT" "50"
AGSID=$(echo "$AGG" | J "[g for g in d['data'] if g['itemCode']=='$C1'][0]['mos'][0]['shortageId']")
[ -n "$AGSID" ] && [ "$AGSID" != "None" ] && ok "明细携带 shortageId（转建议入参）" || bad "缺 shortageId"
AGF=$(C -H "$(A $TPLAN)" "$B/api/mrp/kitting/shortages?itemCode=$C1" | J "len(d['data'])")
EQ "物料筛选命中 1 组" "$AGF" "1"
AGM=$(C -H "$(A $TPLAN)" "$B/api/mrp/kitting/shortages?moNo=NOT-EXIST-XYZ" | J "len(d['data'])")
EQ "无命中筛选返回空" "$AGM" "0"

echo "== 9. 缺料转采购建议（成功/可见/业务码 422 防重/处置后放行）="
SID1=$(echo "$AGG" | J "[g for g in d['data'] if g['itemCode']=='$C1'][0]['mos'][0]['shortageId']")
CV=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d "{\"shortageIds\":[\"$SID1\"]}" $B/api/mrp/suggestions/from-shortage)
CVN=$(echo "$CV" | J "d['data']['created']")
EQ "转出 1 条建议" "$CVN" "1"
CVREMARK=$(echo "$CV" | J "d['data']['suggestions'][0]['remark']")
CONTAINS "REMARK 含 [SHORTAGE: 来源标识" "$CVREMARK" "[SHORTAGE:"
CVTYPE=$(echo "$CV" | J "d['data']['suggestions'][0]['type']")
EQ "TYPE=PURCHASE" "$CVTYPE" "PURCHASE"
SUGID=$(echo "$CV" | J "d['data']['suggestions'][0]['id']")
VISP=$(C -H "$(A $TPLAN)" "$B/api/mrp/suggestions?type=PURCHASE&status=PENDING&keyword=$C1" \
  | J "len([s for s in d['data'] if '[SHORTAGE:' in (s.get('remark') or '')])")
EQ "5.3.1 列表可见缺料来源建议" "$VISP" "1"
# 防重：业务码 422（HTTP 恒 200）
BIZC=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d "{\"shortageIds\":[\"$SID1\"]}" $B/api/mrp/suggestions/from-shortage)
EQ "未处置重复转业务码 422" "$BIZC" "422"
# 取消后放行
C -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d '{"reason":"冒烟处置"}' \
  "$B/api/mrp/suggestions/$SUGID/cancel" >/dev/null
CV2=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d "{\"shortageIds\":[\"$SID1\"]}" $B/api/mrp/suggestions/from-shortage)
CV2N=$(echo "$CV2" | J "d['data']['created']")
EQ "已处置（取消）后可再转" "$CV2N" "1"

echo "== 10. 权限矩阵（业务码 403；HTTP 层已验 401）="
BIZ403=$(BIZ -X POST -H "$(A $TVIEW)" -H 'Content-Type: application/json' -d "{\"moIds\":[\"$MO1\"]}" $B/api/mrp/kitting/checks)
EQ "viewer 调校验业务码 403" "$BIZ403" "403"
BIZWH=$(BIZ -X POST -H "$(A $TWH)" -H 'Content-Type: application/json' -d "{\"moIds\":[\"$MO1\"]}" $B/api/mrp/kitting/checks)
EQ "warehouse 调校验放行（业务码 200）" "$BIZWH" "200"
BIZCV=$(BIZ -X POST -H "$(A $TWH)" -H 'Content-Type: application/json' -d '{"shortageIds":["x"]}' $B/api/mrp/suggestions/from-shortage)
EQ "warehouse 转建议 403（MrpPlan PLANNER/ADMIN 口径）" "$BIZCV" "403"
BIZDET=$(BIZ -H "$(A $TVIEW)" "$B/api/mrp/mos/$MO1/kitting-detail")
EQ "viewer 查明细放行（读仅需认证）" "$BIZDET" "200"

echo "== 11. 调度器（需后端 --app.kitting.sweep-ms≤20000；先置 RELEASED 进扫描范围）="
SQL "UPDATE erp_mrp_mo SET STATUS='RELEASED' WHERE ID='$MO1';" >/dev/null
# 恢复方向：补足库存（需求 20）→ 扫描清标记 + 推恢复通知
SQL "INSERT INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, QTY, AVAILABLE_QTY) VALUES ('st-ksk-c1','$C1','齐套冒烟子件A',50,50);" >/dev/null
echo "  … 等待调度轮（≤30s）"
SLEPT=0; FLAGA="1"
while [ "$SLEPT" -lt 30 ] && [ "$FLAGA" = "1" ]; do
  sleep 5; SLEPT=$((SLEPT+5))
  FLAGA=$(SQL "SELECT SHORTAGE_FLAG FROM erp_mrp_mo WHERE ID='$MO1'")
done
EQ "调度重检恢复清标记（等 ${SLEPT}s）" "$FLAGA" "0"
NSH=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='MO_KITTING_RESTORED' AND BIZ_ID='$MO1'")
EQ "恢复通知推三角色（按接收方幂等=3 行）" "$NSH" "3"
# 反向：扣光库存 → 扫出新缺料 + 推缺料通知
SQL "DELETE FROM erp_inv_stock WHERE ID='st-ksk-c1';" >/dev/null
SLEPT=0; FLAGB="0"
while [ "$SLEPT" -lt 30 ] && [ "$FLAGB" = "0" ]; do
  sleep 5; SLEPT=$((SLEPT+5))
  FLAGB=$(SQL "SELECT SHORTAGE_FLAG FROM erp_mrp_mo WHERE ID='$MO1'")
done
EQ "调度扫出新缺料（领料方向，等 ${SLEPT}s）" "$FLAGB" "1"
NSH2=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='MO_SHORTAGE' AND BIZ_ID='$MO1'")
EQ "缺料通知推三角色" "$NSH2" "3"

echo "== 12. 通知读取端点（/api/notices 正名）="
UNREAD=$(C -H "$(A $TPLAN)" $B/api/notices/unread-count | J "d['data']")
[ "$UNREAD" -ge 1 ] && ok "未读数 ≥1（实际 $UNREAD）" || bad "未读数异常：$UNREAD"
MYNOTICE=$(C -H "$(A $TPLAN)" $B/api/notices \
  | J "len([n for n in d['data'] if n['bizType'] in ('MO_SHORTAGE','MO_KITTING_RESTORED')])")
[ "$MYNOTICE" -ge 1 ] && ok "planner 可见齐套通知 $MYNOTICE 条" || bad "通知不可见"
NID=$(C -H "$(A $TPLAN)" $B/api/notices | J "d['data'][0]['id'] if d['data'] else ''")
if [ -n "$NID" ]; then
  MR=$(BIZ -X PUT -H "$(A $TPLAN)" "$B/api/notices/$NID/read")
  EQ "标记已读业务码 200" "$MR" "200"
else
  bad "无可读通知（NID 空）"
fi
OLDC=$(HTTP -H "$(A $TPLAN)" "$B/api/crm/notices")
EQ "旧端点 /crm/notices 已迁出（带 token 404）" "$OLDC" "404"

echo ""
echo "=========================="
echo "  PASS=$PASS FAIL=$FAIL"
echo "=========================="
[ "$FAIL" -eq 0 ]
