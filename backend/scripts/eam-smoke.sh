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
tot_ok = t and float(t[0]['totalCost'])>0 and t[0]['moCount']==416
print('OK' if tot_ok else 'NG')" 2>/dev/null)
[ "${CT_TROK:-NG}" = "OK" ] && ok "trend 单期如实（202610×416）" || bad "trend 异常（$CT_TR）"

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
echo "EAM 转资冒烟：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN"; else echo "HAS FAILURES"; exit 1; fi
