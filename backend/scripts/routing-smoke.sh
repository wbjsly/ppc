#!/bin/zsh
# add-routing-management 全链路 API 冒烟（任务 8.3）
# 链路：登录→建工序/工作中心/定额→装配（无定额阻断→补定额放行）→提交→403 矩阵→
#       主管通过→发布→契约查询→变更→旧版 REVISED→在途阻断→废止→三角色矩阵
set -u
B=http://127.0.0.1:8090
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✘ $1"; }
C() { curl -s --noproxy '*' "$@"; }
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
EQ() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望[$3] 实际[$2]）"; fi; }
CONTAINS() { case "$2" in *"$3"*) ok "$1";; *) bad "$1（未包含[$3]，实际[$2]）";; esac; }

echo "== 0. 前置清理（可重跑） =="
export MYSQL_PWD=root123
docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot erp -e "
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='RoutingPublish';
DELETE FROM erp_sys_approval WHERE BIZ_TYPE='RoutingPublish';
DELETE ro FROM erp_mrp_routing_op ro JOIN erp_mrp_routing r ON ro.ROUTING_ID=r.ID
 WHERE r.ITEM_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '路由冒烟%');
DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN
 (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '路由冒烟%');
DELETE ro FROM erp_mrp_routing_op ro WHERE ro.ROUTING_ID NOT IN (SELECT ID FROM erp_mrp_routing);
DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE LIKE 'RS-OP%' OR WC_CODE LIKE 'RS-WC%';
DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'RS-OP%';
DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'RS-WC%';
DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '路由冒烟%';" >/dev/null 2>&1 \
  && ok "清理完成" || bad "清理失败"

echo "== 1. 登录三角色 =="
TENG=$(C -H 'Content-Type: application/json' -d '{"username":"bom-eng","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TMGR=$(C -H 'Content-Type: application/json' -d '{"username":"bom-mgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TVIEW=$(C -H 'Content-Type: application/json' -d '{"username":"bom-viewer","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TENG" ] && [ -n "$TMGR" ] && [ -n "$TADM" ] && [ -n "$TVIEW" ] && ok "eng/mgr/admin/viewer 登录" || bad "登录失败"

echo "== 2. 未认证 401 =="
EQ "GET /api/mrp/operations 401" "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' $B/api/mrp/operations)" "401"
EQ "POST /api/mrp/routings 401" "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST $B/api/mrp/routings)" "401"
EQ "GET /api/mrp/op-wc-standards 401" "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' $B/api/mrp/op-wc-standards)" "401"

echo "== 3. 取产品物料 =="
CAT=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e "SELECT CATEGORY_CODE FROM erp_mdm_item_category ORDER BY CATEGORY_CODE LIMIT 1")
mkitem() {
  C -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d "{\"itemName\":\"$1\",\"categoryCode\":\"$CAT\",\"baseUnit\":\"PC\",\"materialGroup\":\"STRUCT\",\"purchaseType\":\"BUY\",\"storageCondition\":\"NORMAL\",\"batchFlag\":\"0\",\"packingSpec\":\"箱\",\"dupNote\":\"routing 冒烟物料，非业务数据\"}" "$B/api/mdm/items?forceCreate=true" | J "d['data']['itemCode']"
}
P1=$(mkitem "路由冒烟产品一"); P2=$(mkitem "路由冒烟产品二")
if [ -z "$P1" ] || [ -z "$P2" ]; then bad "建产品失败"; echo "== 结果: PASS=$PASS FAIL=$FAIL"; exit 1; fi
ok "产品 P1=$P1 P2=$P2"

echo "== 4. 工序字典 =="
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"opCode":"RS-OP-10","opName":"冒烟装配","skillReq":"测试"}' $B/api/mrp/operations)
EQ "工序创建 code=200" "$(echo $RES | J "d['code']")" "200"
OPID=$(echo $RES | J "d['data']['id']")
RES=$(C -X PUT -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d "{\"id\":\"$OPID\",\"opCode\":\"RS-OP-CHG\",\"opName\":\"改名\"}" $B/api/mrp/operations/$OPID)
EQ "改码 422" "$(echo $RES | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"opCode":"RS-OP-10","opName":"重复"}' $B/api/mrp/operations)
EQ "编码重复 422" "$(echo $RES | J "d['code']")" "422"

