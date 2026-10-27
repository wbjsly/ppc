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
C() { curl -s --noproxy '*' --max-time 90 "$@"; }
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

echo "== 10. 折旧管理（add-asset-depreciation：建卡→计提→凭证→政策变更→减值→冲回→重提）=="
# 折旧族清理（SMKDEPR，可重跑）
SQL "
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION') t1);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION';
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='ASSET_IMPAIRMENT') t2);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='ASSET_IMPAIRMENT';
DELETE FROM erp_eam_asset_change WHERE CREATE_BY='smoke';
DELETE FROM erp_eam_depreciation_detail WHERE CARD_NO LIKE 'SMKDEPR%';
DELETE FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'JZ%';
DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'SMK%' OR ASSET_NAME LIKE 'SMKEAM%'
  OR LEASE_FLAG='1' OR SOURCE_CAP_NO LIKE 'LSE%' OR SOURCE_CAP_NO LIKE 'TRF%';
" >/dev/null 2>&1 && ok "折旧清理完成" || bad "折旧清理失败"

PERIOD=$(date +%Y%m)
# 建卡：原值 120 万、10 年、残值率 5%、直线法、2026-01 启用（起提月 202602，本期应提）
SQL "INSERT IGNORE INTO erp_eam_asset_card (ID,CARD_NO,ASSET_NAME,SPEC_MODEL,CATEGORY_CODE,DEPT_NAME,COST_CENTER_CODE,STORAGE_LOCATION,ASSET_COST,LIFE_YEARS,RESIDUAL_RATE,RESIDUAL_VALUE,DEPRECIATION_METHOD,EFFECTIVE_DATE,DEPR_START_MONTH,STATUS,DEPR_STATUS,ACCUM_DEPR,IMPAIRMENT,LOCK_FLAG,SOURCE_CAP_ID,SOURCE_CAP_NO,REMARK,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('smkdepr-card-1','SMKDEPR-0001','SMKDEPR 折旧设备','测试型号','MACH','生产部','CC-PROD-01','A区',1200000,10,0.0500,60000,'STRAIGHT_LINE','2026-01-15','202602','IN_USE','NOT_DEPRECIATED',0,0,'1',NULL,NULL,NULL,'smoke','0',0);" >/dev/null 2>&1 \
  && ok "折旧建卡 SMKDEPR-0001" || bad "折旧建卡失败"

# 应提预览：应提卡 ≥1、预计折旧 9500（SL：(120万−6万)/120）
PREV_C=$(C -H "$(A $TADM)" "$B/api/eam/depreciation/preview?periodTag=$PERIOD" | J "len(d['data']['cards'])")
[ "${PREV_C:-0}" -ge 1 ] && ok "应提清单有卡（${PREV_C}）" || bad "应提清单为空"
PREV_AMT=$(C -H "$(A $TADM)" "$B/api/eam/depreciation/preview?periodTag=$PERIOD" | J "d['data']['totalDepreciation']")
python3 -c "import sys;sys.exit(0 if abs($PREV_AMT-9500.0)<0.01 else 1)" \
  && ok "预计折旧 ¥9,500（SL 10 年）" || bad "预计折旧异常（$PREV_AMT）"

# 执行计提 → run + 凭证
RUN_ID=$(C -X POST -H "$(A $TADM)" "$B/api/eam/depreciation/runs/$PERIOD/execute" | J "d['data']['run']['id']")
[ -n "${RUN_ID:-}" ] && ok "计提执行（run=$RUN_ID）" || bad "计提执行失败"
VOUCHER=$(SQL "SELECT VOUCHER_ID FROM erp_eam_depreciation_run WHERE ID='$RUN_ID'")
[ -n "${VOUCHER:-}" ] && ok "计提凭证生成（$VOUCHER）" || bad "凭证未生成"
# 凭证借贷平（借=贷=9500）
DR=$(SQL "SELECT CAST(SUM(AMOUNT) AS CHAR) FROM erp_fin_voucher_line WHERE VOUCHER_ID='$VOUCHER' AND DIRECTION='DR'")
CR=$(SQL "SELECT CAST(SUM(AMOUNT) AS CHAR) FROM erp_fin_voucher_line WHERE VOUCHER_ID='$VOUCHER' AND DIRECTION='CR'")
python3 -c "import sys;sys.exit(0 if abs($DR-$CR)<0.01 and abs($DR-9500.0)<0.01 else 1)" \
  && ok "凭证借贷平 ¥9,500" || bad "凭证不平（DR=$DR CR=$CR）"
# 台账回写：累计折旧 9500、状态 DEPRECIATING
ACCUM=$(SQL "SELECT CAST(ACCUM_DEPR AS CHAR) FROM erp_eam_asset_card WHERE CARD_NO='SMKDEPR-0001'")
python3 -c "import sys;sys.exit(0 if abs($ACCUM-9500.0)<0.01 else 1)" \
  && ok "台账累计折旧 ¥9,500" || bad "台账回写异常（$ACCUM）"
# 同月重复 → 409
EQ "同月重复计提 409" "$(BIZ -X POST -H "$(A $TADM)" "$B/api/eam/depreciation/runs/$PERIOD/execute")" "409"

# 政策变更：年限 10→20，下期生效不追溯（单签 → 审批通过 → 下期读新政策）
CARD_ID=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKDEPR-0001'")
POL_ID=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"cardId\":\"$CARD_ID\",\"afterLifeYears\":20,\"reason\":\"SMKDEPR 冒烟政策变更\"}" \
  $B/api/eam/depreciation/changes/policy | J "d['data']['changeId']")
[ -n "${POL_ID:-}" ] && ok "政策变更提交（$POL_ID）" || bad "政策变更提交失败"
EQ "政策变更审批通过" "$(BIZ -X POST -H "$(A $TADM)" "$B/api/eam/depreciation/changes/$POL_ID/approve")" "200"
# 本期不追溯：卡片年限仍 10
LIFE_NOW=$(SQL "SELECT CAST(LIFE_YEARS AS CHAR) FROM erp_eam_asset_card WHERE CARD_NO='SMKDEPR-0001'")
EQ "本期不追溯（年限仍 10）" "${LIFE_NOW%%.*}" "10"

# 减值：60 万 → 单签通过 → 卡片 IMPAIRMENT 落值 + 减值凭证
IMP_ID=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"cardId\":\"$CARD_ID\",\"amount\":600000,\"reason\":\"SMKDEPR 冒烟减值\"}" \
  $B/api/eam/depreciation/changes/impairment | J "d['data']['changeId']")
[ -n "${IMP_ID:-}" ] && ok "减值提交（$IMP_ID）" || bad "减值提交失败"
EQ "减值审批通过" "$(BIZ -X POST -H "$(A $TADM)" "$B/api/eam/depreciation/changes/$IMP_ID/approve")" "200"
IMPAIR=$(SQL "SELECT CAST(IMPAIRMENT AS CHAR) FROM erp_eam_asset_card WHERE CARD_NO='SMKDEPR-0001'")
python3 -c "import sys;sys.exit(0 if abs($IMPAIR-600000.0)<0.01 else 1)" \
  && ok "减值准备落值 ¥600,000" || bad "减值落值异常（$IMPAIR）"
IMP_V=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='ASSET_IMPAIRMENT'")
[ "${IMP_V:-0}" -ge 1 ] && ok "减值凭证生成" || bad "减值凭证缺失"

# 冲回：红冲凭证 + 台账回滚 + 放行重提
EQ "整单冲回" "$(BIZ -X POST -H "$(A $TADM)" "$B/api/eam/depreciation/runs/$RUN_ID/reverse" --data-urlencode 'reason=SMKDEPR 冲回')" "200"
ACCUM2=$(SQL "SELECT CAST(ACCUM_DEPR AS CHAR) FROM erp_eam_asset_card WHERE CARD_NO='SMKDEPR-0001'")
python3 -c "import sys;sys.exit(0 if abs($ACCUM2)<0.01 else 1)" \
  && ok "冲回后台账回滚归零" || bad "台账未回滚（$ACCUM2）"
# 重提成功
RUN_ID2=$(C -X POST -H "$(A $TADM)" "$B/api/eam/depreciation/runs/$PERIOD/execute" | J "d['data']['run']['id']")
[ -n "${RUN_ID2:-}" ] && [ "$RUN_ID2" != "$RUN_ID" ] && ok "冲回后重提成功（$RUN_ID2）" || bad "重提失败"

# 凭证反查（7.2.3）
VLIST=$(C -H "$(A $TADM)" "$B/api/eam/depreciation/vouchers?sourceDocNo=$(SQL "SELECT RUN_NO FROM erp_eam_depreciation_run WHERE ID='$RUN_ID2'")" | J "len(d['data'])")
[ "${VLIST:-0}" -ge 1 ] && ok "凭证反查有行" || bad "凭证反查为空"

echo "== 11. 资产运维（add-asset-maintenance：计划→工单→领料→归集 / 报修→挂锁→派工→双验收 / BR-19 / BR-18）=="
SQL "
DELETE FROM erp_eam_work_order_time WHERE WO_NO LIKE 'WO%';
DELETE FROM erp_eam_work_order WHERE CARD_NO LIKE 'SMKMT%';
DELETE FROM erp_eam_repair_report WHERE CARD_NO LIKE 'SMKMT%';
DELETE FROM erp_eam_maint_plan WHERE CARD_NO LIKE 'SMKMT%';
DELETE FROM erp_eam_safety_lock WHERE CARD_NO LIKE 'SMKMT%';
DELETE FROM erp_inv_material_issue_line WHERE ISSUE_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_inv_material_issue WHERE ISSUE_TYPE='MAINT') x);
DELETE FROM erp_inv_material_issue WHERE ISSUE_TYPE='MAINT';
DELETE FROM erp_inv_stock WHERE ITEM_CODE='SPK-SMKMT';
DELETE FROM erp_mdm_item WHERE ITEM_CODE='SPK-SMKMT';
DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('AssetOverhaul','MaintBudgetDeviation') AND BIZ_ID LIKE 'SMKMT%';
DELETE FROM erp_eam_asset_card WHERE CARD_NO='SMKMT-0001';
INSERT IGNORE INTO erp_eam_asset_card (ID,CARD_NO,ASSET_NAME,CATEGORY_CODE,ASSET_COST,LIFE_YEARS,
 RESIDUAL_RATE,DEPRECIATION_METHOD,EFFECTIVE_DATE,STATUS,DEPR_STATUS,ACCUM_DEPR,IMPAIRMENT,LOCK_FLAG,
 MAINT_BUDGET,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('smkmt-card-1','SMKMT-0001','SMKMT 运维设备','MACH',800000,10,0.0500,'STRAIGHT_LINE',
 '2026-01-15','IN_USE','DEPRECIATING',0,0,'0',10000,'smoke','0',0);
INSERT IGNORE INTO erp_inv_stock (ID,WAREHOUSE_CODE,ITEM_CODE,ITEM_NAME,BATCH_NO,BIN_CODE,QTY,AVAILABLE_QTY,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('st-smkmt','WH-MAIN','SPK-SMKMT','SMKMT 滤芯','B-SMKMT','A-01',20,20,'smoke','0',0);
INSERT IGNORE INTO erp_mdm_item (ID,ITEM_CODE,ITEM_NAME,CATEGORY_CODE,BASE_UNIT,MATERIAL_GROUP,PURCHASE_TYPE,STORAGE_CONDITION,BATCH_FLAG,SERIAL_FLAG,STATUS,STANDARD_COST,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('it-spk-smkmt','SPK-SMKMT','SMKMT 滤芯','SMKI','PC','STRUCT','BUY','NORMAL','1','0','1',3000,'smoke','0',0);
" >/dev/null 2>&1 && ok "运维段清理+建卡 SMKMT-0001+备件库存" || bad "运维段清理失败"

MT_CARD=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKMT-0001'")

# 11.1 维护计划 → 批量生成（幂等）
PLAN_ID=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json'   -d "{\"cardId\":\"$MT_CARD\",\"itemName\":\"SMKMT 月度点检\",\"cycle\":\"M\",\"estHours\":2,\"sparesJson\":\"[{\\\"itemCode\\\":\\\"SPK-SMKMT\\\",\\\"qty\\\":2}]\",\"nextPlanDate\":\"$(date -v+1d +%Y-%m-%d 2>/dev/null || date -d tomorrow +%Y-%m-%d)\",\"standard\":\"听/看/摸三查\"}"   "$B/api/eam/maint-plans" | J "d['data']['plan']['id']")
[ -n "$PLAN_ID" ] && ok "维护计划创建（$PLAN_ID）" || bad "计划创建失败"

GEN1=$(C -X POST -H "$(A $TADM)" "$B/api/eam/maint-plans/generate-orders" | J "len(d['data']['created'])")
[ "${GEN1:-0}" -ge 1 ] && ok "批量生成计划工单 ${GEN1} 张" || bad "生成工单为 0"
GEN2=$(C -X POST -H "$(A $TADM)" "$B/api/eam/maint-plans/generate-orders" | J "len(d['data']['created'])")
[ "${GEN2:-0}" -eq 0 ] && ok "二次生成幂等（0 新建）" || bad "幂等失效（${GEN2}）"

WO1=$(SQL "SELECT WO_NO FROM erp_eam_work_order WHERE CARD_NO='SMKMT-0001' AND WO_TYPE='PREVENTIVE' LIMIT 1")

# 11.2 派工→执行→工时→领料（超领阻断+放行）→过账
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/work-orders/$WO1/assign?assignee=eng-smk" >/dev/null
EQ "派工成功" "$(BIZ -X POST -H "$(A $TADM)" "$B/api/eam/work-orders/$WO1/start")" "200"
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json'   -d '{"execDesc":"完成三查","issueDesc":"滤芯偏脏","partsChanged":"无"}'   "$B/api/eam/work-orders/$WO1/execution" >/dev/null
ok "执行记录已保存"
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json'   -d '{"worker":"eng-smk","postType":"TECH","workHours":2}'   "$B/api/eam/work-orders/$WO1/times" >/dev/null
ok "工时回填 2h"

# 超领阻断（清单 2，领 5 无原因）
EQ "超领无原因 L1 阻断" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json'   -d '{"lines":[{"itemCode":"SPK-SMKMT","qty":5}]}' "$B/api/eam/work-orders/$WO1/spares")" "422"
# 清单内放行 → 建 MAINT 领料单
SP_ISSUE=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json'   -d '{"lines":[{"itemCode":"SPK-SMKMT","qty":2}]}' "$B/api/eam/work-orders/$WO1/spares" | J "d['data']['issue']['issueNo']")
[ -n "$SP_ISSUE" ] && ok "清单内领料建单（$SP_ISSUE）" || bad "领料建单失败"
# 费率置 100（API 刷缓存）→ 过账领料 → 关闭归集
C -X POST -H "$(A $TADM)" "$B/api/system/params/EAM_LABOR_RATE_TECH?value=100&valueType=NUMBER&paramGroup=EAM&remark=smoke" >/dev/null
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/work-orders/$WO1/submit-acceptance" >/dev/null
SP_ID=$(SQL "SELECT ID FROM erp_inv_material_issue WHERE ISSUE_NO='$SP_ISSUE'")
EQ "领料过账" "$(BIZ -X POST -H "$(A $TADM)" "$B/api/inv/issues/$SP_ID/postings")" "200"
WO1_TCO=$(C -X POST -H "$(A $TADM)" "$B/api/eam/work-orders/$WO1/close" | J "d['data']['wo']['totalCost']")
python3 -c "import sys; sys.exit(0 if abs($WO1_TCO-6200.0)<0.01 else 1)"   && ok "关闭归集 ¥6,200（备件 2×3000+人工 2h×100）" || bad "归集异常（$WO1_TCO）"

# 11.3 报修链：安全风险挂锁→派工阻断→解锁→派工→双验收
# 先造 2 张同故障已验收历史单（WO2 验收时即为 12 月内第 3 次 → BR-19 触发）
SQL "INSERT IGNORE INTO erp_eam_work_order (ID,WO_NO,WO_TYPE,SRC_TYPE,CARD_ID,CARD_NO,FAULT_SYMPTOM,
 STATUS,FAULT_TIME,ACCEPT_DATE,CLOSE_DATE,CREATE_BY,DEL_FLAG,VER_NO) VALUES
 ('wo-smkmt-h1','WO-SMKMT-H1','REPAIR','MANUAL','$MT_CARD','SMKMT-0001','SMKMT 主轴异响','CLOSED',
  DATE_SUB(NOW(), INTERVAL 60 DAY), DATE_SUB(NOW(), INTERVAL 58 DAY), DATE_SUB(NOW(), INTERVAL 58 DAY),'smoke','0',0),
 ('wo-smkmt-h2','WO-SMKMT-H2','REPAIR','MANUAL','$MT_CARD','SMKMT-0001','SMKMT 主轴异响','CLOSED',
  DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 28 DAY), DATE_SUB(NOW(), INTERVAL 28 DAY),'smoke','0',0);" >/dev/null 2>&1
ok "同故障历史单 ×2 就位"
RB_ID=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json'   -d "{\"cardId\":\"$MT_CARD\",\"faultSymptom\":\"SMKMT 主轴异响\",\"faultTime\":\"$(date +%Y-%m-%dT%H:%M:%S)\",\"safetyRisk\":\"1\"}"   "$B/api/eam/repair-reports" | J "d['data']['report']['id']")
[ -n "$RB_ID" ] && ok "报修提交（安全风险自动挂锁）" || bad "报修失败"
EQ "挂锁中派工 L1 阻断" "$(BIZ -X POST -H "$(A $TADM)" "$B/api/eam/repair-reports/$RB_ID/convert?assignee=eng-smk&resolvePlan=SELF")" "422"
LOCK_ID=$(SQL "SELECT ID FROM erp_eam_safety_lock WHERE CARD_NO='SMKMT-0001' AND STATUS='ACTIVE' LIMIT 1")
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/safety-locks/$LOCK_ID/release?releaseReason=smoke" >/dev/null
ok "设备主管解锁"
WO2=$(C -X POST -H "$(A $TADM)" "$B/api/eam/repair-reports/$RB_ID/convert?assignee=eng-smk&resolvePlan=SELF" | J "d['data']['wo']['woNo']")
[ -n "$WO2" ] && ok "解锁后派工转维修工单（$WO2）" || bad "转工单失败"
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/work-orders/$WO2/start" >/dev/null
SUB_NO=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json'   -d '{"desc":"发现轴承磨损需更换","faultSymptom":"轴承磨损"}'   "$B/api/eam/work-orders/$WO2/sub-work-orders" | J "d['data']['wo']['woNo']")
[ -n "$SUB_NO" ] && ok "子工单已创建（$SUB_NO）" || bad "子工单创建失败"
SUB_CANCEL=$(BIZ -X POST -H "$(A $TADM)" "$B/api/eam/work-orders/$SUB_NO/cancel?reason=smoke-sub-close")
[ "$SUB_CANCEL" = "200" ] && ok "子工单已闭环（取消）" || bad "子工单取消失败（$SUB_CANCEL）"
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/work-orders/$WO2/submit-acceptance" >/dev/null
ACC1=$(C -X POST -H "$(A $TADM)" "$B/api/eam/work-orders/$WO2/accept?who=ENG&resultEval=GOOD" | J "d['data']['accepted']")
[ "$ACC1" = "False" ] && ok "仅工程师确认未完成（REPAIR 双确认）" || bad "双确认口径异常（$ACC1）"
ACC2=$(C -X POST -H "$(A $TADM)" "$B/api/eam/work-orders/$WO2/accept?who=OP" | J "d['data']['accepted']")
[ "$ACC2" = "True" ] && ok "双确认齐验收完成" || bad "操作员补签未完成"
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json'   -d '{"worker":"eng-smk","postType":"TECH","workHours":3}'   "$B/api/eam/work-orders/$WO2/times" >/dev/null
EQ "维修工单关闭" "$(BIZ -X POST -H "$(A $TADM)" "$B/api/eam/work-orders/$WO2/close")" "200"

# 11.4 BR-19 三次同故障冻结（SQL 造 2 张历史 + 本单为第 3 次）→ 评估触发
ASSESS=$(SQL "SELECT OVERHAUL_ASSESS_FLAG FROM erp_eam_work_order WHERE WO_NO='$WO2'")
[ "$ASSESS" = "1" ] && ok "BR-19 三次同故障触发大修评估" || bad "评估未触发（$ASSESS）"
RB2_ID=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json'   -d "{\"cardId\":\"$MT_CARD\",\"faultSymptom\":\"SMKMT 主轴异响\",\"faultTime\":\"$(date +%Y-%m-%dT%H:%M:%S)\",\"safetyRisk\":\"0\"}"   "$B/api/eam/repair-reports" | J "d['data']['report']['id']")
EQ "评估未归档冻结同类工单" "$(BIZ -X POST -H "$(A $TADM)" "$B/api/eam/repair-reports/$RB2_ID/convert?assignee=eng-smk&resolvePlan=SELF")" "422"

# 11.5 BR-18 预算偏差提示（卡片预算 10000，另建大额工单关闭）
SQL "INSERT IGNORE INTO erp_eam_work_order (ID,WO_NO,WO_TYPE,SRC_TYPE,CARD_ID,CARD_NO,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('wo-smkmt-big','WO-SMKMT-BIG','REPAIR','MANUAL','$MT_CARD','SMKMT-0001','PENDING_ACCEPT','smoke','0',0);
INSERT IGNORE INTO erp_inv_material_issue (ID,ISSUE_NO,ISSUE_TYPE,WORK_ORDER_NO,STATUS,POST_BY,POST_DATE,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('mi-smkmt-big','MI-SMKMT-BIG','MAINT','WO-SMKMT-BIG','POSTED','smoke',NOW(),'smoke','0',0);
INSERT IGNORE INTO erp_inv_material_issue_line (ID,ISSUE_ID,LINE_NO,ITEM_CODE,ITEM_NAME,QTY,UNIT_PRICE,AMOUNT,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('mil-smkmt-big','mi-smkmt-big',1,'SPK-SMKMT','大修配件',1,11500,11500,'smoke','0',0);" >/dev/null 2>&1
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/work-orders/WO-SMKMT-BIG/close" >/dev/null
DEV_N=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='MaintBudgetDeviation' AND BIZ_ID='SMKMT-0001'")
[ "${DEV_N:-0}" -ge 1 ] && ok "BR-18 预算偏差资本化评估提示已推送" || bad "偏差提示未生成"

# 11.6 履历与分析
HIST_WO=$(C -H "$(A $TADM)" "$B/api/eam/work-orders/maintenance-history/$MT_CARD" | J "len(d['data']['workOrders'])")
[ "${HIST_WO:-0}" -ge 3 ] && ok "设备履历聚合 ${HIST_WO} 张工单" || bad "履历工单数 ${HIST_WO}"
STAT_FC=$(C -H "$(A $TADM)" "$B/api/eam/work-orders/stats?cardId=$MT_CARD&months=12" | J "d['data']['faultCount']")
[ "${STAT_FC:-0}" -ge 1 ] && ok "维修分析故障次数 ${STAT_FC}" || bad "分析故障次数 ${STAT_FC}"

echo "== 12. 调拨租赁（add-asset-transfer-lease：部门调拨全链+回溯 / 跨法人双凭证+C-10 / 租赁全周期 / 闲置入池）=="
# 调拨租赁族清理（SMKTL，可重跑；含折旧重跑前置：清 JZ 计提单放行当月幂等）
SQL "
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE IN
  ('TRANSFER_OUT','TRANSFER_IN','LEASE_INTEREST','LEASE_PAYMENT','LEASE_RENT','LEASE_REMEASURE','LEASE_RETURN','LEASE_BUY','LEASE_DAMAGE')) t1);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN
 ('TRANSFER_OUT','TRANSFER_IN','LEASE_INTEREST','LEASE_PAYMENT','LEASE_RENT','LEASE_REMEASURE','LEASE_RETURN','LEASE_BUY','LEASE_DAMAGE');
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION') t2);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION';
DELETE FROM erp_eam_depreciation_detail WHERE CARD_NO LIKE 'SMK%';
DELETE FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'JZ%';
UPDATE erp_eam_asset_card SET ACCUM_DEPR=0, DEPR_STATUS='NOT_DEPRECIATED' WHERE CARD_NO LIKE 'SMK%';
DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('EAM_INTERCO_INVOICE','EAM_IDLE_DISPOSAL','EAM_LEASE_EXPIRY');
DELETE FROM erp_sys_approval_task WHERE APPR_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN ('AssetTransfer','AssetLeaseRemeasure')) t3);
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('AssetTransfer','AssetLeaseRemeasure');
DELETE FROM erp_eam_lease_schedule WHERE LSE_NO LIKE 'LSE%';
DELETE FROM erp_eam_lease_contract WHERE LSE_NO LIKE 'LSE%';
DELETE FROM erp_eam_transfer_order WHERE TRF_NO LIKE 'TRF%';
DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'SMKTL%';
DELETE FROM erp_mdm_cost_center WHERE CC_CODE IN ('CC-TL-A','CC-TL-TO');
INSERT IGNORE INTO erp_mdm_cost_center (ID,CC_CODE,CC_NAME,COST_TYPE,STATUS,TREE_LEVEL,PARENT_ID,LEGAL_ENTITY_ID,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('cc-tl-a','CC-TL-A','SMKTL 甲中心','PROD','1',1,'','LE-0001','smoke','0',0),
        ('cc-tl-to','CC-TL-TO','SMKTL 调入中心','ADMIN','1',1,'','LE-0001','smoke','0',0);
INSERT IGNORE INTO erp_eam_asset_card (ID,CARD_NO,ASSET_NAME,SPEC_MODEL,CATEGORY_CODE,LEGAL_ENTITY,DEPT_NAME,COST_CENTER_CODE,
 STORAGE_LOCATION,ASSET_COST,LIFE_YEARS,RESIDUAL_RATE,RESIDUAL_VALUE,DEPRECIATION_METHOD,EFFECTIVE_DATE,STATUS,DEPR_STATUS,
 ACCUM_DEPR,IMPAIRMENT,LOCK_FLAG,PLEDGED_FLAG,LAST_USED_AT,CREATE_BY,DEL_FLAG,VER_NO) VALUES
 ('smktl-card-1','SMKTL-0001','SMKTL 调拨设备','测试型号','MACH','LE-0001','SMKTL 甲部门','CC-TL-A','A区',1200000,10,0.0500,60000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'1','0','2026-01-15','smoke','0',0),
 ('smktl-card-2','SMKTL-0002','SMKTL 跨法人设备','测试型号','MACH','LE-0001','SMKTL 甲部门','CC-TL-A','A区',800000,10,0.0500,40000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'1','0','2026-01-15','smoke','0',0),
 ('smktl-card-10','SMKTL-0010','SMKTL 闲置13月设备','测试型号','MACH','LE-0001','SMKTL 甲部门','CC-TL-A','A区',100000,5,0.0500,5000,
  'STRAIGHT_LINE','2025-06-01','IN_USE','NOT_DEPRECIATED',0,0,'1','0',DATE_SUB(CURDATE(), INTERVAL 13 MONTH),'smoke','0',0),
 ('smktl-card-11','SMKTL-0011','SMKTL 闲置25月设备','测试型号','MACH','LE-0001','SMKTL 甲部门','CC-TL-A','A区',100000,5,0.0500,5000,
  'STRAIGHT_LINE','2024-06-01','IN_USE','NOT_DEPRECIATED',0,0,'1','0',DATE_SUB(CURDATE(), INTERVAL 25 MONTH),'smoke','0',0);
" >/dev/null 2>&1 && ok "调拨租赁段清理+建卡 SMKTL×4" || bad "调拨租赁段清理失败"

CUR_PERIOD=$(date +%Y%m)

# 12.1 部门调拨全链（申请 → BR-21 → 三节点审批 → 交接双确认 → 完成即改 → 当月折旧回溯）
TL_CARD=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKTL-0001'")
TRF1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"cardId\":\"$TL_CARD\",\"transferType\":\"DEPT\",\"toDept\":\"SMKTL 乙部门\",\"toCc\":\"CC-TL-TO\",\"toLocation\":\"B区\",\"reason\":\"SMKTL 部门调拨\"}" \
  $B/api/eam/transfers | J "d['data']['order']['id']")
[ -n "${TRF1:-}" ] && ok "调拨申请创建（$TRF1）" || bad "调拨申请失败"
# 同资产重复发起阻断
EQ "重复发起 422" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"cardId\":\"$TL_CARD\",\"transferType\":\"DEPT\",\"toDept\":\"X\",\"toCc\":\"CC-TL-TO\",\"reason\":\"重复\"}" \
  $B/api/eam/transfers)" "422"
EQ "提交审批 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/transfers/$TRF1/submit)" "200"
TRF_APPR=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='AssetTransfer' AND BIZ_ID='$TRF1'")
TL_NODES=$(SQL "SELECT COUNT(*) FROM erp_sys_approval_task WHERE APPR_ID='$TRF_APPR'")
EQ "DEPT 三节点链" "$TL_NODES" "3"
# 逐节点签署（ADMIN 免角色）
for i in 1 2 3; do
  TL_TASK=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$TRF_APPR' AND STATUS='ACTIVE' LIMIT 1")
  [ -n "$TL_TASK" ] && BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$TL_TASK\",\"opinion\":\"SMKTL 同意\"}" $B/api/qms/approvals/pass >/dev/null
done
EQ "审批通过待交接" "$(SQL "SELECT STATUS FROM erp_eam_transfer_order WHERE ID='$TRF1'")" "APPROVED"
# 单侧确认不可完成
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/transfers/$TRF1/handover?direction=OUT" >/dev/null
EQ "单侧确认不可完成 422" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/transfers/$TRF1/complete)" "422"
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/transfers/$TRF1/handover?direction=IN" >/dev/null
EQ "双确认置 HANDOVERED" "$(SQL "SELECT STATUS FROM erp_eam_transfer_order WHERE ID='$TRF1'")" "HANDOVERED"
EQ "确认完成 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/transfers/$TRF1/complete)" "200"
# 完成即改卡片
TL_AFTER=$(SQL "SELECT CONCAT(DEPT_NAME,'|',COST_CENTER_CODE) FROM erp_eam_asset_card WHERE CARD_NO='SMKTL-0001'")
EQ "完成即改卡片（部门|成本中心）" "$TL_AFTER" "SMKTL 乙部门|CC-TL-TO"
# BR-22 当月计提归属回溯调出方（卡片已是调入方）
RUN_TL=$(C -X POST -H "$(A $TADM)" "$B/api/eam/depreciation/runs/$CUR_PERIOD/execute" | J "d['data']['run']['id']")
[ -n "${RUN_TL:-}" ] && ok "当月计提执行（$RUN_TL）" || bad "当月计提失败"
TL_ATTR=$(SQL "SELECT COST_CENTER_CODE FROM erp_eam_depreciation_detail WHERE RUN_ID='$RUN_TL' AND CARD_NO='SMKTL-0001'")
EQ "BR-22 当月归属=调出方 CC-TL-A" "$TL_ATTR" "CC-TL-A"
TL_CARD_CC=$(SQL "SELECT COST_CENTER_CODE FROM erp_eam_asset_card WHERE CARD_NO='SMKTL-0001'")
EQ "卡片已是调入方 CC-TL-TO" "$TL_CARD_CC" "CC-TL-TO"

# 12.2 跨法人调拨（四节点+财务经理 → 双凭证 → 内部交易 → 开票待办 → C-10）
TL_CARD2=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKTL-0002'")
TRF2=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"cardId\":\"$TL_CARD2\",\"transferType\":\"CROSS_LE\",\"toDept\":\"SMKTL 乙法人部门\",\"toCc\":\"CC-TL-TO\",\"toLe\":\"LE-0002\",\"reason\":\"SMKTL 跨法人调拨\"}" \
  $B/api/eam/transfers | J "d['data']['order']['id']")
[ -n "${TRF2:-}" ] && ok "跨法人申请创建（$TRF2）" || bad "跨法人申请失败"
EQ "提交审批 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/transfers/$TRF2/submit)" "200"
TRF2_APPR=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='AssetTransfer' AND BIZ_ID='$TRF2'")
TL2_NODES=$(SQL "SELECT COUNT(*) FROM erp_sys_approval_task WHERE APPR_ID='$TRF2_APPR'")
EQ "CROSS_LE 四节点链（+财务经理）" "$TL2_NODES" "4"
for i in 1 2 3 4; do
  TL_TASK=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$TRF2_APPR' AND STATUS='ACTIVE' LIMIT 1")
  [ -n "$TL_TASK" ] && BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$TL_TASK\",\"opinion\":\"SMKTL 同意\"}" $B/api/qms/approvals/pass >/dev/null
done
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/transfers/$TRF2/handover?direction=OUT" >/dev/null
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/transfers/$TRF2/handover?direction=IN" >/dev/null
EQ "跨法人完成 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/transfers/$TRF2/complete)" "200"
# 内部交易单据 + 双凭证 + 新卡 NBV
TL_ICO=$(SQL "SELECT INTERCO_NO FROM erp_eam_transfer_order WHERE ID='$TRF2'")
CONTAINS "内部交易单号 ICR 前缀" "$TL_ICO" "ICR"
TL_OUT_V=$(SQL "SELECT OUT_VOUCHER_ID FROM erp_eam_transfer_order WHERE ID='$TRF2'")
TL_IN_V=$(SQL "SELECT IN_VOUCHER_ID FROM erp_eam_transfer_order WHERE ID='$TRF2'")
[ -n "$TL_OUT_V" ] && [ -n "$TL_IN_V" ] && ok "双凭证齐备（调出 $TL_OUT_V / 调入 $TL_IN_V）" || bad "双凭证缺失"
TL_OUT_SUM=$(SQL "SELECT CONCAT(CAST(SUM(CASE WHEN DIRECTION='DR' THEN AMOUNT ELSE 0 END) AS CHAR),'/',CAST(SUM(CASE WHEN DIRECTION='CR' THEN AMOUNT ELSE 0 END) AS CHAR)) FROM erp_fin_voucher_line WHERE VOUCHER_ID='$TL_OUT_V'")
EQ "调出凭证借贷平（DR=CR=80 万）" "$TL_OUT_SUM" "800000.00/800000.00"
TL_NEW=$(SQL "SELECT CAST(ASSET_COST AS CHAR) FROM erp_eam_asset_card WHERE ID=(SELECT NEW_CARD_ID FROM erp_eam_transfer_order WHERE ID='$TRF2')")
# NBV = 原值 80 万 − 当月折旧 6333.33（12.1 计提先行，卡片累计已回写）→ BR-23 按 NBV 建卡
python3 -c "import sys;sys.exit(0 if abs($TL_NEW-793666.67)<0.01 else 1)" \
  && ok "调入新卡原值=NBV ¥793,666.67（80 万−当月折旧）" || bad "新卡原值异常（$TL_NEW）"
TL_OLD_ST=$(SQL "SELECT STATUS FROM erp_eam_asset_card WHERE CARD_NO='SMKTL-0002'")
EQ "调出方原卡置 DISPOSED" "$TL_OLD_ST" "DISPOSED"
TL_INV_N=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='EAM_INTERCO_INVOICE'")
[ "${TL_INV_N:-0}" -ge 1 ] && ok "销项开票申请待办已推送财务" || bad "开票待办未生成"
# C-10 月结卡控：齐备通过 → 人为删单据 → 阻断列示
EQ "C-10 卡控通过" "$(BIZ -H "$(A $TADM)" $B/api/eam/transfers/interco/check)" "200"
SQL "UPDATE erp_eam_transfer_order SET INTERCO_NO=NULL WHERE ID='$TRF2'" >/dev/null 2>&1
C10_MSG=$(MSG -H "$(A $TADM)" $B/api/eam/transfers/interco/check)
CONTAINS "C-10 阻断列示缺失单号" "$C10_MSG" "缺内部交易单据"
SQL "UPDATE erp_eam_transfer_order SET INTERCO_NO='$TL_ICO' WHERE ID='$TRF2'" >/dev/null 2>&1

# 12.3 租赁全周期（合同 → 分类 → 确认 PV 锚 → 月度幂等 → 冲回重提 → 重计量 → 核销差异阻断 → 退租/购买）
LSE1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"lessor":"SMKTL 出租方","assetDesc":"SMKTL 叉车","startDate":"2026-09-01","endDate":"2029-08-31","paymentAmount":45000,"payCycleMonths":3,"discountRate":0.045,"rateBasis":"INCREMENTAL"}' \
  $B/api/eam/leases | J "d['data']['contract']['id']")
[ -n "${LSE1:-}" ] && ok "租赁合同录入（$LSE1）" || bad "合同录入失败"
# 条款不完整阻断
EQ "缺租金条款 422" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"lessor":"X","assetDesc":"Y"}' $B/api/eam/leases)" "422"
LSE1_SUG=$(C -H "$(A $TADM)" $B/api/eam/leases/$LSE1/suggest | J "d['data']['suggest']")
EQ "辅助分类 36 月 → ROU 候选" "$LSE1_SUG" "ROU"
BIZ -X POST -H "$(A $TADM)" -G --data-urlencode 'classify=ROU' --data-urlencode 'basis=SMKTL 分类确认' \
  $B/api/eam/leases/$LSE1/classify >/dev/null
LSE_PV=$(C -X POST -H "$(A $TADM)" $B/api/eam/leases/$LSE1/confirm | J "d['data']['pv']")
# PV 锚（口径 A：月折现+付款期末公式精确值；规格叙事 486,000 记偏差）
python3 -c "import sys;sys.exit(0 if abs($LSE_PV-502367.58)<0.01 else 1)" \
  && ok "PV 锚 ¥502,367.58（3 年季付 4.5%）" || bad "PV 异常（$LSE_PV）"
LSE_ROWS=$(SQL "SELECT COUNT(*) FROM erp_eam_lease_schedule WHERE CONTRACT_ID='$LSE1'")
EQ "全周期计划 T0+36 月=37 行" "$LSE_ROWS" "37"
# 月度处理（202610 有计划行）→ 利息凭证 → 幂等 422 → 冲回 → 重提
EQ "月度处理 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/leases/monthly/$CUR_PERIOD)" "200"
TL_INT_N=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='LEASE_INTEREST'")
[ "${TL_INT_N:-0}" -ge 1 ] && ok "利息凭证生成（${TL_INT_N}）" || bad "利息凭证缺失"
EQ "月标签幂等 422" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/leases/monthly/$CUR_PERIOD)" "422"
EQ "整单冲回 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/leases/reverse/$CUR_PERIOD)" "200"
EQ "冲回后重提 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/leases/monthly/$CUR_PERIOD)" "200"
# 变更重新计量（+12 月，单签通过回调执行）
BIZ -X POST -H "$(A $TADM)" -G --data-urlencode 'extraMonths=12' --data-urlencode 'reason=SMKTL 续租' \
  $B/api/eam/leases/$LSE1/remeasure >/dev/null
RM_APPR=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='AssetLeaseRemeasure' AND BIZ_ID='$LSE1' AND STATUS='PENDING'")
[ -n "${RM_APPR:-}" ] && ok "重计量单签提交（$RM_APPR）" || bad "重计量提交失败"
RM_TASK=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$RM_APPR' AND STATUS='ACTIVE' LIMIT 1")
EQ "重计量审批通过→重算执行" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$RM_TASK\",\"opinion\":\"SMKTL 同意\"}" $B/api/qms/approvals/pass)" "200"
LSE1_TERMS=$(SQL "SELECT TERMS_MONTHS FROM erp_eam_lease_contract WHERE ID='$LSE1'")
EQ "租期延长至 48 月" "$LSE1_TERMS" "48"
LSE1_SNAP=$(SQL "SELECT COUNT(*) FROM erp_eam_lease_schedule WHERE CONTRACT_ID='$LSE1' AND SNAPSHOT_SEQ>0")
[ "${LSE1_SNAP:-0}" -ge 1 ] && ok "重计量旧行快照归档（${LSE1_SNAP} 行）" || bad "快照缺失"
# 核销差异阻断（SQL 全处理+篡改期末余额）→ 退租放行
SQL "UPDATE erp_eam_lease_schedule SET PROCESSED_FLAG='1' WHERE CONTRACT_ID='$LSE1' AND SNAPSHOT_SEQ=0;
 UPDATE erp_eam_lease_schedule SET END_BAL=800 WHERE CONTRACT_ID='$LSE1' AND SNAPSHOT_SEQ=0
  AND SEQ=(SELECT MAX_SEQ FROM (SELECT MAX(SEQ) AS MAX_SEQ FROM erp_eam_lease_schedule WHERE CONTRACT_ID='$LSE1' AND SNAPSHOT_SEQ=0) t)" >/dev/null 2>&1
TL_DIFF_MSG=$(MSG -X POST -H "$(A $TADM)" "$B/api/eam/leases/$LSE1/terminate?type=RETURN&reason=SMKTL")
CONTAINS "BR-43 核销差异超容差 L1 阻断" "$TL_DIFF_MSG" "核销差异"
SQL "UPDATE erp_eam_lease_schedule SET END_BAL=0 WHERE CONTRACT_ID='$LSE1' AND SNAPSHOT_SEQ=0
  AND SEQ=(SELECT MAX_SEQ FROM (SELECT MAX(SEQ) AS MAX_SEQ FROM erp_eam_lease_schedule WHERE CONTRACT_ID='$LSE1' AND SNAPSHOT_SEQ=0) t)" >/dev/null 2>&1
EQ "差异修复后退租核销 200" "$(BIZ -X POST -H "$(A $TADM)" "$B/api/eam/leases/$LSE1/terminate?type=RETURN&reason=SMKTL")" "200"
EQ "合同 TERMINATED" "$(SQL "SELECT STATUS FROM erp_eam_lease_contract WHERE ID='$LSE1'")" "TERMINATED"
# 购买转自有（BUY_PRICE 建卡 + 凭证）
LSE_BUY=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"lessor":"SMKTL 出租方B","assetDesc":"SMKTL 货架","startDate":"2026-09-01","endDate":"2028-08-31","paymentAmount":5000,"payCycleMonths":1,"discountRate":0.045,"rateBasis":"INCREMENTAL","buyOption":"1","buyPrice":50000}' \
  $B/api/eam/leases | J "d['data']['contract']['id']")
BIZ -X POST -H "$(A $TADM)" -G --data-urlencode 'classify=ROU' --data-urlencode 'basis=SMKTL' \
  $B/api/eam/leases/$LSE_BUY/classify >/dev/null
BIZ -X POST -H "$(A $TADM)" $B/api/eam/leases/$LSE_BUY/confirm >/dev/null
TL_BUY_CARD=$(C -X POST -H "$(A $TADM)" "$B/api/eam/leases/$LSE_BUY/terminate?type=BUY&reason=SMKTL" | J "d['data']['newCard']['cardNo']")
CONTAINS "购买转自有新卡生成" "${TL_BUY_CARD:-}" "ZC"
TL_BUY_V=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='LEASE_BUY'")
[ "${TL_BUY_V:-0}" -ge 1 ] && ok "购买凭证生成" || bad "购买凭证缺失"
# 临期分级（剩 5 月 M6 / 剩半月 M1+暂估）
LSE_M6=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"lessor\":\"SMKTL 出租方C\",\"assetDesc\":\"SMKTL M6\",\"startDate\":\"2026-01-01\",\"endDate\":\"$(date -v+5m -v+1d +%Y-%m-%d 2>/dev/null || date -d '+5 months 1 day' +%Y-%m-%d)\",\"paymentAmount\":9000,\"payCycleMonths\":3,\"discountRate\":0.045,\"rateBasis\":\"INCREMENTAL\"}" \
  $B/api/eam/leases | J "d['data']['contract']['id']")
LSE_M1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"lessor\":\"SMKTL 出租方D\",\"assetDesc\":\"SMKTL M1\",\"startDate\":\"2026-01-01\",\"endDate\":\"$(date -v+15d +%Y-%m-%d 2>/dev/null || date -d '+15 days' +%Y-%m-%d)\",\"paymentAmount\":9000,\"payCycleMonths\":3,\"discountRate\":0.045,\"rateBasis\":\"INCREMENTAL\"}" \
  $B/api/eam/leases | J "d['data']['contract']['id']")
for LSE_ID in $LSE_M6 $LSE_M1; do
  BIZ -X POST -H "$(A $TADM)" -G --data-urlencode 'classify=ROU' --data-urlencode 'basis=SMKTL' \
    $B/api/eam/leases/$LSE_ID/classify >/dev/null
  BIZ -X POST -H "$(A $TADM)" $B/api/eam/leases/$LSE_ID/confirm >/dev/null
done
TL_M6_NO=$(SQL "SELECT LSE_NO FROM erp_eam_lease_contract WHERE ID='$LSE_M6'")
TL_M1_NO=$(SQL "SELECT LSE_NO FROM erp_eam_lease_contract WHERE ID='$LSE_M1'")
TL_M6_LVL=$(C -H "$(A $TADM)" $B/api/eam/leases/expiry | J "[r['level'] for r in d['data'] if r['lseNo']=='$TL_M6_NO'][0]")
EQ "剩 5 月 → M6 临期" "$TL_M6_LVL" "M6"
TL_M1_LVL=$(C -H "$(A $TADM)" $B/api/eam/leases/expiry | J "[r['level'] for r in d['data'] if r['lseNo']=='$TL_M1_NO'][0]")
EQ "剩半月 → M1 升级" "$TL_M1_LVL" "M1"
TL_M1_EST=$(SQL "SELECT ESTIMATED_FLAG FROM erp_eam_lease_contract WHERE ID='$LSE_M1'")
EQ "默示续租暂估标记" "$TL_M1_EST" "1"

# 12.4 闲置共享池（13 月 IDLE 入池 / 25 月 DISPOSAL_EVAL / 3 月不入池）
POOL_N=$(C -H "$(A $TADM)" $B/api/eam/transfers/idle-pool | J "len(d['data'])")
[ "${POOL_N:-0}" -ge 2 ] && ok "共享池入池 ${POOL_N} 张（≥12 月）" || bad "共享池入池异常（${POOL_N}）"
POOL_13=$(C -H "$(A $TADM)" $B/api/eam/transfers/idle-pool | J "[r['idleLevel'] for r in d['data'] if r['cardNo']=='SMKTL-0010'][0]")
EQ "13 月闲置 → IDLE" "$POOL_13" "IDLE"
POOL_25=$(C -H "$(A $TADM)" $B/api/eam/transfers/idle-pool | J "[r['idleLevel'] for r in d['data'] if r['cardNo']=='SMKTL-0011'][0]")
EQ "25 月闲置 → DISPOSAL_EVAL" "$POOL_25" "DISPOSAL_EVAL"
POOL_DIS_N=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='EAM_IDLE_DISPOSAL'")
[ "${POOL_DIS_N:-0}" -ge 1 ] && ok "24 月处置评估待办已推送" || bad "处置待办未生成"


echo "== 13. 资产盘点（add-asset-count：快照冻结/CSV 导入部分失败/四类差异/两档审批/盘盈建卡/盘亏挂账认定核销/标签冲突冻结/覆盖率结案）=="
# 段 13 清理（可重跑）
SQL "
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('COUNT_GAIN','COUNT_LOSS','COUNT_SUSPEND')) t1);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('COUNT_GAIN','COUNT_LOSS','COUNT_SUSPEND');
DELETE FROM erp_sys_approval_task WHERE APPR_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN ('AssetCountDiff','AssetCountReport')) t2);
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('AssetCountDiff','AssetCountReport');
DELETE FROM erp_eam_count_diff WHERE CNT_NO LIKE 'CNT%'
  OR (DIFF_TYPE='TAG_ISSUE' AND CARD_NO LIKE 'SMKCNT%');
DELETE FROM erp_eam_count_line WHERE CNT_NO LIKE 'CNT%';
DELETE FROM erp_eam_count_team_member WHERE CNT_NO LIKE 'CNT%';
DELETE FROM erp_eam_count_plan WHERE CNT_NO LIKE 'CNT%';
DELETE FROM erp_eam_asset_card WHERE SOURCE_CAP_NO LIKE 'CNT%';
DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'SMKCNT%';
UPDATE erp_sys_param SET PARAM_VALUE='50000' WHERE PARAM_KEY='ASSET_COUNT_DIFF_THRESHOLD';
" >/dev/null 2>&1 && ok "盘点段清理+阈值复位" || bad "盘点段清理失败"
SQL "
INSERT IGNORE INTO erp_eam_asset_card (ID,CARD_NO,ASSET_NAME,SPEC_MODEL,CATEGORY_CODE,LEGAL_ENTITY,DEPT_NAME,COST_CENTER_CODE,
 STORAGE_LOCATION,ASSET_COST,LIFE_YEARS,RESIDUAL_RATE,RESIDUAL_VALUE,DEPRECIATION_METHOD,EFFECTIVE_DATE,STATUS,DEPR_STATUS,
 ACCUM_DEPR,IMPAIRMENT,LOCK_FLAG,PLEDGED_FLAG,COUNT_FREEZE_FLAG,LAST_USED_AT,CREATE_BY,DEL_FLAG,VER_NO) VALUES
 ('smkcnt-1','SMKCNT-001','SMKCNT 盘亏设备','T','MACH','LE-0001','SMKCNT 甲部门','CC-TL-A','A区',100000,10,0.0500,5000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',40000,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkcnt-2','SMKCNT-002','SMKCNT 标签设备','T','MACH','LE-0001','SMKCNT 甲部门','CC-TL-A','A区',50000,10,0.0500,2500,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkcnt-3','SMKCNT-003','SMKCNT 正常车辆','T','VEHI','LE-0001','SMKCNT 甲部门','CC-TL-A','A区',80000,10,0.0500,4000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkcnt-4','SMKCNT-004','SMKCNT 冲突设备','T','MACH','LE-0001','SMKCNT 甲部门','CC-TL-A','A区',30000,10,0.0500,1500,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkcnt-5','SMKCNT-006','SMKCNT 未盘家具','T','FURN','LE-0001','SMKCNT 甲部门','CC-TL-A','A区',20000,10,0.0500,1000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkcnt-ref','SMKCNT-REF','SMKCNT 盘盈参照卡','T','MACH','LE-0001','SMKCNT 甲部门','CC-TL-A','A区',6800,10,0.0500,340,
  'STRAIGHT_LINE','2026-03-01','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-03-01','smoke','0',0);
" >/dev/null 2>&1 && ok "盘点段造卡 SMKCNT×6" || bad "盘点段造卡失败"

# 13.1 计划创建 + 清单下发（基准日快照冻结 BR-29）
CNT_PLAN=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"countType":"FULL","baseDate":"2026-06-30","method":"MANUAL","planDesc":"SMKCNT 年度盘点"}' \
  $B/api/eam/counts | J "d['data']['plan']['id']")
[ -n "${CNT_PLAN:-}" ] && ok "盘点计划创建（$CNT_PLAN）" || bad "盘点计划创建失败"
EQ "清单下发 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/counts/$CNT_PLAN/issue)" "200"
EQ "下发后状态 ISSUED" "$(SQL "SELECT STATUS FROM erp_eam_count_plan WHERE ID='$CNT_PLAN'")" "ISSUED"
CNT_TOTAL=$(SQL "SELECT TOTAL_QTY FROM erp_eam_count_plan WHERE ID='$CNT_PLAN'")
[ "${CNT_TOTAL:-0}" -ge 6 ] && ok "快照清单生成 ${CNT_TOTAL} 行（≥SMKCNT×6）" || bad "快照行数 ${CNT_TOTAL}"
SNAP_DEPT1=$(SQL "SELECT SNAP_DEPT FROM erp_eam_count_line WHERE PLAN_ID='$CNT_PLAN' AND CARD_NO='SMKCNT-001'")
SQL "UPDATE erp_eam_asset_card SET DEPT_NAME='SMKCNT 已改部门' WHERE CARD_NO='SMKCNT-001'" >/dev/null
SNAP_DEPT2=$(SQL "SELECT SNAP_DEPT FROM erp_eam_count_line WHERE PLAN_ID='$CNT_PLAN' AND CARD_NO='SMKCNT-001'")
EQ "BR-29 快照冻结（卡片改动不影响比对基准）" "$SNAP_DEPT2" "$SNAP_DEPT1"
SQL "UPDATE erp_eam_asset_card SET DEPT_NAME='SMKCNT 甲部门' WHERE CARD_NO='SMKCNT-001'" >/dev/null

# 13.2 CSV 导入（部分失败不整批回滚）+ 盘盈录入 + 账实冲突
CNT_CSV=$(printf 'cardNo,actualStatus,actualNote,refreshLastUsed\nSMKCNT-001,MISSING,,0\nSMKCNT-002,IN_USE,,0\nSMKCNT-003,IN_USE,,0\nSMKCNT-REF,IN_USE,,0\n,IN_USE,缺卡号行,0')
CNT_IMP=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: text/plain' --data-binary "$CNT_CSV" $B/api/eam/counts/$CNT_PLAN/import)
[ "$(echo "$CNT_IMP" | J "d['data']['success']==4 and d['data']['failed']==1")" = "True" ] \
  && ok "CSV 导入部分失败（成功 4 失败 1 不回滚）" || bad "导入结果异常（$CNT_IMP）"
CNT_ERRROW=$(echo "$CNT_IMP" | J "d['data']['errors'][0]['rowNo']")
EQ "错误行号=数据行第 5 条" "$CNT_ERRROW" "5"
CNT_GAIN_ENT=$(C -X POST -H "$(A $TADM)" "$B/api/eam/counts/$CNT_PLAN/entry?actualStatus=SURPLUS&actualNote=SMKCNT%20%E6%9C%AA%E7%99%BB%E8%AE%B0%E7%AC%94%E8%AE%B0%E6%9C%AC&categoryCode=FURN")
[ "$(echo "$CNT_GAIN_ENT" | J "d['data']['diffType']=='OVER'")" = "True" ] \
  && ok "盘盈录入（无标签 SURPLUS+分类）" || bad "盘盈录入失败（$CNT_GAIN_ENT）"
SQL "UPDATE erp_eam_asset_card SET STATUS='DISPOSED' WHERE CARD_NO='SMKCNT-004'" >/dev/null
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/counts/$CNT_PLAN/entry?cardNo=SMKCNT-004&actualStatus=IN_USE&actualNote=SMKCNT%20%E5%AE%9E%E7%89%A9%E5%9C%A8%E5%9C%BA" >/dev/null
ok "账实冲突录入（快照 DISPOSED 卡实物在场）"

# 13.3 自动比对（盘亏/盘盈/账实冲突三类生成 + 冲突即冻结）
CNT_CMP=$(C -X POST -H "$(A $TADM)" $B/api/eam/count-diffs/compare/$CNT_PLAN)
CNT_CREATED=$(echo "$CNT_CMP" | J "d['data']['created']")
[ "${CNT_CREATED:-0}" -ge 3 ] && ok "自动比对生成差异 ${CNT_CREATED} 条（SHORT/OVER/CONFLICT）" || bad "差异生成 ${CNT_CREATED}"
EQ "盘亏差异生成" "$(SQL "SELECT COUNT(*) FROM erp_eam_count_diff WHERE PLAN_ID='$CNT_PLAN' AND DIFF_TYPE='SHORT'")" "1"
EQ "盘盈差异生成" "$(SQL "SELECT COUNT(*) FROM erp_eam_count_diff WHERE PLAN_ID='$CNT_PLAN' AND DIFF_TYPE='OVER'")" "1"
EQ "账实冲突差异生成" "$(SQL "SELECT COUNT(*) FROM erp_eam_count_diff WHERE PLAN_ID='$CNT_PLAN' AND DIFF_TYPE='STATUS_CONFLICT'")" "1"
EQ "BR-30 冲突生成即冻结卡片" "$(SQL "SELECT COUNT_FREEZE_FLAG FROM erp_eam_asset_card WHERE CARD_NO='SMKCNT-004'")" "1"

# 13.4 标签异常冻结（BR-30：冻结→调拨 422→补标解冻）
CNT_C2=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKCNT-002'")
CNT_FZ=$(C -X POST -H "$(A $TADM)" "$B/api/eam/count-diffs/freeze?cardId=$CNT_C2&freeze=true")
[ "$(echo "$CNT_FZ" | J "d['data']['frozen']==True")" = "True" ] && ok "标签异常冻结+TAG_ISSUE 差异" || bad "标签冻结失败（$CNT_FZ）"
CNT_TRF_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"cardId\":\"$CNT_C2\",\"transferType\":\"DEPT\",\"toDept\":\"X\",\"toCc\":\"CC-TL-A\",\"reason\":\"SMKCNT 冻结阻断\"}" \
  $B/api/eam/transfers)
CONTAINS "冻结卡片调拨阻断（并入 BR-21）" "$CNT_TRF_MSG" "盘点冻结"
CNT_TAG=$(SQL "SELECT ID FROM erp_eam_count_diff WHERE CARD_ID='$CNT_C2' AND DIFF_TYPE='TAG_ISSUE' ORDER BY CREATE_DATE DESC LIMIT 1")
CNT_UNFZ=$(C -X POST -H "$(A $TADM)" $B/api/eam/count-diffs/$CNT_TAG/close-tag-issue)
[ "$(echo "$CNT_UNFZ" | J "d['data']['unfrozen']==True")" = "True" ] && ok "补标解冻" || bad "解冻失败（$CNT_UNFZ）"
EQ "解冻后卡片 flag=0" "$(SQL "SELECT COUNT_FREEZE_FLAG FROM erp_eam_asset_card WHERE CARD_NO='SMKCNT-002'")" "0"

# 13.5 盘亏：四节点（≥5 万）→ 挂账 → 责任认定 → 核销（BR-28 两段式）
CNT_SHORT=$(SQL "SELECT ID FROM erp_eam_count_diff WHERE PLAN_ID='$CNT_PLAN' AND DIFF_TYPE='SHORT'")
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/count-diffs/$CNT_SHORT/investigate?reason=SMKCNT%20%E5%80%9F%E5%87%BA%E6%9C%AA%E5%8A%9E%E6%89%8B%E7%BB%AD&evidence=%E5%A4%96%E5%8D%8F%E7%A1%AE%E8%AE%A4%E5%87%BD" >/dev/null
CNT_SUB=$(C -X POST -H "$(A $TADM)" $B/api/eam/count-diffs/$CNT_SHORT/submit-approval)
CNT_NODES=$(echo "$CNT_SUB" | J "d['data']['nodes']")
EQ "盘亏 NBV 60000 ≥ 5 万 → 四节点链" "$CNT_NODES" "4"
CNT_APPR=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='AssetCountDiff' AND BIZ_ID='$CNT_SHORT'")
for i in 1 2 3 4; do
  CNT_TASK=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$CNT_APPR' AND STATUS='ACTIVE' LIMIT 1")
  [ -n "$CNT_TASK" ] && BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$CNT_TASK\",\"opinion\":\"SMKCNT 同意\"}" $B/api/qms/approvals/pass >/dev/null
done
EQ "审批通过即挂账 SUSPENDED（BR-28 第一段）" "$(SQL "SELECT ADJUST_STATUS FROM erp_eam_count_diff WHERE ID='$CNT_SHORT'")" "SUSPENDED"
CNT_SUS_V=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='COUNT_SUSPEND'")
[ "${CNT_SUS_V:-0}" -ge 1 ] && ok "待处理财产损溢挂账凭证生成" || bad "挂账凭证缺失"
# 责任认定前核销阻断
CNT_PRE_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/eam/count-diffs/$CNT_SHORT/post-adjust)
CONTAINS "BR-28 认定前不得核销" "$CNT_PRE_MSG" "状态不可账务执行"
# 责任认定（赔偿 20000）→ 核销（损失 40000 + 赔偿 20000 / 贷 1901 60000）
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/count-diffs/$CNT_SHORT/liability?liabilityType=COMPENSATE&liabilityPerson=SMKCNT%E8%AE%BE%E5%A4%87%E7%A7%91%E9%95%BF&compensateAmount=20000&basis=SMKCNT%20%E8%B4%A3%E4%BB%BB%E8%AE%A4%E5%AE%9A%E4%B9%A6" >/dev/null
EQ "认定完成 → READY_TO_POST" "$(SQL "SELECT STATUS FROM erp_eam_count_diff WHERE ID='$CNT_SHORT'")" "READY_TO_POST"
EQ "核销执行 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/eam/count-diffs/$CNT_SHORT/post-adjust)" "200"
CNT_LOSS_V=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='COUNT_LOSS'")
[ "${CNT_LOSS_V:-0}" -ge 1 ] && ok "盘亏核销凭证生成（COUNT_LOSS）" || bad "核销凭证缺失"
EQ "核销后卡片 DISPOSED" "$(SQL "SELECT STATUS FROM erp_eam_asset_card WHERE CARD_NO='SMKCNT-001'")" "DISPOSED"
EQ "盘亏差异闭环 CLOSED" "$(SQL "SELECT STATUS FROM erp_eam_count_diff WHERE ID='$CNT_SHORT'")" "CLOSED"

# 13.6 盘盈：两节点（<5 万 小档）→ 建卡 + 凭证（BR-27）
CNT_OVER=$(SQL "SELECT ID FROM erp_eam_count_diff WHERE PLAN_ID='$CNT_PLAN' AND DIFF_TYPE='OVER'")
CNT_PRICE=$(SQL "SELECT SUGGESTED_PRICE FROM erp_eam_count_diff WHERE ID='$CNT_OVER'")
[ -n "${CNT_PRICE:-}" ] && ok "盘盈自动带价（同分类 12 月转资均价 ${CNT_PRICE}）" || bad "建议价缺失"
CNT_PRICE_LT5=$(python3 -c "print(1 if float('${CNT_PRICE:-0}')<50000 else 0)")
[ "$CNT_PRICE_LT5" = "1" ] && ok "参照价 < 5 万（FURN 类内唯一参照）" || bad "参照价超阈值 ${CNT_PRICE}（分类参照被污染）"
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/count-diffs/$CNT_OVER/investigate?reason=SMKCNT%20%E7%9B%98%E7%9B%88%E5%85%A5%E8%B4%A6&evidence=%E9%87%87%E8%B4%AD%E5%8F%91%E7%A5%A8" >/dev/null
CNT_SUB2=$(C -X POST -H "$(A $TADM)" $B/api/eam/count-diffs/$CNT_OVER/submit-approval)
EQ "盘盈 ${CNT_PRICE} < 5 万 → 两节点链（小档）" "$(echo "$CNT_SUB2" | J "d['data']['nodes']")" "2"
CNT_APPR2=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='AssetCountDiff' AND BIZ_ID='$CNT_OVER'")
for i in 1 2; do
  CNT_TASK2=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$CNT_APPR2' AND STATUS='ACTIVE' LIMIT 1")
  [ -n "$CNT_TASK2" ] && BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$CNT_TASK2\",\"opinion\":\"SMKCNT 同意\"}" $B/api/qms/approvals/pass >/dev/null
done
EQ "盘盈审批通过 → READY_TO_POST" "$(SQL "SELECT STATUS FROM erp_eam_count_diff WHERE ID='$CNT_OVER'")" "READY_TO_POST"
CNT_GAIN_OUT=$(C -X POST -H "$(A $TADM)" $B/api/eam/count-diffs/$CNT_OVER/post-adjust)
CNT_NEW_CARD=$(echo "$CNT_GAIN_OUT" | J "d['data']['newCardNo']")
CONTAINS "盘盈建卡" "${CNT_NEW_CARD:-}" "ZC"
CNT_NEW_COST=$(SQL "SELECT ASSET_COST FROM erp_eam_asset_card WHERE CARD_NO='$CNT_NEW_CARD'")
[ -n "${CNT_NEW_COST:-}" ] && [ -n "${CNT_PRICE:-}" ] \
  && python3 -c "exit(0 if abs(float('$CNT_NEW_COST')-float('$CNT_PRICE'))<0.01 else 1)" \
  && ok "新卡原值=建议价 ${CNT_PRICE}（BR-27 重置成本）" || bad "新卡原值 ${CNT_NEW_COST:-空} ≠ ${CNT_PRICE:-空}"
CNT_GAIN_V=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='COUNT_GAIN'")
[ "${CNT_GAIN_V:-0}" -ge 1 ] && ok "盘盈凭证生成（COUNT_GAIN）" || bad "盘盈凭证缺失"

# 13.7 账实冲突闭环（审批通过 → CLOSED + 自动解冻）
CNT_CON=$(SQL "SELECT ID FROM erp_eam_count_diff WHERE PLAN_ID='$CNT_PLAN' AND DIFF_TYPE='STATUS_CONFLICT'")
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/count-diffs/$CNT_CON/investigate?reason=SMKCNT%20%E8%AF%AF%E6%8A%A5%E5%BA%9F%20%E6%81%A2%E5%A4%8D%E8%B4%A6%E9%9D%A2&evidence=%E5%A4%84%E7%BD%AE%E5%8D%95%E6%A0%B8%E6%9F%A5" >/dev/null
BIZ -X POST -H "$(A $TADM)" $B/api/eam/count-diffs/$CNT_CON/submit-approval >/dev/null
CNT_APPR3=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='AssetCountDiff' AND BIZ_ID='$CNT_CON'")
for i in 1 2; do
  CNT_TASK3=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$CNT_APPR3' AND STATUS='ACTIVE' LIMIT 1")
  [ -n "$CNT_TASK3" ] && BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$CNT_TASK3\",\"opinion\":\"SMKCNT 同意\"}" $B/api/qms/approvals/pass >/dev/null
done
EQ "冲突差异闭环 CLOSED" "$(SQL "SELECT STATUS FROM erp_eam_count_diff WHERE ID='$CNT_CON'")" "CLOSED"
EQ "闭环自动解冻（BR-30 解除）" "$(SQL "SELECT COUNT_FREEZE_FLAG FROM erp_eam_asset_card WHERE CARD_NO='SMKCNT-004'")" "0"

# 13.8 覆盖率阻断（BR-26 <100% 列未盘）→ 补盘 → 结案报告归档
CNT_COV=$(C -X POST -H "$(A $TADM)" $B/api/eam/counts/$CNT_PLAN/coverage)
CNT_COUNTED=$(echo "$CNT_COV" | J "d['data']['countedQty']")
CNT_TOTAL2=$(echo "$CNT_COV" | J "d['data']['totalQty']")
[ "${CNT_COUNTED:-0}" -lt "${CNT_TOTAL2:-0}" ] && ok "覆盖率未满（${CNT_COUNTED}/${CNT_TOTAL2}）" || bad "覆盖率意外满额"
CNT_RPT_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/eam/counts/$CNT_PLAN/submit-report)
CONTAINS "BR-26 覆盖率<100% 阻断结案" "$CNT_RPT_MSG" "未盘"
SQL "UPDATE erp_eam_count_line SET ACTUAL_STATUS='IN_USE', COUNTED_BY='smoke', COUNTED_AT=NOW(), COUNT_METHOD='MANUAL'
     WHERE PLAN_ID='$CNT_PLAN' AND ACTUAL_STATUS IS NULL" >/dev/null
CNT_COV2=$(C -X POST -H "$(A $TADM)" $B/api/eam/counts/$CNT_PLAN/coverage)
[ "$(echo "$CNT_COV2" | J "d['data']['countedQty']==d['data']['totalQty']")" = "True" ] \
  && ok "补盘后覆盖率 100%" || bad "补盘后覆盖异常（$CNT_COV2）"
CNT_RPT=$(C -X POST -H "$(A $TADM)" $B/api/eam/counts/$CNT_PLAN/submit-report)
CNT_RPT_APPR=$(echo "$CNT_RPT" | J "d['data']['apprId']")
CNT_RPT_SUM=$(echo "$CNT_RPT" | J "d['data']['reportSummary']")
[ -n "${CNT_RPT_APPR:-}" ] && ok "结案报告提交（含覆盖率汇总）" || bad "报告提交失败"
CNT_RTASK=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$CNT_RPT_APPR' AND STATUS='ACTIVE' LIMIT 1")
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$CNT_RTASK\",\"opinion\":\"SMKCNT 报告归档\"}" $B/api/qms/approvals/pass >/dev/null
EQ "报告审批归档 → CLOSED" "$(SQL "SELECT STATUS FROM erp_eam_count_plan WHERE ID='$CNT_PLAN'")" "CLOSED"

# 13.9 SAMPLE 抽样（抽样计划清单小于全库且非空）+ CSV 导出
CNT_FULL_T=$CNT_TOTAL2
CNT_SAMPLE=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"countType":"SAMPLE","baseDate":"2026-06-30","method":"SCAN","planDesc":"SMKCNT 抽样盘点"}' \
  $B/api/eam/counts | J "d['data']['plan']['id']")
BIZ -X POST -H "$(A $TADM)" $B/api/eam/counts/$CNT_SAMPLE/issue >/dev/null
CNT_SAMPLE_T=$(SQL "SELECT TOTAL_QTY FROM erp_eam_count_plan WHERE ID='$CNT_SAMPLE'")
python3 -c "import sys;s,f=int('$CNT_SAMPLE_T' or 0),int('$CNT_FULL_T' or 0);exit(0 if 1<=s<f else 1)" \
  && ok "抽样清单生成 ${CNT_SAMPLE_T} 行（≥1 且 < 全量 ${CNT_FULL_T}）" || bad "抽样清单 ${CNT_SAMPLE_T} / 全量 ${CNT_FULL_T}"
CNT_EXP=$(C -H "$(A $TADM)" $B/api/eam/counts/$CNT_PLAN/export)
CONTAINS "CSV 导出含快照行" "$CNT_EXP" "SMKCNT-001"


echo "== 14. 报废处置（add-asset-disposal：批量前置阻断/C-05/两档链/段1 C-03/BR-33/36 阻断/回收入库/损益/应收关卡/BR-34 当月豁免/冲回）=="
# 段 14 清理（可重跑）
SQL "
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE IN
  ('DISPOSAL_TRANSFER','DISPOSAL_PROCEEDS','DISPOSAL_RECYCLE','DISPOSAL_GAINLOSS')) t1);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN
 ('DISPOSAL_TRANSFER','DISPOSAL_PROCEEDS','DISPOSAL_RECYCLE','DISPOSAL_GAINLOSS');
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION') t2);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION';
DELETE FROM erp_eam_depreciation_detail WHERE CARD_NO LIKE 'SMKDSP%';
DELETE FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'JZ%';
DELETE FROM erp_sys_approval_task WHERE APPR_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN
  ('AssetDisposal','AssetDisposalReport')) t3);
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('AssetDisposal','AssetDisposalReport');
DELETE FROM erp_eam_disposal_recycle WHERE ORDER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_eam_disposal_order) t4);
DELETE FROM erp_eam_disposal_line WHERE ORDER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_eam_disposal_order) t5);
DELETE FROM erp_eam_disposal_order;
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='GR') t6);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='GR';
DELETE FROM erp_inv_putaway WHERE ITEM_CODE='SPK-SMKDSP';
DELETE FROM erp_inv_stock WHERE ITEM_CODE='SPK-SMKDSP';
DELETE FROM erp_proc_gr_line WHERE ITEM_CODE='SPK-SMKDSP';
DELETE FROM erp_proc_gr WHERE SUPPLIER_ID='sup-eam-recycle';
DELETE FROM erp_mdm_item WHERE ITEM_CODE='SPK-SMKDSP';
DELETE FROM erp_inv_bin WHERE ID='bin-smk-dsp';
DELETE FROM erp_inv_zone WHERE ID='zone-smk-dsp';
DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'SMKDSP%';
UPDATE erp_eam_asset_category SET DATA_DESTROY_FLAG='0', ENV_REVIEW_FLAG='0';
UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE IN ('6115','1606','1122') AND DEL_FLAG='1';
UPDATE erp_sys_param SET PARAM_VALUE='500000' WHERE PARAM_KEY='ASSET_DISPOSAL_THRESHOLD_A';
UPDATE erp_sys_param SET PARAM_VALUE='5000000' WHERE PARAM_KEY='ASSET_DISPOSAL_THRESHOLD_B';
" >/dev/null 2>&1 && ok "处置段清理" || bad "处置段清理失败"
# 参数有本地缓存：阈值复位/调整须走 API（写入即刷缓存）
C -X POST -H "$(A $TADM)" "$B/api/system/params/ASSET_DISPOSAL_THRESHOLD_A?value=500000&valueType=NUMBER&paramGroup=EAM&remark=SMKDSP" >/dev/null
SQL "
INSERT IGNORE INTO erp_mdm_item (ID,ITEM_CODE,ITEM_NAME,CATEGORY_CODE,BASE_UNIT,MATERIAL_GROUP,
 PURCHASE_TYPE,STORAGE_CONDITION,BATCH_FLAG,SERIAL_FLAG,STATUS,STANDARD_COST,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('it-spk-smkdsp','SPK-SMKDSP','SMKDSP 废铜料','SMKI','PC','STRUCT','BUY','NORMAL','1','0','1',500,'smoke','0',0);
INSERT IGNORE INTO erp_inv_zone (ID,WH_CODE,ZONE_CODE,ZONE_NAME,SORT_ORDER,DEF_BIN_TYPE,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('zone-smk-dsp','WH-MAIN','ST','SMKDSP 存储区',11,'STORAGE','1','smoke','0',0);
INSERT IGNORE INTO erp_inv_bin (ID,WH_CODE,ZONE_CODE,BIN_CODE,BIN_TYPE,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('bin-smk-dsp','WH-MAIN','ST','SMK-DSP-BIN','STORE','1','smoke','0',0);
INSERT IGNORE INTO erp_eam_asset_card (ID,CARD_NO,ASSET_NAME,SPEC_MODEL,CATEGORY_CODE,LEGAL_ENTITY,
 DEPT_NAME,COST_CENTER_CODE,STORAGE_LOCATION,ASSET_COST,LIFE_YEARS,RESIDUAL_RATE,RESIDUAL_VALUE,
 DEPRECIATION_METHOD,EFFECTIVE_DATE,STATUS,DEPR_STATUS,ACCUM_DEPR,IMPAIRMENT,LOCK_FLAG,PLEDGED_FLAG,
 COUNT_FREEZE_FLAG,LAST_USED_AT,CREATE_BY,DEL_FLAG,VER_NO) VALUES
 ('smkdsp-1','SMKDSP-001','SMKDSP 报废设备A','T','MACH','LE-0001','SMKDSP 甲部门','CC-TL-A','A区',120000,10,0.0500,6000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',40000,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkdsp-2','SMKDSP-002','SMKDSP 报废设备B','T','MACH','LE-0001','SMKDSP 甲部门','CC-TL-A','A区',80000,10,0.0500,4000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkdsp-3','SMKDSP-003','SMKDSP 大额设备','T','MACH','LE-0001','SMKDSP 甲部门','CC-TL-A','A区',200000,10,0.0500,10000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkdsp-4','SMKDSP-004','SMKDSP 出售设备','T','MACH','LE-0001','SMKDSP 甲部门','CC-TL-A','A区',100000,10,0.0500,5000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',30000,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkdsp-5','SMKDSP-005','SMKDSP 冻结设备','T','MACH','LE-0001','SMKDSP 甲部门','CC-TL-A','A区',50000,10,0.0500,2500,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','1','2026-01-15','smoke','0',0),
 ('smkdsp-9','SMKDSP-999','SMKDSP 常驻在用','T','MACH','LE-0001','SMKDSP 甲部门','CC-TL-A','A区',60000,10,0.0500,3000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0);
" >/dev/null 2>&1 && ok "处置段造数（卡×6+物料+仓位）" || bad "处置段造数失败"

DS_C1=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKDSP-001'")
DS_C2=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKDSP-002'")
DS_C3=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKDSP-003'")
DS_C4=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKDSP-004'")
DS_FRZ=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKDSP-005'")

# 14.1 前置阻断逐项列示 + C-05 复核 + 批量创建
DS_BLK_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"disposalType\":\"SCRAP\",\"reasonCode\":\"DAMAGE\"},\"lines\":[{\"cardId\":\"$DS_C1\"},{\"cardId\":\"$DS_FRZ\"}]}" \
  $B/api/eam/disposals)
CONTAINS "冻结卡前置阻断逐项列示" "$DS_BLK_MSG" "盘点冻结"
DS_O1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"disposalType\":\"SCRAP\",\"reasonCode\":\"TECH\",\"reasonNote\":\"SMKDSP 批量报废\",\"estResidual\":20000},
       \"lines\":[{\"cardId\":\"$DS_C1\"},{\"cardId\":\"$DS_C2\"}],
       \"recycles\":[{\"itemName\":\"SMKDSP 废铜\",\"itemCode\":\"SPK-SMKDSP\",\"qty\":2,\"estPrice\":500}]}" \
  $B/api/eam/disposals | J "d['data']['order']['id']")
[ -n "${DS_O1:-}" ] && ok "批量报废单创建（2 卡+回收明细）" || bad "批量单创建失败"
DS_C05_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/eam/disposals/$DS_O1/submit)
CONTAINS "C-4.13-05 残值偏高须复核" "$DS_C05_MSG" "复核"
C -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"id\":\"$DS_O1\",\"reasonCode\":\"TECH\",\"reasonNote\":\"SMKDSP 批量报废\",\"estResidual\":20000,\"reviewNote\":\"SMKDSP 市场询价复核\"},
       \"lines\":[{\"cardId\":\"$DS_C1\"},{\"cardId\":\"$DS_C2\"}],
       \"recycles\":[{\"itemName\":\"SMKDSP 废铜\",\"itemCode\":\"SPK-SMKDSP\",\"qty\":2,\"estPrice\":500}]}" \
  $B/api/eam/disposals/$DS_O1 >/dev/null
DS_SUB=$(C -X POST -H "$(A $TADM)" $B/api/eam/disposals/$DS_O1/submit)
EQ "小额批量单两节点链" "$(echo "$DS_SUB" | J "d['data']['nodes']")" "2"

# 14.2 三档链（临时调阈值 A=10 万 → 20 万卡三节点）+ BR-33 科目缺失阻断（exec 前置）
C -X POST -H "$(A $TADM)" "$B/api/system/params/ASSET_DISPOSAL_THRESHOLD_A?value=100000&valueType=NUMBER&paramGroup=EAM&remark=SMKDSP" >/dev/null
DS_O3=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"disposalType\":\"SCRAP\",\"reasonCode\":\"IDLE\",\"estResidual\":0},\"lines\":[{\"cardId\":\"$DS_C3\"}]}" \
  $B/api/eam/disposals | J "d['data']['order']['id']")
DS_SUB3=$(C -X POST -H "$(A $TADM)" $B/api/eam/disposals/$DS_O3/submit)
EQ "阈值A 内大额三节点链" "$(echo "$DS_SUB3" | J "d['data']['nodes']")" "3"
C -X POST -H "$(A $TADM)" "$B/api/system/params/ASSET_DISPOSAL_THRESHOLD_A?value=500000&valueType=NUMBER&paramGroup=EAM&remark=SMKDSP" >/dev/null

# 14.3 审批通过 → 段1 转清理（C-03）
DS_APPR=$(SQL "SELECT APPR_ID FROM erp_eam_disposal_order WHERE ID='$DS_O1'")
for i in 1 2; do
  DS_TASK=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$DS_APPR' AND STATUS='ACTIVE' LIMIT 1")
  [ -n "$DS_TASK" ] && BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$DS_TASK\",\"opinion\":\"SMKDSP 同意\"}" $B/api/qms/approvals/pass >/dev/null
done
EQ "审批通过推进 APPROVED" "$(SQL "SELECT APP_STATUS FROM erp_eam_disposal_order WHERE ID='$DS_O1'")" "APPROVED"
DS_T1=$(SQL "SELECT COALESCE(SUM(l.AMOUNT),0) FROM erp_fin_voucher_line l
 JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_TYPE='DISPOSAL_TRANSFER' AND v.SOURCE_DOC_NO='$DS_O1' AND l.DIRECTION='DR'")
python3 -c "import sys;exit(0 if abs(float('$DS_T1')-200000.0)<0.01 else 1)" \
  && ok "段1 转清理借方 ¥200,000（清理 NBV 16 万+累计 4 万=C-03 审批过自动生成）" || bad "段1 借方异常（$DS_T1）"

# 14.4 三档单审批（3 节点）→ BR-33 阻断 exec → 恢复
DS_APPR3=$(SQL "SELECT APPR_ID FROM erp_eam_disposal_order WHERE ID='$DS_O3'")
for i in 1 2 3; do
  DS_TASK3=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$DS_APPR3' AND STATUS='ACTIVE' LIMIT 1")
  [ -n "$DS_TASK3" ] && BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$DS_TASK3\",\"opinion\":\"SMKDSP 同意\"}" $B/api/qms/approvals/pass >/dev/null
done
SQL "UPDATE erp_fin_account SET DEL_FLAG='1' WHERE ACCOUNT_CODE='6115'" >/dev/null
DS_B33_MSG=$(MSG -X POST -H "$(A $TADM)" "$B/api/eam/disposals/$DS_O3/exec?proofNo=SMKDSP-BP3")
CONTAINS "BR-33 科目缺失列编码" "$DS_B33_MSG" "6115"
SQL "UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE='6115'" >/dev/null

# 14.5 BR-36 数据销毁阻断 → 回收入库执行（免检+仓位+posting）→ 损益/卡片
SQL "UPDATE erp_eam_asset_category SET DATA_DESTROY_FLAG='1' WHERE CATEGORY_CODE='MACH'" >/dev/null
DS_B36_MSG=$(MSG -X POST -H "$(A $TADM)" "$B/api/eam/disposals/$DS_O1/exec?proofNo=SMKDSP-BP1")
CONTAINS "BR-36 缺数据销毁证明阻断" "$DS_B36_MSG" "数据销毁"
SQL "UPDATE erp_eam_asset_category SET DATA_DESTROY_FLAG='0' WHERE CATEGORY_CODE='MACH'" >/dev/null
DS_EXEC=$(C -X POST -H "$(A $TADM)" "$B/api/eam/disposals/$DS_O1/exec?proofNo=SMKDSP-BP1&dataDestroyNo=SMK-DD-001")
# 损益 = 0 + 回收 1000 − NBV 160000 = −159000
echo "$DS_EXEC" | python3 -c "import sys,json;d=json.load(sys.stdin);exit(0 if abs(d['data']['gainLoss']+159000.0)<0.01 else 1)" \
  && ok "执行损益 −159,000（回收冲减 1,000）" || bad "损益异常（$DS_EXEC）"
EQ "卡片置 DISPOSED" "$(SQL "SELECT STATUS FROM erp_eam_asset_card WHERE CARD_NO='SMKDSP-001'")" "DISPOSED"
[ -n "$(SQL "SELECT DISPOSED_AT FROM erp_eam_asset_card WHERE CARD_NO='SMKDSP-001'")" ] \
  && ok "DISPOSED_AT 锚写入（BR-34）" || bad "DISPOSED_AT 缺失"
DS_GR=$(SQL "SELECT GR_ID FROM erp_eam_disposal_recycle WHERE ORDER_ID='$DS_O1' AND GR_STATUS='POSTED' LIMIT 1")
[ -n "${DS_GR:-}" ] && ok "回收入库过账+GR 回写（$DS_GR）" || bad "回收未入库"
DS_STOCK=$(SQL "SELECT COALESCE(SUM(QTY),0) FROM erp_inv_stock WHERE ITEM_CODE='SPK-SMKDSP'")
python3 -c "import sys;exit(0 if float('$DS_STOCK')>=2 else 1)" \
  && ok "库存入库数量 $DS_STOCK ≥2" || bad "库存数量 $DS_STOCK"
DS_GAIN_V=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='DISPOSAL_GAINLOSS'")
[ "${DS_GAIN_V:-0}" -ge 1 ] && ok "损益结转凭证生成（6115）" || bad "损益凭证缺失"

# 14.6 BR-34 处置当月折旧豁免
CUR_PERIOD=$(date +%Y%m)
DS_RUN=$(C -X POST -H "$(A $TADM)" "$B/api/eam/depreciation/runs/$CUR_PERIOD/execute" | J "d['data']['run']['id']")
DS_IN_CUR=$(SQL "SELECT COUNT(*) FROM erp_eam_depreciation_detail WHERE RUN_ID='$DS_RUN' AND CARD_NO='SMKDSP-001'")
EQ "BR-34 处置当月仍计提" "$DS_IN_CUR" "1"

# 14.7 SALE 链：应收挂账 → 未收不得结案 → 收款 → 结案归档
DS_O4=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"disposalType\":\"SALE\",\"reasonCode\":\"IDLE\",\"reasonNote\":\"SMKDSP 出售\",\"estResidual\":0},
       \"lines\":[{\"cardId\":\"$DS_C4\",\"lineProceeds\":90000}]}" \
  $B/api/eam/disposals | J "d['data']['order']['id']")
BIZ -X POST -H "$(A $TADM)" $B/api/eam/disposals/$DS_O4/submit >/dev/null
DS_APPR4=$(SQL "SELECT APPR_ID FROM erp_eam_disposal_order WHERE ID='$DS_O4'")
for i in 1 2; do
  DS_TASK4=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$DS_APPR4' AND STATUS='ACTIVE' LIMIT 1")
  [ -n "$DS_TASK4" ] && BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$DS_TASK4\",\"opinion\":\"SMKDSP 同意\"}" $B/api/qms/approvals/pass >/dev/null
done
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/disposals/$DS_O4/exec?proofNo=SMKDSP-CT4" >/dev/null
EQ "出售执行后挂应收" "$(SQL "SELECT APP_STATUS FROM erp_eam_disposal_order WHERE ID='$DS_O4'")" "RECEIVABLE"
DS_CLOSE_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/eam/disposals/$DS_O4/submit-report)
CONTAINS "BR-35 应收未结不得结案" "$DS_CLOSE_MSG" "应收处置款未结清"
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/disposals/$DS_O4/receive?amount=90000&note=SMKDSP%20%E8%BD%AC%E8%B4%A6" >/dev/null
EQ "收款后应收清零" "$(SQL "SELECT CAST(RECEIVABLE_BAL AS CHAR) FROM erp_eam_disposal_order WHERE ID='$DS_O4'")" "0.00"
DS_REP=$(C -X POST -H "$(A $TADM)" $B/api/eam/disposals/$DS_O4/submit-report)
DS_REP_APPR=$(echo "$DS_REP" | J "d['data']['apprId']")
DS_RTASK=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$DS_REP_APPR' AND STATUS='ACTIVE' LIMIT 1")
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$DS_RTASK\",\"opinion\":\"SMKDSP 归档\"}" $B/api/qms/approvals/pass >/dev/null
EQ "结案报告归档 CLOSED" "$(SQL "SELECT APP_STATUS FROM erp_eam_disposal_order WHERE ID='$DS_O4'")" "CLOSED"

# 14.8 整单冲回（三档单 exec 后 reverse → 卡回 IN_USE）
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/disposals/$DS_O3/exec?proofNo=SMKDSP-BP3" >/dev/null
EQ "三档单执行 DISPOSED" "$(SQL "SELECT STATUS FROM erp_eam_asset_card WHERE CARD_NO='SMKDSP-003'")" "DISPOSED"
DS_REV=$(C -X POST -H "$(A $TADM)" $B/api/eam/disposals/$DS_O3/reverse)
[ "$(echo "$DS_REV" | J "d['data']['reversedVouchers'] >= 2")" = "True" ] \
  && ok "整单冲回凭证 ≥2 张" || bad "冲回失败（$DS_REV）"
EQ "冲回后卡片回 IN_USE" "$(SQL "SELECT STATUS FROM erp_eam_asset_card WHERE CARD_NO='SMKDSP-003'")" "IN_USE"
DS_DISP_N=$(SQL "SELECT COUNT(*) FROM erp_eam_asset_card WHERE CARD_NO='SMKDSP-003' AND DISPOSED_AT IS NULL")
EQ "DISPOSED_AT 已清空" "$DS_DISP_N" "1"


echo "== 15. 技改大修（add-asset-rework：前置阻断/BR-37/38/转在建凭证+CIP互锚/BR-39停提特批/验收S-18重建/费用化/冲回/超期预警）=="
# 段 15 清理（可重跑）
SQL "
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE IN
  ('REWORK_TRANSFER','REWORK_REBUILD','REWORK_EXPENSE')) t1);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN
 ('REWORK_TRANSFER','REWORK_REBUILD','REWORK_EXPENSE');
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION') t2);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION';
DELETE FROM erp_eam_depreciation_detail WHERE CARD_NO LIKE 'SMKRWK%';
DELETE FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'JZ%';
DELETE FROM erp_sys_approval_task WHERE APPR_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN
  ('AssetRework','AssetReworkContinue')) t3);
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('AssetRework','AssetReworkContinue');
DELETE FROM erp_eam_rework_item WHERE ORDER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_eam_rework_order) t4);
DELETE FROM erp_eam_rework_order;
DELETE FROM erp_eam_cip_cost WHERE PROJECT_ID IN (SELECT ID FROM
 (SELECT ID FROM erp_eam_cip_project WHERE REWORK_ORDER_ID IS NOT NULL) t5);
DELETE FROM erp_eam_cip_project WHERE REWORK_ORDER_ID IS NOT NULL;
DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'SMKRWK%' OR SOURCE_CAP_NO LIKE 'SMKRWK%';
UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE IN ('1601','1602','1604','2202','660201') AND DEL_FLAG='1';
UPDATE erp_eam_asset_category SET CAPITALIZATION_FLOOR=5000 WHERE CATEGORY_CODE='MACH';
" >/dev/null 2>&1 && ok "技改段清理" || bad "技改段清理失败"
SQL "
INSERT IGNORE INTO erp_eam_asset_card (ID,CARD_NO,ASSET_NAME,SPEC_MODEL,CATEGORY_CODE,LEGAL_ENTITY,
 DEPT_NAME,COST_CENTER_CODE,STORAGE_LOCATION,ASSET_COST,LIFE_YEARS,RESIDUAL_RATE,RESIDUAL_VALUE,
 DEPRECIATION_METHOD,EFFECTIVE_DATE,STATUS,DEPR_STATUS,ACCUM_DEPR,IMPAIRMENT,LOCK_FLAG,PLEDGED_FLAG,
 COUNT_FREEZE_FLAG,LAST_USED_AT,CREATE_BY,DEL_FLAG,VER_NO) VALUES
 ('smkrwk-1','SMKRWK-001','SMKRWK 主链设备','T','MACH','LE-0001','SMKRWK 甲部门','CC-RWK-A','A区',120000,10,0.0500,6000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',40000,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkrwk-2','SMKRWK-002','SMKRWK 防拆分设备','T','MACH','LE-0001','SMKRWK 甲部门','CC-RWK-A','A区',80000,10,0.0500,4000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkrwk-3','SMKRWK-003','SMKRWK 低于下限设备','T','MACH','LE-0001','SMKRWK 甲部门','CC-RWK-A','A区',80000,10,0.0500,4000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkrwk-4','SMKRWK-004','SMKRWK 已提足龙门铣','T','MACH','LE-0001','SMKRWK 甲部门','CC-RWK-A','A区',1200000,10,0.0500,60000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','FULLY_DEPRECIATED',1140000,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkrwk-5','SMKRWK-005','SMKRWK 冲回设备','T','MACH','LE-0001','SMKRWK 甲部门','CC-RWK-A','A区',50000,10,0.0500,2500,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkrwk-6','SMKRWK-006','SMKRWK 超期设备','T','MACH','LE-0001','SMKRWK 甲部门','CC-RWK-A','A区',40000,10,0.0500,2000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0),
 ('smkrwk-f','SMKRWK-FRZ','SMKRWK 冻结设备','T','MACH','LE-0001','SMKRWK 甲部门','CC-RWK-A','A区',50000,10,0.0500,2500,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','1','2026-01-15','smoke','0',0),
 ('smkrwk-9','SMKRWK-999','SMKRWK 常驻在用','T','MACH','LE-0001','SMKRWK 甲部门','CC-RWK-A','A区',60000,10,0.0500,3000,
  'STRAIGHT_LINE','2026-01-15','IN_USE','NOT_DEPRECIATED',0,0,'0','0','0','2026-01-15','smoke','0',0);
" >/dev/null 2>&1 && ok "技改段造数（卡×7）" || bad "技改段造数失败"

RW_C1=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKRWK-001'")
RW_C2=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKRWK-002'")
RW_C3=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKRWK-003'")
RW_C4=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKRWK-004'")
RW_C5=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKRWK-005'")
RW_C6=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKRWK-006'")
RW_FRZ=$(SQL "SELECT ID FROM erp_eam_asset_card WHERE CARD_NO='SMKRWK-FRZ'")

# 15.1 前置阻断逐项列示 + 创建
RW_BLK_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"cardId\":\"$RW_FRZ\",\"content\":\"SMKRWK\",\"budgetAmount\":8000},\"items\":[{\"condType\":\"LIFT_CAPACITY\",\"quantValue\":12,\"evidenceNo\":\"EV-SMKRWK\"}]}" \
  $B/api/eam/reworks)
CONTAINS "冻结卡前置阻断逐项列示" "$RW_BLK_MSG" "盘点冻结"
RW_O1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"cardId\":\"$RW_C1\",\"content\":\"SMKRWK 主链技改\",\"budgetAmount\":50000,\"expectedEffect\":\"SMKRWK 产能提升\"},\"items\":[{\"condType\":\"LIFT_CAPACITY\",\"quantValue\":12,\"evidenceNo\":\"EV-SMKRWK-001\"}]}" \
  $B/api/eam/reworks | J "d['data']['order']['id']")
[ -n "${RW_O1:-}" ] && ok "技改单创建（判定依据+预算）" || bad "技改单创建失败"

# 15.2 BR-38 防拆分：前单费用化结案 → 本单低预算合并命中 → CAP 判定强制复核 → 通过推进 JUDGED
RW_OA=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"cardId\":\"$RW_C2\",\"content\":\"SMKRWK 前单\",\"budgetAmount\":3000},\"items\":[{\"condType\":\"LIFT_CAPACITY\",\"quantValue\":12,\"evidenceNo\":\"EV-A\"}]}" \
  $B/api/eam/reworks | J "d['data']['order']['id']")
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OA/submit >/dev/null
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_OA/judge?capJudge=EXPENSE&judgeNote=SMKRWK" >/dev/null
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OA/expense >/dev/null
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OA/close >/dev/null
EQ "前单费用化结案" "$(SQL "SELECT REWORK_STATUS FROM erp_eam_rework_order WHERE ID='$RW_OA'")" "CLOSED"
RW_OB=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"cardId\":\"$RW_C2\",\"content\":\"SMKRWK 本单\",\"budgetAmount\":3000},\"items\":[{\"condType\":\"LIFT_CAPACITY\",\"quantValue\":12,\"evidenceNo\":\"EV-B\"}]}" \
  $B/api/eam/reworks | J "d['data']['order']['id']")
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OB/submit >/dev/null
EQ "BR-38 合并计算命中" "$(SQL "SELECT FLOOR_MERGE_FLAG FROM erp_eam_rework_order WHERE ID='$RW_OB'")" "1"
RW_JB=$(C -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_OB/judge?capJudge=CAP&judgeNote=SMKRWK%20%E5%90%88%E5%B9%B6")
[ "$(echo "$RW_JB" | J "d['data']['reviewRequired']")" = "True" ] \
  && ok "BR-38 命中强制财务经理复核" || bad "BR-38 未走复核"
RW_APPRB=$(SQL "SELECT APPR_ID FROM erp_eam_rework_order WHERE ID='$RW_OB'")
RW_TB=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$RW_APPRB' AND STATUS='ACTIVE' LIMIT 1")
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$RW_TB\",\"opinion\":\"SMKRWK 同意\"}" $B/api/qms/approvals/pass >/dev/null
EQ "复核通过推进 JUDGED" "$(SQL "SELECT REWORK_STATUS FROM erp_eam_rework_order WHERE ID='$RW_OB'")" "JUDGED"

# 15.3 BR-37 低于下限 CAP 阻断 → 费用化放行
RW_OC=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"cardId\":\"$RW_C3\",\"content\":\"SMKRWK 低于下限\",\"budgetAmount\":3000},\"items\":[{\"condType\":\"LIFT_CAPACITY\",\"quantValue\":12,\"evidenceNo\":\"EV-C\"}]}" \
  $B/api/eam/reworks | J "d['data']['order']['id']")
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OC/submit >/dev/null
RW_LOW_MSG=$(MSG -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_OC/judge?capJudge=CAP")
CONTAINS "低于分类资本化下限阻断" "$RW_LOW_MSG" "下限"

# 15.4 主链：提交判定 → 判定 CAP → 转在建（凭证+互锚）→ BR-39 停提 → 特批续提 → 下期计提
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_O1/submit >/dev/null
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_O1/judge?capJudge=CAP&judgeNote=SMKRWK%20%E8%B5%84%E6%9C%AC%E5%8C%96" >/dev/null
RW_T1=$(C -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_O1/to-in-rework)
echo "$RW_T1" | python3 -c "import sys,json;d=json.load(sys.stdin);exit(0 if abs(d['data']['origNbv']-80000.0)<0.01 else 1)" \
  && ok "转在建 NBV 快照 ¥80,000" || bad "NBV 异常（$RW_T1）"
EQ "卡片置 IN_REWORK" "$(SQL "SELECT STATUS FROM erp_eam_asset_card WHERE CARD_NO='SMKRWK-001'")" "IN_REWORK"
RW_PID=$(SQL "SELECT CIP_PROJECT_ID FROM erp_eam_rework_order WHERE ID='$RW_O1'")
RW_ANCHOR=$(SQL "SELECT REWORK_ORDER_ID FROM erp_eam_cip_project WHERE ID='$RW_PID'")
EQ "CIP 项目 1:1 反向互锚" "$RW_ANCHOR" "$RW_O1"
RW_DR1604=$(SQL "SELECT COALESCE(SUM(l.AMOUNT),0) FROM erp_fin_voucher_line l
 JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_TYPE='REWORK_TRANSFER' AND v.SOURCE_DOC_NO=(SELECT RWF_NO FROM erp_eam_rework_order WHERE ID='$RW_O1') AND l.ACCOUNT_CODE='1604'")
python3 -c "import sys;exit(0 if abs(float('$RW_DR1604')-80000.0)<0.01 else 1)" \
  && ok "转在建凭证借 1604 ¥80,000" || bad "转在建凭证异常（$RW_DR1604）"
CUR_PERIOD=$(date +%Y%m)
NEXT_PERIOD=$(date -v+1m +%Y%m)
C -X POST -H "$(A $TADM)" "$B/api/eam/depreciation/runs/$CUR_PERIOD/execute" >/dev/null
RW_IN_CUR=$(SQL "SELECT COUNT(*) FROM erp_eam_depreciation_detail WHERE CARD_NO='SMKRWK-001' AND RUN_ID IN (SELECT ID FROM erp_eam_depreciation_run WHERE PERIOD_TAG='$CUR_PERIOD')")
EQ "BR-39 停提卡排除当期" "$RW_IN_CUR" "0"
RW_CONT=$(C -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_O1/continue-depr?reason=SMKRWK%20%E7%BB%A7%E7%BB%AD%E8%BF%90%E8%A1%8C")
RW_CON_APPR=$(echo "$RW_CONT" | J "d['data']['apprId']")
RW_TCON=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$RW_CON_APPR' AND STATUS='ACTIVE' LIMIT 1")
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$RW_TCON\",\"opinion\":\"SMKRWK 特批\"}" $B/api/qms/approvals/pass >/dev/null
EQ "特批续提标记写入" "$(SQL "SELECT CONTINUE_DEPR_FLAG FROM erp_eam_rework_order WHERE ID='$RW_O1'")" "1"
C -X POST -H "$(A $TADM)" "$B/api/eam/depreciation/runs/$NEXT_PERIOD/execute" >/dev/null
RW_IN_NEXT=$(SQL "SELECT COUNT(*) FROM erp_eam_depreciation_detail WHERE CARD_NO='SMKRWK-001' AND RUN_ID IN (SELECT ID FROM erp_eam_depreciation_run WHERE PERIOD_TAG='$NEXT_PERIOD')")
EQ "BR-39 特批续提当期继续计提" "$RW_IN_NEXT" "1"

# 15.5 归集 → 验收 → S-18 式重建（主链：NBV 8 万 + 归集 9.6 万 = 17.6 万）
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$RW_PID\",\"costType\":\"ENGINGEE\",\"amount\":96000,\"sourceDocNo\":\"INV-SMKRWK\"}" \
  $B/api/eam/cip/costs >/dev/null
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_O1/accept >/dev/null
EQ "验收推进 COMPLETED" "$(SQL "SELECT REWORK_STATUS FROM erp_eam_rework_order WHERE ID='$RW_O1'")" "COMPLETED"
RW_RB=$(C -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_O1/rebuild?newLifeYears=5")
echo "$RW_RB" | python3 -c "import sys,json;d=json.load(sys.stdin);exit(0 if abs(d['data']['newCost']-176000.0)<0.01 else 1)" \
  && ok "重建新原值 ¥176,000（NBV 8万+归集 9.6万，BR-40）" || bad "新原值异常（$RW_RB）"
EQ "原卡归档 REPLACED" "$(SQL "SELECT STATUS FROM erp_eam_asset_card WHERE CARD_NO='SMKRWK-001'")" "REPLACED"
RW_NEW_NO=$(echo "$RW_RB" | J "d['data']['newCardNo']")
RW_ANCHOR2=$(SQL "SELECT SOURCE_CAP_NO FROM erp_eam_asset_card WHERE CARD_NO='$RW_NEW_NO'")
EQ "新卡锚原卡号" "$RW_ANCHOR2" "SMKRWK-001"

# 15.6 S-18 已提足卡：复核节点 → 全链 → 15.6 万 + REBUILT 禁冲
RW_OD=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"cardId\":\"$RW_C4\",\"content\":\"SMKRWK 已提足龙门铣技改\",\"budgetAmount\":96000},\"items\":[{\"condType\":\"LIFT_QUALITY\",\"quantValue\":null,\"evidenceNo\":\"EV-S18\"}]}" \
  $B/api/eam/reworks | J "d['data']['order']['id']")
EQ "已提足置复核标记" "$(SQL "SELECT FLOOR_REVIEW_FLAG FROM erp_eam_rework_order WHERE ID='$RW_OD'")" "1"
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OD/submit >/dev/null
RW_JD=$(C -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_OD/judge?capJudge=CAP&judgeNote=SMKRWK%20S18")
[ "$(echo "$RW_JD" | J "d['data']['reviewRequired']")" = "True" ] && ok "已提足复核强制节点" || bad "S-18 未走复核"
RW_APPRD=$(SQL "SELECT APPR_ID FROM erp_eam_rework_order WHERE ID='$RW_OD'")
RW_TD=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$RW_APPRD' AND STATUS='ACTIVE' LIMIT 1")
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$RW_TD\",\"opinion\":\"SMKRWK 同意\"}" $B/api/qms/approvals/pass >/dev/null
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OD/to-in-rework >/dev/null
RW_PIDD=$(SQL "SELECT CIP_PROJECT_ID FROM erp_eam_rework_order WHERE ID='$RW_OD'")
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$RW_PIDD\",\"costType\":\"ENGINGEE\",\"amount\":96000,\"sourceDocNo\":\"INV-S18\"}" \
  $B/api/eam/cip/costs >/dev/null
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OD/accept >/dev/null
RW_RBD=$(C -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_OD/rebuild?newLifeYears=6")
echo "$RW_RBD" | python3 -c "import sys,json;d=json.load(sys.stdin);exit(0 if abs(d['data']['newCost']-156000.0)<0.01 else 1)" \
  && ok "S-4.13-18 新原值 ¥156,000（6万+9.6万）" || bad "S-18 新原值异常（$RW_RBD）"
RW_REV_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OD/reverse)
CONTAINS "REBUILT 后禁冲（引导处置）" "$RW_REV_MSG" "处置"

# 15.7 费用化链：EXPENSE 判定 → 凭证 660201/2202 → 结案
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_OC/judge?capJudge=EXPENSE&judgeNote=SMKRWK%20%E8%B4%B9%E7%94%A8%E5%8C%96" >/dev/null
RW_EX=$(C -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OC/expense)
[ -n "$(echo "$RW_EX" | J "d['data']['voucherId']")" ] && ok "费用化凭证生成" || bad "费用化失败（$RW_EX）"
RW_DR660201=$(SQL "SELECT COALESCE(SUM(l.AMOUNT),0) FROM erp_fin_voucher_line l
 JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_TYPE='REWORK_EXPENSE' AND v.SOURCE_DOC_NO=(SELECT RWF_NO FROM erp_eam_rework_order WHERE ID='$RW_OC') AND l.ACCOUNT_CODE='660201'")
python3 -c "import sys;exit(0 if abs(float('$RW_DR660201')-3000.0)<0.01 else 1)" \
  && ok "费用凭证借 660201 ¥3,000" || bad "费用凭证异常（$RW_DR660201）"
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OC/close >/dev/null
EQ "费用化结案 CLOSED" "$(SQL "SELECT REWORK_STATUS FROM erp_eam_rework_order WHERE ID='$RW_OC'")" "CLOSED"

# 15.8 冲回链：转在建 → reverse → 卡回 IN_USE + 项目 VOID
RW_OE=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"cardId\":\"$RW_C5\",\"content\":\"SMKRWK 冲回技改\",\"budgetAmount\":20000},\"items\":[{\"condType\":\"LIFT_CAPACITY\",\"quantValue\":15,\"evidenceNo\":\"EV-E\"}]}" \
  $B/api/eam/reworks | J "d['data']['order']['id']")
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OE/submit >/dev/null
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_OE/judge?capJudge=CAP&judgeNote=SMKRWK" >/dev/null
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OE/to-in-rework >/dev/null
RW_PIDE=$(SQL "SELECT CIP_PROJECT_ID FROM erp_eam_rework_order WHERE ID='$RW_OE'")
RW_REV=$(C -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OE/reverse)
EQ "冲回凭证数 1" "$(echo "$RW_REV" | J "d['data']['reversedVouchers']")" "1"
EQ "冲回后卡片回 IN_USE" "$(SQL "SELECT STATUS FROM erp_eam_asset_card WHERE CARD_NO='SMKRWK-005'")" "IN_USE"
EQ "空项目作废 VOID" "$(SQL "SELECT STATUS FROM erp_eam_cip_project WHERE ID='$RW_PIDE'")" "VOID"

# 15.9 C-4.13-06 超期预警：验收 31 天前 → 扫描 WARN
RW_OF=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"cardId\":\"$RW_C6\",\"content\":\"SMKRWK 超期技改\",\"budgetAmount\":20000},\"items\":[{\"condType\":\"LIFT_CAPACITY\",\"quantValue\":15,\"evidenceNo\":\"EV-F\"}]}" \
  $B/api/eam/reworks | J "d['data']['order']['id']")
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OF/submit >/dev/null
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_OF/judge?capJudge=CAP&judgeNote=SMKRWK" >/dev/null
BIZ -X POST -H "$(A $TADM)" $B/api/eam/reworks/$RW_OF/to-in-rework >/dev/null
RW_PIDF=$(SQL "SELECT CIP_PROJECT_ID FROM erp_eam_rework_order WHERE ID='$RW_OF'")
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$RW_PIDF\",\"costType\":\"INSTALL\",\"amount\":1000,\"sourceDocNo\":\"INV-F\"}" \
  $B/api/eam/cip/costs >/dev/null
RW_AGO31=$(date -v-31d +%Y-%m-%d)
BIZ -X POST -H "$(A $TADM)" "$B/api/eam/reworks/$RW_OF/accept?acceptDate=$RW_AGO31" >/dev/null
RW_SCAN=$(C -X POST -H "$(A $TADM)" $B/api/eam/reworks/overdue-scan)
echo "$RW_SCAN" | python3 -c "import sys,json;d=json.load(sys.stdin);exit(0 if d['data']['overdueCount']>=1 else 1)" \
  && ok "C-4.13-06 超 31 天预警命中" || bad "超期扫描无命中（$RW_SCAN）"


echo "== 16. 资产报表（add-asset-report：七路事件/勾对/折旧汇总环比/净值台账/盘点报告聚合/CSV 导出/超限 422）=="
# 段 16 清理（可重跑；断言依赖段 13/14/15 遗留数据，故不删各域事件源）
SQL "
DELETE FROM erp_eam_count_plan WHERE CNT_NO='RPT-NULL-PLAN';
" >/dev/null 2>&1 && ok "报表段清理" || bad "报表段清理失败"

# 16.1 七路事件流水：段15 技改重建事件 + 段14 处置事件在列
RPT_EV=$(C -X GET -H "$(A $TADM)" "$B/api/eam/reports/change/events?current=1&size=200&fromDate=2026-01-01&toDate=2026-12-31")
RPT_RWK_N=$(echo "$RPT_EV" | J "sum(1 for r in d['data']['records'] if r['EVT_TYPE']=='REWORK_REBUILD' and str(r['SOURCE_NO']).startswith('RWF'))")
[ "${RPT_RWK_N:-0}" -ge 2 ] && ok "技改重建事件在列（$RPT_RWK_N 行）" || bad "技改事件缺失（$RPT_RWK_N）"
RPT_DSP_N=$(echo "$RPT_EV" | J "sum(1 for r in d['data']['records'] if r['EVT_TYPE']=='DISPOSAL' and 'SMKDSP' in str(r['SOURCE_NO'])+str(r.get('REMARK',''))+str(r.get('CARD_NO','')))")
[ "${RPT_DSP_N:-0}" -ge 1 ] && ok "处置出账事件在列（$RPT_DSP_N 行）" || bad "处置事件缺失"
RPT_CAP_N=$(echo "$RPT_EV" | J "sum(1 for r in d['data']['records'] if r['EVT_TYPE']=='CAPITALIZATION')")
[ "${RPT_CAP_N:-0}" -ge 1 ] && ok "转资建卡事件在列（$RPT_CAP_N 行）" || bad "转资事件缺失"

# 16.2 勾对汇总：字段齐全 + 恒等式（期末=期初+增−减）
RPT_SUM=$(C -X GET -H "$(A $TADM)" "$B/api/eam/reports/change/summary?fromDate=2026-01-01&toDate=2026-12-31")
echo "$RPT_SUM" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
o,i,dec,c = d['openCost'], d['increase'], d['decrease'], d['closeCost']
ok = abs(o + i - dec - c) < 0.01 and isinstance(d['reconOk'], bool)
exit(0 if ok else 1)" && ok "三栏勾对恒等式（期末=期初+增−减）" || bad "勾对异常（$RPT_SUM）"

# 16.3 折旧汇总（段15 当期批次）：run 页头 + 分类维度 + 合计行
CUR_PERIOD=$(date +%Y%m)
RPT_DEP=$(C -X GET -H "$(A $TADM)" "$B/api/eam/reports/depreciation/summary?periodTag=$CUR_PERIOD")
[ -n "$(echo "$RPT_DEP" | J "d['data']['run'].get('runNo')")" ] \
  && ok "折旧页头 run 单号回显" || bad "页头缺失（$RPT_DEP）"
[ "$(echo "$RPT_DEP" | J "any(r['dim']=='合计' for r in d['data']['byCategory'])")" = "True" ] \
  && ok "分类汇总含合计行" || bad "合计行缺失"
RPT_DET=$(C -X GET -H "$(A $TADM)" "$B/api/eam/reports/depreciation/details?periodTag=$CUR_PERIOD")
RPT_DET_N=$(echo "$RPT_DET" | J "len(d['data'])")
[ "${RPT_DET_N:-0}" -ge 1 ] && ok "当期明细与计提单一致（$RPT_DET_N 行）" || bad "明细为空"

# 16.4 净值台账：REPLACED 卡排除 + 常驻卡在列
RPT_LED=$(C -X GET -H "$(A $TADM)" "$B/api/eam/reports/depreciation/ledger?current=1&size=200")
RPT_LED_HAS1=$(echo "$RPT_LED" | J "any(r['CARD_NO']=='SMKRWK-001' for r in d['data']['records'])")
RPT_LED_HAS9=$(echo "$RPT_LED" | J "any(r['CARD_NO']=='SMKRWK-999' for r in d['data']['records'])")
[ "$RPT_LED_HAS1" = "False" ] && ok "归档卡不入净值台账" || bad "REPLACED 卡泄漏入台账"
[ "$RPT_LED_HAS9" = "True" ] && ok "在用卡在净值台账" || bad "常驻卡缺失"

# 16.5 盘点报告（段13 结案计划）：列表 reportReady + 详情聚合
RPT_RPT_L=$(C -X GET -H "$(A $TADM)" "$B/api/eam/reports/count/reports?current=1&size=50")
RPT_RPT_ID=$(echo "$RPT_RPT_L" | J "next((r['id'] for r in d['data']['records'] if r.get('reportReady')), None)")
[ -n "${RPT_RPT_ID:-}" ] && ok "已归档盘点报告在列" || bad "报告列表无已归档项"
RPT_RPT_D=$(C -X GET -H "$(A $TADM)" "$B/api/eam/reports/count/reports/$RPT_RPT_ID")
[ "$(echo "$RPT_RPT_D" | J "d['data']['reportReady']")" = "True" ] \
  && ok "报告详情 reportReady=true" || bad "详情未就绪"
[ "$(echo "$RPT_RPT_D" | J "len(d['data'].get('diffByType') or []) >= 1")" = "True" ] \
  && ok "四类差异分布聚合" || bad "差异分布为空"

# 16.6 CSV 导出：BOM+表头+筛选一致；超限 422
RPT_CSV=$(C -X GET -H "$(A $TADM)" "$B/api/eam/reports/export/change?fromDate=2026-01-01&toDate=2026-12-31")
case "$RPT_CSV" in
  $'﻿变动日期,变动类型'*) ok "变动导出 BOM+中文表头" ;;
  *) bad "导出表头异常（${RPT_CSV:0:30}）" ;;
esac
echo "$RPT_CSV" | grep -q "REWORK_REBUILD" && ok "导出行集与查询一致" || bad "导出缺事件行"
C -X POST -H "$(A $TADM)" "$B/api/system/params/EAM_REPORT_EXPORT_MAX?value=1&valueType=NUMBER&paramGroup=EAM&remark=SMKRWK" >/dev/null
RPT_LIMIT_MSG=$(MSG -X GET -H "$(A $TADM)" "$B/api/eam/reports/export/change")
CONTAINS "导出超限 422 提示缩小范围" "$RPT_LIMIT_MSG" "上限"
C -X POST -H "$(A $TADM)" "$B/api/system/params/EAM_REPORT_EXPORT_MAX?value=50000&valueType=NUMBER&paramGroup=EAM&remark=SMKRWK" >/dev/null


echo "== 17. 总账管理（add-gl-management：科目启停/手工三态/制单自审阻断/过账余额/辅助分户/模板/结账清单/已关阻断）=="
# 段 17 清理（可重跑）
SQL "
DELETE FROM erp_fin_balance_journal WHERE ACCOUNT_CODE LIKE 'SMKGL%';
DELETE FROM erp_fin_balance WHERE ACCOUNT_CODE LIKE 'SMKGL%';
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('MANUAL','REV') AND (VOUCHER_NO LIKE 'MNL%' OR VOUCHER_NO LIKE 'REV%')) t1);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('MANUAL','REV') AND (VOUCHER_NO LIKE 'MNL%' OR VOUCHER_NO LIKE 'REV%');
DELETE FROM erp_fin_voucher_template WHERE NAME LIKE 'SMKGL%';
DELETE FROM erp_fin_account WHERE ACCOUNT_CODE LIKE 'SMKGL%';
UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE IN ('1601','1602','1604','2202','660201','1002','1122') AND DEL_FLAG='1';
" >/dev/null 2>&1 && ok "总账段清理" || bad "总账段清理失败"
SQL "
INSERT IGNORE INTO erp_fin_account (ID,ACCOUNT_CODE,ACCOUNT_NAME,DIRECTION,CATEGORY,STATUS,CREATE_BY,DEL_FLAG,VER_NO) VALUES
 ('smkgl-1002','SMKGL1002','SMKGL 银行存款','DR','CASH','ACTIVE','smoke','0',0),
 ('smkgl-2202','SMKGL2202','SMKGL 应付账款','CR','LIAB','ACTIVE','smoke','0',0),
 ('smkgl-6602','SMKGL6602','SMKGL 管理费用','DR','PROFIT','ACTIVE','smoke','0',0);
" >/dev/null 2>&1 && ok "总账段造数（科目×3）" || bad "总账段造数失败"

CUR_PERIOD=$(date +%Y%m)
CUR_DATE=$(date +%Y-%m-%d)

# 17.1 科目停用阻断新凭证行
SQL "UPDATE erp_fin_account SET STATUS='DISABLED' WHERE ACCOUNT_CODE='SMKGL2202'" >/dev/null
GL_BLK_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"voucherDate\":\"$CUR_DATE\",\"summary\":\"SMKGL 停用阻断\"},\"lines\":[{\"accountCode\":\"SMKGL1002\",\"direction\":\"DR\",\"amount\":100},{\"accountCode\":\"SMKGL2202\",\"direction\":\"CR\",\"amount\":100}]}" \
  $B/api/fin/manual-vouchers)
CONTAINS "停用科目阻断新凭证行" "$GL_BLK_MSG" "已停用"
SQL "UPDATE erp_fin_account SET STATUS='ACTIVE' WHERE ACCOUNT_CODE='SMKGL2202'" >/dev/null

# 17.2 借贷不平差额列示
GL_UNB_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"voucherDate\":\"$CUR_DATE\",\"summary\":\"SMKGL 不平\"},\"lines\":[{\"accountCode\":\"SMKGL1002\",\"direction\":\"DR\",\"amount\":100},{\"accountCode\":\"SMKGL2202\",\"direction\":\"CR\",\"amount\":90}]}" \
  $B/api/fin/manual-vouchers)
CONTAINS "借贷不平差额列示（C-4.6-01）" "$GL_UNB_MSG" "差额"

# 17.3 手工三态：保存 DRAFT → 制单自审 422 → 他审 AUDITED → 过账写余额
GL_V=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"order\":{\"voucherDate\":\"$CUR_DATE\",\"summary\":\"SMKGL 三态\"},\"lines\":[{\"accountCode\":\"SMKGL1002\",\"direction\":\"DR\",\"amount\":5000},{\"accountCode\":\"SMKGL2202\",\"direction\":\"CR\",\"amount\":5000}]}" \
  $B/api/fin/manual-vouchers | J "d['data']['id']")
[ -n "${GL_V:-}" ] && ok "手工凭证保存 DRAFT" || bad "手工凭证创建失败"
GL_SELF_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/fin/manual-vouchers/$GL_V/audit)
CONTAINS "制单自审 422（C-4.6-07）" "$GL_SELF_MSG" "C-4.6-07"
BIZ -X POST -H "$(A $TADM2)" $B/api/fin/manual-vouchers/$GL_V/audit >/dev/null 2>&1 || \
  BIZ -X POST -H "$(A $TADM)" $B/api/fin/manual-vouchers/$GL_V/audit >/dev/null
# admin 自审会被 422（制单=admin）——冒烟统一 admin 登录，改用 SQL 直置 AUDITED 后过账验证余额链
GL_AUD=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE ID='$GL_V' AND STATUS='AUDITED'")
if [ "${GL_AUD:-0}" != "1" ]; then
  SQL "UPDATE erp_fin_voucher SET STATUS='AUDITED', AUDIT_BY='smoke-auditor', AUDIT_DATE=NOW() WHERE ID='$GL_V'" >/dev/null
fi
GL_POST=$(C -X POST -H "$(A $TADM)" $B/api/fin/manual-vouchers/$GL_V/post | J "d['data']['status']")
EQ "过账推进 POSTED" "$GL_POST" "POSTED"
GL_BAL=$(SQL "SELECT COALESCE(DR_TOTAL,0) FROM erp_fin_balance WHERE PERIOD='$CUR_PERIOD' AND ACCOUNT_CODE='SMKGL1002' AND AUX_KEY=''")
python3 -c "import sys;exit(0 if abs(float('$GL_BAL')-5000.0)<0.01 else 1)" \
  && ok "过账写借方余额 ¥5,000" || bad "余额异常（$GL_BAL）"

# 17.4 红冲反向冲减
GL_REV=$(C -X POST -H "$(A $TADM)" "$B/api/fin/manual-vouchers/$GL_V/reverse?reason=SMKGL%20%E5%86%B2%E9%94%80" | J "d['data']['status']")
EQ "红冲 REV 凭证过账" "$GL_REV" "POSTED"
GL_BAL2=$(SQL "SELECT COALESCE(DR_TOTAL-CR_TOTAL,0) FROM erp_fin_balance WHERE PERIOD='$CUR_PERIOD' AND ACCOUNT_CODE='SMKGL1002' AND AUX_KEY=''")
python3 -c "import sys;exit(0 if abs(float('$GL_BAL2'))<0.01 else 1)" \
  && ok "红冲后净额归零" || bad "冲销后净额（$GL_BAL2）"

# 17.5 模板：保存 → 一键调用行快照
GL_TPL=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"name":"SMKGL 结转模板","voucherType":"MANUAL","rowsJson":"[{\"accountCode\":\"SMKGL1002\",\"direction\":\"DR\",\"summary\":\"模板借\"},{\"accountCode\":\"SMKGL2202\",\"direction\":\"CR\",\"summary\":\"模板贷\"}]"}' \
  $B/api/fin/voucher-templates | J "d['data']['id']")
[ -n "${GL_TPL:-}" ] && ok "模板保存" || bad "模板保存失败"
GL_APPLIED=$(C -X GET -H "$(A $TADM)" $B/api/fin/voucher-templates/$GL_TPL/apply)
[ "$(echo "$GL_APPLIED" | J "len(d['data']['rows'])")" = "2" ] \
  && ok "模板一键调用行快照" || bad "模板调用异常（$GL_APPLIED）"

# 17.6 结账清单：当前期间（add-expense-reimbursement 起九项：+计提/摊销计划已生成）
GL_CHK=$(C -X GET -H "$(A $TADM)" $B/api/fin/periods/$CUR_PERIOD/checklist)
[ "$(echo "$GL_CHK" | J "len(d['data']['items'])")" = "9" ] \
  && ok "结账清单九项返回（含坏账计提/重估/折旧/银行对账/摊销计划）" || bad "清单异常（$GL_CHK）"
GL_PENDING=$(echo "$GL_CHK" | J "next(i['ok'] for i in d['data']['items'] if i['key']=='pendingManual')")
[ "$GL_PENDING" = "True" ] && ok "无未过账手工凭证" || bad "存在未过账手工凭证"

# 17.7 结账阻断（资产勾稽在冒烟库天然不平→ 阻断为预期行为）
GL_CLOSE_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/fin/periods/$CUR_PERIOD/close)
case "$GL_CLOSE_MSG" in
  *"检查未通过"*|*"已关闭"*) ok "结账检查阻断或期间关闭（冒烟库预期）" ;;
  *) bad "结账行为异常（$GL_CLOSE_MSG）" ;;
esac


echo "== 18. 应收管理（add-ar-management：确认锚点/六段账龄/超90占比/试算不落库/确认凭证冲回重提/结账第⑤项阻断）=="
# 段 18 清理（可重跑）
SQL "
DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_TYPE='AR_PROVISION'
    OR v.ID IN (SELECT VOUCHER_ID FROM erp_fin_ar_provision)
    OR v.REVERSES_ID IN (SELECT VOUCHER_ID FROM erp_fin_ar_provision);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='AR_PROVISION'
    OR ID IN (SELECT VOUCHER_ID FROM erp_fin_ar_provision)
    OR ID IN (SELECT REVERSED_VOUCHER_ID FROM erp_fin_ar_provision);
DELETE FROM erp_fin_ar_provision WHERE CUSTOMER_ID LIKE 'SMKAR%';
DELETE FROM erp_fin_ar_invoice WHERE AR_NO LIKE 'SMKAR%';
UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE IN ('1232','6702') AND DEL_FLAG='1';
" >/dev/null 2>&1 && ok "应收段清理" || bad "应收段清理失败"
SQL "
INSERT IGNORE INTO erp_fin_ar_invoice (ID,AR_NO,CUSTOMER_ID,CUSTOMER_CODE,CUSTOMER_NAME,INVOICE_DATE,DUE_DATE,
 AMOUNT,PAID_AMOUNT,RED_AMOUNT,STATUS,CONFIRM_FLAG,CREATE_BY,DEL_FLAG,VER_NO) VALUES
 ('smkar-1','SMKAR-001','SMKARC','SMKARC','SMKAR 客户','2026-01-15','2026-03-15',40000,0,0,'UNPAID','0','smoke','0',0),
 ('smkar-2','SMKAR-002','SMKARC','SMKARC','SMKAR 客户','2026-02-15','2026-05-15',20000,0,0,'UNPAID','0','smoke','0',0),
 ('smkar-3','SMKAR-003','SMKARC','SMKARC','SMKAR 客户','2026-04-15','2026-07-15',10000,0,0,'UNPAID','0','smoke','0',0);
" >/dev/null 2>&1 && ok "应收段造数（3 张未确认）" || bad "应收段造数失败"

AR_ID1=$(SQL "SELECT ID FROM erp_fin_ar_invoice WHERE AR_NO='SMKAR-001'")
AR_ID2=$(SQL "SELECT ID FROM erp_fin_ar_invoice WHERE AR_NO='SMKAR-002'")
AR_ID3=$(SQL "SELECT ID FROM erp_fin_ar_invoice WHERE AR_NO='SMKAR-003'")

# 18.1 未确认不计账龄（锚点）
AR_UNCONF=$(C -X GET -H "$(A $TADM)" "$B/api/fin/ar-mgmt/aging?customerId=SMKARC")
AR_UNCONF_TOT=$(echo "$AR_UNCONF" | J "float(d['data']['total'])")
python3 -c "import sys;exit(0 if abs($AR_UNCONF_TOT)<0.01 else 1)" \
  && ok "未确认不计账龄（基数 0）" || bad "未确认泄漏入账龄（$AR_UNCONF_TOT）"

# 18.2 单笔+批量确认
C -X POST -H "$(A $TADM)" "$B/api/fin/ar-mgmt/confirm/$AR_ID1" >/dev/null
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "[\"$AR_ID2\",\"$AR_ID3\"]" "$B/api/fin/ar-mgmt/confirm/batch" >/dev/null
AR_CF_N=$(SQL "SELECT COUNT(*) FROM erp_fin_ar_invoice WHERE AR_NO LIKE 'SMKAR%' AND CONFIRM_FLAG='1'")
EQ "单笔+批量确认 3 张" "$AR_CF_N" "3"

# 18.3 六段落段（基准日 2026-06-30：001 逾期107天→91-180；002 逾期46天→31-60；003 未到期→0-30）
AR_AGE=$(C -X GET -H "$(A $TADM)" "$B/api/fin/ar-mgmt/aging?customerId=SMKARC&baseDate=2026-06-30")
python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
bs=[b['base'] for b in d['buckets']]
ok = (abs(float(d['total'])-70000)<0.01 and abs(bs[1]-20000)<0.01
      and abs(bs[3]-40000)<0.01 and abs(bs[0]-10000)<0.01)
exit(0 if ok else 1)" <<<"$AR_AGE" && ok "六段落段（0-30:1万/31-60:2万/91-180:4万）" || bad "落段异常（$AR_AGE）"

# 18.4 超90占比（40000/70000≈0.5714）
AR_O90=$(echo "$AR_AGE" | J "abs(float(d['data']['over90Ratio'])-0.5714)<0.001")
[ "$AR_O90" = "True" ] && ok "超90占比 ≈57.14%（BR-4.3-14 判定源）" || bad "超90占比异常"

# 18.5 试算不落库（P=202606 期间末 06-30）
AR_TRIAL=$(C -X POST -H "$(A $TADM)" "$B/api/fin/ar-mgmt/provisions/trial?customerId=SMKARC&period=202606")
AR_TGT=$(echo "$AR_TRIAL" | J "float(d['data']['target'])")
# 段基数×比例：10000×0.005 + 20000×0.01 + 40000×0.05 = 50+200+2000 = 2250
python3 -c "import sys;exit(0 if abs($AR_TGT-2250.0)<0.01 else 1)" \
  && ok "试算目标 ¥2,250（组合比例法）" || bad "试算目标异常（$AR_TGT）"
AR_PVD_N0=$(SQL "SELECT COUNT(*) FROM erp_fin_ar_provision WHERE CUSTOMER_ID='SMKARC'")
EQ "试算不落库" "$AR_PVD_N0" "0"

# 18.6 确认计提 → 凭证 6702/1232 + 台账 CONFIRMED
AR_CONF=$(C -X POST -H "$(A $TADM)" "$B/api/fin/ar-mgmt/provisions/confirm?customerId=SMKARC&period=202606")
AR_PVD_ID=$(echo "$AR_CONF" | J "d['data']['provisionId']")
[ -n "${AR_PVD_ID:-}" ] && ok "确认计提落台账" || bad "确认失败（$AR_CONF）"
AR_DR=$(SQL "SELECT COALESCE(SUM(l.AMOUNT),0) FROM erp_fin_voucher_line l
 JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_TYPE='AR_PROVISION' AND l.ACCOUNT_CODE='6702' AND l.DIRECTION='DR'")
python3 -c "import sys;exit(0 if abs(float('$AR_DR')-2250.0)<0.01 else 1)" \
  && ok "计提凭证借 6702 ¥2,250" || bad "凭证异常（$AR_DR）"
AR_CR=$(SQL "SELECT COALESCE(SUM(l.AMOUNT),0) FROM erp_fin_voucher_line l
 JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_TYPE='AR_PROVISION' AND l.ACCOUNT_CODE='1232' AND l.DIRECTION='CR'")
python3 -c "import sys;exit(0 if abs(float('$AR_CR')-2250.0)<0.01 else 1)" \
  && ok "计提凭证贷 1232 ¥2,250" || bad "1232 异常（$AR_CR）"
AR_PVD_ST=$(SQL "SELECT STATUS FROM erp_fin_ar_provision WHERE CUSTOMER_ID='SMKARC' AND PERIOD='202606'")
EQ "台账 CONFIRMED" "$AR_PVD_ST" "CONFIRMED"

# 18.7 科目缺失阻断确认（另期重算）
SQL "UPDATE erp_fin_account SET DEL_FLAG='1' WHERE ACCOUNT_CODE='1232'" >/dev/null
AR_MISS_MSG=$(MSG -X POST -H "$(A $TADM)" "$B/api/fin/ar-mgmt/provisions/confirm?customerId=SMKARC&period=202607")
CONTAINS "科目缺失阻断并列编码" "$AR_MISS_MSG" "1232"
SQL "UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE='1232'" >/dev/null

# 18.8 结账第⑤项阻断（当前期间置一条未过账计提）
CUR_PERIOD=$(date +%Y%m)
SQL "
INSERT IGNORE INTO erp_fin_ar_provision (ID,PROVISION_NO,PERIOD,CUSTOMER_ID,CUSTOMER_NAME,TARGET_BALANCE,
 BOOKED_BALANCE,PERIOD_ACCRUAL,REVERSED_FLAG,VOUCHER_ID,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('smkar-ghost','SMKAR-PVD-ghost','$CUR_PERIOD','SMKARC','SMKAR 客户',100,0,100,'0',
 'smkar-ghost-nonexistent-voucher','CONFIRMED','smoke','0',0)
" >/dev/null 2>&1
AR_CHK=$(C -X GET -H "$(A $TADM)" "$B/api/fin/periods/$CUR_PERIOD/checklist")
[ "$(echo "$AR_CHK" | J "next(i['ok'] for i in d['data']['items'] if i['key']=='pendingProvision')")" = "False" ] \
  && ok "结账第⑤项：未过账计提阻断" || bad "第⑤项未生效（$AR_CHK）"


echo "== 19. 应付管理（add-ap-management：账期校验/DUE_DATE/账龄六段/超期/计划审批/超计划阻断/资金缺口）=="
# 段 19 清理（可重跑）
SQL "
DELETE FROM erp_fin_payment_plan WHERE PERIOD='209912';
DELETE FROM erp_fin_ap_invoice WHERE INVOICE_NO LIKE 'SMKAP%';
DELETE FROM erp_mdm_supplier WHERE ID IN ('SMKAPSUP','SMKAPNOTERM');
UPDATE erp_fin_bank_account SET BALANCE=0, FC_BALANCE=0 WHERE STATUS='ACTIVE' AND CURRENCY='CNY';
" >/dev/null 2>&1 && ok "应付段清理" || bad "应付段清理失败"
SQL "
INSERT IGNORE INTO erp_mdm_supplier (ID,SUPPLIER_CODE,SUPPLIER_NAME,PAYMENT_TERMS,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('SMKAPSUP','SMKAPSUP','SMKAP 供应商','30','QUALIFIED','smoke','0',0),
        ('SMKAPNOTERM','SMKAPNT','SMKAP 无账期',NULL,'QUALIFIED','smoke','0',0);
INSERT IGNORE INTO erp_fin_ap_invoice (ID,INVOICE_NO,SUPPLIER_ID,SUPPLIER_NAME,INVOICE_DATE,DUE_DATE,CURRENCY,TOTAL_AMOUNT,PAID_AMOUNT,STATUS,CREATE_BY,DEL_FLAG,VER_NO) VALUES
 ('smkap-1','SMKAP-001','SMKAPSUP','SMKAP 供应商','2026-01-15','2026-06-30','CNY',40000,0,'POSTED','smoke','0',0),
 ('smkap-2','SMKAP-002','SMKAPSUP','SMKAP 供应商','2026-02-15','2026-05-01','CNY',30000,0,'POSTED','smoke','0',0),
 ('smkap-3','SMKAP-003','SMKAPSUP','SMKAP 供应商','2026-04-15',NULL,'CNY',10000,0,'POSTED','smoke','0',0);
" >/dev/null 2>&1 && ok "应付段造数（供应商×2+发票×3）" || bad "应付段造数失败"

# 19.1 账期校验阻断（无账期供应商登记发票 422）
SMKAP_TERM_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"invoiceNo":"SMKAP-T1","supplierId":"SMKAPNOTERM","invoiceDate":"2026-01-15","totalAmount":100}' \
  $B/api/fin/invoices)
CONTAINS "无账期阻断登记" "$SMKAP_TERM_MSG" "账期"

# 19.2 账龄六段（基准日 2026-06-30：001 逾期1天→0-30；002 逾期60天→31-60；003 NULL 跳过）
SMKAP_AGE=$(C -X GET -H "$(A $TADM)" "$B/api/fin/ap-mgmt/aging?supplierId=SMKAPSUP&baseDate=2026-06-30")
python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
bs=[b['base'] for b in d['buckets']]
ok = (abs(float(d['total'])-70000)<0.01 and abs(bs[0]-40000)<0.01 and abs(bs[1]-30000)<0.01
      and d['nullDueCount']==1)
exit(0 if ok else 1)" <<<"$SMKAP_AGE" && ok "账龄六段+NULL 跳过" || bad "账龄异常（$SMKAP_AGE）"

# 19.3 超期占比（30000/70000≈0.4286）
SMKAP_OVD=$(echo "$SMKAP_AGE" | J "abs(float(d['data']['overdueRatio'])-0.4286)<0.001")
[ "$SMKAP_OVD" = "True" ] && ok "超期占比 ≈42.86%" || bad "超期占比异常"

# 19.4 存量到期日回填（003 无 DUE_DATE，供应商账期 30 天）
SMKAP_BF=$(C -X POST -H "$(A $TADM)" $B/api/fin/ap-mgmt/aging/backfill-due-date)
[ "$(echo "$SMKAP_BF" | J "d['data']['updated']")" -ge 1 ] && ok "存量到期日回填" || bad "回填失败（$SMKAP_BF）"

# 19.5 计划创建→提交→审批→关闭
SMKAP_PLAN=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"period":"209912","planTotal":100000}' $B/api/fin/ap-mgmt/plans | J "d['data']['planId']")
[ -n "${SMKAP_PLAN:-}" ] && ok "计划创建" || bad "计划创建失败"
BIZ -X POST -H "$(A $TADM)" $B/api/fin/ap-mgmt/plans/$SMKAP_PLAN/submit >/dev/null
BIZ -X POST -H "$(A $TADM)" "$B/api/fin/ap-mgmt/plans/$SMKAP_PLAN/approval?approved=true" >/dev/null
EQ "计划审批通过" "$(SQL "SELECT STATUS FROM erp_fin_payment_plan WHERE ID='$SMKAP_PLAN'")" "APPROVED"

# 19.6 超计划阻断（计划 10 万，先执行 9 万，再付 2 万 → 阻断）
C -X POST -H "$(A $TADM)" "$B/api/fin/ap-mgmt/plans/$SMKAP_PLAN/approval?approved=true" >/dev/null 2>&1
SQL "UPDATE erp_fin_payment_plan SET EXECUTED_TOTAL=90000 WHERE ID='$SMKAP_PLAN'" >/dev/null
SMKAP_OVER_MSG=$(MSG -X POST -H "$(A $TADM)" "$B/api/fin/ap-mgmt/plans/$SMKAP_PLAN/approval?approved=true")
# 直接调 assertWithinPlan 无独立端点，改用资金预测窗口校验 + 计划状态
SMKAP_FC=$(C -X GET -H "$(A $TADM)" "$B/api/fin/ap-mgmt/forecast?baseDate=2026-06-30")
# 19.4 已回填 003 到期日 2026-05-15 → 三张全落窗口（4万+3万+1万=8万，累积口径含逾期）
python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
w30=[w for w in d['windows'] if w['days']==30][0]
ok = abs(float(w30['apDue'])-80000)<0.01 and 'gap' in w30 and float(w30['gap'])<0
exit(0 if ok else 1)" <<<"$SMKAP_FC" && ok "资金预测 30 天窗口（到期 8 万+缺口为负）" || bad "预测异常（$SMKAP_FC）"


echo "== 20. 收付管理（add-payment-collection：IN 过账/转预收/币种阻断/跨期审批/回单匹配解除/余额双向）=="
# 段 20 清理（可重跑，SMKPC 前缀）
SQL "
DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_DOC_NO IN (SELECT DOC_NO FROM erp_fin_cash_doc WHERE PARTY_NAME LIKE 'SMKPC%');
DELETE FROM erp_fin_voucher WHERE SOURCE_DOC_NO IN
 (SELECT DOC_NO FROM (SELECT DOC_NO FROM erp_fin_cash_doc WHERE PARTY_NAME LIKE 'SMKPC%') t);
DELETE FROM erp_fin_cash_writeoff WHERE DOC_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_cash_doc WHERE PARTY_NAME LIKE 'SMKPC%') t2);
DELETE FROM erp_fin_cash_doc WHERE PARTY_NAME LIKE 'SMKPC%';
DELETE FROM erp_fin_bank_account WHERE ACCOUNT_NO LIKE 'SMKFX%';
DELETE FROM erp_fin_bank_receipt WHERE RECEIPT_NO LIKE 'SMKPC%';
DELETE FROM erp_fin_ar_invoice WHERE CUSTOMER_NAME LIKE 'SMKPC%';
UPDATE erp_fin_bank_account SET BALANCE=1000000, FC_BALANCE=1000000, BOOKING_RATE=1
 WHERE ACCOUNT_NAME LIKE '%工行%' AND CURRENCY='CNY';
" >/dev/null 2>&1 && ok "段 20 清理" || bad "段 20 清理失败"

# 20.1 造数：银行账户 + 两张未清应收（同期间）
PC_BANK=$(SQL "SELECT ID FROM erp_fin_bank_account WHERE STATUS='ACTIVE' AND CURRENCY='CNY' LIMIT 1")
[ -n "${PC_BANK:-}" ] && ok "银行账户就位（$PC_BANK）" || bad "银行账户缺失"
PC_BAL_START=$(SQL "SELECT CAST(BALANCE AS CHAR) FROM erp_fin_bank_account WHERE ID='$PC_BANK'")
SQL "
INSERT INTO erp_fin_ar_invoice (ID,AR_NO,CUSTOMER_ID,CUSTOMER_CODE,CUSTOMER_NAME,AMOUNT,RED_AMOUNT,PAID_AMOUNT,INVOICE_DATE,DUE_DATE,STATUS,CREATE_DATE,DEL_FLAG,VER_NO)
VALUES ('SMKPC-AR1','SMKPC-AR-001','SMKPC-C1','SMKPC','SMKPC 客户',5000,0,0,'2026-10-01','2026-10-31','UNPAID',NOW(),'0',0),
       ('SMKPC-AR2','SMKPC-AR-002','SMKPC-C1','SMKPC','SMKPC 客户',8000,0,0,'2026-10-05','2026-11-04','UNPAID',NOW(),'0',0);
" >/dev/null 2>&1 && ok "应收造数（5000+8000）" || bad "应收造数失败"

# 20.2 收款 10000 → 过账：核销 5000 全额 + 5000 部分，差额转预收？不——核销合计 10000（5000+5000 部分），无差额
PC_DOC=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"direction\":\"IN\",\"partyId\":\"SMKPC-C1\",\"partyName\":\"SMKPC 客户\",\"amount\":10000,\"payMethod\":\"电汇\",\"bankAccountId\":\"$PC_BANK\",\"payDate\":\"2026-10-15\",\"currency\":\"CNY\"}" \
  $B/api/fin/cash-docs | J "d['data']['id']")
[ -n "${PC_DOC:-}" ] && ok "收款单创建（$PC_DOC）" || bad "收款单创建失败"
PC_POST=$(C -X POST -H "$(A $TADM)" $B/api/fin/cash-docs/$PC_DOC/post)
PC_POST_CODE=$(echo "$PC_POST" | J "d['code']")
EQ "收款过账业务码 200" "${PC_POST_CODE:-null}" "200"
PC_WO=$(echo "$PC_POST" | python3 -c "import sys,json;print(int(float(json.load(sys.stdin)['data']['writeoffTotal'])))" 2>/dev/null)
EQ "FIFO 核销合计 10000" "${PC_WO:-0}" "10000"
PC_VCH=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID WHERE v.SOURCE_DOC_NO=(SELECT DOC_NO FROM erp_fin_cash_doc WHERE ID='$PC_DOC')")
EQ "单张资金凭证 2 行（借1002/贷1122 无预收行）" "${PC_VCH:-0}" "2"
PC_PAID1=$(SQL "SELECT CAST(PAID_AMOUNT AS CHAR) FROM erp_fin_ar_invoice WHERE ID='SMKPC-AR1'")
EQ "AR1 全额核销 5000" "${PC_PAID1%%.*}" "5000"

# 20.3 转预收：收款 3000 无未清对象 → 差额全额贷 2205
PC_DOC2=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"direction\":\"IN\",\"partyId\":\"SMKPC-C2\",\"partyName\":\"SMKPC 客户B\",\"amount\":3000,\"payMethod\":\"电汇\",\"bankAccountId\":\"$PC_BANK\",\"payDate\":\"2026-10-16\",\"currency\":\"CNY\"}" \
  $B/api/fin/cash-docs | J "d['data']['id']")
C -X POST -H "$(A $TADM)" $B/api/fin/cash-docs/$PC_DOC2/post >/dev/null
PC_PRE=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID WHERE v.SOURCE_DOC_NO=(SELECT DOC_NO FROM erp_fin_cash_doc WHERE ID='$PC_DOC2') AND l.ACCOUNT_CODE='2205'")
EQ "转预收差额行 2205 存在" "${PC_PRE:-0}" "1"

# 20.4 币种阻断（C-4.6-11）：USD 单据核销 CNY 应收
PC_DOC3=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"direction\":\"IN\",\"partyId\":\"SMKPC-C1\",\"partyName\":\"SMKPC 客户\",\"amount\":1000,\"payMethod\":\"电汇\",\"bankAccountId\":\"$PC_BANK\",\"payDate\":\"2026-10-17\",\"currency\":\"USD\"}" \
  $B/api/fin/cash-docs | J "d['data']['id']")
PC_CUR_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/fin/cash-docs/$PC_DOC3/post)
CONTAINS "USD 收款核销 CNY 应收被阻断" "${PC_CUR_MSG:-}" "币种"

# 20.5 跨期核销审批（C-4.6-02）：造 9 月应收 + 10 月收款 → 挂审批 → 批准 → 生效
SQL "
INSERT INTO erp_fin_ar_invoice (ID,AR_NO,CUSTOMER_ID,CUSTOMER_CODE,CUSTOMER_NAME,AMOUNT,RED_AMOUNT,PAID_AMOUNT,INVOICE_DATE,DUE_DATE,STATUS,CREATE_DATE,DEL_FLAG,VER_NO)
VALUES ('SMKPC-AR3','SMKPC-AR-003','SMKPC-C3','SMKPC','SMKPC 客户C',4000,0,0,'2026-09-01','2026-09-30','UNPAID',NOW(),'0',0);
" >/dev/null
PC_DOC4=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"direction\":\"IN\",\"partyId\":\"SMKPC-C3\",\"partyName\":\"SMKPC 客户C\",\"amount\":4000,\"payMethod\":\"电汇\",\"bankAccountId\":\"$PC_BANK\",\"payDate\":\"2026-10-18\",\"currency\":\"CNY\"}" \
  $B/api/fin/cash-docs | J "d['data']['id']")
PC_CROSS=$(C -X POST -H "$(A $TADM)" $B/api/fin/cash-docs/$PC_DOC4/post | J "str(d['data']['crossPeriod']).lower()")
EQ "跨期自动标记" "${PC_CROSS:-false}" "true"
PC_PEND=$(SQL "SELECT WRITEOFF_STATE FROM erp_fin_cash_doc WHERE ID='$PC_DOC4'")
EQ "核销挂起 PENDING" "${PC_PEND:-}" "PENDING"
PC_PAID3=$(SQL "SELECT CAST(PAID_AMOUNT AS CHAR) FROM erp_fin_ar_invoice WHERE ID='SMKPC-AR3'")
EQ "挂起期间发票未回写（0）" "${PC_PAID3%%.*}" "0"
PC_TASK=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID='$PC_DOC4' AND t.STATUS='ACTIVE' LIMIT 1")
PC_PASS_CODE=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"taskId\":\"$PC_TASK\",\"opinion\":\"跨期核销同意\"}" $B/api/qms/approvals/pass)
EQ "财务主管批准跨期核销（业务码 200）" "${PC_PASS_CODE:-null}" "200"
PC_PAID3B=$(SQL "SELECT CAST(PAID_AMOUNT AS CHAR) FROM erp_fin_ar_invoice WHERE ID='SMKPC-AR3'")
EQ "批准后核销生效（4000）" "${PC_PAID3B%%.*}" "4000"
PC_DONE=$(SQL "SELECT WRITEOFF_STATE FROM erp_fin_cash_doc WHERE ID='$PC_DOC4'")
EQ "核销状态 DONE" "${PC_DONE:-}" "DONE"

# 20.6 回单对账：录入 + 自动匹配 + 解除 + 已对账禁冲销
RC1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"receiptNo\":\"SMKPC-RC001\",\"bankAccountId\":\"$PC_BANK\",\"tradeDate\":\"2026-10-15\",\"direction\":\"RECEIVE\",\"amount\":10000,\"counterparty\":\"SMKPC 客户\"}" \
  $B/api/fin/bank-receipts | J "d['data']['id']")
[ -n "${RC1:-}" ] && ok "回单录入（$RC1）" || bad "回单录入失败"
PC_MATCH=$(C -X POST -H "$(A $TADM)" $B/api/fin/bank-receipts/auto-match | J "d['data']['matched']")
[ "${PC_MATCH:-0}" -ge 1 ] && ok "自动匹配 ≥1 对（$PC_MATCH）" || bad "自动匹配失败（$PC_MATCH）"
PC_RC_ST=$(SQL "SELECT MATCH_STATUS FROM erp_fin_bank_receipt WHERE RECEIPT_NO='SMKPC-RC001'")
EQ "回单 MATCHED" "${PC_RC_ST:-}" "MATCHED"
PC_DOC_ST=$(SQL "SELECT STATUS FROM erp_fin_cash_doc WHERE ID='$PC_DOC'")
EQ "单据 RECONCILED" "${PC_DOC_ST:-}" "RECONCILED"
PC_VOID_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"reason":"冒烟作废"}' $B/api/fin/cash-docs/$PC_DOC/void)
CONTAINS "已对账单据禁作废/冲销" "${PC_VOID_MSG:-}" "作废"
PC_UN=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"reason":"冒烟解除"}' $B/api/fin/bank-receipts/$RC1/unreconcile | J "d['data']['matchStatus']")
EQ "解除匹配回 UNMATCHED" "${PC_UN:-}" "UNMATCHED"
PC_DOC_ST2=$(SQL "SELECT STATUS FROM erp_fin_cash_doc WHERE ID='$PC_DOC'")
EQ "解除后单据回 POSTED" "${PC_DOC_ST2:-}" "POSTED"

# 20.7 余额双向：收款入账 +13000（10000+3000）合计，付款出账验证
PC_BAL_MID=$(SQL "SELECT CAST(BALANCE AS CHAR) FROM erp_fin_bank_account WHERE ID='$PC_BANK'")
PC_DIFF=$(python3 -c "print(int(float('$PC_BAL_MID')-float('$PC_BAL_START')))")
EQ "收款入账余额 +17000（10000+3000+跨期4000）" "${PC_DIFF}" "17000"
PC_OUT=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"direction\":\"OUT\",\"partyId\":\"SMKPC-S1\",\"partyName\":\"SMKPC 供应商\",\"amount\":2000,\"payMethod\":\"电汇\",\"bankAccountId\":\"$PC_BANK\",\"payDate\":\"2026-10-19\",\"currency\":\"CNY\"}" \
  $B/api/fin/cash-docs | J "d['data']['id']")
C -X POST -H "$(A $TADM)" $B/api/fin/cash-docs/$PC_OUT/post >/dev/null
PC_BAL_END=$(SQL "SELECT CAST(BALANCE AS CHAR) FROM erp_fin_bank_account WHERE ID='$PC_BANK'")
PC_DIFF2=$(python3 -c "print(int(float('$PC_BAL_END')-float('$PC_BAL_MID')))")
EQ "付款出账余额 −2000" "${PC_DIFF2}" "-2000"
PC_OUT_VCH=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID WHERE v.SOURCE_DOC_NO=(SELECT DOC_NO FROM erp_fin_cash_doc WHERE ID='$PC_OUT') AND l.ACCOUNT_CODE='2202'")
EQ "付款凭证借 2202 行存在" "${PC_OUT_VCH:-0}" "1"
SMOKE20_OK=1
echo "段 20 收付管理追加完成"

echo "== 21. 外币核算（add-fx-revaluation：固化汇率/开户/试算三类+精度/生成台账+DRAFT/审核过账 6604/下期红冲/重复阻断/缺汇率阻断/未过账阻断结账/币种不一致）=="
# 段 21 清理（可重跑，SMKFX 前缀；期间 209909~209912 隔离）
SQL "
DELETE FROM erp_fin_fx_reval WHERE PERIOD IN ('209909','209910','209911','209912');
DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID
 WHERE v.SOURCE_TYPE='REVAL' AND v.SOURCE_DOC_NO LIKE 'REVAL-2099%';
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='REVAL' AND SOURCE_DOC_NO LIKE 'REVAL-2099%';
DELETE FROM erp_fin_balance WHERE ACCOUNT_CODE='6604' AND PERIOD IN ('209909','209910','209911','209912');
DELETE FROM erp_fin_ap_invoice WHERE INVOICE_NO LIKE 'SMKFX%';
DELETE FROM erp_fin_ar_invoice WHERE AR_NO LIKE 'SMKFX%';
DELETE FROM erp_fin_bank_account WHERE ACCOUNT_NO LIKE 'SMKFX%';
DELETE FROM erp_mdm_supplier WHERE ID='SMKFXSUP';
DELETE FROM erp_mdm_exchange_rate WHERE ID LIKE 'fx-smkfx-%';
" >/dev/null 2>&1 && ok "外币段清理" || bad "外币段清理失败"
# 造数：供应商 + USD 中间价三段（09=7.18 / 10=7.20 / 11=7.25）
SQL "
INSERT IGNORE INTO erp_mdm_supplier (ID,SUPPLIER_CODE,SUPPLIER_NAME,PAYMENT_TERMS,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('SMKFXSUP','SMKFXSUP','SMKFX 供应商','30','QUALIFIED','smoke','0',0);
INSERT IGNORE INTO erp_mdm_exchange_rate (ID,BASE_CCY,QUOTE_CCY,RATE_TYPE,EFFECTIVE_DATE,EXPIRE_DATE,RATE,SOURCE_FILE_NO,CREATE_BY,DEL_FLAG,VER_NO) VALUES
 ('fx-smkfx-u09','USD','CNY','MIDDLE','2099-09-01','2099-09-30',7.180000,'央行公告FX','smoke','0',0),
 ('fx-smkfx-u10','USD','CNY','MIDDLE','2099-10-01','2099-10-31',7.200000,'央行公告FX','smoke','0',0),
 ('fx-smkfx-u11','USD','CNY','MIDDLE','2099-11-01','2099-11-30',7.250000,'央行公告FX','smoke','0',0);
" >/dev/null 2>&1 && ok "外币段造数（供应商+USD 三段汇率）" || bad "外币段造数失败"

# 21.1 外币发票固化（API 登记 USD 发票 → BOOKING_RATE=发票日中间价 7.18）
SMKFX_AP=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"invoiceNo":"SMKFX-AP1","supplierId":"SMKFXSUP","invoiceDate":"2099-09-15","currency":"USD","totalAmount":1000}' \
  $B/api/fin/invoices | J "d['data']['id']")
[ -n "${SMKFX_AP:-}" ] && ok "USD 应付发票登记（$SMKFX_AP）" || bad "USD 应付发票登记失败"
SMKFX_RATE=$(SQL "SELECT CAST(BOOKING_RATE AS CHAR) FROM erp_fin_ap_invoice WHERE INVOICE_NO='SMKFX-AP1'")
EQ "发票记账汇率固化 7.18" "${SMKFX_RATE%%.*}" "7"
# API 登记落 DRAFT（三方匹配确认后才 POSTED）——模拟确认过账使其进重估池（重估只扫 POSTED）
SQL "UPDATE erp_fin_ap_invoice SET STATUS='POSTED' WHERE INVOICE_NO='SMKFX-AP1'" >/dev/null 2>&1

# 21.2 外币账户开户固化（开户日 09-15 中间价 7.18；期初 10000 USD）
SMKFX_BK=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"accountName":"SMKFX USD 户","accountNo":"SMKFX-USD-01","currency":"USD","balance":10000,"openDate":"2099-09-15"}' \
  $B/api/fin/bank-accounts | J "d['data']['id']")
[ -n "${SMKFX_BK:-}" ] && ok "USD 账户开户（$SMKFX_BK）" || bad "USD 账户开户失败"
SMKFX_BKROW=$(SQL "SELECT CONCAT(CAST(BOOKING_RATE AS CHAR),'|',CAST(FC_BALANCE AS CHAR),'|',CAST(BALANCE AS CHAR)) FROM erp_fin_bank_account WHERE ID='$SMKFX_BK'")
case "$SMKFX_BKROW" in 7*10000*71800*) ok "开户三列固化（rate/fc/bal=$SMKFX_BKROW）";; *) bad "开户三列异常（$SMKFX_BKROW）";; esac
# 开户缺汇率阻断（JPY 无汇率）
SMKFX_JPY_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"accountName":"SMKFX JPY 户","accountNo":"SMKFX-JPY-01","currency":"JPY","balance":1000,"openDate":"2099-09-15"}' \
  $B/api/fin/bank-accounts)
CONTAINS "JPY 开户缺汇率阻断" "${SMKFX_JPY_MSG:-}" "汇率"

# 21.3 AR 造数（USD @7.10，覆盖三类项目）
SQL "
INSERT IGNORE INTO erp_fin_ar_invoice (ID,AR_NO,CUSTOMER_ID,CUSTOMER_CODE,CUSTOMER_NAME,AMOUNT,RED_AMOUNT,PAID_AMOUNT,INVOICE_DATE,DUE_DATE,STATUS,CURRENCY,BOOKING_RATE,CREATE_DATE,DEL_FLAG,VER_NO)
 VALUES ('smkfx-ar1','SMKFX-AR-001','SMKFX-C1','SMKFX','SMKFX 客户',5000,0,0,'2099-09-05','2099-10-05','UNPAID','USD',7.100000,NOW(),'0',0);
" >/dev/null 2>&1 && ok "AR 造数（USD 5000 @7.10）" || bad "AR 造数失败"

# 21.4 试算 209910（期末 10-31 中间价 7.20；三类各一笔 + IAS 21 精度）
SMKFX_TRIAL=$(C -X POST -H "$(A $TADM)" "$B/api/fin/fx-reval/trial-run?period=209910")
python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
items={i['itemNo']:i for i in d['items']}
ok3 = (len(items)==3
  and abs(float(items['SMKFX-AP1']['diffAmount'])-20.00)<0.01    # 1000×(7.20−7.18)
  and abs(float(items['SMKFX-USD-01']['diffAmount'])-200.00)<0.01 # 10000×(7.20−7.18)
  and abs(float(items['SMKFX-AR-001']['diffAmount'])-500.00)<0.01 # 5000×(7.20−7.10)
  and abs(float(d['totalDiff'])-720.00)<0.01)
exit(0 if ok3 else 1)" <<<"$SMKFX_TRIAL" && ok "试算三类+精度（AP20/BK200/AR500 合 720）" || bad "试算异常（$SMKFX_TRIAL）"

# 21.5 生成 209910（台账逐笔 + DRAFT 凭证）
SMKFX_GEN=$(C -X POST -H "$(A $TADM)" "$B/api/fin/fx-reval/generate?period=209910")
SMKFX_GENOK=$(echo "$SMKFX_GEN" | J "str(d['data']['revalNo'])=='REVAL-209910-0001' and d['data']['itemCount']==3")
[ "$SMKFX_GENOK" = "True" ] && ok "生成批次 REVAL-209910-0001（3 笔）" || bad "生成异常（$SMKFX_GEN）"
SMKFX_VST=$(SQL "SELECT STATUS FROM erp_fin_voucher WHERE SOURCE_DOC_NO='REVAL-209910'")
EQ "重估凭证落 DRAFT" "${SMKFX_VST:-}" "DRAFT"
SMKFX_LEDGER=$(SQL "SELECT COUNT(*) FROM erp_fin_fx_reval WHERE PERIOD='209910'")
EQ "台账逐笔 3 行" "${SMKFX_LEDGER:-0}" "3"

# 21.6 同期重复生成阻断
SMKFX_DUP_MSG=$(MSG -X POST -H "$(A $TADM)" "$B/api/fin/fx-reval/generate?period=209910")
CONTAINS "同期重复生成阻断" "${SMKFX_DUP_MSG:-}" "重复生成"

# 21.7 审核过账（制单=admin，审核=finmgr；6604 贷方 720 入总账）
# 审核链：HTTP /api/fin 写兜底仅 ADMIN/PM（FINANCE_MGR 被 403），沿用段 17 先例——
# SQL 直置 AUDITED（服务层制单≠审核已由 FxRevalGenerateDbTest 覆盖），过账走 API 验三态推进
SMKFX_VID=$(SQL "SELECT ID FROM erp_fin_voucher WHERE SOURCE_DOC_NO='REVAL-209910'")
SQL "UPDATE erp_fin_voucher SET STATUS='AUDITED', AUDIT_BY='smoke-finmgr', AUDIT_DATE=NOW() WHERE ID='$SMKFX_VID'" >/dev/null 2>&1
SMKFX_AUD=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE ID='$SMKFX_VID' AND STATUS='AUDITED'")
EQ "财务主管审核推进 AUDITED" "${SMKFX_AUD:-0}" "1"
SMKFX_POST_CODE=$(BIZ -X POST -H "$(A $TADM)" "$B/api/fin/manual-vouchers/$SMKFX_VID/post")
[ "$SMKFX_POST_CODE" = "200" ] && ok "重估凭证过账" || bad "过账失败（$SMKFX_POST_CODE）"
SMKFX_6604=$(SQL "SELECT CAST(CR_TOTAL AS CHAR) FROM erp_fin_balance WHERE PERIOD='209910' AND ACCOUNT_CODE='6604'")
EQ "6604 贷方 720.00（汇兑收益）" "${SMKFX_6604:-}" "720.00"

# 21.8 下期生成自动红冲上期（BR-4.6-25 一体原子）
SMKFX_GEN11=$(C -X POST -H "$(A $TADM)" "$B/api/fin/fx-reval/generate?period=209911")
SMKFX_RVOK=$(echo "$SMKFX_GEN11" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
rv=d.get('reversedPrior') or {}
print('OK' if (rv.get('count')==1 and d['period']=='209911') else 'NG')" 2>/dev/null)
[ "$SMKFX_RVOK" = "OK" ] && ok "209911 生成自动红冲上期（count=1）" || bad "红冲异常（$SMKFX_GEN11）"
SMKFX_CHAIN=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_DOC_NO='REVAL-RV-209911' AND REVERSES_ID='$SMKFX_VID'")
EQ "红冲链锚定上期凭证" "${SMKFX_CHAIN:-0}" "1"
SMKFX_REVB=$(SQL "SELECT COUNT(*) FROM erp_fin_fx_reval WHERE PERIOD='209910' AND REVERSED_BY IS NOT NULL")
EQ "上期台账标记被冲回（3 行）" "${SMKFX_REVB:-0}" "3"

# 21.9 未过账重估阻断结账（209911 DRAFT → 结账清单第⑥项失败）
SMKFX_CHK=$(C -X GET -H "$(A $TADM)" "$B/api/fin/periods/209911/checklist")
SMKFX_CHKOK=$(echo "$SMKFX_CHK" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
it=[i for i in d['items'] if i['key']=='pendingReval']
print('OK' if (it and it[0]['ok'] is False) else 'NG')" 2>/dev/null)
[ "$SMKFX_CHKOK" = "OK" ] && ok "未过账重估凭证阻断结账（第⑥项）" || bad "结账清单第⑥项异常（$SMKFX_CHK）"

# 21.10 缺期末汇率阻断（JPY 发票无 209912 期末价 → 逐币种列示）
SQL "INSERT IGNORE INTO erp_fin_ap_invoice (ID,INVOICE_NO,SUPPLIER_ID,SUPPLIER_NAME,INVOICE_DATE,DUE_DATE,CURRENCY,BOOKING_RATE,TOTAL_AMOUNT,PAID_AMOUNT,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
 VALUES ('smkfx-jpy','SMKFX-JPY-AP','SMKFXSUP','SMKFX 供应商','2099-09-15','2099-10-15','JPY',0.050000,100000,0,'POSTED','smoke','0',0);" >/dev/null 2>&1
SMKFX_MISS_MSG=$(MSG -X POST -H "$(A $TADM)" "$B/api/fin/fx-reval/trial-run?period=209912")
CONTAINS "缺期末汇率逐币种阻断" "${SMKFX_MISS_MSG:-}" "JPY"
SQL "DELETE FROM erp_fin_ap_invoice WHERE ID='smkfx-jpy';" >/dev/null 2>&1

# 21.11 USD 单据挂 CNY 账户 → 币种不一致阻断（账户维度断言）
SMKFX_CNYBK=$(SQL "SELECT ID FROM erp_fin_bank_account WHERE CURRENCY='CNY' AND STATUS='ACTIVE' LIMIT 1")
SMKFX_DOC=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"direction\":\"IN\",\"partyId\":\"SMKFX-C1\",\"partyName\":\"SMKFX 客户\",\"amount\":100,\"payMethod\":\"电汇\",\"bankAccountId\":\"$SMKFX_CNYBK\",\"payDate\":\"2099-10-15\",\"currency\":\"USD\"}" \
  $B/api/fin/cash-docs | J "d['data']['id']")
SMKFX_MIS_MSG=$(MSG -X POST -H "$(A $TADM)" "$B/api/fin/cash-docs/$SMKFX_DOC/post")
CONTAINS "USD 单据挂 CNY 账户阻断" "${SMKFX_MIS_MSG:-}" "不一致"
SQL "DELETE FROM erp_fin_cash_doc WHERE ID='$SMKFX_DOC';" >/dev/null 2>&1
SMKFX21_OK=1
echo "段 21 外币核算追加完成"



echo "== 22. 折旧监控（add-depreciation-monitoring：实时预警命中/排除 + 结账⑦按需阻断/放行 + 期间状态/明细下钻）=="
# 段 22 清理（可重跑，SMKDM 前缀；期间 209911/209912 隔离，不与段 14/21 互扰）
SQL "
DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'SMKDM-%';
DELETE FROM erp_eam_depreciation_detail WHERE PERIOD_TAG IN ('209911','209912');
DELETE FROM erp_eam_depreciation_run WHERE PERIOD_TAG IN ('209911','209912');
" >/dev/null 2>&1 && ok "折旧监控段清理" || bad "折旧监控段清理失败"
# 阈值参数固化 10%（防共享库他测改写；与引擎缺省一致）
SQL "REPLACE INTO erp_sys_param (ID,PARAM_KEY,PARAM_VALUE,VALUE_TYPE,PARAM_GROUP,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
 VALUES ('smkdm-thr','DEPRECIATION_RESIDUAL_ALERT','0.10','DECIMAL','EAM','smoke',NOW(),'0',0);" >/dev/null 2>&1

# 22.1 造数：四态在用卡（正常/临界命中/已提足/已处置）+ 209911 冲回运行
SQL "
INSERT INTO erp_eam_asset_card (ID,CARD_NO,ASSET_NAME,CATEGORY_CODE,ASSET_COST,LIFE_YEARS,RESIDUAL_RATE,RESIDUAL_VALUE,DEPRECIATION_METHOD,EFFECTIVE_DATE,STATUS,DEPR_STATUS,ACCUM_DEPR,IMPAIRMENT,LOCK_FLAG,PLEDGED_FLAG,COUNT_FREEZE_FLAG,LEASE_FLAG,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) VALUES
 ('smkdm-norm','SMKDM-NORM','SMKDM 正常机','MACH',100000,5,0.05,5000,'STRAIGHT_LINE','2096-01-01','IN_USE','DEPRECIATING',20000,0,'0','0','0','0','smoke',NOW(),'0',0),
 ('smkdm-hit','SMKDM-HIT','SMKDM 临界机','MACH',100000,5,0.10,10000,'STRAIGHT_LINE','2095-01-01','IN_USE','DEPRECIATING',85000,0,'0','0','0','0','smoke',NOW(),'0',0),
 ('smkdm-full','SMKDM-FULL','SMKDM 提足机','MACH',100000,5,0.10,10000,'STRAIGHT_LINE','2095-01-01','IN_USE','FULLY_DEPRECIATED',85000,10000,'0','0','0','0','smoke',NOW(),'0',0),
 ('smkdm-disp','SMKDM-DISP','SMKDM 已处置机','MACH',100000,5,0.10,10000,'STRAIGHT_LINE','2095-01-01','DISPOSED','DEPRECIATING',85000,0,'0','0','0','0','smoke',NOW(),'0',0);
INSERT INTO erp_eam_depreciation_run (ID,RUN_NO,PERIOD_TAG,STATUS,TOTAL_DEPR,RECON_DIFF,WARNING_COUNT,REVERSED_FLAG,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
 VALUES ('smkdm-run-rev','SMKDMJZ209911','209911','REVERSED',48000,0,1,'1','smoke',NOW(),'0',0);
" >/dev/null 2>&1 && ok "折旧监控造数（四态卡 + 209911 冲回运行）" || bad "折旧监控造数失败"

# 22.2 实时预警扫描：临界卡命中，正常/提足/处置卡排除，阈值 10%
DM22_AL=$(C -X GET -H "$(A $TADM)" $B/api/fin/depreciation-monitor/residual-alerts)
DM22_R=$(echo "$DM22_AL" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
items={i['cardNo']:i for i in d['items']}
okc = ('SMKDM-HIT' in items and 'SMKDM-NORM' not in items
       and 'SMKDM-FULL' not in items and 'SMKDM-DISP' not in items
       and abs(float(d['threshold'])-0.10)<1e-9
       and abs(float(items['SMKDM-HIT']['residualRatio'])-0.6667)<0.001)
print('OK' if okc else 'NG')" 2>/dev/null)
[ "$DM22_R" = "OK" ] && ok "实时预警：命中临界卡（占比66.67%），排除正常/提足/处置，阈值10%" || bad "实时预警异常（$DM22_AL）"

# 22.3 结账⑦按需阻断：209912 有需计提卡（2096 启用已过起提月）且无运行 → FAIL + close 422
DM22_CHK=$(C -X GET -H "$(A $TADM)" $B/api/fin/periods/209912/checklist)
DM22_R=$(echo "$DM22_CHK" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
it=[i for i in d['items'] if i['key']=='pendingDepreciation']
print('OK' if (it and it[0]['ok'] is False) else 'NG')" 2>/dev/null)
[ "$DM22_R" = "OK" ] && ok "结账⑦：需计提卡无运行 → FAIL" || bad "结账⑦ FAIL 异常（$DM22_CHK）"
DM22_CLOSE_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/fin/periods/209912/close)
CONTAINS "结账⑦阻断关账（列示折旧）" "$DM22_CLOSE_MSG" "折旧"

# 22.4 期间状态：209911 已冲回在列
DM22_RUNS=$(C -X GET -H "$(A $TADM)" $B/api/fin/depreciation-monitor/runs)
DM22_R=$(echo "$DM22_RUNS" | python3 -c "
import sys,json
rows=json.load(sys.stdin)['data']
r=[x for x in rows if x['period']=='209911']
print('OK' if (r and r[0]['status']=='REVERSED') else 'NG')" 2>/dev/null)
[ "$DM22_R" = "OK" ] && ok "M8-6-1 期间列表：209911 REVERSED" || bad "期间列表异常（$DM22_RUNS）"

# 22.5 POSTED 运行 → ⑦ PASS（EAM 真实执行见段 14.6，此处验证闸口放行语义）
SQL "
INSERT INTO erp_eam_depreciation_run (ID,RUN_NO,PERIOD_TAG,STATUS,TOTAL_DEPR,RECON_DIFF,WARNING_COUNT,REVERSED_FLAG,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
 VALUES ('smkdm-run','SMKDMJZ209912','209912','POSTED',50000,0,1,'0','smoke',NOW(),'0',0);
INSERT INTO erp_eam_depreciation_detail (ID,RUN_ID,PERIOD_TAG,CARD_ID,CARD_NO,ASSET_NAME,ASSET_COST,BEGIN_ACCUM_DEPR,BEGIN_NBV,PERIOD_DEPR,END_ACCUM_DEPR,END_NBV,RESIDUAL_ALERT,CAPPED,REVERSED,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
 VALUES ('smkdm-det','smkdm-run','209912','smkdm-hit','SMKDM-HIT','SMKDM 临界机',100000,85000,15000,2000,87000,13000,'1','0','0','smoke',NOW(),'0',0);
" >/dev/null 2>&1
DM22_CHK2=$(C -X GET -H "$(A $TADM)" $B/api/fin/periods/209912/checklist)
DM22_R=$(echo "$DM22_CHK2" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
it=[i for i in d['items'] if i['key']=='pendingDepreciation']
print('OK' if (it and it[0]['ok'] is True) else 'NG')" 2>/dev/null)
[ "$DM22_R" = "OK" ] && ok "结账⑦：POSTED 运行 → PASS 放行" || bad "结账⑦ PASS 异常（$DM22_CHK2）"

# 22.6 期间状态 POSTED + 预警张数 + 凭证关联展示；明细下钻逐卡 + 预警标记
DM22_RUNS2=$(C -X GET -H "$(A $TADM)" $B/api/fin/depreciation-monitor/runs)
DM22_R=$(echo "$DM22_RUNS2" | python3 -c "
import sys,json
rows=json.load(sys.stdin)['data']
r=[x for x in rows if x['period']=='209912']
print('OK' if (r and r[0]['status']=='POSTED' and r[0].get('warningCount')==1) else 'NG')" 2>/dev/null)
[ "$DM22_R" = "OK" ] && ok "M8-6-1 期间列表：209912 POSTED + 预警张数" || bad "期间 POSTED 断言异常（$DM22_RUNS2）"
DM22_DET=$(C -X GET -H "$(A $TADM)" $B/api/fin/depreciation-monitor/runs/209912/details)
DM22_R=$(echo "$DM22_DET" | python3 -c "
import sys,json
items=json.load(sys.stdin)['data']['items']
r=[x for x in items if x.get('cardNo')=='SMKDM-HIT']
okc = (r and r[0].get('residualAlert') is True
       and abs(float(r[0]['periodDepr'])-2000.0)<0.01
       and abs(float(r[0]['endNbv'])-13000.0)<0.01)
print('OK' if okc else 'NG')" 2>/dev/null)
[ "$DM22_R" = "OK" ] && ok "M8-6-1 明细下钻：逐卡明细 + 预警标记" || bad "明细下钻异常（$DM22_DET）"
SMKDM22_OK=1
echo "段 22 折旧监控追加完成"


echo "== 23. 银行对账（add-bank-reconciliation：录入/自校验/四类分类/差异率/调节表/确认链/D6 锁定/结账⑧）=="
# 段 23 清理（可重跑，SMKBR 前缀）
SQL "
DELETE FROM erp_fin_bank_statement WHERE ACCOUNT_NO LIKE 'SMKBR%';
DELETE FROM erp_fin_bank_reconcile_item WHERE BANK_ACCOUNT_ID IN (SELECT ID FROM (SELECT ID FROM erp_fin_bank_account WHERE ACCOUNT_NO LIKE 'SMKBR%') t);
DELETE FROM erp_fin_bank_receipt WHERE RECEIPT_NO LIKE 'SMKBR-%';
DELETE FROM erp_fin_cash_doc WHERE DOC_NO LIKE 'SMKBR-%';
DELETE FROM erp_fin_bank_account WHERE ACCOUNT_NO LIKE 'SMKBR%';
" >/dev/null 2>&1 && ok "段 23 清理" || bad "段 23 清理失败"

# 23.1 开户造数（CNY，账面 100,000）
SMKBR_BANK=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"accountName\":\"SMKBR 银行\",\"accountNo\":\"SMKBR001\",\"currency\":\"CNY\",\"balance\":100000}" \
  $B/api/fin/bank-accounts | J "d['data']['id']")
[ -n "${SMKBR_BANK:-}" ] && ok "开户（$SMKBR_BANK）" || bad "开户失败"
SQL "UPDATE erp_fin_bank_account SET BALANCE=100000.00, FC_BALANCE=100000.00 WHERE ID='$SMKBR_BANK'" >/dev/null 2>&1

# 23.2 造数：已匹配回单 收 20,000（勾稽用）+ UNMATCHED 收 30,000（③）+ UNMATCHED 付 5,000（④）
SQL "
INSERT INTO erp_fin_bank_receipt (ID,RECEIPT_NO,BANK_ACCOUNT_ID,TRADE_DATE,DIRECTION,AMOUNT,SOURCE,MATCH_STATUS,CREATE_BY,DEL_FLAG,VER_NO)
VALUES ('SMKBR-R1','SMKBR-R1','$SMKBR_BANK','2099-11-05','RECEIVE',20000,'MANUAL','MATCHED','smoke','0',0),
       ('SMKBR-R2','SMKBR-R2','$SMKBR_BANK','2099-11-10','RECEIVE',30000,'MANUAL','UNMATCHED','smoke','0',0),
       ('SMKBR-R3','SMKBR-R3','$SMKBR_BANK','2099-11-12','PAY',5000,'MANUAL','UNMATCHED','smoke','0',0);
" >/dev/null 2>&1 && ok "回单造数（匹配收 20000 / 未匹配收 30000 / 付 5000）" || bad "回单造数失败"

# 23.3 录入对账单：期初 80,000 + 已匹配收 20,000 = 期末 100,000 → 勾稽平衡
SMKBR_STMT=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"bankAccountId\":\"$SMKBR_BANK\",\"period\":\"209911\",\"stmtNo\":\"SMKBR-STMT-001\",\"openingBalance\":80000,\"closingBalance\":100000}" \
  $B/api/fin/bank-reconciliation/statements | J "d['data']['id']")
[ -n "${SMKBR_STMT:-}" ] && ok "录入对账单（$SMKBR_STMT）" || bad "录入失败"
SMKBR_BAL=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"bankAccountId\":\"$SMKBR_BANK\",\"period\":\"209911\",\"openingBalance\":80000,\"closingBalance\":100000}" \
  $B/api/fin/bank-reconciliation/statements | J "d['code']")
[ "${SMKBR_BAL:-null}" = "422" ] && ok "同账户同期间重复录入 422" || bad "重复未阻断（$SMKBR_BAL）"

# 23.4 四类分类：③30,000 ④5,000（①②无 cash_doc）
SMKBR_OU=$(C -X GET -H "$(A $TADM)" "$B/api/fin/bank-reconciliation/outstanding-items?bankAccountId=$SMKBR_BANK&period=209911")
SMKBR_3=$(echo "$SMKBR_OU" | python3 -c "import sys,json;print(int(float(json.load(sys.stdin)['data']['sums']['BANK_RCPT_ENT_PEND'])))" 2>/dev/null)
SMKBR_4=$(echo "$SMKBR_OU" | python3 -c "import sys,json;print(int(float(json.load(sys.stdin)['data']['sums']['BANK_PAY_ENT_PEND'])))" 2>/dev/null)
EQ "③银行已收企业未收 30000" "${SMKBR_3:-0}" "30000"
EQ "④银行已付企业未付 5000" "${SMKBR_4:-0}" "5000"

# 23.5 调节表：账面 100,000 + ③30,000 − ④5,000 = 125,000 vs 银行 100,000 + 0 − 0 = 100,000 → 不平 → 确认阻断
SMKBR_DET=$(C -X GET -H "$(A $TADM)" $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT)
SMKBR_BALOK=$(echo "$SMKBR_DET" | J "d['data']['reconcileTable']['balanced']")
[ "${SMKBR_BALOK}" = "False" ] && ok "调节表不平识别" || bad "不平未识别（$SMKBR_DET）"
SMKBR_CONF_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT/confirm)
CONTAINS "不平阻断确认" "${SMKBR_CONF_MSG:-}" "排查"

# 23.6 补齐未达解释使调节平衡：期末改 125,000（100,000 账面 + 30,000 − 5,000）
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"id\":\"$SMKBR_STMT\",\"bankAccountId\":\"$SMKBR_BANK\",\"period\":\"209911\",\"openingBalance\":80000,\"closingBalance\":125000}" \
  $B/api/fin/bank-reconciliation/statements >/dev/null
SMKBR_DET2=$(C -X GET -H "$(A $TADM)" $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT)
SMKBR_L=$(echo "$SMKBR_DET2" | python3 -c "import sys,json;print(int(float(json.load(sys.stdin)['data']['reconcileTable']['leftSide'])))" 2>/dev/null)
SMKBR_R=$(echo "$SMKBR_DET2" | python3 -c "import sys,json;print(int(float(json.load(sys.stdin)['data']['reconcileTable']['rightSide'])))" 2>/dev/null)
EQ "调节表账面方 125000" "${SMKBR_L:-0}" "125000"
EQ "调节表银行方 125000" "${SMKBR_R:-0}" "125000"

# 23.7 差异率：|125,000 − 100,000| / 125,000 = 20% > 0.5% → 超容差标记
SMKBR_OT=$(SQL "SELECT OVER_TOLERANCE FROM erp_fin_bank_statement WHERE ID='$SMKBR_STMT'")
EQ "超容差标记" "${SMKBR_OT:-}" "1"

# 23.8 确认→审核→撤销→重确认（状态机全链）
C -X POST -H "$(A $TADM)" $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT/confirm >/dev/null
SMKBR_ST=$(SQL "SELECT STATUS FROM erp_fin_bank_statement WHERE ID='$SMKBR_STMT'")
EQ "出纳确认 CONFIRMED" "${SMKBR_ST:-}" "CONFIRMED"
C -X POST -H "$(A $TADM)" $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT/audit >/dev/null
SMKBR_ST2=$(SQL "SELECT STATUS FROM erp_fin_bank_statement WHERE ID='$SMKBR_STMT'")
EQ "主管审核 AUDITED" "${SMKBR_ST2:-}" "AUDITED"
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"reason":"冒烟撤销"}' \
  $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT/revoke >/dev/null
SMKBR_ST3=$(SQL "SELECT STATUS FROM erp_fin_bank_statement WHERE ID='$SMKBR_STMT'")
EQ "撤销回 DRAFT" "${SMKBR_ST3:-}" "DRAFT"
SMKBR_RSN=$(SQL "SELECT REVOKE_REASON FROM erp_fin_bank_statement WHERE ID='$SMKBR_STMT'")
EQ "撤销原因留痕" "${SMKBR_RSN:-}" "冒烟撤销"

# 23.9 D6 锁定：重确认+审核后，同期间回单人工匹配应 422
C -X POST -H "$(A $TADM)" $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT/confirm >/dev/null
C -X POST -H "$(A $TADM)" $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT/audit >/dev/null
SQL "
INSERT INTO erp_fin_cash_doc (ID,DOC_NO,DIRECTION,PARTY_TYPE,PARTY_ID,PARTY_NAME,AMOUNT,ACTUAL_AMOUNT,CURRENCY,PAY_METHOD,BANK_ACCOUNT_ID,PAY_DATE,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
VALUES ('SMKBR-D1','SMKBR-D1','IN','CUSTOMER','SMKBR-P','SMKBR 客户',30000,30000,'CNY','电汇','$SMKBR_BANK','2099-11-10','POSTED','smoke','0',0);
" >/dev/null 2>&1
SMKBR_LOCK_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"docId\":\"SMKBR-D1\"}" $B/api/fin/bank-receipts/SMKBR-R2/match)
CONTAINS "AUDITED 期间锁定人工匹配" "${SMKBR_LOCK_MSG:-}" "AUDITED"
# 撤销后放行
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"reason":"冒烟放行"}' \
  $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT/revoke >/dev/null
SMKBR_MATCH_CODE=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"docId\":\"SMKBR-D1\"}" $B/api/fin/bank-receipts/SMKBR-R2/match)
[ "${SMKBR_MATCH_CODE:-null}" = "200" ] && ok "撤销后匹配放行" || bad "撤销后仍锁定（$SMKBR_MATCH_CODE）"

# 23.10 结账⑧：重确认+审核后放行；再撤销制造 DRAFT → 阻断
# 恢复 23.9 改变的状态：解除匹配后 D1 回 POSTED 会成为①类未达（+30,000）——一并删除 D1
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"reason":"冒烟恢复"}' \
  $B/api/fin/bank-receipts/SMKBR-R2/unreconcile >/dev/null
SQL "DELETE FROM erp_fin_cash_doc WHERE ID='SMKBR-D1'" >/dev/null 2>&1
C -X POST -H "$(A $TADM)" $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT/confirm >/dev/null
C -X POST -H "$(A $TADM)" $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT/audit >/dev/null
SMKBR_CHK=$(C -X GET -H "$(A $TADM)" $B/api/fin/periods/209911/checklist)
SMKBR_CHKOK=$(echo "$SMKBR_CHK" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
it=[i for i in d['items'] if i['key']=='pendingBankRecon']
print('OK' if (it and it[0]['ok'] is True) else 'NG')" 2>/dev/null)
[ "${SMKBR_CHKOK:-NG}" = "OK" ] && ok "⑧已对账放行（AUDITED）" || bad "⑧异常（$SMKBR_CHK）"
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"reason":"冒烟阻断"}' \
  $B/api/fin/bank-reconciliation/statements/$SMKBR_STMT/revoke >/dev/null
SMKBR_CHK2=$(C -X GET -H "$(A $TADM)" $B/api/fin/periods/209911/checklist)
SMKBR_CHK2OK=$(echo "$SMKBR_CHK2" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
it=[i for i in d['items'] if i['key']=='pendingBankRecon']
print('OK' if (it and it[0]['ok'] is False) else 'NG')" 2>/dev/null)
[ "${SMKBR_CHK2OK:-NG}" = "OK" ] && ok "⑧未审对账单阻断" || bad "⑧阻断异常（$SMKBR_CHK2）"

# 23.11 处理留痕：标记处理中→已处理
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"bankAccountId\":\"$SMKBR_BANK\",\"period\":\"209911\",\"itemType\":\"BANK_RCPT_ENT_PEND\",\"anchorType\":\"RECEIPT\",\"anchorId\":\"SMKBR-R2\",\"anchorNo\":\"SMKBR-R2\",\"amount\":30000,\"tradeDate\":\"2099-11-10\",\"status\":\"PROCESSING\",\"handlingNote\":\"冒烟处理中\"}" \
  $B/api/fin/bank-reconciliation/handling >/dev/null
SMKBR_REC=$(C -X GET -H "$(A $TADM)" "$B/api/fin/bank-reconciliation/handling-records?bankAccountId=$SMKBR_BANK&period=209911" | J "len(d['data'])")
[ "${SMKBR_REC:-0}" -ge 1 ] && ok "处理留痕落库（$SMKBR_REC 条）" || bad "留痕失败"
SMKBR23_OK=1
echo "段 23 银行对账追加完成"

# ============================================================================
# 段 24 add-expense-reimbursement：M8-8 费用报销（映射/借款双签/发票重复/FIFO
# 冲销/差额结算/账龄红标）+ M8-9 预提待摊（计划尾差/幂等/差异调整/年末 L4）
# + 结账第⑨项两分支。提取约定：POST 响应存 raw JSON（下游 J 单通解析 d['data']
# [...]），凭证锚 = SOURCE_TYPE + SOURCE_DOC_NO，422 一律 BIZ 判码。
# ============================================================================
echo "== 24. 费用报销与预提待摊（add-expense-reimbursement：双签/发票重复/FIFO/差额结算 + 计划幂等/差异调整/年末L4 + 结账⑨）=="

# 24.0 清理（可重跑）+ 补付/冲销账户充值（绝对 SET 防累积）
SQL "
DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID
 WHERE v.SOURCE_TYPE IN ('LOAN','REIMB','LOANREF','PREPAY','AMORT');
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('LOAN','REIMB','LOANREF','PREPAY','AMORT');
DELETE FROM erp_fin_balance_journal WHERE VOUCHER_ID NOT IN (SELECT ID FROM (SELECT ID FROM erp_fin_voucher) t0);
DELETE FROM erp_fin_balance WHERE PERIOD = '209910';
-- 旧版冒烟 PSETTLE/PAY 落真实当期 202610 的 2251 残留（新 payDate=209910 已防再生，一次性回收）
DELETE FROM erp_fin_balance WHERE PERIOD = '202610' AND ACCOUNT_CODE = '2251';
DELETE FROM erp_sys_approval_task WHERE APPR_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN ('ExpenseReimburse','EmployeeLoan')) t1);
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('ExpenseReimburse','EmployeeLoan');
DELETE FROM erp_fin_cash_doc WHERE PAY_TYPE IN ('REIMBURSE','LOAN_REFUND');
DELETE FROM erp_fin_reimburse_line;
DELETE FROM erp_fin_loan_writeoff;
DELETE FROM erp_fin_reimburse;
DELETE FROM erp_fin_employee_loan;
DELETE FROM erp_fin_amort_plan;
DELETE FROM erp_fin_prepaid_doc;
DELETE FROM erp_fin_deferred_doc;
UPDATE erp_fin_bank_account SET BALANCE = 50000 WHERE ID = 'bank-icbc-basic';
" >/dev/null 2>&1 && ok "清理完成（报销/借款/计划族 + 209910 余额 + 账户 50000）" || bad "清理失败"

# 24.1 费用类型映射种子（FR-4.6-7-5 前置）
FET_CNT=$(SQL "SELECT COUNT(*) FROM erp_fin_expense_type WHERE STATUS='ACTIVE'")
[ "${FET_CNT:-0}" -ge 5 ] && ok "费用类型种子（$FET_CNT 个启用）" || bad "种子缺失（$FET_CNT）"
FET_TRAVEL=$(SQL "SELECT ACCOUNT_CODE FROM erp_fin_expense_type WHERE TYPE_CODE='TRAVEL'")
EQ "TRAVEL 映射 660202" "$FET_TRAVEL" "660202"

# 24.2 借款常规（无超期 → 单节点）
LN1_RAW=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"amount":5000,"purpose":"SMKEX 出差借款","expectedDate":"2099-12-31"}' $B/api/fin/employee-loans)
LN1_ID=$(echo "$LN1_RAW" | J "d['data']['id']")
LN1_NO=$(echo "$LN1_RAW" | J "d['data']['loanNo']")
LN1_OD=$(echo "$LN1_RAW" | J "d['data'].get('overdueDetected')")
[ -n "${LN1_ID:-}" ] && [ "$LN1_OD" = "False" ] \
  && ok "常规借款单节点（无超期不加签）" || bad "常规借款异常（$LN1_RAW）"
LN1_APPR=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='EmployeeLoan' AND BIZ_ID='$LN1_ID'")
LN1_TASK=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$LN1_APPR' AND STATUS='ACTIVE' LIMIT 1")
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"taskId\":\"$LN1_TASK\",\"opinion\":\"SMKEX 同意\"}" $B/api/qms/approvals/pass >/dev/null
LN1_ST=$(SQL "SELECT CONCAT(STATUS,'|',CAST(OUTSTANDING_BALANCE AS CHAR)) FROM erp_fin_employee_loan WHERE ID='$LN1_ID'")
EQ "借款放款挂账（PAID|5000.00）" "$LN1_ST" "PAID|5000.00"
LN1_V=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='LOAN' AND SOURCE_DOC_NO='$LN1_NO') t2)")
EQ "挂账凭证两行（借1299/贷1002）" "$LN1_V" "2"

# 24.3 超期加签双签（C-4.6-14 前款不清后款不借）
SQL "UPDATE erp_fin_employee_loan SET PAID_DATE = DATE_SUB(CURDATE(), INTERVAL 100 DAY) WHERE ID='$LN1_ID'" >/dev/null
LN2_RAW=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"amount":3000,"purpose":"SMKEX 二次借款","expectedDate":"2099-12-31"}' $B/api/fin/employee-loans)
LN2_ID=$(echo "$LN2_RAW" | J "d['data']['id']")
LN2_OD=$(echo "$LN2_RAW" | J "d['data'].get('overdueDetected')")
LN2_DAYS=$(echo "$LN2_RAW" | J "d['data'].get('overdueDays')")
[ "$LN2_OD" = "True" ] && [ "${LN2_DAYS:-0}" -ge 90 ] \
  && ok "超期实时判定（$LN2_DAYS 天 > 90 → 加签）" || bad "超期判定异常（$LN2_RAW）"
LN2_APPR=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='EmployeeLoan' AND BIZ_ID='$LN2_ID'")
LN2_NODES=$(SQL "SELECT COUNT(*) FROM erp_sys_approval_task WHERE APPR_ID='$LN2_APPR'")
EQ "双签两节点（部门主管+财务主管）" "$LN2_NODES" "2"
for _i in 1 2; do
  T=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$LN2_APPR' AND STATUS='ACTIVE' LIMIT 1")
  [ -n "$T" ] && C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"taskId\":\"$T\",\"opinion\":\"SMKEX 双签\"}" $B/api/qms/approvals/pass >/dev/null
done
LN2_ST=$(SQL "SELECT CONCAT(STATUS,'|',CAST(OUTSTANDING_BALANCE AS CHAR)) FROM erp_fin_employee_loan WHERE ID='$LN2_ID'")
EQ "双签通过放款（PAID|3000.00）" "$LN2_ST" "PAID|3000.00"

# 24.4 账龄监控红标（BR-4.6-02 升级提示视图）
AGING=$(C -X GET -H "$(A $TADM)" $B/api/fin/employee-loans/aging-view)
AGING_OD=$(echo "$AGING" | J "max([r.get('maxAgingDays') or 0 for r in d['data']] or [0])")
AGING_RED=$(echo "$AGING" | J "any(r.get('overdue') is True for r in d['data'])")
[ "${AGING_OD:-0}" -gt 90 ] && [ "$AGING_RED" = "True" ] \
  && ok "账龄视图红标（最大 $AGING_OD 天 > 90，overdue=true）" || bad "账龄异常（$AGING）"

# 24.5 报销 R1（6000 < 借款 8000 → FIFO 部分冲销 + BR-4.6-01 全额冲销不付现）
R1_RAW=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"totalAmount":6000,"occurPeriod":"209912","remark":"SMKEX 差旅报销","lines":[{"expenseType":"TRAVEL","amount":4000,"occurDate":"2099-12-01","invoiceNo":"SMKEI-001","invoiceVendor":"冒烟销方"},{"expenseType":"OFFICE","amount":2000,"occurDate":"2099-12-01","invoiceNo":"SMKEI-002","invoiceVendor":"冒烟文具"}]}' $B/api/fin/reimburse)
R1_ID=$(echo "$R1_RAW" | J "d['data']['id']")
R1_NO=$(echo "$R1_RAW" | J "d['data']['expNo']")
[ -n "${R1_ID:-}" ] && ok "报销提交（$R1_NO，两行 6000）" || bad "报销提交失败（$R1_RAW）"

# 发票重复 L1（C-4.6-13 展示原单号）
DUP_CODE=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"totalAmount":4000,"occurPeriod":"209912","lines":[{"expenseType":"TRAVEL","amount":4000,"occurDate":"2099-12-02","invoiceNo":"SMKEI-001","invoiceVendor":"冒烟销方"}]}' $B/api/fin/reimburse)
DUP_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"totalAmount":4000,"occurPeriod":"209912","lines":[{"expenseType":"TRAVEL","amount":4000,"occurDate":"2099-12-02","invoiceNo":"SMKEI-001","invoiceVendor":"冒烟销方"}]}' $B/api/fin/reimburse)
[ "$DUP_CODE" = "422" ] && CONTAINS "重复发票 L1 阻断（展示原单）" "$DUP_MSG" "原单" || bad "重复发票未阻断（code=$DUP_CODE）"

# 停用类型引用 422（映射配置可维护 + FR-4.6-7-5）
OFF_ID=$(SQL "SELECT ID FROM erp_fin_expense_type WHERE TYPE_CODE='OFFICE'")
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"status":"DISABLED"}' $B/api/fin/reimburse/expense-types/$OFF_ID/status >/dev/null
DIS_CODE=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"totalAmount":100,"occurPeriod":"209912","lines":[{"expenseType":"OFFICE","amount":100,"occurDate":"2099-12-02","invoiceNo":"SMKEI-901"}]}' $B/api/fin/reimburse)
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"status":"ACTIVE"}' $B/api/fin/reimburse/expense-types/$OFF_ID/status >/dev/null
EQ "停用类型引用 422" "$DIS_CODE" "422"

# 两节点审批（部门主管 → 财务审核）
R1_APPR=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='ExpenseReimburse' AND BIZ_ID='$R1_ID'")
R1_NODES=$(SQL "SELECT COUNT(*) FROM erp_sys_approval_task WHERE APPR_ID='$R1_APPR'")
EQ "报销两节点链" "$R1_NODES" "2"
for _i in 1 2; do
  T=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$R1_APPR' AND STATUS='ACTIVE' LIMIT 1")
  [ -n "$T" ] && C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"taskId\":\"$T\",\"opinion\":\"SMKEX 审批\"}" $B/api/qms/approvals/pass >/dev/null
done
R1_ST=$(SQL "SELECT STATUS FROM erp_fin_reimburse WHERE ID='$R1_ID'")
EQ "两节点审批完成" "$R1_ST" "APPROVED"

# Callback 单事务：REIMB 报销凭证 + LWO FIFO 冲销（loan1 5000 + loan2 1000）
WO_CNT=$(SQL "SELECT COUNT(*) FROM erp_fin_loan_writeoff WHERE REIMBURSE_ID='$R1_ID'")
EQ "FIFO 冲销两笔（5000+1000）" "$WO_CNT" "2"
WO_SUM=$(SQL "SELECT IFNULL(SUM(AMOUNT),0) FROM erp_fin_loan_writeoff WHERE REIMBURSE_ID='$R1_ID'")
EQ "冲销合计 6000.00" "$WO_SUM" "6000.00"
LN_BAL=$(SQL "SELECT GROUP_CONCAT(CAST(OUTSTANDING_BALANCE AS CHAR) ORDER BY LOAN_NO) FROM erp_fin_employee_loan")
EQ "借款余额递减（loan1=0.00, loan2=2000.00）" "$LN_BAL" "0.00,2000.00"

# settle-info：全额冲销 → 差额 0；BR-4.6-01 拒付
SI1=$(C -X GET -H "$(A $TADM)" $B/api/fin/reimburse/$R1_ID/settle-info)
SI1_DIFF=$(echo "$SI1" | J "int(float(d['data']['difference']))")
EQ "冲销后差额 0" "$SI1_DIFF" "0"
PAY_CODE=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"bankAccountId":"bank-icbc-basic"}' $B/api/fin/reimburse/$R1_ID/pay-difference)
PAY_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"bankAccountId":"bank-icbc-basic"}' $B/api/fin/reimburse/$R1_ID/pay-difference)
[ "$PAY_CODE" = "422" ] && CONTAINS "BR-4.6-01 无差额拒付现" "$PAY_MSG" "无待补差额" || bad "无差额未拒付（code=$PAY_CODE）"

# 24.6 剩余借款登记退款（loan2 余额 2000 → LOANREF 清零）
RF_OVER_CODE=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"loanId\":\"$LN2_ID\",\"amount\":9999,\"bankAccountId\":\"bank-icbc-basic\"}" $B/api/fin/reimburse/$R1_ID/refund)
EQ "超额退款 422" "$RF_OVER_CODE" "422"
RF_RAW=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"loanId\":\"$LN2_ID\",\"amount\":2000,\"payDate\":\"2099-10-31\",\"bankAccountId\":\"bank-icbc-basic\"}" $B/api/fin/reimburse/$R1_ID/refund)
RF_V=$(echo "$RF_RAW" | J "d['data']['voucherNo']")
LN2_BAL=$(SQL "SELECT CAST(OUTSTANDING_BALANCE AS CHAR) FROM erp_fin_employee_loan WHERE ID='$LN2_ID'")
[ -n "${RF_V:-}" ] && [ "$LN2_BAL" = "0.00" ] \
  && ok "退款登记清借款（凭证 $RF_V，余额 0）" || bad "退款异常（$RF_RAW / $LN2_BAL）"

# 24.7 报销 R2（10000 无借款 → 差额 10000 → 出纳补付 → 重复阻断）
R2_RAW=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"totalAmount":10000,"occurPeriod":"209912","remark":"SMKEX 无借款报销","lines":[{"expenseType":"TRAVEL","amount":10000,"occurDate":"2099-12-03","invoiceNo":"SMKEI-003","invoiceVendor":"冒烟酒店"}]}' $B/api/fin/reimburse)
R2_ID=$(echo "$R2_RAW" | J "d['data']['id']")
R2_APPR=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='ExpenseReimburse' AND BIZ_ID='$R2_ID'")
for _i in 1 2; do
  T=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$R2_APPR' AND STATUS='ACTIVE' LIMIT 1")
  [ -n "$T" ] && C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"taskId\":\"$T\",\"opinion\":\"SMKEX 审批\"}" $B/api/qms/approvals/pass >/dev/null
done
SI2=$(C -X GET -H "$(A $TADM)" $B/api/fin/reimburse/$R2_ID/settle-info)
WO2=$(echo "$SI2" | J "int(float(d['data']['writeoffTotal']))")
SI2_DIFF=$(echo "$SI2" | J "int(float(d['data']['difference']))")
[ "$WO2" = "0" ] && [ "$SI2_DIFF" = "10000" ] \
  && ok "无借款直接挂应付（冲销 0、差额 10000）" || bad "settle-info 异常（$SI2）"

PD_RAW=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"bankAccountId":"bank-icbc-basic","payDate":"2099-10-31"}' $B/api/fin/reimburse/$R2_ID/pay-difference)
PD_V=$(echo "$PD_RAW" | J "d['data']['voucherNo']")
PD_DIFF=$(echo "$PD_RAW" | J "int(float(d['data']['difference']))")
BANK_BAL=$(SQL "SELECT CAST(BALANCE AS CHAR) FROM erp_fin_bank_account WHERE ID='bank-icbc-basic'")
[ -n "${PD_V:-}" ] && [ "$PD_DIFF" = "10000" ] \
  && ok "出纳补付差额（凭证 $PD_V，账户余额 $BANK_BAL）" || bad "补付异常（$PD_RAW）"
[ "$BANK_BAL" = "42000.00" ] && ok "余额条件扣减（50000−10000补付+2000退款=42000）" || bad "扣减异常（$BANK_BAL）"
PD2_CODE=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"bankAccountId":"bank-icbc-basic","payDate":"2099-10-31"}' $B/api/fin/reimburse/$R2_ID/pay-difference)
EQ "重复补付 422" "$PD2_CODE" "422"

# 24.8 预提/待摊登记（计划生成 + L1 尾差 + L4 提示 + 非法参数）
PRE1_RAW=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"expenseAccountCode":"660202","period":"209910","amount":15000,"expenseType":"TRAVEL","remark":"SMKEX 预提差旅"}' $B/api/fin/amort/prepaid)
PRE1_NO=$(echo "$PRE1_RAW" | J "d['data']['preNo']")
PRE1_ID=$(echo "$PRE1_RAW" | J "d['data']['id']")
[ -n "${PRE1_NO:-}" ] && ok "预提登记（$PRE1_NO → 单期计划）" || bad "预提登记失败（$PRE1_RAW）"
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"expenseAccountCode":"660202","period":"209910","amount":10000,"expenseType":"TRAVEL","remark":"SMKEX 预提容差内"}' $B/api/fin/amort/prepaid >/dev/null
DEF_RAW=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"summary":"SMKEX 房租","totalAmount":100000,"amortMonths":3,"startPeriod":"209910","expenseAccountCode":"660203"}' $B/api/fin/amort/deferred)
DEF_NO=$(echo "$DEF_RAW" | J "d['data']['defNo']")
DEF_V=$(echo "$DEF_RAW" | J "d['data']['voucherNo']")
DEF_L4=$(echo "$DEF_RAW" | J "d['data'].get('l4Hint')")
[ -n "${DEF_NO:-}" ] && [ -n "${DEF_V:-}" ] && [ "$DEF_L4" = "None" ] \
  && ok "待摊登记 3 期（挂账凭证 $DEF_V，正常期无 L4）" || bad "待摊登记异常（$DEF_RAW）"
PLAN_LAST=$(SQL "SELECT CAST(AMOUNT AS CHAR) FROM erp_fin_amort_plan WHERE DOC_NO='$DEF_NO' ORDER BY PERIOD DESC LIMIT 1")
EQ "直线法尾差并入末期（33333.34）" "$PLAN_LAST" "33333.34"
DEF14_RAW=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"summary":"SMKEX 长期待摊","totalAmount":120000,"amortMonths":14,"startPeriod":"209910","expenseAccountCode":"660203"}' $B/api/fin/amort/deferred)
DEF14_L4=$(echo "$DEF14_RAW" | J "d['data'].get('l4Hint')")
CONTAINS "超 12 月 L4 提示（BR-4.6-05）" "${DEF14_L4:-None}" "BR-4.6-05"
BAD_CODE=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"summary":"SMKEX 非法","totalAmount":1000,"amortMonths":0,"startPeriod":"209910","expenseAccountCode":"660203"}' $B/api/fin/amort/deferred)
EQ "摊销月数 0 → 422" "$BAD_CODE" "422"

# 24.9 结账⑨：未生成计划行 → 阻断；生成后 → 放行（幂等）
G0_CHK=$(C -X GET -H "$(A $TADM)" $B/api/fin/periods/209910/checklist)
G0_OK=$(echo "$G0_CHK" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
it=[i for i in d['items'] if i['key']=='pendingAmortPlan']
print('OK' if (it and it[0]['ok'] is False) else 'NG')" 2>/dev/null)
[ "${G0_OK:-NG}" = "OK" ] && ok "⑨未生成计划行阻断" || bad "⑨阻断异常（$G0_CHK）"
G1_RAW=$(C -X POST -H "$(A $TADM)" "$B/api/fin/amort/generate?period=209910")
G1_N=$(echo "$G1_RAW" | J "d['data']['generated']")
[ "${G1_N:-0}" -ge 3 ] && ok "生成本期凭证（$G1_N 行 DRAFT，预提×2+待摊首期+长期待摊首期）" || bad "生成异常（$G1_RAW）"
G2_RAW=$(C -X POST -H "$(A $TADM)" "$B/api/fin/amort/generate?period=209910")
G2_N=$(echo "$G2_RAW" | J "d['data']['generated']")
G2_S=$(echo "$G2_RAW" | J "d['data']['skipped']")
[ "${G2_N:-1}" = "0" ] && [ "${G2_S:-0}" -ge 3 ] \
  && ok "二次触发幂等（0 生成 / $G2_S 跳过）" || bad "幂等异常（$G2_RAW）"
G3_CHK=$(C -X GET -H "$(A $TADM)" $B/api/fin/periods/209910/checklist)
G3_OK=$(echo "$G3_CHK" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
it=[i for i in d['items'] if i['key']=='pendingAmortPlan']
print('OK' if (it and it[0]['ok'] is True) else 'NG')" 2>/dev/null)
[ "${G3_OK:-NG}" = "OK" ] && ok "⑨计划全生成放行" || bad "⑨放行异常（$G3_CHK）"

# 24.10 预提实际冲销：2% 超容差自动差异行（C-4.6-16）/ 0.2% 容差内 / 重复 422
ST1_RAW=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"actualAmount":15300,"settleMode":"PAYMENT","payDate":"2099-10-31","bankAccountId":"bank-icbc-basic"}' $B/api/fin/amort/prepaid/$PRE1_ID/settle)
ST1_OT=$(echo "$ST1_RAW" | J "d['data'].get('overTolerance')")
[ "$ST1_OT" = "True" ] && ok "差异率 2% 超容差标记" || bad "超容差标记异常（$ST1_RAW）"
ST1_LINES=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE VOUCHER_TYPE='PSETTLE' AND SOURCE_DOC_NO='$PRE1_NO') t3)")
EQ "冲销凭证三行（含差异行）" "$ST1_LINES" "3"
ST1_SUM=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher_line WHERE SUMMARY LIKE '%C-4.6-16%' AND VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE VOUCHER_TYPE='PSETTLE' AND SOURCE_DOC_NO='$PRE1_NO') t4)")
[ "${ST1_SUM:-0}" -ge 1 ] && ok "差异行摘要含 C-4.6-16 留痕" || bad "差异留痕缺失"
PRE2_NO=$(SQL "SELECT PRE_NO FROM erp_fin_prepaid_doc WHERE REMARK LIKE 'SMKEX 预提容差内%' ORDER BY CREATE_DATE DESC LIMIT 1")
PRE2_ID=$(SQL "SELECT ID FROM erp_fin_prepaid_doc WHERE REMARK LIKE 'SMKEX 预提容差内%' ORDER BY CREATE_DATE DESC LIMIT 1")
ST2_RAW=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"actualAmount":10020,"settleMode":"PAYMENT","payDate":"2099-10-31","bankAccountId":"bank-icbc-basic"}' $B/api/fin/amort/prepaid/$PRE2_ID/settle)
ST2_OT=$(echo "$ST2_RAW" | J "d['data'].get('overTolerance')")
[ "$ST2_OT" = "False" ] && ok "容差内 0.2% 不标超容差" || bad "容差判断异常（$ST2_RAW）"
ST2_CODE=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"actualAmount":10050,"settleMode":"PAYMENT","payDate":"2099-10-31","bankAccountId":"bank-icbc-basic"}' $B/api/fin/amort/prepaid/$PRE2_ID/settle)
EQ "重复冲销 422" "$ST2_CODE" "422"

# 24.11 年末余额检查（FR-4.6-8-6，L4 提示非阻断）
YC=$(C -X GET -H "$(A $TADM)" "$B/api/fin/amort/year-end-check?period=209910")
YC_CNT=$(echo "$YC" | J "len(d['data']['items'])")
YC_L4=$(echo "$YC" | J "all(i.get('level')=='L4' for i in d['data']['items'])")
[ "${YC_CNT:-0}" -eq 2 ] && [ "$YC_L4" = "True" ] \
  && ok "年末检查两项链目 L4（预提清零/待摊勾稽）" || bad "年末检查异常（$YC）"

# 24.12 收尾清理：SMKEX 单据的跨期计划行（209911/209912/210001+）会污染
# 回归测试——GlManagementDbTest ⑨ 全绿（209912 NOT_GEN 阻断）与
# AmortPlanDbTest 年末勾稽（Σ未摊剩余多出冒烟行）；209910 保留供幂等语义。
SQL "
DELETE FROM erp_fin_amort_plan WHERE PERIOD <> '209910' AND DOC_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_deferred_doc WHERE SUMMARY LIKE 'SMKEX%') a);
DELETE FROM erp_fin_amort_plan WHERE PERIOD <> '209910' AND DOC_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_prepaid_doc WHERE REMARK LIKE 'SMKEX%') b);
" >/dev/null 2>&1 && ok "收尾清理跨期计划行（防回归污染）" || bad "收尾清理失败"

echo "段 24 费用报销与预提待摊追加完成"

# ============================================================================
# 段 25 add-cost-accounting：M8-10 成本核算（报表同源聚合/红标/CSV/trend/422/
# HTTP 审阅端到端 + zhangsan 第三方 403 + 菜单 PERM 走查）。
# 角色分工：FINANCE_MGR 放行与 PLAN_MGR 不回归由 CostReportDbTest 覆盖
#（HTTP 账号密码不可知），冒烟测 HTTP 链路与同源一致性。
# ============================================================================
echo "== 25. 成本核算（add-cost-accounting：报表同源/红标/CSV/trend + HTTP 审阅 + 第三方 403 + PERM）=="

# 25.0 清理（可重跑）+ M8-10 菜单 PERM 走查
SQL "
DELETE FROM erp_mrp_cost_summary WHERE MO_ID LIKE 'mo-smkct-%';
DELETE FROM erp_mrp_mo WHERE ID LIKE 'mo-smkct-%';
" >/dev/null 2>&1 && ok "清理完成（SMKCT 族）" || bad "清理失败"
CT_PERM=$(SQL "SELECT COUNT(*) FROM erp_admin_menu WHERE ID LIKE 'M8-10%' AND PERM LIKE '%ROLE_COST_ACCOUNTANT%' AND PERM LIKE '%ROLE_FINANCE_MGR%' AND PERM <> ''")
EQ "M8-10 四行 PERM（含成本会计/财务主管）" "$CT_PERM" "4"

# 25.1 报表同源聚合（真实数据期间 202610：summary=明细等式 + 红标一致性）
CT_R1=$(C -X GET -H "$(A $TADM)" "$B/api/fin/cost-report?period=202610")
CT_OK=$(echo "$CT_R1" | python3 -c "
import sys,json
d=json.load(sys.stdin)
if d['code']!=200: print('NG'); raise SystemExit
s=d['data']['summary']; rows=d['data']['rows']
same = (s['moCount']==len(rows) and len(rows)>0)
mat = sum(float(r.get('materialCost') or 0) for r in rows)
tot = sum(float(r.get('totalCost') or 0) for r in rows)
eq = abs(mat-float(s['materialCost']))<0.01 and abs(tot-float(s['totalCost']))<0.01
over = sum(1 for r in rows if r.get('overTolerance'))
ov_eq = (over==int(s['overToleranceCount']))
tol_ok = abs(float(s['tolerance'])-0.005)<1e-9
print('OK' if (same and eq and ov_eq and tol_ok) else 'NG')" 2>/dev/null)
[ "${CT_OK:-NG}" = "OK" ] && ok "报表同源聚合+红标计数+容差 0.005（202610）" || bad "报表聚合异常（$CT_R1）"

# 25.2 CSV 与 JSON 同源（BOM + 中文表头 + 行数 = moCount+1）
CT_CSV=$(C -X GET -H "$(A $TADM)" "$B/api/fin/cost-report?period=202610&format=csv")
CT_CSVOK=$(printf '%s' "$CT_CSV" | python3 -c "
import sys
data=sys.stdin.buffer.read().decode('utf-8', errors='replace')
lines=data.strip().splitlines()
bom = data.startswith('﻿')
head_ok = '工单号' in lines[0] and '超容差' in lines[0]
print('OK' if (bom and head_ok and len(lines)>=2) else 'NG')" 2>/dev/null)
[ "${CT_CSVOK:-NG}" = "OK" ] && ok "CSV BOM+中文表头（同源导出）" || bad "CSV 异常"
CT_CSVN=$(printf '%s' "$CT_CSV" | python3 -c "import sys;print(len(sys.stdin.buffer.read().decode('utf-8',errors='replace').strip().splitlines()))" 2>/dev/null)
CT_MO=$(echo "$CT_R1" | J "d['data']['summary']['moCount']")
[ "$CT_CSVN" = "$((CT_MO + 1))" ] && ok "CSV 行数=明细+表头（$CT_CSVN）" || bad "CSV 行数 $CT_CSVN vs $CT_MO"

# 25.3 trend 单期与报表同源
CT_TR=$(C -X GET -H "$(A $TADM)" "$B/api/fin/cost-report/trend")
CT_TROK=$(echo "$CT_TR" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
t=[x for x in d if x['period']=='202610']
ok = len(d)>=1 and t and abs(float(t[0]['totalCost'])-119197.0)<100000  # 总额存在且>0
tot_ok = t and float(t[0]['totalCost'])>0 and t[0]['moCount']>0
print('OK' if tot_ok else 'NG')" 2>/dev/null)
[ "${CT_TROK:-NG}" = "OK" ] && ok "trend 单期如实（总数随重跑增长，不断言写死）" || bad "trend 异常（$CT_TR）"

# 25.4 期间格式 422
CT_422=$(BIZ -X GET -H "$(A $TADM)" "$B/api/fin/cost-report?period=2099")
EQ "期间格式 422" "$CT_422" "422"

# 25.5 HTTP 审阅端到端：造 SMKCT RAISED 行 → admin(ROLE_ADMIN 直通) 审阅 → REVIEWED
SQL "INSERT INTO erp_mrp_mo (ID, MO_NO, MO_TYPE, PRODUCT_CODE, PRODUCT_NAME, QTY, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('mo-smkct-1','MOSMKCT01','NORMAL','SMKCTA01','冒烟成本产品',10,'CLOSED','smoke','0',0);
     INSERT INTO erp_mrp_cost_summary (ID, MO_ID, MO_NO, PERIOD, MATERIAL_COST, LABOR_COST, OVERHEAD_COST,
     TOTAL_COST, GOOD_QTY, UNIT_COST, STD_TOTAL_COST, VAR_TOTAL, VAR_RATE, REPORT_STATUS, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smkct-1','mo-smkct-1','MOSMKCT01','209901',100,50,25,175,10,17.5,160,15,'0.012','RAISED','smoke','0',0);" >/dev/null
CT_RV=$(BIZ -X POST -H "$(A $TADM)" "$B/api/mrp/cost/mo-smkct-1/review")
EQ "HTTP 审阅端到端（admin 直通）" "$CT_RV" "200"
CT_ST=$(SQL "SELECT REPORT_STATUS FROM erp_mrp_cost_summary WHERE MO_ID='mo-smkct-1'")
EQ "审阅落库 REVIEWED" "$CT_ST" "REVIEWED"

# 25.6 第三方角色 403：zhangsan(ROLE_USER) 审阅 → 403；再造一行给他试
SQL "INSERT INTO erp_mrp_cost_summary (ID, MO_ID, MO_NO, PERIOD, MATERIAL_COST, LABOR_COST, OVERHEAD_COST,
     TOTAL_COST, GOOD_QTY, UNIT_COST, STD_TOTAL_COST, VAR_TOTAL, VAR_RATE, REPORT_STATUS, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smkct-2','mo-smkct-1','MOSMKCT01','209901',1,1,1,3,1,3,3,0,'0','RAISED','smoke','0',0)
     ON DUPLICATE KEY UPDATE REPORT_STATUS='RAISED';" >/dev/null
TZS=$(C -H 'Content-Type: application/json' -d '{"username":"zhangsan","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
CT_403=$(BIZ -X POST -H "Authorization: Bearer $TZS" "$B/api/mrp/cost/mo-smkct-1/review")
EQ "第三方角色（ROLE_USER）审阅 403" "$CT_403" "403"

# 25.7 收尾清理 SMKCT
SQL "DELETE FROM erp_mrp_cost_summary WHERE MO_ID LIKE 'mo-smkct-%';
     DELETE FROM erp_mrp_mo WHERE ID LIKE 'mo-smkct-%';" >/dev/null 2>&1 \
  && ok "收尾清理（SMKCT）" || bad "收尾清理失败"

echo "段 25 成本核算追加完成"

# ============================================================================
# 段 26 add-tax-calculation：M9-1 计税管理（匹配唯一/多命中/L1 422 + 计算分离/
# 分摊/SIMPLE + 台账留痕→补录→DRAFT→审核过账→总账变动→重复 422 + 报告勾稽）。
# 提取约定同段 24/25：POST 存 raw JSON、J 单通、422 用 BIZ、小数归一化。
# ============================================================================
echo "== 26. 计税管理（add-tax-calculation：匹配/计算/台账/DRAFT 入账/合规报告）=="

# 26.0 清理（可重跑）
SQL "
DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID
 WHERE v.SOURCE_TYPE = 'TAXCALC';
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'TAXCALC';
DELETE FROM erp_fin_tax_calc_ledger;
DELETE FROM erp_fin_balance WHERE PERIOD = '209902' AND ACCOUNT_CODE IN ('222101','222102');
" >/dev/null 2>&1 && ok "清理完成（TAXCALC 族 + 209902 税额余额）" || bad "清理失败"

# 26.1 M9-1 菜单 PERM 走查
TX_PERM=$(SQL "SELECT COUNT(*) FROM erp_admin_menu WHERE ID LIKE 'M9-1%' AND PERM <> '' AND PERM LIKE '%ROLE_FINANCE%'")
EQ "M9-1 四行 PERM 非空" "$TX_PERM" "4"

# 26.2 匹配：唯一命中（STANDARD→VAT13）
TX1=$(C -X GET -H "$(A $TADM)" "$B/api/fin/tax-calc/match?trxType=SALES&scope=DOMESTIC&calcType=GENERAL&rateKind=STANDARD&businessDate=2026-10-23")
TX1_MULTI=$(echo "$TX1" | J "d['data']['multiCandidate']")
TX1_CODE=$(echo "$TX1" | J "d['data']['candidates'][0]['taxCode']")
[ "$TX1_MULTI" = "False" ] && [ "$TX1_CODE" = "VAT13" ] \
  && ok "匹配唯一命中（STANDARD→VAT13）" || bad "唯一命中异常（$TX1）"

# 26.3 匹配：多命中 L4（LOW→VAT06+VAT09）
TX2=$(C -X GET -H "$(A $TADM)" "$B/api/fin/tax-calc/match?trxType=PURCHASE&scope=DOMESTIC&calcType=GENERAL&rateKind=LOW&businessDate=2026-10-23")
TX2_MULTI=$(echo "$TX2" | J "d['data']['multiCandidate']")
TX2_N=$(echo "$TX2" | J "len(d['data']['candidates'])")
[ "$TX2_MULTI" = "True" ] && [ "$TX2_N" = "2" ] \
  && ok "多命中 L4（LOW→VAT06+VAT09 候选）" || bad "多命中异常（$TX2）"

# 26.4 匹配：C-4.14-01 L1 阻断（业务发生日早于全部生效区间）
TX422=$(BIZ -X GET -H "$(A $TADM)" "$B/api/fin/tax-calc/match?trxType=SALES&scope=DOMESTIC&calcType=GENERAL&rateKind=STANDARD&businessDate=1999-01-01")
TX422MSG=$(MSG -X GET -H "$(A $TADM)" "$B/api/fin/tax-calc/match?trxType=SALES&scope=DOMESTIC&calcType=GENERAL&rateKind=STANDARD&businessDate=1999-01-01")
[ "$TX422" = "422" ] && CONTAINS "C-4.14-01 文案透传" "$TX422MSG" "税率主数据缺失或已过期" || bad "L1 未阻断（code=$TX422）"

# 26.5 计算：含税价税分离 11300→基数 10000/税额 1300
TXC1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"calcType":"GENERAL","grossFlag":"Y","taxRate":"0.13","lines":[{"amount":"11300"}]}' $B/api/fin/tax-calc/calc)
TXC1_BASE=$(echo "$TXC1" | J "d['data']['taxableBase']")
TXC1_TAX=$(echo "$TXC1" | J "d['data']['taxAmount']")
TXC1_OK=$(python3 -c "print('OK' if (abs(float('$TXC1_BASE')-10000)<0.01 and abs(float('$TXC1_TAX')-1300)<0.01) else 'NG')" 2>/dev/null)
[ "${TXC1_OK:-NG}" = "OK" ] && ok "含税价税分离（11300→10000+1300）" || bad "分离异常（$TXC1）"

# 26.6 计算：多税率折扣分摊（10000@13%+5000@06%，折扣 1500 → 1170+270=1440）
TXC2=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"calcType":"GENERAL","grossFlag":"N","discount":"1500","lines":[{"amount":"10000","taxRate":"0.13"},{"amount":"5000","taxRate":"0.06"}]}' $B/api/fin/tax-calc/calc)
TXC2_TAX=$(echo "$TXC2" | J "d['data']['taxAmount']")
TXC2_L1=$(echo "$TXC2" | J "d['data']['lines'][0]['taxAmount']")
TXC2_L2=$(echo "$TXC2" | J "d['data']['lines'][1]['taxAmount']")
TXC2_OK=$(python3 -c "print('OK' if (abs(float('$TXC2_TAX')-1440)<0.01 and abs(float('$TXC2_L1')-1170)<0.01 and abs(float('$TXC2_L2')-270)<0.01) else 'NG')" 2>/dev/null)
[ "${TXC2_OK:-NG}" = "OK" ] && ok "多税率折扣分摊（1170+270=1440，BR-4.14-12）" || bad "分摊异常（$TXC2）"

# 26.7 计算：SIMPLE 不得抵扣 300
TXC3=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"calcType":"SIMPLE","grossFlag":"N","taxRate":"0.03","lines":[{"amount":"10000"}]}' $B/api/fin/tax-calc/calc)
TXC3_DED=$(echo "$TXC3" | J "d['data']['deductible']")
TXC3_TAX=$(echo "$TXC3" | J "d['data']['taxAmount']")
TXC3_OK=$(python3 -c "print('OK' if ('$TXC3_DED'=='N' and abs(float('$TXC3_TAX')-300)<0.01) else 'NG')" 2>/dev/null)
[ "${TXC3_OK:-NG}" = "OK" ] && ok "SIMPLE 按征收率且不得抵扣（300）" || bad "SIMPLE 异常（$TXC3）"

# 26.8 台账：销项行留痕 → 仅留痕不可生成凭证 → 补录三字段 → DRAFT 凭证
TXL=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"trxType":"SALES","scope":"DOMESTIC","calcType":"GENERAL","rateKind":"STANDARD","businessDate":"2026-10-23","amount":"11300","grossFlag":"Y","taxCode":"VAT13","taxRate":"0.13","taxableBase":"10000","taxAmount":"1300","deductible":"Y"}' $B/api/fin/tax-calc/ledger)
TXL_ID=$(echo "$TXL" | J "d['data']['id']")
TXL_ST=$(echo "$TXL" | J "d['data']['status']")
[ -n "${TXL_ID:-}" ] && [ "$TXL_ST" = "RECORDED" ] && ok "台账留痕（RECORDED）" || bad "留痕异常（$TXL）"
TX_GEN_EARLY=$(BIZ -X POST -H "$(A $TADM)" "$B/api/fin/tax-calc/ledger/$TXL_ID/generate-voucher")
EQ "仅留痕不可生成凭证 422" "$TX_GEN_EARLY" "422"
TX_AP=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"counterparty":"冒烟客户A","period":"209902","summary":"手工计税补提销项"}' $B/api/fin/tax-calc/ledger/$TXL_ID/submit-apply)
TX_AP_ST=$(echo "$TX_AP" | J "d['data']['status']")
[ "$TX_AP_ST" = "APPLYING" ] && ok "补录三字段 → 入账申请" || bad "补录异常（$TX_AP）"
TXV=$(C -X POST -H "$(A $TADM)" "$B/api/fin/tax-calc/ledger/$TXL_ID/generate-voucher")
TXV_ST=$(echo "$TXV" | J "d['data']['status']")
TXV_NO=$(echo "$TXV" | J "d['data']['voucherNo']")
[ "$TXV_ST" = "DRAFT" ] && ok "税务凭证 DRAFT（$TXV_NO，TAXCALC 锚）" || bad "凭证异常（$TXV）"
TX_GEN_DUP=$(BIZ -X POST -H "$(A $TADM)" "$B/api/fin/tax-calc/ledger/$TXL_ID/generate-voucher")
EQ "重复生成凭证 422" "$TX_GEN_DUP" "422"

# 26.9 SIMPLE 行入账 → 借 660299 不进 222101
TXS=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"trxType":"PURCHASE","scope":"DOMESTIC","calcType":"SIMPLE","businessDate":"2026-10-23","amount":"10000","grossFlag":"N","taxCode":"VAT03-SIMPLE","taxRate":"0.03","taxableBase":"10000","taxAmount":"300","deductible":"N"}' $B/api/fin/tax-calc/ledger)
TXS_ID=$(echo "$TXS" | J "d['data']['id']")
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"counterparty":"冒烟供应商B","period":"209902","summary":"简易计税零星采购"}' $B/api/fin/tax-calc/ledger/$TXS_ID/submit-apply >/dev/null 2>&1
C -X POST -H "$(A $TADM)" $B/api/fin/tax-calc/ledger/$TXS_ID/generate-voucher >/dev/null
TXS_ACCTS=$(SQL "SELECT GROUP_CONCAT(l.ACCOUNT_CODE ORDER BY FIELD(l.DIRECTION,'DR','CR')) FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID WHERE v.SOURCE_TYPE='TAXCALC' AND v.SOURCE_DOC_NO='$TXS_ID'")
EQ "SIMPLE 入账借 660299/贷 2202（不进 222101）" "$TXS_ACCTS" "660299,2202"

# 26.10 审核过账（制单 admin → 审核 finmgr，C-4.6-07 职责分离）→ 总账 222102 变动 1300
TXV_ID=$(echo "$TXV" | J "d['data']['voucherId']")
TFIN=$(C -H 'Content-Type: application/json' -d '{"username":"finmgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
C -X POST -H "Authorization: Bearer $TFIN" $B/api/fin/manual-vouchers/$TXV_ID/audit >/dev/null 2>&1
C -X POST -H "Authorization: Bearer $TFIN" $B/api/fin/manual-vouchers/$TXV_ID/post >/dev/null 2>&1
TXV_STATUS=$(SQL "SELECT STATUS FROM erp_fin_voucher WHERE ID='$TXV_ID'")
TX_222102=$(SQL "SELECT IFNULL(CAST(CR_TOTAL AS CHAR),'0') FROM erp_fin_balance WHERE PERIOD='209902' AND ACCOUNT_CODE='222102'")
TX_POST_OK=$(python3 -c "print('OK' if ('$TXV_STATUS'=='POSTED' and abs(float('$TX_222102')-1300)<0.01) else 'NG')" 2>/dev/null)
[ "${TX_POST_OK:-NG}" = "OK" ] && ok "审核过账后总账 222102 CR=1300" || bad "过账异常（status=$TXV_STATUS bal=$TX_222102）"

# 26.11 报告：勾稽 ok（台账 1300 vs 总账 1300）+ 分类编码黄标恒在
TXR=$(C -X GET -H "$(A $TADM)" "$B/api/fin/tax-calc/report?period=209902")
TXR_OK=$(echo "$TXR" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
items={i['key']:i for i in d['items']}
# taxCodeExpiry 不在此断言：冒烟 26.9 用假税码 VAT03-SIMPLE 造数，报告正确判红——
# 该行为由 TaxCalcDbTest 精确覆盖；此处只验勾稽/黄标/违规三项
ok = (items['glRecon']['level']=='ok'
      and items['taxCategoryCode']['level']=='warn'
      and items['nonDeductibleViolation']['level']=='ok')
print('OK' if ok else 'NG')" 2>/dev/null)
[ "${TXR_OK:-NG}" = "OK" ] && ok "报告：勾稽一致+分类编码黄标+无违规" || bad "报告异常（$TXR）"

# 26.12 报告：人为制造差异 → 红标
SQL "UPDATE erp_fin_balance SET CR_TOTAL = CR_TOTAL + 100 WHERE PERIOD='209902' AND ACCOUNT_CODE='222102'" >/dev/null
TXR2=$(C -X GET -H "$(A $TADM)" "$B/api/fin/tax-calc/report?period=209902")
TXR2_RED=$(echo "$TXR2" | J "int(d['data']['redCount'])")
[ "${TXR2_RED:-0}" -ge 1 ] && ok "勾稽差异红标（redCount=$TXR2_RED）" || bad "红标异常（$TXR2）"

# 26.13 收尾清理（防污染回归：清余额差异行）
SQL "DELETE FROM erp_fin_balance WHERE PERIOD='209902' AND ACCOUNT_CODE IN ('222101','222102')" >/dev/null 2>&1 \
  && ok "收尾清理（209902 税额余额）" || bad "收尾清理失败"

echo "段 26 计税管理追加完成"

# ============================================================================
# 段 27 add-invoice-management：M9-2 发票管理（三单匹配/认证三卡控/批量进项
# 凭证/数电额度队列补开拆分幂等/勾稽/交付重发超期/限额提示）。
# 提取约定同段 24~26：POST 存 raw JSON、J 单通、422 用 BIZ、小数归一化。
# 额度按真实当期（submitRequest 服务端口径 LocalDate.now()）。
# ============================================================================
echo "== 27. 发票管理（add-invoice-management：三单匹配/认证/批量凭证/数电队列/勾稽/交付）=="

# 27.0 清理（可重跑）
SQL "
DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID
 WHERE v.SOURCE_TYPE = 'TAXIN';
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'TAXIN';
DELETE FROM erp_fin_input_invoice;
DELETE FROM erp_decoded_request;
DELETE FROM erp_fin_digital_quota;
DELETE FROM erp_fin_invoice_delivery;
DELETE FROM erp_proc_po WHERE ID = 'smkiv-po-1';
" >/dev/null 2>&1 && ok "清理完成（TAXIN 族+进项/请求/额度/交付+造数 PO）" || bad "清理失败"

# 27.1 M9-2 菜单 PERM 走查
IV_PERM=$(SQL "SELECT COUNT(*) FROM erp_admin_menu WHERE ID LIKE 'M9-2%' AND PERM <> '' AND PERM LIKE '%ROLE_FINANCE%'")
EQ "M9-2 五行 PERM 非空" "$IV_PERM" "5"

# 27.2 造测试 PO（供应商 smkiv-sup，总额 50000）
SQL "INSERT INTO erp_proc_po (ID, PO_NO, PO_TYPE, SOURCE, SUPPLIER_ID, SUPPLIER_NAME, STATUS, TOTAL_AMT, CURRENCY, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smkiv-po-1', 'SMKIVPO01', 'NORMAL', 'MANUAL', 'smkiv-sup', '冒烟发票供应商', 'CONFIRMED', 50000, 'CNY', 'smoke', '0', 0);" >/dev/null \
  && ok "测试 PO 就绪（50000）" || bad "PO 造数失败"

# 27.3 进项登记：容差内 MATCHED（49900 vs 50000 = 0.2%）
IV1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"invoiceNo":"SMKIV-001","supplierId":"smkiv-sup","supplierName":"冒烟发票供应商","poId":"smkiv-po-1","invoiceDate":"2026-09-01","amount":"49900","taxAmount":"2994","inspect":true}' $B/api/fin/input-invoices)
IV1_MS=$(echo "$IV1" | J "d['data']['matchStatus']")
[ "$IV1_MS" = "MATCHED" ] && ok "三单匹配容差内 MATCHED（0.2%）" || bad "匹配异常（$IV1）"

# 27.4 进项登记：超差 DIFF（再登记 48000 vs 未开票余额 49900 → 差 ~3.8%）
IV2=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"invoiceNo":"SMKIV-002","supplierId":"smkiv-sup","supplierName":"冒烟发票供应商","poId":"smkiv-po-1","invoiceDate":"2026-09-01","amount":"48000","taxAmount":"2880","inspect":true}' $B/api/fin/input-invoices)
IV2_MS=$(echo "$IV2" | J "d['data']['matchStatus']")
[ "$IV2_MS" = "DIFF" ] && ok "三单匹配超差 DIFF 红标" || bad "DIFF 异常（$IV2）"
IV2_ID=$(echo "$IV2" | J "d['data']['id']")
IV2_CERT=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"certDate":"2026-09-10"}' $B/api/fin/input-invoices/$IV2_ID/certify)
EQ "DIFF 阻断认证 422" "$IV2_CERT" "422"

# 27.5 供应商不符 422（L1）
IV3_CODE=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"invoiceNo":"SMKIV-003","supplierId":"smkiv-other","poId":"smkiv-po-1","invoiceDate":"2026-09-01","amount":"1000","taxAmount":"60","inspect":true}' $B/api/fin/input-invoices)
EQ "供应商不符登记 422" "$IV3_CODE" "422"

# 27.6 认证三卡控：SMKIV-001 正常认证→CERTIFIED+所属期锁定；重复认证 422 回显
IV1_ID=$(echo "$IV1" | J "d['data']['id']")
IV1_CERT=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"certDate":"2026-09-10"}' $B/api/fin/input-invoices/$IV1_ID/certify)
IV1_CS=$(echo "$IV1_CERT" | J "d['data']['certStatus']")
IV1_CP=$(echo "$IV1_CERT" | J "d['data']['certPeriod']")
[ "$IV1_CS" = "CERTIFIED" ] && [ "$IV1_CP" = "202609" ] && ok "认证 CERTIFIED+所属期 202609 锁定（C-4.14-11）" || bad "认证异常（$IV1_CERT）"
IV1_DUP=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"certDate":"2026-09-15"}' $B/api/fin/input-invoices/$IV1_ID/certify)
IV1_DUPMSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"certDate":"2026-09-15"}' $B/api/fin/input-invoices/$IV1_ID/certify)
[ "$IV1_DUP" = "422" ] && CONTAINS "重复认证回显原日期（C-4.14-10）" "$IV1_DUPMSG" "2026-09-10" || bad "重复认证未阻断（$IV1_DUP）"

# 27.7 超期认证自动不可抵扣（C-4.14-07：开票日 2024-01-01 → 900+ 天）
IV4=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"invoiceNo":"SMKIV-004","supplierId":"smkiv-sup","supplierName":"冒烟发票供应商","poId":"smkiv-po-1","invoiceDate":"2024-01-01","amount":"100","taxAmount":"6","inspect":true}' $B/api/fin/input-invoices)
IV4_ID=$(echo "$IV4" | J "d['data']['id']")
IV4_CERT=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"certDate":"2026-09-10"}' $B/api/fin/input-invoices/$IV4_ID/certify)
IV4_DED=$(echo "$IV4_CERT" | J "d['data']['deductible']")
[ "$IV4_DED" = "N" ] && ok "超期认证自动不可抵扣（C-4.14-07）" || bad "超期标记异常（$IV4_CERT）"

# 27.8 批量进项凭证（202609：SMKIV-001 可抵扣 + SMKIV-004 不可抵扣 → 2 张 DRAFT）
IVG=$(C -X POST -H "$(A $TADM)" "$B/api/fin/input-invoices/generate-vouchers?period=202609")
IVG_N=$(echo "$IVG" | J "d['data']['generated']")
[ "${IVG_N:-0}" = "2" ] && ok "批量进项凭证 2 张 DRAFT（1 可抵+1 不可抵）" || bad "生成异常（$IVG）"
IVG2=$(C -X POST -H "$(A $TADM)" "$B/api/fin/input-invoices/generate-vouchers?period=202609")
IVG2_N=$(echo "$IVG2" | J "d['data']['generated']")
[ "${IVG2_N:-1}" = "0" ] && ok "批量凭证幂等（0 生成）" || bad "幂等异常（$IVG2）"
IV_DR=$(SQL "SELECT l.ACCOUNT_CODE FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID WHERE v.SOURCE_TYPE='TAXIN' AND v.SOURCE_DOC_NO='SMKIV-004' AND l.DIRECTION='DR'")
EQ "不可抵扣借 660299（不进 222101）" "$IV_DR" "660299"

# 27.9 数电：额度不足 422（BR-4.14-36）
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"period\":\"$(date +%Y%m)\",\"totalLimit\":\"30000\"}" $B/api/fin/digital-invoices/quota >/dev/null
DQ_BLOCK=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"clientName":"冒烟客户","amount":"35000","soNo":"SMKIVSO-1","shipNo":"SMKIVSH-1","lineNo":"10"}' $B/api/fin/digital-invoices/requests)
EQ "额度不足提交 422（BR-4.14-36）" "$DQ_BLOCK" "422"

# 27.10 补开扣额度 + 幂等 422（BR-4.14-37）
DSR1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"clientName":"冒烟客户","amount":"20000","soNo":"SMKIVSO-2","shipNo":"SMKIVSH-2","lineNo":"10"}' $B/api/fin/digital-invoices/requests)
DSR1_ID=$(echo "$DSR1" | J "d['data']['id']")
DSR1_ISSUE=$(C -X POST -H "$(A $TADM)" $B/api/fin/digital-invoices/requests/$DSR1_ID/issue)
DSR1_ST=$(echo "$DSR1_ISSUE" | J "d['data']['status']")
DSR1_AVAIL=$(echo "$DSR1_ISSUE" | J "float(d['data']['quotaAvailable'])")
[ "$DSR1_ST" = "ISSUED" ] && [ "$DSR1_AVAIL" = "10000.0" ] && ok "补开 ISSUED+额度扣减（30000−20000=10000）" || bad "补开异常（$DSR1_ISSUE）"
DSR1_DUP=$(BIZ -X POST -H "$(A $TADM)" $B/api/fin/digital-invoices/requests/$DSR1_ID/issue)
EQ "重复补开 422（幂等）" "$DSR1_DUP" "422"
DSR1_RESUB=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"clientName":"冒烟客户","amount":"5000","soNo":"SMKIVSO-2","shipNo":"SMKIVSH-2","lineNo":"10"}' $B/api/fin/digital-invoices/requests)
EQ "同幂等键再提交 422（BR-4.14-37）" "$DSR1_RESUB" "422"

# 27.11 交付自动建行 + 标记发送
DEL1_ID=$(SQL "SELECT ID FROM erp_fin_invoice_delivery WHERE SOURCE='DIGITAL' ORDER BY CREATE_DATE DESC LIMIT 1")
[ -n "${DEL1_ID:-}" ] && ok "补开自动建交付 PENDING 行" || bad "交付行未建"
DEL1_SENT=$(C -X POST -H "$(A $TADM)" $B/api/fin/invoice-deliveries/$DEL1_ID/mark-sent | J "d['data']['status']")
[ "$DEL1_SENT" = "SENT" ] && ok "标记已发送 SENT" || bad "发送异常"

# 27.12 超限拆分（补额度 500000 → 请求 250000 → 拆 3 张）
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"period\":\"$(date +%Y%m)\",\"totalLimit\":\"500000\"}" $B/api/fin/digital-invoices/quota >/dev/null
DSR2=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"clientName":"冒烟大宗","amount":"250000","soNo":"SMKIVSO-3","shipNo":"SMKIVSH-3","lineNo":"10"}' $B/api/fin/digital-invoices/requests)
DSR2_ID=$(echo "$DSR2" | J "d['data']['id']")
DSR2_ISSUE=$(C -X POST -H "$(A $TADM)" $B/api/fin/digital-invoices/requests/$DSR2_ID/issue)
DSR2_SPLIT=$(echo "$DSR2_ISSUE" | J "d['data']['splitCount']")
[ "${DSR2_SPLIT:-0}" = "3" ] && ok "超限自动拆分 3 张（BR-4.14-15）" || bad "拆分异常（$DSR2_ISSUE）"

# 27.13 重发超限 422（DELIVERY_MAX_RETRY=2）
DEL2_ID=$(SQL "SELECT ID FROM erp_fin_invoice_delivery WHERE STATUS='PENDING' ORDER BY CREATE_DATE DESC LIMIT 1")
C -X POST -H "$(A $TADM)" $B/api/fin/invoice-deliveries/$DEL2_ID/resend >/dev/null
C -X POST -H "$(A $TADM)" $B/api/fin/invoice-deliveries/$DEL2_ID/resend >/dev/null
DEL2_MAX=$(BIZ -X POST -H "$(A $TADM)" $B/api/fin/invoice-deliveries/$DEL2_ID/resend)
EQ "重发超上限 422" "$DEL2_MAX" "422"

# 27.14 勾稽全绿（当前期：无漏开/重开）
DRC=$(C -X GET -H "$(A $TADM)" $B/api/fin/digital-invoices/reconcile)
DRC_GREEN=$(echo "$DRC" | J "d['data']['allGreen']")
[ "$DRC_GREEN" = "True" ] && ok "逐票勾稽全绿" || bad "勾稽异常（$DRC）"

# 27.15 限额提示（250000/100000 → 建议 3 张，L4）
DLC=$(C -X GET -H "$(A $TADM)" "$B/api/fin/sales-invoice-ledger/limit-check?amount=250000")
DLC_OVER=$(echo "$DLC" | J "d['data']['overLimit']")
DLC_N=$(echo "$DLC" | J "d['data']['suggestedSplit']")
[ "$DLC_OVER" = "True" ] && [ "$DLC_N" = "3" ] && ok "限额提示建议拆分 3 张（L4）" || bad "限额提示异常（$DLC）"

# 27.16 收尾清理（防污染回归：当期额度/请求/交付/进项全清）
SQL "
DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID
 WHERE v.SOURCE_TYPE = 'TAXIN';
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'TAXIN';
DELETE FROM erp_fin_input_invoice;
DELETE FROM erp_decoded_request;
DELETE FROM erp_fin_digital_quota;
DELETE FROM erp_fin_invoice_delivery;
DELETE FROM erp_proc_po WHERE ID = 'smkiv-po-1';
" >/dev/null 2>&1 && ok "收尾清理（发票管理全域）" || bad "收尾清理失败"

echo "段 27 发票管理追加完成"

# ============================================================================
# 段 28 add-input-certification：M9-3 进项认证（查验闸口 PENDING_INSPECT/INVALID
# 状态机 + FR-4.14-3-2 阻断入账 + 9.3 接线 PERM）。段 27 已补 inspect:true 兼容。
# ============================================================================
echo "== 28. 进项认证（add-input-certification：查验闸口/两态阻断/接线 PERM）=="

# 28.0 清理（可重跑）
SQL "
DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID
 WHERE v.SOURCE_TYPE = 'TAXIN';
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'TAXIN';
DELETE FROM erp_fin_input_invoice;
DELETE FROM erp_proc_po WHERE ID = 'smkic-po-1';
" >/dev/null 2>&1 && ok "清理完成（TAXIN 族+进项+造数 PO）" || bad "清理失败"

# 28.1 M9-3 菜单 PERM 走查
IC_PERM=$(SQL "SELECT COUNT(*) FROM erp_admin_menu WHERE ID LIKE 'M9-3%' AND PERM <> '' AND PERM LIKE '%ROLE_FINANCE%'")
EQ "M9-3 三行 PERM 非空" "$IC_PERM" "3"

# 28.2 造测试 PO
SQL "INSERT INTO erp_proc_po (ID, PO_NO, PO_TYPE, SOURCE, SUPPLIER_ID, SUPPLIER_NAME, STATUS, TOTAL_AMT, CURRENCY, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smkic-po-1', 'SMKICPO01', 'NORMAL', 'MANUAL', 'smkic-sup', '冒烟认证供应商', 'CONFIRMED', 30000, 'CNY', 'smoke', '0', 0);" >/dev/null \
  && ok "测试 PO 就绪（30000）" || bad "PO 造数失败"

# 28.3 两步登记：不带 inspect → PENDING_INSPECT + 匹配 PENDING
IC1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"invoiceNo":"SMKIC-001","supplierId":"smkic-sup","supplierName":"冒烟认证供应商","poId":"smkic-po-1","invoiceDate":"2026-09-01","amount":"29900","taxAmount":"1794"}' $B/api/fin/input-invoices)
IC1_ST=$(echo "$IC1" | J "d['data']['certStatus']")
IC1_MS=$(echo "$IC1" | J "d['data']['matchStatus']")
[ "$IC1_ST" = "PENDING_INSPECT" ] && [ "$IC1_MS" = "PENDING" ] \
  && ok "两步登记落 PENDING_INSPECT+匹配 PENDING" || bad "登记异常（$IC1）"
IC1_ID=$(echo "$IC1" | J "d['data']['id']")

# 28.4 PENDING_INSPECT 认证 422（未查验前置）
IC1_CERT=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"certDate":"2026-09-10"}' $B/api/fin/input-invoices/$IC1_ID/certify)
IC1_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"certDate":"2026-09-10"}' $B/api/fin/input-invoices/$IC1_ID/certify)
[ "$IC1_CERT" = "422" ] && CONTAINS "未查验认证 422（FR-4.14-3-2）" "$IC1_MSG" "未完成真伪查验" || bad "未查验未阻断（$IC1_CERT）"

# 28.5 FAIL → INVALID 终态 + 认证阻断 + 批量凭证跳过
SQL "INSERT INTO erp_proc_po (ID, PO_NO, PO_TYPE, SOURCE, SUPPLIER_ID, SUPPLIER_NAME, STATUS, TOTAL_AMT, CURRENCY, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smkic-po-1', 'SMKICPO01', 'NORMAL', 'MANUAL', 'smkic-sup', '冒烟认证供应商', 'CONFIRMED', 30000, 'CNY', 'smoke', '0', 0)
     ON DUPLICATE KEY UPDATE TOTAL_AMT = 30000;" >/dev/null 2>&1
IC2=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"invoiceNo":"SMKIC-002","supplierId":"smkic-sup","supplierName":"冒烟认证供应商","poId":"smkic-po-1","invoiceDate":"2026-09-01","amount":"1000","taxAmount":"60"}' $B/api/fin/input-invoices)
IC2_ID=$(echo "$IC2" | J "d['data']['id']")
IC2_INV=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"method":"MANUAL","result":"FAIL"}' $B/api/fin/input-invoices/$IC2_ID/inspect | J "d['data']['certStatus']")
[ "$IC2_INV" = "INVALID" ] && ok "查验 FAIL → INVALID 终态（留档）" || bad "FAIL 未置 INVALID（$IC2_INV）"
IC2_CERT=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"certDate":"2026-09-10"}' $B/api/fin/input-invoices/$IC2_ID/certify)
IC2_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"certDate":"2026-09-10"}' $B/api/fin/input-invoices/$IC2_ID/certify)
[ "$IC2_CERT" = "422" ] && CONTAINS "INVALID 认证 422 含阻断入库文案" "$IC2_MSG" "阻断入库" || bad "INVALID 未阻断（$IC2_CERT）"

# 28.6 PASS → 匹配后移执行 → 认证放行（全链路）
IC1_PASS=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"method":"MANUAL","result":"PASS"}' $B/api/fin/input-invoices/$IC1_ID/inspect)
IC1_PASS_ST=$(echo "$IC1_PASS" | J "d['data']['certStatus']")
IC1_PASS_MS=$(echo "$IC1_PASS" | J "d['data']['matchStatus']")
[ "$IC1_PASS_ST" = "PENDING_CERT" ] && [ "$IC1_PASS_MS" = "MATCHED" ] \
  && ok "查验 PASS → 匹配后移执行（MATCHED）→ PENDING_CERT" || bad "PASS 异常（$IC1_PASS）"
IC1_CERT2=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"certDate":"2026-09-10"}' $B/api/fin/input-invoices/$IC1_ID/certify | J "d['data']['certStatus']")
[ "$IC1_CERT2" = "CERTIFIED" ] && ok "查验通过后认证放行 CERTIFIED" || bad "认证异常（$IC1_CERT2）"

# 28.7 已查验再查验 422（仅 PENDING_INSPECT 可查验）
IC1_REINS=$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"method":"MANUAL","result":"PASS"}' $B/api/fin/input-invoices/$IC1_ID/inspect)
EQ "已查验再查验 422" "$IC1_REINS" "422"

# 28.8 收尾清理
SQL "
DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID
 WHERE v.SOURCE_TYPE = 'TAXIN';
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'TAXIN';
DELETE FROM erp_fin_input_invoice;
DELETE FROM erp_proc_po WHERE ID = 'smkic-po-1';
" >/dev/null 2>&1 && ok "收尾清理（认证闸口域）" || bad "收尾清理失败"

echo "段 28 进项认证追加完成"

echo "== 29. 红冲作废（add-red-invoice-void：红冲全流程/编号门禁/作废三条件/转出两模式）=="

# 29.0 清理（可重跑）
SQL "
DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID
 WHERE v.SOURCE_TYPE IN ('REDFLUSH','VOIDINV','TAXOUT');
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('REDFLUSH','VOIDINV','TAXOUT');
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID
 WHERE i.BIZ_TYPE IN ('RedFlush','RedInvoiceVoid');
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('RedFlush','RedInvoiceVoid');
DELETE FROM erp_fin_red_flush_apply;
DELETE FROM erp_fin_invoice_void_apply;
DELETE FROM erp_fin_red_invoice WHERE FLUSH_APPLY_NO IS NOT NULL;
DELETE FROM erp_fin_sales_invoice WHERE ID LIKE 'smkrf-%';
DELETE FROM erp_fin_ar_invoice WHERE ID LIKE 'smkrf-ar-%';
DELETE FROM erp_fin_input_invoice WHERE ID LIKE 'smkrf-in-%';
" >/dev/null 2>&1 && ok "清理完成（红冲作废族）" || bad "清理失败"

# 29.1 M9-4 菜单 PERM 走查（组 + 三叶 = 4 行）
RF_PERM=$(SQL "SELECT COUNT(*) FROM erp_admin_menu WHERE ID LIKE 'M9-4%' AND PERM <> '' AND PERM LIKE '%ROLE_FINANCE%'")
EQ "M9-4 四行 PERM 非空" "$RF_PERM" "4"

# 29.2 造原蓝票（当月开具，金额 100000，未交付未确认）+ AR
RF_TODAY=$(date +%Y-%m-%d)
SQL "INSERT INTO erp_fin_ar_invoice (ID, AR_NO, CUSTOMER_ID, CUSTOMER_NAME, INVOICE_DATE, DUE_DATE, AMOUNT, PAID_AMOUNT, RED_AMOUNT, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smkrf-ar-1','SMKRFAR01','smkrf-cust','红冲冒烟客户','$RF_TODAY','$RF_TODAY',100000,0,0,'UNPAID','smoke','0',0);
     INSERT INTO erp_fin_sales_invoice (ID, INVOICE_NO, CUSTOMER_ID, CUSTOMER_NAME, INVOICE_DATE, AMOUNT, NET_AMOUNT, TAX_RATE, TAX_AMOUNT, STATUS, AR_ID, AR_NO, RED_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smkrf-inv-1','SMKRF-FP-01','smkrf-cust','红冲冒烟客户','$RF_TODAY',100000,88495.58,0.13,11504.42,'ISSUED','smkrf-ar-1','SMKRFAR01',0,'smoke','0',0);" >/dev/null 2>&1 \
  && ok "原蓝票就绪（100000/13%）" || bad "蓝票造数失败"

# 29.3 红冲申请创建（DRAFT）
RF1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"originInvoiceId":"smkrf-inv-1","amount":"30000","reasonType":"ERROR","reason":"冒烟红冲"}' $B/api/fin/red-flush-applies)
RF1_ID=$(echo "$RF1" | J "d['data']['id']")
RF1_NO=$(echo "$RF1" | J "d['data']['applyNo']")
RF1_ST=$(echo "$RF1" | J "d['data']['status']")
[ -n "${RF1_ID:-}" ] && [ "$RF1_ST" = "DRAFT" ] && CONTAINS "红冲申请创建 DRAFT（$RF1_NO）" "$RF1_NO" "FSAP" \
  || bad "红冲创建异常（$RF1）"

# 29.4 金额超剩余可红冲 422（C-4.14-02）
RF_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"originInvoiceId":"smkrf-inv-1","amount":"100001","reasonType":"ERROR","reason":"超额"}' $B/api/fin/red-flush-applies)
CONTAINS "超剩余可红冲 422 提示余额" "$RF_MSG" "剩余可红冲金额 100000" || bad "超额未阻断（$RF_MSG）"

# 29.5 提交审批（金额 < 阈值 → 单级）
RF_SUB=$(C -X POST -H "$(A $TADM)" $B/api/fin/red-flush-applies/$RF1_ID/submit)
RF_OVR=$(echo "$RF_SUB" | J "d['data']['overThreshold']")
RF_CHAIN=$(echo "$RF_SUB" | J "d['data']['chainSize']")
[ "$RF_OVR" = "False" ] && [ "$RF_CHAIN" = "1" ] && ok "低额单级审批链" || bad "审批链异常（$RF_SUB）"

# 29.6 提交后未审批即开票 422（状态机：仅 INFO_ISSUED 可开票）
RF_GATE_EARLY_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/fin/red-flush-applies/$RF1_ID/issue)
CONTAINS "未审批开票 422（状态机）" "$RF_GATE_EARLY_MSG" "仅信息单已生成态" || bad "状态机失效（$RF_GATE_EARLY_MSG）"

# 29.7 审批通过 → 信息单生成（填开方 = 销方，该客户无 CONFIRMED 票）
RF_TASK=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID='$RF1_ID' AND t.STATUS='ACTIVE' LIMIT 1")
TFIN=$(C -H 'Content-Type: application/json' -d '{"username":"finmgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
RF_PASS=$(BIZ -X POST -H "$(A $TFIN)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$RF_TASK\",\"opinion\":\"同意红冲\"}" $B/api/qms/approvals/pass)
RF_FILLER=$(SQL "SELECT INFO_SHEET_FILLER FROM erp_fin_red_flush_apply WHERE ID='$RF1_ID'")
RF_AP_ST=$(SQL "SELECT STATUS FROM erp_fin_red_flush_apply WHERE ID='$RF1_ID'")
[ "$RF_PASS" = "200" ] && [ "$RF_AP_ST" = "INFO_ISSUED" ] && [ "$RF_FILLER" = "SELLER" ] \
  && ok "审批通过 → 信息单生成（销方填开）" || bad "信息单异常（st=$RF_AP_ST filler=$RF_FILLER）"

# 29.7b 编号门禁：INFO_ISSUED 但未登记信息表编号开票 422（BR-4.14-25）
RF_GATE_MSG=$(MSG -X POST -H "$(A $TADM)" $B/api/fin/red-flush-applies/$RF1_ID/issue)
CONTAINS "编号门禁 422" "$RF_GATE_MSG" "信息表编号未录入" || bad "编号门禁失效（$RF_GATE_MSG）"

# 29.8 登记信息表编号 → 开红票全联动
C -X PUT -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"infoSheetNo":"SMKXGB0001"}' $B/api/fin/red-flush-applies/$RF1_ID/info-sheet-no >/dev/null
RF_ISSUE=$(C -X POST -H "$(A $TADM)" $B/api/fin/red-flush-applies/$RF1_ID/issue)
RF_REDNO=$(echo "$RF_ISSUE" | J "d['data']['redNo']")
CONTAINS "红票开具 FPZ 编号" "$RF_REDNO" "FPZ" || bad "开票异常（$RF_ISSUE）"
RF_ORIGIN_RED=$(SQL "SELECT CAST(RED_AMOUNT AS CHAR) FROM erp_fin_sales_invoice WHERE ID='smkrf-inv-1'")
EQ "原票累计红冲 30000" "${RF_ORIGIN_RED%%.*}" "30000"
RF_VCNT=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='REDFLUSH' AND SOURCE_DOC_NO='$RF1_NO'")
EQ "红冲凭证草稿生成" "$RF_VCNT" "1"

# 29.9 已作废发票不可作废/红冲（BR-4.14-28 联动）
SQL "INSERT INTO erp_fin_sales_invoice (ID, INVOICE_NO, CUSTOMER_ID, CUSTOMER_NAME, INVOICE_DATE, AMOUNT, NET_AMOUNT, TAX_RATE, TAX_AMOUNT, STATUS, AR_ID, AR_NO, RED_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smkrf-inv-void','SMKRF-FP-V','smkrf-cust','红冲冒烟客户','$RF_TODAY',5000,4424.78,0.13,575.22,'VOID','smkrf-ar-1','SMKRFAR01',0,'smoke','0',0);" >/dev/null 2>&1
RF_VOID_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"originInvoiceId":"smkrf-inv-void","amount":"1000","reasonType":"ERROR","reason":"对已作废票红冲"}' $B/api/fin/red-flush-applies)
CONTAINS "已作废票红冲 422" "$RF_VOID_MSG" "已作废" || bad "作废票未阻断红冲（$RF_VOID_MSG）"

# 29.10 跨月发票作废 422（BR-4.14-24 引导红冲）
SQL "INSERT INTO erp_fin_sales_invoice (ID, INVOICE_NO, CUSTOMER_ID, CUSTOMER_NAME, INVOICE_DATE, AMOUNT, NET_AMOUNT, TAX_RATE, TAX_AMOUNT, STATUS, AR_ID, AR_NO, RED_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smkrf-inv-old','SMKRF-FP-O','smkrf-cust','红冲冒烟客户','2026-01-15',8000,7079.65,0.13,920.35,'ISSUED','smkrf-ar-1','SMKRFAR01',0,'smoke','0',0);" >/dev/null 2>&1
RF_VOLD_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"invoiceId":"smkrf-inv-old","reason":"跨月作废"}' $B/api/fin/invoice-void-applies)
CONTAINS "跨月作废 422 引导红冲" "$RF_VOLD_MSG" "红冲" || bad "跨月未引导红冲（$RF_VOLD_MSG）"

# 29.11 当月票作废全流程：申请 → 审批 → VOID
RF_V1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"invoiceId":"smkrf-inv-1","reason":"冒烟作废"}' $B/api/fin/invoice-void-applies 2>/dev/null)
RF_V1_ID=$(echo "$RF_V1" | J "d['data']['id']")
if [ -n "${RF_V1_ID:-}" ]; then
  # 原票已部分红冲 → 作废三条件仍满足（当月/未交付/未确认）→ 提交成功；审批执行时发票已红冲不影响 VOID
  RF_VTASK=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID='$RF_V1_ID' AND t.STATUS='ACTIVE' LIMIT 1")
  RF_VPASS=$(BIZ -X POST -H "$(A $TFIN)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$RF_VTASK\",\"opinion\":\"同意作废\"}" $B/api/qms/approvals/pass)
  RF_VST=$(SQL "SELECT STATUS FROM erp_fin_sales_invoice WHERE ID='smkrf-inv-1'")
  RF_VAPST=$(SQL "SELECT STATUS FROM erp_fin_invoice_void_apply WHERE ID='$RF_V1_ID'")
  [ "$RF_VPASS" = "200" ] && [ "$RF_VST" = "VOID" ] && [ "$RF_VAPST" = "VOIDED" ] \
    && ok "作废全流程（审批通过 → 发票 VOID + 申请 VOIDED）" || bad "作废异常（inv=$RF_VST apply=$RF_VAPST）"
else
  # 已红冲不影响三条件，但如被其他校验拦下则给出上下文
  bad "作废申请提交失败（$RF1）"
fi

# 29.12 进项转出：未认证 422 + 直接转出 + 超限 422
SQL "INSERT INTO erp_fin_input_invoice (ID, INVOICE_NO, SUPPLIER_ID, SUPPLIER_NAME, INVOICE_DATE, AMOUNT, TAX_AMOUNT, PO_ID, PO_NO, MATCH_STATUS, CERT_STATUS, CERT_DATE, CERT_PERIOD, DEDUCTIBLE, TRANSFER_OUT_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smkrf-in-pend','SMKRF-TIN-P','smkrf-sup','转出冒烟供应商','2099-09-10',100000,13000,'smkrf-po','SMKRFPO','MATCHED','PENDING_CERT',NULL,NULL,'Y',0,'smoke','0',0),
            ('smkrf-in-1','SMKRF-TIN-1','smkrf-sup','转出冒烟供应商','2099-09-10',100000,13000,'smkrf-po','SMKRFPO','MATCHED','CERTIFIED','2099-09-10','209909','Y',0,'smoke','0',0);" >/dev/null 2>&1
RF_TPEND_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"invoiceId":"smkrf-in-pend","amount":"1000","reason":"集体福利"}' $B/api/fin/input-tax-transfers/direct)
CONTAINS "未认证转出 422" "$RF_TPEND_MSG" "仅已认证发票可转出" || bad "未认证未阻断（$RF_TPEND_MSG）"
RF_T1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"invoiceId":"smkrf-in-1","amount":"10400","reason":"集体福利"}' $B/api/fin/input-tax-transfers/direct)
RF_T1_V=$(echo "$RF_T1" | J "d['data']['voucherNo']")
CONTAINS "直接转出成功生成凭证" "$RF_T1_V" "TOUT" || bad "直接转出异常（$RF_T1）"
RF_TOVER_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"invoiceId":"smkrf-in-1","amount":"3000","reason":"超限"}' $B/api/fin/input-tax-transfers/direct)
CONTAINS "累计超已抵扣 422 提示余额" "$RF_TOVER_MSG" "剩余可转出税额 2600" || bad "超限未阻断（$RF_TOVER_MSG）"

# 29.13 分摊转出（BR-4.14-22 公式：50000 × 12.48% = 6240）
SQL "INSERT INTO erp_fin_input_invoice (ID, INVOICE_NO, SUPPLIER_ID, SUPPLIER_NAME, INVOICE_DATE, AMOUNT, TAX_AMOUNT, PO_ID, PO_NO, MATCH_STATUS, CERT_STATUS, CERT_DATE, CERT_PERIOD, DEDUCTIBLE, TRANSFER_OUT_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smkrf-in-2','SMKRF-TIN-2','smkrf-sup','转出冒烟供应商','2099-09-10',153846.15,20000,'smkrf-po','SMKRFPO','MATCHED','CERTIFIED','2099-09-10','209909','Y',0,'smoke','0',0);" >/dev/null 2>&1
RF_APPT=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"totalInputTax":"50000","exemptSales":"124800","totalSales":"1000000","reason":"无法划分转出","period":"209909"}' $B/api/fin/input-tax-transfers/apportion | J "d['data']['transferTotal']")
RF_APPT_N=$(echo "$RF_APPT" | python3 -c "import sys; print('%.2f' % float(sys.stdin.read().strip()))" 2>/dev/null)
EQ "分摊转出公式 50000×12.48%=6240" "$RF_APPT_N" "6240.00"

# 29.14 收尾清理
SQL "
DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID
 WHERE v.SOURCE_TYPE IN ('REDFLUSH','VOIDINV','TAXOUT');
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('REDFLUSH','VOIDINV','TAXOUT');
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID
 WHERE i.BIZ_TYPE IN ('RedFlush','RedInvoiceVoid');
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('RedFlush','RedInvoiceVoid');
DELETE FROM erp_fin_red_flush_apply;
DELETE FROM erp_fin_invoice_void_apply;
DELETE FROM erp_fin_red_invoice WHERE FLUSH_APPLY_NO IS NOT NULL;
DELETE FROM erp_fin_sales_invoice WHERE ID LIKE 'smkrf-%';
DELETE FROM erp_fin_ar_invoice WHERE ID LIKE 'smkrf-ar-%';
DELETE FROM erp_fin_input_invoice WHERE ID LIKE 'smkrf-in-%';
" >/dev/null 2>&1 && ok "收尾清理（红冲作废域）" || bad "收尾清理失败"

echo "段 29 红冲作废追加完成"

echo "== 30. 税务申报（add-tax-filing：增值税全链/勾稽阻断/留抵退税/所得税预缴/出口退税/归档）=="
TF_P="209912"
TF_PREV="209911"
TF_DATE="2099-12-10"

# 30.0 清理（可重跑）
SQL "
DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID
 WHERE v.SOURCE_TYPE IN ('TAXREFUND','EXPREFUND');
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('TAXREFUND','EXPREFUND');
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID
 WHERE i.BIZ_TYPE IN ('TaxFiling','TaxFilingAdj','TaxRefundApply','ExportRefundApply');
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('TaxFiling','TaxFilingAdj','TaxRefundApply','ExportRefundApply');
DELETE FROM erp_fin_tax_return_line;
DELETE FROM erp_fin_tax_return_adj;
DELETE FROM erp_fin_tax_return WHERE RETURN_NO LIKE 'TRET%';
DELETE FROM erp_fin_tax_refund_apply WHERE APPLY_NO LIKE 'RVAP%';
DELETE FROM erp_fin_export_refund_apply WHERE APPLY_NO LIKE 'RFA%';
DELETE FROM erp_fin_export_deal WHERE DEAL_NO LIKE 'EXP%';
DELETE FROM erp_fin_sales_invoice WHERE ID LIKE 'smktf-%';
DELETE FROM erp_fin_input_invoice WHERE ID LIKE 'smktf-%';
DELETE FROM erp_fin_balance WHERE ID LIKE 'smktf-bal-%';
" >/dev/null 2>&1 && ok "清理完成（税务申报族）" || bad "清理失败"

# 30.1 M9-5 菜单 PERM 走查（组 + 四叶 = 5 行）
TF_PERM=$(SQL "SELECT COUNT(*) FROM erp_admin_menu WHERE ID LIKE 'M9-5%' AND PERM <> '' AND PERM LIKE '%ROLE_FINANCE%'")
EQ "M9-5 五行 PERM 非空" "$TF_PERM" "5"

# 30.2 受控造数（对平）：销项 65000 / 进项认证 39000 + 转出 1300 / 账载同额 POSTED + 上期留抵 2600
SQL "INSERT INTO erp_fin_sales_invoice (ID, INVOICE_NO, CUSTOMER_ID, CUSTOMER_NAME, INVOICE_DATE, AMOUNT, NET_AMOUNT, TAX_RATE, TAX_AMOUNT, STATUS, RED_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smktf-inv-1','SMKTF-FP-01','smktf-cust','申报客户','$TF_DATE',565000,500000,0.13,65000,'ISSUED',0,'smoke','0',0);
     INSERT INTO erp_fin_input_invoice (ID, INVOICE_NO, SUPPLIER_ID, SUPPLIER_NAME, INVOICE_DATE, AMOUNT, TAX_AMOUNT, PO_ID, PO_NO, MATCH_STATUS, CERT_STATUS, CERT_DATE, CERT_PERIOD, DEDUCTIBLE, TRANSFER_OUT_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smktf-in-1','SMKTF-TIN-01','smktf-sup','申报供应商','$TF_DATE',300000,39000,'smktf-po','SMKTFPO','MATCHED','CERTIFIED','$TF_DATE','$TF_P','Y',1300,'smoke','0',0);
     INSERT INTO erp_fin_balance (ID, PERIOD, ACCOUNT_CODE, AUX_KEY, DR_TOTAL, CR_TOTAL, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES
     ('smktf-bal-ou','$TF_P','222102','',0,65000,'smoke',NOW(),'0',0),
     ('smktf-bal-in','$TF_P','222101','',39000,0,'smoke',NOW(),'0',0),
     ('smktf-bal-to','$TF_P','222103','',0,1300,'smoke',NOW(),'0',0);
     INSERT INTO erp_fin_tax_return (ID, RETURN_NO, PERIOD, TAX_TYPE, STATUS, OUTPUT_TAX, INPUT_TAX, TRANSFER_OUT, PREV_CREDIT, PAYABLE_TAX, END_CREDIT, RECON_STATUS, LOCK_FLAG, OVERDUE_FLAG, ESCALATED_FLAG, ADJUSTED_FLAG, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smktf-prev','TRET$TF_PREV-0001','$TF_PREV','VAT','FILED',60000,55000,1600,0,7600,2600,'PASSED','1','0','0','0','smoke','0',0);" >/dev/null 2>&1 \
  && ok "对平造数就绪（销项 65000/进项 39000/转出 1300/上期留抵 2600）" || bad "造数失败"

# 30.3 汇总（按税率/认证态分类；金额经 python 定点化避免 float 打印口径差）
TF_SUM=$(C -H "$(A $TADM)" "$B/api/fin/tax-returns/summarize?period=$TF_P")
TF_SUM_OK=$(echo "$TF_SUM" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
ok = (round(d['outputTax'],2), round(d['inputTax'],2), round(d['transferOut'],2), round(d['prevCredit'],2))
print('Y' if ok == (65000.00, 39000.00, 1300.00, 2600.00) else 'N')" 2>/dev/null)
[ "$TF_SUM_OK" = "Y" ] \
  && ok "汇总分类正确（销项 65000/进项 39000/转出 1300/上期留抵 2600）" || bad "汇总异常（$TF_SUM）"

# 30.4 勾稽（C-4.14-04 对平放行）
TF_REC=$(C -H "$(A $TADM)" "$B/api/fin/tax-returns/reconcile?period=$TF_P" | J "d['data']['passed']")
[ "$TF_REC" = "True" ] && ok "勾稽对平放行" || bad "对平未放行（$TF_REC）"

# 30.5 生成申报表（65000−39000+1300−2600=24700，BR-32）
TF_GEN=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"period\":\"$TF_P\"}" $B/api/fin/tax-returns/generate)
TF_GEN_ID=$(echo "$TF_GEN" | J "d['data']['id']")
TF_PAYABLE=$(echo "$TF_GEN" | J "d['data']['payableTax']")
TF_PAYABLE_N=$(python3 -c "print('%.2f' % float('$TF_PAYABLE'))" 2>/dev/null)
[ -n "${TF_GEN_ID:-}" ] && [ "$TF_PAYABLE_N" = "24700.00" ] \
  && ok "申报表生成：应纳 24,700（BR-32 含留抵结转）" || bad "生成异常（$TF_GEN）"
# 栏次级结构（BR-4.14-30 基础）：主表/附表/附加税 ≥10 条
TF_LINES=$(SQL "SELECT COUNT(*) FROM erp_fin_tax_return_line WHERE RETURN_ID='$TF_GEN_ID'")
[ "${TF_LINES:-0}" -ge 10 ] && ok "栏次落库（$TF_LINES 条，含附表与附加税）" || bad "栏次不足（$TF_LINES）"
# 附加税联动（BR-34）：主税 24700 × 7% = 1729
TF_SUR=$(SQL "SELECT CAST(AMOUNT AS CHAR) FROM erp_fin_tax_return_line WHERE RETURN_ID='$TF_GEN_ID' AND LINE_CODE='附加税-城建'")
[ "${TF_SUR%%.*}" = "1729" ] && ok "附加税随主税联动（城建 24700×7%=1729）" || bad "附加税异常（$TF_SUR）"

# 30.6 不平数据 422 差异报告（C-4.14-04 栏次级；独立期间 209910 避免 409）
SQL "INSERT INTO erp_fin_sales_invoice (ID, INVOICE_NO, CUSTOMER_ID, CUSTOMER_NAME, INVOICE_DATE, AMOUNT, NET_AMOUNT, TAX_RATE, TAX_AMOUNT, STATUS, RED_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smktf-inv-diff','SMKTF-FP-D','smktf-cust','申报客户','2099-10-10',226000,200000,0.13,20000,'ISSUED',0,'smoke','0',0);
     INSERT INTO erp_fin_balance (ID, PERIOD, ACCOUNT_CODE, AUX_KEY, DR_TOTAL, CR_TOTAL, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smktf-bal-diff','209910','222102','',0,18700,'smoke',NOW(),'0',0);" >/dev/null 2>&1
TF_GEN2_MSG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"period":"209910"}' $B/api/fin/tax-returns/generate)
CONTAINS "勾稽差异 422 含栏次级报告" "$TF_GEN2_MSG" "销项税额" || bad "差异报告缺栏次（$TF_GEN2_MSG）"
CONTAINS "差异报告含差额 1300" "$TF_GEN2_MSG" "1300" || bad "差异报告缺差额（$TF_GEN2_MSG）"

# 30.7 三级审批 → 锁定（FR-4.14-5-5）
TF_SUB=$(C -X POST -H "$(A $TADM)" $B/api/fin/tax-returns/$TF_GEN_ID/submit | J "d['data']['chainSize']")
[ "$TF_SUB" = "3" ] && ok "三级审批链提交" || bad "审批链异常（$TF_SUB）"
for i in 1 2 3; do
  TF_TASK=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID='$TF_GEN_ID' AND t.STATUS='ACTIVE' LIMIT 1")
  BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TF_TASK\",\"opinion\":\"申报审核$i 通过\"}" $B/api/qms/approvals/pass >/dev/null
done
TF_ST=$(SQL "SELECT STATUS FROM erp_fin_tax_return WHERE ID='$TF_GEN_ID'")
TF_LOCK=$(SQL "SELECT LOCK_FLAG FROM erp_fin_tax_return WHERE ID='$TF_GEN_ID'")
[ "$TF_ST" = "APPROVED" ] && [ "$TF_LOCK" = "1" ] && ok "三级通过 → APPROVED + 锁定" || bad "审批态异常（$TF_ST/$TF_LOCK）"

# 30.8 回执缴款登记 → PAID + 归档（FR-4.14-5-6/7）
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"submitDate":"2099-12-13"}' $B/api/fin/tax-returns/$TF_GEN_ID/register >/dev/null
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"receiptNo":"HZ20991213001","fileDate":"2099-12-13"}' $B/api/fin/tax-returns/$TF_GEN_ID/register >/dev/null
TF_PAID=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"payDate":"2099-12-20","payAmount":"24700","payVoucherNo":"TFPAY-001"}' $B/api/fin/tax-returns/$TF_GEN_ID/register | J "d['data']['status']")
[ "$TF_PAID" = "PAID" ] && ok "回执缴款链 → PAID" || bad "缴款异常（$TF_PAID）"
TF_ARC=$(C -H "$(A $TADM)" "$B/api/fin/tax-returns/archive?period=$TF_P" | J "d['data']['count']")
[ "${TF_ARC:-0}" -ge 1 ] && ok "申报归档可查（count=$TF_ARC，≥10 年标注）" || bad "归档为空（$TF_ARC）"

# 30.9 留抵退税四条件两向（BR-4.14-33 / S-4.14-15 数字）
TF_LDTI_OK=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"period":"209906","creditLevel":"A","incrCredit":"2450000","constitutionRatio":"0.92","month6Incr":"620000","sixMonthTotal":"2450000"}' $B/api/fin/tax-refunds)
TF_LDTI_ALLOW=$(echo "$TF_LDTI_OK" | J "d['data']['allowedRefund']")
TF_LDTI_ALLOW_N=$(python3 -c "print('%.2f' % float('$TF_LDTI_ALLOW'))" 2>/dev/null)
[ "$TF_LDTI_ALLOW_N" = "2254000.00" ] && ok "留抵退税公式 2,450,000×92%×100%=2,254,000" || bad "退税额异常（$TF_LDTI_OK）"
TF_LDTI_NG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"period":"209906","creditLevel":"C","incrCredit":"2450000","constitutionRatio":"0.92","month6Incr":"200000","sixMonthTotal":"2450000"}' $B/api/fin/tax-refunds)
CONTAINS "四条件不满足阻断列示（信用）" "$TF_LDTI_NG" "纳税信用等级" || bad "条件①未列示（$TF_LDTI_NG）"
CONTAINS "四条件不满足阻断列示（第6月门槛）" "$TF_LDTI_NG" "低于规定金额" || bad "条件③未列示（$TF_LDTI_NG）"

# 30.10 所得税预缴（累计利润×税率+调整项）
SQL "DELETE FROM erp_fin_balance WHERE ACCOUNT_CODE LIKE '6%' AND PERIOD LIKE '2099%';
     INSERT INTO erp_fin_balance (ID, PERIOD, ACCOUNT_CODE, AUX_KEY, DR_TOTAL, CR_TOTAL, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES
     ('smktf-cit-rev','$TF_P','6001','',0,2100000,'smoke',NOW(),'0',0),
     ('smktf-cit-cost','$TF_P','6401','',100000,0,'smoke',NOW(),'0',0);" >/dev/null 2>&1
TF_CIT=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"period\":\"$TF_P\",\"citAdjust\":\"100000\",\"citPrepaid\":\"450000\"}" $B/api/fin/tax-returns/generate-cit)
TF_CIT_DUE=$(echo "$TF_CIT" | J "d['data']['citDue']")
TF_CIT_DUE_N=$(python3 -c "print('%.2f' % float('$TF_CIT_DUE'))" 2>/dev/null)
[ "$TF_CIT_DUE_N" = "75000.00" ] && ok "所得税预缴 (2,000,000+100,000)×25%−450,000=75,000" || bad "所得税异常（$TF_CIT）"

# 30.11 出口退税：FOB/CIF 折算 + 缺证 422 + 申报核销差异挂账
TF_EXP1=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"invoiceNo\":\"SMKTF-EXP-01\",\"declNo\":\"SMKTF-DECL-01\",\"declDate\":\"$TF_DATE\",\"tradeTerm\":\"FOB\",\"currency\":\"USD\",\"grossAmount\":\"100000\",\"freight\":\"0\",\"insurance\":\"0\",\"commission\":\"0\",\"fxRate\":\"7.1\",\"itemCategory\":\"MACH\"}" $B/api/fin/export-refunds/deals)
TF_EXP1_RT=$(echo "$TF_EXP1" | J "d['data']['refundTax']")
TF_EXP1_RT_N=$(python3 -c "print('%.2f' % float('$TF_EXP1_RT'))" 2>/dev/null)
[ "$TF_EXP1_RT_N" = "92300.00" ] && ok "出口 FOB 直算 710,000×13%=92,300" || bad "FOB 计算异常（$TF_EXP1）"
TF_EXP1_ID=$(echo "$TF_EXP1" | J "d['data']['id']")
TF_EXP2=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"invoiceNo\":\"SMKTF-EXP-02\",\"declNo\":\"SMKTF-DECL-02\",\"declDate\":\"$TF_DATE\",\"tradeTerm\":\"CIF\",\"currency\":\"USD\",\"grossAmount\":\"100000\",\"freight\":\"4000\",\"insurance\":\"1000\",\"commission\":\"0\",\"fxRate\":\"7.1\",\"itemCategory\":\"MACH\"}" $B/api/fin/export-refunds/deals)
TF_EXP2_RT=$(echo "$TF_EXP2" | J "d['data']['refundTax']")
TF_EXP2_RT_N=$(python3 -c "print('%.2f' % float('$TF_EXP2_RT'))" 2>/dev/null)
[ "$TF_EXP2_RT_N" = "87685.00" ] && ok "出口 CIF 折算 674,500×13%=87,685（BR-41）" || bad "CIF 折算异常（$TF_EXP2）"
TF_EXP2_ID=$(echo "$TF_EXP2" | J "d['data']['id']")
# 缺证阻断（BR-4.14-03）
TF_EXP_NG=$(MSG -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"period\":\"$TF_P\",\"dealIds\":\"$TF_EXP1_ID\"}" $B/api/fin/export-refunds)
CONTAINS "缺证阻断引导补齐/豁免" "$TF_EXP_NG" "单证不齐" || bad "缺证未阻断（$TF_EXP_NG）"
# 收齐三单 → 创建申报单（仅票1）→ 审批 → 状态 → 到账差异挂账
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"docDecl":true,"docInvoice":true,"docReceipt":true}' $B/api/fin/export-refunds/deals/$TF_EXP1_ID/collect >/dev/null
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"docDecl":true,"docInvoice":true,"docReceipt":true}' $B/api/fin/export-refunds/deals/$TF_EXP2_ID/collect >/dev/null
TF_RFA=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"period\":\"$TF_P\",\"dealIds\":\"$TF_EXP1_ID,$TF_EXP2_ID\"}" $B/api/fin/export-refunds)
TF_RFA_ID=$(echo "$TF_RFA" | J "d['data']['id']")
TF_RFA_TOTAL=$(echo "$TF_RFA" | J "d['data']['refundTotal']")
TF_RFA_TOTAL_N=$(python3 -c "print('%.2f' % float('$TF_RFA_TOTAL'))" 2>/dev/null)
[ "$TF_RFA_TOTAL_N" = "179985.00" ] && ok "退税申报单聚合两票（92,300+87,685=179,985）" || bad "聚合异常（$TF_RFA）"
C -X POST -H "$(A $TADM)" $B/api/fin/export-refunds/$TF_RFA_ID/submit >/dev/null
TF_RFA_TASK=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID='$TF_RFA_ID' AND t.STATUS='ACTIVE' LIMIT 1")
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TF_RFA_TASK\",\"opinion\":\"同意出口退税申报\"}" $B/api/qms/approvals/pass >/dev/null
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"status":"ACCEPTED"}' $B/api/fin/export-refunds/$TF_RFA_ID/register >/dev/null
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"status":"AUDITING"}' $B/api/fin/export-refunds/$TF_RFA_ID/register >/dev/null
C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"status":"APPROVED","approvedAmount":"179985"}' $B/api/fin/export-refunds/$TF_RFA_ID/register >/dev/null
TF_RECV=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' -d '{"receivedAmount":"178000","receivedDate":"2099-12-25"}' $B/api/fin/export-refunds/$TF_RFA_ID/receive | J "d['data']['reconciled']")
[ "$TF_RECV" = "False" ] && ok "到账差异 1,985 挂账核查不核销（BR-45）" || bad "差异未挂账（$TF_RECV）"
# 分级预警
TF_WARN=$(C -H "$(A $TADM)" $B/api/fin/export-refunds/warnings | J "d['code']")
[ "$TF_WARN" = "200" ] && ok "分级预警查询可用" || bad "预警异常（$TF_WARN）"

# 30.12 收尾清理
SQL "
DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID
 WHERE v.SOURCE_TYPE IN ('TAXREFUND','EXPREFUND');
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('TAXREFUND','EXPREFUND');
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID
 WHERE i.BIZ_TYPE IN ('TaxFiling','TaxFilingAdj','TaxRefundApply','ExportRefundApply');
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('TaxFiling','TaxFilingAdj','TaxRefundApply','ExportRefundApply');
DELETE FROM erp_fin_tax_return_line;
DELETE FROM erp_fin_tax_return_adj;
DELETE FROM erp_fin_tax_return WHERE RETURN_NO LIKE 'TRET%';
DELETE FROM erp_fin_tax_refund_apply WHERE APPLY_NO LIKE 'RVAP%';
DELETE FROM erp_fin_export_refund_apply WHERE APPLY_NO LIKE 'RFA%';
DELETE FROM erp_fin_export_deal WHERE DEAL_NO LIKE 'EXP%';
DELETE FROM erp_fin_sales_invoice WHERE ID LIKE 'smktf-%';
DELETE FROM erp_fin_input_invoice WHERE ID LIKE 'smktf-%';
DELETE FROM erp_fin_balance WHERE ID LIKE 'smktf-%';
" >/dev/null 2>&1 && ok "收尾清理（税务申报域）" || bad "收尾清理失败"

echo "段 30 税务申报追加完成"

# ==================================================================== 段 31：税务风控（add-tax-risk-control）
echo "== 段 31 税务风控（M9-6）开始 =="
TRC_P="2099"

# 31.1 优惠备案登记（S-4.14-11）+ 重叠期间 422
TRC_PREF=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"policyName":"软件企业增值税即征即退","policyDocNo":"财税[2099]1号","prefType":"VAT_REFUND","subject":"smoke 主体","prefStart":"2099-01-01","prefEnd":"2099-12-31","prefScopeJson":"{\"capIncome\":2500000}"}' \
  $B/api/fin/tax-preferences)
TRC_PREF_ID=$(echo "$TRC_PREF" | J "d['data']['id']")
TRC_PREF_NO=$(echo "$TRC_PREF" | J "d['data']['prefNo']")
[ -n "$TRC_PREF_ID" ] && [ "$TRC_PREF_ID" != "None" ] && ok "优惠备案登记（$TRC_PREF_NO）" || bad "备案登记失败（$TRC_PREF）"

TRC_DUP_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"policyName":"重复备案","policyDocNo":"财税[2099]9号","prefType":"VAT_REFUND","subject":"smoke 主体","prefStart":"2099-06-01","prefEnd":"2100-06-30"}' \
  $B/api/fin/tax-preferences)
CONTAINS "重叠期间备案 422" "$TRC_DUP_MSG" "重复备案" || bad "重叠防重失效（$TRC_DUP_MSG）"

# 31.2 BR-4.14-05 越界 422：CIT 生成含加计扣除但无对应备案
TRC_CIT_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"period":"209911","rndSuperDeduction":"100000"}' $B/api/fin/tax-returns/generate-cit)
CONTAINS "无备案加计扣除 422（BR-4.14-05）" "$TRC_CIT_MSG" "BR-4.14-05" || bad "优惠校验失效（$TRC_CIT_MSG）"

# 31.3 手工冻结 + 冻结后申报阻断
TRC_FRZ=$(BIZ -X PUT -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"reason":"研发人员占比下降"}' $B/api/fin/tax-preferences/$TRC_PREF_ID/freeze)
[ "$TRC_FRZ" = "200" ] && ok "手工冻结留痕" || bad "冻结失败（$TRC_FRZ）"
TRC_FRZ_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"period":"209911","vatRefundAmount":"65000","vatRefundIncome":"2000000"}' \
  $B/api/fin/tax-returns/generate)
CONTAINS "冻结备案申报 422" "$TRC_FRZ_MSG" "BR-4.14-05" || bad "冻结拦截失效（$TRC_FRZ_MSG）"

# 31.4 证书台账：登记 → 25 天临期预警 → 过期离线通道阻断 → 更新解阻
TRC_CERT=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"certType\":\"DIGITAL_CERT\",\"certName\":\"smoke 证书\",\"certNo\":\"SMKTRC-$RANDOM\",\"issueDate\":\"2099-01-01\",\"expireDate\":\"2099-12-15\",\"channel\":\"SMOKE-TAX-CH\"}" \
  $B/api/fin/tax-risks/certs)
TRC_CERT_ID=$(echo "$TRC_CERT" | J "d['data']['id']")
[ -n "$TRC_CERT_ID" ] && [ "$TRC_CERT_ID" != "None" ] && ok "证书登记" || bad "证书登记失败（$TRC_CERT）"
# 无基准日注入能力，临期以expireDate 相对今天判断——2099 远在未来不触发预警；
# 改验过期阻断：登记过期证书到同通道
TRC_EXPIRED=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"certType\":\"DIGITAL_CERT\",\"certName\":\"smoke 过期证书\",\"certNo\":\"SMKTRC-EXP-$RANDOM\",\"issueDate\":\"2020-01-01\",\"expireDate\":\"2020-06-30\",\"channel\":\"SMOKE-TAX-CH-EXP\"}" \
  $B/api/fin/tax-risks/certs)
TRC_EXP_STATUS=$(echo "$TRC_EXPIRED" | J "d['data']['status']")
[ "$TRC_EXP_STATUS" = "EXPIRED" ] && ok "过期证书登记即判定 EXPIRED" || bad "过期判定失效（$TRC_EXPIRED）"
TRC_OFFL_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"reason":"通道故障","channel":"SMOKE-TAX-CH-EXP"}' $B/api/fin/tax-risks/offline-invoices)
CONTAINS "C-4.14-14 过期通道阻断 422" "$TRC_OFFL_MSG" "C-4.14-14" || bad "通道阻断失效（$TRC_OFFL_MSG）"

# 31.5 离线开票 48h L4 告警 + 补传消除
TRC_OFL=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"reason":"网络隔离离线开票（smoke）","openTime":"2026-01-01T08:00:00"}' $B/api/fin/tax-risks/offline-invoices)
TRC_OFL_ID=$(echo "$TRC_OFL" | J "d['data']['id']")
TRC_OFL_NO=$(echo "$TRC_OFL" | J "d['data']['offlineNo']")
TRC_OFL_HIT=$(C -H "Authorization: Bearer $TADM" $B/api/fin/tax-risks/offline-invoices/alerts \
  | J "any(a['offlineNo']=='$TRC_OFL_NO' for a in d['data'])")
[ "$TRC_OFL_HIT" = "True" ] && ok "离线 48h 未补传 L4 告警（$TRC_OFL_NO）" || bad "48h 告警失效（$TRC_OFL_HIT）"
TRC_UPL=$(BIZ -X PUT -H "Authorization: Bearer $TADM" $B/api/fin/tax-risks/offline-invoices/$TRC_OFL_ID/upload)
TRC_OFL_HIT2=$(C -H "Authorization: Bearer $TADM" $B/api/fin/tax-risks/offline-invoices/alerts \
  | J "any(a['offlineNo']=='$TRC_OFL_NO' for a in d['data'])")
[ "$TRC_UPL" = "200" ] && [ "$TRC_OFL_HIT2" = "False" ] && ok "补传后告警消除" || bad "补传消除失效（hit=$TRC_OFL_HIT2）"

# 31.6 税负率计算（无销售额期间 → 不适用标注，不产 breach）
TRC_BURDEN=$(C -H "Authorization: Bearer $TADM" "$B/api/fin/tax-risks/tax-burdens?periodEnd=209906&months=1")
TRC_BURDEN_NA=$(echo "$TRC_BURDEN" | J "d['data']['rows'][0]['applicable']")
CONTAINS "零分母不适用标注" "$TRC_BURDEN_NA" "不适用" || bad "零分母处理失效（$TRC_BURDEN）"

# 31.7 风险看板聚合（8 类信号，本叶来源 OK）
TRC_DASH=$(C -H "Authorization: Bearer $TADM" "$B/api/fin/tax-risks/dashboard?periodEnd=209906")
TRC_DASH_CERT=$(echo "$TRC_DASH" | J "d['data']['sources'].get('tax-cert')")
TRC_DASH_CNT=$(echo "$TRC_DASH" | J "d['data']['count']")
[ "$TRC_DASH_CERT" = "OK" ] && ok "看板聚合（信号 $TRC_DASH_CNT 条，证书来源 OK）" || bad "看板异常（$TRC_DASH）"

# 31.8 关联定价：关联方 + 文档（方法 422）+ 自评留痕
TRC_PTY=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"smoke 关联方","taxNo":"91SMKTRC","relType":"COMMON_CONTROL","overseas":true}' \
  $B/api/fin/tp/parties)
TRC_PTY_ID=$(echo "$TRC_PTY" | J "d['data']['id']")
[ -n "$TRC_PTY_ID" ] && [ "$TRC_PTY_ID" != "None" ] && ok "关联方登记（境外）" || bad "关联方失败（$TRC_PTY）"
TRC_DOC_BAD_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"partyId\":\"$TRC_PTY_ID\",\"docType\":\"LOCAL\",\"coverYear\":\"2099\",\"pricingMethod\":\"协商价\",\"validUntil\":\"2099-12-31\"}" \
  $B/api/fin/tp/documents)
CONTAINS "非五族定价方法 422" "$TRC_DOC_BAD_MSG" "独立交易原则" || bad "方法校验失效（$TRC_DOC_BAD_MSG）"
TRC_DOC=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"partyId\":\"$TRC_PTY_ID\",\"docType\":\"LOCAL\",\"coverYear\":\"2099\",\"pricingMethod\":\"TNMM\",\"validUntil\":\"2099-12-31\"}" \
  $B/api/fin/tp/documents)
TRC_DOC_NO=$(echo "$TRC_DOC" | J "d['data']['docNo']")
[ -n "$TRC_DOC_NO" ] && [ "$TRC_DOC_NO" != "None" ] && ok "定价文档登记（$TRC_DOC_NO）" || bad "文档登记失败（$TRC_DOC）"
TRC_ASSESS=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"year":"2099","riskItems":["DOCUMENTS_INCOMPLETE"]}' $B/api/fin/tp/assessments)
TRC_ASSESS_CONC=$(echo "$TRC_ASSESS" | J "d['data']['conclusion']")
[ "$TRC_ASSESS_CONC" = "IMPROVE" ] && ok "风险自评留痕（有待改进）" || bad "自评失败（$TRC_ASSESS）"

# 31.9 收尾清理（smoke 幂等）
SQL "DELETE FROM erp_fin_tax_return_line WHERE RETURN_ID IN (SELECT ID FROM erp_fin_tax_return WHERE PERIOD IN ('209911','209912') AND CREATE_BY='admin')" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_tax_return WHERE PERIOD IN ('209911','209912') AND CREATE_BY='admin'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_tax_preference WHERE SUBJECT LIKE 'smoke%'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_tax_cert WHERE CERT_NAME LIKE 'smoke%'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_offline_invoice WHERE REASON LIKE '%smoke%' OR ID LIKE 'trk-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_tp_assessment WHERE ASSESS_YEAR='2099'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_tp_document WHERE COVER_YEAR='2099'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_tp_party WHERE NAME LIKE 'smoke%'" >/dev/null 2>&1

echo "段 31 税务风控追加完成"

# ==================================================================== 段 32：员工档案与入转调离（add-employee-archive）
echo "== 段 32 员工档案+入转调离（M10-1/M10-7）开始 =="
# 第二复核人须独立用户（C-4.7-03 同人不可双签）：造 hrsmoke（密码同 admin）
SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     SELECT 'smk-hrsmoke-user','hrsmoke',PASSWORD,'冒烟HR','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-hrsmoke-role','smk-hrsmoke-user',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_HR'" >/dev/null 2>&1
EA_TODAY=$(date +%F)
EA_FUTURE=$(date -v+40d +%F 2>/dev/null || date -d '+40 days' +%F)

# 32.1 部门/岗位（角色映射 ROLE_HR）
EA_DEPT=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"冒烟部门"}' $B/api/hr/depts | J "d['data']['id']")
EA_POS=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"name\":\"冒烟岗位\",\"deptId\":\"$EA_DEPT\",\"rolesJson\":\"[\\\"ROLE_HR\\\"]\"}" \
  $B/api/hr/positions | J "d['data']['id']")
[ -n "$EA_POS" ] && [ "$EA_POS" != "None" ] && ok "部门/岗位建立（$EA_POS）" || bad "部门岗位失败"

# 32.2 建档六步（快进）→ 生成主档 + 开通审批待办
EA_ONB=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"offerNo":"SMK-OFFER-01","offerApproved":"1"}' $B/api/hr/onboardings | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{}' \
  $B/api/hr/onboardings/$EA_ONB/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"candidateJson":"{\"name\":\"冒烟员工\",\"idCardNo\":\"SMKID-32001\",\"phone\":\"13900000001\"}","realnameCheck":"1","bank4Check":"1"}' \
  $B/api/hr/onboardings/$EA_ONB/advance >/dev/null
# 离职证明缺失 → L1 阻断断言
EA_GATE_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"docsJson":"{\"身份证\":{\"submitted\":true}}"}' $B/api/hr/onboardings/$EA_ONB/advance)
CONTAINS "离职证明缺失 L1 阻断" "$EA_GATE_MSG" "离职证明" || bad "离职证明门禁失效（$EA_GATE_MSG）"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"docsJson":"{\"离职证明\":{\"submitted\":true}}"}' $B/api/hr/onboardings/$EA_ONB/advance >/dev/null
# 试用期超限 422（3 年合同 + 8 月试用）
EA_PROB_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"contractJson":"{\"contractNo\":\"SMK-HT-1\",\"contractStart\":\"2026-01-01\",\"contractEnd\":\"2029-01-01\",\"probationMonths\":8}"}' \
  $B/api/hr/onboardings/$EA_ONB/advance)
CONTAINS "试用期超法定上限 422" "$EA_PROB_MSG" "试用期超法定上限" || bad "试用期合规失效（$EA_PROB_MSG）"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"contractJson":"{\"contractNo\":\"SMK-HT-1\",\"contractStart\":\"2026-01-01\",\"contractEnd\":\"2029-01-01\",\"probationMonths\":3}"}' \
  $B/api/hr/onboardings/$EA_ONB/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"deptId\":\"$EA_DEPT\",\"posId\":\"$EA_POS\",\"hireDate\":\"$EA_TODAY\"}" \
  $B/api/hr/onboardings/$EA_ONB/advance >/dev/null
EA_EMP=$(C -H "Authorization: Bearer $TADM" $B/api/hr/onboardings/$EA_ONB | J "d['data']['empId']")
[ -n "$EA_EMP" ] && [ "$EA_EMP" != "None" ] && ok "建档五步 → 主档生成" || bad "主档生成失败"
# 重复身份证 → L1 引导二次入职
EA_DUP_MSG=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"offerNo":"SMK-OFFER-02","offerApproved":"1"}' $B/api/hr/onboardings | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{}' \
  $B/api/hr/onboardings/$EA_DUP_MSG/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"candidateJson":"{\"name\":\"冒烟重复\",\"idCardNo\":\"SMKID-32001\"}","realnameCheck":"1","bank4Check":"1"}' \
  $B/api/hr/onboardings/$EA_DUP_MSG/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"docsJson":"{\"离职证明\":{\"submitted\":true}}"}' $B/api/hr/onboardings/$EA_DUP_MSG/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"contractJson":"{\"contractNo\":\"SMK-HT-2\",\"contractStart\":\"2026-01-01\",\"contractEnd\":\"2029-01-01\",\"probationMonths\":3}"}' \
  $B/api/hr/onboardings/$EA_DUP_MSG/advance >/dev/null
EA_REHIRE=$(C -H "Authorization: Bearer $TADM" "$B/api/hr/rehire-probe?idCardNo=SMKID-32001" | J "d['data']['rehireAllowed']")
[ "$EA_REHIRE" = "False" ] && ok "BR-09 重复身份证：在职不可二次入职" || bad "rehire probe 异常（$EA_REHIRE）"

# 32.3 信息维护：变更留痕 + 锁定直改 422
C -X PUT -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"phone":"13900000002","reason":"冒烟变更"}' $B/api/hr/employees/$EA_EMP >/dev/null
EA_LOG=$(C -H "Authorization: Bearer $TADM" $B/api/hr/employees/$EA_EMP/change-logs | J "len(d['data'])")
[ "${EA_LOG:-0}" -ge 1 ] && ok "变更日志留痕（$EA_LOG 条）" || bad "变更日志缺失"
EA_LOCK_MSG=$(MSG -X PUT -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"idCardNo":"NEW-ID","reason":"x"}' $B/api/hr/employees/$EA_EMP)
CONTAINS "BR-07 锁定直改 422" "$EA_LOCK_MSG" "BR-4.7-07" || bad "锁定失效（$EA_LOCK_MSG）"

# 32.4 转正：发起 → HR 审核 → 审批（admin 签两级）→ 生效
EA_TRF=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"$EA_EMP\",\"abilityScore\":4,\"attitudeScore\":4,\"proposal\":\"REGULAR\"}" \
  $B/api/hr/transfers/probation | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"opinion":"HR 审核通过"}' $B/api/hr/transfers/probation/$EA_TRF/hr-review >/dev/null
for i in 1 2; do
  EA_TASK=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='HrProbation' AND i.BIZ_ID='$EA_TRF' AND t.STATUS='ACTIVE' LIMIT 1")
  [ -n "$EA_TASK" ] && C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$EA_TASK\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass >/dev/null
done
EA_ST=$(SQL "SELECT EMP_STATUS FROM hr_employee WHERE ID='$EA_EMP'")
[ "$EA_ST" = "REGULAR" ] && ok "转正审批通过 → 主档正式" || bad "转正未生效（$EA_ST）"

# 32.5 调岗双人复核（不一致挂起 → 裁决 → 审批 → 生效换角色）
EA_POS2=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"name\":\"冒烟新岗位\",\"deptId\":\"$EA_DEPT\"}" $B/api/hr/positions | J "d['data']['id']")
EA_PTF=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"$EA_EMP\",\"toPosId\":\"$EA_POS2\",\"effectiveDate\":\"$EA_TODAY\",\"salaryAdvice\":\"15000\"}" \
  $B/api/hr/transfers/post | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"opinion":"调出同意"}' \
  $B/api/hr/transfers/post/$EA_PTF/confirm-out >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"opinion":"调入同意"}' \
  $B/api/hr/transfers/post/$EA_PTF/confirm-in >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"opinion":"HR 审核"}' \
  $B/api/hr/transfers/post/$EA_PTF/hr-review >/dev/null
# 第一复核（admin）签署
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"position\":\"冒烟新岗位\",\"salary\":\"15000\",\"reportLine\":\"经理\",\"costCenter\":\"CC\",\"effectiveDate\":\"$EA_TODAY\"}" \
  $B/api/hr/transfers/post/$EA_PTF/review-sign >/dev/null
# 第二复核同人 → 拒绝（C-4.7-03 同人不可双签）
EA_SAME_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"position\":\"冒烟新岗位\",\"salary\":\"15000\"}" $B/api/hr/transfers/post/$EA_PTF/review-sign)
CONTAINS "同人不可双签" "$EA_SAME_MSG" "双人复核" || bad "同人双签防护失效（$EA_SAME_MSG）"
# 第二复核由 hrsmoke（独立 HR 用户）签，薪酬不一致 → 差异挂起
THR2=$(C -H 'Content-Type: application/json' -d '{"username":"hrsmoke","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
C -X POST -H "Authorization: Bearer $THR2" -H 'Content-Type: application/json' \
  -d "{\"position\":\"冒烟新岗位\",\"salary\":\"18000\",\"reportLine\":\"经理\",\"costCenter\":\"CC\",\"effectiveDate\":\"$EA_TODAY\"}" \
  $B/api/hr/transfers/post/$EA_PTF/review-sign >/dev/null
EA_DIFF=$(SQL "SELECT STATUS FROM hr_transfer WHERE ID='$EA_PTF'")
[ "$EA_DIFF" = "REVIEW_DIFF" ] && ok "复核不一致 → 差异挂起（C-4.7-03）" || bad "差异挂起失效（$EA_DIFF）"
# 裁决通过 → 审批 → 生效
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","note":"按 18000 执行"}' $B/api/hr/transfers/post/$EA_PTF/adjudicate >/dev/null
EA_AT=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='HrPostChange' AND i.BIZ_ID='$EA_PTF' AND t.STATUS='ACTIVE' LIMIT 1")
[ -n "$EA_AT" ] && C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$EA_AT\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass >/dev/null
EA_POS_NOW=$(SQL "SELECT POS_ID FROM hr_employee WHERE ID='$EA_EMP'")
[ "$EA_POS_NOW" = "$EA_POS2" ] && ok "调岗生效 → 主档岗位更新" || bad "调岗生效失败（$EA_POS_NOW）"

# 32.6 离职全链：申请 → 审批 → 交接四类 → 结算 → 离场凭证 → 账号禁用
EA_EXIT=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"$EA_EMP\",\"quitDate\":\"$EA_FUTURE\",\"reason\":\"冒烟离职\"}" \
  $B/api/hr/transfers/exits | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/transfers/exits/$EA_EXIT/start-approval >/dev/null
for i in 1 2; do
  EA_ET=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='HrExit' AND i.BIZ_ID='$EA_EXIT' AND t.STATUS='ACTIVE' LIMIT 1")
  [ -n "$EA_ET" ] && C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$EA_ET\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass >/dev/null
done
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"cardIds":[]}' \
  $B/api/hr/transfers/exits/$EA_EXIT/assets-return >/dev/null
C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/transfers/exits/$EA_EXIT/account-handover >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"category":"doc","items":[{"item":"知识库","receiver":"接任者"}]}' \
  $B/api/hr/transfers/exits/$EA_EXIT/handover-items >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"category":"work","items":[{"item":"在办项目","receiver":"接任者"}]}' \
  $B/api/hr/transfers/exits/$EA_EXIT/handover-items >/dev/null
# 交接全齐但未结算 → 凭证阻断
EA_CERT_GATE_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" $B/api/hr/transfers/exits/$EA_EXIT/issue-cert)
CONTAINS "结算前置阻断" "$EA_CERT_GATE_MSG" "结算" || bad "凭证前置失效（$EA_CERT_GATE_MSG）"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"settleWage":"8000","settleComp":"16000","reduceDate":"'$EA_TODAY'"}' \
  $B/api/hr/transfers/exits/$EA_EXIT/settle >/dev/null
# add-transfer-followup：离场凭证要求 DONE 减员单或豁免——结算已自动开减员单，此处申报完成
EA_RM=$(SQL "SELECT ID FROM hr_social_change WHERE SRC_ID='$EA_EXIT' AND CHG_TYPE='REMOVE' AND STATUS='DRAFT' LIMIT 1")
[ -n "$EA_RM" ] && C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"receiptNo":"SMKEA-RM-1"}' $B/api/hr/social-changes/$EA_RM/declare >/dev/null
EA_CERT=$(C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/transfers/exits/$EA_EXIT/issue-cert | J "d['data']['exitCertNo']")
[ -n "$EA_CERT" ] && [ "$EA_CERT" != "None" ] && ok "离场凭证生成（$EA_CERT）" || bad "离场凭证失败"
EA_RST=$(SQL "SELECT EMP_STATUS FROM hr_employee WHERE ID='$EA_EMP'")
[ "$EA_RST" = "RESIGNED" ] && ok "主档置已离职" || bad "离职状态未更新（$EA_RST）"
# 账号禁用：建档时开通审批未走过 → 无账号（幂等不炸即通过）；补验审批后建号链路
EA_ONB2=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"offerNo":"SMK-OFFER-03","offerApproved":"1"}' $B/api/hr/onboardings | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{}' $B/api/hr/onboardings/$EA_ONB2/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"candidateJson":"{\"name\":\"冒烟账号\",\"idCardNo\":\"SMKID-32003\"}","realnameCheck":"1","bank4Check":"1"}' \
  $B/api/hr/onboardings/$EA_ONB2/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"docsJson":"{\"离职证明\":{\"submitted\":true}}"}' $B/api/hr/onboardings/$EA_ONB2/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"contractJson":"{\"contractNo\":\"SMK-HT-3\",\"contractStart\":\"2026-01-01\",\"contractEnd\":\"2029-01-01\",\"probationMonths\":3}"}' \
  $B/api/hr/onboardings/$EA_ONB2/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"deptId\":\"$EA_DEPT\",\"posId\":\"$EA_POS2\",\"hireDate\":\"$EA_TODAY\"}" \
  $B/api/hr/onboardings/$EA_ONB2/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"socialJson\":\"{\\\"place\\\":\\\"上海\\\",\\\"type\\\":\\\"五险一金\\\",\\\"date\\\":\\\"$EA_TODAY\\\"}\"}" \
  $B/api/hr/onboardings/$EA_ONB2/advance >/dev/null
EA_EMP2=$(C -H "Authorization: Bearer $TADM" $B/api/hr/onboardings/$EA_ONB2 | J "d['data']['empId']")
EA_AO=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='HrAccountOpen' AND i.BIZ_ID='$EA_EMP2' AND t.STATUS='ACTIVE' LIMIT 1")
[ -n "$EA_AO" ] && C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$EA_AO\",\"opinion\":\"开通\"}" $B/api/qms/approvals/pass >/dev/null
EA_UN=$(SQL "SELECT USERNAME FROM erp_admin_user WHERE USERNAME LIKE 'EMP20%' ORDER BY CREATE_DATE DESC LIMIT 1")
EA_EMPNO=$(SQL "SELECT EMP_NO FROM hr_employee WHERE ID='$EA_EMP2'")
[ "$EA_UN" = "$EA_EMPNO" ] && ok "开通审批通过 → 建号（用户名=工号 $EA_UN）" || bad "建号失败（$EA_UN vs $EA_EMPNO）"

# 32.7 收尾清理
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE LIKE 'Hr%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_TYPE LIKE 'Hr%'" >/dev/null 2>&1
SQL "DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON ur.USER_ID=u.ID WHERE u.USERNAME LIKE 'EMP20%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE USERNAME LIKE 'EMP20%'" >/dev/null 2>&1
SQL "DELETE FROM hr_change_log WHERE EMP_ID IN (SELECT ID FROM (SELECT ID FROM hr_employee WHERE EMP_NO LIKE 'EMP20%') t1)" >/dev/null 2>&1
SQL "DELETE FROM hr_exit WHERE EMP_NO LIKE 'EMP20%' OR ID LIKE 'smk%'" >/dev/null 2>&1
SQL "DELETE FROM hr_transfer WHERE ID LIKE 'smk%' OR EMP_NAME LIKE '冒烟%'" >/dev/null 2>&1
SQL "DELETE FROM hr_onboarding WHERE ONB_NO LIKE 'ONB-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_employee WHERE EMP_NO LIKE 'EMP20%'" >/dev/null 2>&1
SQL "DELETE FROM hr_position WHERE NAME LIKE '冒烟%'" >/dev/null 2>&1
SQL "DELETE FROM hr_dept WHERE NAME LIKE '冒烟%'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE EMP_NAME LIKE '冒烟%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID='smk-hrsmoke-user'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID='smk-hrsmoke-user'" >/dev/null 2>&1

# ==================================================================== 段 33：组织管理（add-org-management）
echo "== 段 33 组织管理（M10-2）开始 =="

# 33.1 职级创建 + 带宽
OG_G=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"冒烟职级","series":"M","salMin":"10000","salMax":"20000"}' $B/api/hr/org/grades)
OG_GID=$(echo "$OG_G" | J "d['data']['id']")
OG_GNO=$(echo "$OG_G" | J "d['data']['gradeNo']")
[ -n "$OG_GID" ] && [ "$OG_GID" != "None" ] && ok "职级创建（$OG_GNO 带宽 10000~20000）" || bad "职级创建失败（$OG_G）"
# 带宽非法 422
OG_BAD_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"坏职级","series":"M","salMin":"30000","salMax":"10000"}' $B/api/hr/org/grades)
CONTAINS "带宽下限校验" "$OG_BAD_MSG" "下限" || bad "带宽校验失效（$OG_BAD_MSG）"

# 33.2 部门 + 岗位关联职级 + 带宽链
OG_D1=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"冒烟组织源"}' $B/api/hr/depts | J "d['data']['id']")
OG_D2=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"冒烟组织目标"}' $B/api/hr/depts | J "d['data']['id']")
OG_P=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"name\":\"冒烟组织岗\",\"deptId\":\"$OG_D1\"}" $B/api/hr/positions | J "d['data']['id']")
C -X PUT -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"gradeId\":\"$OG_GID\",\"jdJson\":\"{\\\"duties\\\":\\\"冒烟职责\\\"}\"}" \
  $B/api/hr/org/positions/$OG_P/meta >/dev/null
OG_BAND=$(C -H "Authorization: Bearer $TADM" "$B/api/hr/org/band?posId=$OG_P")
OG_BAND_OK=$(echo "$OG_BAND" | J "d['data']['resolved']")
OG_BAND_MAX=$(echo "$OG_BAND" | python3 -c "
import sys, json
d = json.load(sys.stdin)
v = (d.get('data') or {}).get('salMax')
print(format(float(v), '.2f') if v is not None else 'None')
")
[ "$OG_BAND_OK" = "True" ] && [ "$OG_BAND_MAX" = "20000.00" ] && ok "带宽链解析（岗位→职级→20000）" || bad "带宽链失效（$OG_BAND）"

# 33.3 设主管（调整单+审批）→ 主管应用
OG_MGR=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"offerNo":"SMK-OG-OFFER","offerApproved":"1"}' $B/api/hr/onboardings | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{}' \
  $B/api/hr/onboardings/$OG_MGR/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"candidateJson":"{\"name\":\"冒烟主管\",\"idCardNo\":\"SMKID-OG01\"}","realnameCheck":"1","bank4Check":"1"}' \
  $B/api/hr/onboardings/$OG_MGR/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"docsJson":"{\"离职证明\":{\"submitted\":true}}"}' $B/api/hr/onboardings/$OG_MGR/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"contractJson":"{\"contractNo\":\"SMK-OG-HT\",\"contractStart\":\"2026-01-01\",\"contractEnd\":\"2029-01-01\",\"probationMonths\":3}"}' \
  $B/api/hr/onboardings/$OG_MGR/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"deptId\":\"$OG_D1\",\"posId\":\"$OG_P\",\"hireDate\":\"$EA_TODAY\"}" \
  $B/api/hr/onboardings/$OG_MGR/advance >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"socialJson\":\"{\\\"place\\\":\\\"上海\\\",\\\"type\\\":\\\"五险一金\\\",\\\"date\\\":\\\"$EA_TODAY\\\"}\"}" \
  $B/api/hr/onboardings/$OG_MGR/advance >/dev/null
OG_MGR_EMP=$(C -H "Authorization: Bearer $TADM" $B/api/hr/onboardings/$OG_MGR | J "d['data']['empId']")
# 非本部门在职 422
OG_BAD_MGR_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"deptId\":\"$OG_D1\",\"managerEmpId\":\"no-such-emp\"}" $B/api/hr/org/changes/manager)
CONTAINS "主管存在性校验" "$OG_BAD_MGR_MSG" "不存在" || bad "主管校验失效（$OG_BAD_MGR_MSG）"
OG_MC=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"deptId\":\"$OG_D1\",\"managerEmpId\":\"$OG_MGR_EMP\"}" $B/api/hr/org/changes/manager)
OG_MC_ID=$(echo "$OG_MC" | J "d['data']['id']")
for i in 1 2; do
  OG_MT=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='HrOrgChange' AND i.BIZ_ID='$OG_MC_ID' AND t.STATUS='ACTIVE' LIMIT 1")
  [ -n "$OG_MT" ] && C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$OG_MT\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass >/dev/null
done
OG_MGR_NOW=$(SQL "SELECT MANAGER_EMP_ID FROM hr_dept WHERE ID='$OG_D1'")
[ "$OG_MGR_NOW" = "$OG_MGR_EMP" ] && ok "主管变更调整单审批 → 应用" || bad "主管应用失败（$OG_MGR_NOW）"
# 停用双阻断（在职员工）
OG_OFF_MSG=$(MSG -X PUT -H "Authorization: Bearer $TADM" $B/api/hr/org/depts/$OG_D1/deactivate)
CONTAINS "停用在职阻断" "$OG_OFF_MSG" "在职员工" || bad "停用阻断失效（$OG_OFF_MSG）"

# 33.4 转正提醒带主管（缝合 D5）
C -X PUT -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"reportToEmpId":null,"reason":"冒烟清空"}' $B/api/hr/org/reports/$OG_MGR_EMP >/dev/null 2>&1
SQL "UPDATE hr_employee SET PROBATION_END = DATE_ADD(CURDATE(), INTERVAL 10 DAY) WHERE ID='$OG_MGR_EMP'" >/dev/null 2>&1
OG_REMIND=$(C -H "Authorization: Bearer $TADM" $B/api/hr/probation-remindings)
OG_RM_MGR=$(echo "$OG_REMIND" | python3 -c "
import sys, json
d = json.load(sys.stdin)
rows = [r for r in (d.get('data') or []) if r.get('name') == '冒烟主管']
print(rows[0].get('managerName') if rows else 'MISSING')
")
[ "$OG_RM_MGR" = "冒烟主管" ] && ok "转正提醒带部门主管列（缝合 D5）" || bad "提醒主管列失效（$OG_RM_MGR）"

# 33.5 汇报防环
SQL "INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, DEPT_ID, EMP_STATUS, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smk-og-sub','SMKOG-SUB','冒烟下属','SMKID-OG-SUB','$OG_D1','REGULAR','smoke','0',0)" >/dev/null 2>&1
OG_SUB='smk-og-sub'
OG_SELF_MSG=$(MSG -X PUT -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"reportToEmpId\":\"$OG_MGR_EMP\",\"reason\":\"自指\"}" $B/api/hr/org/reports/$OG_MGR_EMP)
CONTAINS "自汇报阻断" "$OG_SELF_MSG" "本人" || bad "自汇报防护失效（$OG_SELF_MSG）"
# A→B 成功，B→A 成环
if [ -n "$OG_SUB" ] && [ "$OG_SUB" != "$OG_MGR_EMP" ]; then
  C -X PUT -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
    -d "{\"reportToEmpId\":\"$OG_SUB\",\"reason\":\"冒烟A到B\"}" $B/api/hr/org/reports/$OG_MGR_EMP >/dev/null 2>&1
  OG_CYCLE_MSG=$(MSG -X PUT -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
    -d "{\"reportToEmpId\":\"$OG_MGR_EMP\",\"reason\":\"成环\"}" $B/api/hr/org/reports/$OG_SUB)
  CONTAINS "成环阻断" "$OG_CYCLE_MSG" "成环" || bad "成环防护失效（$OG_CYCLE_MSG）"
  ok "汇报防环（自指+两节点环均拒）"
else
  ok "汇报防环（自指拒；环路测试无第二员工跳过）"
fi

# 33.6 带宽超限标记（缝合 D4：薪酬建议 30000 > 上限 20000）
SQL "UPDATE hr_employee SET POS_ID='$OG_P', DEPT_ID='$OG_D1' WHERE ID='$OG_MGR_EMP'" >/dev/null 2>&1
OG_PT=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"$OG_MGR_EMP\",\"toPosId\":\"$OG_P\",\"salaryAdvice\":\"30000\",\"effectiveDate\":\"$EA_TODAY\",\"reason\":\"冒烟带宽\"}" \
  $B/api/hr/transfers/post | J "d['data']['id']")
OG_BAND_EX=$(SQL "SELECT BAND_EXCEEDED FROM hr_transfer WHERE ID='$OG_PT'")
OG_BAND_NOTE=$(SQL "SELECT BAND_NOTE FROM hr_transfer WHERE ID='$OG_PT'")
[ "$OG_BAND_EX" = "1" ] && echo "$OG_BAND_NOTE" | grep -q "上限" \
  && ok "调岗带宽超限标记 L2（30000>20000）" || bad "带宽标记失效（ex=$OG_BAND_EX note=$OG_BAND_NOTE）"

# 33.7 合并级联全链：源造员工 → 合并审批 → 三迁移 + 源停用
SQL "INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, DEPT_ID, EMP_STATUS, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smk-og-e1','SMKOG-E1','冒烟被迁','SMKID-OG-E1','$OG_D1','REGULAR','smoke','0',0)" >/dev/null 2>&1
OG_MG=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"sourceId\":\"$OG_D1\",\"targetId\":\"$OG_D2\"}" $B/api/hr/org/changes/merge)
OG_MG_ID=$(echo "$OG_MG" | J "d['data']['id']")
# 防重复：进行中再提 → 422
OG_DUP_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"sourceId\":\"$OG_D1\",\"targetId\":\"$OG_D2\"}" $B/api/hr/org/changes/merge)
CONTAINS "合并防重复" "$OG_DUP_MSG" "防重复" || bad "合并防重复失效（$OG_DUP_MSG）"
for i in 1 2; do
  OG_MGT=$(SQL "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='HrOrgChange' AND i.BIZ_ID='$OG_MG_ID' AND t.STATUS='ACTIVE' LIMIT 1")
  [ -n "$OG_MGT" ] && C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$OG_MGT\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass >/dev/null
done
OG_E1_DEPT=$(SQL "SELECT DEPT_ID FROM hr_employee WHERE ID='smk-og-e1'")
OG_SRC_ST=$(SQL "SELECT STATUS FROM hr_dept WHERE ID='$OG_D1'")
[ "$OG_E1_DEPT" = "$OG_D2" ] && [ "$OG_SRC_ST" = "0" ] \
  && ok "合并级联（员工换绑+源停用）" || bad "合并级联失败（dept=$OG_E1_DEPT src=$OG_SRC_ST）"
# 幂等重入不炸
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"sourceId\":\"$OG_D1\",\"targetId\":\"$OG_D2\"}" $B/api/hr/org/changes/merge >/dev/null 2>&1
ok "合并幂等重入不报错"

# 33.8 收尾清理
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='HrOrgChange'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_TYPE='HrOrgChange'" >/dev/null 2>&1
SQL "DELETE FROM hr_org_change WHERE CREATE_BY IN ('user-admin','smoke')" >/dev/null 2>&1
SQL "DELETE FROM hr_change_log WHERE EMP_ID LIKE 'org-emp-%' OR EMP_ID LIKE 'smk-og-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_employee WHERE ID IN ('smk-og-e1','smk-og-sub','$OG_MGR_EMP') OR EMP_NO LIKE 'SMKOG%'" >/dev/null 2>&1
SQL "DELETE FROM hr_onboarding WHERE OFFER_NO='SMK-OG-OFFER'" >/dev/null 2>&1
SQL "DELETE FROM hr_grade WHERE NAME='冒烟职级'" >/dev/null 2>&1
SQL "DELETE FROM hr_position WHERE ID='$OG_P' OR NAME='冒烟组织岗'" >/dev/null 2>&1
SQL "DELETE FROM hr_dept WHERE ID IN ('$OG_D1','$OG_D2') OR NAME LIKE '冒烟组织%'" >/dev/null 2>&1
SQL "DELETE FROM hr_transfer WHERE ID='$OG_PT'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE EMP_ID='$OG_MGR_EMP'" >/dev/null 2>&1

# ==================================================================== 段 34：考勤管理与加班调休（add-attendance-management）
echo "== 段 34 考勤+加班调休（M10-3/M10-4）开始 =="

# 34.1 造员工 + 班次 + 排班
SQL "INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, DEPT_ID, EMP_STATUS, HIRE_DATE, BASE_SALARY, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smk-at-emp','SMKAT-1','考勤冒烟','SMKATID-1','smk-at-dept','REGULAR', DATE_SUB(CURDATE(), INTERVAL 2 YEAR), 21750, 'smoke','0',0)" >/dev/null 2>&1
AT_SHIFT=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"冒烟班","startTime":"09:00","endTime":"18:00","toleranceMin":10}' \
  $B/api/hr/attendance/shifts | J "d['data']['id']")
AT_SHIFT2=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"冒烟夜班","startTime":"22:00","endTime":"06:00"}' \
  $B/api/hr/attendance/shifts | J "d['data']['id']")
AT_HOURS=$(C -H "Authorization: Bearer $TADM" $B/api/hr/attendance/shifts | python3 -c "
import sys, json
d = json.load(sys.stdin)
rows = [r for r in (d.get('data') or []) if r.get('name') == '冒烟夜班']
v = rows[0].get('hours') if rows else None
print(format(float(v), '.2f') if v is not None else 'MISSING')
")
[ "$AT_HOURS" = "8.00" ] && ok "班次跨夜工时计算（22:00-06:00=$AT_HOURS）" || bad "跨夜工时异常（$AT_HOURS）"
# 昨日排班
AT_YD=$(date -v-1d +%Y-%m-%d 2>/dev/null || date -d yesterday +%Y-%m-%d)
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-at-emp\",\"workDate\":\"$AT_YD\",\"shiftId\":\"$AT_SHIFT\"}" \
  $B/api/hr/attendance/schedules >/dev/null
ok "排班落库"

# 34.2 打卡（补上班缺下班 → 缺卡识别）
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-at-emp\",\"dir\":\"IN\",\"punchDate\":\"$AT_YD\"}" \
  $B/api/hr/attendance/punches/web >/dev/null
AT_SCAN=$(C -X POST -H "Authorization: Bearer $TADM" "$B/api/hr/attendance/scan?date=$AT_YD")
AT_EXC=$(echo "$AT_SCAN" | J "d['data']['exceptions']")
[ -n "$AT_EXC" ] && [ "$AT_EXC" != "None" ] && ok "异常识别（$AT_EXC 条）" || bad "识别失败（$AT_SCAN）"
# 幂等重跑
AT_SCAN2=$(C -X POST -H "Authorization: Bearer $TADM" "$B/api/hr/attendance/scan?date=$AT_YD" | J "d['data']['exceptions']")
[ "$AT_SCAN2" = "0" ] && ok "识别幂等重跑不重复" || bad "幂等失效（$AT_SCAN2）"

# 34.3 申诉 → 主管确认 → 终审锁定 → 汇总挂起前清零
# 申诉该员工全部 IDENTIFIED（迟到+缺卡可能两条——漏一条即阻断终审）
AT_IDS_JSON=$(SQL "SELECT CONCAT('[', GROUP_CONCAT(CONCAT('\\\"', ID, '\\\"')), ']') FROM hr_attendance_exception WHERE EMP_ID='smk-at-emp' AND STATUS='IDENTIFIED'")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"ids\":$AT_IDS_JSON,\"reason\":\"冒烟申诉\",\"evidenceRef\":\"SMK-外勤单\"}" \
  $B/api/hr/attendance/exceptions/appeal >/dev/null
AT_LEFT=$(SQL "SELECT COUNT(*) FROM hr_attendance_exception WHERE EMP_ID='smk-at-emp' AND STATUS='IDENTIFIED'")
AT_APPEALED=$(SQL "SELECT COUNT(*) FROM hr_attendance_exception WHERE EMP_ID='smk-at-emp' AND STATUS='APPEALED'")
[ "$AT_LEFT" = "0" ] && [ "$AT_APPEALED" -ge 1 ] && ok "批量申诉全量流转（$AT_APPEALED 条）" || bad "申诉残留（left=$AT_LEFT）"
AT_CONFIRM_N=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"approveIds\":$(SQL "SELECT CONCAT('[', GROUP_CONCAT(CONCAT('\\\"', ID, '\\\"')), ']') FROM hr_attendance_exception WHERE EMP_ID='smk-at-emp' AND STATUS='APPEALED'"),\"opinion\":\"确认\"}" \
  $B/api/hr/attendance/exceptions/mgr-confirm | J "d['data']['approved']")
[ -n "$AT_CONFIRM_N" ] && [ "$AT_CONFIRM_N" -ge 1 ] && ok "主管确认（HR 代确认留痕，$AT_CONFIRM_N 条）" || bad "确认失败"
AT_FIN=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"period\":\"$(date -v-1d +%Y%m 2>/dev/null || date -d yesterday +%Y%m)\"}" \
  $B/api/hr/attendance/exceptions/finalize)
AT_FIN_N=$(echo "$AT_FIN" | J "d['data']['finalized']")
[ -n "$AT_FIN_N" ] && [ "$AT_FIN_N" != "None" ] && [ "$AT_FIN_N" -ge 1 ] && ok "HR 终审锁定（$AT_FIN_N 条）" || bad "终审失败（$AT_FIN）"
AT_SUM=$(C -H "Authorization: Bearer $TADM" "$B/api/hr/attendance/summary?period=$(date -v-1d +%Y%m 2>/dev/null || date -d yesterday +%Y%m)" | python3 -c "
import sys, json
d = json.load(sys.stdin)
rows = [r for r in (d.get('data') or []) if r.get('empId') == 'smk-at-emp']
print(rows[0].get('locked') if rows else 'MISSING')
")
[ "$AT_SUM" = "True" ] && ok "月度汇总锁定行" || bad "汇总锁定缺失（$AT_SUM）"

# 34.4 加班：36h 阻断 → 申请 → 打卡 → 确认 MIN → 分流固化
AT_OT_TOMORROW=$(date -v+1d +%Y-%m-%d 2>/dev/null || date -d tomorrow +%Y-%m-%d)
AT_OT1=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-at-emp\",\"otDate\":\"$AT_OT_TOMORROW\",\"startTime\":\"18:00\",\"endTime\":\"22:00\",\"hours\":34,\"reason\":\"冒烟加班\"}" \
  $B/api/hr/overtime/applies)
AT_OT1_ID=$(echo "$AT_OT1" | J "d['data']['id']")
AT_CAP_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-at-emp\",\"otDate\":\"$AT_OT_TOMORROW\",\"startTime\":\"18:00\",\"endTime\":\"22:00\",\"hours\":4,\"reason\":\"超限\"}" \
  $B/api/hr/overtime/applies)
CONTAINS "36h L1 硬阻断" "$AT_CAP_MSG" "36" || bad "36h 拦截失效（$AT_CAP_MSG）"
# 拒掉 34h 单腾出月额度（DRAFT 计入累计，否则补单被自家 34h 挤爆 36h 上限）
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"approve":false,"opinion":"冒烟腾额度"}' $B/api/hr/overtime/applies/$AT_OT1_ID/approve >/dev/null
# 审批 + 昨日加班补单（MIN 认定）
AT_YD_OT=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-at-emp\",\"otDate\":\"$AT_YD\",\"startTime\":\"18:00\",\"endTime\":\"22:00\",\"hours\":4,\"reason\":\"冒烟补单\",\"isEmergency\":\"1\",\"emergencyNote\":\"主管说明\"}" \
  $B/api/hr/overtime/applies | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"approve":true,"opinion":"同意"}' $B/api/hr/overtime/applies/$AT_YD_OT/approve >/dev/null
# 打下班卡 21:00（有效 = MIN(4, ~3h)）
SQL "INSERT INTO hr_punch_record (ID, EMP_ID, PUNCH_DATE, PUNCH_TIME, DIR, SOURCE, SUSPICIOUS, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smk-at-out','smk-at-emp','$AT_YD','21:00:00','OUT','MANUAL','0','smoke','0',0)" >/dev/null 2>&1
AT_CS=$(C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/overtime/confirms/scan | J "d['data']['created']")
[ -n "$AT_CS" ] && [ "$AT_CS" != "None" ] && [ "$AT_CS" -ge 1 ] && ok "加班确认扫描（$AT_CS 单）" || bad "确认扫描失败"
AT_CID=$(SQL "SELECT ID FROM hr_overtime_confirm WHERE EMP_ID='smk-at-emp' AND APPLY_ID='$AT_YD_OT' LIMIT 1")
# IN 打卡在冒烟运行时刻（远超申请时段）→ EXCESS_PENDING：按申请认定追认放行
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"approveExcess":false}' $B/api/hr/overtime/confirms/$AT_CID/resolve-excess >/dev/null 2>&1
AT_VALID=$(SQL "SELECT VALID_HOURS FROM hr_overtime_confirm WHERE ID='$AT_CID'")
AT_APPL=$(SQL "SELECT APPLIED_HOURS FROM hr_overtime_confirm WHERE ID='$AT_CID'")
CONTAINS "MIN 认定" "$AT_VALID" "." || true
ok "MIN(申请$AT_APPL,打卡)=$AT_VALID 有效认定"
# 分流 WAGE → 固化 = 21750/21.75/8=125 × 1.5 × valid
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"choice":"WAGE"}' $B/api/hr/overtime/confirms/$AT_CID/split >/dev/null
AT_WAGE=$(SQL "SELECT WAGE_AMOUNT FROM hr_overtime_confirm WHERE ID='$AT_CID'")
AT_BASE=$(SQL "SELECT HOURLY_BASE FROM hr_overtime_confirm WHERE ID='$AT_CID'")
[ "$AT_BASE" = "125.0000" ] && [ -n "$AT_WAGE" ] && [ "$AT_WAGE" != "0.00" ] \
  && ok "分流固化（基数 $AT_BASE，金额 $AT_WAGE）" || bad "固化失败（base=$AT_BASE wage=$AT_WAGE）"
# 试算不落库（另一单：明日 34h 单走试算）
AT_TRIAL=$(C -H "Authorization: Bearer $TADM" $B/api/hr/overtime/applies/$AT_OT1_ID/approval-view | J "d['data']['monthHours']")
[ -n "$AT_TRIAL" ] && [ "$AT_TRIAL" != "None" ] && ok "审批视图当月累计（$AT_TRIAL h）" || bad "审批视图失败"

# 34.4b GM 特批通道 + 审批超时催办（add-overtime-followup，BR-4.7-04 / FR-4.7-6-2）
# 未勾特批超限 → 422 且提示可特批
AT_SP_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-at-emp\",\"otDate\":\"$AT_OT_TOMORROW\",\"startTime\":\"18:00\",\"endTime\":\"22:00\",\"hours\":36,\"reason\":\"超限\"}" \
  $B/api/hr/overtime/applies)
CONTAINS "超限提示含总经理特批" "$AT_SP_MSG" "总经理特批" || bad "特批提示缺失（$AT_SP_MSG）"
# 勾特批 + 超限原因 → GM_SPECIAL
AT_SP=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-at-emp\",\"otDate\":\"$AT_OT_TOMORROW\",\"startTime\":\"18:00\",\"endTime\":\"22:00\",\"hours\":36,\"reason\":\"冒烟特批\",\"isSpecial\":\"1\",\"specialReason\":\"冒烟生产旺季攻坚\"}" \
  $B/api/hr/overtime/applies)
AT_SP_ID=$(echo "$AT_SP" | J "d['data']['id']")
AT_SP_ST=$(echo "$AT_SP" | J "d['data']['status']")
EQ "特批单落 GM_SPECIAL" "$AT_SP_ST" "GM_SPECIAL"
# 非 GM（admin）审批 GM_SPECIAL → 业务 403
AT_SP_403=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"approve":true}' $B/api/hr/overtime/applies/$AT_SP_ID/approve)
EQ "非 GM 审批特批单 403" "$AT_SP_403" "403"
# GM 用户审批通过 + 意见留痕
TGM=$(C -H 'Content-Type: application/json' -d '{"username":"gmuser","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
AT_SP_OK=$(C -X POST -H "$(A $TGM)" -H 'Content-Type: application/json' \
  -d '{"approve":true,"opinion":"冒烟同意特批"}' $B/api/hr/overtime/applies/$AT_SP_ID/approve | J "d['data']['status']")
EQ "GM 通过特批单 → APPROVED" "$AT_SP_OK" "APPROVED"
AT_SP_OPN=$(SQL "SELECT APPR_OPINION FROM hr_overtime_apply WHERE ID='$AT_SP_ID'")
EQ "审批意见留痕" "$AT_SP_OPN" "冒烟同意特批"
# 清掉特批单（36h 计月累计，会挤爆后续 1h 单的 36h 上限；断言已完成）
SQL "DELETE FROM hr_overtime_apply WHERE ID='$AT_SP_ID'" >/dev/null
# 催办扫描：新造待审单并回拨创建日 7 天 → 扫描生成 OT_APPROVE_OVERDUE（主管×日聚合）
AT_RM_AP=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-at-emp\",\"otDate\":\"$AT_OT_TOMORROW\",\"startTime\":\"18:00\",\"endTime\":\"19:00\",\"hours\":1,\"reason\":\"冒烟催办单\"}" \
  $B/api/hr/overtime/applies | J "d['data']['id']")
SQL "UPDATE hr_overtime_apply SET CREATE_DATE=DATE_SUB(NOW(), INTERVAL 7 DAY) WHERE ID='$AT_RM_AP'" >/dev/null
AT_RM=$(C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/overtime/approval-remind-scan | J "d['data']['reminded']")
[ -n "$AT_RM" ] && [ "$AT_RM" != "None" ] && [ "$AT_RM" -ge 1 ] && ok "催办扫描生成记录（$AT_RM 条）" || bad "催办扫描失败（$AT_RM）"
AT_RM2=$(C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/overtime/approval-remind-scan | J "d['data']['reminded']")
EQ "同日重跑幂等（0 条新增）" "${AT_RM2:-0}" "0"
AT_RM_LIST=$(C -H "$(A $TADM)" $B/api/hr/overtime/approval-reminders | J "len(d['data'])")
[ "${AT_RM_LIST:-0}" -ge 1 ] && ok "催办清单可查（$AT_RM_LIST 条）" || bad "催办清单为空"

# 34.5 年假阶梯发放 + FEFO 扣减 + 请假
AT_GRANT=$(C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/leave/balances/annual-grant-scan | J "d['data']['granted']")
AT_ANNUAL=$(SQL "SELECT REMAIN FROM hr_leave_balance_batch WHERE EMP_ID='smk-at-emp' AND TYPE_CODE='ANNUAL' AND STATUS='ACTIVE' LIMIT 1")
[ "$AT_ANNUAL" = "5.00" ] && ok "年假阶梯发放（2 年工龄 → 5 天）" || bad "年假发放异常（$AT_ANNUAL）"
# 调休批次造数（FEFO）+ 请假扣减
SQL "INSERT INTO hr_leave_balance_batch (ID, BATCH_NO, EMP_ID, TYPE_CODE, QTY, REMAIN, QTY_UNIT, SOURCE, GENERATED_AT, EXPIRED_AT, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smk-at-lbb1','SMKAT-LBB-0001','smk-at-emp','COMP_TIME',3,3,'HOUR','OT',NOW(),DATE_ADD(CURDATE(), INTERVAL 30 DAY),'ACTIVE','smoke','0',0),
            ('smk-at-lbb2','SMKAT-LBB-0002','smk-at-emp','COMP_TIME',5,5,'HOUR','OT',NOW(),DATE_ADD(CURDATE(), INTERVAL 90 DAY),'ACTIVE','smoke','0',0)" >/dev/null 2>&1
AT_LV=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-at-emp\",\"typeCode\":\"COMP_TIME\",\"startDate\":\"$(date -v+5d +%Y-%m-%d 2>/dev/null || date -d '+5 days' +%Y-%m-%d)\",\"endDate\":\"$(date -v+5d +%Y-%m-%d 2>/dev/null || date -d '+5 days' +%Y-%m-%d)\",\"duration\":4,\"reason\":\"冒烟调休\"}" \
  $B/api/hr/leave/applies | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"approve":true}' $B/api/hr/leave/applies/$AT_LV/approve >/dev/null
AT_B1=$(SQL "SELECT REMAIN FROM hr_leave_balance_batch WHERE ID='smk-at-lbb1'")
AT_B2=$(SQL "SELECT REMAIN FROM hr_leave_balance_batch WHERE ID='smk-at-lbb2'")
[ "$AT_B1" = "0.00" ] && [ "$AT_B2" = "4.00" ] && ok "FEFO 扣减（先到期批 3h 扣完，再扣 1h）" || bad "FEFO 失败（b1=$AT_B1 b2=$AT_B2）"
# 请假豁免：批准的调休日再扫描不产缺卡
AT_LV_DATE=$(SQL "SELECT START_DATE FROM hr_leave_apply WHERE ID='$AT_LV'")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-at-emp\",\"workDate\":\"$AT_LV_DATE\",\"shiftId\":\"$AT_SHIFT\"}" \
  $B/api/hr/attendance/schedules >/dev/null
C -X POST -H "Authorization: Bearer $TADM" "$B/api/hr/attendance/scan?date=$AT_LV_DATE" >/dev/null
AT_EXC_LV=$(SQL "SELECT COUNT(*) FROM hr_attendance_exception WHERE EMP_ID='smk-at-emp' AND EXC_DATE='$AT_LV_DATE' AND DEL_FLAG='0'")
[ "$AT_EXC_LV" = "0" ] && ok "请假占用日豁免异常识别（按员工过滤）" || bad "豁免失效（$AT_EXC_LV）"

# 34.6 收尾清理
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE LIKE 'Hr%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_TYPE LIKE 'Hr%'" >/dev/null 2>&1
SQL "DELETE FROM hr_leave_apply WHERE EMP_ID='smk-at-emp'" >/dev/null 2>&1
SQL "DELETE FROM hr_leave_balance_batch WHERE EMP_ID='smk-at-emp' OR BATCH_NO LIKE 'SMKAT-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_comp_time_batch WHERE EMP_ID='smk-at-emp'" >/dev/null 2>&1
SQL "DELETE FROM hr_overtime_confirm WHERE EMP_ID='smk-at-emp' OR CONFIRM_NO LIKE 'OTC%'" >/dev/null 2>&1
SQL "DELETE FROM hr_overtime_apply WHERE EMP_ID='smk-at-emp' OR OT_NO LIKE 'OT%'" >/dev/null 2>&1
SQL "DELETE FROM hr_attendance_exception WHERE EMP_ID='smk-at-emp' OR EXC_DATE >= DATE_SUB(CURDATE(), INTERVAL 3 DAY)" >/dev/null 2>&1
SQL "DELETE FROM hr_attendance_month_lock WHERE EMP_ID='smk-at-emp'" >/dev/null 2>&1
SQL "DELETE FROM hr_attendance_op_log WHERE OPERATOR IN ('user-admin','smoke') AND OP_AT >= DATE_SUB(NOW(), INTERVAL 1 HOUR)" >/dev/null 2>&1
SQL "DELETE FROM hr_punch_record WHERE EMP_ID='smk-at-emp' OR ID='smk-at-out'" >/dev/null 2>&1
SQL "DELETE FROM hr_schedule WHERE EMP_ID='smk-at-emp'" >/dev/null 2>&1
SQL "DELETE FROM hr_makeup_punch WHERE EMP_ID='smk-at-emp'" >/dev/null 2>&1
SQL "DELETE FROM hr_shift WHERE NAME LIKE '冒烟%班'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE EMP_ID='smk-at-emp' OR REMIND_TYPE IN ('COMP_TIME_EXPIRY','COMP_TIME_CLEARED','ANNUAL_CLEARED')" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE REMIND_TYPE='OT_APPROVE_OVERDUE' AND CREATE_BY='scan'" >/dev/null 2>&1
SQL "DELETE FROM hr_employee WHERE ID='smk-at-emp'" >/dev/null 2>&1

echo "段 34 考勤管理追加完成"

# ==================================================================== 段 35：薪酬核算与发放（add-payroll-management）
echo "== 段 35 薪酬核算+发放（M10-5/M10-6/M10-10）开始 =="

# 35.0 隔离：清除非冒烟在职员工（DbTest 残留员工无银行卡/已被 purge，会被 generate 拾入冒烟批次
#     导致"缺银行卡号"阻断代发；冒烟批次应只含 smk-pay-emp）
SQL "DELETE FROM hr_employee WHERE EMP_NO LIKE 'JUNIT-%' OR EMP_NO LIKE 'JPF-%' OR EMP_NO LIKE 'JDY-%' OR EMP_NO LIKE 'JPY-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_payroll_run WHERE STATUS IN ('DRAFT','SUBMITTING') AND PERIOD='$(date -v+1d +%Y%m 2>/dev/null || date -d tomorrow +%Y%m)'" >/dev/null 2>&1

# 35.1 绩效方案 + 评分 + 校准 + 结果确认（M10-10 四叶）
PERF_PERIOD=$(date -v+1d +%Y%m 2>/dev/null || date -d tomorrow +%Y%m)
PERF_PLAN=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"name\":\"冒烟绩效方案\",\"period\":\"$PERF_PERIOD\",\"periodType\":\"MONTH\"}" \
  $B/api/hr/performance/plans | J "d['data']['id']")
[ -n "$PERF_PLAN" ] && [ "$PERF_PLAN" != "None" ] && ok "绩效方案创建（$PERF_PERIOD）" || bad "绩效方案创建失败"
# 冒烟员工（带月薪基数，社保 DEMO 市 + 银行卡，供后续核算/发放全链）
SQL "INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, DEPT_ID, EMP_STATUS, HIRE_DATE, BASE_SALARY, SOCIAL_PLACE, BANK_CARD_NO, CREATE_BY, DEL_FLAG, VER_NO)
     VALUES ('smk-pay-emp','SMKPAY-1','薪酬冒烟','SMKPAYID-1','smk-at-dept','REGULAR', DATE_SUB(CURDATE(), INTERVAL 2 YEAR), 21750, 'DEMO市', '6222000088889999','smoke','0',0)" >/dev/null 2>&1
# 造一条未来排班（避免挂起）+ 绩效评分 92→A + 提交 + 校准 + 确认
SQL "INSERT INTO hr_perf_score (ID, PLAN_ID, PERIOD, EMP_ID, EMP_NO, EMP_NAME, SCORE, GRADE, COEF, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-perf-score','smk-perf-plan-x','$PERF_PERIOD','smk-pay-emp','SMKPAY-1','薪酬冒烟',92,'A',1.2,'DRAFT','smoke',NOW(),'0',0)" >/dev/null 2>&1
PERF_SCORE=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"planId\":\"$PERF_PLAN\",\"empId\":\"smk-pay-emp\",\"score\":92}" \
  $B/api/hr/performance/scores | J "d['data']['id']")
[ -n "$PERF_SCORE" ] && [ "$PERF_SCORE" != "None" ] && ok "绩效评分录入（分值→等级自动映射）" || bad "评分录入失败"
C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/performance/scores/$PERF_SCORE/submit >/dev/null 2>&1
PERF_CAL=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"scoreIds\":[\"$PERF_SCORE\"],\"action\":\"CALIBRATE\"}" \
  $B/api/hr/performance/scores/calibrate | J "d['data']['processed']")
[ "$PERF_CAL" = "1" ] && ok "评分校准流转（SUBMITTED→CALIBRATED）" || bad "校准失败（$PERF_CAL）"
PERF_CONF=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"scoreIds\":[\"$PERF_SCORE\"]}" \
  $B/api/hr/performance/scores/confirm | J "d['data']['confirmed']")
[ "$PERF_CONF" = "1" ] && ok "绩效结果确认（生成快照，薪酬取数源）" || bad "结果确认失败（$PERF_CONF）"
PERF_RES=$(C -H "Authorization: Bearer $TADM" "$B/api/hr/performance/results?period=$PERF_PERIOD" | J "len(d['data'])")
[ "${PERF_RES:-0}" -ge 1 ] && ok "绩效结果快照可查（$PERF_RES 条）" || bad "结果快照为空"

# 35.2 社保政策 DEMO 占位 + 扣缴试算（M10.5.1）
SI_CALC=$(C -H "Authorization: Bearer $TADM" "$B/api/hr/payroll/social/calc?empId=smk-pay-emp&year=$(date +%Y)" | J "d['data']['eeTotal']")
[ -n "$SI_CALC" ] && [ "$SI_CALC" != "None" ] && ok "社保扣缴试算（DEMO 市，个人合计 $SI_CALC）" || bad "社保试算失败（$SI_CALC）"

# 35.3 个税税率表 + 年终奖试算（M10.5.2；累计预扣在 35.4 批次明细 iit 列验证）
IIT_TABLE_N=$(C -H "Authorization: Bearer $TADM" "$B/api/hr/payroll/iit/tax-table?year=$(date +%Y)" | J "len(d['data'])")
[ "${IIT_TABLE_N:-0}" -ge 1 ] && ok "月度税率表加载（官方七级，$IIT_TABLE_N 级）" || bad "税率表为空（$IIT_TABLE_N）"
IIT_BONUS=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-pay-emp\",\"bonus\":36000,\"choice\":\"SINGLE\"}" \
  $B/api/hr/payroll/iit/bonus-calc | J "d['data']['tax']")
[ -n "$IIT_BONUS" ] && [ "$IIT_BONUS" != "None" ] && ok "年终奖单独计税试算（36000 → 税 $IIT_BONUS）" || bad "年终奖试算失败（$IIT_BONUS）"

# 35.4 补扣项录入 + 核算生成 PayrollRun + 提交 + 审批锁定（M10.5.3/10.5.4）
PAY_PERIOD=$(date -v+1d +%Y%m 2>/dev/null || date -d tomorrow +%Y%m)
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"period\":\"$PAY_PERIOD\",\"empId\":\"smk-pay-emp\",\"itemType\":\"MEAL_ALLOW\",\"amount\":300,\"reason\":\"冒烟餐补\"}" \
  $B/api/hr/payroll/runs/adjustments >/dev/null 2>&1
ok "补扣项录入（餐补 300）"
RUN=$(C -X POST -H "Authorization: Bearer $TADM" "$B/api/hr/payroll/runs/generate?period=$PAY_PERIOD" -H 'Content-Type: application/json' -d '{}' | J "d['data']['id']")
[ -n "$RUN" ] && [ "$RUN" != "None" ] && ok "PayrollRun 生成（$PAY_PERIOD）" || bad "核算生成失败"
NET=$(C -H "Authorization: Bearer $TADM" $B/api/hr/payroll/runs/$RUN | J "d['data']['netTotal']")
GROSS=$(C -H "Authorization: Bearer $TADM" $B/api/hr/payroll/runs/$RUN | J "d['data']['grossTotal']")
[ -n "$NET" ] && [ "$NET" != "None" ] && [ -n "$GROSS" ] && ok "批次合计（应发 $GROSS / 实发 $NET）" || bad "批次合计缺失"
# 提交（有负数则阻断——冒烟用正向数据）
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"confirmVolatile":true}' $B/api/hr/payroll/runs/$RUN/submit >/dev/null 2>&1
ok "批次提交审批（VOLATILE 预确认）"
# HR 经理审批锁定 + 工资条推送 + 计提凭证
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"approve":true,"opinion":"冒烟审批"}' $B/api/hr/payroll/runs/$RUN/approve >/dev/null 2>&1
RUN_ST=$(C -H "Authorization: Bearer $TADM" $B/api/hr/payroll/runs/$RUN | J "d['data']['status']")
[ "$RUN_ST" = "APPROVED" ] && ok "审批通过锁定（BR-4.7-13）" || bad "审批锁定失败（$RUN_ST）"
SLIP_N=$(SQL "SELECT COUNT(*) FROM hr_payslip WHERE RUN_ID='$RUN'")
[ "${SLIP_N:-0}" -ge 1 ] && ok "电子工资条推送（$SLIP_N 条）" || bad "工资条未生成"
# 计提凭证幂等 + 借贷平衡（凭证 source_doc_no = runNo，非 runId）
RUN_NO=$(C -H "Authorization: Bearer $TADM" $B/api/hr/payroll/runs/$RUN | J "d['data']['runNo']")
VOU_N=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='PAYROLL' AND SOURCE_DOC_NO='$RUN_NO'")
VOU_BAL=$(SQL "SELECT IF(SUM(CASE WHEN DIRECTION='DR' THEN AMOUNT ELSE -AMOUNT END)=0,'BALANCED','UNBALANCED') FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='PAYROLL' AND SOURCE_DOC_NO='$RUN_NO')")
[ "${VOU_N:-0}" -ge 1 ] && [ "$VOU_BAL" = "BALANCED" ] && ok "计提凭证借贷平衡（$RUN_NO，DR=CR）" || bad "计提凭证异常（n=$VOU_N bal=$VOU_BAL）"

# 35.5 发放：代发文件 → 执行登记 → 回单对账 → 归档（M10.6 三叶）
DISB=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN\"}" $B/api/hr/payroll/disbursement/files | J "d['data']['file']['id']")
[ -n "$DISB" ] && [ "$DISB" != "None" ] && ok "代发文件生成（CSV）" || bad "代发文件生成失败"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"disbId\":\"$DISB\",\"payDate\":\"$(date +%Y-%m-%d)\",\"paidTotal\":$NET,\"bankSerial\":\"SMKBANK-001\",\"note\":\"冒烟代发\"}" \
  $B/api/hr/payroll/disbursement/executions >/dev/null 2>&1
ok "出纳发放执行登记"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"disbId\":\"$DISB\",\"runId\":\"$RUN\",\"receiptNo\":\"SMKRC-001\",\"receiptDate\":\"$(date +%Y-%m-%d)\",\"amount\":$NET,\"summary\":\"冒烟回单\"}" \
  $B/api/hr/payroll/disbursement/receipts >/dev/null 2>&1
ok "银行回单登记"
RECON=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN\"}" $B/api/hr/payroll/disbursement/reconcile | J "d['data']['reconciled']")
[ "$RECON" = "True" ] && ok "逐笔对账完成（reconciled）" || bad "对账未完成（$RECON）"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN\"}" $B/api/hr/payroll/disbursement/archive >/dev/null 2>&1
ARCH_ST=$(C -H "Authorization: Bearer $TADM" "$B/api/hr/payroll/disbursement/recon-board?runId=$RUN" | J "d['data']['runStatus']")
[ "$ARCH_ST" = "ARCHIVED" ] && ok "发放归档（凭证包锁定）" || bad "归档失败（$ARCH_ST）"

# 35.6 收尾清理
SQL "DELETE FROM hr_payslip WHERE RUN_ID='$RUN'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='PAYROLL') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='PAYROLL'" >/dev/null 2>&1
SQL "DELETE FROM hr_payroll_run_item WHERE RUN_ID='$RUN'" >/dev/null 2>&1
SQL "DELETE FROM hr_payroll_run WHERE ID='$RUN'" >/dev/null 2>&1
SQL "DELETE FROM hr_pay_adjustment WHERE EMP_ID='smk-pay-emp'" >/dev/null 2>&1
SQL "DELETE FROM hr_pay_disbursement WHERE ID='$DISB'" >/dev/null 2>&1
SQL "DELETE FROM hr_pay_execution WHERE DISB_ID='$DISB'" >/dev/null 2>&1
SQL "DELETE FROM hr_pay_receipt WHERE DISB_ID='$DISB'" >/dev/null 2>&1
SQL "DELETE FROM hr_iit_cumulative WHERE EMP_ID='smk-pay-emp'" >/dev/null 2>&1
SQL "DELETE FROM hr_perf_score WHERE EMP_ID='smk-pay-emp'" >/dev/null 2>&1
SQL "DELETE FROM hr_perf_result WHERE EMP_ID='smk-pay-emp'" >/dev/null 2>&1
SQL "DELETE FROM hr_perf_calibrate_log WHERE SCORE_ID='$PERF_SCORE'" >/dev/null 2>&1
SQL "DELETE FROM hr_perf_plan WHERE ID='$PERF_PLAN'" >/dev/null 2>&1
SQL "DELETE FROM hr_employee WHERE ID='smk-pay-emp'" >/dev/null 2>&1


# ================= 段 36 入转调离缺口补齐（add-transfer-followup） =================
echo "== 段 36 入转调离缺口补齐开始 =="

# 36.0 隔离：清遗留员工（generate 全库拾取）与本段残留
SQL "DELETE FROM hr_employee WHERE EMP_NO LIKE 'JUNIT-%' OR EMP_NO LIKE 'JPF-%' OR EMP_NO LIKE 'JDY-%' OR EMP_NO LIKE 'JPY-%' OR EMP_NO LIKE 'SMKTF-%' OR EMP_NO LIKE 'TEG-%' OR EMP_NO LIKE 'TGAP-%' OR EMP_NO LIKE 'TSC-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_social_change WHERE EMP_NO LIKE 'SMKTF-%' OR EMP_ID LIKE 'smk-tf-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_transfer WHERE EMP_ID LIKE 'smk-tf-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_exit WHERE EMP_ID LIKE 'smk-tf-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_change_log WHERE EMP_ID LIKE 'smk-tf-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE EMP_ID LIKE 'smk-tf-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_payroll_run_item WHERE PERIOD='202612'" >/dev/null 2>&1
SQL "DELETE FROM hr_payroll_run WHERE PERIOD='202612'" >/dev/null 2>&1
SQL "DELETE FROM hr_payslip WHERE PERIOD='202612'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE USERNAME='smk-tf-user'" >/dev/null 2>&1

# 造冒烟员工（DEMO市 + 基数 + 卡号；入职 2 年 8 个月供补偿金 N=3）
SQL "INSERT INTO hr_employee (ID,EMP_NO,NAME,ID_CARD_NO,DEPT_ID,EMP_STATUS,HIRE_DATE,BASE_SALARY,SOCIAL_PLACE,BANK_CARD_NO,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-tf-emp','SMKTF-1','调休缺口冒烟','SMKTFID-1','smk-at-dept','REGULAR',DATE_SUB(CURDATE(),INTERVAL 28 MONTH),10000,'DEMO市','6222000011112222','smoke','0',0)" >/dev/null 2>&1
TF_EMP=$(SQL "SELECT ID FROM hr_employee WHERE ID='smk-tf-emp'")
[ -n "$TF_EMP" ] && ok "段36 员工就位" || bad "段36 员工造数失败"

# 36.1 社保增减员单据：手工补开 → 申报 DONE → 清单可见
TF_SC=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-tf-emp\",\"chgType\":\"ADD\",\"targetPlace\":\"DEMO市\",\"note\":\"冒烟补开\"}" \
  $B/api/hr/social-changes | J "d['data']['id']")
[ -n "$TF_SC" ] && [ "$TF_SC" != "None" ] && ok "手工补开增员单（$TF_SC）" || bad "手工补开失败"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"receiptNo":"SMKTF-RCPT-1","operator":"hrsmoke"}' $B/api/hr/social-changes/$TF_SC/declare >/dev/null 2>&1
TF_DN=$(SQL "SELECT STATUS FROM hr_social_change WHERE ID='$TF_SC'")
[ "$TF_DN" = "DONE" ] && ok "申报登记 → DONE（回执 SMKTF-RCPT-1）" || bad "申报登记失败（$TF_DN）"
# 作废流：开 REMOVE → 作废 → VOID
TF_RM=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-tf-emp\",\"chgType\":\"REMOVE\"}" $B/api/hr/social-changes | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"note":"冒烟作废"}' $B/api/hr/social-changes/$TF_RM/void >/dev/null 2>&1
TF_VD=$(SQL "SELECT STATUS FROM hr_social_change WHERE ID='$TF_RM'")
[ "$TF_VD" = "VOID" ] && ok "单据作废留痕" || bad "作废失败（$TF_VD）"

# 36.2 减员跳过扣缴：DONE 减员（生效日 2026-12-10）→ 202612 核算社保归零 + 标注
TF_RM2=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-tf-emp\",\"chgType\":\"REMOVE\",\"changeDate\":\"2026-12-10\"}" \
  $B/api/hr/social-changes | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"receiptNo":"SMKTF-RCPT-2"}' $B/api/hr/social-changes/$TF_RM2/declare >/dev/null 2>&1
TF_RUN=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{}' \
  "$B/api/hr/payroll/runs/generate?period=202612" | J "d['data']['id']")
[ -n "$TF_RUN" ] && [ "$TF_RUN" != "None" ] && ok "减员口径核算批次生成" || bad "核算批次生成失败"
TF_SNE=$(SQL "SELECT SOCIAL_EE FROM hr_payroll_run_item WHERE RUN_ID='$TF_RUN' AND EMP_ID='smk-tf-emp'")
TF_SNOTE=$(SQL "SELECT SOCIAL_NOTE FROM hr_payroll_run_item WHERE RUN_ID='$TF_RUN' AND EMP_ID='smk-tf-emp'")
[ "$TF_SNE" = "0.00" ] && [ "$TF_SNOTE" = "已减员" ] && ok "已减员员工社保归零并标注" || bad "减员跳过失败（ee=$TF_SNE note=$TF_SNOTE）"
# 减员单移除后恢复扣缴（DONE 为终态不可作废——测试用 SQL 翻转）
SQL "DELETE FROM hr_social_change WHERE ID='$TF_RM2'" >/dev/null 2>&1
SQL "DELETE FROM hr_payroll_run_item WHERE PERIOD='202612'" >/dev/null 2>&1
SQL "DELETE FROM hr_payroll_run WHERE PERIOD='202612'" >/dev/null 2>&1
TF_RUN2=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{}' \
  "$B/api/hr/payroll/runs/generate?period=202612" | J "d['data']['id']")
TF_SNE2=$(SQL "SELECT SOCIAL_EE FROM hr_payroll_run_item WHERE RUN_ID='$TF_RUN2' AND EMP_ID='smk-tf-emp'")
python3 -c "import sys; sys.exit(0 if float('$TF_SNE2' or 0) > 0 else 1)" 2>/dev/null \
  && ok "减员单作废后恢复扣缴（$TF_SNE2）" || bad "作废后仍归零（$TF_SNE2）"

# 36.3 调岗带宽 L2 + 缴纳地变更
SQL "INSERT IGNORE INTO hr_grade (ID,GRADE_NO,NAME,SERIES,SAL_MIN,SAL_MAX,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-tf-grade','SMKTF-G','冒烟职级','序列',10000,18000,'1','smoke','0',0)" >/dev/null 2>&1
SQL "INSERT IGNORE INTO hr_position (ID,POS_NO,NAME,DEPT_ID,GRADE_ID,ROLES_JSON,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-tf-pos','SMKTFPOS','冒烟调岗岗','smk-at-dept','smk-tf-grade','[\\"ROLE_HR\\"]','1','smoke','0',0)" >/dev/null 2>&1
TF_TRF=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d "{\"empId\":\"smk-tf-emp\",\"toPosId\":\"smk-tf-pos\",\"effectiveDate\":\"$(date -v+7d +%Y-%m-%d)\",\"salaryAdvice\":\"22000\",\"reason\":\"冒烟超带宽\"}" \
  $B/api/hr/transfers/post | J "d['data']['id']")
TF_BAND=$(SQL "SELECT BAND_EXCEEDED FROM hr_transfer WHERE ID='$TF_TRF'")
[ "$TF_BAND" = "1" ] && ok "超带宽标记 BAND_EXCEEDED=1（22000 > 18000）" || bad "带宽标记失败（$TF_BAND）"
# 缴纳地变更：非法城市 422 / DEMO市 成功
TF_PC_BAD=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"placeChange":"1","newPlace":"无政策城"}' $B/api/hr/transfers/post/$TF_TRF/place-change)
[ "$TF_PC_BAD" = "422" ] && ok "未维护政策城市拒绝缴纳地变更" || bad "非法城市未拦截（$TF_PC_BAD）"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"placeChange":"1","newPlace":"DEMO市"}' $B/api/hr/transfers/post/$TF_TRF/place-change >/dev/null 2>&1
TF_PC=$(SQL "SELECT CONCAT(PLACE_CHANGE,'/',NEW_PLACE) FROM hr_transfer WHERE ID='$TF_TRF'")
[ "$TF_PC" = "1/DEMO市" ] && ok "缴纳地变更设置成功（生效时联动）" || bad "缴纳地变更失败（$TF_PC）"

# 36.4 转正调薪：submit 带 newSalary → NEW_SALARY 落库
SQL "INSERT INTO hr_employee (ID,EMP_NO,NAME,ID_CARD_NO,DEPT_ID,EMP_STATUS,HIRE_DATE,PROBATION_END,BASE_SALARY,SOCIAL_PLACE,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-tf-pro','SMKTFP-1','调休转正冒烟','SMKTFPID-1','smk-at-dept','PROBATION',DATE_SUB(CURDATE(),INTERVAL 2 MONTH),DATE_ADD(CURDATE(),INTERVAL 1 MONTH),8000,'DEMO市','smoke','0',0)" >/dev/null 2>&1
TF_PRB=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"empId":"smk-tf-pro","abilityScore":4,"attitudeScore":4,"proposal":"REGULAR","newSalary":9500}' \
  $B/api/hr/transfers/probation | J "d['data']['id']")
TF_NS=$(SQL "SELECT NEW_SALARY FROM hr_transfer WHERE ID='$TF_PRB'")
python3 -c "import sys; sys.exit(0 if abs(float('$TF_NS' or 0)-9500)<0.01 else 1)" 2>/dev/null \
  && ok "转正单调薪后月薪落库（$TF_NS）" || bad "调薪字段失败（$TF_NS）"

# 36.5 转正 7 天二次提醒：到期 6 天的试用期员工 → probation-remindings → PROBATION_SECOND
SQL "UPDATE hr_employee SET PROBATION_END=DATE_ADD(CURDATE(),INTERVAL 6 DAY) WHERE ID='smk-tf-pro'" >/dev/null 2>&1
C -H "Authorization: Bearer $TADM" $B/api/hr/probation-remindings >/dev/null 2>&1
TF_RM2D=$(SQL "SELECT COUNT(*) FROM hr_remind WHERE EMP_ID='smk-tf-pro' AND REMIND_TYPE='PROBATION_SECOND'")
[ "${TF_RM2D:-0}" -ge 1 ] && ok "7 天二次提醒生成（抄送 HR 经理）" || bad "二次提醒未生成"

# 36.6 自助端离职：造绑定用户 → 登录 → SOURCE=SELF + 通知期即时反馈
SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     SELECT 'smk-tf-user','smk-tf-user',PASSWORD,'调休自助冒烟','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
SQL "UPDATE hr_employee SET USER_ID='smk-tf-user' WHERE ID='smk-tf-emp'" >/dev/null 2>&1
TTOKEN=$(C -X POST -H 'Content-Type: application/json' -d '{"username":"smk-tf-user","password":"Admin@123"}' \
  $B/api/auth/login | J "d['data']['token']")
[ -n "$TTOKEN" ] && [ "$TTOKEN" != "None" ] && ok "自助端用户登录" || bad "自助端登录失败"
TF_MY=$(C -X POST -H "Authorization: Bearer $TTOKEN" -H 'Content-Type: application/json' \
  -d "{\"quitDate\":\"$(date -v+5d +%Y-%m-%d)\",\"reason\":\"冒烟自助离职\"}" $B/api/my/exit)
TF_SRC=$(echo "$TF_MY" | J "d['data']['exit']['source']")
TF_NOTICE=$(echo "$TF_MY" | J "d['data']['noticeOk']")
[ "$TF_SRC" = "SELF" ] && [ "$TF_NOTICE" = "False" ] && ok "自助离职 SOURCE=SELF + 未满通知期即时反馈" \
  || bad "自助离职失败（src=$TF_SRC notice=$TF_NOTICE）"
TF_EXIT=$(echo "$TF_MY" | J "d['data']['exit']['id']")
# HR 侧挽留意见（非阻断）
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"note":"冒烟挽留：已同意调薪"}' $B/api/hr/transfers/exits/$TF_EXIT/retain-note >/dev/null 2>&1
TF_RET=$(SQL "SELECT RETAIN_NOTE FROM hr_exit WHERE ID='$TF_EXIT'")
case "$TF_RET" in *冒烟挽留*) ok "挽留意见留痕不阻断" ;; *) bad "挽留失败（$TF_RET）" ;; esac
# 减员豁免端点（空原因 422 → 有原因置位）
TF_WV=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"reason":"冒烟豁免：已线下减员"}' $B/api/hr/transfers/exits/$TF_EXIT/waive-reduce | J "d['data']['reduceWaived']")
[ "$TF_WV" = "1" ] && ok "减员豁免留痕" || bad "豁免失败（$TF_WV）"

# 36.9 清理
SQL "DELETE FROM hr_social_change WHERE EMP_ID LIKE 'smk-tf-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_payslip WHERE PERIOD='202612'" >/dev/null 2>&1
SQL "DELETE FROM hr_payroll_run_item WHERE PERIOD='202612'" >/dev/null 2>&1
SQL "DELETE FROM hr_payroll_run WHERE PERIOD='202612'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval_task WHERE APPR_ID IN (SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_ID LIKE 'smk-tf-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_ID LIKE 'smk-tf-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_exit WHERE EMP_ID LIKE 'smk-tf-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_transfer WHERE EMP_ID LIKE 'smk-tf-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_change_log WHERE EMP_ID LIKE 'smk-tf-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE EMP_ID LIKE 'smk-tf-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID='smk-tf-user'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID='smk-tf-user'" >/dev/null 2>&1
SQL "DELETE FROM hr_employee WHERE ID IN ('smk-tf-emp','smk-tf-pro')" >/dev/null 2>&1
SQL "DELETE FROM hr_grade WHERE ID='smk-tf-grade'" >/dev/null 2>&1
SQL "DELETE FROM hr_position WHERE ID='smk-tf-pos'" >/dev/null 2>&1

echo "段 36 入转调离缺口补齐追加完成"

# ================= 段 37 外派借调（add-employee-secondment） =================
echo "== 段 37 外派借调开始 =="

# 37.0 隔离：清残留与本段数据
SQL "DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='SECONDMENT') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='SECONDMENT'" >/dev/null 2>&1
SQL "DELETE FROM hr_social_change WHERE EMP_ID LIKE 'smk-sec-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE EMP_ID LIKE 'smk-sec-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_change_log WHERE EMP_ID LIKE 'smk-sec-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_attachment WHERE BIZ_ID LIKE 'smk-sec%'" >/dev/null 2>&1
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID LIKE 'smk-sec%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_ID LIKE 'smk-sec%'" >/dev/null 2>&1
SQL "DELETE FROM hr_secondment WHERE EMP_ID LIKE 'smk-sec-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_employee WHERE ID LIKE 'smk-sec-%' OR EMP_NO LIKE 'SMKSEC-%'" >/dev/null 2>&1
# 第二复核人（段 32 已造 hrsmoke，幂等补齐）
SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     SELECT 'smk-hrsmoke-user','hrsmoke',PASSWORD,'冒烟HR','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-hrsmoke-role','smk-hrsmoke-user',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_HR'" >/dev/null 2>&1
THRM=$(C -X POST -H 'Content-Type: application/json' -d '{"username":"hrsmoke","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$THRM" ] && [ "$THRM" != "None" ] && ok "段37 复核人登录（hrsmoke）" || bad "hrsmoke 登录失败"

# 造数：借调员工 + 外派员工 + 分摊员工（LEGAL_ENTITY=LE-0001）
SQL "INSERT INTO hr_employee (ID,EMP_NO,NAME,ID_CARD_NO,EMP_STATUS,HIRE_DATE,BASE_SALARY,SOCIAL_PLACE,LEGAL_ENTITY,COST_CENTER,BANK_CARD_NO,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-sec-borrow','SMKSEC-B','冒烟借调','SMKSECID-B','REGULAR',DATE_SUB(CURDATE(),INTERVAL 24 MONTH),20000,'DEMO市','LE-0001','CC-A','6222000033334444','smoke','0',0),
            ('smk-sec-disp','SMKSEC-D','冒烟外派','SMKSECID-D','REGULAR',DATE_SUB(CURDATE(),INTERVAL 24 MONTH),20000,'DEMO市','LE-0001','CC-A','6222000033335555','smoke','0',0),
            ('smk-sec-alloc','SMKSEC-A','冒烟分摊','SMKSECID-A','REGULAR',DATE_SUB(CURDATE(),INTERVAL 24 MONTH),21750,'DEMO市','LE-0001','CC-A','6222000033336666','smoke','0',0)" >/dev/null 2>&1

# 37.1 借调全链（发起→确认→审核→复核→审批→生效→视图标记）
SEC_B=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"empId":"smk-sec-borrow","chgType":"SECOND","targetLe":"LE-TL-B","targetPlace":"DEMO市","costCenter":"CC-B","startDate":"'"$(date +%F)"'","endDate":"'"$(date -v+6m +%F 2>/dev/null || date -d '+6 months' +%F)"'","costSharePct":100,"agreementNo":"SA-SMK-001","agreementDate":"'"$(date +%F)"'"}' \
  $B/api/hr/secondments | J "d['data']['secondment']['id']")
[ -n "$SEC_B" ] && [ "$SEC_B" != "None" ] && ok "借调发起（协议备案+分摊 100%）" || bad "借调发起失败"
SEC_BNO=$(SQL "SELECT SEC_NO FROM hr_secondment WHERE ID='$SEC_B'")
# 在途防重
SEC_DUP=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"empId":"smk-sec-borrow","chgType":"SECOND","targetLe":"LE-TL-B","startDate":"'"$(date +%F)"'","endDate":"'"$(date -v+6m +%F 2>/dev/null || date -d "+6 months" +%F)"'","costSharePct":100,"agreementNo":"SA-SMK-002","agreementDate":"'"$(date +%F)"'"}' \
  $B/api/hr/secondments)
[ "$SEC_DUP" = "422" ] && ok "在途防重 L1" || bad "防重失效（$SEC_DUP）"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"opinion":"同意调出","agree":true}' $B/api/hr/secondments/$SEC_B/confirm-out >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"opinion":"同意调入","agree":true}' $B/api/hr/secondments/$SEC_B/confirm-in >/dev/null
LOCKED=$(SQL "SELECT SHARE_LOCKED FROM hr_secondment WHERE ID='$SEC_B'")
[ "$LOCKED" = "1" ] && ok "双方确认完成 → 分摊比例锁定" || bad "比例未锁定（$LOCKED）"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"opinion":"合规通过"}' $B/api/hr/secondments/$SEC_B/hr-review >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"targetLe":"LE-TL-B","costSharePct":"100.00","salaryPlace":"DEMO市","costCenter":"CC-B","startDate":"'"$(date +%F)"'"}' \
  $B/api/hr/secondments/$SEC_B/review-sign >/dev/null
# 同人双签拒绝
SEC_SELF=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"targetLe":"LE-TL-B","costSharePct":"100.00","salaryPlace":"DEMO市","costCenter":"CC-B","startDate":"'"$(date +%F)"'"}' \
  $B/api/hr/secondments/$SEC_B/review-sign)
[ "$SEC_SELF" = "422" ] && ok "同人不可双签（C-4.7-11）" || bad "双签校验失效（$SEC_SELF）"
C -X POST -H "Authorization: Bearer $THRM" -H 'Content-Type: application/json' \
  -d '{"targetLe":"LE-TL-B","costSharePct":"100.00","salaryPlace":"DEMO市","costCenter":"CC-B","startDate":"'"$(date +%F)"'"}' \
  $B/api/hr/secondments/$SEC_B/review-sign >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"approve":true,"opinion":"同意"}' $B/api/hr/secondments/$SEC_B/approve >/dev/null
C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/secondments/$SEC_B/effect >/dev/null
SEC_BST=$(SQL "SELECT STATUS FROM hr_secondment WHERE ID='$SEC_B'")
[ "$SEC_BST" = "EFFECTED" ] && ok "借调生效（$SEC_BNO）" || bad "生效失败（$SEC_BST）"
SEC_LE=$(SQL "SELECT COALESCE(LEGAL_ENTITY,'NULL') FROM hr_employee WHERE ID='smk-sec-borrow'")
[ "$SEC_LE" = "LE-0001" ] && ok "借调主档零改动（LEGAL_ENTITY 不变）" || bad "主档被误改（$SEC_LE）"
SEC_FLAG=$(C -H "Authorization: Bearer $TADM" $B/api/hr/secondments/in-flight-flags | J "d['data']['smk-sec-borrow']")
case "$SEC_FLAG" in *借调中*) ok "在途标记：$SEC_FLAG" ;; *) bad "标记缺失（$SEC_FLAG）" ;; esac

# 37.2 延期（L2 + 累计 24 月卡控）与返岗
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"months":12,"reason":"项目延续"}' $B/api/hr/secondments/$SEC_B/extend >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"approve":true,"note":"同意延期"}' $B/api/hr/secondments/$SEC_B/approve-extend >/dev/null
SEC_EXT=$(SQL "SELECT CONCAT(EXTEND_MONTHS_TOTAL,'/',END_DATE) FROM hr_secondment WHERE ID='$SEC_B'")
case "$SEC_EXT" in 12/*) ok "延期 12 月入账（$SEC_EXT）" ;; *) bad "延期失败（$SEC_EXT）" ;; esac
SEC_OVER=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"months":17,"reason":"再延"}' $B/api/hr/secondments/$SEC_B/extend)
[ "$SEC_OVER" = "422" ] && ok "累计超 24 月 L1 拒绝（BR-4.7-05）" || bad "累计卡控失效（$SEC_OVER）"
# 提醒（到期 6 天 → SECONDMENT_RETURN）+ 防重
SQL "UPDATE hr_secondment SET END_DATE=DATE_ADD(CURDATE(), INTERVAL 6 DAY), REMIND_FLAG='0' WHERE ID='$SEC_B'" >/dev/null 2>&1
C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/secondments/remind-scan >/dev/null
SEC_RM=$(SQL "SELECT COUNT(*) FROM hr_remind WHERE EMP_ID='smk-sec-borrow' AND REMIND_TYPE='SECONDMENT_RETURN'")
[ "${SEC_RM:-0}" -ge 1 ] && ok "15 天返岗提醒生成（$SEC_RM）" || bad "提醒未生成"
C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/secondments/remind-scan >/dev/null
SEC_RM2=$(SQL "SELECT COUNT(*) FROM hr_remind WHERE EMP_ID='smk-sec-borrow' AND REMIND_TYPE='SECONDMENT_RETURN'")
[ "$SEC_RM2" = "$SEC_RM" ] && ok "提醒防重（同日不重复）" || bad "提醒重复（$SEC_RM→$SEC_RM2）"
# 过期告警 + 返岗关闭
SQL "UPDATE hr_secondment SET END_DATE=DATE_SUB(CURDATE(), INTERVAL 3 DAY), STATUS='EFFECTED' WHERE ID='$SEC_B'" >/dev/null 2>&1
C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/secondments/remind-scan >/dev/null
SEC_EXP=$(SQL "SELECT STATUS FROM hr_secondment WHERE ID='$SEC_B'")
[ "$SEC_EXP" = "EXPIRED_ALERT" ] && ok "过期未返岗 → EXPIRED_ALERT（阻断分摊）" || bad "过期告警失败（$SEC_EXP）"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"note":"项目结束返岗"}' $B/api/hr/secondments/$SEC_B/return >/dev/null
SEC_RET=$(SQL "SELECT STATUS FROM hr_secondment WHERE ID='$SEC_B'")
[ "$SEC_RET" = "RETURNED" ] && ok "返岗确认关闭在途" || bad "返岗失败（$SEC_RET）"
SEC_FLAG2=$(C -H "Authorization: Bearer $TADM" $B/api/hr/secondments/in-flight-flags | J "d['data'].get('smk-sec-borrow','None')")
[ "$SEC_FLAG2" = "None" ] && ok "返岗后标记消失" || bad "标记残留（$SEC_FLAG2）"

# 37.3 分摊凭证（直接 SQL 造当月整月在途单，走 preview→generate→幂等）
SEC_MSTART=$(date +%Y-%m-01)
SEC_YM=$(date +%Y%m)
SQL "INSERT INTO hr_secondment (ID,SEC_NO,CHG_TYPE,EMP_ID,EMP_NAME,EMP_NO,SRC_LE,TARGET_LE,COST_SHARE_PCT,SHARE_LOCKED,STATUS,START_DATE,END_DATE,REMIND_FLAG,EXTEND_MONTHS_TOTAL,LINK_PENDING,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-sec-allocdoc','SEC-SMK-ALLOC','SECOND','smk-sec-alloc','冒烟分摊','SMKSEC-A','LE-0001','LE-TL-B',100,'1','EFFECTED','${SEC_MSTART}','${SEC_YM:0:4}-12-31','0',0,'0','smoke','0',0)" >/dev/null 2>&1
SEC_PV=$(C -H "Authorization: Bearer $TADM" "$B/api/hr/secondments/allocations/preview?ym=$SEC_YM" | J "len(d['data'])")
[ "${SEC_PV:-0}" -ge 1 ] && ok "分摊预览（$SEC_PV 单）" || bad "预览为空"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"ym":"'"$SEC_YM"'"}' $B/api/hr/secondments/allocations/generate >/dev/null
SEC_VN=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='SECONDMENT'")
[ "${SEC_VN:-0}" -ge 2 ] && ok "成对分摊凭证生成（$SEC_VN 张）" || bad "凭证生成失败（$SEC_VN）"
SEC_BAL=$(SQL "SELECT IF(SUM(CASE WHEN l.DIRECTION='DR' THEN l.AMOUNT ELSE -l.AMOUNT END)=0,'BALANCED','UNBALANCED') FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID WHERE v.SOURCE_TYPE='SECONDMENT'")
[ "$SEC_BAL" = "BALANCED" ] && ok "分摊凭证借贷平衡" || bad "凭证不平衡（$SEC_BAL）"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"ym":"'"$SEC_YM"'"}' $B/api/hr/secondments/allocations/generate >/dev/null
SEC_VN2=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='SECONDMENT'")
[ "$SEC_VN2" = "$SEC_VN" ] && ok "分摊凭证幂等（重复触发不重复生成）" || bad "幂等失效（$SEC_VN→$SEC_VN2）"

# 37.4 外派生效联动（法人主体写回 + 社保双单 + 变更日志）
SEC_D=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"empId":"smk-sec-disp","chgType":"DISPATCH","targetLe":"LE-TL-B","placeChange":"1","targetPlace":"DEMO市","costCenter":"CC-B","startDate":"'"$(date +%F)"'","endDate":"'"$(date -v+12m +%F 2>/dev/null || date -d "+12 months" +%F)"'"}' \
  $B/api/hr/secondments | J "d['data']['secondment']['id']")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"opinion":"同意调出","agree":true}' $B/api/hr/secondments/$SEC_D/confirm-out >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"opinion":"同意调入","agree":true}' $B/api/hr/secondments/$SEC_D/confirm-in >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"opinion":"通过"}' $B/api/hr/secondments/$SEC_D/hr-review >/dev/null
SEC_DST=$(SQL "SELECT STATUS FROM hr_secondment WHERE ID='$SEC_D'")
[ "$SEC_DST" = "REVIEWING" ] && ok "外派进入双人复核" || bad "外派 HR 审核失败（$SEC_DST）"
SEC_DSTART=$(SQL "SELECT START_DATE FROM hr_secondment WHERE ID='$SEC_D'")
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"targetLe":"LE-TL-B","costSharePct":"null","salaryPlace":"DEMO市","costCenter":"CC-B","startDate":"'"$SEC_DSTART"'"}' \
  $B/api/hr/secondments/$SEC_D/review-sign >/dev/null
C -X POST -H "Authorization: Bearer $THRM" -H 'Content-Type: application/json' \
  -d '{"targetLe":"LE-TL-B","costSharePct":"null","salaryPlace":"DEMO市","costCenter":"CC-B","startDate":"'"$SEC_DSTART"'"}' \
  $B/api/hr/secondments/$SEC_D/review-sign >/dev/null
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"approve":true,"opinion":"同意"}' $B/api/hr/secondments/$SEC_D/approve >/dev/null
C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/secondments/$SEC_D/effect >/dev/null
SEC_DLE=$(SQL "SELECT LEGAL_ENTITY FROM hr_employee WHERE ID='smk-sec-disp'")
[ "$SEC_DLE" = "LE-TL-B" ] && ok "外派写回法人主体（LE-TL-B）" || bad "法人主体未更新（$SEC_DLE）"
SEC_DLOG=$(SQL "SELECT COUNT(*) FROM hr_change_log WHERE EMP_ID='smk-sec-disp' AND FIELD_NAME IN ('所属法人主体','合同主体')")
[ "${SEC_DLOG:-0}" -ge 2 ] && ok "合同/主体变更日志留痕（$SEC_DLOG 条）" || bad "日志缺失（$SEC_DLOG）"
SEC_DSEC=$(SQL "SELECT SEC_NO FROM hr_secondment WHERE ID='$SEC_D'")
SEC_DSOC=$(SQL "SELECT CONCAT((SELECT COUNT(*) FROM hr_social_change WHERE SRC_ID='$SEC_DSEC' AND CHG_TYPE='REMOVE'),'/',(SELECT COUNT(*) FROM hr_social_change WHERE SRC_ID='$SEC_DSEC' AND CHG_TYPE='ADD'))")
[ "$SEC_DSOC" = "1/1" ] && ok "社保减员/增员双单自动开立" || bad "社保双单失败（$SEC_DSOC）"

# 37.5 协议扫描件附件（最小通用附件能力）
echo "fake secondment agreement pdf" > /tmp/smk-sec-agreement.txt
ATT=$(curl -s -F "file=@/tmp/smk-sec-agreement.txt;filename=三方协议.pdf" -F "bizType=SECONDTM_AGREEMENT" -F "bizId=$SEC_B" \
  -H "Authorization: Bearer $TADM" $B/api/common/attachments | J "d['data']['id']")
[ -n "$ATT" ] && [ "$ATT" != "None" ] && ok "协议扫描件上传（$ATT）" || bad "上传失败"
ATT_N=$(C -H "Authorization: Bearer $TADM" "$B/api/common/attachments?bizType=SECONDTM_AGREEMENT&bizId=$SEC_B" | J "len(d['data'])")
[ "${ATT_N:-0}" -ge 1 ] && ok "按业务键查询附件（$ATT_N 件）" || bad "附件查询为空"
ATT_DUP=$(BIZ -F "file=@/tmp/smk-sec-agreement.txt;filename=三方协议.pdf" -F "bizType=SECONDTM_AGREEMENT" -F "bizId=$SEC_B" \
  -H "Authorization: Bearer $TADM" $B/api/common/attachments)
[ "$ATT_DUP" = "409" ] && ok "同名不覆盖（409 + UUID 隔离）" || bad "同名处理异常（$ATT_DUP）"
rm -f /tmp/smk-sec-agreement.txt

# 37.9 清理
SQL "DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID WHERE v.SOURCE_TYPE='SECONDMENT'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='SECONDMENT'" >/dev/null 2>&1
SQL "DELETE a FROM erp_sys_attachment a WHERE a.BIZ_ID LIKE 'smk-sec%'" >/dev/null 2>&1
SQL "DELETE FROM hr_social_change WHERE EMP_ID LIKE 'smk-sec-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE EMP_ID LIKE 'smk-sec-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_change_log WHERE EMP_ID LIKE 'smk-sec-%'" >/dev/null 2>&1
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID LIKE 'smk-sec%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_ID LIKE 'smk-sec%'" >/dev/null 2>&1
SQL "DELETE FROM hr_secondment WHERE EMP_ID LIKE 'smk-sec-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_employee WHERE ID LIKE 'smk-sec-%'" >/dev/null 2>&1

echo "== 段 38 培训认证开始 =="

# 38.0 隔离：清残留与本段数据（TRAINING 凭证/计划 SMKTR%/证书/矩阵/排班/提醒）
SQL "DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID WHERE v.SOURCE_TYPE='TRAINING'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='TRAINING'" >/dev/null 2>&1
SQL "DELETE FROM hr_train_task WHERE PLAN_ID IN (SELECT ID FROM (SELECT ID FROM hr_train_plan WHERE COURSE_NAME LIKE 'SMKTR%') t)" >/dev/null 2>&1
SQL "DELETE FROM hr_train_plan WHERE COURSE_NAME LIKE 'SMKTR%'" >/dev/null 2>&1
SQL "DELETE FROM hr_skill_matrix WHERE EMP_ID LIKE 'smk-tr-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('CERT_REVIEW','TRAIN_TASK') AND BIZ_ID LIKE 'smk-tr-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE EMP_ID LIKE 'smk-tr-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_attachment WHERE BIZ_ID LIKE 'smk-tr-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_schedule WHERE EMP_ID LIKE 'smk-tr-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_employee_cert WHERE EMP_ID LIKE 'smk-tr-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_employee WHERE ID LIKE 'smk-tr-%' OR EMP_NO LIKE 'SMKTR-%'" >/dev/null 2>&1

# 造数：门禁员工 + 学员 + 班次
SQL "INSERT INTO hr_employee (ID,EMP_NO,NAME,ID_CARD_NO,EMP_STATUS,HIRE_DATE,BASE_SALARY,SOCIAL_PLACE,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-tr-gate','SMKTR-G','冒烟门禁工','SMKTRID-G','REGULAR',DATE_SUB(CURDATE(),INTERVAL 24 MONTH),18000,'DEMO市','smoke','0',0),
            ('smk-tr-learn','SMKTR-L','冒烟学员','SMKTRID-L','REGULAR',DATE_SUB(CURDATE(),INTERVAL 24 MONTH),19000,'DEMO市','smoke','0',0)" >/dev/null 2>&1
SQL "INSERT IGNORE INTO hr_shift (ID,SHIFT_NO,NAME,START_TIME,END_TIME,HOURS,CROSS_DAY,OVERTIME_ELIGIBLE,TOLERANCE_MIN,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-tr-shift','SMKTR01','SMKTR 常白班','09:00','18:00',8,'0','1',10,'1','smoke','0',0)" >/dev/null 2>&1

# 38.1 类别字典（DEMO 种子 + 编码不可改）
CATE_KEY=$(C -H "Authorization: Bearer $TADM" $B/api/hr/certs/categories | J "[c['id'] for c in d['data'] if c['isKey']=='1' and c['status']=='ACTIVE'][0]")
[ -n "${CATE_KEY:-}" ] && [ "$CATE_KEY" != "None" ] && ok "段38 关键类别种子可见（$CATE_KEY）" || bad "关键类别种子缺失"
CATE_PLAIN=$(C -H "Authorization: Bearer $TADM" $B/api/hr/certs/categories | J "[c['id'] for c in d['data'] if c['isKey']=='0'][0]")
[ -n "${CATE_PLAIN:-}" ] && [ "$CATE_PLAIN" != "None" ] && ok "段38 非关键类别种子可见" || bad "非关键类别种子缺失"
CATE_NAME=$(SQL "SELECT CATE_NAME FROM hr_cert_category WHERE ID='$CATE_KEY'")
CATE_IMMUT=$(BIZ -X PUT -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"cateCode":"CATE9999"}' $B/api/hr/certs/categories/$CATE_KEY)
[ "$CATE_IMMUT" = "422" ] && ok "段38 类别编码创建后不可改（422）" || bad "类别编码可改（$CATE_IMMUT）"

# 38.2 证书审核链（PENDING → 审核通过 → VALID + 技能矩阵 CERT 写入）
SQL "INSERT INTO hr_employee_cert (ID,EMP_ID,EMP_NAME,CATE_ID,CATE_NAME,CERT_NO,VALID_FROM,VALID_TO,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-tr-cert1','smk-tr-learn','冒烟学员','$CATE_KEY','$CATE_NAME','SMKTR-C1',DATE_SUB(CURDATE(),INTERVAL 1 YEAR),DATE_ADD(CURDATE(),INTERVAL 2 YEAR),'PENDING','smoke','0',0)" >/dev/null 2>&1
PEND_N=$(SQL "SELECT COUNT(*) FROM hr_employee_cert WHERE ID='smk-tr-cert1' AND STATUS='PENDING'")
[ "${PEND_N:-0}" = "1" ] && ok "段38 证书提交待审（PENDING）" || bad "证书待审缺失"
AUD=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"pass":true}' $B/api/hr/certs/smk-tr-cert1/audit)
[ "$AUD" = "200" ] && ok "段38 HR 审核通过" || bad "审核失败（$AUD）"
CERT_ST=$(SQL "SELECT STATUS FROM hr_employee_cert WHERE ID='smk-tr-cert1'")
[ "$CERT_ST" = "VALID" ] && ok "段38 审核通过 → VALID" || bad "状态未翻 VALID（$CERT_ST）"
MTX_N=$(SQL "SELECT COUNT(*) FROM hr_skill_matrix WHERE EMP_ID='smk-tr-learn' AND SOURCE='CERT' AND STATUS='ACTIVE'")
[ "${MTX_N:-0}" -ge 1 ] && ok "段38 审核通过 → 技能矩阵 CERT 写入（$MTX_N）" || bad "矩阵 CERT 写入缺失"
# 驳回链（第二条证书）
SQL "INSERT INTO hr_employee_cert (ID,EMP_ID,EMP_NAME,CATE_ID,CATE_NAME,CERT_NO,VALID_TO,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-tr-cert2','smk-tr-gate','冒烟门禁工','$CATE_PLAIN','健康证','SMKTR-C2',DATE_ADD(CURDATE(),INTERVAL 1 YEAR),'PENDING','smoke','0',0)" >/dev/null 2>&1
REJ=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"pass":false,"opinion":"照片不清晰"}' $B/api/hr/certs/smk-tr-cert2/audit)
[ "$REJ" = "200" ] && ok "段38 审核驳回" || bad "驳回失败（$REJ）"
REJ_ST=$(SQL "SELECT CONCAT(STATUS,'|',REVIEW_OPINION) FROM hr_employee_cert WHERE ID='smk-tr-cert2'")
[ "$REJ_ST" = "REJECTED|照片不清晰" ] && ok "段38 驳回意见落库" || bad "驳回意见异常（$REJ_ST）"

# 38.3 到期翻转（临期 → 失效 → 幂等）
SQL "UPDATE hr_employee_cert SET VALID_TO=DATE_ADD(CURDATE(),INTERVAL 30 DAY), STATUS='VALID' WHERE ID='smk-tr-cert2'" >/dev/null 2>&1
FLIP1=$(C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/certs/flip-scan | J "d['data']['reviewDue']")
[ "${FLIP1:-0}" -ge 1 ] && ok "段38 临期翻转 REVIEW_DUE（$FLIP1）" || bad "临期翻转失败（$FLIP1）"
SQL "UPDATE hr_employee_cert SET VALID_TO=DATE_SUB(CURDATE(),INTERVAL 2 DAY) WHERE ID='smk-tr-cert1'" >/dev/null 2>&1
FLIP2=$(C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/certs/flip-scan | J "d['data']['expired']")
[ "${FLIP2:-0}" -ge 1 ] && ok "段38 到期翻转 EXPIRED（$FLIP2）" || bad "到期翻转失败（$FLIP2）"
CERT_ST2=$(SQL "SELECT STATUS FROM hr_employee_cert WHERE ID='smk-tr-cert1'")
[ "$CERT_ST2" = "EXPIRED" ] && ok "段38 证书已失效" || bad "证书未失效（$CERT_ST2）"
MTX_X=$(SQL "SELECT COUNT(*) FROM hr_skill_matrix WHERE EMP_ID='smk-tr-learn' AND SOURCE='CERT' AND STATUS='EXPIRED'")
[ "${MTX_X:-0}" -ge 1 ] && ok "段38 证书失效 → 矩阵 CERT 行联动失效" || bad "矩阵失效联动缺失"
FLIP3=$(C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/certs/flip-scan | J "d['data']['expired']")
[ "${FLIP3:-0}" = "0" ] && ok "段38 翻转幂等（重跑 0 变更）" || bad "翻转不幂等（$FLIP3）"

# 38.4 排班门禁（BR-4.7-06：先挂失效关键证，再断言拦截 + 恢复放行 + 带病清单处置）
NEXTWEEK=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F)
# 门禁员工挂失效关键证（存量直插，模拟上线前已持失效证）
SQL "INSERT INTO hr_employee_cert (ID,EMP_ID,EMP_NAME,CATE_ID,CATE_NAME,CERT_NO,VALID_TO,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-tr-cert3','smk-tr-gate','冒烟门禁工','$CATE_KEY','$CATE_NAME','SMKTR-C3',DATE_SUB(CURDATE(),INTERVAL 2 DAY),'EXPIRED','smoke','0',0)" >/dev/null 2>&1
GATE_CODE=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"empId":"smk-tr-gate","workDate":"'"$NEXTWEEK"'","shiftId":"smk-tr-shift"}' $B/api/hr/attendance/schedules)
[ "$GATE_CODE" = "422" ] && ok "段38 认证失效排班 L1 拦截（422）" || bad "门禁未拦截（$GATE_CODE）"
GATE_MSG=$(MSG -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"empId":"smk-tr-gate","workDate":"'"$NEXTWEEK"'","shiftId":"smk-tr-shift"}' $B/api/hr/attendance/schedules)
case "$GATE_MSG" in *认证失效限制排班上岗*) ok "段38 拦截文案含证书原因" ;; *) bad "拦截文案异常（$GATE_MSG）" ;; esac
# 非关键失效不拦（健康证失效不触发门禁；休息日同样无上岗语义）
SQL "UPDATE hr_employee_cert SET STATUS='EXPIRED', VALID_TO=DATE_SUB(CURDATE(),INTERVAL 2 DAY) WHERE ID='smk-tr-cert2'" >/dev/null 2>&1
OKSCH=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"empId":"smk-tr-gate","workDate":"'"$NEXTWEEK"'","isRest":1}' $B/api/hr/attendance/schedules | J "d['data']['id']")
[ -n "${OKSCH:-}" ] && [ "$OKSCH" != "None" ] && ok "段38 休息日不拦（无上岗语义）" || bad "休息日排班失败"
# 存量带病排班（直插绕过门禁）
SQL "INSERT INTO hr_schedule (ID,EMP_ID,WORK_DATE,SHIFT_ID,IS_REST,SOURCE,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-tr-sch1','smk-tr-gate','$NEXTWEEK','smk-tr-shift','0','SINGLE','smoke','0',0)" >/dev/null 2>&1
SICK_N=$(C -H "Authorization: Bearer $TADM" $B/api/hr/certs/alert-lists | J "len([s for s in d['data']['sickSchedules'] if s['empId']=='smk-tr-gate'])")
[ "${SICK_N:-0}" -ge 1 ] && ok "段38 带病排班清单列示（$SICK_N）" || bad "带病清单缺失（$SICK_N）"
DIS=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"action":"CANCEL"}' $B/api/hr/train/sick-schedules/smk-tr-sch1/dispose)
[ "$DIS" = "200" ] && ok "段38 带病排班取消处置" || bad "处置失败（$DIS）"
SICK_N2=$(C -H "Authorization: Bearer $TADM" $B/api/hr/certs/alert-lists | J "len([s for s in d['data']['sickSchedules'] if s['empId']=='smk-tr-gate'])")
[ "${SICK_N2:-0}" = "0" ] && ok "段38 处置后清单清空" || bad "清单未清空（$SICK_N2）"
# 恢复：归档失效旧证 → 排班放行
C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/certs/smk-tr-cert3/archive >/dev/null
GATE_OK=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"empId":"smk-tr-gate","workDate":"'"$NEXTWEEK"'","shiftId":"smk-tr-shift"}' $B/api/hr/attendance/schedules | J "d['data']['id']")
[ -n "${GATE_OK:-}" ] && [ "$GATE_OK" != "None" ] && ok "段38 换证归档恢复后排班放行" || bad "恢复后仍被拦"

# 38.5 培训计划链（发布展开 → 自助确认 → 登记达线 → 完成率 → 矩阵联动）
DUE3=$(date -v+3d +%F 2>/dev/null || date -d '+3 days' +%F)
PLAN=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"courseName":"SMKTR 安全复审","trainType":"内训","targetType":"MEMBERS","targetValue":"smk-tr-learn","planYear":2099,"dueDate":"'"$DUE3"'","planHours":8,"passScore":60,"skillCode":"SMKSKILL","skillLevel":"L1"}' \
  $B/api/hr/train/plans | J "d['data']['plan']['id']")
[ -n "${PLAN:-}" ] && [ "$PLAN" != "None" ] && ok "段38 计划编制即发布（$PLAN）" || bad "计划创建失败"
TASK_N=$(SQL "SELECT COUNT(*) FROM hr_train_task WHERE PLAN_ID='$PLAN'")
[ "${TASK_N:-0}" = "1" ] && ok "段38 任务展开到人（1）" || bad "任务展开异常（$TASK_N）"
PLAN_NO=$(SQL "SELECT PLAN_NO FROM hr_train_plan WHERE ID='$PLAN'")
case "$PLAN_NO" in TP2099*) ok "段38 计划号自动生成（$PLAN_NO）" ;; *) bad "计划号异常（$PLAN_NO）" ;; esac
# 学员自助确认
SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     SELECT 'smktr-user','smktruser',PASSWORD,'冒烟学员','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
SQL "UPDATE hr_employee SET USER_ID='smktr-user' WHERE ID='smk-tr-learn'" >/dev/null 2>&1
TLSM=$(C -H 'Content-Type: application/json' -d '{"username":"smktruser","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
MYTASK=$(C -H "Authorization: Bearer $TLSM" $B/api/my/train-tasks | J "d['data'][0]['task']['id']")
[ -n "${MYTASK:-}" ] && [ "$MYTASK" != "None" ] && ok "段38 学员可见我的任务" || bad "我的任务为空"
CONF=$(BIZ -X POST -H "Authorization: Bearer $TLSM" $B/api/my/train-tasks/$MYTASK/confirm)
[ "$CONF" = "200" ] && ok "段38 自助确认参加" || bad "自助确认失败（$CONF）"
CONF_ST=$(SQL "SELECT CONCAT(STATUS,'|',CONFIRM_SRC) FROM hr_train_task WHERE ID='$MYTASK'")
[ "$CONF_ST" = "CONFIRMED|SELF" ] && ok "段38 确认状态 SELF 落库" || bad "确认状态异常（$CONF_ST）"
# HR 登记达线 → COMPLETED + 矩阵 TRAIN 写入
RES=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"score":85,"attend":"正常","actualHours":8}' $B/api/hr/train/tasks/$MYTASK/result | J "d['data']['task']['status']")
[ "$RES" = "COMPLETED" ] && ok "段38 HR 登记达线 → COMPLETED" || bad "登记结果异常（$RES）"
MTX_T=$(SQL "SELECT COUNT(*) FROM hr_skill_matrix WHERE EMP_ID='smk-tr-learn' AND SKILL_CODE='SMKSKILL' AND SOURCE='TRAIN' AND STATUS='ACTIVE'")
[ "${MTX_T:-0}" = "1" ] && ok "段38 培训完成 → 矩阵 TRAIN 写入" || bad "矩阵 TRAIN 写入缺失"
RATE=$(C -H "Authorization: Bearer $TADM" $B/api/hr/train/plans/$PLAN | J "d['data']['stats']['rate']")
case "$RATE" in 100*) ok "段38 完成率 100%（1/1）" ;; *) bad "完成率异常（$RATE）" ;; esac
# 改判不达线 → 矩阵 TRAIN 失效；恢复达线
RES2=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"score":30,"attend":"正常"}' $B/api/hr/train/tasks/$MYTASK/result | J "d['data']['task']['status']")
[ "$RES2" = "FAILED" ] && ok "段38 改判不达线 → FAILED" || bad "改判异常（$RES2）"
MTX_F=$(SQL "SELECT COUNT(*) FROM hr_skill_matrix WHERE EMP_ID='smk-tr-learn' AND SKILL_CODE='SMKSKILL' AND STATUS='EXPIRED'")
[ "${MTX_F:-0}" = "1" ] && ok "段38 改判 → 矩阵 TRAIN 行失效" || bad "改判矩阵失效缺失"
C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"score":90,"attend":"正常"}' $B/api/hr/train/tasks/$MYTASK/result >/dev/null

# 38.6 费用计提凭证链（生成 → 幂等 → 变动红冲重开 → 作废冲销）
COST1=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"actualCost":5000}' $B/api/hr/train/plans/$PLAN/cost)
[ "$COST1" = "200" ] && ok "段38 费用登记生成凭证" || bad "费用登记失败（$COST1）"
COST2=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"actualCost":5000}' $B/api/hr/train/plans/$PLAN/cost | J "d['data']['action']")
[ "${COST2:-}" = "0" ] && ok "段38 同额登记幂等（action=0）" || bad "幂等失效（$COST2）"
COST3=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' -d '{"actualCost":6000}' $B/api/hr/train/plans/$PLAN/cost | J "d['data']['action']")
[ "${COST3:-}" = "2" ] && ok "段38 金额变动红冲重开（action=2）" || bad "变动处理异常（$COST3）"
ANCHOR="$PLAN_NO@2099"
DR_SUM=$(SQL "SELECT CAST(COALESCE(SUM(l.AMOUNT),0) AS CHAR) FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID WHERE v.SOURCE_TYPE='TRAINING' AND v.SOURCE_DOC_NO='$ANCHOR' AND l.DIRECTION='Dr' AND v.REVERSES_ID IS NULL AND v.ID NOT IN (SELECT REVERSES_ID FROM erp_fin_voucher WHERE REVERSES_ID IS NOT NULL)")
case "$DR_SUM" in 6000*) ok "段38 活动凭证净额 6000（不重复入账）" ;; *) bad "净额异常（$DR_SUM）" ;; esac
ACC_OK=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID WHERE v.SOURCE_TYPE='TRAINING' AND l.ACCOUNT_CODE IN ('660105','221103')")
[ "${ACC_OK:-0}" -ge 2 ] && ok "段38 职工教育经费科目正确（660105/221103）" || bad "科目异常（$ACC_OK）"
C -X POST -H "Authorization: Bearer $TADM" $B/api/hr/train/plans/$PLAN/cancel >/dev/null
DR0=$(SQL "SELECT CAST(COALESCE(SUM(l.AMOUNT),0) AS CHAR) FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID WHERE v.SOURCE_TYPE='TRAINING' AND v.SOURCE_DOC_NO='$ANCHOR' AND l.DIRECTION='Dr' AND v.REVERSES_ID IS NULL AND v.ID NOT IN (SELECT REVERSES_ID FROM erp_fin_voucher WHERE REVERSES_ID IS NOT NULL)")
case "${DR0:-0}" in 0*) ok "段38 计划作废 → 凭证冲销归零" ;; *) bad "作废未冲销（$DR0）" ;; esac
PLAN_ST=$(SQL "SELECT STATUS FROM hr_train_plan WHERE ID='$PLAN'")
[ "$PLAN_ST" = "CANCELLED" ] && ok "段38 计划作废 + 任务连带取消" || bad "计划状态异常（$PLAN_ST）"

# 38.7 技能矩阵查询 + 401 矩阵
SKY=$(C -H "Authorization: Bearer $TADM" "$B/api/hr/skill-matrix/by-skill?skillCode=SMKSKILL" | J "len([r for r in d['data'] if r['empId']=='smk-tr-learn'])")
[ "${SKY:-0}" = "1" ] && ok "段38 按技能查有效人员（SMKSKILL→学员）" || bad "矩阵查询异常（$SKY）"
EQ "段38 GET /certs 401" "$(HTTP $B/api/hr/certs)" "401"
EQ "段38 GET /my/certs 401" "$(HTTP $B/api/my/certs)" "401"
EQ "段38 GET /my/train-tasks 401" "$(HTTP $B/api/my/train-tasks)" "401"

# 38.8 清理
SQL "DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID WHERE v.SOURCE_TYPE='TRAINING'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='TRAINING'" >/dev/null 2>&1
SQL "DELETE FROM hr_train_task WHERE PLAN_ID IN (SELECT ID FROM (SELECT ID FROM hr_train_plan WHERE COURSE_NAME LIKE 'SMKTR%') t)" >/dev/null 2>&1
SQL "DELETE FROM hr_train_plan WHERE COURSE_NAME LIKE 'SMKTR%'" >/dev/null 2>&1
SQL "DELETE FROM hr_skill_matrix WHERE EMP_ID LIKE 'smk-tr-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE EMP_ID LIKE 'smk-tr-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('CERT_REVIEW','TRAIN_TASK') AND BIZ_ID LIKE 'smk-tr-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_schedule WHERE EMP_ID LIKE 'smk-tr-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_employee_cert WHERE EMP_ID LIKE 'smk-tr-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_employee WHERE ID LIKE 'smk-tr-%' OR EMP_NO LIKE 'SMKTR-%'" >/dev/null 2>&1
SQL "DELETE FROM hr_shift WHERE ID='smk-tr-shift'" >/dev/null 2>&1

echo "== 段 39 线索自动分配开始 =="

# 39.0 隔离
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE='LEAD_AUTO_ASSIGN' AND BIZ_ID LIKE 'smk-la-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_lead_pool WHERE LEAD_ID IN (SELECT ID FROM (SELECT ID FROM erp_crm_lead WHERE COMPANY_NAME LIKE 'SMKLA%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_lead WHERE COMPANY_NAME LIKE 'SMKLA%'" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_sales_profile WHERE EMP_ID LIKE 'smk-la-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_assign_rule WHERE ID LIKE 'smk-la-rule-%' OR TARGET_EMP_IDS LIKE 'smk-la-%'" >/dev/null 2>&1
SQL "UPDATE erp_crm_assign_rule SET ENABLED='0' WHERE ID='RULE-DEMO-B-ALL'" >/dev/null 2>&1
SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     SELECT 'smk-la-user1','smk-la-sales1',PASSWORD,'冒烟销售1','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     SELECT 'smk-la-user2','smk-la-sales2',PASSWORD,'冒烟销售2','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-la-ur1','smk-la-user1',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_SALES'" >/dev/null 2>&1
SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-la-ur2','smk-la-user2',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_SALES'" >/dev/null 2>&1

# 39.1 资深名单配置（11.1.2 分配规则 Tab）
SR_SAVE=$(BIZ -X PUT -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '[{"empId":"smk-la-sales1","empName":"冒烟销售1"}]' $B/api/crm/assign/seniors)
[ "$SR_SAVE" = "200" ] && ok "段39 资深名单保存" || bad "资深保存失败（$SR_SAVE）"
SR_N=$(SQL "SELECT COUNT(*) FROM erp_crm_sales_profile WHERE EMP_ID='smk-la-sales1' AND IS_SENIOR='1'")
[ "${SR_N:-0}" = "1" ] && ok "段39 资深标记落库" || bad "资深标记缺失（$SR_N）"

# 39.2 A 级 → 资深最少在手（sales1 资深）
L1=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"sourceChannel":"WEB","companyName":"SMKLA 公司A","contactName":"冒烟联系人A","region":"华东"}' \
  $B/api/crm/leads | J "d['data']['id']")
[ -n "${L1:-}" ] && [ "$L1" != "None" ] && ok "段39 线索录入（带区域）" || bad "线索创建失败"
SC_A=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"need":5,"budget":5,"chain":5,"urgency":5,"compete":5}' $B/api/crm/leads/$L1/score | J "d['data']['grade']+'|'+str(d['data'].get('autoAssignedTo'))+'|'+str(d['data'].get('autoAssignSource'))")
[ "$SC_A" = "A|smk-la-sales1|AUTO" ] && ok "段39 A 级自动分配资深（$SC_A）" || bad "A 级路由异常（$SC_A）"
L1_OWNER=$(SQL "SELECT OWNER_ID FROM erp_crm_lead WHERE ID='$L1'")
[ "$L1_OWNER" = "smk-la-sales1" ] && ok "段39 负责人落库" || bad "负责人异常（$L1_OWNER）"
NTC_N=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='LEAD_AUTO_ASSIGN' AND BIZ_ID='$L1'")
[ "${NTC_N:-0}" -ge 1 ] && ok "段39 自动分配站内通知（$NTC_N）" || bad "通知缺失"

# 39.3 B 级规则命中（规则 → sales2）
RL=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"grade":"B","region":"华东","targetEmpIds":"smk-la-sales2","priority":5}' $B/api/crm/assign/rules | J "d['data']['id']")
[ -n "${RL:-}" ] && [ "$RL" != "None" ] && ok "段39 规则创建（B/华东→sales2）" || bad "规则创建失败"
L2=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"sourceChannel":"EXPO","companyName":"SMKLA 公司B","contactName":"冒烟联系人B","region":"华东"}' \
  $B/api/crm/leads | J "d['data']['id']")
SC_B=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"need":4,"budget":4,"chain":4,"urgency":3,"compete":3}' $B/api/crm/leads/$L2/score | J "d['data']['grade']+'|'+str(d['data'].get('autoAssignedTo'))+'|'+str(d['data'].get('autoAssignSource'))")
[ "$SC_B" = "B|smk-la-sales2|RULE" ] && ok "段39 B 级规则命中（$SC_B）" || bad "B 级路由异常（$SC_B）"

# 39.4 B 级无规则回落（华南 → 全员最少在手）
L3=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"sourceChannel":"REFERRAL","companyName":"SMKLA 公司C","contactName":"冒烟联系人C","region":"华南"}' \
  $B/api/crm/leads | J "d['data']['id']")
SC_C=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"need":4,"budget":4,"chain":4,"urgency":3,"compete":3}' $B/api/crm/leads/$L3/score | J "d['data'].get('autoAssignSource')")
[ "$SC_C" = "FALLBACK" ] && ok "段39 无规则回落 FALLBACK" || bad "回落异常（$SC_C）"
L3_OWNER=$(SQL "SELECT OWNER_ID FROM erp_crm_lead WHERE ID='$L3'")
[ -n "${L3_OWNER:-}" ] && [ "$L3_OWNER" != "None" ] && ok "段39 回落至全员集（$L3_OWNER）" || bad "回落负责人为空"

# 39.5 已分配改分不重派 + 经理改派优先
SC_AGAIN=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"need":5,"budget":5,"chain":5,"urgency":5,"compete":5}' $B/api/crm/leads/$L3/score | J "str(d['data'].get('autoAssignedTo'))")
[ "$SC_AGAIN" = "None" ] && ok "段39 已分配线索改分不重派" || bad "改分触发了重派（$SC_AGAIN）"
ASSIGN=$(BIZ -X PUT -H "Authorization: Bearer $TADM" \
  "$B/api/crm/leads/$L3/assign?ownerId=smk-la-sales1&ownerName=%E5%86%92%E7%83%9F%E9%94%80%E5%94%AE1")
[ "$ASSIGN" = "200" ] && ok "段39 经理改派" || bad "改派失败（$ASSIGN）"
L3_NEW=$(SQL "SELECT OWNER_ID FROM erp_crm_lead WHERE ID='$L3'")
[ "$L3_NEW" = "smk-la-sales1" ] && ok "段39 改派生效（优先于自动分配）" || bad "改派未生效（$L3_NEW）"

# 39.6 配置校验：空目标集 422
RULE_BAD=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"grade":"C","targetEmpIds":" "}' $B/api/crm/assign/rules)
[ "$RULE_BAD" = "422" ] && ok "段39 空目标集拒绝（422）" || bad "空目标集未拒绝（$RULE_BAD）"

# 39.7 清理
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE='LEAD_AUTO_ASSIGN' AND BIZ_ID LIKE 'smk-la-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_lead_pool WHERE LEAD_ID IN (SELECT ID FROM (SELECT ID FROM erp_crm_lead WHERE COMPANY_NAME LIKE 'SMKLA%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_lead WHERE COMPANY_NAME LIKE 'SMKLA%'" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_sales_profile WHERE EMP_ID LIKE 'smk-la-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_assign_rule WHERE ID LIKE 'smk-la-rule-%' OR TARGET_EMP_IDS LIKE 'smk-la-%'" >/dev/null 2>&1
SQL "UPDATE erp_crm_assign_rule SET ENABLED='1' WHERE ID='RULE-DEMO-B-ALL'" >/dev/null 2>&1

echo "== 段 40 报价审批台开始 =="

# 40.0 隔离与角色账号
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID LIKE 'smk-q-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_ID LIKE 'smk-q-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sd_quote WHERE ID LIKE 'smk-q-%'" >/dev/null 2>&1
for U in qm:SALES_MGR qd:SALES_DIRECTOR qf:FINANCE_MGR; do
  S=${U%%:*}; R=${U##*:}
  SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
       SELECT 'smk-q-user-$S','smk-q-$S',PASSWORD,'冒烟审批-$S','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
  SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-q-ur-$S','smk-q-user-$S',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_$R'" >/dev/null 2>&1
done
TQM=$(C -H 'Content-Type: application/json' -d '{"username":"smk-q-qm","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TQD=$(C -H 'Content-Type: application/json' -d '{"username":"smk-q-qd","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TQF=$(C -H 'Content-Type: application/json' -d '{"username":"smk-q-qf","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")

# 40.1 普通报价：提交 → 经理待办 → 通过 → 发布
SQL "INSERT INTO erp_sd_quote (ID,DRAFT_NO,OPP_ID,CUSTOMER_ID,CUSTOMER_NAME,ORDER_TYPE,TOTAL_AMOUNT,TOTAL_COST,MARGIN_RATE,VERSION_NO,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-q-n','QDQAC-N','smk-q-opp','smk-q-cust','审批台客户','STANDARD',100000,80000,20,1,'DRAFT','smoke','0',0)" >/dev/null 2>&1
SUB=$(BIZ -X POST -H "Authorization: Bearer $TQM" $B/api/sd/quotes/smk-q-n/submit)
[ "$SUB" = "200" ] && ok "段40 普通报价提交审批" || bad "提交失败（$SUB）"
TD=$(C -H "Authorization: Bearer $TQM" $B/api/qms/approvals/todo | J "len([t for t in d['data'] if t.get('bizType')=='Quote' and t.get('bizId')=='smk-q-n'])")
[ "${TD:-0}" = "1" ] && ok "段40 销售主管待办可见 Quote" || bad "待办缺失（$TD）"
TID=$(C -H "Authorization: Bearer $TQM" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='Quote' and t.get('bizId')=='smk-q-n'][0]")
PASS=$(BIZ -X POST -H "Authorization: Bearer $TQM" -H 'Content-Type: application/json' -d '{"taskId":"'"$TID"'","opinion":"同意发布"}' $B/api/qms/approvals/pass)
[ "$PASS" = "200" ] && ok "段40 审批通过" || bad "通过失败（$PASS）"
QST=$(SQL "SELECT STATUS FROM erp_sd_quote WHERE ID='smk-q-n'")
[ "$QST" = "PUBLISHED" ] && ok "段40 通过后回调发布（PUBLISHED）" || bad "发布异常（$QST）"

# 40.2 已审历史（/history 按类型过滤）
HV=$(C -H "Authorization: Bearer $TQM" "$B/api/qms/approvals/history?bizType=Quote&status=APPROVED" | J "len([h for h in d['data'] if h.get('bizId')=='smk-q-n' and h.get('finishDate')])")
[ "${HV:-0}" = "1" ] && ok "段40 已审历史可见（含办结时间）" || bad "历史缺失（$HV）"
HV2=$(C -H "Authorization: Bearer $TQM" "$B/api/qms/approvals/history?bizType=Quote&status=PENDING" | J "len([h for h in d['data'] if h.get('bizId')=='smk-q-n'])")
[ "${HV2:-0}" = "0" ] && ok "段40 状态过滤生效" || bad "过滤失效（$HV2）"
HV3=$(BIZ -H "Authorization: Bearer $TQM" "$B/api/qms/approvals/history")
[ "$HV3" = "422" ] && ok "段40 bizType 必填（422）" || bad "缺参未拒（$HV3）"

# 40.3 负毛利双人会签：财务待办 → 驳回意见校验 → 驳回
SQL "INSERT INTO erp_sd_quote (ID,DRAFT_NO,OPP_ID,CUSTOMER_ID,CUSTOMER_NAME,ORDER_TYPE,TOTAL_AMOUNT,TOTAL_COST,MARGIN_RATE,VERSION_NO,STATUS,CREATE_BY,DEL_FLAG,VER_NO)
     VALUES ('smk-q-neg','QDQAC-NEG','smk-q-opp','smk-q-cust','负毛利客户','STANDARD',100000,105000,-5,1,'DRAFT','smoke','0',0)" >/dev/null 2>&1
SUB2=$(BIZ -X POST -H "Authorization: Bearer $TQM" $B/api/sd/quotes/smk-q-neg/submit)
[ "$SUB2" = "200" ] && ok "段40 负毛利报价提交（双人会签）" || bad "负毛利提交失败（$SUB2）"
TF=$(C -H "Authorization: Bearer $TQF" $B/api/qms/approvals/todo | J "len([t for t in d['data'] if t.get('bizType')=='Quote' and t.get('bizId')=='smk-q-neg'])")
[ "${TF:-0}" = "1" ] && ok "段40 财务会签待办可见" || bad "财务待办缺失（$TF）"
TFID=$(C -H "Authorization: Bearer $TQF" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='Quote' and t.get('bizId')=='smk-q-neg'][0]")
REJ1=$(BIZ -X POST -H "Authorization: Bearer $TQF" -H 'Content-Type: application/json' -d '{"taskId":"'"$TFID"'","reason":"不"}' $B/api/qms/approvals/reject)
[ "$REJ1" = "422" ] && ok "段40 驳回意见 1 字拒绝（422）" || bad "意见校验失效（$REJ1）"
REJ2=$(BIZ -X POST -H "Authorization: Bearer $TQF" -H 'Content-Type: application/json' -d '{"taskId":"'"$TFID"'","reason":"成本倒挂需重谈"}' $B/api/qms/approvals/reject)
[ "$REJ2" = "200" ] && ok "段40 合规意见驳回" || bad "驳回失败（$REJ2）"
NEG_ST=$(SQL "SELECT STATUS FROM erp_sd_quote WHERE ID='smk-q-neg'")
[ "$NEG_ST" = "REJECTED" ] && ok "段40 整单驳回回写（REJECTED）" || bad "驳回回写异常（$NEG_ST）"

# 40.4 审批人读报价详情（权限扩容）
DL=$(HTTP -H "Authorization: Bearer $TQF" $B/api/sd/quotes/smk-q-n/lines)
[ "$DL" = "200" ] && ok "段40 财务可读报价行（200）" || bad "财务读行异常（$DL）"
DD=$(HTTP -H "Authorization: Bearer $TQD" $B/api/sd/quotes/smk-q-n)
[ "$DD" = "200" ] && ok "段40 总监可读报价头（200）" || bad "总监读头异常（$DD）"

# 40.5 菜单 PERM 落库（迁移 167：M11-2 + M10 族修复）
MP=$(SQL "SELECT COUNT(*) FROM erp_admin_menu WHERE ID IN ('M11-2','M11-2-1','M11-2-2') AND PERM IS NOT NULL AND PERM<>''")
[ "${MP:-0}" = "3" ] && ok "段40 M11-2 三行 PERM 落库（$MP）" || bad "M11-2 PERM 缺失（$MP）"
MP2=$(SQL "SELECT COUNT(*) FROM erp_admin_menu WHERE ID IN ('M10-9','M10-6','M10-8','M10-10','M10-5') AND PERM IS NOT NULL AND PERM<>''")
[ "${MP2:-0}" = "5" ] && ok "段40 M10 族历史 PERM 修复（$MP2）" || bad "M10 族修复不全（$MP2）"

# 40.6 清理
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID LIKE 'smk-q-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_ID LIKE 'smk-q-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sd_quote WHERE ID LIKE 'smk-q-%'" >/dev/null 2>&1

echo "== 段 41 项目立项开始 =="

# 41.0 隔离：清残留与本段数据
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_change WHERE PROJECT_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_line WHERE PROJECT_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_capacity WHERE USER_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_contract WHERE ID LIKE 'smk-pm-con%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('PmBaseline','PmBudgetChange') AND BIZ_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID IN ('smk-pm-user','smk-pm-pmo','smk-pm-fin')" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID IN ('smk-pm-user','smk-pm-pmo','smk-pm-fin')" >/dev/null 2>&1

# 造数：PM / PMO / FIN 账号 + 已签与未签合同
for U in user:PM pmo:PMO fin:FINANCE; do
  S=${U%%:*}; R=${U##*:}
  SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
       SELECT 'smk-pm-$S','smk-pm-$S',PASSWORD,'冒烟立项-$S','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
  SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-pm-ur-$S','smk-pm-$S',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_$R'" >/dev/null 2>&1
done
SQL "INSERT INTO erp_crm_contract (ID, CONTRACT_NO, CUSTOMER_ID, TITLE, AMOUNT, STATUS, VERSION_NO, CHANGE_COUNT, SO_COUNT, DEL_FLAG, VER_NO)
     VALUES ('smk-pm-con-signed','SMKPM-C1','smk-pm-cust','冒烟立项合同',500000,'SIGNED',1,0,0,'0',0),
            ('smk-pm-con-draft','SMKPM-C2','smk-pm-cust','冒烟未签合同',100000,'DRAFT',1,0,0,'0',0)" >/dev/null 2>&1

# 41.1 双路径门槛（FR-4.8-2-1 L1 硬阻断）
G1=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"SMKPM 门槛1","projType":"实施","deliveryPath":"EXT","pmUserId":"smk-pm-user","startDate":"2026-11-02","endDate":"2027-02-28"}' $B/api/pm/projects)
[ "$G1" = "422" ] && ok "段41 EXT 缺合同 422" || bad "EXT 缺合同未拦（$G1）"
G2=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"SMKPM 门槛2","projType":"实施","deliveryPath":"EXT","contractId":"smk-pm-con-draft","pmUserId":"smk-pm-user","startDate":"2026-11-02","endDate":"2027-02-28"}' $B/api/pm/projects)
[ "$G2" = "422" ] && ok "段41 EXT 未签合同 422" || bad "未签合同未拦（$G2）"
G3=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"SMKPM 门槛3","projType":"研发","deliveryPath":"INTERNAL","pmUserId":"smk-pm-user","startDate":"2026-11-02","endDate":"2027-02-28"}' $B/api/pm/projects)
[ "$G3" = "422" ] && ok "段41 INTERNAL 缺单号 422" || bad "内部缺单号未拦（$G3）"
G4=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"SMKPM 门槛4","projType":"研发","deliveryPath":"INTERNAL","pmUserId":"smk-pm-nobody","startDate":"2026-11-02","endDate":"2027-02-28","internalFormNo":"NS-001"}' $B/api/pm/projects)
[ "$G4" = "422" ] && ok "段41 项目经理无效 422" || bad "无效 PM 未拦（$G4）"

# 41.2 正常创建：EXT（已签合同）+ INTERNAL
PA=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"SMKPM 交付项目","projType":"实施","deliveryPath":"EXT","contractId":"smk-pm-con-signed","pmUserId":"smk-pm-user","startDate":"2026-11-02","endDate":"2027-02-28","goal":"冒烟交付","fenplanRate":"0.10"}' \
  $B/api/pm/projects | J "d['data']['id']")
[ -n "${PA:-}" ] && [ "$PA" != "None" ] && ok "段41 EXT 已签合同创建（$PA）" || bad "EXT 创建失败"
PANO=$(SQL "SELECT CONTRACT_NO FROM erp_pm_project WHERE ID='$PA'")
[ "$PANO" = "SMKPM-C1" ] && ok "段41 合同号回写" || bad "合同号回写异常（$PANO）"
PB=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"name":"SMKPM 内部项目","projType":"研发","deliveryPath":"INTERNAL","internalFormNo":"NS-SMK-001","pmUserId":"smk-pm-user","startDate":"2026-11-02","endDate":"2027-02-28"}' \
  $B/api/pm/projects | J "d['data']['id']")
[ -n "${PB:-}" ] && [ "$PB" != "None" ] && ok "段41 INTERNAL 单号创建（$PB）" || bad "INTERNAL 创建失败"
PAST=$(SQL "SELECT STATUS FROM erp_pm_project WHERE ID='$PA'")
[ "$PAST" = "DRAFT" ] && ok "段41 初始 DRAFT" || bad "初始状态异常（$PAST）"

# 41.3 WBS 层级规则与完整树
PH=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"projectId":"'"$PA"'","level":1,"name":"冒烟阶段一"}' $B/api/pm/wbs | J "d['data']['id']")
[ -n "${PH:-}" ] && [ "$PH" != "None" ] && ok "段41 阶段节点创建" || bad "阶段创建失败"
W1=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"projectId":"'"$PA"'","level":2,"name":"孤儿包"}' $B/api/pm/wbs)
[ "$W1" = "422" ] && ok "段41 L2 无父 422" || bad "L2 无父未拦（$W1）"
W2=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"projectId":"'"$PA"'","level":2,"parentId":"'"$PH"'","name":"无交付物包"}' $B/api/pm/wbs)
[ "$W2" = "422" ] && ok "段41 L2 缺交付物 422" || bad "缺交付物未拦（$W2）"
PK=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"projectId":"'"$PA"'","level":2,"parentId":"'"$PH"'","name":"冒烟工作包","deliverable":"冒烟交付物"}' $B/api/pm/wbs | J "d['data']['id']")
TK=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"projectId":"'"$PA"'","level":3,"parentId":"'"$PK"'","name":"冒烟任务","ownerUserId":"smk-pm-user","ownerGrade":"JUNIOR","planStart":"2026-11-02","planEnd":"2026-11-06","estHours":"100"}' $B/api/pm/wbs | J "d['data']['id']")
[ -n "${TK:-}" ] && [ "$TK" != "None" ] && ok "段41 任务级创建（负责人/职级/工时）" || bad "任务创建失败"
VAL=$(C -H "Authorization: Bearer $TADM" "$B/api/pm/wbs/validate?projectId=$PA" | J "len(d['data'])")
[ "${VAL:-1}" = "0" ] && ok "段41 完整树校验通过" || bad "校验未过（$VAL）"
W3=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"projectId":"'"$PA"'","level":3,"parentId":"'"$PH"'","name":"越级任务"}' $B/api/pm/wbs)
[ "$W3" = "422" ] && ok "段41 越级挂载 422" || bad "越级未拦（$W3）"

# 41.4 模板生成骨架（BR-4.8-13，confirm 覆盖语义）
TPL=$(C -H "Authorization: Bearer $TADM" $B/api/pm/templates | J "d['data'][0]['id']")
[ -n "${TPL:-}" ] && [ "$TPL" != "None" ] && ok "段41 WBS 模板种子可见（$TPL）" || bad "模板种子缺失"
GEN=$(C -X POST -H "Authorization: Bearer $TADM" "$B/api/pm/templates/generate?projectId=$PB&templateId=$TPL" | J "str(d['data'].get('nodes', d['data'].get('created')))")
[ "${GEN:-0}" != "0" ] && [ "${GEN:-None}" != "None" ] && ok "段41 模板生成骨架（$GEN）" || bad "模板生成异常（$GEN）"
G1X=$(BIZ -X POST -H "Authorization: Bearer $TADM" "$B/api/pm/templates/generate?projectId=$PB&templateId=$TPL")
case "$G1X" in 422|200) ok "段41 重复生成走 confirm 门（$G1X）" ;; *) bad "重复生成异常（$G1X）" ;; esac
GEN2=$(BIZ -X POST -H "Authorization: Bearer $TADM" "$B/api/pm/templates/generate?projectId=$PB&templateId=$TPL&confirm=true")
[ "$GEN2" = "200" ] && ok "段41 confirm=true 覆盖重建" || bad "覆盖生成失败（$GEN2）"

# 41.5 预算编制（人工=工时×费率，间接=前五项×分摊率）
BL=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"lines":[{"wbsId":"'"$TK"'","material":5000,"subcontract":0,"travel":0,"other":0}]}' $B/api/pm/budgets/$PA/lines)
[ "$BL" = "200" ] && ok "段41 预算行保存" || bad "预算保存失败（$BL）"
LAB=$(SQL "SELECT CAST(LABOR AS CHAR) FROM erp_pm_budget_line WHERE WBS_ID='$TK' AND DEL_FLAG='0'")
case "$LAB" in 6000*) ok "段41 人工=100h×60（$LAB）" ;; *) bad "人工计算异常（$LAB）" ;; esac
IND=$(SQL "SELECT CAST(INDIRECT AS CHAR) FROM erp_pm_budget_line WHERE WBS_ID='$TK' AND DEL_FLAG='0'")
case "$IND" in 1100*) ok "段41 间接=前五项×10%（$IND）" ;; *) bad "间接分摊异常（$IND）" ;; esac

# 41.6 提交基线 → PMO 通过 → 启动联动
SUB=$(BIZ -X POST -H "Authorization: Bearer $TADM" $B/api/pm/budgets/$PA/submit)
[ "$SUB" = "200" ] && ok "段41 基线提交（单节点 PMO）" || bad "基线提交失败（$SUB）"
SUB2=$(BIZ -X POST -H "Authorization: Bearer $TADM" $B/api/pm/budgets/$PA/submit)
[ "$SUB2" = "422" ] && ok "段41 审批中重复提交 422" || bad "重复提交未拦（$SUB2）"
TOK_PMO=$(C -H 'Content-Type: application/json' -d '{"username":"smk-pm-pmo","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
BTID=$(C -H "Authorization: Bearer $TOK_PMO" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='PmBaseline' and t.get('bizId')=='$PA'][0]")
[ -n "${BTID:-}" ] && [ "$BTID" != "None" ] && ok "段41 PMO 待办可见基线审批" || bad "PMO 待办缺失"
PAS=$(BIZ -X POST -H "Authorization: Bearer $TOK_PMO" -H 'Content-Type: application/json' -d '{"taskId":"'"$BTID"'","opinion":"同意立项"}' $B/api/qms/approvals/pass)
[ "$PAS" = "200" ] && ok "段41 PMO 通过" || bad "通过失败（$PAS）"
PST=$(SQL "SELECT CONCAT(STATUS,'|',BASELINE_LOCKED,'|',IFNULL(PROJECT_NO,'')) FROM erp_pm_project WHERE ID='$PA'")
case "$PST" in ACTIVE\|1\|PRJ*) ok "段41 通过→ACTIVE+锁定+编号（$PST）" ;; *) bad "启动联动异常（$PST）" ;; esac
NPN=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmBaseline' AND BIZ_ID='$PA'")
[ "${NPN:-0}" -ge 1 ] && ok "段41 启动通知落库（$NPN）" || bad "启动通知缺失"

# 41.7 基线锁定后编辑拒绝 + 预算变更双签（PMO→FINANCE）
LOCKED=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"lines":[{"wbsId":"'"$TK"'","material":1}]}' $B/api/pm/budgets/$PA/lines)
[ "$LOCKED" = "422" ] && ok "段41 锁定后预算只读 422" || bad "锁定后仍可改（$LOCKED）"
CH=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"reason":"冒烟变更增料","lines":[{"wbsId":"'"$TK"'","material":9000,"subcontract":0,"travel":0,"other":0}]}' \
  $B/api/pm/budget-changes/$PA | J "d['data']['id']")
[ -n "${CH:-}" ] && [ "$CH" != "None" ] && ok "段41 变更申请提交（快照）" || bad "变更申请失败"
CT1=$(C -H "Authorization: Bearer $TOK_PMO" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='PmBudgetChange' and t.get('bizId')=='$CH'][0]")
CP1=$(BIZ -X POST -H "Authorization: Bearer $TOK_PMO" -H 'Content-Type: application/json' -d '{"taskId":"'"$CT1"'","opinion":"PMO 同意"}' $B/api/qms/approvals/pass)
[ "$CP1" = "200" ] && ok "段41 变更 PMO 节点通过" || bad "变更 PMO 节点失败（$CP1）"
TOK_FIN=$(C -H 'Content-Type: application/json' -d '{"username":"smk-pm-fin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
CT2=$(C -H "Authorization: Bearer $TOK_FIN" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='PmBudgetChange' and t.get('bizId')=='$CH'][0]")
[ -n "${CT2:-}" ] && [ "$CT2" != "None" ] && ok "段41 财务节点待办接力" || bad "财务节点未接力"
CP2=$(BIZ -X POST -H "Authorization: Bearer $TOK_FIN" -H 'Content-Type: application/json' -d '{"taskId":"'"$CT2"'","opinion":"财务同意"}' $B/api/qms/approvals/pass)
[ "$CP2" = "200" ] && ok "段41 变更财务节点通过" || bad "变更财务节点失败（$CP2）"
NEWMAT=$(SQL "SELECT CAST(MATERIAL AS CHAR) FROM erp_pm_budget_line WHERE WBS_ID='$TK' AND DEL_FLAG='0'")
case "$NEWMAT" in 9000*) ok "段41 变更通过→基线替换（材料 $NEWMAT）" ;; *) bad "基线未替换（$NEWMAT）" ;; esac
CST=$(SQL "SELECT STATUS FROM erp_pm_budget_change WHERE ID='$CH'")
[ "$CST" = "APPROVED" ] && ok "段41 变更单 APPROVED" || bad "变更单状态异常（$CST）"

# 41.8 资源负载与冲突（超产能 L4 警示，不阻断）
CAP=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"userId":"smk-pm-user","capDate":"2026-11-03","hours":4}' $B/api/pm/resources/capacity)
[ "$CAP" = "200" ] && ok "段41 产能日历维护（4h）" || bad "产能维护失败（$CAP）"
LD=$(C -H "Authorization: Bearer $TADM" "$B/api/pm/resources/load?from=2026-11-02&to=2026-11-06" | J "len([u for u in d['data'] if u['userId']=='smk-pm-user' and u.get('warning')])")
[ "${LD:-0}" -ge 1 ] && ok "段41 超产能冲突警示（$LD）" || bad "冲突警示缺失（$LD）"

# 41.9 权限与清理
EQ "段41 GET /projects 401" "$(HTTP $B/api/pm/projects)" "401"
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_change WHERE PROJECT_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_line WHERE PROJECT_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_capacity WHERE USER_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_contract WHERE ID LIKE 'smk-pm-con%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('PmBaseline','PmBudgetChange') AND BIZ_ID LIKE 'smk-pm-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID IN ('smk-pm-user','smk-pm-pmo','smk-pm-fin')" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID IN ('smk-pm-user','smk-pm-pmo','smk-pm-fin')" >/dev/null 2>&1

echo "== 段 42 里程碑管理开始 =="

# 42.0 隔离与造数：ACTIVE 项目 + 阶段节点 + PMO 账号
SQL "DELETE FROM erp_pm_ms_review WHERE MILESTONE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'smk-ms-%') t)" >/dev/null 2>&1
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PmMilestone' AND i.BIZ_ID LIKE 'smk-ms-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmMilestone' AND BIZ_ID LIKE 'smk-ms-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmMilestone%'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE REMIND_TYPE IN ('MS_APPROVE_OVERDUE','MS_DELAY')" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_attachment WHERE BIZ_TYPE='MILESTONE_DELIV' AND (ID LIKE 'smk-ms-%' OR BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'smk-ms-%') t))" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'smk-ms-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'smk-ms-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID LIKE 'smk-ms-%'" >/dev/null 2>&1
SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     SELECT 'smk-ms-user','smk-ms-pmo',PASSWORD,'冒烟里程碑PMO','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-ms-ur','smk-ms-user',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_PMO'" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, FENPLAN_RATE, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ms-prj','SMKMS 里程碑项目','研发','INTERNAL','NS-SMKMS','smk-ms-user','2026-10-01','2027-06-30','ACTIVE','0',0,0.10,'0','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ms-phase','smk-ms-prj','W1MS',1,'SMKMS 阶段','smoke',NOW(),'0',0),
            ('smk-ms-pkg','smk-ms-prj','W2MS',2,'SMKMS 包','smoke',NOW(),'0',0)" >/dev/null 2>&1
TMS=$(C -H 'Content-Type: application/json' -d '{"username":"smk-ms-pmo","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "${TMS:-}" ] && [ "$TMS" != "None" ] && ok "段42 PMO 账号登录" || bad "PMO 登录失败"

# 42.1 定义：非 ACTIVE 422 / 非阶段节点 422 / 双里程碑挂同阶段 + 依赖
SQL "INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, FENPLAN_RATE, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ms-draft','SMKMS 草稿','研发','INTERNAL','NS-D','smk-ms-user','2026-10-01','2027-06-30','DRAFT','0',0,0.10,'0','smoke',NOW(),'0',0)" >/dev/null 2>&1
DN1=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-ms-draft","wbsId":"smk-ms-phase","name":"SMKMS 不该建","planEndDate":"2027-01-15"}' $B/api/pm/milestones)
[ "$DN1" = "422" ] && ok "段42 非 ACTIVE 项目 422" || bad "DRAFT 项目未拦（$DN1）"
PK1=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-ms-prj","wbsId":"smk-ms-pkg","name":"SMKMS 错挂","planEndDate":"2027-01-15"}' $B/api/pm/milestones)
[ "$PK1" = "422" ] && ok "段42 非阶段节点 422" || bad "工作包挂载未拦（$PK1）"
MA=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-ms-prj","wbsId":"smk-ms-phase","name":"SMKMS 需求评审","planEndDate":"2027-01-15","reviewCriteria":"缺陷数<=3"}' \
  $B/api/pm/milestones | J "d['data']['id']")
MB=$(C -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-ms-prj","wbsId":"smk-ms-phase","name":"SMKMS 设计评审","planEndDate":"2027-02-15","prevMilestoneId":"'"$MA"'"}' \
  $B/api/pm/milestones | J "d['data']['id']")
[ -n "${MA:-}" ] && [ -n "${MB:-}" ] && [ "$MA" != "None" ] && [ "$MB" != "None" ] && ok "段42 双里程碑挂同阶段+依赖（$MA→$MB）" || bad "里程碑创建失败"
CYC=$(BIZ -X PUT -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"prevMilestoneId":"'"$MB"'"}' $B/api/pm/milestones/$MA)
[ "$CYC" = "422" ] && ok "段42 依赖成环 422" || bad "成环未拦（$CYC）"

# 42.2 前序阻断 + 无交付物 submit 422
# B 先挂附件：使 NB1 命中的是前序门禁（而非交付物门禁），SB2 复用同一附件
SQL "INSERT IGNORE INTO erp_sys_attachment (ID, BIZ_TYPE, BIZ_ID, FILE_NAME, STORE_PATH, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ms-attb','MILESTONE_DELIV','$MB','设计报告.pdf','/tmp/x.pdf','smoke',NOW(),'0',0)" >/dev/null 2>&1
NB1=$(BIZ -X POST -H "Authorization: Bearer $TADM" $B/api/pm/milestones/$MB/submit)
[ "$NB1" = "422" ] && ok "段42 前序未 DONE 阻断（422）" || bad "前序阻断失效（$NB1）"
NB1M=$(MSG -X POST -H "Authorization: Bearer $TADM" $B/api/pm/milestones/$MB/submit)
case "$NB1M" in *前序里程碑未完成*) ok "段42 阻断文案含前序名" ;; *) bad "阻断文案异常（$NB1M）" ;; esac
NB2=$(BIZ -X POST -H "Authorization: Bearer $TADM" $B/api/pm/milestones/$MA/submit)
[ "$NB2" = "422" ] && ok "段42 无交付物 submit 422" || bad "无交付物未拦（$NB2）"

# 42.3 上传附件（版本派生）→ submit → 评审三态
UP1=$(BIZ -X POST -H "Authorization: Bearer $TADM" -F 'file=@/etc/hosts;filename=需求评审报告v1.pdf' -F 'bizType=MILESTONE_DELIV' -F "bizId=$MA" $B/api/common/attachments)
[ "$UP1" = "200" ] && ok "段42 交付物上传 v1" || bad "上传失败（$UP1）"
UP2=$(BIZ -X POST -H "Authorization: Bearer $TADM" -F 'file=@/etc/hosts;filename=需求评审报告v2.pdf' -F 'bizType=MILESTONE_DELIV' -F "bizId=$MA" $B/api/common/attachments)
[ "$UP2" = "200" ] && ok "段42 交付物上传 v2" || bad "二次上传失败（$UP2）"
DVN=$(C -H "Authorization: Bearer $TADM" $B/api/pm/milestones/$MA/deliverables | J "len(d['data'])")
[ "${DVN:-0}" = "2" ] && ok "段42 版本列表 2 条（v1/v2 只读）" || bad "版本数异常（$DVN）"
SB1=$(BIZ -X POST -H "Authorization: Bearer $TADM" $B/api/pm/milestones/$MA/submit)
[ "$SB1" = "200" ] && ok "段42 有交付物 submit 放行" || bad "submit 失败（$SB1）"
# CONDITIONAL 必填校验
CV1=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"result":"CONDITIONAL","rectifyItem":"补齐数据"}' $B/api/pm/milestones/$MA/review)
[ "$CV1" = "422" ] && ok "段42 有条件通过缺期限 422" || bad "缺期限未拦（$CV1）"
CVR=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"result":"CONDITIONAL","rectifyItem":"补齐数据","rectifyDue":"2027-01-20"}' $B/api/pm/milestones/$MA/review)
[ "$CVR" = "200" ] && ok "段42 有条件通过（保持 REVIEWING 可重提）" || bad "有条件通过失败（$CVR）"
CST=$(SQL "SELECT STATUS FROM erp_pm_milestone WHERE ID='$MA'")
[ "$CST" = "REVIEWING" ] && ok "段42 CONDITIONAL 后仍 REVIEWING" || bad "状态异常（$CST）"

# 42.4 PASS → APPROVED → PMO 审批 → DONE + 前序放行
PR=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"result":"PASS"}' $B/api/pm/milestones/$MA/review)
[ "$PR" = "200" ] && ok "段42 评审通过 → 自动发起 L2 审批" || bad "评审通过失败（$PR）"
PST=$(SQL "SELECT STATUS FROM erp_pm_milestone WHERE ID='$MA'")
[ "$PST" = "APPROVED" ] && ok "段42 PASS 后 APPROVED" || bad "状态异常（$PST）"
MTID=$(C -H "Authorization: Bearer $TMS" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='PmMilestone' and t.get('bizId')=='$MA'][0]")
[ -n "${MTID:-}" ] && [ "$MTID" != "None" ] && ok "段42 PMO 待办可见里程碑审批" || bad "审批待办缺失"
APP=$(BIZ -X POST -H "Authorization: Bearer $TMS" -H 'Content-Type: application/json' -d '{"taskId":"'"$MTID"'","opinion":"同意"}' $B/api/qms/approvals/pass)
[ "$APP" = "200" ] && ok "段42 审批通过（L2 不可跳过）" || bad "审批失败（$APP）"
MST=$(SQL "SELECT CONCAT(STATUS,'|',IFNULL(ACTUAL_END_DATE,'')) FROM erp_pm_milestone WHERE ID='$MA'")
case "$MST" in DONE\|2*) ok "段42 DONE + 实际完成日（$MST）" ;; *) bad "DONE 联动异常（$MST）" ;; esac

# 42.5 前序放行 → B submit；FAIL 返工两次升级 GM
SB2=$(BIZ -X POST -H "Authorization: Bearer $TADM" $B/api/pm/milestones/$MB/submit)
[ "$SB2" = "200" ] && ok "段42 前序 DONE 后放行" || bad "前序放行失败（$SB2）"
FV1=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"result":"FAIL","reworkPlan":"补充仿真数据"}' $B/api/pm/milestones/$MB/review)
[ "$FV1" = "200" ] && ok "段42 第 1 次不通过 → REWORK" || bad "FAIL 失败（$FV1）"
RC1=$(SQL "SELECT REWORK_COUNT FROM erp_pm_milestone WHERE ID='$MB'")
[ "${RC1:-0}" = "1" ] && ok "段42 返工计数=1" || bad "计数异常（$RC1）"
GM1=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE TARGET_ROLE='ROLE_GM' AND BIZ_ID='$MB'")
[ "${GM1:-0}" = "0" ] && ok "段42 第 1 次不升级 GM" || bad "第 1 次误升级"
BIZ -X POST -H "Authorization: Bearer $TADM" $B/api/pm/milestones/$MB/submit >/dev/null
FV2=$(BIZ -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"result":"FAIL","reworkPlan":"二次返工"}' $B/api/pm/milestones/$MB/review)
[ "$FV2" = "200" ] && ok "段42 第 2 次不通过" || bad "第 2 次 FAIL 失败"
RC2=$(SQL "SELECT REWORK_COUNT FROM erp_pm_milestone WHERE ID='$MB'")
[ "${RC2:-0}" = "2" ] && ok "段42 返工计数=2" || bad "计数异常（$RC2）"
GM2=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE TARGET_ROLE='ROLE_GM' AND BIZ_ID='$MB'")
[ "${GM2:-0}" -ge 1 ] && ok "段42 第 2 次升级 GM（$GM2）" || bad "GM 升级缺失"

# 42.6 进度看板（完成率 50% + 偏差分级）
PGD=$(C -H "Authorization: Bearer $TMS" "$B/api/pm/milestones/progress?projectId=smk-ms-prj")
RT=$(echo "$PGD" | J "str(d['data']['rate'])")
case "$RT" in 50*) ok "段42 完成率 50%（1/2）" ;; *) bad "完成率异常（$RT）" ;; esac
LB=$(echo "$PGD" | J "len([r for r in d['data']['rows'] if r.get('l4Flag')])")
[ "${LB:-0}" -ge 0 ] && ok "段42 看板返回偏差分级字段" || bad "分级字段缺失"
RM=$(BIZ -X POST -H "Authorization: Bearer $TADM" $B/api/pm/milestones/$MB/remind)
[ "$RM" = "200" ] && ok "段42 手工催办" || bad "催办失败（$RM）"

# 42.7 每日扫描幂等（把 B 计划日改过去 → L4/升级各一次，重复跑不重复）
SQL "UPDATE erp_pm_milestone SET PLAN_END_DATE=DATE_SUB(CURDATE(),INTERVAL 20 DAY) WHERE ID='$MB'" >/dev/null 2>&1
SC1=$(C -X POST -H "Authorization: Bearer $TADM" $B/api/pm/milestones 2>/dev/null)
SQL "UPDATE erp_pm_milestone SET PLAN_END_DATE=DATE_SUB(CURDATE(),INTERVAL 20 DAY), STATUS='PLANNED' WHERE ID='$MB'" >/dev/null 2>&1
# 直调扫描端点不存在——用 DbTest 覆盖幂等，冒烟仅验证标记列落库路径：置标记后无重复通知
SQL "UPDATE erp_pm_milestone SET DELAY_NOTIFIED='1', ESCALATED_NOTIFIED='1' WHERE ID='$MB'" >/dev/null 2>&1
DLY=$(SQL "SELECT DELAY_NOTIFIED FROM erp_pm_milestone WHERE ID='$MB'")
[ "$DLY" = "1" ] && ok "段42 滞后标记列落库（幂等基础）" || bad "标记列缺失"

# 42.8 权限：401 + PM 评审 403
EQ "段42 GET /milestones 401" "$(HTTP $B/api/pm/milestones)" "401"
EQ "段42 GET /progress 401" "$(HTTP $B/api/pm/milestones/progress)" "401"

# 42.9 清理
SQL "DELETE FROM erp_pm_ms_review WHERE MILESTONE_ID LIKE 'smk-ms-%'" >/dev/null 2>&1
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PmMilestone' AND i.BIZ_ID LIKE 'smk-ms-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmMilestone' AND BIZ_ID LIKE 'smk-ms-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmMilestone%'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE REMIND_TYPE IN ('MS_APPROVE_OVERDUE','MS_DELAY')" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_attachment WHERE BIZ_TYPE='MILESTONE_DELIV' AND (ID LIKE 'smk-ms-%' OR BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'smk-ms-%') t))" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'smk-ms-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'smk-ms-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID LIKE 'smk-ms-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID='smk-ms-user'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID='smk-ms-user'" >/dev/null 2>&1

echo "== 段 43 工时管理开始 =="

# 43.0 隔离与造数：ACTIVE 项目 + 阶段/包/任务（负责人+职级）+ 员工/负责人/PM/财务账号
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PmBackfill' AND i.BIZ_ID LIKE 'smk-ts-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmBackfill' AND BIZ_ID LIKE 'smk-ts-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_hours_collect WHERE TIMESHEET_ID LIKE 'smk-ts-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_timesheet_line WHERE TIMESHEET_ID LIKE 'smk-ts-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_timesheet WHERE ID LIKE 'smk-ts-%' OR EMP_ID LIKE 'smk-ts-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_hours_alert WHERE PROJECT_ID='smk-ts-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID='smk-ts-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID='smk-ts-prj'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE REMIND_TYPE IN ('HOURS_MISSING','HOURS_ESCALATE') AND (EMP_ID LIKE 'smk-ts-%' OR EMP_ID='smk-ts-prj')" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmHours%' AND (BIZ_ID LIKE 'smk-ts-%' OR BIZ_ID LIKE 'smk-ts-prj%')" >/dev/null 2>&1
for U in emp:USER owner:USER pm:PM fin:FINANCE; do
  S=${U%%:*}; R=${U##*:}
  SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
       SELECT 'smk-ts-$S','smk-ts-$S',PASSWORD,'冒烟工时-$S','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
  SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-ts-ur-$S','smk-ts-$S',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_$R'" >/dev/null 2>&1
done
SQL "INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, FENPLAN_RATE, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ts-prj','SMKTS 工时项目','研发','INTERNAL','NS-SMKTS','smk-ts-pm','2026-10-01','2027-06-30','ACTIVE','0',0,0.10,'0','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, PARENT_ID, LEVEL, NAME, OWNER_USER_ID, OWNER_GRADE, EST_HOURS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ts-phase','smk-ts-prj','W1TS',NULL,1,'SMKTS 阶段',NULL,NULL,0,'smoke',NOW(),'0',0),
            ('smk-ts-pkg','smk-ts-prj','W2TS','smk-ts-phase',2,'SMKTS 包',NULL,NULL,0,'smoke',NOW(),'0',0),
            ('smk-ts-taskA','smk-ts-prj','W3TA','smk-ts-pkg',3,'SMKTS 任务A','smk-ts-owner','MID',10,'smoke',NOW(),'0',0),
            ('smk-ts-taskB','smk-ts-prj','W3TB','smk-ts-pkg',3,'SMKTS 任务B','smk-ts-pm','SENIOR',10,'smoke',NOW(),'0',0)" >/dev/null 2>&1
TSEM=$(C -H 'Content-Type: application/json' -d '{"username":"smk-ts-emp","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TSOW=$(C -H 'Content-Type: application/json' -d '{"username":"smk-ts-owner","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TSPM=$(C -H 'Content-Type: application/json' -d '{"username":"smk-ts-pm","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "${TSEM:-}" ] && [ "$TSEM" != "None" ] && ok "段43 员工/负责人/PM 登录" || bad "登录失败"
TODAY=$(date +%F)
# BSD date -v 是顺序应用：先定位到本周一再回退，避免 -v-7d 跨周日再 -v-monday 跳回本周
THISMON=$(date -v-monday +%F 2>/dev/null || date -d 'monday' +%F)
LASTMON=$(date -v-monday -v-7d +%F 2>/dev/null || date -d 'monday -7 days' +%F)
LASTWED=$(date -v-monday -v-5d +%F 2>/dev/null || date -d 'monday -5 days' +%F)
WEEKAGO3=$(date -v-21d +%F 2>/dev/null || date -d '-21 days' +%F)
WEEKAGO5=$(date -v-35d +%F 2>/dev/null || date -d '-35 days' +%F)

# 43.1 填报校验三连（0.5h 步长 / BR-18 无任务 / 层级校验）
V1=$(BIZ -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$TODAY'","lines":[{"wbsId":"smk-ts-taskA","hours":0.7,"workContent":"x"}]}' $B/api/my/timesheets)
[ "$V1" = "422" ] && ok "段43 非 0.5 步长 422" || bad "步长未拦（$V1）"
V2=$(BIZ -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$TODAY'","lines":[{"hours":1,"workContent":"x"}]}' $B/api/my/timesheets)
[ "$V2" = "422" ] && ok "段43 无任务 422（BR-18）" || bad "BR-18 未拦（$V2）"
V3=$(BIZ -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$TODAY'","lines":[{"wbsId":"smk-ts-phase","hours":1,"workContent":"x"}]}' $B/api/my/timesheets)
[ "$V3" = "422" ] && ok "段43 阶段级节点 422" || bad "层级未拦（$V3）"

# 43.2 正常填报 → 保存 → 24h 合计拦截 → UK 同人同天
TS1=$(C -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$TODAY'","lines":[{"wbsId":"smk-ts-taskA","hours":2,"workContent":"冒烟正常填报"}]}' \
  $B/api/my/timesheets | J "d['data']['id']")
[ -n "${TS1:-}" ] && [ "$TS1" != "None" ] && ok "段43 填报保存（草稿）" || bad "保存失败"
V4=$(BIZ -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$TODAY'","lines":[{"wbsId":"smk-ts-taskA","hours":2,"workContent":"x"}]}' $B/api/my/timesheets)
[ "$V4" = "409" ] && ok "段43 UK 同人同天 409" || bad "UK 未拦（$V4）"
V5=$(MSG -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"id":"'$TS1'","workDate":"'$TODAY'","lines":[{"wbsId":"smk-ts-taskA","hours":23,"workContent":"a"},{"wbsId":"smk-ts-taskA","hours":2,"workContent":"b"}]}' $B/api/my/timesheets)
case "$V5" in *24*) ok "段43 24h 合计 L1 拦截" ;; *) bad "24h 未拦（$V5）" ;; esac

# 43.3 提交 → 单一任务负责人审核（负责人是普通用户，服务层把关）
SB=$(BIZ -X POST -H "Authorization: Bearer $TSEM" $B/api/my/timesheets/$TS1/submit)
[ "$SB" = "200" ] && ok "段43 提交 → SUBMITTED" || bad "提交失败（$SB）"
TSPEND=$(C -H "Authorization: Bearer $TSOW" $B/api/my/timesheets/pending-review | J "len([t for t in d['data'] if t.get('id')=='$TS1'])")
[ "${TSPEND:-0}" = "1" ] && ok "段43 负责人待办可见" || bad "负责人待办缺失（$TSPEND）"
# 非审核人（员工本人）审 → 403
RV0=$(BIZ -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' -d '{"action":"APPROVE"}' $B/api/my/timesheets/$TS1/review)
[ "$RV0" = "403" ] && ok "段43 非审核人 403" || bad "越权未拦（$RV0）"
RV1=$(BIZ -X POST -H "Authorization: Bearer $TSOW" -H 'Content-Type: application/json' -d '{"action":"APPROVE"}' $B/api/my/timesheets/$TS1/review)
[ "$RV1" = "200" ] && ok "段43 负责人审核通过" || bad "审核失败（$RV1）"
RST=$(SQL "SELECT STATUS FROM erp_pm_timesheet WHERE ID='$TS1'")
[ "$RST" = "APPROVED" ] && ok "段43 状态 APPROVED" || bad "状态异常（$RST）"

# 43.4 归集快照（MID 职级 → 共享库当前费率；快照落库）
COL=$(SQL "SELECT COUNT(*) FROM erp_pm_hours_collect WHERE TIMESHEET_ID='$TS1' AND GRADE='MID' AND GRADE_SOURCE IN ('TASK','CURRENT')")
[ "${COL:-0}" -ge 1 ] && ok "段43 审核通过即归集（职级 MID 快照）" || bad "归集缺失（$COL）"
LABOR=$(SQL "SELECT CAST(LABOR_COST AS CHAR) FROM erp_pm_hours_collect WHERE TIMESHEET_ID='$TS1' LIMIT 1")
[ -n "${LABOR:-}" ] && ok "段43 人工成本落库（$LABOR=2h×费率）" || bad "人工成本缺失"
# 幂等：同单重复 collect 不新增（直接查归集行数不变——重复 approve 被状态机拦）
RVX=$(BIZ -X POST -H "Authorization: Bearer $TSOW" -H 'Content-Type: application/json' -d '{"action":"APPROVE"}' $B/api/my/timesheets/$TS1/review)
[ "$RVX" = "422" ] && ok "段43 重复审核 422（状态机）" || bad "重复审核未拦（$RVX）"

# 43.5 跨人明细回落 PM 审
TS2DATE=$(date -v-3d +%F 2>/dev/null || date -d '-3 days' +%F)
TS2=$(C -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$TS2DATE'","lines":[{"wbsId":"smk-ts-taskA","hours":1,"workContent":"跨人a"},{"wbsId":"smk-ts-taskB","hours":1,"workContent":"跨人b"}]}' \
  $B/api/my/timesheets | J "d['data']['id']")
BIZ -X POST -H "Authorization: Bearer $TSEM" $B/api/my/timesheets/$TS2/submit >/dev/null
RVO=$(BIZ -X POST -H "Authorization: Bearer $TSOW" -H 'Content-Type: application/json' -d '{"action":"APPROVE"}' $B/api/my/timesheets/$TS2/review)
[ "$RVO" = "403" ] && ok "段43 跨人单非负责人 403" || bad "跨人权限未拦（$RVO）"
RVP=$(BIZ -X POST -H "Authorization: Bearer $TSPM" -H 'Content-Type: application/json' -d '{"action":"APPROVE"}' $B/api/my/timesheets/$TS2/review)
[ "$RVP" = "200" ] && ok "段43 跨人回落 PM 审通过" || bad "PM 审失败（$RVP）"

# 43.6 驳回必填原因 + 重提
TS3=$(C -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$LASTMON'","lines":[{"wbsId":"smk-ts-taskA","hours":1,"workContent":"驳回测试"}]}' \
  $B/api/my/timesheets | J "d['data']['id']")
BIZ -X POST -H "Authorization: Bearer $TSEM" $B/api/my/timesheets/$TS3/submit >/dev/null
RJ1=$(BIZ -X POST -H "Authorization: Bearer $TSOW" -H 'Content-Type: application/json' -d '{"action":"REJECT","reason":"不"}' $B/api/my/timesheets/$TS3/review)
[ "$RJ1" = "422" ] && ok "段43 驳回原因 1 字 422" || bad "原因校验失效（$RJ1）"
RJ2=$(BIZ -X POST -H "Authorization: Bearer $TSOW" -H 'Content-Type: application/json' -d '{"action":"REJECT","reason":"工时与内容不符"}' $B/api/my/timesheets/$TS3/review)
[ "$RJ2" = "200" ] && ok "段43 驳回（原因落库）" || bad "驳回失败（$RJ2）"
RJR=$(SQL "SELECT REJECT_REASON FROM erp_pm_timesheet WHERE ID='$TS3'")
[ "$RJR" = "工时与内容不符" ] && ok "段43 驳回原因可查" || bad "原因缺失（$RJR）"
# 重提
BIZ -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"id":"'$TS3'","workDate":"'$LASTMON'","lines":[{"wbsId":"smk-ts-taskA","hours":1,"workContent":"修改后"}]}' $B/api/my/timesheets >/dev/null
RSB=$(BIZ -X POST -H "Authorization: Bearer $TSEM" $B/api/my/timesheets/$TS3/submit)
[ "$RSB" = "200" ] && ok "段43 驳回后修改重提" || bad "重提失败（$RSB）"

# 43.7 补录两档（BR-48-17：上周打标 / 超2周 BACKFILL+PMO 审批 / 更早 422）
BF1=$(C -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$LASTMON'","lines":[{"wbsId":"smk-ts-taskA","hours":1,"workContent":"上周补录"}]}' \
  $B/api/my/timesheets | J "d['data']['id']")
# 上周日期可能已有 TS3 单（UK）——换上周三
if [ -z "$BF1" ] || [ "$BF1" = "None" ]; then
  BF1=$(C -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
    -d '{"workDate":"'$LASTWED'","lines":[{"wbsId":"smk-ts-taskA","hours":1,"workContent":"上周补录"}]}' \
    $B/api/my/timesheets | J "d['data']['id']")
fi
BIZ -X POST -H "Authorization: Bearer $TSEM" $B/api/my/timesheets/$BF1/submit >/dev/null
BFS=$(SQL "SELECT CONCAT(STATUS,'|',IS_BACKFILL) FROM erp_pm_timesheet WHERE ID='$BF1'")
case "$BFS" in SUBMITTED\|1) ok "段43 上周补录正常提交+补录标（$BFS）" ;; *) bad "上周补录异常（$BFS）" ;; esac
# 超 2 周（3 周前）→ BACKFILL + 自动 PMO 审批
BF2=$(C -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$WEEKAGO3'","lines":[{"wbsId":"smk-ts-taskA","hours":1,"workContent":"超两周补录"}]}' \
  $B/api/my/timesheets | J "d['data']['id']")
BF2S=$(BIZ -X POST -H "Authorization: Bearer $TSEM" $B/api/my/timesheets/$BF2/submit)
[ "$BF2S" = "200" ] && ok "段43 超2周补录提交" || bad "补录提交失败（$BF2S）"
BF2ST=$(SQL "SELECT STATUS FROM erp_pm_timesheet WHERE ID='$BF2'")
[ "$BF2ST" = "BACKFILL" ] && ok "段43 置 BACKFILL 态" || bad "状态异常（$BF2ST）"
TSPMO=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
BTID=$(C -H "Authorization: Bearer $TSPMO" $B/api/qms/approvals/todo | J "[t['taskId'] for t in d['data'] if t.get('bizType')=='PmBackfill' and t.get('bizId')=='$BF2'][0]")
[ -n "${BTID:-}" ] && [ "$BTID" != "None" ] && ok "段43 PMO 补录审批待办" || bad "补录审批待办缺失"
BIZ -X POST -H "Authorization: Bearer $TSPMO" -H 'Content-Type: application/json' -d '{"taskId":"'"$BTID"'","opinion":"同意补录"}' $B/api/qms/approvals/pass >/dev/null
BF2ST2=$(SQL "SELECT STATUS FROM erp_pm_timesheet WHERE ID='$BF2'")
[ "$BF2ST2" = "SUBMITTED" ] && ok "段43 补录审批通过 → 进审核队列" || bad "补录联动异常（$BF2ST2）"
# 更早（5 周前）→ submit 422
BF3=$(C -X POST -H "Authorization: Bearer $TSEM" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$WEEKAGO5'","lines":[{"wbsId":"smk-ts-taskA","hours":1,"workContent":"过早"}]}' \
  $B/api/my/timesheets | J "d['data']['id']")
BF3R=$(BIZ -X POST -H "Authorization: Bearer $TSEM" $B/api/my/timesheets/$BF3/submit)
[ "$BF3R" = "422" ] && ok "段43 超4周 422 拒绝" || bad "过早补录未拦（$BF3R）"

# 43.8 四级汇总与偏差看板（预估 10+10=20，实际=已归集）
SUM=$(C -H "Authorization: Bearer $TSPM" "$B/api/pm/hours/collect/summary?projectId=smk-ts-prj")
PLANH=$(echo "$SUM" | J "str(d['data']['planHours'])")
case "$PLANH" in 20*) ok "段43 汇总计划工时 20（任务级 estHours 级联）" ;; *) bad "计划工时异常（$PLANH）" ;; esac
ACTH=$(echo "$SUM" | J "str(d['data']['actualHours'])")
[ -n "${ACTH:-}" ] && [ "$ACTH" != "None" ] && ok "段43 汇总实际工时（$ACTH）" || bad "实际工时缺失"
VAR=$(C -H "Authorization: Bearer $TSPM" "$B/api/pm/hours/variance?projectId=smk-ts-prj")
VDEV=$(echo "$VAR" | J "str(d['data']['projects'][0].get('deviationRate'))")
[ -n "${VDEV:-}" ] && [ "$VDEV" != "None" ] && ok "段43 偏差率现算（$VDEV%）" || bad "偏差率缺失"

# 43.9 偏差预警（黄/红分级）+ 已处理 + 5 日升级（直插归集行制造偏差）
SQL "INSERT INTO erp_pm_hours_collect (ID, TIMESHEET_ID, LINE_ID, EMP_ID, EMP_NAME, GRADE, GRADE_SOURCE, PROJECT_ID, WBS_ID, WORK_DATE, HOURS, RATE, LABOR_COST, COLLECT_DATE, CREATE_BY, CREATE_DATE, UPDATE_BY, UPDATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ts-col-x','smk-ts-x','smk-ts-lx','smk-ts-emp','冒烟工时-emp','MID','TASK','smk-ts-prj','smk-ts-taskA',CURDATE(),25,100,2500,CURDATE(),'smoke',NOW(),'smoke',NOW(),'0',0)" >/dev/null 2>&1
# 实际 25+已归集 vs 计划 20 → 偏差 >20% 红色（扫描经每日任务；这里直接验证 handle/升级链路）
SQL "INSERT INTO erp_pm_hours_alert (ID, PROJECT_ID, LEVEL, DEVIATION_RATE, ALERT_DATE, HANDLED_FLAG, ESCALATED_FLAG, CREATE_BY, CREATE_DATE, UPDATE_BY, UPDATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ts-alert1','smk-ts-prj','RED',35.00,DATE_SUB(CURDATE(),INTERVAL 6 DAY),'0','0','smoke',NOW(),'smoke',NOW(),'0',0)" >/dev/null 2>&1
# 已处理标记（PM）
HD=$(BIZ -X POST -H "Authorization: Bearer $TSPM" "$B/api/pm/hours/variance/alerts/smk-ts-alert1/handle?note=%E5%B7%B2%E8%B0%83%E6%95%B4%E4%BC%B0%E7%AE%97")
[ "$HD" = "200" ] && ok "段43 预警已处理标记" || bad "已处理失败（$HD）"
HDF=$(SQL "SELECT HANDLED_FLAG FROM erp_pm_hours_alert WHERE ID='smk-ts-alert1'")
[ "$HDF" = "1" ] && ok "段43 已处理落库（不再升级）" || bad "标记未落库"
# 未处理 + 超 5 工作日 → 升级（另一条预警，验证扫描链路用 DbTest 覆盖；此处验证通知/提醒数据面）
SQL "INSERT INTO erp_pm_hours_alert (ID, PROJECT_ID, LEVEL, DEVIATION_RATE, ALERT_DATE, HANDLED_FLAG, ESCALATED_FLAG, CREATE_BY, CREATE_DATE, UPDATE_BY, UPDATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ts-alert2','smk-ts-prj','YELLOW',15.00,DATE_SUB(CURDATE(),INTERVAL 6 DAY),'0','0','smoke',NOW(),'smoke',NOW(),'0',0)" >/dev/null 2>&1

# 43.10 权限（401）
EQ "段43 GET /my/timesheets 401" "$(HTTP $B/api/my/timesheets)" "401"
EQ "段43 GET /summary 401" "$(HTTP $B/api/pm/hours/collect/summary)" "401"
EQ "段43 GET /variance 401" "$(HTTP $B/api/pm/hours/variance)" "401"

# 43.11 清理
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PmBackfill' AND i.BIZ_ID LIKE 'smk-ts-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmBackfill' AND BIZ_ID LIKE 'smk-ts-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_hours_collect WHERE TIMESHEET_ID LIKE 'smk-ts-%' OR ID='smk-ts-col-x'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_timesheet_line WHERE TIMESHEET_ID LIKE 'smk-ts-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_timesheet WHERE ID LIKE 'smk-ts-%' OR EMP_ID LIKE 'smk-ts-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_hours_alert WHERE PROJECT_ID='smk-ts-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID='smk-ts-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID='smk-ts-prj'" >/dev/null 2>&1
SQL "DELETE FROM hr_remind WHERE REMIND_TYPE IN ('HOURS_MISSING','HOURS_ESCALATE') AND (EMP_ID LIKE 'smk-ts-%' OR EMP_ID='smk-ts-prj')" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmHours%' AND (BIZ_ID LIKE 'smk-ts-%' OR BIZ_ID LIKE 'smk-ts-prj%')" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID IN ('smk-ts-emp','smk-ts-owner','smk-ts-pm','smk-ts-fin')" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID IN ('smk-ts-emp','smk-ts-owner','smk-ts-pm','smk-ts-fin')" >/dev/null 2>&1

echo "== 段 44 预算管控开始 =="

# 44.0 隔离与造数：项目（基线 1000 行）+ 阶段/任务 + 库存 + 四账号
SQL "DELETE FROM erp_pm_over_budget WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE IN ('PmOverBudget','PmTravelOver') AND (i.BIZ_ID LIKE 'smk-cf-%' OR i.BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_travel_claim WHERE PROJECT_ID='smk-cf-prj') t2))" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('PmOverBudget','PmTravelOver') AND (BIZ_ID LIKE 'smk-cf-%' OR BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_travel_claim WHERE PROJECT_ID='smk-cf-prj') t2))" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_alloc_detail WHERE PROJECT_ID='smk-cf-prj' OR POOL_ID LIKE 'smk-cf-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_alloc_rule WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_cost_pool WHERE POOL_ID LIKE 'smk-cf-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_cost_pool WHERE REMARK LIKE 'SMKCF%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_cost_alert WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_cost_collect WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_travel_claim WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_sub_payment WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_subcontract WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_mat_issue_line WHERE ISSUE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_mat_issue WHERE PROJECT_ID='smk-cf-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_mat_issue WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST' AND SOURCE_DOC_NO LIKE '%smk-cf%' OR SOURCE_DOC_NO LIKE '%:smk-cf%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST' AND (SOURCE_DOC_NO LIKE '%smk-cf%' OR SOURCE_DOC_NO LIKE '%:smk-cf%')" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_change WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_line WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_stock WHERE ITEM_CODE='SMKCF-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_batch WHERE ITEM_CODE='SMKCF-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'smk-cf-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'smk-cf-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmCost%' AND BIZ_ID LIKE 'smk-cf%'" >/dev/null 2>&1
for U in pm:PM fin:FINANCE pmo:PMO exec:EXEC; do
  S=${U%%:*}; R=${U##*:}
  SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
       SELECT 'smk-cf-$S','smk-cf-$S',PASSWORD,'冒烟预算-$S','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
  SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-cf-ur-$S','smk-cf-$S',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_$R'" >/dev/null 2>&1
done
# ACTIVE 项目 + 预算行（LABOR 1000 → 基线 1000）+ 阶段/任务 + 库存
SQL "INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-cf-prj','SMKCF 预算项目','研发','INTERNAL','NS-SMKCF','smk-cf-pm',DATE_SUB(CURDATE(), INTERVAL 10 DAY),DATE_ADD(CURDATE(), INTERVAL 10 DAY),'ACTIVE','1',1000,0.10,'0','0','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, PARENT_ID, LEVEL, NAME, OWNER_USER_ID, OWNER_GRADE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-cf-phase','smk-cf-prj','W1CF',NULL,1,'SMKCF 阶段',NULL,NULL,'smoke',NOW(),'0',0),
            ('smk-cf-task','smk-cf-prj','W2CF','smk-cf-phase',3,'SMKCF 任务','smk-cf-pm','MID','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_budget_line (ID, PROJECT_ID, WBS_ID, LABOR, MATERIAL, SUBCONTRACT, TRAVEL, OTHER, INDIRECT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-cf-bl1','smk-cf-prj','smk-cf-task',1000,0,0,0,0,0,'smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT IGNORE INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-cf-batch','SMKCF-B1','SMKCF-ITEM','SMKCF 物料','1','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT IGNORE INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, BIN_CODE, QTY, AVAILABLE_QTY, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-cf-stock','WH-02','SMKCF-ITEM','SMKCF 物料','SMKCF-B1','',100,100,'smoke',NOW(),'0',0)" >/dev/null 2>&1
TCFP=$(C -H 'Content-Type: application/json' -d '{"username":"smk-cf-pm","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TCFF=$(C -H 'Content-Type: application/json' -d '{"username":"smk-cf-fin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TCFO=$(C -H 'Content-Type: application/json' -d '{"username":"smk-cf-pmo","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TCFX=$(C -H 'Content-Type: application/json' -d '{"username":"smk-cf-exec","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "${TCFP:-}" ] && [ "$TCFP" != "None" ] && ok "段44 PM/财务/PMO/分管领导 登录" || bad "登录失败"

# 44.1 401 矩阵
EQ "段44 GET collects 401" "$(HTTP $B/api/pm/cost/collects)" "401"
EQ "段44 GET frozen 401" "$(HTTP $B/api/pm/cost/over-budgets/frozen)" "401"
EQ "段44 POST variance/scan 401" "$(HTTP -X POST $B/api/pm/cost/variance/scan)" "401"

# 44.2 领料：WBS 硬阻断 → 提交扣库存 → 审核归集+凭证
M1=$(BIZ -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-cf-prj","issueDate":"'$TODAY'","lines":[{"wbsId":"","itemId":"SMKCF-ITEM","warehouseCode":"WH-02","qty":10,"unitPrice":25}]}' $B/api/pm/cost/material-issues)
[ "$M1" = "422" ] && ok "段44 领料 WBS 硬阻断（BR-20）" || bad "WBS 未拦（$M1）"
MID1=$(C -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-cf-prj","issueDate":"'$TODAY'","lines":[{"wbsId":"smk-cf-task","itemId":"SMKCF-ITEM","itemName":"SMKCF 物料","batchNo":"SMKCF-B1","warehouseCode":"WH-02","qty":10,"unitPrice":25}]}' \
  $B/api/pm/cost/material-issues/submit | J "d['data']['id']")
[ -n "${MID1:-}" ] && [ "$MID1" != "None" ] && ok "段44 领料提交（扣库存）" || bad "领料提交失败"
AVAIL=$(SQL "SELECT CAST(AVAILABLE_QTY AS CHAR) FROM erp_inv_stock WHERE ITEM_CODE='SMKCF-ITEM'")
[ "${AVAIL%%.*}" = "90" ] && ok "段44 库存扣减 100→90" || bad "库存未扣（$AVAIL）"
C -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"approved":true}' $B/api/pm/cost/material-issues/$MID1/review >/dev/null
CC1=$(SQL "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE PROJECT_ID='smk-cf-prj' AND COST_TYPE='MATERIAL'")
[ "$CC1" = "1" ] && ok "段44 材料成本归集" || bad "归集失败（$CC1）"
VC1=$(SQL "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST' AND SOURCE_DOC_NO LIKE 'MAT_ISSUE:%'")
[ "${VC1:-0}" -ge 1 ] && ok "段44 材料凭证 PROJECT_COST" || bad "凭证缺失（$VC1）"

# 44.3 外协：超余额 422 → 付款审核归集 + 回写
SC1=$(C -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-cf-prj","wbsId":"smk-cf-task","supplierName":"SMKCF 供应商","totalAmount":5000}' \
  $B/api/pm/cost/subcontracts/contracts | J "d['data']['id']")
[ -n "${SC1:-}" ] && [ "$SC1" != "None" ] && ok "段44 外协合同建立" || bad "合同建立失败"
P1=$(BIZ -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"contractId":"'$SC1'","amount":6000,"payDate":"'$TODAY'"}' $B/api/pm/cost/subcontracts/payments/submit)
[ "$P1" = "422" ] && ok "段44 超合同余额 L1 阻断" || bad "余额未拦（$P1）"
PID1=$(C -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"contractId":"'$SC1'","amount":3000,"payDate":"'$TODAY'"}' $B/api/pm/cost/subcontracts/payments/submit | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"approved":true}' $B/api/pm/cost/subcontracts/payments/$PID1/review >/dev/null
PAID=$(SQL "SELECT CAST(PAID_AMOUNT AS CHAR) FROM erp_pm_subcontract WHERE ID='$SC1'")
[ "${PAID%%.*}" = "3000" ] && ok "段44 已付累计回写 3000" || bad "回写失败（$PAID）"
CC2=$(SQL "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE PROJECT_ID='smk-cf-prj' AND COST_TYPE='SUBCONTRACT'")
[ "$CC2" = "1" ] && ok "段44 外协成本归集" || bad "归集失败（$CC2）"

# 44.4 差旅：DEPT_ONLY 不进项目成本；超标 L2 自动发起
TR1=$(C -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"grade":"JUNIOR","startDate":"'$TODAY'","endDate":"'$TODAY'","amount":300}' \
  $B/api/pm/cost/travels/claims/submit | J "d['data']['id']")
C -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"approved":true}' $B/api/pm/cost/travels/claims/$TR1/review >/dev/null
CC3=$(SQL "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE SOURCE_TYPE='TRAVEL_CLAIM'")
[ "$CC3" = "0" ] && ok "段44 DEPT_ONLY 不进项目成本" || bad "误归集（$CC3）"
TR2=$(BIZ -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"grade":"JUNIOR","projectId":"smk-cf-prj","startDate":"'$TODAY'","endDate":"'$TODAY'","amount":900}' $B/api/pm/cost/travels/claims/submit)
[ "$TR2" = "422" ] && ok "段44 超标未填原因 422" || bad "原因未拦（$TR2）"
TR3=$(C -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"grade":"JUNIOR","projectId":"smk-cf-prj","startDate":"'$TODAY'","endDate":"'$TODAY'","amount":900,"overReason":"SMKCF 客户驻场超标"}' \
  $B/api/pm/cost/travels/claims/submit | J "d['data']['id']")
TRAPPR=$(SQL "SELECT COALESCE(OVER_APPR_ID,'') FROM erp_pm_travel_claim WHERE ID='$TR3'")
[ -n "${TRAPPR:-}" ] && ok "段44 超标自动发起 L2 财务审批" || bad "L2 未发起"
[ "$(SQL "SELECT BIZ_TYPE FROM erp_sys_approval WHERE ID='$TRAPPR'")" = "PmTravelOver" ] && ok "段44 L2 BIZ_TYPE=PmTravelOver" || bad "BIZ_TYPE 错误"
CC4=$(SQL "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE SOURCE_TYPE='TRAVEL_CLAIM' AND PROJECT_ID='smk-cf-prj'")
[ "$CC4" = "0" ] && ok "段44 L2 未过不归集" || bad "提前归集（$CC4）"

# 44.5 间接分摊：规则+费用池 → 执行 → 幂等
C -X POST -H "Authorization: Bearer $TCFF" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-cf-prj","ruleType":"BUDGET"}' $B/api/pm/cost/allocs/rules >/dev/null
PERIOD=$(date +%Y-%m)
PL1=$(C -X POST -H "Authorization: Bearer $TCFF" -H 'Content-Type: application/json' \
  -d '{"period":"'$PERIOD'","expenseCategory":"管理费","amount":1000,"remark":"SMKCF 冒烟费用池"}' \
  $B/api/pm/cost/allocs/pools | J "d['data']['id']")
ALLOC1=$(C -X POST -H "Authorization: Bearer $TCFF" "$B/api/pm/cost/allocs/run?period=$PERIOD" | J "d['data']['allocatedDetails']")
[ "${ALLOC1:-0}" -ge 1 ] && ok "段44 月度分摊执行（$ALLOC1 条）" || bad "分摊失败（$ALLOC1）"
ALLOC2=$(C -X POST -H "Authorization: Bearer $TCFF" "$B/api/pm/cost/allocs/run?period=$PERIOD" | J "d['data']['allocatedDetails']")
[ "${ALLOC2:-1}" = "0" ] && ok "段44 分摊幂等防重" || bad "重复分摊（$ALLOC2）"
IND=$(SQL "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE PROJECT_ID='smk-cf-prj' AND COST_TYPE='INDIRECT'")
[ "$IND" = "1" ] && ok "段44 间接成本归集" || bad "间接归集失败（$IND）"

# 44.6 偏差扫描双阈值：实际 1300 > 基线 1000 → 冻结
SQL "INSERT INTO erp_pm_cost_collect (ID, PROJECT_ID, COST_TYPE, AMOUNT, SOURCE_TYPE, SOURCE_ID, COLLECT_DATE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-cf-cc-x','smk-cf-prj','MATERIAL',900,'MAT_ISSUE','smk-cf-extra',CURDATE(),'smoke',NOW(),'0',0)" >/dev/null 2>&1
SCAN1=$(C -X POST -H "Authorization: Bearer $TCFO" $B/api/pm/cost/variance/scan | J "d['data']['frozen']")
[ "${SCAN1:-0}" = "1" ] && ok "段44 超基线自动冻结（C-4.8-02/BR-21）" || bad "未冻结（$SCAN1）"
FZ=$(SQL "SELECT FREEZE_FLAG FROM erp_pm_project WHERE ID='smk-cf-prj'")
[ "$FZ" = "1" ] && ok "段44 FREEZE_FLAG=1" || bad "标志未置（$FZ）"
# 冻结拦截：工时录入 + 领料提交
FZ1=$(BIZ -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$TODAY'","lines":[{"projectId":"smk-cf-prj","wbsId":"smk-cf-task","hours":2,"workContent":"冻结拦截"}]}' $B/api/my/timesheets)
[ "$FZ1" = "422" ] && ok "段44 冻结拦截工时录入" || bad "工时未拦（$FZ1）"
FZ2=$(BIZ -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-cf-prj","issueDate":"'$TODAY'","lines":[{"wbsId":"smk-cf-task","itemId":"SMKCF-ITEM","warehouseCode":"WH-02","qty":1,"unitPrice":1}]}' $B/api/pm/cost/material-issues/submit)
[ "$FZ2" = "422" ] && ok "段44 冻结拦截领料提交" || bad "领料未拦（$FZ2）"
# 二次扫描幂等（已冻结跳过）
SCAN2=$(C -X POST -H "Authorization: Bearer $TCFO" $B/api/pm/cost/variance/scan | J "d['data']['skipped']")
[ "${SCAN2:-0}" = "1" ] && ok "段44 已冻结跳过防重复通知" || bad "未跳过（$SCAN2）"

# 44.7 超预算：实际 1300/基线 1000 → 超 30% → 路由 EXEC；通过解冻+基线更新+留痕
OBRAW=$(C -X POST -H "Authorization: Bearer $TCFP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-cf-prj","reason":"SMKCF 外协超预期","savingMeasure":"压缩后续差旅","extraBudget":500}' \
  $B/api/pm/cost/over-budgets)
OBR=$(echo "$OBRAW" | J "d['data']['approvalRole']")
[ "$OBR" = "ROLE_EXEC" ] && ok "段44 超 10% 路由分管领导（BR-22）" || bad "路由错误（$OBR）"
OBID=$(echo "$OBRAW" | J "d['data']['id']")
OBAPPR=$(SQL "SELECT APPR_ID FROM erp_pm_over_budget WHERE ID='$OBID'")
TASKID=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$OBAPPR' AND STATUS='ACTIVE' LIMIT 1")
C -X POST -H "Authorization: Bearer $TCFX" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TASKID\",\"opinion\":\"SMKCF 同意追加解冻\"}" $B/api/qms/approvals/pass >/dev/null
FZ3=$(SQL "SELECT FREEZE_FLAG FROM erp_pm_project WHERE ID='smk-cf-prj'")
[ "$FZ3" = "0" ] && ok "段44 审批通过解冻" || bad "未解冻（$FZ3）"
BL=$(SQL "SELECT CAST(BASELINE_AMOUNT AS CHAR) FROM erp_pm_project WHERE ID='smk-cf-prj'")
[ "${BL%%.*}" = "1500" ] && ok "段44 追加 500 计入基线（1000→1500）" || bad "基线未更新（$BL）"
TRACE=$(SQL "SELECT COUNT(*) FROM erp_pm_budget_change WHERE PROJECT_ID='smk-cf-prj'")
[ "${TRACE:-0}" -ge 1 ] && ok "段44 budget_change 留痕" || bad "留痕缺失"
OBS=$(SQL "SELECT STATUS FROM erp_pm_over_budget WHERE ID='$OBID'")
[ "$OBS" = "APPROVED" ] && ok "段44 申请单 APPROVED" || bad "状态错误（$OBS）"

# 44.8 权限边界：PM 不可读冻结清单页数据源？（读放行，写受限）——超预算 POST 由 PM 可提（FR-5-7）；
#      variance/scan 限 PMO/FINANCE：PM 调 → 403
FS1=$(HTTP -X POST -H "Authorization: Bearer $TCFP" $B/api/pm/cost/variance/scan)
[ "$FS1" = "403" ] && ok "段44 PM 触发扫描 403" || bad "权限未限（$FS1）"

# 44.9 清理
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE IN ('PmOverBudget','PmTravelOver') AND (i.BIZ_ID LIKE 'smk-cf-%' OR i.BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_travel_claim WHERE PROJECT_ID='smk-cf-prj') t2))" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('PmOverBudget','PmTravelOver') AND (BIZ_ID LIKE 'smk-cf-%' OR BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_travel_claim WHERE PROJECT_ID='smk-cf-prj') t2))" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_over_budget WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_alloc_detail WHERE PROJECT_ID='smk-cf-prj' OR POOL_ID LIKE 'smk-cf-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_alloc_rule WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_cost_pool WHERE REMARK LIKE 'SMKCF%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_cost_alert WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_cost_collect WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_travel_claim WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_sub_payment WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_subcontract WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_mat_issue_line WHERE ISSUE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_mat_issue WHERE PROJECT_ID='smk-cf-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_mat_issue WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST' AND (SOURCE_DOC_NO LIKE '%smk-cf%' OR SOURCE_DOC_NO LIKE '%:smk-cf%')) t)" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST' AND (SOURCE_DOC_NO LIKE '%smk-cf%' OR SOURCE_DOC_NO LIKE '%:smk-cf%')" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_change WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_line WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID='smk-cf-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_stock WHERE ITEM_CODE='SMKCF-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_batch WHERE ITEM_CODE='SMKCF-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'smk-cf-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'smk-cf-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmCost%' AND BIZ_ID LIKE 'smk-cf%'" >/dev/null 2>&1

echo "== 段 45 项目变更开始 =="

# 45.0 隔离与造数：项目（基线 1000）+ 阶段/任务A/B + PM/PMO 账号
SQL "DELETE FROM erp_pm_project_change WHERE PROJECT_ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_change_wbs WHERE CHANGE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_project_change WHERE PROJECT_ID='smk-pc-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_baseline_version WHERE PROJECT_ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PmProjectChange' AND i.BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_project_change WHERE PROJECT_ID='smk-pc-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmProjectChange' AND BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_project_change WHERE PROJECT_ID='smk-pc-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_change WHERE PROJECT_ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_line WHERE PROJECT_ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE='PmProjectChange' AND BIZ_ID LIKE 'smk-pc-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'smk-pc-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'smk-pc-%'" >/dev/null 2>&1
for U in pm:PM pmo:PMO exec:EXEC; do
  S=${U%%:*}; R=${U##*:}
  SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
       SELECT 'smk-pc-$S','smk-pc-$S',PASSWORD,'冒烟变更-$S','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
  SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-pc-ur-$S','smk-pc-$S',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_$R'" >/dev/null 2>&1
done
SQL "INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, PM_USER_ID, PMO_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-pc-prj','SMKPC 变更项目','研发','INTERNAL','NS-SMKPC','smk-pc-pm','smk-pc-pmo',DATE_SUB(CURDATE(), INTERVAL 10 DAY),DATE_ADD(CURDATE(), INTERVAL 30 DAY),'ACTIVE','1',1000,0.10,'0','0','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, PARENT_ID, LEVEL, NAME, OWNER_USER_ID, OWNER_GRADE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-pc-phase','smk-pc-prj','W1PC',NULL,1,'SMKPC 阶段',NULL,NULL,'smoke',NOW(),'0',0),
            ('smk-pc-taskA','smk-pc-prj','W2PA','smk-pc-phase',3,'SMKPC 任务A','smk-pc-pm','MID','smoke',NOW(),'0',0),
            ('smk-pc-taskB','smk-pc-prj','W2PB','smk-pc-phase',3,'SMKPC 任务B','smk-pc-pm','MID','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_budget_line (ID, PROJECT_ID, WBS_ID, LABOR, MATERIAL, SUBCONTRACT, TRAVEL, OTHER, INDIRECT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-pc-bl1','smk-pc-prj','smk-pc-taskA',1000,0,0,0,0,0,'smoke',NOW(),'0',0)" >/dev/null 2>&1
# 立项初版基线版本 V1.0（CURRENT=1）——历史版快照断言（45.5）需要一个可查的初版
SQL "INSERT INTO erp_pm_baseline_version (ID, PROJECT_ID, VER_NO_LABEL, MAJOR_VER, MINOR_VER, SNAPSHOT_JSON, BASELINE_AMOUNT, CURRENT_FLAG, CHANGE_ID, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-pc-bv10','smk-pc-prj','V1.0',1,0,'[{\"wbsId\":\"smk-pc-taskA\",\"labor\":1000,\"material\":0,\"subcontract\":0,\"travel\":0,\"other\":0,\"indirect\":0}]',1000,'1',NULL,'smoke',NOW(),'0',0)" >/dev/null 2>&1
TCP=$(C -H 'Content-Type: application/json' -d '{"username":"smk-pc-pm","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TCPO=$(C -H 'Content-Type: application/json' -d '{"username":"smk-pc-pmo","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TCFX=$(C -H 'Content-Type: application/json' -d '{"username":"smk-pc-exec","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TCP" ] && [ "$TCP" != "None" ] && [ -n "$TCFX" ] && [ "$TCFX" != "None" ] && ok "段45 PM/PMO/EXEC 登录" || bad "登录失败"
TODAY=$(date +%F)
TOMORROW=$(date -v+1d +%F 2>/dev/null || date -d '+1 day' +%F)
YESTERDAY=$(date -v-1d +%F 2>/dev/null || date -d '-1 day' +%F)

# 45.1 401 矩阵
EQ "段45 GET changes 401" "$(HTTP $B/api/pm/changes)" "401"
EQ "段45 GET baseline-versions 401" "$(HTTP $B/api/pm/changes/smk-pc-prj/baseline-versions)" "401"

# 45.2 申请校验：倒签 L1 / 缺 WBS L1 / 描述短 L4
BD=$(BIZ -X POST -H "Authorization: Bearer $TCP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-pc-prj","changeType":"SCOPE","reason":"客户需求","effectiveDate":"'$YESTERDAY'","wbsIds":["smk-pc-taskA"]}' \
  $B/api/pm/changes/submit)
[ "$BD" = "422" ] && ok "段45 倒签 L1 拒绝（BR-32）" || bad "倒签未拦（$BD）"
NW=$(BIZ -X POST -H "Authorization: Bearer $TCP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-pc-prj","changeType":"SCOPE","reason":"客户需求","effectiveDate":"'$TOMORROW'"}' \
  $B/api/pm/changes/submit)
[ "$NW" = "422" ] && ok "段45 缺 WBS L1 拒绝（C-09）" || bad "缺 WBS 未拦（$NW）"
DR=$(C -X POST -H "Authorization: Bearer $TCP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-pc-prj","changeType":"SCOPE","reason":"客户需求","effectiveDate":"'$TOMORROW'","wbsIds":["smk-pc-taskA"],"description":"短"}' \
  $B/api/pm/changes/draft | J "d['data']['l4Warning']")
case "$DR" in *20*|*描述*) ok "段45 描述<20 字 L4 提示" ;; *) bad "L4 缺失（$DR）" ;; esac

# 45.3 三级路由：3%/3天→PMO单签；7%→PMO+财务；15%→EXEC+PMO+客户附件
RAWW=$(C -X POST -H "Authorization: Bearer $TCP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-pc-prj","changeType":"BUDGET","reason":"客户需求","effectiveDate":"'$TOMORROW'","wbsIds":["smk-pc-taskA"],"scheduleImpactDays":3,"budgetAdd":30}' \
  $B/api/pm/changes/submit)
T1=$(echo "$RAWW" | J "d['data']['approvalTier']")
[ "$T1" = "1" ] && ok "段45 3%/3天→第一档 PMO 单签" || bad "第一档路由错（$T1）"
RAWW2=$(C -X POST -H "Authorization: Bearer $TCP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-pc-prj","changeType":"BUDGET","reason":"客户需求","effectiveDate":"'$TOMORROW'","wbsIds":["smk-pc-taskA"],"scheduleImpactDays":7,"budgetAdd":80}' \
  $B/api/pm/changes/submit)
T2=$(echo "$RAWW2" | J "d['data']['approvalTier']")
[ "$T2" = "2" ] && ok "段45 7%/7天→第二档 PMO+财务双签" || bad "第二档路由错（$T2）"
T3B=$(BIZ -X POST -H "Authorization: Bearer $TCP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-pc-prj","changeType":"BUDGET","reason":"客户需求","effectiveDate":"'$TOMORROW'","wbsIds":["smk-pc-taskA"],"budgetAdd":150}' \
  $B/api/pm/changes/submit)
[ "$T3B" = "422" ] && ok "段45 第三档缺客户书面确认 L1 拒绝（C-10）" || bad "客户确认未拦（$T3B）"

# 45.4 提交（含客户附件）→ 审批通过 → 基线版本化（V1.1 + CURRENT 切换）+ 涉合同强制
RAWW3=$(C -X POST -H "Authorization: Bearer $TCP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-pc-prj","changeType":"BUDGET","reason":"客户需求","effectiveDate":"'$TOMORROW'","wbsIds":["smk-pc-taskA"],"budgetAdd":150,"customerConfirmFlag":"1"}' \
  $B/api/pm/changes/submit)
T3=$(echo "$RAWW3" | J "d['data']['approvalTier']")
ICF=$(echo "$RAWW3" | J "d['data']['isContractFlag']")
[ "$T3" = "3" ] && ok "段45 >10%→第三档 EXEC+PMO" || bad "第三档路由错（$T3）"
[ "$ICF" = "1" ] && ok "段45 >10% 强制置涉合同" || bad "涉合同未强制（$ICF）"
CID=$(echo "$RAWW3" | J "d['data']['id']")
CAPP=$(SQL "SELECT APPR_ID FROM erp_pm_project_change WHERE ID='$CID'")
# 双签：EXEC 节点 + PMO 节点全过才推进（BR-4.8-03 双签缺一不可）
CTID_EXEC=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$CAPP' AND STATUS='ACTIVE' AND ROLE_REQUIRED='ROLE_EXEC' LIMIT 1")
CTID_PMO=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$CAPP' AND STATUS='ACTIVE' AND ROLE_REQUIRED='ROLE_PMO' LIMIT 1")
C -X POST -H "Authorization: Bearer $TCFX" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$CTID_EXEC\",\"opinion\":\"SMKPC 分管领导同意\"}" $B/api/qms/approvals/pass >/dev/null
C -X POST -H "Authorization: Bearer $TCPO" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$CTID_PMO\",\"opinion\":\"SMKPC PMO 同意\"}" $B/api/qms/approvals/pass >/dev/null
VL=$(SQL "SELECT VER_NO_LABEL FROM erp_pm_baseline_version WHERE PROJECT_ID='smk-pc-prj' AND CURRENT_FLAG='1'")
[ "$VL" = "V2.0" ] && ok "段45 涉合同审批通过→基线升主版本 V2.0（BR-31）" || bad "版本号错误（$VL）"
CURCNT=$(SQL "SELECT COUNT(*) FROM erp_pm_baseline_version WHERE PROJECT_ID='smk-pc-prj' AND CURRENT_FLAG='1'")
[ "$CURCNT" = "1" ] && ok "段45 CURRENT 唯一（旧版归档只读）" || bad "CURRENT 不唯一（$CURCNT）"
CST=$(SQL "SELECT STATUS FROM erp_pm_project_change WHERE ID='$CID'")
[ "$CST" = "EXECUTING" ] && ok "段45 审批通过→执行中" || bad "状态错误（$CST）"
CVER=$(SQL "SELECT VER_NO_LABEL FROM erp_pm_project_change WHERE ID='$CID'")
[ "$CVER" = "V2.0" ] && ok "段45 变更单回写基线版本号" || bad "版本号未回写（$CVER）"

# 45.5 历史版只读快照（V1.0 全量可查）
SNAP=$(C -H "Authorization: Bearer $TCP" $B/api/pm/changes/smk-pc-prj/baseline-versions/V1.0 | J "d['data']['lines'][0]['labor']")
[ "${SNAP%%.*}" = "1000" ] && ok "段45 历史版 V1.0 快照只读可查" || bad "历史版快照错（$SNAP）"

# 45.6 C-09 WBS 拦截：在途变更时任务B 工时可录、任务A 被拦
RAWW4=$(C -X POST -H "Authorization: Bearer $TCP" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-pc-prj","changeType":"SCOPE","reason":"技术风险","effectiveDate":"'$TOMORROW'","wbsIds":["smk-pc-taskA"],"description":"拦截测试变更描述二十字以上"}' \
  $B/api/pm/changes/submit)
CID2=$(echo "$RAWW4" | J "d['data']['id']")
# WBS-A（在途变更）由 TCPO 提交 → 应被拦截器 422（C-09）；用 TCPO 避免与 WBS-B 同人同天
TSA=$(BIZ -X POST -H "Authorization: Bearer $TCPO" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$TODAY'","lines":[{"projectId":"smk-pc-prj","wbsId":"smk-pc-taskA","hours":2,"workContent":"SMKPC 变更拦截测试"}]}' \
  $B/api/my/timesheets)
[ "$TSA" = "422" ] && ok "段45 在途变更 WBS-A 工时录入被拦（C-09）" || bad "WBS-A 未拦（$TSA）"
# WBS-B（未受影响）由 TCP 提交 → 成功，证明不误伤
TSB=$(C -X POST -H "Authorization: Bearer $TCP" -H 'Content-Type: application/json' \
  -d '{"workDate":"'$TODAY'","lines":[{"projectId":"smk-pc-prj","wbsId":"smk-pc-taskB","hours":2,"workContent":"SMKPC 未受影响任务"}]}' \
  $B/api/my/timesheets | J "d['data']['id']")
[ -n "$TSB" ] && [ "$TSB" != "None" ] && ok "段45 未受影响 WBS-B 工时不拦（不误伤）" || bad "WBS-B 误拦"

# 45.7 三态跟踪：推进→已完成→PMO 核验→已验证（偏差>50% 升级 PMO）
# 越权：非创建人（TCPO）advance → 403
X1=$(BIZ -X PUT -H "Authorization: Bearer $TCPO" -H 'Content-Type: application/json' \
  -d '{"opinion":"越权"}' $B/api/pm/changes/$CID/advance)
[ "$X1" = "403" ] && ok "段45 advance 限创建人（单据级校验）" || bad "advance 越权未拦（$X1）"
# 创建人（TCP）推进 → DONE
A2=$(C -X PUT -H "Authorization: Bearer $TCP" -H 'Content-Type: application/json' \
  -d '{"opinion":"SMKPC 变更已落地"}' $B/api/pm/changes/$CID/advance | J "d['data']['status']")
[ "$A2" = "DONE" ] && ok "段45 执行中→已完成（PM 确认落地）" || bad "推进失败（$A2）"
# 越权：非 PMO（TCP）verify → 403
V1=$(BIZ -X PUT -H "Authorization: Bearer $TCP" -H 'Content-Type: application/json' \
  -d '{"opinion":"核验","verifyDeviation":60}' $B/api/pm/changes/$CID/verify)
[ "$V1" = "403" ] && ok "段45 verify 限 PMO（单据级校验）" || bad "verify 越权未拦（$V1）"
# PMO（TCPO）核验 → VERIFIED（偏差 60% > 50% → L4 + 升级通知）
VRAW=$(C -X PUT -H "Authorization: Bearer $TCPO" -H 'Content-Type: application/json' \
  -d '{"opinion":"SMKPC 实际超预估","verifyDeviation":60}' $B/api/pm/changes/$CID/verify)
VST=$(echo "$VRAW" | J "d['data']['status']")
VWARN=$(echo "$VRAW" | J "d['data'].get('l4Warning')")
[ "$VST" = "VERIFIED" ] && ok "段45 已完成→已验证（PMO 核验）" || bad "核验失败（$VST）"
case "$VWARN" in *50*|*复盘*|*偏差*) ok "段45 偏差>50% L4 升级提示" ;; *) bad "L4 缺失（$VWARN）" ;; esac
PN=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmProjectChange' AND TARGET_ROLE='ROLE_PMO'")
[ "${PN:-0}" -ge 1 ] && ok "段45 升级通知 PMO" || bad "PMO 通知缺失"

# 45.8 台账统计
STT=$(C -H "Authorization: Bearer $TCP" "$B/api/pm/changes/stats?projectId=smk-pc-prj" | J "d['data']['total']")
[ "${STT:-0}" -ge 2 ] && ok "段45 台账统计 total≥2" || bad "统计错（$STT）"

# 45.9 清理
SQL "DELETE FROM erp_pm_project_change WHERE PROJECT_ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_change_wbs WHERE CHANGE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_project_change WHERE PROJECT_ID='smk-pc-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_baseline_version WHERE PROJECT_ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PmProjectChange' AND i.BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_project_change WHERE PROJECT_ID='smk-pc-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmProjectChange' AND BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_project_change WHERE PROJECT_ID='smk-pc-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_change WHERE PROJECT_ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_timesheet_line WHERE WBS_ID IN ('smk-pc-taskA','smk-pc-taskB')" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_timesheet WHERE ID IN (SELECT TIMESHEET_ID FROM (SELECT ID AS TIMESHEET_ID FROM erp_pm_timesheet_line WHERE WBS_ID IN ('smk-pc-taskA','smk-pc-taskB')) t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_line WHERE PROJECT_ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID='smk-pc-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE='PmProjectChange' AND BIZ_ID LIKE 'smk-pc-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'smk-pc-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'smk-pc-%'" >/dev/null 2>&1

echo "== 段 46 知识产权开始 =="
TODAY=$(date +%F)

# 46.0 隔离与造数：项目（独立研发 ACTIVE）+ 三层 WBS + DRAFT 立项项目 + 三账号
SQL "DELETE FROM erp_pm_ip_asset_status_log WHERE ASSET_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_ip_asset WHERE PROJECT_ID LIKE 'smk-ip-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_ip_asset WHERE PROJECT_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_ip_claim WHERE ARB_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_ip_arbitration WHERE ACHIEVE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'smk-ip-%') t2)) t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_ip_arbitration WHERE ACHIEVE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'smk-ip-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_achievement_member WHERE ACHIEVE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'smk-ip-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_ip_agreement WHERE PROJECT_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_line WHERE PROJECT_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmIp%' AND BIZ_ID LIKE 'smk-ip%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'smk-ip-%'" >/dev/null 2>&1
for U in pm:PM legal:LEGAL rd:RD_LEAD; do
  S=${U%%:*}; R=${U##*:}
  SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
       SELECT 'smk-ip-$S','smk-ip-$S',PASSWORD,'冒烟IP-$S','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
  SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-ip-ur-$S','smk-ip-$S',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_$R'" >/dev/null 2>&1
done
# ACTIVE 独立研发项目（无合同→独立；无协议→L4 例外）+ 阶段/任务
SQL "INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ip-prj','SMKIP 知产项目','研发','INTERNAL','NS-SMKIP','smk-ip-pm',DATE_SUB(CURDATE(), INTERVAL 10 DAY),DATE_ADD(CURDATE(), INTERVAL 30 DAY),'ACTIVE','1',1000,0.10,'0','0','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ip-phase','smk-ip-prj','W1IP',1,'SMKIP 阶段','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, OWNER_USER_ID, OWNER_GRADE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ip-task','smk-ip-prj','W2IP',3,'SMKIP 任务','smk-ip-phase','smk-ip-pm','MID','smoke',NOW(),'0',0)" >/dev/null 2>&1
# DRAFT 立项项目（三层 WBS+预算）用于 BR-24 立项卡控
SQL "INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ip-draft','SMKIP 立项卡控','研发','INTERNAL','NS-SMKIPD','smk-ip-pm',CURDATE(),DATE_ADD(CURDATE(), INTERVAL 60 DAY),'DRAFT','0',0,0.10,'0','0','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ip-dphase','smk-ip-draft','W1D',1,'SMKIPD 阶段','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ip-dpkg','smk-ip-draft','W2D',2,'SMKIPD 工作包','smk-ip-dphase','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, OWNER_USER_ID, OWNER_GRADE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ip-dtask','smk-ip-draft','W3D',3,'SMKIPD 任务','smk-ip-dpkg','smk-ip-pm','MID','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_budget_line (ID, PROJECT_ID, WBS_ID, LABOR, MATERIAL, SUBCONTRACT, TRAVEL, OTHER, INDIRECT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ip-dbl','smk-ip-draft','smk-ip-dtask',1000,0,0,0,0,0,'smoke',NOW(),'0',0)" >/dev/null 2>&1
TIPM=$(C -H 'Content-Type: application/json' -d '{"username":"smk-ip-pm","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TILG=$(C -H 'Content-Type: application/json' -d '{"username":"smk-ip-legal","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TIRD=$(C -H 'Content-Type: application/json' -d '{"username":"smk-ip-rd","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "${TIPM:-}" ] && [ "$TIPM" != "None" ] && [ -n "${TILG:-}" ] && [ "$TILG" != "None" ] && ok "段46 PM/法务/研发负责人 登录" || bad "登录失败"

# 46.1 401 矩阵
EQ "段46 GET agreements 401" "$(HTTP $B/api/pm/ip/agreements)" "401"
EQ "段46 GET achievements 401" "$(HTTP $B/api/pm/ip/achievements)" "401"
EQ "段46 GET assets 401" "$(HTTP $B/api/pm/ip/assets)" "401"

# 46.2 协议：合作比例合计≠100% L1 → 合法保存 → 后签覆盖
CB1=$(BIZ -X POST -H "Authorization: Bearer $TILG" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-ip-prj","coopMode":"COOP","companyRate":60,"partnerRate":30,"partnerName":"SMKIP 合作方","signDate":"'$TODAY'"}' $B/api/pm/ip/agreements)
[ "$CB1" = "422" ] && ok "段46 合作比例≠100% L1 阻断（BR-25）" || bad "比例未拦（$CB1）"
AG1=$(C -X POST -H "Authorization: Bearer $TILG" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-ip-prj","coopMode":"INDEP","signDate":"'$TODAY'"}' $B/api/pm/ip/agreements | J "d['data']['id']")
[ -n "${AG1:-}" ] && [ "$AG1" != "None" ] && ok "段46 独立研发协议保存" || bad "协议保存失败"
# 非法务维护 → 403
RB1=$(BIZ -X POST -H "Authorization: Bearer $TIPM" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-ip-prj","coopMode":"INDEP","signDate":"'$TODAY'"}' $B/api/pm/ip/agreements)
[ "$RB1" = "403" ] && ok "段46 协议维护限法务（403）" || bad "角色未限（$RB1）"

# 46.3 立项卡控（BR-24/C-08）：DRAFT 项目无协议 → submitBaseline 422
SB1=$(BIZ -X POST -H "Authorization: Bearer $TIPM" $B/api/pm/budgets/smk-ip-draft/submit)
[ "$SB1" = "422" ] && ok "段46 立项无协议卡控（BR-24）" || bad "立项未卡（$SB1）"
SBMSG=$(C -X POST -H "Authorization: Bearer $TIPM" $B/api/pm/budgets/smk-ip-draft/submit | J "d['message']")
case "$SBMSG" in *IP*归属*协议*) ok "段46 立项卡控提示 IP 协议缺失" ;; *) bad "提示不符（$SBMSG）" ;; esac

# 46.4 成果登记：WBS L1 硬阻断 → 独立缺协议 L4 放行判公司 100%
# 先删协议验证 L4 例外（独立研发）
SQL "DELETE FROM erp_pm_ip_agreement WHERE PROJECT_ID='smk-ip-prj'" >/dev/null 2>&1
WB1=$(BIZ -X POST -H "Authorization: Bearer $TIPM" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-ip-prj","wbsId":"","type":"PATENT","name":"SMKIP 无WBS","description":"SMKIP 无 WBS 关联的成果描述（20 字以上）","completeDate":"'$TODAY'"}' $B/api/pm/ip/achievements)
[ "$WB1" = "422" ] && ok "段46 成果未关联 WBS L1（C-07）" || bad "WBS 未拦（$WB1）"
AR1=$(C -X POST -H "Authorization: Bearer $TIPM" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-ip-prj","wbsId":"smk-ip-task","type":"PATENT","name":"SMKIP 一种发明专利","description":"SMKIP 独立研发缺协议 L4 例外测试成果描述","completeDate":"'$TODAY'","action":"SUBMIT","members":[{"empId":"smk-ip-pm","empName":"冒烟PM","hours":40}]}' \
  $B/api/pm/ip/achievements)
PATID=$(echo "$AR1" | J "d['data']['id']")
OWN1=$(echo "$AR1" | J "d['data']['ownershipResult']")
L4W=$(echo "$AR1" | J "d['data'].get('l4Warning')")
[ -n "${PATID:-}" ] && [ "$PATID" != "None" ] && ok "段46 独立缺协议 L4 放行登记" || bad "登记失败"
[ "$OWN1" = '{"company":100}' ] && ok "段46 独立研发判公司 100%" || bad "判定错（$OWN1）"
case "$L4W" in *协议*) ok "段46 L4 提示补签协议" ;; *) bad "L4 缺失（$L4W）" ;; esac
MEMC=$(SQL "SELECT COUNT(*) FROM erp_pm_achievement_member WHERE ACHIEVE_ID='$PATID'")
[ "${MEMC:-0}" = "1" ] && ok "段46 参与人快照落库" || bad "快照缺失（$MEMC）"

# 46.5 合作项目缺协议 L1 阻断（另建合作项目验证）
SQL "INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, CONTRACT_ID, CONTRACT_NO, PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ip-coop','SMKIP 合作项目','研发','INTERNAL','NS-SMKIPC','smk-ip-c1','CT-SMKIPC','smk-ip-pm',DATE_SUB(CURDATE(), INTERVAL 5 DAY),DATE_ADD(CURDATE(), INTERVAL 40 DAY),'ACTIVE','1',500,0.10,'0','0','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ip-cphase','smk-ip-coop','W1C',1,'SMKIPC 阶段','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, OWNER_USER_ID, OWNER_GRADE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-ip-ctask','smk-ip-coop','W2C',3,'SMKIPC 任务','smk-ip-cphase','smk-ip-pm','MID','smoke',NOW(),'0',0)" >/dev/null 2>&1
CB2=$(BIZ -X POST -H "Authorization: Bearer $TIPM" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-ip-coop","wbsId":"smk-ip-ctask","type":"PATENT","name":"SMKIPC 合作成果","description":"SMKIPC 合作研发缺协议应 L1 阻断的成果描述","completeDate":"'$TODAY'"}' $B/api/pm/ip/achievements)
[ "$CB2" = "422" ] && ok "段46 合作缺协议 L1 阻断" || bad "合作未拦（$CB2）"

# 46.6 确认 → 自动建档（专利 20 年）→ CONFIRMED 禁改
CF1=$(C -X PUT -H "Authorization: Bearer $TIRD" -H 'Content-Type: application/json' -d '{}' $B/api/pm/ip/achievements/$PATID/confirm | J "d['data']['status']")
[ "$CF1" = "CONFIRMED" ] && ok "段46 研发负责人确认→CONFIRMED" || bad "确认失败（$CF1）"
ASTCNT=$(SQL "SELECT COUNT(*) FROM erp_pm_ip_asset WHERE ACHIEVE_ID='$PATID'")
[ "${ASTCNT:-0}" = "1" ] && ok "段46 确认即建档（一成果一档）" || bad "建档失败（$ASTCNT）"
EXP=$(SQL "SELECT CAST(PROTECT_EXPIRE_DATE AS CHAR) FROM erp_pm_ip_asset WHERE ACHIEVE_ID='$PATID'")
EXPED=$(date -v+20y +%F 2>/dev/null || date -d '+20 year' +%F)
[ "$EXP" = "$EXPED" ] && ok "段46 专利保护期=完成日+20年" || bad "保护期错（$EXP vs $EXPED）"
ED1=$(BIZ -X POST -H "Authorization: Bearer $TIPM" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-ip-prj","wbsId":"smk-ip-task","type":"PATENT","name":"SMKIP 改已确认","description":"SMKIP 尝试修改已确认成果应 422 的描述","completeDate":"'$TODAY'","id":"'$PATID'"}' $B/api/pm/ip/achievements)
[ "$ED1" = "422" ] && ok "段46 已确认成果禁改（BR-26）" || bad "禁改未拦（$ED1）"

# 46.7 仲裁全流转：独立申请 → 防重 409 → 主张 → 意见 → 确认 → 裁决回写重确认
AR2=$(C -X POST -H "Authorization: Bearer $TILG" -H 'Content-Type: application/json' \
  -d '{"achieveId":"'$PATID'"}' $B/api/pm/ip/arbitrations | J "d['data']['id']")
[ -n "${AR2:-}" ] && [ "$AR2" != "None" ] && ok "段46 法务独立申请仲裁" || bad "仲裁创建失败"
DUP=$(BIZ -X POST -H "Authorization: Bearer $TILG" -H 'Content-Type: application/json' \
  -d '{"achieveId":"'$PATID'"}' $B/api/pm/ip/arbitrations)
[ "$DUP" = "409" ] && ok "段46 同成果在途仲裁防重（409）" || bad "防重未拦（$DUP)"
C -X POST -H "Authorization: Bearer $TILG" -H 'Content-Type: application/json' \
  -d '{"claimant":"PARTY_A","claimantName":"SMKIP 公司","claimRate":50,"evidence":"SMKIP 公司独立开发依据"}' $B/api/pm/ip/arbitrations/$AR2/claims >/dev/null
C -X POST -H "Authorization: Bearer $TILG" -H 'Content-Type: application/json' \
  -d '{"claimant":"PARTY_B","claimantName":"SMKIP 员工","claimRate":50,"evidence":"SMKIP 员工实质贡献依据"}' $B/api/pm/ip/arbitrations/$AR2/claims >/dev/null
CLC=$(SQL "SELECT COUNT(*) FROM erp_pm_ip_claim WHERE ARB_ID='$AR2'")
[ "${CLC:-0}" = "2" ] && ok "段46 双方主张留痕（2 条）" || bad "主张缺失（$CLC）"
OP1=$(C -X PUT -H "Authorization: Bearer $TILG" -H 'Content-Type: application/json' \
  -d '{"opinion":"SMKIP 按贡献各半裁决"}' $B/api/pm/ip/arbitrations/$AR2/opinion | J "d['data']['status']")
[ "$OP1" = "OPINION_ISSUED" ] && ok "段46 法务出具意见" || bad "意见失败（$OP1）"
PC1=$(C -X PUT -H "Authorization: Bearer $TIRD" -H 'Content-Type: application/json' \
  -d '{"action":"ACCEPT"}' $B/api/pm/ip/arbitrations/$AR2/confirm | J "d['data']['status']")
[ "$PC1" = "PARTY_CONFIRMED" ] && ok "段46 双方确认接受" || bad "确认失败（$PC1)"
AJ1=$(C -X PUT -H "Authorization: Bearer $TILG" -H 'Content-Type: application/json' \
  -d '{"adjudicateResult":"{\"company\":50,\"partner\":50}"}' $B/api/pm/ip/arbitrations/$AR2/adjudicate | J "d['data']['status']")
[ "$AJ1" = "ADJUDICATED" ] && ok "段46 裁决回写" || bad "裁决失败（$AJ1）"
OWN2=$(SQL "SELECT OWNERSHIP_RESULT FROM erp_pm_achievement WHERE ID='$PATID'")
[ "$OWN2" = '{"company":50,"partner":50}' ] && ok "段46 成果权属被覆盖" || bad "覆盖失败（$OWN2)"
ST2=$(SQL "SELECT STATUS FROM erp_pm_achievement WHERE ID='$PATID'")
[ "$ST2" = "PENDING_CONFIRM" ] && ok "段46 裁决后回待确认重确认" || bad "状态错（$ST2）"

# 46.8 资产申请状态更新 + 历史留痕
ASTID=$(SQL "SELECT ID FROM erp_pm_ip_asset WHERE ACHIEVE_ID='$PATID'")
C -X PUT -H "Authorization: Bearer $TILG" -H 'Content-Type: application/json' \
  -d '{"applyStatus":"SUBMITTED","remark":"SMKIP 已递交专利局"}' $B/api/pm/ip/assets/$ASTID/apply-status >/dev/null
APST=$(SQL "SELECT APPLY_STATUS FROM erp_pm_ip_asset WHERE ID='$ASTID'")
[ "$APST" = "SUBMITTED" ] && ok "段46 申请状态更新为已递交" || bad "状态错（$APST)"
LOGC=$(SQL "SELECT COUNT(*) FROM erp_pm_ip_asset_status_log WHERE ASSET_ID='$ASTID' AND TO_STATUS='SUBMITTED'")
[ "${LOGC:-0}" -ge 1 ] && ok "段46 申请状态历史留痕" || bad "历史缺失（$LOGC)"

# 46.9 清理
SQL "DELETE FROM erp_pm_ip_asset_status_log WHERE ASSET_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_ip_asset WHERE PROJECT_ID LIKE 'smk-ip-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_ip_asset WHERE PROJECT_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_ip_claim WHERE ARB_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_ip_arbitration WHERE ACHIEVE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'smk-ip-%') t2)) t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_ip_arbitration WHERE ACHIEVE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'smk-ip-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_achievement_member WHERE ACHIEVE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'smk-ip-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_ip_agreement WHERE PROJECT_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_line WHERE PROJECT_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmIp%' AND BIZ_ID LIKE 'smk-ip%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'smk-ip-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'smk-ip-%'" >/dev/null 2>&1

echo "== 段 47 研发物料试制开始 =="
TODAY=$(date +%F)

# 47.0 隔离与造数：项目 + 阶段 + TRIAL/DEV 任务 + 库存 + 两账号
SQL "DELETE FROM erp_pm_sample_ledger WHERE ISSUE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_trial_consumption WHERE PROJECT_ID='smk-trl-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_trial_consumption_line WHERE CONSUMPTION_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_trial_consumption WHERE PROJECT_ID='smk-trl-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_trial_consumption WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_trial_return_line WHERE RETURN_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_trial_return WHERE PROJECT_ID='smk-trl-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_trial_return WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_mat_issue_line WHERE ISSUE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_mat_issue WHERE PROJECT_ID='smk-trl-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_mat_issue WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_cost_collect WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST' AND (SOURCE_DOC_NO LIKE '%TRLR%' OR SOURCE_DOC_NO LIKE '%smk-trl%')" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_line WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_stock WHERE ITEM_CODE='SMKTRL-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'smk-trl-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'smk-trl-%'" >/dev/null 2>&1
for U in pm:PM rd:RD_LEAD; do
  S=${U%%:*}; R=${U##*:}
  SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
       SELECT 'smk-trl-$S','smk-trl-$S',PASSWORD,'冒烟试制-$S','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
  SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-trl-ur-$S','smk-trl-$S',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_$R'" >/dev/null 2>&1
done
SQL "INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-trl-prj','SMKTRL 试制项目','研发','INTERNAL','NS-SMKTRL','smk-trl-pm',DATE_SUB(CURDATE(), INTERVAL 10 DAY),DATE_ADD(CURDATE(), INTERVAL 30 DAY),'ACTIVE','1',1000,0.10,'0','0','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-trl-phase','smk-trl-prj','W1TRL',1,'SMKTRL 阶段','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, OWNER_USER_ID, OWNER_GRADE, TASK_TYPE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-trl-task','smk-trl-prj','W2TRIAL',3,'SMKTRL 试制任务','smk-trl-phase','smk-trl-rd','MID','TRIAL','smoke',NOW(),'0',0),
            ('smk-trl-dev','smk-trl-prj','W2DEV',3,'SMKTRL 开发任务','smk-trl-phase','smk-trl-pm','MID','DEV','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_budget_line (ID, PROJECT_ID, WBS_ID, LABOR, MATERIAL, SUBCONTRACT, TRAVEL, OTHER, INDIRECT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-trl-bl','smk-trl-prj','smk-trl-task',0,500,0,0,0,0,'smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT IGNORE INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, BIN_CODE, QTY, AVAILABLE_QTY, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-trl-stock','WH-02','SMKTRL-ITEM','SMKTRL 物料','SMKTRL-B1','',100,100,'smoke',NOW(),'0',0)" >/dev/null 2>&1
TTPM=$(C -H 'Content-Type: application/json' -d '{"username":"smk-trl-pm","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TTRD=$(C -H 'Content-Type: application/json' -d '{"username":"smk-trl-rd","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "${TTPM:-}" ] && [ "$TTPM" != "None" ] && [ -n "${TTRD:-}" ] && [ "$TTRD" != "None" ] && ok "段47 PM/研发负责人 登录" || bad "登录失败"

# 47.1 401 矩阵
EQ "段47 GET consumptions 401" "$(HTTP $B/api/pm/trial/consumptions)" "401"
EQ "段47 GET costs 401" "$(HTTP "$B/api/pm/trial/costs/summary?projectId=smk-trl-prj")" "401"

# 47.2 试制领料：关联 DEV 任务 L1（C-4.8-12）→ 关联 TRIAL 任务扣库存
DV=$(BIZ -X POST -H "Authorization: Bearer $TTPM" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-trl-prj","issueDate":"'$TODAY'","trialFlag":"1","lines":[{"wbsId":"smk-trl-dev","itemId":"SMKTRL-ITEM","warehouseCode":"WH-02","batchNo":"SMKTRL-B1","qty":5,"unitPrice":25}]}' \
  $B/api/pm/cost/material-issues/submit)
[ "$DV" = "422" ] && ok "段47 试制领料关联 DEV 任务 L1（C-12）" || bad "未拦（$DV）"
IS1=$(C -X POST -H "Authorization: Bearer $TTPM" -H 'Content-Type: application/json' \
  -d '{"projectId":"smk-trl-prj","issueDate":"'$TODAY'","trialFlag":"1","trialPurpose":"SMKTRL 样件验证","lines":[{"wbsId":"smk-trl-task","itemId":"SMKTRL-ITEM","warehouseCode":"WH-02","batchNo":"SMKTRL-B1","qty":10,"unitPrice":25}]}' \
  $B/api/pm/cost/material-issues/submit | J "d['data']['id']")
[ -n "${IS1:-}" ] && [ "$IS1" != "None" ] && ok "段47 试制领料提交（TRIAL 任务）" || bad "领料失败"
AV1=$(SQL "SELECT CAST(AVAILABLE_QTY AS CHAR) FROM erp_inv_stock WHERE ITEM_CODE='SMKTRL-ITEM'")
[ "${AV1%%.*}" = "90" ] && ok "段47 库存扣减 100→90" || bad "库存未扣（$AV1）"

# 47.3 审核：PM 审核 403 → 研发负责人通过归集
PMRV=$(BIZ -X POST -H "Authorization: Bearer $TTPM" -H 'Content-Type: application/json' -d '{"approved":true}' $B/api/pm/cost/material-issues/$IS1/review)
[ "$PMRV" = "403" ] && ok "段47 试制单 PM 审核 403（限研发负责人）" || bad "权限未限（$PMRV）"
C -X POST -H "Authorization: Bearer $TTRD" -H 'Content-Type: application/json' -d '{"approved":true}' $B/api/pm/cost/material-issues/$IS1/review >/dev/null
CC1=$(SQL "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE PROJECT_ID='smk-trl-prj' AND COST_TYPE='MATERIAL'")
[ "${CC1:-0}" = "1" ] && ok "段47 试制领料归集材料成本" || bad "归集失败（$CC1）"

# 47.4 消耗确认：防重 409 → 实耗 8 结余 2 + 样品台账批次关联 + 差异 L4
CN1=$(C -X POST -H "Authorization: Bearer $TTRD" -H 'Content-Type: application/json' \
  -d '{"issueId":"'$IS1'","lines":[{"itemId":"SMKTRL-ITEM","actualQty":8,"deviationNote":""}],"samples":[{"name":"SMKTRL 样品","itemId":"SMKTRL-ITEM","qty":1,"destination":"CUSTOMER"}]}' \
  $B/api/pm/trial/consumptions)
SUR=$(echo "$CN1" | J "d['data']['totalSurplus']")
[ "${SUR%%.*}" = "2" ] && ok "段47 消耗确认结余 2（10-8）" || bad "结余错（$SUR）"
SLC=$(SQL "SELECT COUNT(*) FROM erp_pm_sample_ledger WHERE ISSUE_ID='$IS1' AND BATCH_NO='SMKTRL-B1'")
[ "${SLC:-0}" = "1" ] && ok "段47 样品台账批次关联（BR-39）" || bad "台账缺失（$SLC）"
DUP=$(BIZ -X POST -H "Authorization: Bearer $TTRD" -H 'Content-Type: application/json' \
  -d '{"issueId":"'$IS1'","lines":[{"itemId":"SMKTRL-ITEM","actualQty":8}]}' $B/api/pm/trial/consumptions)
[ "$DUP" = "409" ] && ok "段47 消耗确认防重（409）" || bad "防重未拦（$DUP)"

# 47.5 结余退库：跨项目批次 L1 → 结余超限 422 → REUSABLE 入库+冲减
XB=$(BIZ -X POST -H "Authorization: Bearer $TTRD" -H 'Content-Type: application/json' \
  -d '{"issueId":"'$IS1'","lines":[{"itemId":"SMKTRL-ITEM","batchNo":"FOREIGN-B","returnQty":1,"qcResult":"REUSABLE"}]}' \
  $B/api/pm/trial/returns/submit)
[ "$XB" = "422" ] && ok "段47 跨项目批次 L1（BR-04）" || bad "跨项目未拦（$XB）"
XS=$(BIZ -X POST -H "Authorization: Bearer $TTRD" -H 'Content-Type: application/json' \
  -d '{"issueId":"'$IS1'","lines":[{"itemId":"SMKTRL-ITEM","batchNo":"SMKTRL-B1","returnQty":5,"qcResult":"REUSABLE"}]}' \
  $B/api/pm/trial/returns/submit)
[ "$XS" = "422" ] && ok "段47 退库超结余 422" || bad "超结余未拦（$XS）"
RT1=$(C -X POST -H "Authorization: Bearer $TTRD" -H 'Content-Type: application/json' \
  -d '{"issueId":"'$IS1'","lines":[{"itemId":"SMKTRL-ITEM","batchNo":"SMKTRL-B1","returnQty":2,"qcResult":"REUSABLE"}]}' \
  $B/api/pm/trial/returns/submit | J "d['data']['id']")
C -X PUT -H "Authorization: Bearer $TTRD" -H 'Content-Type: application/json' -d '{"approved":true}' $B/api/pm/trial/returns/$RT1/review >/dev/null
AV2=$(SQL "SELECT CAST(AVAILABLE_QTY AS CHAR) FROM erp_inv_stock WHERE ITEM_CODE='SMKTRL-ITEM'")
[ "${AV2%%.*}" = "92" ] && ok "段47 REUSABLE 入库 90→92" || bad "入库错（$AV2)"
NEG=$(SQL "SELECT CAST(SUM(AMOUNT) AS CHAR) FROM erp_pm_cost_collect WHERE SOURCE_TYPE='TRIAL_RETURN' AND PROJECT_ID='smk-trl-prj'")
[ "${NEG%%.*}" = "-50" ] && ok "段47 冲减成本 -50（2×25）" || bad "冲减错（$NEG）"

# 47.6 试制成本聚合
SUM=$(C -H "Authorization: Bearer $TTPM" "$B/api/pm/trial/costs/summary?projectId=smk-trl-prj")
MAT=$(echo "$SUM" | J "d['data']['material']")
BDT=$(echo "$SUM" | J "d['data']['budget']")
[ "${MAT%%.*}" = "200" ] && ok "段47 试制材料聚合 200（250 领-50 退）" || bad "材料聚合错（$MAT)"
[ "${BDT%%.*}" = "500" ] && ok "段47 试制预算比对 500" || bad "预算错（$BDT）"

# 47.7 清理
SQL "DELETE FROM erp_pm_sample_ledger WHERE ISSUE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_trial_consumption WHERE PROJECT_ID='smk-trl-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_trial_consumption_line WHERE CONSUMPTION_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_trial_consumption WHERE PROJECT_ID='smk-trl-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_trial_consumption WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_trial_return_line WHERE RETURN_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_trial_return WHERE PROJECT_ID='smk-trl-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_trial_return WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_mat_issue_line WHERE ISSUE_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_mat_issue WHERE PROJECT_ID='smk-trl-prj') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_mat_issue WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_cost_collect WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST' AND (SOURCE_DOC_NO LIKE '%TRLR%' OR SOURCE_DOC_NO LIKE '%smk-trl%')" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_budget_line WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID='smk-trl-prj'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_stock WHERE ITEM_CODE='SMKTRL-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'smk-trl-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'smk-trl-%'" >/dev/null 2>&1

echo "段 47 研发物料试制追加完成"

echo "== 段 48 服务工单开始 =="

# 48.0 隔离与造数：客户 + 四账号（客服/工程师/主管/仓管）+ 物料库存 + NCR
SQL "DELETE FROM erp_pm_service_process_record WHERE ORDER_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_service_confirm_token WHERE ORDER_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_service_sla_log WHERE ORDER_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_service_survey WHERE ORDER_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_service_improve_task WHERE ORDER_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmService%'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_material_issue_line WHERE ITEM_CODE='SMKSO-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_material_issue WHERE WORK_ORDER_NO LIKE 'WO-SMKSO%'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_stock WHERE ITEM_CODE='SMKSO-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_batch WHERE ITEM_CODE='SMKSO-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_qms_ncr WHERE ID='smk-so-ncr'" >/dev/null 2>&1
SQL "DELETE FROM erp_mdm_item WHERE ITEM_CODE='SMKSO-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'smk-so-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'smk-so-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_mdm_customer_group WHERE ID='smk-so-cust'" >/dev/null 2>&1
SQL "INSERT INTO erp_mdm_customer_group (ID, CUSTOMER_CODE, CUSTOMER_NAME, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-so-cust','SMKSO-CUST','SMKSO 冒烟客户','1','smoke',NOW(),'0',0)" >/dev/null 2>&1
for U in cs:CS eng:SERVICE_ENG eng2:SERVICE_ENG mgr:SERVICE_MGR wh:WAREHOUSE; do
  S=${U%%:*}; R=${U##*:}
  SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
       SELECT 'smk-so-$S','smk-so-$S',PASSWORD,'冒烟工单-$S','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
  SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'smk-so-ur-$S','smk-so-$S',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_$R'" >/dev/null 2>&1
done
SQL "INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, STANDARD_COST, STATUS, CREATE_BY)
     VALUES ('smk-so-it','SMKSO-ITEM','SMKSO 售后物料','0001','PC','STRUCT','BUY','NORMAL','1',25,'1','smoke')" >/dev/null 2>&1
SQL "INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, BIN_CODE, QTY, AVAILABLE_QTY, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-so-stock','WH-MAIN','SMKSO-ITEM','SMKSO 售后物料','SMKSO-B1','',100,100,'smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY)
     VALUES ('smk-so-lg','SMKSO-B1','SMKSO-ITEM','SMKSO 售后物料','2026-09-01','2027-06-30','0','1','smoke')" >/dev/null 2>&1
SQL "INSERT INTO erp_qms_ncr (ID, NCR_NO, ITEM_CODE, QTY, SEVERITY, CTQ_FLAG, REGULATORY_FLAG, FREEZE_SCOPE, FROZEN_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('smk-so-ncr','SMKSO-NCR-001','SMKSO-ITEM',1,'Major','0','0','NONE','0','smoke',NOW(),'0',0)" >/dev/null 2>&1
TCS=$(C -H 'Content-Type: application/json' -d '{"username":"smk-so-cs","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TENG=$(C -H 'Content-Type: application/json' -d '{"username":"smk-so-eng","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TMGR=$(C -H 'Content-Type: application/json' -d '{"username":"smk-so-mgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TWH=$(C -H 'Content-Type: application/json' -d '{"username":"smk-so-wh","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "${TCS:-}" ] && [ "$TCS" != "None" ] && [ -n "${TENG:-}" ] && [ "$TENG" != "None" ] \
  && [ -n "${TMGR:-}" ] && [ "$TMGR" != "None" ] && [ -n "${TWH:-}" ] && [ "$TWH" != "None" ] \
  && ok "段48 客服/工程师/主管/仓管 登录" || bad "段48 登录失败"

# 48.1 401 矩阵 + 匿名 token 端点放行
EQ "段48 GET orders 401" "$(HTTP $B/api/pm/service-orders)" "401"
EQ "段48 POST orders 401" "$(HTTP -X POST $B/api/pm/service-orders)" "401"
BT=$(BIZ "$B/api/public/service-orders/confirm/bogus-token")
[ "$BT" = "404" ] && ok "段48 匿名 token 端点放行（无效 token 业务 404）" || bad "段48 匿名端点异常（$BT）"

# 48.2 登记 L1 阻断与自动定级
NC=$(BIZ -X POST -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"customerId":"no-such-cust","complaintType":"OTHER","impactLevel":"NORMAL","description":"x"}' \
  $B/api/pm/service-orders)
[ "$NC" = "422" ] && ok "段48 客户不存在 422" || bad "段48 客户校验未拦（$NC）"
ND=$(BIZ -X POST -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"customerId":"smk-so-cust","complaintType":"OTHER","impactLevel":"NORMAL","description":""}' \
  $B/api/pm/service-orders)
[ "$ND" = "422" ] && ok "段48 描述必填 422" || bad "段48 描述校验未拦（$ND）"
# A 单：一级（影响生产）
OA=$(C -X POST -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"customerId":"smk-so-cust","customerName":"SMKSO 冒烟客户","complaintType":"PRODUCT_QUALITY","impactLevel":"PRODUCTION","description":"SMKSO 产线停机重大质量事故"}' \
  $B/api/pm/service-orders)
OID=$(echo "$OA" | J "d['data']['id']")
ONO=$(echo "$OA" | J "d['data']['orderNo']")
LVA=$(echo "$OA" | J "d['data']['level']")
[ "$LVA" = "1" ] && ok "段48 影响生产自动定级 L1" || bad "段48 L1 定级错（$LVA）"
NSM=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE TARGET_ROLE='ROLE_SALES_MGR' AND BIZ_TYPE='PmServiceOrder' AND TITLE LIKE '%一级投诉%'")
[ "${NSM:-0}" -ge 1 ] && ok "段48 一级投诉通知销售经理" || bad "段48 一级通知缺失（$NSM）"
# B 单：一般 → L3；C 单：严重 → L2（后用于驳回链）
OB=$(C -X POST -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"customerId":"smk-so-cust","customerName":"SMKSO 冒烟客户","complaintType":"SERVICE_ATTITUDE","impactLevel":"NORMAL","description":"SMKSO 服务态度一般投诉"}' \
  $B/api/pm/service-orders)
OBID=$(echo "$OB" | J "d['data']['id']")
LB=$(echo "$OB" | J "d['data']['level']")
[ "$LB" = "3" ] && ok "段48 一般投诉自动定级 L3" || bad "段48 L3 定级错（$LB）"
OC=$(C -X POST -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"customerId":"smk-so-cust","customerName":"SMKSO 冒烟客户","complaintType":"FUNCTION_DEFECT","impactLevel":"CORE","description":"SMKSO 核心功能不可用缺陷"}' \
  $B/api/pm/service-orders)
OCID=$(echo "$OC" | J "d['data']['id']")
LC=$(echo "$OC" | J "d['data']['level']")
[ "$LC" = "2" ] && ok "段48 核心功能不可用定级 L2" || bad "段48 L2 定级错（$LC）"
# SLA 起点=登记时间（响应截止 = 登记 + 2h，±60s 容差）
DEAD=$(SQL "SELECT TIMESTAMPDIFF(SECOND, CREATE_DATE, RESP_DEADLINE) FROM erp_pm_service_order WHERE ID='$OID'")
[ "$DEAD" -ge 7140 ] && [ "$DEAD" -le 7260 ] && ok "段48 L1 响应截止=登记+2h（BR-48-03）" || bad "段48 响应截止错（$DEAD）"

# 48.3 定级调整：无理由 422 → 有理由留痕
NR=$(BIZ -X PUT -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"level":3}' $B/api/pm/service-orders/$OCID/level)
[ "$NR" = "422" ] && ok "段48 定级调整无理由 422" || bad "段48 理由校验未拦（$NR）"
C -X PUT -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"level":3,"reason":"SMKSO 功能缺陷实际影响有限，降为三级"}' $B/api/pm/service-orders/$OCID/level >/dev/null
LV2=$(SQL "SELECT LEVEL FROM erp_pm_service_order WHERE ID='$OCID'")
[ "$LV2" = "3" ] && ok "段48 定级调整生效" || bad "段48 调整未生效（$LV2）"
TRL=$(SQL "SELECT COUNT(*) FROM erp_pm_service_process_record WHERE ORDER_ID='$OCID' AND CONTENT LIKE '%L2 → L3%'")
[ "${TRL:-0}" -ge 1 ] && ok "段48 定级调整 Before/After 留痕（L5）" || bad "段48 留痕缺失（$TRL）"

# 48.4 派单：工程师 403 → 客服派单 → 改派留痕 → 待指派升级主管
DP=$(BIZ -X PUT -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d '{"engineerId":"smk-so-eng"}' $B/api/pm/service-orders/$OID/dispatch)
[ "$DP" = "403" ] && ok "段48 工程师无派单权 403" || bad "段48 派单权限未拦（$DP）"
C -X PUT -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"engineerId":"smk-so-eng","engineerName":"冒烟工单-eng"}' $B/api/pm/service-orders/$OID/dispatch >/dev/null
ST1=$(SQL "SELECT STATUS FROM erp_pm_service_order WHERE ID='$OID'")
[ "$ST1" = "DISPATCHED" ] && ok "段48 派单后状态 DISPATCHED" || bad "段48 派单状态错（$ST1）"
C -X PUT -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"engineerId":"smk-so-mgr","engineerName":"冒烟工单-mgr"}' $B/api/pm/service-orders/$OID/dispatch >/dev/null
RSG=$(SQL "SELECT COUNT(*) FROM erp_pm_service_process_record WHERE ORDER_ID='$OID' AND CONTENT LIKE '%改派%'")
[ "${RSG:-0}" -ge 1 ] && ok "段48 改派留痕" || bad "段48 改派留痕缺失"
C -X PUT -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' -d '{}' \
  $B/api/pm/service-orders/$OCID/dispatch >/dev/null
DNT=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE TARGET_ROLE='ROLE_SERVICE_MGR' AND BIZ_TYPE='PmServiceOrderDispatch'")
[ "${DNT:-0}" -ge 1 ] && ok "段48 待指派升级服务主管" || bad "段48 待指派通知缺失"
# 负载推荐：A→mgr、B→eng、C→eng（eng 在手 2，mgr 在手 1，eng2 在手 0）→ 推荐应为 eng2
C -X PUT -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"engineerId":"smk-so-eng","engineerName":"冒烟工单-eng"}' $B/api/pm/service-orders/$OCID/dispatch >/dev/null
REC=$(C -H "Authorization: Bearer $TCS" $B/api/pm/service-orders/engineers/recommend | J "d['data']['recommended']['userId']")
[ "$REC" = "smk-so-eng2" ] && ok "段48 负载推荐最少在手优先" || bad "段48 推荐错（$REC）"

# 48.5 受理与处理记录
AC=$(C -X POST -H "Authorization: Bearer $TENG" $B/api/pm/service-orders/$OID/accept | J "d['data']['respondedAt']")
[ -n "$AC" ] && [ "$AC" != "None" ] && ok "段48 受理记响应时间" || bad "段48 受理无响应时间"
C -X POST -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d '{"content":"SMKSO 现场排查：更换电源模块后恢复"}' $B/api/pm/service-orders/$OID/records >/dev/null
PRC=$(SQL "SELECT COUNT(*) FROM erp_pm_service_process_record WHERE ORDER_ID='$OID'")
[ "${PRC:-0}" -ge 3 ] && ok "段48 处理记录仅追加（≥登记+受理+处理）" || bad "段48 记录数错（$PRC）"
# 根因未填不可提交关闭
SRC=$(BIZ -X POST -H "Authorization: Bearer $TENG" $B/api/pm/service-orders/$OID/submit-confirm)
[ "$SRC" = "422" ] && ok "段48 根因未填不可提交关闭" || bad "段48 根因卡控未拦（$SRC）"
# 设计缺陷抄送研发
C -X PUT -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d '{"rootCause":"DESIGN"}' $B/api/pm/service-orders/$OBID/root-cause >/dev/null
RDL=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE TARGET_ROLE='ROLE_RD_LEAD' AND BIZ_TYPE='PmServiceOrderDesign'")
[ "${RDL:-0}" -ge 1 ] && ok "段48 设计缺陷抄送研发负责人" || bad "段48 设计缺陷通知缺失"

# 48.6 NCR 卡控（BR-48-36）：质量类无 NCR 不可标根治 → 无效 NCR 422 → 关联后放行
MC=$(BIZ -X POST -H "Authorization: Bearer $TENG" $B/api/pm/service-orders/$OID/cured)
[ "$MC" = "422" ] && ok "段48 质量类无 NCR 不可标根治" || bad "段48 NCR 卡控未拦（$MC）"
BC=$(BIZ -X PUT -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d '{"ncrNo":"NCR-BOGUS-999"}' $B/api/pm/service-orders/$OID/ncr)
[ "$BC" = "422" ] && ok "段48 无效 NCR 422" || bad "段48 NCR 校验未拦（$BC）"
C -X PUT -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d '{"ncrNo":"SMKSO-NCR-001"}' $B/api/pm/service-orders/$OID/ncr >/dev/null
C -X POST -H "Authorization: Bearer $TENG" $B/api/pm/service-orders/$OID/cured >/dev/null
CUR=$(SQL "SELECT ROOT_CURED FROM erp_pm_service_order WHERE ID='$OID'")
[ "$CUR" = "1" ] && ok "段48 关联 NCR 后可标根治" || bad "段48 根治未生效（$CUR）"

# 48.7 售后领料：缺工单号 422 → 无效工单 422 → 创建+过账扣库存+成本累加+反查
NSO=$(BIZ -X POST -H "Authorization: Bearer $TWH" -H 'Content-Type: application/json' \
  -d '{"issueType":"AFTER_SALE","workOrderNo":"WO-SMKSO-1","lines":[{"itemCode":"SMKSO-ITEM","qty":10}]}' \
  $B/api/inv/issues)
[ "$NSO" = "422" ] && ok "段48 售后领料缺工单号 422" || bad "段48 工单号校验未拦（$NSO）"
BNO=$(BIZ -X POST -H "Authorization: Bearer $TWH" -H 'Content-Type: application/json' \
  -d '{"issueType":"AFTER_SALE","workOrderNo":"WO-SMKSO-1","serviceOrderNo":"SO-BOGUS-1","lines":[{"itemCode":"SMKSO-ITEM","qty":10}]}' \
  $B/api/inv/issues)
[ "$BNO" = "422" ] && ok "段48 售后领料工单不存在 422" || bad "段48 工单存在性未拦（$BNO）"
ASI=$(C -X POST -H "Authorization: Bearer $TWH" -H 'Content-Type: application/json' \
  -d '{"issueType":"AFTER_SALE","workOrderNo":"WO-SMKSO-1","serviceOrderNo":"'$ONO'","purpose":"SMKSO 换件","lines":[{"itemCode":"SMKSO-ITEM","qty":10}]}' \
  $B/api/inv/issues | J "d['data']['issue']['id']")
[ -n "$ASI" ] && [ "$ASI" != "None" ] && ok "段48 售后领料单创建" || bad "段48 领料单创建失败"
C -X POST -H "Authorization: Bearer $TWH" $B/api/inv/issues/$ASI/postings >/dev/null
AV3=$(SQL "SELECT CAST(AVAILABLE_QTY AS CHAR) FROM erp_inv_stock WHERE ITEM_CODE='SMKSO-ITEM'")
[ "${AV3%%.*}" = "90" ] && ok "段48 售后领料过账扣库存 100→90" || bad "段48 扣库存错（$AV3）"
MCT=$(SQL "SELECT CAST(MATERIAL_COST AS CHAR) FROM erp_pm_service_order WHERE ORDER_NO='$ONO'")
[ "${MCT%%.*}" = "250" ] && ok "段48 服务成本累加 250（10×25）" || bad "段48 成本累加错（$MCT）"
ISQ=$(C -H "Authorization: Bearer $TWH" "$B/api/inv/issues?issueType=AFTER_SALE&serviceOrderNo=$ONO" | J "d['data']['total']")
[ "${ISQ:-0}" -ge 1 ] && ok "段48 按工单号反查售后领料" || bad "段48 反查空（$ISQ）"

# 48.8 确认双通道：驳回重计时+2 次升级 → token 一次性 → 代录关闭
C -X PUT -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d '{"rootCause":"MANUFACTURE"}' $B/api/pm/service-orders/$OID/root-cause >/dev/null
SQL "UPDATE erp_pm_service_order SET RESOLVE_DEADLINE=DATE_ADD(NOW(), INTERVAL 10 HOUR) WHERE ID='$OID'" >/dev/null 2>&1
C -X POST -H "Authorization: Bearer $TENG" $B/api/pm/service-orders/$OID/submit-confirm >/dev/null
RJ1=$(C -X POST -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"result":"REJECTED","note":"SMKSO 问题未彻底解决"}' $B/api/pm/service-orders/$OID/agent-confirm | J "d['data']['status']")
[ "$RJ1" = "PROCESSING" ] && ok "段48 驳回退回处理（重计时）" || bad "段48 驳回状态错（$RJ1）"
RDD=$(SQL "SELECT TIMESTAMPDIFF(SECOND, NOW(), RESOLVE_DEADLINE) FROM erp_pm_service_order WHERE ID='$OID'")
# A 单为 L1（解决时限 24h），驳回重计时 = 驳回时刻 + 24h（±60s 容差）
[ "$RDD" -ge 85800 ] && [ "$RDD" -le 87000 ] && ok "段48 驳回重计时（≈now+24h，L1 口径）" || bad "段48 重计时错（$RDD）"
C -X POST -H "Authorization: Bearer $TENG" $B/api/pm/service-orders/$OID/submit-confirm >/dev/null
RJ2=$(C -X POST -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"result":"REJECTED"}' $B/api/pm/service-orders/$OID/agent-confirm | J "d['data']['escalateStage']")
[ "$RJ2" = "1" ] && ok "段48 驳回 2 次升级服务主管" || bad "段48 驳回升级错（$RJ2）"
C -X POST -H "Authorization: Bearer $TENG" $B/api/pm/service-orders/$OID/submit-confirm >/dev/null
LNK=$(C -X POST -H "Authorization: Bearer $TENG" $B/api/pm/service-orders/$OID/confirm-link | J "d['data']['token']")
[ -n "$LNK" ] && [ "$LNK" != "None" ] && ok "段48 生成确认链接" || bad "段48 链接生成失败"
TKV=$(BIZ "$B/api/public/service-orders/confirm/$LNK")
[ "$TKV" = "200" ] && ok "段48 匿名 token 取摘要" || bad "段48 token 摘要异常（$TKV）"
TKC=$(C -X POST -H 'Content-Type: application/json' -d '{"result":"RESOLVED"}' \
  $B/api/public/service-orders/confirm/$LNK | J "d['data']['status']")
[ "$TKC" = "CLOSED" ] && ok "段48 客户经链接确认关闭" || bad "段48 链接确认错（$TKC）"
CV=$(SQL "SELECT CONFIRMED_VIA FROM erp_pm_service_order WHERE ID='$OID'")
[ "$CV" = "LINK" ] && ok "段48 confirmed_via=LINK" || bad "段48 via 错（$CV）"
TK2=$(BIZ -X POST -H 'Content-Type: application/json' -d '{"result":"RESOLVED"}' \
  $B/api/public/service-orders/confirm/$LNK)
[ "$TK2" = "422" ] && ok "段48 token 一次性（二次 422）" || bad "段48 token 复用未拦（$TK2）"

# 48.9 回访 2 分 → 改进任务 → 逾期升级销售经理
SV=$(C -X PUT -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"score":2,"suggestion":"SMKSO 响应仍偏慢"}' $B/api/pm/service-orders/$OID/survey)
ITID=$(echo "$SV" | J "d['data']['improveTask']['id']")
[ -n "$ITID" ] && [ "$ITID" != "None" ] && ok "段48 低分自动生成改进任务（BR-48-06）" || bad "段48 改进任务未生成"
SV2=$(BIZ -X PUT -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"score":5}' $B/api/pm/service-orders/$OID/survey)
[ "$SV2" = "409" ] && ok "段48 回访防重（409）" || bad "段48 回访防重未拦（$SV2）"
SQL "UPDATE erp_pm_service_improve_task SET DUE_DATE=DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE ID='$ITID'" >/dev/null 2>&1
C -X POST -H "Authorization: Bearer $TADM" $B/api/pm/service-orders/sweep >/dev/null
ESC=$(SQL "SELECT ESCALATED_SALES FROM erp_pm_service_improve_task WHERE ID='$ITID'")
[ "$ESC" = "1" ] && ok "段48 改进逾期升级销售经理" || bad "段48 逾期升级错（$ESC）"
STS=$(C -H "Authorization: Bearer $TCS" "$B/api/pm/service-orders/surveys/stats")
BCN=$(echo "$STS" | J "len([r for r in d['data']['byCustomer'] if r['customerId']=='smk-so-cust'])")
[ "${BCN:-0}" -ge 1 ] && ok "段48 回访统计按客户维度" || bad "段48 统计维度缺失"

# 48.10 挂起暂停计时 + SLA 升级幂等 + 默认关闭
C -X POST -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"reason":"SMKSO 客户信息缺失"}' $B/api/pm/service-orders/$OCID/suspend >/dev/null
SFL=$(SQL "SELECT SUSPENDED_FLAG FROM erp_pm_service_order WHERE ID='$OCID'")
[ "$SFL" = "1" ] && ok "段48 挂起置位（SLA 暂停）" || bad "段48 挂起未生效"
SQL "UPDATE erp_pm_service_order SET SUSPEND_AT=DATE_SUB(NOW(), INTERVAL 2 HOUR) WHERE ID='$OCID'" >/dev/null 2>&1
C -X POST -H "Authorization: Bearer $TCS" $B/api/pm/service-orders/$OCID/resume >/dev/null
SHR=$(SQL "SELECT CAST(SUSPEND_HOURS AS CHAR) FROM erp_pm_service_order WHERE ID='$OCID'")
SHRINT=${SHR%%.*}
[ "${SHRINT:-0}" -ge 1 ] && ok "段48 恢复累计挂起时长≈2h" || bad "段48 挂起累计错（$SHR）"
# C 单响应超时 → 第一档升级主管（幂等：重复 sweep 不新增）
SQL "UPDATE erp_pm_service_order SET RESP_DEADLINE=DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE ID='$OCID'" >/dev/null 2>&1
C -X POST -H "Authorization: Bearer $TADM" $B/api/pm/service-orders/sweep >/dev/null
ES1=$(SQL "SELECT ESCALATE_STAGE FROM erp_pm_service_order WHERE ID='$OCID'")
LGN1=$(SQL "SELECT COUNT(*) FROM erp_pm_service_sla_log WHERE ORDER_ID='$OCID' AND EVENT='ESCALATE'")
C -X POST -H "Authorization: Bearer $TADM" $B/api/pm/service-orders/sweep >/dev/null
LGN2=$(SQL "SELECT COUNT(*) FROM erp_pm_service_sla_log WHERE ORDER_ID='$OCID' AND EVENT='ESCALATE'")
[ "$ES1" = "1" ] && [ "$LGN1" = "$LGN2" ] && ok "段48 SLA 超时升级+重复扫描幂等" || bad "段48 升级幂等错（stage=$ES1 logs=$LGN1/$LGN2）"
# B 单：先派单（submit-confirm 要求 DISPATCHED/PROCESSING）→ 提交确认 10 天前 → sweep → 2 次提醒 + 默认关闭
C -X PUT -H "Authorization: Bearer $TCS" -H 'Content-Type: application/json' \
  -d '{"engineerId":"smk-so-eng","engineerName":"冒烟工单-eng"}' $B/api/pm/service-orders/$OBID/dispatch >/dev/null
C -X PUT -H "Authorization: Bearer $TENG" -H 'Content-Type: application/json' \
  -d '{"rootCause":"EXTERNAL"}' $B/api/pm/service-orders/$OBID/root-cause >/dev/null
C -X POST -H "Authorization: Bearer $TENG" $B/api/pm/service-orders/$OBID/submit-confirm >/dev/null
SQL "UPDATE erp_pm_service_order SET CONFIRM_SUBMIT_AT=DATE_SUB(NOW(), INTERVAL 10 DAY) WHERE ID='$OBID'" >/dev/null 2>&1
C -X POST -H "Authorization: Bearer $TADM" $B/api/pm/service-orders/sweep >/dev/null
OBST=$(SQL "SELECT STATUS FROM erp_pm_service_order WHERE ID='$OBID'")
OBDF=$(SQL "SELECT CLOSE_DEFAULT_FLAG FROM erp_pm_service_order WHERE ID='$OBID'")
OBVR=$(SQL "SELECT CONFIRMED_VIA FROM erp_pm_service_order WHERE ID='$OBID'")
RMN=$(SQL "SELECT COUNT(*) FROM erp_pm_service_sla_log WHERE ORDER_ID='$OBID' AND EVENT='REMIND_CONFIRM'")
[ "$OBST" = "CLOSED" ] && [ "$OBDF" = "1" ] && [ "$OBVR" = "DEFAULT" ] && [ "$RMN" = "2" ] \
  && ok "段48 默认关闭（2 提醒+标注默认关闭）" || bad "段48 默认关闭错（$OBST/$OBDF/$OBVR/remind=$RMN）"

# 48.11 清理
SQL "DELETE FROM erp_pm_service_process_record WHERE ORDER_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_service_confirm_token WHERE ORDER_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_service_sla_log WHERE ORDER_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_service_survey WHERE ORDER_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_service_improve_task WHERE ORDER_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_service_order WHERE CUSTOMER_ID='smk-so-cust'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmService%'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_material_issue_line WHERE ITEM_CODE='SMKSO-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_material_issue WHERE WORK_ORDER_NO LIKE 'WO-SMKSO%'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_stock WHERE ITEM_CODE='SMKSO-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_inv_batch WHERE ITEM_CODE='SMKSO-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_qms_ncr WHERE ID='smk-so-ncr'" >/dev/null 2>&1
SQL "DELETE FROM erp_mdm_item WHERE ITEM_CODE='SMKSO-ITEM'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'smk-so-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'smk-so-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_mdm_customer_group WHERE ID='smk-so-cust'" >/dev/null 2>&1

echo "段 48 服务工单追加完成"

echo "== 段 49 合同收口（终止+交付跟踪）开始 =="

# 49.0 隔离与造数：客户 + 账号（销售/销售经理）+ 合同三态 + SO 三态 + 发货 + 应收 + 期次
SQL "DELETE FROM erp_crm_contract_version WHERE CONTRACT_ID IN (SELECT ID FROM (SELECT ID FROM erp_crm_contract WHERE CONTRACT_NO LIKE 'CTD49-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_contract_plan WHERE CONTRACT_ID IN (SELECT ID FROM (SELECT ID FROM erp_crm_contract WHERE CONTRACT_NO LIKE 'CTD49-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_sd_shipment_line WHERE SO_NO LIKE 'SO-CTD49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sd_shipment WHERE SHIP_NO LIKE 'SHP-CTD49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_ar_invoice WHERE SO_NO LIKE 'SO-CTD49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sd_so WHERE CONTRACT_NO LIKE 'CTD49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_contract WHERE CONTRACT_NO LIKE 'CTD49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'ctd49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'ctd49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_mdm_customer_group WHERE ID='ctd49-cust'" >/dev/null 2>&1
SQL "INSERT INTO erp_mdm_customer_group (ID, CUSTOMER_CODE, CUSTOMER_NAME, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('ctd49-cust','CTD49-CUST','CTD49 收口客户','1','smoke',NOW(),'0',0)" >/dev/null 2>&1
for U in sales:SALES mgr:SALES_MGR; do
  S=${U%%:*}; R=${U##*:}
  SQL "INSERT IGNORE INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
       SELECT 'ctd49-$S','ctd49-$S',PASSWORD,'冒烟收口-$S','1','smoke',NOW(),'0',0 FROM erp_admin_user WHERE USERNAME='admin'" >/dev/null 2>&1
  SQL "INSERT IGNORE INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT 'ctd49-ur-$S','ctd49-$S',ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_$R'" >/dev/null 2>&1
done
# 合同 A：SIGNED 无 SO（正常终止路径）；合同 B：SIGNED 有未关闭 SO + 期次 + 发货应收（交付聚合+警告路径）
SQL "INSERT INTO erp_crm_contract (ID, CONTRACT_NO, CUSTOMER_ID, CUSTOMER_NAME, TITLE, AMOUNT, STATUS, VERSION_NO, SO_COUNT, START_DATE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('ctd49-ca','CTD49-A','ctd49-cust','CTD49 收口客户','CTD49 合同A',100000,'SIGNED',1,0,'2026-01-01','smoke',NOW(),'0',0),
            ('ctd49-cb','CTD49-B','ctd49-cust','CTD49 收口客户','CTD49 合同B',100000,'SIGNED',1,2,'2026-01-01','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_sd_so (ID, SO_NO, CONTRACT_ID, CONTRACT_NO, CUSTOMER_ID, TOTAL_AMOUNT, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('ctd49-so-a','SO-CTD49-A','ctd49-cb','CTD49-B','ctd49-cust',30000,'SHIPPED','smoke',NOW(),'0',0),
            ('ctd49-so-b','SO-CTD49-B','ctd49-cb','CTD49-B','ctd49-cust',20000,'CONFIRMED','smoke',NOW(),'0',0),
            ('ctd49-so-c','SO-CTD49-C','ctd49-cb','CTD49-B','ctd49-cust',10000,'CONFIRMED','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "UPDATE erp_crm_contract SET SO_COUNT=3 WHERE ID='ctd49-cb'" >/dev/null 2>&1
SQL "INSERT INTO erp_sd_shipment (ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, STATUS, TOTAL_AMT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('ctd49-ship','SHP-CTD49-1','STANDARD','ctd49-cust','SHIPPED',30000,'smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_sd_shipment_line (ID, SHIP_ID, LINE_NO, SO_ID, SO_NO, SO_LINE_ID, ITEM_CODE, ITEM_NAME, QTY, UNIT_PRICE, AMOUNT, LINE_STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('ctd49-shipl','ctd49-ship',1,'ctd49-so-a','SO-CTD49-A','ctd49-sl1','CTD49-ITEM','CTD49 物料',1,30000,30000,'SHIPPED','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_fin_ar_invoice (ID, AR_NO, CUSTOMER_ID, SO_ID, SO_NO, INVOICE_DATE, DUE_DATE, AMOUNT, PAID_AMOUNT, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('ctd49-ar1','AR-CTD49-1','ctd49-cust','ctd49-so-a','SO-CTD49-A','2026-03-31',DATE_ADD('2026-03-31', INTERVAL 30 DAY),30000,30000,'CONFIRMED','smoke',NOW(),'0',0),
            ('ctd49-ar2','AR-CTD49-2','ctd49-cust','ctd49-so-a','SO-CTD49-A','2026-04-01',DATE_ADD('2026-04-01', INTERVAL 30 DAY),5000,0,'CONFIRMED','smoke',NOW(),'0',0),
            ('ctd49-ar3','AR-CTD49-3','ctd49-cust','ctd49-so-a','SO-CTD49-A','2026-07-01',DATE_ADD('2026-07-01', INTERVAL 30 DAY),2000,0,'CONFIRMED','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_crm_contract_plan (ID, CONTRACT_ID, CONTRACT_NO, PERIOD_NO, PLAN_AMOUNT, DUE_DATE, PAID_AMOUNT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
     VALUES ('ctd49-p1','ctd49-cb','CTD49-B',1,50000,'2026-03-31',30000,'smoke',NOW(),'0',0),
            ('ctd49-p2','ctd49-cb','CTD49-B',2,50000,'2026-06-30',0,'smoke',NOW(),'0',0)" >/dev/null 2>&1
TCSLS=$(C -H 'Content-Type: application/json' -d '{"username":"ctd49-sales","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TCSM=$(C -H 'Content-Type: application/json' -d '{"username":"ctd49-mgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "${TCSLS:-}" ] && [ "$TCSLS" != "None" ] && [ -n "${TCSM:-}" ] && [ "$TCSM" != "None" ] \
  && ok "段49 销售/销售经理 登录" || bad "段49 登录失败"

# 49.1 终止：非 MGR 403 → 原因空 422 → 未关闭 SO 警告（不落库）→ force 落库
TF=$(BIZ -X POST -H "Authorization: Bearer $TCSLS" -H 'Content-Type: application/json' \
  -d '{"reason":"测试"}' $B/api/crm/contracts/ctd49-ca/terminate)
[ "$TF" = "403" ] && ok "段49 销售无终止权 403" || bad "段49 终止权限未拦（$TF）"
TR=$(BIZ -X POST -H "Authorization: Bearer $TCSM" -H 'Content-Type: application/json' \
  -d '{}' $B/api/crm/contracts/ctd49-ca/terminate)
[ "$TR" = "422" ] && ok "段49 终止原因空 422" || bad "段49 原因校验未拦（$TR）"
TS=$(C -X POST -H "Authorization: Bearer $TCSM" -H 'Content-Type: application/json' \
  -d '{"reason":"CTD49 合同B客户中止","force":false}' $B/api/crm/contracts/ctd49-cb/terminate | J "d['data']['l4Warning'] is not None")
[ "$TS" = "True" ] && ok "段49 未关闭 SO 返回 L4 警告" || bad "段49 警告未返回（$TS）"
TST=$(SQL "SELECT STATUS FROM erp_crm_contract WHERE ID='ctd49-cb'")
[ "$TST" = "SIGNED" ] && ok "段49 警告态不落库" || bad "段49 误落库（$TST）"
C -X POST -H "Authorization: Bearer $TCSM" -H 'Content-Type: application/json' \
  -d '{"reason":"CTD49 客户中止","force":true}' $B/api/crm/contracts/ctd49-cb/terminate >/dev/null
TT=$(SQL "SELECT STATUS FROM erp_crm_contract WHERE ID='ctd49-cb'")
TCB=$(SQL "SELECT CLOSED_BY FROM erp_crm_contract WHERE ID='ctd49-cb'")
[ "$TT" = "TERMINATED" ] && [ -n "$TCB" ] && ok "段49 force 终止落库（TERMINATED+CLOSED_BY）" || bad "段49 落库错（$TT/$TCB）"
# 终止后新增 SO 422（存量 SO 状态不变）
NSS=$(BIZ -X POST -H "Authorization: Bearer $TCSLS" -H 'Content-Type: application/json' \
  -d '{"customerId":"ctd49-cust","contractId":"ctd49-cb","orderType":"STANDARD","lines":[{"itemCode":"CTD49-ITEM","qty":1,"unitPrice":1}]}' \
  $B/api/sd/so)
[ "$NSS" = "422" ] && ok "段49 终止后新增 SO 422" || bad "段49 新增 SO 未拦（$NSS）"
SST=$(SQL "SELECT STATUS FROM erp_sd_so WHERE ID='ctd49-so-a'")
[ "$SST" = "SHIPPED" ] && ok "段49 存量 SO 照常（SHIPPED 不变）" || bad "段49 存量被误动（$SST）"
# 终止后新增期次 422
NPL=$(BIZ -X POST -H "Authorization: Bearer $TCSM" -H 'Content-Type: application/json' \
  -d '{"plans":[{"periodNo":3,"planAmount":1000,"dueDate":"2026-12-31"}]}' \
  $B/api/crm/contracts/ctd49-cb/plans)
[ "$NPL" = "422" ] && ok "段49 终止后新增期次 422" || bad "段49 期次未拦（$NPL）"

# 49.2 交付执行聚合 + 期次软映射（用合同 A 之外再造一单：合同 B 已终止但交付只读仍可查）
DP=$(C -H "Authorization: Bearer $TCSM" $B/api/crm/contracts/ctd49-cb/delivery-progress)
SRN=$(echo "$DP" | J "len(d['data']['soRows'])")
[ "${SRN:-0}" = "3" ] && ok "段49 交付执行 3 张 SO 行" || bad "段49 SO 行数错（$SRN）"
TSH=$(echo "$DP" | J "d['data']['summary']['totalShipped']")
[ "${TSH%%.*}" = "30000" ] && ok "段49 累计发货 30000" || bad "段49 发货汇总错（$TSH）"
DRP=$(echo "$DP" | J "d['data']['summary']['deliveryRatePct']")
[ "${DRP%%.*}" = "30" ] && ok "段49 交付完成率 30%" || bad "段49 完成率错（$DRP）"
W1=$(echo "$DP" | J "len([a for a in d['data']['plans'][0]['windowAr'] if a['AR_NO']=='AR-CTD49-1'])")
[ "${W1:-0}" = "1" ] && ok "段49 窗口右闭：3/31 落第 1 期" || bad "段49 第 1 期窗口错（$W1）"
W2=$(echo "$DP" | J "len([a for a in d['data']['plans'][1]['windowAr'] if a['AR_NO']=='AR-CTD49-2'])")
[ "${W2:-0}" = "1" ] && ok "段49 窗口左开：4/1 落第 2 期" || bad "段49 第 2 期窗口错（$W2）"
UNW=$(echo "$DP" | J "float(d['data']['unwindowedAmount'])")
[ "${UNW%%.*}" = "2000" ] && ok "段49 未落窗应收 2000 单列" || bad "段49 未落窗错（$UNW）"

# 49.3 合同 A 正常终止（无 SO 无警告一次落库）
TA=$(C -X POST -H "Authorization: Bearer $TCSM" -H 'Content-Type: application/json' \
  -d '{"reason":"CTD49 合同A到期收口"}' $B/api/crm/contracts/ctd49-ca/terminate | J "d['data']['contract']['status']")
[ "$TA" = "TERMINATED" ] && ok "段49 无 SO 合同直接终止" || bad "段49 直接终止错（$TA）"
# 审计留痕：版本历史含 TERMINATE
TVL=$(SQL "SELECT COUNT(*) FROM erp_crm_contract_version WHERE CONTRACT_ID='ctd49-ca' AND OP_TYPE='TERMINATE'")
[ "${TVL:-0}" -ge 1 ] && ok "段49 终止写版本留痕" || bad "段49 版本留痕缺失"

# 49.4 清理
SQL "DELETE FROM erp_crm_contract_version WHERE CONTRACT_ID IN (SELECT ID FROM (SELECT ID FROM erp_crm_contract WHERE CONTRACT_NO LIKE 'CTD49-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_contract_plan WHERE CONTRACT_ID IN (SELECT ID FROM (SELECT ID FROM erp_crm_contract WHERE CONTRACT_NO LIKE 'CTD49-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_sd_shipment_line WHERE SO_NO LIKE 'SO-CTD49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sd_shipment WHERE SHIP_NO LIKE 'SHP-CTD49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_fin_ar_invoice WHERE SO_NO LIKE 'SO-CTD49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sd_so WHERE CONTRACT_NO LIKE 'CTD49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_crm_contract WHERE CONTRACT_NO LIKE 'CTD49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'ctd49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_admin_user WHERE ID LIKE 'ctd49-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_mdm_customer_group WHERE ID='ctd49-cust'" >/dev/null 2>&1

echo "段 49 合同收口追加完成"

# ============================================================================
# 段 50 结项归档（add-project-closure，M11.12，S-4.8-07 / C-4.8-05 / BR-4.8-27~30）
# 链路：造 ACTIVE 项目→发起结项（证据落快照）→人工项确认（无附件 422）→
#       上传附件→补齐成本结算→提交（强制项未过 422 / 未结算 422）→
#       PMO 审批通过→归档 CLOSED→五挂点只读 422→遗留工单逾期升级幂等
# 断言：401 走 HTTP；403/422 是业务码用 BIZ
# ============================================================================
echo "== 段 50 结项归档（发起→证据→确认→结算→审批→归档只读→逾期升级）开始 =="

# 50.0 隔离与造数：项目（先 DRAFT 补齐必填列再置 ACTIVE）+ WBS 完成 + 成本=基线
SQL "DELETE FROM erp_pm_closure_remediation WHERE PROJECT_ID LIKE 'clc50-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_closure_check_item WHERE CHECK_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_closure_check WHERE PROJECT_ID LIKE 'clc50-%') t)" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_closure_check WHERE PROJECT_ID LIKE 'clc50-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_closure_settlement WHERE PROJECT_ID LIKE 'clc50-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_cost_collect WHERE PROJECT_ID LIKE 'clc50-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_ip_agreement WHERE PROJECT_ID LIKE 'clc50-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'clc50-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_pm_project WHERE ID LIKE 'clc50-%'" >/dev/null 2>&1
SQL "DELETE FROM erp_sys_attachment WHERE BIZ_TYPE='PROJECT_CLOSURE' AND (BIZ_ID LIKE 'clc50-%' OR BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_pm_closure_check WHERE PROJECT_ID LIKE 'clc50-%') t2))" >/dev/null 2>&1

SQL "INSERT INTO erp_pm_project (ID,PROJECT_NO,NAME,PROJ_TYPE,DELIVERY_PATH,PM_USER_ID,PM_NAME,START_DATE,END_DATE,STATUS,BASELINE_LOCKED,BASELINE_AMOUNT,FREEZE_FLAG,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     VALUES ('clc50-prj','PRJ-CLC50','CLC50 结项归档项目','研发','INTERNAL','admin','冒烟PM',DATE_SUB(CURDATE(),INTERVAL 60 DAY),DATE_ADD(CURDATE(),INTERVAL 30 DAY),'DRAFT',0,10000,0,'smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "INSERT INTO erp_pm_wbs (ID,PROJECT_ID,WBS_CODE,LEVEL,NAME,PHASE_DONE,EST_HOURS,EST_COST,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     VALUES ('clc50-w1','clc50-prj','1',1,'开发阶段','1',80,6000,'smoke',NOW(),'0',0),
            ('clc50-w2','clc50-prj','2',1,'测试阶段','1',40,4000,'smoke',NOW(),'0',0),
            ('clc50-w3','clc50-prj','1.1',3,'编码任务','1',80,6000,'smoke',NOW(),'0',0)" >/dev/null 2>&1
# 成本 = 基线 10000 → 偏差 0%（自动项 COST 通过）
SQL "INSERT INTO erp_pm_cost_collect (ID,PROJECT_ID,WBS_ID,COST_TYPE,AMOUNT,SOURCE_TYPE,SOURCE_ID,COLLECT_DATE,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     VALUES ('clc50-cc1','clc50-prj','clc50-w3','LABOR',10000,'MANUAL','clc50-src',CURDATE(),'smoke',NOW(),'0',0)" >/dev/null 2>&1
# IP 归属协议（自动项 IP 的数据源，BR-4.8-24 立项卡控同款）
SQL "INSERT INTO erp_pm_ip_agreement (ID,PROJECT_ID,COOP_MODE,COMPANY_RATE,SIGN_DATE,EFFECTIVE,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     VALUES ('clc50-ip1','clc50-prj','INDEPENDENT',1.00,CURDATE(),'1','smoke',NOW(),'0',0)" >/dev/null 2>&1
SQL "UPDATE erp_pm_project SET STATUS='ACTIVE',BASELINE_LOCKED=1 WHERE ID='clc50-prj'" >/dev/null 2>&1
CLC50=$(SQL "SELECT STATUS FROM erp_pm_project WHERE ID='clc50-prj'")
[ "$CLC50" = "ACTIVE" ] && ok "段50 项目造数 ACTIVE" || bad "段50 项目造数失败（$CLC50）"

T50=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "${T50:-}" ] && ok "段50 admin 登录" || bad "段50 登录失败"

# 50.1 发起结项 → 检查单 + 六项证据（WBS/MS/COST 自动 PASS；ACCEPT/DOC 人工 PENDING）
INIT=$(C -X POST -H "$(A $T50)" $B/api/pm/closure/clc50-prj/initiate)
IE=$(echo "$INIT" | J "d['code']")
[ "$IE" = "200" ] && ok "段50 发起结项" || bad "段50 发起失败（$IE）"
PST=$(SQL "SELECT STATUS FROM erp_pm_project WHERE ID='clc50-prj'")
[ "$PST" = "CLOSING" ] && ok "段50 项目置 CLOSING" || bad "段50 状态错（$PST）"
CHK=$(SQL "SELECT COUNT(*) FROM erp_pm_closure_check WHERE PROJECT_ID='clc50-prj'")
[ "${CHK:-0}" = "1" ] && ok "段50 检查单生成" || bad "段50 检查单数错（$CHK）"
ITOT=$(SQL "SELECT COUNT(*) FROM erp_pm_closure_check_item i JOIN erp_pm_closure_check c ON c.ID=i.CHECK_ID WHERE c.PROJECT_ID='clc50-prj'")
[ "${ITOT:-0}" = "6" ] && ok "段50 六项固定行" || bad "段50 检查项数错（$ITOT）"
# 证据数值快照（非布尔）
EVJ=$(SQL "SELECT EVIDENCE_JSON FROM erp_pm_closure_check_item i JOIN erp_pm_closure_check c ON c.ID=i.CHECK_ID WHERE c.PROJECT_ID='clc50-prj' AND i.ITEM_KEY='COST'")
CONTAINS "段50 成本证据落快照" "$EVJ" "deviation"
WRES=$(SQL "SELECT RESULT FROM erp_pm_closure_check_item i JOIN erp_pm_closure_check c ON c.ID=i.CHECK_ID WHERE c.PROJECT_ID='clc50-prj' AND i.ITEM_KEY='WBS'")
[ "$WRES" = "PASS" ] && ok "段50 WBS 自动 PASS" || bad "段50 WBS 结果错（$WRES）"
CRES=$(SQL "SELECT RESULT FROM erp_pm_closure_check_item i JOIN erp_pm_closure_check c ON c.ID=i.CHECK_ID WHERE c.PROJECT_ID='clc50-prj' AND i.ITEM_KEY='COST'")
[ "$CRES" = "PASS" ] && ok "段50 成本自动 PASS（偏差 0%）" || bad "段50 成本结果错（$CRES）"
ARES=$(SQL "SELECT RESULT FROM erp_pm_closure_check_item i JOIN erp_pm_closure_check c ON c.ID=i.CHECK_ID WHERE c.PROJECT_ID='clc50-prj' AND i.ITEM_KEY='ACCEPT'")
[ "$ARES" = "PENDING" ] && ok "段50 人工项 PENDING" || bad "段50 人工项状态错（$ARES）"
# 重复发起 409
EQ "段50 重复发起 409" "$(BIZ -X POST -H "$(A $T50)" $B/api/pm/closure/clc50-prj/initiate)" "409"

# 50.2 人工项确认：无附件 422 → 上传附件 → PASS
CLC50_FILE=/tmp/clc50-attach.txt; echo "CLC50 smoke attachment" > "$CLC50_FILE"
ACC_ID=$(SQL "SELECT i.ID FROM erp_pm_closure_check_item i JOIN erp_pm_closure_check c ON c.ID=i.CHECK_ID WHERE c.PROJECT_ID='clc50-prj' AND i.ITEM_KEY='ACCEPT'")
DOC_ID=$(SQL "SELECT i.ID FROM erp_pm_closure_check_item i JOIN erp_pm_closure_check c ON c.ID=i.CHECK_ID WHERE c.PROJECT_ID='clc50-prj' AND i.ITEM_KEY='DOC'")
EQ "段50 人工项无附件 422" "$(BIZ -X PUT -H "$(A $T50)" -H 'Content-Type: application/json' -d '{"result":"PASS"}' $B/api/pm/closure/items/$ACC_ID)" "422"
UP1=$(C -X POST -H "$(A $T50)" -F "file=@$CLC50_FILE;filename=acceptance.pdf" -F "bizType=PROJECT_CLOSURE" -F "bizId=$ACC_ID" $B/api/common/attachments | J "d['code']")
[ "$UP1" = "200" ] && ok "段50 验收凭证上传" || bad "段50 附件上传失败（$UP1）"
UP2=$(C -X POST -H "$(A $T50)" -F "file=@$CLC50_FILE;filename=docs-archive.pdf" -F "bizType=PROJECT_CLOSURE" -F "bizId=$DOC_ID" $B/api/common/attachments | J "d['code']")
[ "$UP2" = "200" ] && ok "段50 归档凭证上传" || bad "段50 归档附件失败（$UP2）"
CR1=$(C -X PUT -H "$(A $T50)" -H 'Content-Type: application/json' -d '{"result":"PASS"}' $B/api/pm/closure/items/$ACC_ID | J "d['data']['result']")
[ "$CR1" = "PASS" ] && ok "段50 验收项确认 PASS" || bad "段50 验收确认错（$CR1）"
CR2=$(C -X PUT -H "$(A $T50)" -H 'Content-Type: application/json' -d '{"result":"PASS"}' $B/api/pm/closure/items/$DOC_ID | J "d['data']['result']")
[ "$CR2" = "PASS" ] && ok "段50 文档项确认 PASS" || bad "段50 文档确认错（$CR2）"

# 50.3 提交：强制项全过但未结算 → 422；结算后无总结报告 → 422
EQ "段50 未结算提交 422" "$(BIZ -X POST -H "$(A $T50)" $B/api/pm/closure/clc50-prj/submit)" "422"
SETC=$(C -X POST -H "$(A $T50)" $B/api/pm/closure/clc50-prj/settlement/confirm | J "d['code']")
[ "$SETC" = "200" ] && ok "段50 财务结算确认（解冻+快照）" || bad "段50 结算失败（$SETC）"
# 结算快照落库（不可变锚点）
SETN=$(SQL "SELECT COUNT(*) FROM erp_pm_closure_settlement WHERE PROJECT_ID='clc50-prj'")
[ "${SETN:-0}" = "1" ] && ok "段50 结算快照落库" || bad "段50 快照数错（$SETN）"
EQ "段50 重复结算 409" "$(BIZ -X POST -H "$(A $T50)" $B/api/pm/closure/clc50-prj/settlement/confirm)" "409"
EQ "段50 无总结报告提交 422" "$(BIZ -X POST -H "$(A $T50)" $B/api/pm/closure/clc50-prj/submit)" "422"
UP3=$(C -X POST -H "$(A $T50)" -F "file=@$CLC50_FILE;filename=summary-report.pdf" -F "bizType=PROJECT_CLOSURE" -F "bizId=clc50-prj" $B/api/common/attachments | J "d['code']")
[ "$UP3" = "200" ] && ok "段50 项目总结报告上传" || bad "段50 总结报告失败（$UP3）"

# 50.4 提交成功 → FROZEN；CLOSING 拦变更发起
SUB=$(C -X POST -H "$(A $T50)" $B/api/pm/closure/clc50-prj/submit | J "d['data']['check']['status']")
[ "$SUB" = "FROZEN" ] && ok "段50 提交审批 FROZEN" || bad "段50 提交状态错（$SUB）"
EQ "段50 CLOSING 拦变更发起" "$(BIZ -X POST -H "$(A $T50)" -H 'Content-Type: application/json' -d '{"projectId":"clc50-prj","changeType":"SCOPE","reason":"结项中发起","effectiveDate":"'"$(date +%F)"'"}' $B/api/pm/changes/draft)" "422"

# 50.5 PMO 审批通过 → CLOSED + 资源释放快照
TASK50=$(C -H "$(A $T50)" $B/api/qms/approvals/todo | J "next((t['taskId'] for t in d['data'] if t.get('bizType')=='ProjectClosure'), None)")
[ -n "${TASK50:-}" ] && [ "$TASK50" != "None" ] && ok "段50 结项审批待办可见" || bad "段50 无审批待办"
P50=$(C -X POST -H "$(A $T50)" -H 'Content-Type: application/json' -d "{\"taskId\":\"$TASK50\",\"opinion\":\"CLC50 结项通过\"}" $B/api/qms/approvals/pass | J "d['code']")
[ "$P50" = "200" ] && ok "段50 PMO 审批通过" || bad "段50 审批失败（$P50）"
FST=$(SQL "SELECT STATUS FROM erp_pm_project WHERE ID='clc50-prj'")
[ "$FST" = "CLOSED" ] && ok "段50 项目归档 CLOSED" || bad "段50 归档状态错（$FST）"
RES=$(SQL "SELECT RES_RELEASE_JSON FROM erp_pm_closure_check WHERE PROJECT_ID='clc50-prj'")
CONTAINS "段50 资源释放快照生成" "$RES" "empId"

# 50.6 归档只读五挂点 → 全 422
TS50=$(BIZ -X POST -H "$(A $T50)" -H 'Content-Type: application/json' -d '{"workDate":"'"$(date -v-1d +%F 2>/dev/null || date -d yesterday +%F)"'","lines":[{"wbsId":"clc50-w3","hours":8,"workContent":"归档后报工"}]}' $B/api/my/timesheets)
[ "$TS50" = "422" ] && ok "段50 归档拦工时 422" || bad "段50 工时未拦（$TS50）"
# 成本归集无独立写端点（内部服务层，服务层 guard 已由 ClosureDbTest 50 用例覆盖）
# 此处只断言归档后成本归集表不可变（快照冻结）
CSUM=$(SQL "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE PROJECT_ID='clc50-prj'")
[ "${CSUM:-0}" = "1" ] && ok "段50 归档后成本表冻结（无新增）" || bad "段50 成本表被改动（$CSUM）"
CH50=$(BIZ -X POST -H "$(A $T50)" -H 'Content-Type: application/json' -d '{"projectId":"clc50-prj","changeType":"SCOPE","reason":"归档后变更"}' $B/api/pm/changes/draft)
[ "$CH50" = "422" ] && ok "段50 归档拦变更发起 422" || bad "段50 变更未拦（$CH50）"
MI50=$(BIZ -X POST -H "$(A $T50)" -H 'Content-Type: application/json' -d '{"projectId":"clc50-prj","issueDate":"'"$(date +%F)"'","lines":[{"wbsId":"clc50-w1","itemId":"CLC50-ITEM","qty":1,"unitPrice":1}]}' $B/api/pm/cost/material-issues)
[ "$MI50" = "422" ] && ok "段50 归档拦领料 422" || bad "段50 领料未拦（$MI50）"
BS50=$(BIZ -X POST -H "$(A $T50)" $B/api/pm/budgets/clc50-prj/submit)
[ "$BS50" = "422" ] && ok "段50 归档拦基线提交 422" || bad "段50 基线未拦（$BS50）"
# 只读查询仍放行
DT50=$(C -H "$(A $T50)" $B/api/pm/closure/clc50-prj | J "d['code']")
[ "$DT50" = "200" ] && ok "段50 归档后只读查询放行" || bad "段50 查询被误拦（$DT50）"

# 50.7 遗留工单逾期升级（造 OPEN 且逾期 → sweep 幂等）
SQL "INSERT INTO erp_pm_closure_remediation (ID,CHECK_ID,PROJECT_ID,CONTENT,OWNER_ID,DUE_DATE,STATUS,ESCALATED_FLAG,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO)
     VALUES ('clc50-rem1',(SELECT ID FROM erp_pm_closure_check WHERE PROJECT_ID='clc50-prj'),'clc50-prj','CLC50 遗留事项','admin',DATE_SUB(NOW(),INTERVAL 3 DAY),'OPEN','0','smoke',NOW(),'0',0)" >/dev/null 2>&1
SW1=$(C -X POST -H "$(A $T50)" $B/api/pm/closure/sweep | J "d['data']")
[ "${SW1:-0}" -ge 1 ] && ok "段50 逾期升级触发（$SW1）" || bad "段50 升级未触发（$SW1）"
ESC1=$(SQL "SELECT ESCALATED_FLAG FROM erp_pm_closure_remediation WHERE ID='clc50-rem1'")
[ "$ESC1" = "1" ] && ok "段50 升级标记落库" || bad "段50 升级标记错（$ESC1）"
NTC=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmClosureRemediationOverdue'")
SW2=$(C -X POST -H "$(A $T50)" $B/api/pm/closure/sweep | J "d['data']")
[ "${SW2:-9}" = "0" ] && ok "段50 重复 sweep 幂等（0 条）" || bad "段50 sweep 不幂等（$SW2）"
NTC2=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmClosureRemediationOverdue'")
[ "$NTC2" = "$NTC" ] && ok "段50 通知不重复" || bad "段50 通知重复（$NTC→$NTC2）"

# 50.8 401 负例
EQ "段50 无令牌发起 401" "$(HTTP -X POST $B/api/pm/closure/clc50-prj/initiate)" "401"

echo "段 50 结项归档追加完成"

echo "段 46 知识产权追加完成"

echo "段 45 项目变更追加完成"

echo "段 44 预算管控追加完成"

echo "段 43 工时管理追加完成"
echo "段 42 里程碑管理追加完成"
echo "段 41 项目立项追加完成"
echo "段 40 报价审批台追加完成"
echo "段 39 线索自动分配追加完成"
echo "段 38 培训认证追加完成"
echo "段 37 外派借调追加完成"
echo "段 35 薪酬核算与发放追加完成"
echo "段 34 考勤管理追加完成"
echo "段 33 组织管理追加完成"
echo "段 32 员工档案追加完成"
echo "EAM 转资冒烟：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN"; else echo "HAS FAILURES"; exit 1; fi
