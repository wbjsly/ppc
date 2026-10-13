#!/bin/zsh
# add-rework-management 全链路 API 冒烟（任务 6.1，参照 reporting-smoke 先例，可重跑）
# 链路：清理→账号→登录→401→造数（物料/BOM/WC/工序/定额/REWORK 路线/NCR/冻结库存）→
#       卡控三阻断→建单+转在制守恒钉住→RW 编码分池→齐套分支（INPUT 钉住/ADDL 缺料补料）→
#       派工预扣 INPUT 跳过→投料范围（422→overAck）→低良率冻结+回拨冲销→重报完工
#       （倒冲豁免+复检加严+成本归集）→二次审批/轮次转移/第三次阻断→入库三件套→
#       强制报废→成本回拨→403/401 矩阵→收尾清理
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
MSG() { C "$@" | J "d['message']"; }
export MYSQL_PWD=root123
SQL() { docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --default-character-set=utf8mb4 -uroot -N -B erp -e "$1" 2>/dev/null; }
A() { echo "Authorization: Bearer $1"; }

echo "== 0. 前置清理（rework 冒烟族，可重跑）="
SQL "
DELETE FROM erp_qms_ncr_log WHERE NCR_ID IN (SELECT ID FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER');
DELETE FROM erp_qms_copq_entry WHERE REMARK LIKE 'REWORK:%';
DELETE FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER' OR NCR_NO LIKE 'NCR-RWK-%';
DELETE FROM erp_mrp_mo_freeze WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '返工冒烟%'));
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE IN ('ReworkSecond','ReportReversal');
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('ReworkSecond','ReportReversal');
DELETE FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER' AND REF_ID LIKE '%#REWORK';
DELETE FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER';
DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('YIELD_ALERT','YIELD_UNFREEZE','REWORK_ECON','REWORK_SCRAP','REWORK_SECOND_RESULT','REVERSAL_RESULT');
DELETE FROM erp_inv_transaction WHERE BIZ_DOC_TYPE LIKE 'REWORK_%';
DELETE FROM erp_mrp_mo_bom WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_mrp_mo_op WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo);
DELETE FROM erp_mrp_output WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '返工冒烟%'));
DELETE FROM erp_mrp_feed WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '返工冒烟%'));
DELETE FROM erp_mrp_reporting WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '返工冒烟%'));
DELETE FROM erp_mrp_dispatch WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '返工冒烟%'));
DELETE FROM erp_mrp_reservation WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '返工冒烟%'));
DELETE FROM erp_mrp_mo_shortage WHERE MO_ID IN (SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '返工冒烟%'));
DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '返工冒烟%');
DELETE FROM erp_inv_stock WHERE ITEM_NAME LIKE '返工冒烟%' OR BATCH_NO IN ('B-RWK','B-RWKC');
DELETE FROM erp_inv_freeze WHERE ITEM_CODE IN (SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '返工冒烟%') OR FREEZE_NO LIKE 'FZ-RWK-%';
DELETE FROM erp_sd_return_line WHERE RETURN_ID LIKE 'rt-rwk-%';
DELETE FROM erp_sd_return WHERE ID LIKE 'rt-rwk-%';
DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID LIKE 'rt-rwk-%';
DELETE FROM erp_mrp_routing WHERE ID LIKE 'rt-rwk-%';
DELETE FROM erp_mrp_op_wc_standard WHERE WC_CODE LIKE 'RWKWC%';
DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'RWKWC%';
DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'RWKOP%';
DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '返工冒烟%';
DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'user-rw%';
DELETE FROM erp_admin_user WHERE USERNAME LIKE 'rw%';
INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX)
VALUES ('cat-rpt','RPTK','报工冒烟分类','RPTK');" >/dev/null 2>&1 \
  && ok "清理完成（RPTK 分类就位）" || bad "清理失败"

echo "== 1. 账号 =="
AHASH=$(SQL "SELECT PASSWORD FROM erp_admin_user WHERE USERNAME='admin' LIMIT 1")
mkuser() {
  SQL "INSERT IGNORE INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, CREATE_BY)
       VALUES ('user-$1','$1','$AHASH','$2','1','smoke');
       INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) VALUES ('ur-$1','user-$1','$3');" >/dev/null 2>&1
}
mkuser rwplan    "返工计划员"   role-planner
mkuser rwprm     "返工计划主管" role-plan-mgr
mkuser rwq       "返工质量主管" role-quality-mgr
mkuser rwfloor   "返工操作工"   role-shop-floor
mkuser rwviewer  "返工围观"     role-user
N=$(SQL "SELECT COUNT(*) FROM erp_admin_user WHERE USERNAME IN ('rwplan','rwprm','rwq','rwfloor','rwviewer')")
EQ "五账号就位" "$N" "5"