echo "== 5. 工作中心 =="
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"wcCode":"RS-WC-10","wcName":"冒烟线","wcType":"INTERNAL","calHours":8,"equipAvail":95,"laborAvail":90}' $B/api/mrp/work-centers)
EQ "工作中心创建 200" "$(echo $RES | J "d['code']")" "200"
WCID=$(echo $RES | J "d['data']['id']")
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"wcCode":"RS-WC-20","wcName":"外协缺供方","wcType":"OUTSOURCED"}' $B/api/mrp/work-centers)
EQ "外协缺供应商 422" "$(echo $RES | J "d['code']")" "422"
CONTAINS "外协错误提示含供应商" "$(echo $RES | J "d['message']")" "供应商"
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"wcCode":"RS-WC-30","wcName":"产能非法","wcType":"INTERNAL","equipAvail":150}' $B/api/mrp/work-centers)
EQ "设备可用率 150 → 422" "$(echo $RES | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"wcCode":"RS-WC-40","wcName":"外协正常","wcType":"OUTSOURCED","supplierCode":"SUP-001"}' $B/api/mrp/work-centers)
EQ "外协带供应商 200" "$(echo $RES | J "d['code']")" "200"

echo "== 6. 定额矩阵 =="
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"opCode":"RS-OP-10","wcCode":"RS-WC-10","setupHours":0.5,"runHours":1.5}' $B/api/mrp/op-wc-standards)
EQ "定额创建 200" "$(echo $RES | J "d['code']")" "200"
STDID=$(echo $RES | J "d['data']['id']")
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"opCode":"RS-OP-10","wcCode":"RS-WC-10"}' $B/api/mrp/op-wc-standards)
EQ "重复组合 422" "$(echo $RES | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"opCode":"RS-OP-10","wcCode":"RS-WC-40","runHours":-1}' $B/api/mrp/op-wc-standards)
EQ "负工时 422" "$(echo $RES | J "d['code']")" "422"

echo "== 7. 装配：无定额阻断 → 补定额放行 =="
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d "{\"head\":{\"itemCode\":\"$P1\"},\"ops\":[{\"opCode\":\"RS-OP-10\",\"wcCode\":\"RS-WC-40\",\"leadTime\":1}]}" $B/api/mrp/routings)
EQ "无定额装配 422" "$(echo $RES | J "d['code']")" "422"
CONTAINS "提示含工序编码" "$(echo $RES | J "d['message']")" "RS-OP-10"
CONTAINS "提示含工作中心编码" "$(echo $RES | J "d['message']")" "RS-WC-40"
CONTAINS "指引 5.2.3" "$(echo $RES | J "d['message']")" "5.2.3"
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d "{\"head\":{\"itemCode\":\"$P1\"},\"ops\":[]}" $B/api/mrp/routings)
EQ "空路线 422" "$(echo $RES | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d "{\"head\":{\"itemCode\":\"$P1\"},\"ops\":[{\"opCode\":\"RS-OP-10\",\"wcCode\":\"RS-WC-10\",\"leadTime\":1}]}" $B/api/mrp/routings)
EQ "装配成功 200" "$(echo $RES | J "d['code']")" "200"
RID=$(echo $RES | J "d['data']['id']")
EQ "首版 V1.0" "$(echo $RES | J "d['data']['versionLabel']")" "1.0"
EQ "状态 DRAFT" "$(echo $RES | J "d['data']['status']")" "DRAFT"
RES=$(C -H "Authorization: Bearer $TENG" "$B/api/mrp/routings/published?itemCode=$P1")
EQ "未发布契约查询空" "$(echo $RES | J "len(d['data'])")" "0"

