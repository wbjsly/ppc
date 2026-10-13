#!/bin/zsh
# add-cost-aggregation 全链路 API 冒烟（任务 6.1，可重跑）
# 链路：清理→账号→登录→401→造数（物料/工单/投料/报工）→完工自动归集（缺池标记）→
#       关单卡控①缺池阻断→费用池录入（权限/期次唯一）→手动重跑（OH 分摊 10000、
#       凭证不重生成）→差异 RAISED→关单卡控②未审阅阻断→主管审阅→关单通过→
#       关单卡控③未归集阻断→期间已关 DRAFT→下期过账→月度转销（幂等/零差异）→权限矩阵
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
MSG() { C "$@" | J "d['message']"; }
export MYSQL_PWD=root123
# --force：单句报错不中断整段清理（outsource-smoke 两次踩坑的教训）
SQL() { docker exec -i -e MYSQL_PWD=root123 erp-mysql mysql --force --default-character-set=utf8mb4 -uroot -N -B erp -e "$1" 2>/dev/null; }
A() { echo "Authorization: Bearer $1"; }
PERIOD=$(date +%Y%m)

MO1=mo-csmk-1; MO2=mo-csmk-2; MO3=mo-csmk-3; MO4=mo-csmk-4
NO1=CSMK0001; NO2=CSMK0002; NO3=CSMK0003; NO4=CSMK0004

echo "== 0. 前置清理（成本冒烟族 CSTK/CSMK，可重跑）=="
SQL "
DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_TYPE IN ('MO_COMPLETE','MO_VAR_TRANSFER')
   AND (v.SOURCE_DOC_NO LIKE 'CSMK%' OR v.SOURCE_DOC_NO LIKE 'MOVCH%'
        OR v.SOURCE_DOC_NO LIKE 'MOCST%' OR v.SOURCE_TYPE='MO_VAR_TRANSFER');
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('MO_COMPLETE','MO_VAR_TRANSFER')
   AND (SOURCE_DOC_NO LIKE 'CSMK%' OR SOURCE_DOC_NO LIKE 'MOVCH%'
        OR SOURCE_DOC_NO LIKE 'MOCST%' OR SOURCE_TYPE='MO_VAR_TRANSFER');
DELETE FROM erp_mrp_cost_line WHERE MO_ID LIKE 'mo-csmk-%';
DELETE FROM erp_mrp_cost_summary WHERE MO_ID LIKE 'mo-csmk-%';
DELETE FROM erp_mrp_overhead_pool WHERE PERIOD='$PERIOD';
DELETE FROM erp_mrp_feed WHERE MO_ID LIKE 'mo-csmk-%';
DELETE FROM erp_mrp_reporting WHERE MO_ID LIKE 'mo-csmk-%';
DELETE FROM erp_mrp_routing_op WHERE ID='rto-csmk';
DELETE FROM erp_mrp_op_wc_standard WHERE ID='owc-csmk';
-- 确定性关键：清空其他工单的已确认报工 → 分母=本族 6h → 制费分摊=池全额 10000
DELETE FROM erp_mrp_reporting WHERE STATUS='CONFIRMED' AND MO_ID NOT LIKE 'mo-csmk-%';
DELETE FROM erp_mrp_mo WHERE ID LIKE 'mo-csmk-%';
DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'CSTK%';
DELETE FROM erp_mdm_item_category WHERE ID='cat-cstk';
DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'user-csmk%';
DELETE FROM erp_admin_user WHERE USERNAME LIKE 'csmk%';" >/dev/null 2>&1 \
  && ok "清理完成（CSTK 族就位）" || bad "清理失败"

echo "== 1. 账号（成本会计/计划/主管/财务/质检） =="
AHASH=$(SQL "SELECT PASSWORD FROM erp_admin_user WHERE USERNAME='admin' LIMIT 1")
mkuser() {
  SQL "INSERT IGNORE INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, CREATE_BY)
       VALUES ('user-csmk-$1','csmk$1','$AHASH','$2','1','smoke');
       INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) VALUES ('ur-csmk-$1','user-csmk-$1','$3');" >/dev/null 2>&1
}
mkuser cost  "成本冒烟成本会计" role-cost-accountant
mkuser plan   "成本冒烟计划员"   role-planner
mkuser plmgr  "成本冒烟计划主管" role-plan-mgr
mkuser fin    "成本冒烟财务"     role-finance
mkuser qc     "成本冒烟质检员"   role-inspector
N=$(SQL "SELECT COUNT(*) FROM erp_admin_user WHERE USERNAME LIKE 'csmk%'")
EQ "五账号就位" "$N" "5"