echo "== 2. 登录 =="
TPLAN=$(C -H 'Content-Type: application/json' -d '{"username":"rwplan","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TMRM=$(C -H 'Content-Type: application/json' -d '{"username":"rwprm","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TQ=$(C -H 'Content-Type: application/json' -d '{"username":"rwq","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TFLO=$(C -H 'Content-Type: application/json' -d '{"username":"rwfloor","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TVIEW=$(C -H 'Content-Type: application/json' -d '{"username":"rwviewer","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TPLAN" ] && [ -n "$TQ" ] && [ -n "$TFLO" ] && [ -n "$TADM" ] && [ -n "$TVIEW" ] \
  && ok "plan/prm/quality/floor/admin/viewer 登录" || bad "登录失败"

echo "== 3. 401 矩阵（HTTP 层） =="
EQ "GET /mrp/rework/sources 401" "$(HTTP "$B/api/mrp/rework/sources?srcType=NCR")" "401"
EQ "POST /mrp/rework/create 401" "$(HTTP -X POST $B/api/mrp/rework/create)" "401"
EQ "POST /mrp/rework/second 401" "$(HTTP -X POST $B/api/mrp/rework/second)" "401"
EQ "POST /mrp/rework/scrap 401" "$(HTTP -X POST $B/api/mrp/rework/scrap)" "401"
EQ "POST /mrp/rework/stock-in 401" "$(HTTP -X POST $B/api/mrp/rework/stock-in)" "401"
EQ "GET /mrp/rework/list 401" "$(HTTP $B/api/mrp/rework/list)" "401"

echo "== 4. 造数（物料/BOM/REWORK 路线/NCR/冻结库存） =="
mkitem() {
  C -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"itemName\":\"返工冒烟$1\",\"categoryCode\":\"RPTK\",\"baseUnit\":\"PC\",\"materialGroup\":\"STRUCT\",\"purchaseType\":\"$2\",\"storageCondition\":\"NORMAL\",\"batchFlag\":\"0\",\"packingSpec\":\"箱\",\"dupNote\":\"返工冒烟物料，非业务重复数据\"}" \
    "$B/api/mdm/items?forceCreate=true" | J "d['data']['itemCode']"
}
P1=$(mkitem 产品 MAKE); C1=$(mkitem 追加料 BUY)
[ -n "$P1" ] && [ -n "$C1" ] && ok "物料 P1=$P1 C1=$C1" || bad "建物料失败"
SQL "UPDATE erp_mdm_item SET STANDARD_COST=50 WHERE ITEM_CODE='$P1';
     UPDATE erp_mdm_item SET STANDARD_COST=100 WHERE ITEM_CODE='$C1';" >/dev/null
BID=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"parentItemCode\":\"$P1\"},\"items\":[{\"itemCode\":\"$C1\",\"qty\":1,\"lossRate\":0}]}" \
  $B/api/mrp/boms | J "d['data']['id']")
SQL "UPDATE erp_mrp_bom SET STATUS='PUBLISHED', PUBLISH_BY='smoke', PUBLISH_AT=NOW() WHERE ID='$BID';" >/dev/null
[ -n "$BID" ] && ok "BOM 发布 BID=$BID" || bad "BOM 失败"
# WC/工序/定额/REWORK 路线（直插——冒烟惯例；ROUTE_TYPE=REWORK）
SQL "
INSERT IGNORE INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, CAL_HOURS, STATUS)
 VALUES ('wc-rwk-in','RWKWC-IN','返工冒烟中心','INTERNAL',8,'1');
INSERT IGNORE INTO erp_mrp_operation (ID, OP_CODE, OP_NAME, STATUS)
 VALUES ('op-rwk1','RWKOP10','返工冒烟工序10','1');
INSERT IGNORE INTO erp_mrp_op_wc_standard (ID, OP_CODE, WC_CODE, SETUP_HOURS, RUN_HOURS)
 VALUES ('std-rwk1','RWKOP10','RWKWC-IN',0,1);
INSERT IGNORE INTO erp_mrp_routing (ID, ITEM_CODE, ITEM_NAME, VERSION_MAJOR, VERSION_MINOR, STATUS, ROUTE_TYPE, PUBLISH_BY, PUBLISH_AT)
 VALUES ('rt-rwk-p1','${P1:-X}','返工冒烟产品',1,0,'PUBLISHED','REWORK','smoke',NOW());
INSERT IGNORE INTO erp_mrp_routing_op (ID, ROUTING_ID, OP_SEQ, OP_CODE, OP_NAME, WC_CODE, WC_NAME, LEAD_TIME, STANDARD_YIELD, KEY_OP_FLAG)
 VALUES ('rto-rwk-10','rt-rwk-p1',10,'RWKOP10','返工冒烟工序10','RWKWC-IN','返工冒烟中心',0,98,0);" >/dev/null 2>&1
RTY=$(SQL "SELECT ROUTE_TYPE FROM erp_mrp_routing WHERE ID='rt-rwk-p1'")
EQ "REWORK 路线直插发布" "$RTY" "REWORK"
# NCR（处置=返工，批准 3）+ 冻结库存 QC=3、追加料可用 50
SQL "INSERT IGNORE INTO erp_qms_ncr (ID, NCR_NO, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, DISPOSITION, STATUS)
     VALUES ('ncr-rwk-1','NCR-RWK-001','$P1','返工冒烟产品','B-RWK',3,'REWORK','REWORKING');
     INSERT IGNORE INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, QC_QTY, AVAILABLE_QTY, WIP_QTY)
     VALUES ('st-rwk-p','$P1','返工冒烟产品','B-RWK',3,3,0,0);
     INSERT IGNORE INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, QC_QTY, AVAILABLE_QTY, WIP_QTY)
     VALUES ('st-rwk-c','$C1','返工冒烟追加料','B-RWKC',50,0,50,0);
     INSERT IGNORE INTO erp_inv_freeze (ID, FREEZE_NO, FREEZE_TYPE, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, REASON, SCOPE, STATUS, APPLY_BY)
     VALUES ('fz-rwk-1','FZ-RWK-001','QUALITY','WH-MAIN','$P1','返工冒烟产品','B-RWK',3,'返工冒烟冻结','BATCH','ACTIVE','smoke');" >/dev/null 2>&1
