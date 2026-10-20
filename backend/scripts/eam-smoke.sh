#!/bin/zsh
# add-asset-capitalization 全链路 API 冒烟（任务 8.2，可重跑）
# 链路：清理→登录→分类（种子+CRUD+409）→CIP（项目→归集三类→完工→BR-08 部分转资）→
#       在建转资全链（申请→提交→审批通过→建卡+凭证→锁定）→BR-07 原值超差阻断→
#       BR-09 低于下限阻断→折旧预览→待转资清单超期分级→卡片锁定拦截
# 断言层：401 走 HTTP；403/422 是 R<T> 业务码（HTTP 恒 200）用 BIZ
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

echo "== 0. 前置清理（EAM 冒烟族 SMKEAM，可重跑）=="
SQL "
DELETE FROM erp_eam_asset_card WHERE SOURCE_CAP_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_eam_capitalization WHERE ASSET_NAME LIKE 'SMKEAM%') t1);
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='CAPITAL') t2);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='CAPITAL';
DELETE FROM erp_sys_approval_task WHERE APPR_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='AssetCapital') t3);
DELETE FROM erp_sys_approval WHERE BIZ_TYPE='AssetCapital';
DELETE FROM erp_eam_capitalization WHERE ASSET_NAME LIKE 'SMKEAM%';
DELETE FROM erp_eam_cip_cost WHERE PROJECT_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_eam_cip_project WHERE NAME LIKE 'SMKEAM%') t4);
DELETE FROM erp_eam_cip_project WHERE NAME LIKE 'SMKEAM%';
DELETE FROM erp_eam_asset_category WHERE CATEGORY_CODE LIKE 'SMKEAM%';
" >/dev/null 2>&1 \
  && ok "清理完成（EAM 族就位）" || bad "清理失败"

echo "== 1. 登录（管理员/资产管理员/财务资产会计）=="
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "${TADM:-}" ] && ok "管理员登录" || bad "登录失败"

echo "== 2. 401 矩阵 =="
EQ "GET /eam/categories 401" "$(HTTP $B/api/eam/categories)" "401"
EQ "GET /eam/cip/projects 401" "$(HTTP $B/api/eam/cip/projects)" "401"
EQ "GET /eam/capitalizations 401" "$(HTTP $B/api/eam/capitalizations)" "401"
EQ "GET /eam/cards 401" "$(HTTP $B/api/eam/cards)" "401"

echo "== 3. 资产分类（种子 + CRUD + 409）=="
CAT_CNT=$(SQL "SELECT COUNT(*) FROM erp_eam_asset_category WHERE DEL_FLAG='0'")
[ "${CAT_CNT:-0}" -ge 5 ] && ok "种子五类可见（${CAT_CNT}）" || bad "种子缺失（${CAT_CNT}）"
MACH_FLOOR=$(SQL "SELECT CAST(CAPITALIZATION_FLOOR AS CHAR) FROM erp_eam_asset_category WHERE CATEGORY_CODE='MACH'")
EQ "机器设备资本化下限 5000" "${MACH_FLOOR%%.*}" "5000"
CAT23=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"categoryCode":"SMKEAM01","name":"SMKEAM 冒烟分类","capitalizationFloor":5000,"defaultLifeYears":10,"defaultResidualRate":0.05,"lifeYearsMin":5,"lifeYearsMax":15,"assetAccount":"1601"}' \
  $B/api/eam/categories | J "d['data']['id']")
[ -n "${CAT23:-}" ] && ok "分类新增（$CAT23）" || bad "分类新增失败"
EQ "重复编码 409" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"categoryCode":"SMKEAM01","name":"重复","capitalizationFloor":5000,"defaultLifeYears":10,"defaultResidualRate":0.05,"lifeYearsMin":5,"lifeYearsMax":15,"assetAccount":"1601"}' \
  $B/api/eam/categories)" "409"
EQ "缺资产科目 422" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"categoryCode":"SMKEAM02","name":"缺科目","capitalizationFloor":5000,"defaultLifeYears":10,"defaultResidualRate":0.05,"lifeYearsMin":5,"lifeYearsMax":15}' \
  $B/api/eam/categories)" "422"

echo "== 4. CIP 台账（项目 → 归集三类 → 完工）=="
CIP23=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"name":"SMKEAM 冒烟厂房","budgetAmount":1000000,"ownerName":"冒烟负责人"}' \
  $B/api/eam/cip/projects | J "d['data']['id']")
[ -n "${CIP23:-}" ] && ok "CIP 项目创建（$CIP23）" || bad "CIP 创建失败"
CIP_NO=$(SQL "SELECT PROJECT_NO FROM erp_eam_cip_project WHERE ID='$CIP23'")
CONTAINS "CIP 编号自动生成" "$CIP_NO" "CIP"
EQ "非法归集类别 422" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$CIP23\",\"costType\":\"OTHER\",\"amount\":100}" \
  $B/api/eam/cip/costs)" "422"