echo "== 8. 提交与在途 =="
RES=$(C -H "Authorization: Bearer $TENG" -X POST $B/api/mrp/routings/$RID/submit)
EQ "提交 200 → PENDING" "$(echo $RES | J "d['data']['status']")" "PENDING"
RES=$(C -H "Authorization: Bearer $TENG" -X POST $B/api/mrp/routings/$RID/submit)
EQ "重复提交 422" "$(echo $RES | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d "{\"head\":{\"itemCode\":\"$P1\"},\"ops\":[{\"opCode\":\"RS-OP-10\",\"wcCode\":\"RS-WC-10\"}]}" $B/api/mrp/routings)
EQ "在途时再创建 422" "$(echo $RES | J "d['code']")" "422"
CONTAINS "在途提示" "$(echo $RES | J "d['message']")" "在途"

echo "== 9. 权限矩阵 =="
RES=$(C -H "Authorization: Bearer $TENG" -X POST $B/api/mrp/routings/$RID/obsolete)
EQ "ENG 调废止 403" "$(echo $RES | J "d['code']")" "403"
RES=$(C -H "Authorization: Bearer $TVIEW" -H 'Content-Type: application/json' -d '{"opCode":"RS-OP-99","opName":"无权"}' $B/api/mrp/operations)
EQ "非工艺角色维护 403" "$(echo $RES | J "d['code']")" "403"
TASKID=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='RoutingPublish' AND a.BIZ_ID='$RID' AND t.STATUS='ACTIVE'")
CODE=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TASKID\",\"opinion\":\"我想签\"}" $B/api/qms/approvals/pass)
EQ "ENG HTTP 签署 403（SecurityConfig 名单）" "$CODE" "403"

echo "== 10. 主管通过 → 发布 → 契约 =="
RES=$(C -H "Authorization: Bearer $TMGR" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TASKID\",\"opinion\":\"同意发布\"}" $B/api/qms/approvals/pass)
EQ "MGR 签署 200" "$(echo $RES | J "d['code']")" "200"
ST=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e "SELECT STATUS FROM erp_mrp_routing WHERE ID='$RID'")
EQ "回调发布 PUBLISHED" "$ST" "PUBLISHED"
PB=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e "SELECT COUNT(*) FROM erp_mrp_routing WHERE ID='$RID' AND PUBLISH_BY IS NOT NULL AND PUBLISH_AT IS NOT NULL")
EQ "发布留痕（发布人+时间）" "$PB" "1"
RES=$(C -H "Authorization: Bearer $TENG" "$B/api/mrp/routings/published?itemCode=$P1")
EQ "契约取到已发布" "$(echo $RES | J "d['data']['id']")" "$RID"
EQ "契约行含四类工时定额" "$(echo $RES | J "'runHours' in d['data']['ops'][0]['standard']")" "True"
EQ "契约行含产能三要素" "$(echo $RES | J "all(k in d['data']['ops'][0]['workCenter'] for k in ['calHours','equipAvail','laborAvail'])")" "True"

echo "== 11. 定额删除引用保护 =="
RES=$(C -H "Authorization: Bearer $TENG" -X DELETE $B/api/mrp/op-wc-standards/$STDID)
EQ "删除被已发布引用 422" "$(echo $RES | J "d['code']")" "422"