Q0=$(SQL "SELECT QC_QTY FROM erp_inv_stock WHERE ID='st-rwk-p'")
NEAR "冻结库存就位 QC=3" "$Q0" "3"

echo "== 5. 判定来源候选 =="
SRC=$(C -H "$(A $TPLAN)" "$B/api/mrp/rework/sources?srcType=NCR" | J "[x['srcNo'] for x in d['data'] if x['srcNo']=='NCR-RWK-001'][0]" 2>/dev/null)
EQ "NCR 候选含 NCR-RWK-001" "${SRC:-}" "NCR-RWK-001"
EMPTY=$(C -H "$(A $TPLAN)" "$B/api/mrp/rework/sources?srcType=RETURN" | J "len(d['data'])")
EQ "退货候选（无）0 条" "${EMPTY:-x}" "0"

echo "== 6. 卡控三阻断（C-4.5-13） =="
mkhead() { echo "{\"srcType\":\"NCR\",\"srcNo\":\"NCR-RWK-001\",\"productCode\":\"$P1\",\"qty\":$1}"; }
# ① 超判定数量（批准 3 → 投 4）
BIZ1=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d "{\"head\":$(mkhead 4)}" $B/api/mrp/rework/create)
EQ "超判定数量 422" "$BIZ1" "422"
# ② 财务冻结阻断（叠加 FINANCE 行）
SQL "INSERT IGNORE INTO erp_inv_freeze (ID, FREEZE_NO, FREEZE_TYPE, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, REASON, SCOPE, STATUS, APPLY_BY)
     VALUES ('fz-rwk-fin','FZ-RWK-FIN','FINANCE','WH-MAIN','$P1','返工冒烟产品','B-RWK',3,'财务冻结','BATCH','ACTIVE','smoke');" >/dev/null