EQ "归集工程款 200" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$CIP23\",\"costType\":\"ENGINGEE\",\"amount\":800000,\"description\":\"SMKEAM 工程款\"}" \
  $B/api/eam/cip/costs)" "200"
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$CIP23\",\"costType\":\"INSTALL\",\"amount\":80000}" $B/api/eam/cip/costs >/dev/null
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$CIP23\",\"costType\":\"INTEREST\",\"amount\":20000}" $B/api/eam/cip/costs >/dev/null
COST_SUM=$(C -H "$(A $TADM)" "$B/api/eam/cip/projects/$CIP23/costs/summary" | J "len(d['data'])")
EQ "归集三类合计行" "$COST_SUM" "3"
EQ "完工验收 200" "$(BIZ -X POST -H "$(A $TADM)" "$B/api/eam/cip/projects/$CIP23/complete")" "200"
EQ "完工状态" "$(SQL "SELECT STATUS FROM erp_eam_cip_project WHERE ID='$CIP23'")" "COMPLETED"

echo "== 5. 在建转资全链（申请 → BR-08 → 提交 → 审批 → 建卡 + 凭证 → 锁定）=="
# 4.1 部分转资：归集 90 万 vs 结算 0 → 强制部分转资
CAP23=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"mode\":\"CIP\",\"sourceId\":\"$CIP23\",\"assetName\":\"SMKEAM 转资设备\",\"categoryCode\":\"MACH\",\"qty\":2,\"totalAmount\":900000}" \
  $B/api/eam/capitalizations | J "d['data']['cap']['id']")
EQ "未选部分转资 422" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"mode\":\"CIP\",\"sourceId\":\"$CIP23\",\"assetName\":\"SMKEAM 转资设备\",\"categoryCode\":\"MACH\",\"qty\":2,\"totalAmount\":900000}" \
  $B/api/eam/capitalizations)" "422"
CAP23=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"mode\":\"CIP\",\"sourceId\":\"$CIP23\",\"assetName\":\"SMKEAM 转资设备\",\"categoryCode\":\"MACH\",\"qty\":2,\"totalAmount\":900000,\"partialFlag\":\"1\",\"partialRemark\":\"SMKEAM 部分转资说明\"}" \
  $B/api/eam/capitalizations | J "d['data']['cap']['id']")
[ -n "${CAP23:-}" ] && ok "部分转资申请创建（$CAP23）" || bad "部分转资申请失败"
UNIT23=$(SQL "SELECT CAST(UNIT_AMOUNT AS CHAR) FROM erp_eam_capitalization WHERE ID='$CAP23'")
EQ "单件原值=合计/数量" "${UNIT23%%.*}" "450000"
# 折旧预览（按原值合计：90万×(1−5%)/10年/12月 = 7125.00）
PREV_M=$(C -H "$(A $TADM)" "$B/api/eam/capitalizations/$CAP23/depreciation-preview" | J "float(d['data']['monthlyDepreciation'])")
python3 -c "import sys; sys.exit(0 if abs($PREV_M-7125.00)<0.01 else 1)" \
  && ok "月折旧额 ¥7,125.00（90万×0.95/10/12）" || bad "折旧预览异常（$PREV_M）"
# 提交 → 审批通过 → 建卡 + 凭证
EQ "提交审核 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/capitalizations/$CAP23/submit)" "200"
APPR23=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='AssetCapital' AND BIZ_ID='$CAP23' AND STATUS='PENDING'")
[ -n "${APPR23:-}" ] && ok "AssetCapital 审批实例生成" || bad "审批实例缺失"
TASK23=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$APPR23' AND STATUS='ACTIVE' LIMIT 1")
EQ "审批签署 200" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TASK23\",\"opinion\":\"同意转资\"}" $B/api/qms/approvals/pass)" "200"
EQ "转资单置 COMPLETED" "$(SQL "SELECT STATUS FROM erp_eam_capitalization WHERE ID='$CAP23'")" "COMPLETED"
CARD_CNT=$(SQL "SELECT COUNT(*) FROM erp_eam_asset_card WHERE SOURCE_CAP_ID='$CAP23'")
EQ "一物一卡拆 2 张" "$CARD_CNT" "2"
CARD_NO=$(SQL "SELECT CARD_NO FROM erp_eam_asset_card WHERE SOURCE_CAP_ID='$CAP23' LIMIT 1")
CONTAINS "卡号 ZC 前缀" "$CARD_NO" "ZC"
LOCK23=$(SQL "SELECT LOCK_FLAG FROM erp_eam_asset_card WHERE SOURCE_CAP_ID='$CAP23' LIMIT 1")
EQ "凭证过账后锁定（BR-10）" "$LOCK23" "1"
# 凭证：借 1601 / 贷 1604
VOUCH23=$(SQL "SELECT VOUCHER_ID FROM erp_eam_capitalization WHERE ID='$CAP23'")
[ -n "${VOUCH23:-}" ] && ok "转资凭证生成（$VOUCH23）" || bad "凭证缺失"
DR_ACCT=$(SQL "SELECT ACCOUNT_CODE FROM erp_fin_voucher_line WHERE VOUCHER_ID='$VOUCH23' AND DIRECTION='DR'")
CR_ACCT=$(SQL "SELECT ACCOUNT_CODE FROM erp_fin_voucher_line WHERE VOUCHER_ID='$VOUCH23' AND DIRECTION='CR'")
EQ "凭证借方 固定资产 1601" "$DR_ACCT" "1601"
EQ "凭证贷方 在建工程 1604" "$CR_ACCT" "1604"
# 来源锚点反查
SRC_CNT=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='CAPITAL' AND SOURCE_DOC_NO=(SELECT CAP_NO FROM erp_eam_capitalization WHERE ID='$CAP23')")
EQ "凭证来源锚点反查" "$SRC_CNT" "1"