echo "== 2. 登录 =="
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TCOST=$(C -H 'Content-Type: application/json' -d '{"username":"csmkcost","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TPLAN=$(C -H 'Content-Type: application/json' -d '{"username":"csmkplan","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TPLM=$(C -H 'Content-Type: application/json' -d '{"username":"csmkplmgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TFIN=$(C -H 'Content-Type: application/json' -d '{"username":"csmkfin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TQC=$(C -H 'Content-Type: application/json' -d '{"username":"csmkqc","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TADM" ] && [ -n "$TCOST" ] && [ -n "$TPLAN" ] && [ -n "$TPLM" ] \
  && [ -n "$TFIN" ] && [ -n "$TQC" ] && ok "六角色登录" || bad "登录失败"

# 参数复位（进程缓存 → 必须走 API）
C -X POST -H "$(A $TADM)" "$B/api/system/params/COST_CLOSED_PERIODS?value=" >/dev/null
C -X POST -H "$(A $TADM)" "$B/api/system/params/STD_LABOR_RATE?value=50&valueType=NUMBER" >/dev/null
C -X POST -H "$(A $TADM)" "$B/api/system/params/COST_VARIANCE_RATE?value=0.05&valueType=NUMBER" >/dev/null

echo "== 3. 401 矩阵 =="
EQ "GET /mrp/cost/list 401" "$(HTTP $B/api/mrp/cost/list)" "401"
EQ "POST /mrp/cost/x/rerun 401" "$(HTTP -X POST $B/api/mrp/cost/x/rerun)" "401"
EQ "POST /mrp/cost/pools 401" "$(HTTP -X POST $B/api/mrp/cost/pools)" "401"
EQ "GET /mrp/cost/vouchers 401" "$(HTTP $B/api/mrp/cost/vouchers)" "401"
EQ "POST /mrp/cost/variance-transfer 401" "$(HTTP -X POST "$B/api/mrp/cost/variance-transfer?period=$PERIOD")" "401"
EQ "POST /system/params/x 401" "$(HTTP -X POST "$B/api/system/params/X?value=1")" "401"

echo "== 4. 造数（产成品/物料/工单/投料/报工） =="
SQL "
INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX)
 VALUES ('cat-cstk','CSTK','成本冒烟分类','CSTK');
INSERT IGNORE INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, MATERIAL_GROUP,
 PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, STATUS, CREATE_BY, DEL_FLAG)
 VALUES ('it-cstk-p','CSTK0001','成本冒烟产成品','CSTK','PC','STRUCT','MAKE','NORMAL','0','0','1','smoke','0'),
        ('it-cstk-c','CSTK0002','成本冒烟组件','CSTK','KG','STRUCT','BUY','NORMAL','1','0','1','smoke','0');
UPDATE erp_mdm_item SET STANDARD_COST=100 WHERE ITEM_CODE='CSTK0001';
UPDATE erp_mdm_item SET STANDARD_COST=5   WHERE ITEM_CODE='CSTK0002';
INSERT IGNORE INTO erp_mrp_mo (ID, MO_NO, MO_TYPE, PRODUCT_CODE, PRODUCT_NAME, QTY, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
 VALUES ('$MO1','$NO1','NORMAL','CSTK0001','成本冒烟产成品',50,'RELEASED','smoke','0',0),
        ('$MO3','$NO3','NORMAL','CSTK0001','成本冒烟产成品',10,'COMPLETED','smoke','0',0),
        ('$MO4','$NO4','NORMAL','CSTK0001','成本冒烟产成品',50,'RELEASED','smoke','0',0);
UPDATE erp_mrp_mo SET QUALIFIED_QTY=10 WHERE ID='$MO3';
INSERT IGNORE INTO erp_mrp_feed (ID, MO_ID, ITEM_CODE, ITEM_NAME, QTY, RETURNED_FLAG, CREATE_BY, DEL_FLAG, VER_NO)
 VALUES ('fd-csmk-1','$MO1','CSTK0002','成本冒烟组件',100,'0','smoke','0',0);
INSERT IGNORE INTO erp_mrp_reporting (ID, RPT_NO, DISPATCH_ID, MO_ID, OP_SEQ, SETUP_HOURS, RUN_HOURS,
 MACHINE_HOURS, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
 VALUES ('rpt-csmk-1','RPTCSMK1','dp-csmk-x','$MO1',10,1,5,0,'CONFIRMED','smoke','0',0);
-- 路线与工序定额（效率差异取数：标准工时 = (0.5+1.5)×20 = 40h）
INSERT IGNORE INTO erp_mrp_routing_op (ID, ROUTING_ID, OP_SEQ, OP_CODE, OP_NAME, WC_CODE, WC_NAME,
 LEAD_TIME, STANDARD_YIELD, KEY_OP_FLAG, CREATE_BY, DEL_FLAG)
 VALUES ('rto-csmk','rt-csmk',10,'CSTOPC','成本冒烟工序','CSWC','成本冒烟中心',0,90,0,'smoke','0');
INSERT IGNORE INTO erp_mrp_op_wc_standard (ID, OP_CODE, WC_CODE, SETUP_HOURS, RUN_HOURS, CREATE_BY, DEL_FLAG)
 VALUES ('owc-csmk','CSTOPC','CSWC',0.5,1.5,'smoke','0');
UPDATE erp_mrp_mo SET SOURCE_ROUTING_ID='rt-csmk' WHERE ID='$MO1';" >/dev/null 2>&1
NEAR "产成品标准成本" "$(SQL "SELECT STANDARD_COST FROM erp_mdm_item WHERE ITEM_CODE='CSTK0001'")" "100"
EQ "工单就位（RELEASED×2/COMPLETED×1）" "$(SQL "SELECT COUNT(*) FROM erp_mrp_mo WHERE ID LIKE 'mo-csmk-%'")" "3"
EQ "投料 100 + 报工 6h 就位" "$(SQL "SELECT CONCAT((SELECT COUNT(*) FROM erp_mrp_feed WHERE MO_ID='$MO1'),
 (SELECT COUNT(*) FROM erp_mrp_reporting WHERE MO_ID='$MO1'))")" "11"

echo "== 5. 完工自动归集（缺池 → 标记不阻断完工）+ 完工凭证 POSTED =="
EQ "完工确认 200" "$(C -H "$(A $TPLAN)" -X POST -H 'Content-Type: application/json' \
  -d '{"qualifiedQty":20}' $B/api/mrp/mos/$MO1/complete | J "d['code']")" "200"
EQ "工单 COMPLETED" "$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$MO1'")" "COMPLETED"
EQ "归集行生成" "$(SQL "SELECT COUNT(*) FROM erp_mrp_cost_summary WHERE MO_ID='$MO1' AND DEL_FLAG='0'")" "1"
NEAR "直接材料 = 100×5 = 500" "$(SQL "SELECT MATERIAL_COST FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "500"
NEAR "直接人工 = 6h×50 = 300" "$(SQL "SELECT LABOR_COST FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "300"
NEAR "制费 0（缺池）" "$(SQL "SELECT OVERHEAD_COST FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "0"
CONTAINS "缺池标记 NO_POOL" "$(SQL "SELECT IFNULL(MARK,'') FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "费用池未维护"
EQ "差异报告 RAISED（实际 800 vs 标准 2000 → 60%>5%）" \
  "$(SQL "SELECT REPORT_STATUS FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "RAISED"
# 完工凭证（生成即 POSTED、三段结构、平衡）
EQ "凭证生成 1 张" "$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='MO_COMPLETE' AND SOURCE_DOC_NO='$NO1'")" "1"
EQ "凭证 POSTED（BR-4.5-30）" "$(SQL "SELECT STATUS FROM erp_fin_voucher WHERE SOURCE_TYPE='MO_COMPLETE' AND SOURCE_DOC_NO='$NO1'")" "POSTED"
EQ "借贷平衡" "$(SQL "SELECT TOTAL_DR=TOTAL_CR FROM erp_fin_voucher WHERE SOURCE_TYPE='MO_COMPLETE' AND SOURCE_DOC_NO='$NO1'")" "1"
VL() { SQL "SELECT l.AMOUNT FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_TYPE='MO_COMPLETE' AND v.SOURCE_DOC_NO='$NO1' AND l.ACCOUNT_CODE='$1' AND l.DIRECTION='$2'"; }
NEAR "借 1405 产成品 = 2000（标准 100×20）" "$(VL 1405 DR)" "2000"
NEAR "贷 500101 材料 = 500" "$(VL 500101 CR)" "500"
NEAR "贷 500102 人工 = 300" "$(VL 500102 CR)" "300"
NEAR "差异 500191 贷 = 1200（补平）" "$(VL 500191 CR)" "1200"
EQ "无制造费用行（缺池不虚记）" "$(VL 500103 CR)" ""

echo "== 6. 关单卡控①：缺池 → L1 阻断 =="
EQ "关闭预检失败" "$(C -H "$(A $TPLAN)" $B/api/mrp/mos/$MO1/close-precheck | J "d['data']['passed']")" "False"
CONTAINS "阻断原因含缺素" "$(MSG -H "$(A $TPLAN)" -X POST $B/api/mrp/mos/$MO1/close)" "NO_POOL"

echo "== 7. 费用池录入（权限/期次唯一/默认规则） =="
EQ "计划员录池 403" "$(BIZ -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d "{\"period\":\"$PERIOD\",\"poolAmt\":10000,\"rule\":\"TIME\"}" $B/api/mrp/cost/pools)" "403"
CR=$(C -H "$(A $TCOST)" -H 'Content-Type: application/json' \
  -d "{\"period\":\"$PERIOD\",\"poolAmt\":10000,\"rule\":\"TIME\"}" $B/api/mrp/cost/pools)
EQ "成本会计录池 200" "$(J "d['code']" <<<"$CR")" "200"
EQ "首次录入 created=true" "$(J "d['data']['created']" <<<"$CR")" "True"
CR2=$(C -H "$(A $TCOST)" -H 'Content-Type: application/json' \
  -d "{\"period\":\"$PERIOD\",\"poolAmt\":7000,\"rule\":\"TIME\"}" $B/api/mrp/cost/pools)
EQ "同期再录 created=false（期次唯一 upsert）" "$(J "d['data']['created']" <<<"$CR2")" "False"
# 恢复 10000（断言口径）
C -H "$(A $TCOST)" -H 'Content-Type: application/json' \
  -d "{\"period\":\"$PERIOD\",\"poolAmt\":10000,\"rule\":\"TIME\"}" $B/api/mrp/cost/pools >/dev/null
EQ "池列表 1 行（本期）" "$(SQL "SELECT COUNT(*) FROM erp_mrp_overhead_pool WHERE PERIOD='$PERIOD'")" "1"
EQ "计划员查池 200（读可见）" "$(BIZ -H "$(A $TPLAN)" $B/api/mrp/cost/pools)" "200"

echo "== 8. 手动重跑（分摊 10000 + 凭证不重生成 + 幂等） =="
EQ "计划员重跑 403（只读）" "$(BIZ -X POST -H "$(A $TPLAN)" $B/api/mrp/cost/$MO1/rerun)" "403"
EQ "成本会计重跑 200" "$(C -H "$(A $TCOST)" -X POST $B/api/mrp/cost/$MO1/rerun | J "d['code']")" "200"
# 分母 = 本族已确认报工 6h（清理段保证）→ OH = 10000×6/6 = 10000
NEAR "制造费用分摊 = 10000（池全额）" "$(SQL "SELECT OVERHEAD_COST FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "10000"
CONTAINS "缺池标记消除" "$(SQL "SELECT IFNULL(MARK,'null') FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "null"
NEAR "总成本 = 500+300+10000 = 10800" "$(SQL "SELECT TOTAL_COST FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "10800"
NEAR "单位成本 = 10800/20 = 540" "$(SQL "SELECT UNIT_COST FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "540"
NEAR "四分解-效率 = (6−40)×50 = −1700" \
  "$(SQL "SELECT VAR_EFFICIENCY FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "-1700"
NEAR "四分解-用量 = (100−0)×5 = 500（无 BOM 快照）" \
  "$(SQL "SELECT VAR_VOLUME FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "500"
NEAR "四分解-产能 = OH×(1−0 无派工负荷) = 10000" \
  "$(SQL "SELECT VAR_CAPACITY FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "10000"
NEAR "四分解-价格恒 0（采购 1406 已确认）" "$(SQL "SELECT VAR_PRICE FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "0"
EQ "重跑不触凭证（仍 1 张）" "$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='MO_COMPLETE' AND SOURCE_DOC_NO='$NO1'")" "1"
# 重跑幂等：再跑一次金额不变
C -H "$(A $TCOST)" -X POST $B/api/mrp/cost/$MO1/rerun >/dev/null
NEAR "二次重跑金额稳定" "$(SQL "SELECT TOTAL_COST FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "10800"
EQ "重跑后明细行数稳定（1 物料行+1 报工行+1 制费行）" \
  "$(SQL "SELECT COUNT(*) FROM erp_mrp_cost_line WHERE MO_ID='$MO1' AND DEL_FLAG='0'")" "3"