BIZ2=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d "{\"head\":$(mkhead 3)}" $B/api/mrp/rework/create)
EQ "财务冻结 422" "$BIZ2" "422"
MSG2=$(MSG -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d "{\"head\":$(mkhead 3)}" $B/api/mrp/rework/create)
CONTAINS "财务冻结提示先解冻" "$MSG2" "财务冻结"
SQL "DELETE FROM erp_inv_freeze WHERE ID='fz-rwk-fin';" >/dev/null
# ③ 非冻结/待处理状态（无库存无冻结批次）
SQL "INSERT IGNORE INTO erp_qms_ncr (ID, NCR_NO, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, DISPOSITION, STATUS)
     VALUES ('ncr-rwk-2','NCR-RWK-002','$P1','返工冒烟产品','B-NOFREEZE',2,'REWORK','REWORKING');" >/dev/null
BIZ3=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"srcType\":\"NCR\",\"srcNo\":\"NCR-RWK-002\",\"productCode\":\"$P1\",\"qty\":2}}" $B/api/mrp/rework/create)
EQ "非冻结态 422" "$BIZ3" "422"
MSG3=$(MSG -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"srcType\":\"NCR\",\"srcNo\":\"NCR-RWK-002\",\"productCode\":\"$P1\",\"qty\":2}}" $B/api/mrp/rework/create)
CONTAINS "非冻结态提示核对判定单" "$MSG3" "冻结"
NOC=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo WHERE SRC_NO='NCR-RWK-002'")
EQ "卡控失败零工单" "$NOC" "0"
# ④ 无 REWORK 路线阻断（借 B-NOFREEZE 之外——用另一产品无路线）
P2=$(mkitem 无路线产品 MAKE)
SQL "INSERT IGNORE INTO erp_qms_ncr (ID, NCR_NO, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, DISPOSITION, STATUS)
     VALUES ('ncr-rwk-3','NCR-RWK-003','$P2','返工冒烟无路线产品','B-RWK3',2,'REWORK','REWORKING');
     INSERT IGNORE INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, QC_QTY, AVAILABLE_QTY, WIP_QTY)
     VALUES ('st-rwk-p2','$P2','返工冒烟无路线产品','B-RWK3',2,2,0,0);
     INSERT IGNORE INTO erp_inv_freeze (ID, FREEZE_NO, FREEZE_TYPE, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, REASON, SCOPE, STATUS, APPLY_BY)
     VALUES ('fz-rwk-2','FZ-RWK-002','QUALITY','WH-MAIN','$P2','返工冒烟无路线产品','B-RWK3',2,'返工冒烟冻结','BATCH','ACTIVE','smoke');" >/dev/null 2>&1
BIZ4=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"srcType\":\"NCR\",\"srcNo\":\"NCR-RWK-003\",\"productCode\":\"$P2\",\"qty\":2}}" $B/api/mrp/rework/create)
EQ "无 REWORK 路线 422" "$BIZ4" "422"
MSG4=$(MSG -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"head\":{\"srcType\":\"NCR\",\"srcNo\":\"NCR-RWK-003\",\"productCode\":\"$P2\",\"qty\":2}}" $B/api/mrp/rework/create)
CONTAINS "无路线提示先编制" "$MSG4" "无已发布返工路线"

echo "== 7. 建单 + 转在制守恒钉住 + RW 编码 =="
RW1=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"head\":$(mkhead 3),\"bom\":[{\"itemCode\":\"$C1\",\"itemName\":\"返工冒烟追加料\",\"unitQty\":1,\"lossRate\":0}]}" \
  $B/api/mrp/rework/create | J "d['data']['id']")
[ -n "$RW1" ] && ok "建单 RW1=$RW1" || bad "建单失败"
RWNO=$(SQL "SELECT MO_NO FROM erp_mrp_mo WHERE ID='$RW1'")
case "$RWNO" in RW-*) ok "RW- 编码分池 $RWNO";; *) bad "编码非 RW-：$RWNO";; esac
QC1=$(SQL "SELECT QC_QTY FROM erp_inv_stock WHERE ID='st-rwk-p'")
WIP1=$(SQL "SELECT WIP_QTY FROM erp_inv_stock WHERE ID='st-rwk-p'")
TOT1=$(SQL "SELECT QTY FROM erp_inv_stock WHERE ID='st-rwk-p'")
NEAR "转在制 QC 3→0" "$QC1" "0"; NEAR "转在制 WIP 0→3" "$WIP1" "3"
NEAR "三池守恒 QTY 不变" "$TOT1" "3"
PIN=$(SQL "SELECT REF_MO_ID FROM erp_inv_freeze WHERE ID='fz-rwk-1'")
EQ "冻结行钉住不释放" "$PIN" "$RW1"
TXN=$(SQL "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='REWORK_WIP'")
EQ "转在制流水 1 行" "$TXN" "1"
BOMN=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_bom WHERE MO_ID='$RW1'")
EQ "BOM 快照 2 行（INPUT+ADDL）" "$BOMN" "2"
LINET=$(SQL "SELECT LINE_TYPE FROM erp_mrp_mo_bom WHERE MO_ID='$RW1' AND ITEM_CODE='$C1'")
EQ "追加料行 ADDL 打标" "$LINET" "ADDL"
# 候选退出
GONE=$(C -H "$(A $TPLAN)" "$B/api/mrp/rework/sources?srcType=NCR" | J "[x for x in d['data'] if x['srcNo']=='NCR-RWK-001'].__len__()")
EQ "已建单候选退出" "${GONE:-1}" "0"

