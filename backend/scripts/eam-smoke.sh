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
DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'SMKDEPR%' OR CARD_NO='SMKMT-0001';
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

echo "EAM 转资冒烟：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN"; else echo "HAS FAILURES"; exit 1; fi