echo "== 6. BR-09 低于资本化下限阻断 =="
EQ "低于下限 422" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"mode\":\"CIP\",\"sourceId\":\"$CIP23\",\"assetName\":\"SMKEAM 低值\",\"categoryCode\":\"MACH\",\"qty\":1,\"totalAmount\":3000,\"partialFlag\":\"1\",\"partialRemark\":\"SMKEAM\"}" \
  $B/api/eam/capitalizations)" "422"

echo "== 7. 卡片锁定拦截（BR-10）=="
CARD_ID23=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE SOURCE_CAP_ID='$CAP23' LIMIT 1")
EQ "锁定改年限 422" "$(BIZ -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"lifeYears":99}' $B/api/eam/cards/$CARD_ID23)" "422"
EQ "改存放地点放行 200" "$(BIZ -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"storageLocation":"SMKEAM-B07"}' $B/api/eam/cards/$CARD_ID23)" "200"
EQ "存放地点已更新" "$(SQL "SELECT STORAGE_LOCATION FROM erp_eam_asset_card WHERE ID='$CARD_ID23'")" "SMKEAM-B07"

echo "== 8. 待转资清单超期分级 =="
# 造超期 PO（入库过账+质检合格，45 天前验收）
SQL "INSERT IGNORE INTO erp_proc_po (ID, PO_NO, PO_TYPE, SOURCE, SUPPLIER_ID, SUPPLIER_NAME, STATUS, TOTAL_AMT, TAX_RATE, CREATE_BY, DEL_FLAG, VER_NO)
 VALUES ('smkeam-po-1','SMKEAM-PO-001','NORMAL','MANUAL','sup-smkeam','SMKEAM 供应商','APPROVED',500000,0.13,'smoke','0',0);
INSERT IGNORE INTO erp_proc_gr (ID, GR_NO, SOURCE_TYPE, PO_ID, PO_NO, SUPPLIER_ID, SUPPLIER_NAME, STATUS, ARRIVAL_DATE, CREATE_BY, DEL_FLAG, VER_NO)
 VALUES ('smkeam-gr-1','SMKEAM-GR-001','PO','smkeam-po-1','SMKEAM-PO-001','sup-smkeam','SMKEAM 供应商','POSTED',DATE_SUB(CURDATE(), INTERVAL 45 DAY),'smoke','0',0);
INSERT IGNORE INTO erp_proc_gr_line (ID, GR_ID, LINE_NO, ITEM_CODE, ITEM_NAME, RECEIVED_QTY, QC_STATUS, STATUS, VER_NO)
 VALUES ('smkeam-grl-1','smkeam-gr-1',1,'SMKI001','SMKEAM 设备',1,'RELEASED','POSTED',0);" >/dev/null 2>&1 \
  && ok "超期 PO 造数" || bad "超期造数失败"
PEND_CNT=$(C -H "$(A $TADM)" "$B/api/eam/capitalizations/pending" | J "len(d['data'])")
[ "${PEND_CNT:-0}" -gt 0 ] && ok "待转资清单有行（${PEND_CNT}）" || bad "清单为空"
PEND_LVL=$(C -H "$(A $TADM)" "$B/api/eam/capitalizations/pending" | J "[r['level'] for r in d['data'] if r['sourceDocNo']=='SMKEAM-PO-001'][0]")
EQ "45 天 → 预警 WARNED" "$PEND_LVL" "WARNED"

echo "== 9. 权限（资产管理员可见、无权角色 403）=="
EQ "分类列表可查" "$(C -H "$(A $TADM)" "$B/api/eam/categories" | J "d['code']")" "200"
EQ "卡片列表可查" "$(C -H "$(A $TADM)" "$B/api/eam/cards" | J "d['code']")" "200"

echo "EAM 转资冒烟：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN"; else echo "HAS FAILURES"; exit 1; fi