# 列表/详情查询
EQ "成本会计查列表 200" "$(BIZ -H "$(A $TCOST)" "$B/api/mrp/cost/list?period=$PERIOD")" "200"
CONTAINS "列表含本工单" "$(C -H "$(A $TCOST)" $B/api/mrp/cost/list)" "$NO1"
EQ "计划员查列表 200（只读可见）" "$(BIZ -H "$(A $TPLAN)" $B/api/mrp/cost/list)" "200"

echo "== 9. 关单卡控②：超阈值差异未审阅 → 阻断；审阅后通过 =="
# 实际 10800 vs 标准 2000 → 差异率 440% > 5%
EQ "计划员审阅 403（须主管）" "$(BIZ -X POST -H "$(A $TPLAN)" $B/api/mrp/cost/$MO1/review)" "403"
CONTAINS "未审阅阻断" "$(MSG -X POST -H "$(A $TPLAN)" $B/api/mrp/mos/$MO1/close)" "未处理差异"
EQ "主管审阅 200" "$(C -H "$(A $TPLM)" -X POST $B/api/mrp/cost/$MO1/review | J "d['code']")" "200"
EQ "报告 REVIEWED" "$(SQL "SELECT REPORT_STATUS FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "REVIEWED"
EQ "审阅后关闭 200" "$(C -H "$(A $TPLAN)" -X POST $B/api/mrp/mos/$MO1/close | J "d['code']")" "200"
EQ "关单终态 CLOSED" "$(SQL "SELECT STATUS FROM erp_mrp_mo WHERE ID='$MO1'")" "CLOSED"
EQ "已审阅状态重跑后保持（防丢）" \
  "$(C -H "$(A $TCOST)" -X POST $B/api/mrp/cost/$MO1/rerun >/dev/null; \
     SQL "SELECT REPORT_STATUS FROM erp_mrp_cost_summary WHERE MO_ID='$MO1'")" "REVIEWED"