echo "== 8. 齐套分支（INPUT 钉住满足 / ADDL 缺料） =="
SQL "DELETE FROM erp_inv_stock WHERE ID='st-rwk-c';" >/dev/null   # 追加料置缺
C -H "$(A $TADM)" -X POST -H 'Content-Type: application/json' -d "{\"moIds\":[\"$RW1\"]}" $B/api/mrp/kitting/checks >/dev/null 2>&1
SH=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_shortage WHERE MO_ID='$RW1' AND DEL_FLAG='0'")
EQ "追加料缺料 1 行（INPUT 不缺）" "$SH" "1"
SHI=$(SQL "SELECT ITEM_CODE FROM erp_mrp_mo_shortage WHERE MO_ID='$RW1' AND DEL_FLAG='0' LIMIT 1")
EQ "缺料=追加料（非投入品）" "$SHI" "$C1"
SQL "INSERT IGNORE INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, QC_QTY, AVAILABLE_QTY, WIP_QTY)
     VALUES ('st-rwk-c','$C1','返工冒烟追加料','B-RWKC',50,0,50,0);" >/dev/null
C -H "$(A $TADM)" -X POST -H 'Content-Type: application/json' -d "{\"moIds\":[\"$RW1\"]}" $B/api/mrp/kitting/checks >/dev/null 2>&1
SH2=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_shortage WHERE MO_ID='$RW1' AND DEL_FLAG='0'")
EQ "补料后缺料清零（齐套 100%）" "$SH2" "0"

echo "== 9. 派工 + 预扣 INPUT 跳过 =="
SQL "UPDATE erp_mrp_mo SET STATUS='RELEASED' WHERE ID='$RW1';" >/dev/null
DPR=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"rows\":[{\"moId\":\"$RW1\",\"opSeq\":10,\"wcCode\":null}]}" \
  $B/api/mrp/dispatch/generate | J "d['data'][0]['id']")
[ -n "$DPR" ] && ok "RW 派工生成 DPR=$DPR" || bad "派工生成失败"
C -H "$(A $TADM)" -X POST $B/api/mrp/dispatch/confirm/$DPR >/dev/null
DST=$(SQL "SELECT STATUS FROM erp_mrp_dispatch WHERE ID='$DPR'")
EQ "派工确认 RELEASED" "$DST" "RELEASED"
RESP=$(SQL "SELECT COUNT(*) FROM erp_mrp_reservation WHERE MO_ID='$RW1' AND ITEM_CODE='$P1'")
EQ "INPUT 行不建预扣（WIP 口径）" "$RESP" "0"
RESC=$(SQL "SELECT COUNT(*) FROM erp_mrp_reservation WHERE MO_ID='$RW1' AND ITEM_CODE='$C1'")
EQ "ADDL 行正常预扣" "$RESC" "1"

echo "== 10. 投料范围（超返工 BOM 确认） =="
RID=$(C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"dispatchId\":\"$DPR\"}" $B/api/mrp/reporting/open | J "d['data']['id']")
[ -n "$RID" ] && ok "开工 RID=$RID" || bad "开工失败"
AV0=$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-rwk-c'")
# 范围内（需求=3×1=3；先投 2）
C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID\",\"itemCode\":\"$C1\",\"batchCode\":\"B-RWKC\",\"qty\":2}" \
  $B/api/mrp/reporting/feed >/dev/null