echo "== 12. 变更 → V1.1 → 发布 → 旧版 REVISED =="
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"changeReason":"  "}' $B/api/mrp/routings/$RID/change)
EQ "缺变更原因 422" "$(echo $RES | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d '{"changeReason":"冒烟变更","upgradeMajor":false}' $B/api/mrp/routings/$RID/change)
EQ "变更生成 V1.1 草稿" "$(echo $RES | J "d['data']['versionLabel']")" "1.1"
RID2=$(echo $RES | J "d['data']['id']")
EQ "变更链 change_from_id" "$(echo $RES | J "d['data']['changeFromId']")" "$RID"
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d "{\"head\":{\"itemCode\":\"$P1\"},\"ops\":[{\"opCode\":\"RS-OP-10\",\"wcCode\":\"RS-WC-10\"}]}" $B/api/mrp/routings)
EQ "在途变更草稿时再变更 422" "$(echo $RES | J "d['code']")" "422"
RES=$(C -H "Authorization: Bearer $TENG" -X POST $B/api/mrp/routings/$RID2/submit)
EQ "提交 V1.1 200" "$(echo $RES | J "d['code']")" "200"
TASK2=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='RoutingPublish' AND a.BIZ_ID='$RID2' AND t.STATUS='ACTIVE'")
C -H "Authorization: Bearer $TMGR" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TASK2\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass >/dev/null
ST1=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e "SELECT STATUS FROM erp_mrp_routing WHERE ID='$RID'")
ST2=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e "SELECT STATUS FROM erp_mrp_routing WHERE ID='$RID2'")
EQ "旧版自动 REVISED" "$ST1" "REVISED"
EQ "新版 PUBLISHED（无双已发布）" "$ST2" "PUBLISHED"
CNT=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e "SELECT COUNT(*) FROM erp_mrp_routing WHERE ITEM_CODE='$P1' AND STATUS='PUBLISHED'")
EQ "同产品唯一已发布" "$CNT" "1"

echo "== 13. 驳回回草稿（P2 流程） =="
RES=$(C -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' -d "{\"head\":{\"itemCode\":\"$P2\"},\"ops\":[{\"opCode\":\"RS-OP-10\",\"wcCode\":\"RS-WC-10\"}]}" $B/api/mrp/routings)
RID3=$(echo $RES | J "d['data']['id']")
C -H "Authorization: Bearer $TENG" -X POST $B/api/mrp/routings/$RID3/submit >/dev/null
TASK3=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='RoutingPublish' AND a.BIZ_ID='$RID3' AND t.STATUS='ACTIVE'")
C -H "Authorization: Bearer $TMGR" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TASK3\",\"reason\":\"顺序需调整\"}" $B/api/qms/approvals/reject >/dev/null
ST3=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e "SELECT STATUS FROM erp_mrp_routing WHERE ID='$RID3'")
EQ "驳回回草稿" "$ST3" "DRAFT"
RR=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --default-character-set=utf8mb4 -uroot -N -B erp -e "SELECT REJECT_REASON FROM erp_mrp_routing WHERE ID='$RID3'")
EQ "驳回意见回填" "$RR" "顺序需调整"

echo "== 14. 废止 + ADMIN 矩阵 =="
RES=$(C -H "Authorization: Bearer $TMGR" -X POST $B/api/mrp/routings/$RID/obsolete)
EQ "MGR 废止 REVISED 200" "$(echo $RES | J "d['code']")" "200"
ST=$(docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql -uroot -N -B erp -e "SELECT STATUS FROM erp_mrp_routing WHERE ID='$RID'")
EQ "状态 OBSOLETE" "$ST" "OBSOLETE"
RES=$(C -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"opCode":"RS-OP-ADM","opName":"管理员建工序"}' $B/api/mrp/operations)
EQ "ADMIN 维护放行 200" "$(echo $RES | J "d['code']")" "200"
RES=$(C -H "Authorization: Bearer $TADM" -X POST $B/api/mrp/routings/$RID2/obsolete)
EQ "ADMIN 废止已发布 200" "$(echo $RES | J "d['code']")" "200"

echo "== 15. 版本历史留痕字段 =="
RES=$(C -H "Authorization: Bearer $TENG" "$B/api/mrp/routings?itemCode=$P1")
HAS=$(echo $RES | J "all(k in r for r in d['data'] for k in ['changeReason','changeFromId','publishBy','publishAt','rejectReason','versionLabel'])")
EQ "列表含五类留痕+版本号" "$HAS" "True"

echo ""
echo "== 结果: PASS=$PASS FAIL=$FAIL =="
[ $FAIL -eq 0 ]