echo "== 10. 关单卡控③：未归集 → L1 阻断（引导 5.11） =="
CONTAINS "未归集阻断含 5.11 引导" "$(MSG -X POST -H "$(A $TPLAN)" $B/api/mrp/mos/$MO3/close)" "5.11"

echo "== 11. 期间已关 → DRAFT → 下期过账 =="
C -X POST -H "$(A $TADM)" "$B/api/system/params/COST_CLOSED_PERIODS?value=$PERIOD" >/dev/null
EQ "关期间下完工 200" "$(C -H "$(A $TPLAN)" -X POST -H 'Content-Type: application/json' \
  -d '{"qualifiedQty":20}' $B/api/mrp/mos/$MO4/complete | J "d['code']")" "200"
EQ "归集不回滚" "$(SQL "SELECT COUNT(*) FROM erp_mrp_cost_summary WHERE MO_ID='$MO4' AND DEL_FLAG='0'")" "1"
EQ "凭证落 DRAFT（FR-4.5-6-7）" "$(SQL "SELECT STATUS FROM erp_fin_voucher WHERE SOURCE_TYPE='MO_COMPLETE' AND SOURCE_DOC_NO='$NO4'")" "DRAFT"
EQ "DRAFT 清单可见" "$(C -H "$(A $TCOST)" $B/api/mrp/cost/vouchers/drafts | J "len([r for r in d['data'] if r['sourceDocNo']=='$NO4'])")" "1"
VID4=$(SQL "SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='MO_COMPLETE' AND SOURCE_DOC_NO='$NO4'")
EQ "财务过账 403（只读）" "$(BIZ -X POST -H "$(A $TFIN)" $B/api/mrp/cost/vouchers/$VID4/post)" "403"
EQ "成本会计下期过账 200" "$(C -H "$(A $TCOST)" -X POST $B/api/mrp/cost/vouchers/$VID4/post | J "d['code']")" "200"
EQ "过账后 POSTED" "$(SQL "SELECT STATUS FROM erp_fin_voucher WHERE ID='$VID4'")" "POSTED"
EQ "重复过账 422" "$(BIZ -X POST -H "$(A $TCOST)" $B/api/mrp/cost/vouchers/$VID4/post)" "422"
C -X POST -H "$(A $TADM)" "$B/api/system/params/COST_CLOSED_PERIODS?value=" >/dev/null
EQ "参数复位后开放路径（凭证查询 POSTED 两张）" \
  "$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='MO_COMPLETE' AND STATUS='POSTED' AND SOURCE_DOC_NO LIKE 'CSMK%'")" "2"