AV1=$(SQL "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID='st-rwk-c'")
NEAR "范围内投料 2 即扣" "$AV1" "$(python3 -c "print(float('$AV0')-2)")"
# 超范围（2+2>3）422 → overAck 放行
BIZF=$(BIZ -X POST -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID\",\"itemCode\":\"$C1\",\"batchCode\":\"B-RWKC\",\"qty\":2}" $B/api/mrp/reporting/feed)
EQ "超返工 BOM 范围 422" "$BIZF" "422"
MSGF=$(MSG -X POST -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID\",\"itemCode\":\"$C1\",\"batchCode\":\"B-RWKC\",\"qty\":2}" $B/api/mrp/reporting/feed)
CONTAINS "超范围提示确认" "$MSGF" "超返工 BOM 范围"
BIZF2=$(BIZ -X POST -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID\",\"itemCode\":\"$C1\",\"batchCode\":\"B-RWKC\",\"qty\":2,\"overAck\":true}" $B/api/mrp/reporting/feed)
EQ "overAck 确认放行 200" "$BIZF2" "200"

echo "== 11. 低良率冻结 + 冲销回拨解冻 =="
C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID\",\"goodQty\":2,\"ngQty\":1,\"ngDispose\":\"SCRAP\"}" $B/api/mrp/reporting/output >/dev/null
C -H "$(A $TFLO)" -X POST $B/api/mrp/reporting/confirm/$RID >/dev/null
CF=$(SQL "SELECT STATUS FROM erp_mrp_reporting WHERE ID='$RID'")
EQ "报工确认 CONFIRMED" "$CF" "CONFIRMED"
FZ=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_freeze WHERE MO_ID='$RW1' AND REASON='YIELD' AND ACTIVE_FLAG='1'")
EQ "良率超差冻结（本 RW 单）" "$FZ" "1"
AL=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='YIELD_ALERT'")
[ "$AL" -ge 1 ] && ok "YIELD_ALERT 推送 $AL" || bad "无良率预警"
BIZC=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$RW1\",\"opSeq\":10,\"wcCode\":null}" $B/api/mrp/dispatch/generate)
EQ "冻结期再派工 422（5.6 D12 挂点）" "$BIZC" "422"
# 冲销回拨解冻
BIZR=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID\",\"reason\":\"返工冒烟回拨\"}" $B/api/mrp/reporting/reversal)
EQ "冲销申请受理 200" "$BIZR" "200"
TID=$(C -H "$(A $TMRM)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='ReportReversal'][0]" 2>/dev/null)
[ -n "$TID" ] && ok "冲销审批待办 TID=$TID" || bad "无冲销待办"
C -H "$(A $TMRM)" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TID\",\"opinion\":\"同意回拨\"}" -X POST $B/api/qms/approvals/pass >/dev/null
RST=$(SQL "SELECT STATUS FROM erp_mrp_reporting WHERE ID='$RID'")
EQ "冲销执行 REVERSED" "$RST" "REVERSED"
FZ2=$(SQL "SELECT COUNT(*) FROM erp_mrp_mo_freeze WHERE MO_ID='$RW1' AND ACTIVE_FLAG='1'")
EQ "回拨解冻（活动冻结清零）" "$FZ2" "0"
UN=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='YIELD_UNFREEZE'")
[ "$UN" -ge 1 ] && ok "解冻通知 $UN" || bad "无解冻通知"

echo "== 12. 重报完工：倒冲豁免 + 复检加严 + 成本归集 =="
SQL "UPDATE erp_qms_inspection_lot SET STATUS='RELEASED', RESULT='PASS' WHERE REF_TYPE='WORKORDER' AND REF_ID LIKE CONCAT('$RW1','%');" >/dev/null 2>&1
RID2=$(C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"dispatchId\":\"$DPR\"}" $B/api/mrp/reporting/open | J "d['data']['id']")
[ -n "$RID2" ] && ok "重报开工 RID2=$RID2" || bad "重报开工失败"
# 冲销已回加原投料 → 重投 2（材料成本 2×100=200 → 总 260 > 重置 150 触发经济性）
C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID2\",\"itemCode\":\"$C1\",\"batchCode\":\"B-RWKC\",\"qty\":2,\"overAck\":true}" $B/api/mrp/reporting/feed >/dev/null
C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID2\",\"setupHours\":0,\"runHours\":1,\"machineHours\":0}" $B/api/mrp/reporting/hours >/dev/null
C -H "$(A $TFLO)" -H 'Content-Type: application/json' \
  -d "{\"rptId\":\"$RID2\",\"goodQty\":3,\"ngQty\":0}" $B/api/mrp/reporting/output >/dev/null
C -H "$(A $TFLO)" -X POST $B/api/mrp/reporting/confirm/$RID2 >/dev/null
CF2=$(SQL "SELECT STATUS FROM erp_mrp_reporting WHERE ID='$RID2'")
EQ "重报确认 CONFIRMED" "$CF2" "CONFIRMED"
SQL "UPDATE erp_qms_inspection_lot SET STATUS='RELEASED', RESULT='PASS' WHERE REF_TYPE='WORKORDER' AND REF_ID LIKE CONCAT('$RW1','%');" >/dev/null
COMP=$(C -H "$(A $TADM)" -H 'Content-Type: application/json' -X POST $B/api/mrp/reporting/complete/$RW1 | J "d['code']")
EQ "完工接管 200" "$COMP" "200"
BF=$(SQL "SELECT COUNT(*) FROM erp_mrp_feed WHERE MO_ID='$RW1' AND FLOW_TYPE='BFLUSH'")
EQ "RW 倒冲豁免（零 BFLUSH）" "$BF" "0"
MOS=$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$RW1'")
EQ "工单 COMPLETED" "$MOS" "COMPLETED"
LOTN=$(SQL "SELECT COUNT(*) FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER' AND REF_ID='$RW1#REWORK'")
EQ "完工触发复检批 1 个" "$LOTN" "1"
STR=$(SQL "SELECT STRICTNESS FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER' AND REF_ID='$RW1#REWORK'")
EQ "复检加严一档 TIGHTENED" "$STR" "TIGHTENED"
# 成本：材料 2+2=4×100=400 + 工时 1×60=60 → 460（费率兜底参数）
COST=$(C -H "$(A $TPLAN)" $B/api/mrp/rework/cost/$RW1 | J "float(d['data'].get('amount',0))")
NEAR "成本归集 260（料200+工60）挂 COPQ" "$COST" "260"
ECON=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='REWORK_ECON'")
[ "$ECON" -ge 1 ] && ok "经济性提示（260>150 重置成本）" || bad "无经济性提示"
PH=$(C -H "$(A $TPLAN)" $B/api/mrp/rework/phase/$RW1 | J "d['data']")
EQ "阶段推导 PENDING_INSP" "$PH" "PENDING_INSP"

echo "== 13. 二次审批 + 轮次转移 + 第三次阻断 =="
BIZS=$(BIZ -X POST -H "$(A $TQ)" -H 'Content-Type: application/json' \
  -d '{"srcNo":"NCR-RWK-001"}' $B/api/mrp/rework/second)
EQ "质量侧发起二次审批 200" "$BIZS" "200"
TID2=$(C -H "$(A $TQ)" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='ReworkSecond'][0]" 2>/dev/null)
[ -n "$TID2" ] && ok "二次返工待办 TID=$TID2" || bad "无二次返工待办"
C -H "$(A $TQ)" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TID2\",\"opinion\":\"同意二次返工\"}" -X POST $B/api/qms/approvals/pass >/dev/null
APV=$(SQL "SELECT COUNT(*) FROM erp_sys_approval WHERE BIZ_TYPE='ReworkSecond' AND STATUS='APPROVED'")
EQ "二次审批 APPROVED" "$APV" "1"
WIPB=$(SQL "SELECT WIP_QTY FROM erp_inv_stock WHERE ID='st-rwk-p'")
RW2=$(C -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"head\":$(mkhead 3)}" $B/api/mrp/rework/create | J "d['data']['id']")
[ -n "$RW2" ] && ok "轮次转移后第二张 RW2=$RW2" || bad "第二张建单失败"
WIPA=$(SQL "SELECT WIP_QTY FROM erp_inv_stock WHERE ID='st-rwk-p'")
QCA=$(SQL "SELECT QC_QTY FROM erp_inv_stock WHERE ID='st-rwk-p'")
NEAR "轮次转移账不动（WIP 仍 $WIPB）" "$WIPA" "$WIPB"
NEAR "QC 不重复转出（仍 0）" "$QCA" "0"
PIN2=$(SQL "SELECT REF_MO_ID FROM erp_inv_freeze WHERE ID='fz-rwk-1'")
EQ "钉住转移到 RW2" "$PIN2" "$RW2"
BIZT=$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"head\":$(mkhead 1)}" $B/api/mrp/rework/create)
EQ "第三张阻断 422" "$BIZT" "422"
MSGT=$(MSG -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"head\":$(mkhead 1)}" $B/api/mrp/rework/create)
CONTAINS "BR-4.5-02 不得第三次" "$MSGT" "不得创建第三次"