echo "== 12. 月度差异转销（BR-4.5-29） =="
# 本期待转销净额 = MO1 贷 1200 + MO4 贷 2000 = 3200
EQ "财务转销 403" "$(BIZ -X POST -H "$(A $TFIN)" "$B/api/mrp/cost/variance-transfer?period=$PERIOD")" "403"
TR=$(C -H "$(A $TCOST)" -X POST "$B/api/mrp/cost/variance-transfer?period=$PERIOD")
EQ "转销 200" "$(J "d['code']" <<<"$TR")" "200"
EQ "转销凭证生成" "$(J "d['data']['transferred']" <<<"$TR")" "True"
EQ "转销 POSTED" "$(SQL "SELECT STATUS FROM erp_fin_voucher WHERE SOURCE_TYPE='MO_VAR_TRANSFER' AND SOURCE_DOC_NO='$PERIOD'")" "POSTED"
NEAR "转销金额 = 差异净额 3200" "$(SQL "SELECT TOTAL_DR FROM erp_fin_voucher WHERE SOURCE_TYPE='MO_VAR_TRANSFER' AND SOURCE_DOC_NO='$PERIOD'")" "3200"
EQ "借 500191 清差异" "$(SQL "SELECT l.AMOUNT FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_TYPE='MO_VAR_TRANSFER' AND v.SOURCE_DOC_NO='$PERIOD' AND l.ACCOUNT_CODE='500191' AND l.DIRECTION='DR'")" "3200.00"
EQ "贷 6401 转销售成本" "$(SQL "SELECT l.AMOUNT FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_TYPE='MO_VAR_TRANSFER' AND v.SOURCE_DOC_NO='$PERIOD' AND l.ACCOUNT_CODE='6401' AND l.DIRECTION='CR'")" "3200.00"
EQ "同月重复转销 422" "$(BIZ -X POST -H "$(A $TCOST)" "$B/api/mrp/cost/variance-transfer?period=$PERIOD")" "422"
TRZ=$(C -H "$(A $TCOST)" -X POST "$B/api/mrp/cost/variance-transfer?period=209912")
EQ "零差异期次不生成" "$(J "d['data']['transferred']" <<<"$TRZ")" "False"

echo "== 13. 权限矩阵收口（两线） =="
EQ "质检查归集列表 403" "$(BIZ -H "$(A $TQC)" $B/api/mrp/cost/list)" "403"
EQ "质检查凭证明细 403" "$(BIZ -H "$(A $TQC)" $B/api/mrp/cost/vouchers)" "403"
EQ "质检重跑 403" "$(BIZ -X POST -H "$(A $TQC)" $B/api/mrp/cost/$MO1/rerun)" "403"
EQ "财务查凭证列表 200" "$(BIZ -H "$(A $TFIN)" $B/api/mrp/cost/vouchers)" "200"
EQ "财务转销 403" "$(BIZ -X POST -H "$(A $TFIN)" "$B/api/mrp/cost/variance-transfer?period=$PERIOD")" "403"
EQ "计划员调参数 403" "$(BIZ -X POST -H "$(A $TPLAN)" "$B/api/system/params/COST_VARIANCE_RATE?value=0.9")" "403"
EQ "admin 调参数 200" "$(C -X POST -H "$(A $TADM)" "$B/api/system/params/COST_VARIANCE_RATE?value=0.05&valueType=NUMBER" | J "d['code']")" "200"
EQ "菜单 M5-11 四叶在库" "$(SQL "SELECT COUNT(*) FROM erp_admin_menu WHERE ID LIKE 'M5-11%'")" "5"
EQ "成本会计角色绑定可见" "$(SQL "SELECT COUNT(*) FROM erp_admin_role WHERE ROLE_CODE='ROLE_COST_ACCOUNTANT'")" "1"

echo ""
echo "=================================="
echo "  PASS: $PASS   FAIL: $FAIL"
echo "=================================="
[ "$FAIL" -eq 0 ] && echo "ALL PASS" || echo "HAS FAILURES"
[ "$FAIL" -eq 0 ]