echo "== 14. 强制报废（RW2 终局） =="
# 冒烟短路：直接造 RW2 复检 FAIL 批（引擎判定链由单测覆盖），报废走真实服务
SQL "INSERT IGNORE INTO erp_qms_inspection_lot (ID, LOT_NO, ITEM_CODE, LOT_TYPE, REF_TYPE, REF_ID, STATUS, RESULT)
     VALUES ('lot-rwk-f','LOT-RWK-F','$P1','OQC','WORKORDER','$RW2#REWORK','COMPLETED','FAIL');
     UPDATE erp_mrp_mo SET STATUS='COMPLETED' WHERE ID='$RW2';
     UPDATE erp_mrp_mo_op SET OP_STATUS='DONE' WHERE MO_ID='$RW2';" >/dev/null 2>&1
BIZSC=$(BIZ -X POST -H "$(A $TQ)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$RW2\"}" $B/api/mrp/rework/scrap)
EQ "强制报废执行 200" "$BIZSC" "200"
WIPZ=$(SQL "SELECT WIP_QTY FROM erp_inv_stock WHERE ID='st-rwk-p'")
TOTZ=$(SQL "SELECT QTY FROM erp_inv_stock WHERE ID='st-rwk-p'")
NEAR "报废后在制 0" "$WIPZ" "0"
NEAR "报废出账总量 0（守恒）" "$TOTZ" "0"
FZR=$(SQL "SELECT STATUS FROM erp_inv_freeze WHERE ID='fz-rwk-1'")
EQ "冻结行释放（报废终局）" "$FZR" "RELEASED"
SC=$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$RW2'")
EQ "RW2 关闭终局 CLOSED" "$SC" "CLOSED"
SCT=$(SQL "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='REWORK_SCRAP'")
EQ "报废凭证流水 1 行" "$SCT" "1"
SCN=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='REWORK_SCRAP'")
EQ "报废双通知（质量+计划）" "$SCN" "2"
# 非末轮不可报废（RW1 钉住已转移 → 422）
BIZNC=$(BIZ -X POST -H "$(A $TQ)" -H 'Content-Type: application/json' \
  -d "{\"moId\":\"$RW1\"}" $B/api/mrp/rework/scrap)
EQ "RW1 非当前轮次报废 422" "$BIZNC" "422"

echo "== 15. 权限矩阵（业务码） =="
EQ "操作工来源候选 403" "$(BIZ -H "$(A $TFLO)" "$B/api/mrp/rework/sources?srcType=NCR")" "403"
EQ "操作工建单 403" "$(BIZ -X POST -H "$(A $TFLO)" -H 'Content-Type: application/json' -d "$(mkhead 1)" $B/api/mrp/rework/create)" "403"
EQ "操作工列表 403" "$(BIZ -H "$(A $TFLO)" $B/api/mrp/rework/list)" "403"
EQ "围观建单 403" "$(BIZ -X POST -H "$(A $TVIEW)" -H 'Content-Type: application/json' -d "$(mkhead 1)" $B/api/mrp/rework/create)" "403"
EQ "计划员发起二次审批 403（质量侧）" "$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d '{"srcNo":"NCR-RWK-001"}' $B/api/mrp/rework/second)" "403"
EQ "计划员强制报废 403（质量侧）" "$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d '{"moId":"'$RW1'"}' $B/api/mrp/rework/scrap)" "403"
EQ "质量侧建单 403（计划侧）" "$(BIZ -X POST -H "$(A $TQ)" -H 'Content-Type: application/json' -d "$(mkhead 1)" $B/api/mrp/rework/create)" "403"
# 计划侧读可、质量侧读可
EQ "计划员列表 200" "$(BIZ -H "$(A $TPLAN)" $B/api/mrp/rework/list)" "200"
EQ "质量侧列表 200" "$(BIZ -H "$(A $TQ)" $B/api/mrp/rework/list)" "200"

echo ""
echo "==================== PASS=$PASS FAIL=$FAIL ===================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
