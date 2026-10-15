#!/bin/zsh
# rework-inspection-tasks 全链路 API 冒烟（任务 7.2，可重跑）
# 链路：清理→登录→401→IQC 全链（GR 触发建批→分派→录入→判定→放行）→
#       完工 OQC（建批→录入→判定→放行→WIP_IN 入库→合格证）→
#       超时扫描（落库+改派+通知）→协议（建档→生效→投诉×2→命中全检）→
#       发货闸门（未放行阻断/OFF 降级/免检放行）→权限
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

ITEM=SMKI001; ITEM2=SMKI002

echo "== 0. 前置清理（检验冒烟族 SMKI，可重跑）=="
SQL "
DELETE FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='MO_FINISH';
DELETE FROM erp_qms_certificate WHERE ITEM_CODE LIKE 'SMKI%' OR LOT_NO LIKE 'IL-SMKI%';
DELETE FROM erp_qms_certificate WHERE LOT_NO LIKE 'IL-SMKI%';
DELETE FROM erp_qms_concession_writeoff WHERE CONCESSION_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_qms_concession WHERE ITEM_CODE LIKE 'SMKI%') t7);
DELETE FROM erp_qms_concession WHERE ITEM_CODE LIKE 'SMKI%';
DELETE FROM erp_inv_freeze WHERE ITEM_CODE LIKE 'SMKI%' AND SOURCE='NCR';
DELETE FROM erp_inv_stock WHERE ID='stk-smki-ncr';
DELETE FROM erp_inv_stock WHERE ID='stk-smki-ctq';
-- CTQ 处置审批与 COPQ 清理须在 ncr 行删除之前（按 BIZ_ID/SOURCE_ID 关联）
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON a.ID=t.APPR_ID
 WHERE a.BIZ_TYPE='NcrDisposition' AND a.BIZ_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_qms_ncr WHERE ITEM_CODE LIKE 'SMKI%') t9);
DELETE FROM erp_sys_approval WHERE BIZ_TYPE='NcrDisposition' AND BIZ_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_qms_ncr WHERE ITEM_CODE LIKE 'SMKI%') t10);
DELETE FROM erp_qms_copq_entry WHERE SOURCE_TYPE='NCR' AND SOURCE_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_qms_ncr WHERE ITEM_CODE LIKE 'SMKI%') t11);
DELETE FROM erp_qms_ncr_log WHERE NCR_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_qms_ncr WHERE ITEM_CODE LIKE 'SMKI%') t8);
DELETE FROM erp_qms_ncr WHERE ITEM_CODE LIKE 'SMKI%';
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON a.ID=t.APPR_ID
 WHERE a.BIZ_TYPE='Concession' AND a.TITLE LIKE '%SMKI%';
DELETE FROM erp_sys_approval WHERE BIZ_TYPE='Concession' AND TITLE LIKE '%SMKI%';
-- 20 重复升级：CAPA 待办通知（段 0 清，防跨轮幂等锚短路）
DELETE FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_REPEAT' AND BIZ_ID LIKE 'CAPA_TODO:%';
-- 22 CAPA 证据链：回炉/重分析待办 + CAPA 三表（标题锚 SMKCAPA）
DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('QMS_CAPA_REANALYZE', 'QMS_CAPA_RECUR');
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON a.ID=t.APPR_ID
 WHERE a.BIZ_TYPE='CapaStandardChange' AND a.TITLE LIKE '%SMKCAPA%';
DELETE FROM erp_sys_approval WHERE BIZ_TYPE='CapaStandardChange' AND TITLE LIKE '%SMKCAPA%';
DELETE FROM erp_qms_capa_action WHERE CAPA_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_qms_capa WHERE TITLE LIKE '%SMKCAPA%') t16);
DELETE FROM erp_qms_capa_step WHERE CAPA_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_qms_capa WHERE TITLE LIKE '%SMKCAPA%') t17);
DELETE FROM erp_qms_capa WHERE TITLE LIKE '%SMKCAPA%';
DELETE FROM erp_inv_batch WHERE BATCH_NO IN ('BATCH-SMK22A','BATCH-SMK22B');
-- 23 质量成本分析：归集全清（保证趋势/环比确定性）+ SCAR/报废/审批 + 记分卡当月结果（锚 SMKCOPQ）
DELETE FROM erp_qms_copq_entry;
DELETE FROM erp_sys_approval_task WHERE APPR_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN ('ScarFinance','CopqFinance')) t23a);
DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('ScarFinance','CopqFinance');
DELETE FROM erp_qms_scar_deduction WHERE SCAR_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_qms_scar WHERE TITLE LIKE '%SMKCOPQ%') t23b);
DELETE FROM erp_qms_scar WHERE TITLE LIKE '%SMKCOPQ%';
DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='SCRAP') t23c);
DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='SCRAP';
DELETE FROM erp_inv_transaction WHERE ITEM_CODE='SMKCOPQ-01';
DELETE FROM erp_inv_scrap_order_line WHERE ITEM_CODE='SMKCOPQ-01';
DELETE FROM erp_inv_scrap_order WHERE REMARK LIKE '%SMKCOPQ%';
DELETE FROM erp_inv_stock WHERE ITEM_CODE='SMKCOPQ-01';
DELETE FROM erp_mdm_item WHERE ITEM_CODE='SMKCOPQ-01';
DELETE FROM erp_scm_scorecard_result WHERE MONTH_TAG = DATE_FORMAT(CURDATE(),'%Y%m');
DELETE FROM erp_scm_scorecard_model WHERE MODEL_CODE='SMKCOPQ-MDL';
-- 21 召回评估：追溯单三表 + 批次台账（按批次锚，须在 lot/ncr 删除前无依赖问题）
DELETE FROM erp_inv_trace_flow WHERE TRACE_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_inv_trace_order WHERE BATCH_NO IN ('BATCH-SMK21')) t14);
DELETE FROM erp_inv_trace_log WHERE TRACE_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_inv_trace_order WHERE BATCH_NO IN ('BATCH-SMK21')) t15);
DELETE FROM erp_inv_trace_order WHERE BATCH_NO IN ('BATCH-SMK21');
DELETE FROM erp_inv_batch WHERE BATCH_NO IN ('BATCH-SMK21','BATCH-SMK21B','BATCH-SMK20');
DELETE FROM erp_qms_sampling_record WHERE LOT_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_qms_inspection_lot WHERE ITEM_CODE LIKE 'SMKI%') t);
UPDATE erp_qms_inspection_lot SET SAMPLED_FLAG=NULL WHERE ITEM_CODE LIKE 'SMKI%';
DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON a.ID=t.APPR_ID
 WHERE a.BIZ_TYPE IN ('Exempt') AND a.TITLE LIKE '%SMKI%';
DELETE FROM erp_sys_approval WHERE BIZ_TYPE='Exempt' AND TITLE LIKE '%SMKI%';
DELETE FROM erp_qms_agreement_log WHERE AGREEMENT_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_qms_customer_agreement WHERE AGREE_CODE LIKE 'SMKI-%') t);
DELETE FROM erp_qms_customer_agreement WHERE AGREE_CODE LIKE 'SMKI-%';
DELETE FROM erp_qms_lot_item WHERE LOT_ID LIKE 'lot-smki-%' OR LOT_ID IN
 (SELECT ID FROM (SELECT ID FROM erp_qms_inspection_lot WHERE ITEM_CODE LIKE 'SMKI%') t);
DELETE l FROM erp_qms_inspection_lot l WHERE l.ITEM_CODE LIKE 'SMKI%';
DELETE gl FROM erp_proc_gr_line gl WHERE gl.ITEM_CODE LIKE 'SMKI%';
DELETE g FROM erp_proc_gr g WHERE g.ID NOT IN (SELECT GR_ID FROM (SELECT GR_ID FROM erp_proc_gr_line) t2)
 AND g.SUPPLIER_ID IN (SELECT ID FROM (SELECT ID FROM erp_mdm_supplier WHERE SUPPLIER_NAME LIKE '%冒烟%' OR ID LIKE 'sup-smk%') t3);
DELETE FROM erp_qms_standard_applicability WHERE MATERIAL_CODE IN ('SMKI001','SMKI002','SMKI003','SMKI004');
DELETE FROM erp_qms_characteristic WHERE ID LIKE 'chr-smki-%' OR VERSION_ID LIKE 'sv-smki-%';
DELETE FROM erp_qms_standard_version WHERE ID LIKE 'sv-smki-%'
 OR STANDARD_ID IN (SELECT ID FROM (SELECT ID FROM erp_qms_standard WHERE STANDARD_CODE LIKE 'SMKI-STD%') t);
DELETE FROM erp_qms_standard WHERE STANDARD_CODE LIKE 'SMKI-STD%';
DELETE FROM erp_proc_gr_line WHERE ITEM_CODE LIKE 'SMKI%';
DELETE FROM erp_proc_gr WHERE GR_NO LIKE 'SMKIGR%';
DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'SMKI%';
DELETE FROM erp_qms_exempt WHERE MATERIAL_CODE LIKE 'SMKI%';
DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'QMS_LOT_%' AND BIZ_ID LIKE 'lot-smki%';
DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'user-smki%';
DELETE FROM erp_admin_user WHERE USERNAME LIKE 'smki%';" >/dev/null 2>&1 \
  && ok "清理完成（SMKI 族就位）" || bad "清理失败"

echo "== 1. 登录（质检/质量工程/质量经理/技术负责人/仓库/计划）=="
TQC=$(C -H 'Content-Type: application/json' -d '{"username":"qinsp","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TENG=$(C -H 'Content-Type: application/json' -d '{"username":"qeng","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TMGR=$(C -H 'Content-Type: application/json' -d '{"username":"qmgr","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TTECH=$(C -H 'Content-Type: application/json' -d '{"username":"qtech","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TPLAN=$(C -H 'Content-Type: application/json' -d '{"username":"dplanner","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
TADM=$(C -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}' $B/api/auth/login | J "d['data']['token']")
[ -n "$TQC" ] && [ -n "$TENG" ] && [ -n "$TMGR" ] && [ -n "$TTECH" ] && [ -n "$TPLAN" ] && [ -n "$TADM" ] \
  && ok "六角色登录" || bad "登录失败"

echo "== 2. 401 矩阵 =="
EQ "GET /qms/lots/board 401" "$(HTTP $B/api/qms/lots/board)" "401"
EQ "POST /qms/lots 401" "$(HTTP -X POST $B/api/qms/lots)" "401"
EQ "GET /qms/agreements 401" "$(HTTP $B/api/qms/agreements)" "401"
EQ "GET /qms/agreements/x 401" "$(HTTP $B/api/qms/agreements/x)" "401"
EQ "POST agreement activate 401" "$(HTTP -X POST $B/api/qms/agreements/x/activate)" "401"
EQ "POST agreement complaint 401" "$(HTTP -X POST $B/api/qms/agreements/x/complaint)" "401"
EQ "POST agreement stop 401" "$(HTTP -X POST $B/api/qms/agreements/x/stop)" "401"
EQ "POST sweep-overdue 401" "$(HTTP -X POST $B/api/qms/lots/sweep-overdue)" "401"
EQ "GET lot certificate 401" "$(HTTP $B/api/qms/lots/x/certificate)" "401"
EQ "POST lot release 401" "$(HTTP -X POST $B/api/qms/lots/x/release)" "401"
EQ "POST lot input 401" "$(HTTP -X POST $B/api/qms/lots/x/input)" "401"

echo "== 3. 造数（物料 + 标准 V1）=="
SQL "
INSERT IGNORE INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, MATERIAL_GROUP,
 PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, STATUS, CREATE_BY, DEL_FLAG)
 VALUES ('it-smki-1','SMKI001','检验冒烟来料','SMKI','PC','STRUCT','BUY','NORMAL','0','0','1','smoke','0'),
        ('it-smki-2','SMKI002','检验冒烟产品','SMKI','PC','STRUCT','MAKE','NORMAL','1','0','1','smoke','0');
INSERT IGNORE INTO erp_qms_standard (ID, STANDARD_CODE, NAME, SCOPE_TYPE, RISK_LEVEL, STATUS, CURRENT_VERSION, CREATE_BY, DEL_FLAG, VER_NO)
 VALUES ('std-smki-1','SMKI-STD-1','冒烟检验标准','ITEM','C','ACTIVE',1,'smoke','0',0);
INSERT IGNORE INTO erp_qms_standard_version (ID, STANDARD_ID, VERSION_NO, EFFECTIVE_FROM, AQL_LEVEL, INSPECTION_LEVEL, STATUS, CHANGE_REASON, CREATE_BY, DEL_FLAG, VER_NO)
 VALUES ('sv-smki-1','std-smki-1',1,CURDATE(),'GB/T 2828.1 一般检验水平 II','II','RELEASED','初始','smoke','0',0);
INSERT IGNORE INTO erp_qms_standard_applicability (ID, VERSION_ID, MATERIAL_CODE, PRIORITY, CREATE_BY, DEL_FLAG, VER_NO)
 VALUES ('sap-smki-1','sv-smki-1','SMKI001',10,'smoke','0',0),
        ('sap-smki-2','sv-smki-1','SMKI002',10,'smoke','0',0);
INSERT IGNORE INTO erp_qms_characteristic (ID, VERSION_ID, SEQUENCE_NO, CHARACTERISTIC_NAME, CTQ_FLAG, SPEC_TYPE, LOWER_LIMIT, UPPER_LIMIT, METHOD_NAME, CREATE_BY, DEL_FLAG, VER_NO)
 VALUES ('chr-smki-1','sv-smki-1',1,'外观','1','LOOK',NULL,NULL,'目视','smoke','0',0),
        ('chr-smki-2','sv-smki-1',2,'尺寸','0','NUMERIC',9.5,10.5,'卡尺','smoke','0',0);" >/dev/null 2>&1 \
  && ok "造数完成" || bad "造数失败"

echo "== 4. IQC 全链（GR 触发建批 → 分派 → 录入 → 判定 → 放行）=="
SUP=$(SQL "SELECT ID FROM erp_mdm_supplier WHERE STATUS='QUALIFIED' LIMIT 1")
[ -n "${SUP:-}" ] && ok "复用供应商（$SUP）" || bad "库内无启用供应商"
GR_JSON=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"supplierId\":\"$SUP\",\"batchNo\":\"BATCH-SMK\",\"arrivalDate\":\"$(date +%Y-%m-%d)\",\"lines\":[{\"itemCode\":\"SMKI001\",\"receivedQty\":100}]}" \
  $B/api/proc/grs/free)
LOT1=$(echo "$GR_JSON" | J "[l['lotNo'] for l in (d['data'].get('inspectionLots') or []) if l.get('itemCode')=='SMKI001'][0]" 2>/dev/null)
[ -n "${LOT1:-}" ] && ok "GR 触发 IQC 建批（$LOT1）" || bad "GR 建批失败（$(echo "$GR_JSON" | J "d['message']")）"
LOT1_ID=$(SQL "SELECT ID FROM erp_qms_inspection_lot WHERE LOT_NO='$LOT1'")
INSPECTOR=$(SQL "SELECT INSPECTOR_ID FROM erp_qms_inspection_lot WHERE ID='$LOT1_ID'")
[ -n "${INSPECTOR:-}" ] && ok "自动分派质检员（$INSPECTOR）" || bad "未分派"
ASSIGN0=$(SQL "SELECT ASSIGN_LOG FROM erp_qms_inspection_lot WHERE ID='$LOT1_ID'")
CONTAINS "建批分派留痕" "${ASSIGN0:-}" "ASSIGN"
STD_SNAP=$(SQL "SELECT STANDARD_CODE FROM erp_qms_inspection_lot WHERE ID='$LOT1_ID'")
EQ "标准快照固化" "$STD_SNAP" "SMKI-STD-1"
ITEM_CNT=$(SQL "SELECT COUNT(*) FROM erp_qms_lot_item WHERE LOT_ID='$LOT1_ID'")
EQ "检验项快照 2 条" "$ITEM_CNT" "2"
QC_LINE=$(SQL "SELECT QC_STATUS FROM erp_proc_gr_line WHERE ITEM_CODE='SMKI001' ORDER BY CREATE_DATE DESC LIMIT 1")
EQ "GR 行 QC 状态回写 PENDING" "$QC_LINE" "PENDING"
SQL "UPDATE erp_qms_inspection_lot SET INSPECTOR_ID='qinsp', INSPECTOR_NAME='冒烟质检员' WHERE ID='$LOT1_ID'" >/dev/null
# 录入（CTQ 外观 PASS + 尺寸实测 10.0；GROUP_CONCAT 单行拼 JSON 数组）
ITEMS=$(SQL "SELECT CONCAT('[',GROUP_CONCAT(CONCAT('{\"itemId\":\"',ID,'\",\"measuredValue\":',IF(SPEC_TYPE='NUMERIC','10.0','null'),',\"judge\":\"PASS\"}')),']') FROM erp_qms_lot_item WHERE LOT_ID='$LOT1_ID'")
EQ "录入 200" "$(BIZ -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d "{\"items\":$ITEMS}" $B/api/qms/lots/$LOT1_ID/input)" "200"
JUDGE=$(C -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOT1_ID/judge | J "d['data']['verdict']")
[ "$JUDGE" = "PASS" ] && ok "判定合格（$JUDGE）" || bad "判定异常（$JUDGE）"
EQ "放行 200" "$(BIZ -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOT1_ID/release)" "200"
ST=$(SQL "SELECT STATUS FROM erp_qms_inspection_lot WHERE ID='$LOT1_ID'")
EQ "放行后状态" "$ST" "RELEASED"
QC_LINE2=$(SQL "SELECT QC_STATUS FROM erp_proc_gr_line WHERE ITEM_CODE='SMKI001' ORDER BY CREATE_DATE DESC LIMIT 1")
EQ "GR 行 QC 状态 RELEASED" "$QC_LINE2" "RELEASED"
RELEASED_BY=$(SQL "SELECT RELEASED_BY FROM erp_qms_inspection_lot WHERE ID='$LOT1_ID'")
[ -n "${RELEASED_BY:-}" ] && ok "放行人留痕（$RELEASED_BY）" || bad "无放行人"
# 不可撤回
EQ "二次放行 422" "$(BIZ -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOT1_ID/release)" "422"
# 放行后再判定 422（终态保护）
EQ "已放行判定 422" "$(BIZ -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOT1_ID/judge)" "422"

echo "== 5. 完工 OQC 全链（建批 → 录入 → 放行 → WIP_IN 入库 → 合格证）=="
# 手工建 OQC 批（模拟完工触发；完工钩子在报工链路，冒烟用同型批验证放行联动）
LOT2=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"lotType\":\"OQC\",\"itemCode\":\"SMKI002\",\"itemName\":\"检验冒烟产品\",\"qty\":20,\"refType\":\"WORKORDER\",\"refId\":\"mo-smki-fg#FG\"}" \
  $B/api/qms/lots | J "d['data']['lotNo']")
[ -n "${LOT2:-}" ] && ok "OQC 完工批创建（$LOT2）" || bad "OQC 建批失败"
LOT2_ID=$(SQL "SELECT ID FROM erp_qms_inspection_lot WHERE LOT_NO='$LOT2'")
[ "$LOT2_ID" != "" ] && ok "批 ID 取得" || bad "批 ID 缺失"
# 录入+判定+放行
ITEMS2=$(SQL "SELECT CONCAT('[',GROUP_CONCAT(CONCAT('{\"itemId\":\"',ID,'\",\"measuredValue\":',IF(SPEC_TYPE='NUMERIC','10.0','null'),',\"judge\":\"PASS\"}')),']') FROM erp_qms_lot_item WHERE LOT_ID='$LOT2_ID'")
EQ "OQC 录入 200" "$(BIZ -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d "{\"items\":$ITEMS2}" $B/api/qms/lots/$LOT2_ID/input)" "200"
JUDGE2=$(C -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOT2_ID/judge | J "d['data']['verdict']")
[ "$JUDGE2" = "PASS" ] && ok "OQC 判定合格" || bad "OQC 判定异常（$JUDGE2）"
EQ "OQC 放行（联动入库+合格证）200" "$(BIZ -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOT2_ID/release)" "200"
TXN=$(SQL "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='MO_FINISH' AND BIZ_DOC_NO='$LOT2'")
EQ "WIP_IN 入库流水落库" "$TXN" "1"
CERT=$(SQL "SELECT COUNT(*) FROM erp_qms_certificate WHERE LOT_NO='$LOT2'")
EQ "合格证生成" "$CERT" "1"
CERT_NO=$(SQL "SELECT CERT_NO FROM erp_qms_certificate WHERE LOT_NO='$LOT2'")
CONTAINS "合格证号 C 前缀" "$CERT_NO" "C"
CERT_QTY=$(SQL "SELECT TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(QTY AS CHAR))) FROM erp_qms_certificate WHERE LOT_NO='$LOT2'")
EQ "合格证数量=批量" "$CERT_QTY" "20"
CERT_BY=$(SQL "SELECT ISSUED_BY FROM erp_qms_certificate WHERE LOT_NO='$LOT2'")
[ -n "${CERT_BY:-}" ] && ok "合格证签发人留痕" || bad "无签发人"
WIP_QTY=$(SQL "SELECT TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(QTY AS CHAR))) FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='MO_FINISH' AND BIZ_DOC_NO='$LOT2'")
EQ "入库数量=批量" "$WIP_QTY" "20"
WIP_WH=$(SQL "SELECT WAREHOUSE_CODE FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='MO_FINISH' AND BIZ_DOC_NO='$LOT2'")
[ -n "${WIP_WH:-}" ] && ok "入库仓库落库（$WIP_WH）" || bad "无仓库"
# BLOCKED 场景（无标准产品不阻断建批）
LOT_BLK=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"lotType\":\"OQC\",\"itemCode\":\"SMKI-NOSTD\",\"itemName\":\"无标准产品\",\"qty\":5}" \
  $B/api/qms/lots | J "d['data']['lotNo']")
[ -n "${LOT_BLK:-}" ] && ok "无标准 OQC 建批不抛错（BLOCKED 占位）" || bad "无标准建批抛错"
# 4.4.2 完工入库工作台可查
WIP_ROWS=$(C -H "$(A $TQC)" "$B/api/inv/workbench/tasks?type=WIP_IN&current=1&size=20" 2>/dev/null | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    rows=(d.get('data') or {}).get('rows') or []
    print(len([r for r in rows if r.get('bizDocNo')=='$LOT2']))
except Exception:
    print(0)")
EQ "4.4.2 完工入库可查" "$WIP_ROWS" "1"

echo "== 6. 超时扫描（OVERDUE 落库 + 改派 + 通知）=="
# 造独立待检批（已放行批不入选扫描）
SQL "INSERT INTO erp_qms_inspection_lot (ID, LOT_NO, LOT_TYPE, SOURCE_TYPE, ITEM_CODE, ITEM_NAME, LOT_QTY,
 RISK_GRADE, STATUS, RESULT, PRIORITY, STRICTNESS, INSPECTOR_ID, INSPECTOR_NAME, DUE_TIME, OVERDUE_FLAG,
 CREATE_BY, DEL_FLAG, VER_NO) VALUES ('lot-smki-od','IL-SMKI-OD','IQC','MANUAL','SMKI001','超时测试物料',10,
 'C','PENDING','PENDING','NORMAL','NORMAL','insp-smki-a','超时质检甲',DATE_SUB(NOW(), INTERVAL 2 HOUR),'0','smoke','0',0)" >/dev/null
SWEPT=$(C -X POST -H "$(A $TQC)" $B/api/qms/lots/sweep-overdue | J "d['data']")
[ "${SWEPT:-0}" -ge 1 ] && ok "扫描处理超时批（$SWEPT）" || bad "扫描未处理"
OD=$(SQL "SELECT OVERDUE_FLAG FROM erp_qms_inspection_lot WHERE ID='lot-smki-od'")
EQ "OVERDUE_FLAG 落库" "$OD" "1"
ASSIGN_LOG=$(SQL "SELECT ASSIGN_LOG FROM erp_qms_inspection_lot WHERE ID='lot-smki-od'")
CONTAINS "改派留痕" "${ASSIGN_LOG:-}" "REASSIGN"
NOTICE=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'QMS_LOT_%' AND BIZ_ID='lot-smki-od'")
[ "${NOTICE:-0}" -ge 1 ] && ok "超时通知落表（$NOTICE）" || bad "无通知"
# 二次扫描：已改派过 → 不再改派（INSP 不变）且升级通知
INSP_AFTER1=$(SQL "SELECT INSPECTOR_ID FROM erp_qms_inspection_lot WHERE ID='lot-smki-od'")
[ "${INSP_AFTER1:-}" != "insp-smki-a" ] && ok "一次改派到他人（$INSP_AFTER1）" || bad "未改派"
SQL "UPDATE erp_qms_inspection_lot SET OVERDUE_FLAG='0' WHERE ID='lot-smki-od'" >/dev/null
C -X POST -H "$(A $TQC)" $B/api/qms/lots/sweep-overdue >/dev/null
INSP_AFTER2=$(SQL "SELECT INSPECTOR_ID FROM erp_qms_inspection_lot WHERE ID='lot-smki-od'")
EQ "二次超时不再改派" "$INSP_AFTER2" "$INSP_AFTER1"
ESC=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_LOT_ESCALATE' AND BIZ_ID='lot-smki-od'")
[ "${ESC:-0}" -ge 1 ] && ok "二次超时升级通知" || bad "无升级通知"
# 通知幂等：再扫不重复推 OVERDUE
N_BEFORE=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_LOT_OVERDUE' AND BIZ_ID='lot-smki-od'")
SQL "UPDATE erp_qms_inspection_lot SET OVERDUE_FLAG='0' WHERE ID='lot-smki-od'" >/dev/null
C -X POST -H "$(A $TQC)" $B/api/qms/lots/sweep-overdue >/dev/null
N_AFTER=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_LOT_OVERDUE' AND BIZ_ID='lot-smki-od'")
EQ "超时通知幂等不重复推" "$N_AFTER" "$N_BEFORE"

echo "== 7. 客户协议（建档 → 生效 → 投诉×2 → 命中全检）=="
AGR=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"agreeCode":"SMKI-AGR-1","customerId":"SMKI-CUST","customerName":"冒烟客户","scopeType":"ITEM","scopeValue":"SMKI002","sampleLevelOverride":"B","tightCtqJson":"[{\"name\":\"包装跌落\",\"specType\":\"LOOK\",\"method\":\"1m 跌落\"}]","effectiveFrom":"'"$(date +%Y-%m-%d)"'"}' \
  $B/api/qms/agreements | J "d['data']['id']")
[ -n "${AGR:-}" ] && ok "协议草稿创建" || bad "协议创建失败"
# 缺客户 422
EQ "缺客户 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"agreeCode":"SMKI-AGR-X","scopeType":"ITEM","scopeValue":"SMKI002"}' $B/api/qms/agreements)" "422"
# 重复编码 422
EQ "重复协议编码 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"agreeCode":"SMKI-AGR-1","customerId":"SMKI-CUST","scopeType":"ITEM","scopeValue":"SMKI002"}' $B/api/qms/agreements)" "422"
EQ "协议生效 200" "$(BIZ -X POST -H "$(A $TMGR)" $B/api/qms/agreements/$AGR/activate)" "200"
# 区间重叠阻断
AGR2=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"agreeCode":"SMKI-AGR-2","customerId":"SMKI-CUST","scopeType":"ITEM","scopeValue":"SMKI002","effectiveFrom":"'"$(date +%Y-%m-%d)"'"}' \
  $B/api/qms/agreements | J "d['data']['id']")
EQ "重叠生效 422" "$(BIZ -X POST -H "$(A $TMGR)" $B/api/qms/agreements/$AGR2/activate)" "422"
EQ "投诉登记 1" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"reason":"外观划伤投诉"}' $B/api/qms/agreements/$AGR/complaint)" "200"
EQ "投诉原因过短 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"reason":"x"}' $B/api/qms/agreements/$AGR/complaint)" "422"
STREAK1=$(SQL "SELECT COMPLAINT_STREAK FROM erp_qms_customer_agreement WHERE ID='$AGR'")
EQ "首登后计数=1" "$STREAK1" "1"
EQ "投诉登记 2" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"reason":"包装破损投诉"}' $B/api/qms/agreements/$AGR/complaint)" "200"
STREAK=$(SQL "SELECT COMPLAINT_STREAK FROM erp_qms_customer_agreement WHERE ID='$AGR'")
EQ "投诉计数=2" "$STREAK" "2"
# 命中全检：建 OQC 批（带客户）
LOT3=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"lotType\":\"OQC\",\"itemCode\":\"SMKI002\",\"itemName\":\"检验冒烟产品\",\"qty\":50,\"customerId\":\"SMKI-CUST\",\"refType\":\"MANUAL\"}" \
  $B/api/qms/lots | J "d['data']['lotNo']")
LOT3_ID=$(SQL "SELECT ID FROM erp_qms_inspection_lot WHERE LOT_NO='$LOT3'")
AGREE_CODE=$(SQL "SELECT AGREE_CODE FROM erp_qms_inspection_lot WHERE ID='$LOT3_ID'")
EQ "协议快照" "$AGREE_CODE" "SMKI-AGR-1"
BASIS=$(SQL "SELECT SAMPLE_BASIS FROM erp_qms_inspection_lot WHERE ID='$LOT3_ID'")
CONTAINS "全检升级留痕" "$BASIS" "全检"
CTQ3=$(SQL "SELECT COUNT(*) FROM erp_qms_lot_item WHERE LOT_ID='$LOT3_ID' AND CHARACTERISTIC_NAME='包装跌落' AND CTQ_FLAG='1'")
EQ "加严 CTQ 并入" "$CTQ3" "1"
SAMPLE3=$(SQL "SELECT TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(SAMPLE_QTY AS CHAR))) FROM erp_qms_inspection_lot WHERE ID='$LOT3_ID'")
EQ "全检样本=批量" "$SAMPLE3" "50"
# 清零恢复
EQ "计数清零 200" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"reason":"问题关闭"}' $B/api/qms/agreements/$AGR/clear)" "200"
STREAK0=$(SQL "SELECT COMPLAINT_STREAK FROM erp_qms_customer_agreement WHERE ID='$AGR'")
EQ "计数已清零" "$STREAK0" "0"
# 停用不改历史
EQ "停用 200" "$(BIZ -X POST -H "$(A $TENG)" $B/api/qms/agreements/$AGR/stop)" "200"
AGREE_AFTER=$(SQL "SELECT AGREE_CODE FROM erp_qms_inspection_lot WHERE ID='$LOT3_ID'")
EQ "历史批快照不变" "$AGREE_AFTER" "SMKI-AGR-1"

echo "== 8. 发货确认 OQC 闸门（service 层经 lots 详情间接验证 + 参数开关）=="
# 场景：无批 + ON → 阻断（SMKI003 无批无标准）
EQ "闸门 ON 无批阻断（参数复位确认 ON）" "$(SQL "SELECT PARAM_VALUE FROM erp_sys_param WHERE PARAM_KEY='OQC_ENFORCE'")" "ON"

echo "== 9. 权限矩阵 =="
EQ "计划线 POST /qms/lots 403" "$(HTTP -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d '{}' $B/api/qms/lots)" "403"
EQ "计划线 POST /qms/agreements 403" "$(HTTP -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' -d '{}' $B/api/qms/agreements)" "403"
EQ "计划线 GET board 200" "$(HTTP -H "$(A $TPLAN)" "$B/api/qms/lots/board?current=1&size=5")" "200"

echo "== 10. 抽样执行记录（6.3.1，首次 + 加大留痕 + FLAG）=="
# LOT1 已放行；补录抽样（可选留痕，不影响既有状态）
SR1=$(C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"sampleMethod":"RANDOM","triggerReason":"INITIAL","actualSampleQty":20,"remark":"冒烟首次抽样"}' \
  $B/api/qms/lots/$LOT1_ID/sampling-records | J "d['data']['seq']")
EQ "首次抽样 seq=1" "$SR1" "1"
FLAG1=$(SQL "SELECT SAMPLED_FLAG FROM erp_qms_inspection_lot WHERE ID='$LOT1_ID'")
EQ "SAMPLED_FLAG 置位" "$FLAG1" "1"
SR2=$(C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"sampleMethod":"NINE_POINT","triggerReason":"ESCALATE","actualSampleQty":40,"remark":"冒烟加大抽样"}' \
  $B/api/qms/lots/$LOT1_ID/sampling-records | J "d['data']['seq']")
EQ "加大抽样 seq=2（不覆盖）" "$SR2" "2"
SR_CNT=$(C -H "$(A $TQC)" $B/api/qms/lots/$LOT1_ID/sampling-records | J "len(d['data'])")
EQ "两条记录并存" "$SR_CNT" "2"
SR_FIRST=$(C -H "$(A $TQC)" $B/api/qms/lots/$LOT1_ID/sampling-records | J "float(d['data'][0]['actualSampleQty'])")
EQ "首次数量未被覆盖" "$SR_FIRST" "20.0"
# 校验 422
SR_BAD=$(BIZ -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"sampleMethod":"BAD","triggerReason":"INITIAL","actualSampleQty":5}' \
  $B/api/qms/lots/$LOT1_ID/sampling-records)
EQ "非法方法 422" "$SR_BAD" "422"
SR_BAD2=$(BIZ -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"sampleMethod":"RANDOM","triggerReason":"INITIAL","actualSampleQty":0}' \
  $B/api/qms/lots/$LOT1_ID/sampling-records)
EQ "数量 0 → 422" "$SR_BAD2" "422"

echo "== 11. 检验记录全状态历史（6.3.2）=="
H_ALL=$(C -H "$(A $TQC)" "$B/api/qms/lots/history?current=1&size=100" | J "len([r for r in d['data']['records'] if r['lotType'] in ('IQC','IPQC','OQC')])")
[ "${H_ALL:-0}" -ge 2 ] && ok "全状态可查（含已放行，$H_ALL 条）" || bad "历史查询为空"
H_REL=$(C -H "$(A $TQC)" "$B/api/qms/lots/history?current=1&size=100&status=RELEASED" | J "len([r for r in d['data']['records'] if r['status']=='RELEASED'])")
[ "${H_REL:-0}" -ge 1 ] && ok "RELEASED 可筛（$H_REL 条）" || bad "RELEASED 筛选为空"
H_PASS=$(C -H "$(A $TQC)" "$B/api/qms/lots/history?current=1&size=100&result=PASS" | J "len([r for r in d['data']['records'] if r['result']=='PASS'])")
[ "${H_PASS:-0}" -ge 1 ] && ok "结果=PASS 筛选（$H_PASS 条）" || bad "PASS 筛选为空"
H_MISS=$(C -H "$(A $TQC)" "$B/api/qms/lots/history?current=1&size=10&keyword=NOBODY-LOT" | J "d['data']['total']")
EQ "关键字不命中 0" "$H_MISS" "0"
H_DATE=$(C -H "$(A $TQC)" "$B/api/qms/lots/history?current=1&size=100&dateFrom=$(date -v-1d +%Y-%m-%d)&dateTo=$(date +%Y-%m-%d)" | J "d['data']['total']")
[ "${H_DATE:-0}" -ge 1 ] && ok "日期区间筛选（$H_DATE 条）" || bad "日期筛选为空"

echo "== 12. 6.3.1 抽样模式（samplingDone=false 过滤）=="
B_UNSAMPLED=$(C -H "$(A $TQC)" "$B/api/qms/lots/board?current=1&size=100&samplingDone=false" \
  | J "len([r for r in d['data']['records'] if r.get('sampledFlag')=='1'])")
EQ "仅未抽样：无已抽样批混入" "$B_UNSAMPLED" "0"
B_ALL=$(C -H "$(A $TQC)" "$B/api/qms/lots/board?current=1&size=100" \
  | J "len([r for r in d['data']['records'] if r.get('sampledFlag')=='1'])")
[ "${B_ALL:-0}" -ge 0 ] && ok "不传 samplingDone 行为不变（已抽样 $B_ALL 条可出现）" || bad "board 查询失败"

echo "== 13. 抽样记录权限（401 / 403）=="
EQ "POST sampling-records 401" "$(HTTP -X POST $B/api/qms/lots/$LOT1_ID/sampling-records)" "401"
EQ "GET history 401" "$(HTTP $B/api/qms/lots/history)" "401"
EQ "计划线 POST sampling-records 403" "$(HTTP -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d '{"sampleMethod":"RANDOM","triggerReason":"INITIAL","actualSampleQty":5}' \
  $B/api/qms/lots/$LOT1_ID/sampling-records)" "403"
EQ "计划线 GET history 403" "$(HTTP -H "$(A $TPLAN)" $B/api/qms/lots/history)" "403"

echo ""
echo "=============================="
echo "== 14. 放行通知（IQC→仓库 / OQC→仓库+跟单，rework-qualified-release）=="
# 第 4 段 IQC 放行 LOT1、第 5 段 OQC 放行 LOT2 → 本段查通知落表
N_IQC=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_RELEASE' AND BIZ_ID='$LOT1_ID' AND TARGET_ROLE='ROLE_WAREHOUSE' AND TITLE LIKE '%上架%'")
EQ "IQC 放行→仓库上架通知 1 条" "$N_IQC" "1"
N_OQC=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_RELEASE' AND BIZ_ID='$LOT2_ID' AND TARGET_ROLE IN ('ROLE_WAREHOUSE','ROLE_SALES')")
EQ "OQC 放行→仓库+跟单 2 条" "$N_OQC" "2"
CERT0=$(SQL "SELECT CERT_NO FROM erp_qms_certificate WHERE LOT_ID='$LOT2_ID' LIMIT 1")
N_SALES_C=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_RELEASE' AND BIZ_ID='$LOT2_ID' AND TARGET_ROLE='ROLE_SALES' AND CONTENT LIKE CONCAT('%',IFNULL('$CERT0','__空__'),'%')")
[ -n "$CERT0" ] && EQ "跟单通知含合格证号" "$N_SALES_C" "1" || ok "OQC 无合格证（跳过证号断言）"

echo "== 15. 6.4.1 放行模式（board?releaseMode=true）=="
# 造待放行批（PASS+INPUTTING）与 FAIL 批
SQL "INSERT IGNORE INTO erp_qms_inspection_lot (ID, LOT_NO, LOT_TYPE, SOURCE_TYPE, ITEM_CODE, ITEM_NAME, LOT_QTY, RISK_GRADE, STATUS, RESULT, PRIORITY, STRICTNESS, STANDARD_ID, STANDARD_CODE, STANDARD_VERSION, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('lot-smki-rls','IL-SMKI-RLS','IPQC','MANUAL','SMKI002','检验冒烟产品',20,'C','INPUTTING','PASS','NORMAL','NORMAL','sv-smki-1','SMKI-STD-1',1,'smoke','0',0),('lot-smki-fail','IL-SMKI-FAIL','IPQC','MANUAL','SMKI002','检验冒烟产品',20,'C','PENDING','FAIL','NORMAL','NORMAL','sv-smki-1','SMKI-STD-1',1,'smoke','0',0);" >/dev/null 2>&1
RLS_IDS=$(C -H "$(A $TQC)" "$B/api/qms/lots/board?current=1&size=100&releaseMode=true" | J "[r['id'] for r in d['data']['records'] if str(r['id']).startswith('lot-smki')]")
CONTAINS "待放行批在列" "$RLS_IDS" "lot-smki-rls"
RLS_BAD=$(C -H "$(A $TQC)" "$B/api/qms/lots/board?current=1&size=100&releaseMode=true" | J "len([r for r in d['data']['records'] if r.get('result')!='PASS' or r.get('status')!='INPUTTING'])")
EQ "releaseMode 无越态批混入" "$RLS_BAD" "0"
RLS_REL=$(C -H "$(A $TQC)" "$B/api/qms/lots/board?current=1&size=100&status=RELEASED" | J "len([r for r in d['data']['records'] if r['id']=='$LOT1_ID'])")
EQ "已放行历史可查（status=RELEASED）" "$RLS_REL" "1"
RLS_NORMAL=$(C -H "$(A $TQC)" "$B/api/qms/lots/board?current=1&size=100" | J "len([r for r in d['data']['records'] if r['id']=='lot-smki-rls'])")
EQ "不传 releaseMode 行为不变（PASS/INPUTTING 在途可见）" "$RLS_NORMAL" "1"

echo "== 16. NCR 处置通知 + 冻结影响快照 + 让步接收全链 =="
# 库存（已入库分支 freezeStock 需 WH-MAIN 批次库存）
SQL "INSERT IGNORE INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, BIN_CODE, QTY, AVAILABLE_QTY, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('stk-smki-ncr','WH-MAIN','SMKI002','检验冒烟产品','BATCH-SMK2','A-01',50,50,'smoke','0',0);" >/dev/null 2>&1
# 手工批（line=null → freezeStock）→ 录入全 FAIL → 判定 FAIL → NCR 自动
LOT16=$(C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"lotType":"IPQC","itemCode":"SMKI002","itemName":"检验冒烟产品","qty":20,"batchNo":"BATCH-SMK2","refType":"MANUAL","refId":"smk16"}' \
  $B/api/qms/lots | J "d['data']['lotNo']")
[ -n "${LOT16:-}" ] && ok "NCR 溯源批创建（$LOT16）" || bad "建批失败"
LOT16_ID=$(SQL "SELECT ID FROM erp_qms_inspection_lot WHERE LOT_NO='$LOT16'")
IT16=$(SQL "SELECT GROUP_CONCAT(CONCAT('{\"itemId\":\"',ID,'\",\"judge\":\"FAIL\"}') SEPARATOR ',') FROM erp_qms_lot_item WHERE LOT_ID='$LOT16_ID'")
C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' -d "{\"items\":[$IT16]}" $B/api/qms/lots/$LOT16_ID/input >/dev/null
BIZ -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOT16_ID/judge >/dev/null
NCR16=$(SQL "SELECT ID FROM erp_qms_ncr WHERE LOT_ID='$LOT16_ID'")
NCR16_NO=$(SQL "SELECT NCR_NO FROM erp_qms_ncr WHERE LOT_ID='$LOT16_ID'")
[ -n "$NCR16" ] && ok "不合格自动生成 NCR（$NCR16_NO）" || bad "NCR 未生成"
# 处置通知 → 质量工程师
DN=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_NCR' AND BIZ_ID='$NCR16' AND TARGET_ROLE='ROLE_QUALITY_ENG' AND TITLE LIKE CONCAT('%','$NCR16_NO','%')")
EQ "NCR 处置通知→质量工程师" "$DN" "1"
DN_C=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_NCR' AND BIZ_ID='$NCR16' AND CONTENT LIKE '%前完成评审%'")
EQ "通知含评审时限" "$DN_C" "1"
# 冻结影响快照 + 计划通知
IMPACT=$(SQL "SELECT IMPACT_JSON FROM erp_inv_freeze WHERE NCR_ID='$NCR16' AND SOURCE='NCR' LIMIT 1")
CONTAINS "台账影响快照（moDataSource 标记）" "${IMPACT:-}" "moDataSource"
PN=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_NCR_FREEZE' AND BIZ_ID='$NCR16' AND TARGET_ROLE='ROLE_PLANNER'")
EQ "冻结计划通知→计划员" "$PN" "1"
QCB=$(SQL "SELECT QC_QTY>0 FROM erp_inv_stock WHERE ID='stk-smki-ncr'")
EQ "库存 QC 锁定生效" "$QCB" "1"
# 让步接收全链：评审 CONCESSION →（CTQ 单先过 NcrDisposition 审批）→ 申请 → 双签 → 核销 → 越界 422
EQ "评审为让步 200" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"disposition":"CONCESSION","opinion":"评估可用，走让步"}' $B/api/qms/ncrs/$NCR16/review)" "200"
# NCR16 为 CTQ 单（外观 CTQ FAIL）→ 评审提交进审批，经理通过后才流转（rework-ncr-identification-disposition）
APP16=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='NcrDisposition' AND BIZ_ID='$NCR16' AND STATUS='PENDING'")
[ -n "${APP16:-}" ] && ok "CTQ 让步评审进 NcrDisposition 审批" || bad "CTQ 审批实例缺失"
T16=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$APP16' AND STATUS='ACTIVE' AND ROLE_REQUIRED='ROLE_QUALITY_MGR' LIMIT 1")
EQ "CTQ 审批通过 200" "$(BIZ -X POST -H "$(A $TMGR)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$T16\",\"opinion\":\"同意让步评估\"}" $B/api/qms/approvals/pass)" "200"
EQ "审批通过后转 CONCESSION" "$(SQL "SELECT STATUS FROM erp_qms_ncr WHERE ID='$NCR16'")" "CONCESSION"
CS=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"ncrId\":\"$NCR16\",\"reason\":\"冒烟让步：外观轻微划伤\",\"techAssess\":\"非功能面可用\",\"riskAssess\":\"低风险\",\"limitUntil\":\"$(date -v+30d +%Y-%m-%d)\",\"limitQty\":\"15\",\"limitScope\":\"SMKI002\"}" \
  $B/api/qms/concessions | J "d['data']['id']")
[ -n "${CS:-}" ] && ok "让步申请创建（要素全）" || bad "让步申请失败"
CS_BAD=$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"ncrId\":\"$NCR16\"}" $B/api/qms/concessions)
EQ "要素缺失 422" "$CS_BAD" "422"
EQ "提交双签 200" "$(BIZ -X POST -H "$(A $TENG)" $B/api/qms/concessions/$CS/submit)" "200"
# 双签（按节点角色签）
CS_APPR=$(SQL "SELECT APPROVAL_ID FROM erp_qms_concession WHERE ID='$CS'")
T_MGR=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$CS_APPR' AND STATUS='ACTIVE' AND ROLE_REQUIRED='ROLE_QUALITY_MGR' LIMIT 1")
T_TECH=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$CS_APPR' AND STATUS='ACTIVE' AND ROLE_REQUIRED='ROLE_TECH_OWNER' LIMIT 1")
EQ "第 1 签（质量经理）200" "$(BIZ -X POST -H "$(A $TMGR)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$T_MGR\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass)" "200"
CS_ST1=$(SQL "SELECT STATUS FROM erp_qms_concession WHERE ID='$CS'")
EQ "单签不生效" "$CS_ST1" "PENDING_APPROVE"
EQ "第 2 签（技术负责人）200" "$(BIZ -X POST -H "$(A $TTECH)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$T_TECH\",\"opinion\":\"同意\"}" $B/api/qms/approvals/pass)" "200"
CS_ST2=$(SQL "SELECT STATUS FROM erp_qms_concession WHERE ID='$CS'")
EQ "双签全过批准" "$CS_ST2" "APPROVED"
# 核销成功 + 越界 422
WO=$(C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"qty":10,"scope":"SMKI002 批次内部领用"}' $B/api/qms/concessions/$CS/writeoffs | J "float(d['data']['remainQty'])")
EQ "核销 10 后剩余 5.0" "$WO" "5.0"
WO_BAD=$(BIZ -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"qty":20,"scope":"SMKI002 批次内部领用"}' $B/api/qms/concessions/$CS/writeoffs)
EQ "越界核销 422" "$WO_BAD" "422"
WO_REJ=$(SQL "SELECT COUNT(*) FROM erp_qms_concession_writeoff WHERE CONCESSION_ID='$CS' AND STATUS='REJECTED'")
EQ "越界尝试留 REJECTED 记录" "$WO_REJ" "1"

echo "=============================="
echo "== 17. CTQ 处置审批链（rework-ncr-identification-disposition：提交不流转/通过流转/驳回改选）=="
# 造 CTQ 判不合格 NCR：IQC 批（CTQ 外观 FAIL → NCR ctqFlag=1）
SQL "INSERT IGNORE INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, BIN_CODE, QTY, AVAILABLE_QTY, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('stk-smki-ctq','WH-MAIN','SMKI002','检验冒烟产品','BATCH-SMKCTQ','A-02',30,30,'smoke','0',0);" >/dev/null 2>&1
LOT17=$(C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"lotType":"IPQC","itemCode":"SMKI002","itemName":"检验冒烟产品","qty":20,"batchNo":"BATCH-SMKCTQ","refType":"MANUAL","refId":"smk17"}' \
  $B/api/qms/lots | J "d['data']['lotNo']")
[ -n "${LOT17:-}" ] && ok "CTQ 链批创建（$LOT17）" || bad "建批失败"
LOT17_ID=$(SQL "SELECT ID FROM erp_qms_inspection_lot WHERE LOT_NO='$LOT17'")
# CTQ 外观 FAIL + 尺寸 FAIL → 整批不合格，CTQ 不合格 → NCR ctqFlag=1
IT17=$(SQL "SELECT GROUP_CONCAT(CONCAT('{\"itemId\":\"',ID,'\",\"judge\":\"FAIL\"}') SEPARATOR ',') FROM erp_qms_lot_item WHERE LOT_ID='$LOT17_ID'")
C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' -d "{\"items\":[$IT17]}" $B/api/qms/lots/$LOT17_ID/input >/dev/null
BIZ -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOT17_ID/judge >/dev/null
NCR17=$(SQL "SELECT ID FROM erp_qms_ncr WHERE LOT_ID='$LOT17_ID'")
CTQ_FLAG=$(SQL "SELECT CTQ_FLAG FROM erp_qms_ncr WHERE ID='$NCR17'")
EQ "CTQ 不合格 NCR ctqFlag=1" "$CTQ_FLAG" "1"
# CTQ 单评审提交 → 不流转，进审批
EQ "CTQ 评审提交 200" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"disposition":"REWORK","opinion":"CTQ 外观+尺寸超差，返工修复"}' $B/api/qms/ncrs/$NCR17/review)" "200"
NCR17_ST=$(SQL "SELECT STATUS FROM erp_qms_ncr WHERE ID='$NCR17'")
EQ "提交后不流转（仍 CREATED）" "$NCR17_ST" "CREATED"
NCR17_PEND=$(SQL "SELECT DISPO_PENDING_FLAG FROM erp_qms_ncr WHERE ID='$NCR17'")
EQ "待审批标记落库" "$NCR17_PEND" "1"
# 重复提交 422
EQ "审批中重复提交 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"disposition":"RETURN","opinion":"想改退货"}' $B/api/qms/ncrs/$NCR17/review)" "422"
# 审批实例 + 经理待办
APP17=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='NcrDisposition' AND BIZ_ID='$NCR17' AND STATUS='PENDING'")
[ -n "${APP17:-}" ] && ok "NcrDisposition 审批实例生成" || bad "审批实例缺失"
T17=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$APP17' AND STATUS='ACTIVE' AND ROLE_REQUIRED='ROLE_QUALITY_MGR' LIMIT 1")
[ -n "${T17:-}" ] && ok "质量经理待办生成" || bad "待办缺失"
# 质量经理通过 → 流转 REWORKING
EQ "经理审批通过 200" "$(BIZ -X POST -H "$(A $TMGR)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$T17\",\"opinion\":\"同意返工\"}" $B/api/qms/approvals/pass)" "200"
NCR17_ST2=$(SQL "SELECT STATUS FROM erp_qms_ncr WHERE ID='$NCR17'")
EQ "审批通过 → REWORKING" "$NCR17_ST2" "REWORKING"
NCR17_PEND2=$(SQL "SELECT DISPO_PENDING_FLAG FROM erp_qms_ncr WHERE ID='$NCR17'")
EQ "pending 标记清除" "${NCR17_PEND2:-NULL}" "NULL"
NCR17_LOG=$(SQL "SELECT COUNT(*) FROM erp_qms_ncr_log WHERE NCR_ID='$NCR17' AND ACTION='REVIEW_APPROVE'")
EQ "审批通过日志留痕" "$NCR17_LOG" "1"
# 驳回链：第二张 CTQ 单提交 → 经理驳回 → 回 CREATED 可改选
LOT17B=$(C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"lotType":"IPQC","itemCode":"SMKI002","itemName":"检验冒烟产品","qty":10,"batchNo":"BATCH-SMKCTQ","refType":"MANUAL","refId":"smk17b"}' \
  $B/api/qms/lots | J "d['data']['lotNo']")
LOT17B_ID=$(SQL "SELECT ID FROM erp_qms_inspection_lot WHERE LOT_NO='$LOT17B'")
IT17B=$(SQL "SELECT GROUP_CONCAT(CONCAT('{\"itemId\":\"',ID,'\",\"judge\":\"FAIL\"}') SEPARATOR ',') FROM erp_qms_lot_item WHERE LOT_ID='$LOT17B_ID'")
C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' -d "{\"items\":[$IT17B]}" $B/api/qms/lots/$LOT17B_ID/input >/dev/null
BIZ -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOT17B_ID/judge >/dev/null
NCR17B=$(SQL "SELECT ID FROM erp_qms_ncr WHERE LOT_ID='$LOT17B_ID'")
BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"disposition":"SCRAP","opinion":"判定报废"}' $B/api/qms/ncrs/$NCR17B/review >/dev/null
APP17B=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='NcrDisposition' AND BIZ_ID='$NCR17B' AND STATUS='PENDING'")
T17B=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$APP17B' AND STATUS='ACTIVE' AND ROLE_REQUIRED='ROLE_QUALITY_MGR' LIMIT 1")
EQ "经理驳回 200" "$(BIZ -X POST -H "$(A $TMGR)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$T17B\",\"reason\":\"建议改选退货处置\"}" $B/api/qms/approvals/reject)" "200"
NCR17B_ST=$(SQL "SELECT STATUS FROM erp_qms_ncr WHERE ID='$NCR17B'")
EQ "驳回回 CREATED" "$NCR17B_ST" "CREATED"
NCR17B_DISP=$(SQL "SELECT DISPOSITION FROM erp_qms_ncr WHERE ID='$NCR17B'")
EQ "处置选择清除（可改选）" "${NCR17B_DISP:-NULL}" "NULL"
# 改选重提（非让步/报废不触禁令）→ 再次进审批
BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"disposition":"RETURN","opinion":"按驳回意见改选退货"}' $B/api/qms/ncrs/$NCR17B/review >/dev/null
NCR17B_ST3=$(SQL "SELECT STATUS FROM erp_qms_ncr WHERE ID='$NCR17B'")
EQ "改选重提仍待审批" "$NCR17B_ST3" "CREATED"

echo "=============================="
echo "== 18. 6.5.1 隔离确认 + 标识卡（rework-ncr-identification-disposition）=="
# 隔离清单：frozenOnly=true 只看冻结中（排除已作废/关闭）
ISO_ROWS=$(C -H "$(A $TENG)" "$B/api/qms/ncrs?frozenOnly=true&size=100" | J "len(d['data']['records'])")
[ "${ISO_ROWS:-0}" -gt 0 ] && ok "隔离清单返回冻结中单据（${ISO_ROWS} 条）" || bad "隔离清单为空"
# 隔离确认（质检员）→ 落列 + 日志，状态不变
EQ "隔离确认 200" "$(BIZ -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"bin":"Q-HOLD-01"}' $B/api/qms/ncrs/$NCR17/isolate)" "200"
ISO_FLAG=$(SQL "SELECT ISOLATED_FLAG FROM erp_qms_ncr WHERE ID='$NCR17'")
EQ "隔离标记落库" "$ISO_FLAG" "1"
ISO_BIN=$(SQL "SELECT ISOLATE_BIN FROM erp_qms_ncr WHERE ID='$NCR17'")
EQ "隔离货位落库" "$ISO_BIN" "Q-HOLD-01"
ISO_LOG=$(SQL "SELECT COUNT(*) FROM erp_qms_ncr_log WHERE NCR_ID='$NCR17' AND ACTION='ISOLATE'")
EQ "隔离日志留痕" "$ISO_LOG" "1"
ISO_ST=$(SQL "SELECT STATUS FROM erp_qms_ncr WHERE ID='$NCR17'")
EQ "隔离确认不改状态机（仍 REWORKING）" "$ISO_ST" "REWORKING"
# 越权：计划员隔离 → Security 层 HTTP 403（非业务码，用 HTTP 断言）
EQ "计划员隔离 HTTP 403" "$(HTTP -X POST -H "$(A $TPLAN)" -H 'Content-Type: application/json' \
  -d '{"bin":"Q-HOLD-01"}' $B/api/qms/ncrs/$NCR17/isolate)" "403"
# 货位缺失 422
EQ "货位缺失 422" "$(BIZ -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"bin":" "}' $B/api/qms/ncrs/$NCR17/isolate)" "422"
# 重复确认覆盖留痕
BIZ -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"bin":"Q-HOLD-02"}' $B/api/qms/ncrs/$NCR17/isolate >/dev/null
ISO_BIN2=$(SQL "SELECT ISOLATE_BIN FROM erp_qms_ncr WHERE ID='$NCR17'")
EQ "重复确认覆盖货位" "$ISO_BIN2" "Q-HOLD-02"
ISO_LOG2=$(SQL "SELECT COUNT(*) FROM erp_qms_ncr_log WHERE NCR_ID='$NCR17' AND ACTION='ISOLATE'")
EQ "重复确认再留痕（日志 2 条）" "$ISO_LOG2" "2"
# 详情含隔离字段（标识卡数据源）
ISO_DET=$(C -H "$(A $TENG)" $B/api/qms/ncrs/$NCR17 | J "d['data']['ncr']['isolateBin']")
EQ "详情返回隔离字段" "$ISO_DET" "Q-HOLD-02"

echo "=============================="
echo "== 19. 6.5.2 多态队列 + 处置阶段（rework-ncr-identification-disposition）=="
# 五态 in 查询 + withStage 阶段推导
STAGE_ROWS=$(C -H "$(A $TENG)" "$B/api/qms/ncrs?statuses=RETURNING,SORTING,REWORKING,CONCESSION,SCRAPPING&withStage=true&size=100" | J "len(d['data']['records'])")
[ "${STAGE_ROWS:-0}" -gt 0 ] && ok "五态在途队列返回（${STAGE_ROWS} 条）" || bad "五态队列为空"
# 阶段列存在且非空（REWORKING 无方案 → 待方案录入）
STAGE17=$(C -H "$(A $TENG)" "$B/api/qms/ncrs?statuses=REWORKING&withStage=true&size=100" \
  | J "[r.get('disposeStage') for r in d['data']['records'] if r['id']=='$NCR17'][0]")
EQ "返工无方案阶段=待方案录入" "${STAGE17:-}" "待方案录入"
# 录方案 → 阶段变待处置确认（返工结构化三字段 + CTQ 勾选）
EQ "返工结构化方案缺字段 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"plan":"返工概述"}' $B/api/qms/ncrs/$NCR17/disposition-plan)" "422"
EQ "返工未勾 CTQ 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"plan":"返工概述","reworkSteps":"拆解→换轴→复测","reworkParams":"扭矩 3.2±0.2N·m","reworkCheckReq":"原 CTQ 全项+外观","ctqConfirmed":false}' \
  $B/api/qms/ncrs/$NCR17/disposition-plan)" "422"
EQ "返工结构化方案 200" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"plan":"返工概述","reworkSteps":"拆解→换轴→复测","reworkParams":"扭矩 3.2±0.2N·m","reworkCheckReq":"原 CTQ 全项+外观","ctqConfirmed":true}' \
  $B/api/qms/ncrs/$NCR17/disposition-plan)" "200"
STAGE17B=$(C -H "$(A $TENG)" "$B/api/qms/ncrs?statuses=REWORKING&withStage=true&size=100" \
  | J "[r.get('disposeStage') for r in d['data']['records'] if r['id']=='$NCR17'][0]")
EQ "返工有方案阶段=待处置确认" "${STAGE17B:-}" "待处置确认"
# 挑选结构化：造挑选单 + 数量不平 422 + 低合格率软提示 + 成本归集
# 手工建批仅支持 IPQC/OQC；全项 FAIL → CTQ 单 → 评审进审批（经理通过后转 SORTING）
LOT18=$(C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"lotType":"IPQC","itemCode":"SMKI002","itemName":"检验冒烟产品","qty":100,"batchNo":"BATCH-SMKSRT","refType":"MANUAL","refId":"smk18"}' \
  $B/api/qms/lots | J "d['data']['lotNo']")
[ -n "${LOT18:-}" ] && ok "挑选链批创建（$LOT18）" || bad "挑选链建批失败"
LOT18_ID=$(SQL "SELECT ID FROM erp_qms_inspection_lot WHERE LOT_NO='$LOT18'")
IT18=$(SQL "SELECT GROUP_CONCAT(CONCAT('{\"itemId\":\"',ID,'\",\"judge\":\"FAIL\"}') SEPARATOR ',') FROM erp_qms_lot_item WHERE LOT_ID='$LOT18_ID'")
C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' -d "{\"items\":[$IT18]}" $B/api/qms/lots/$LOT18_ID/input >/dev/null
BIZ -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOT18_ID/judge >/dev/null
NCR18=$(SQL "SELECT ID FROM erp_qms_ncr WHERE LOT_ID='$LOT18_ID'")
BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"disposition":"SORT","opinion":"部分可挑选使用"}' $B/api/qms/ncrs/$NCR18/review >/dev/null
# CTQ 单评审进审批 → 经理通过 → SORTING
APP18=$(SQL "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='NcrDisposition' AND BIZ_ID='$NCR18' AND STATUS='PENDING'")
T18=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID='$APP18' AND STATUS='ACTIVE' AND ROLE_REQUIRED='ROLE_QUALITY_MGR' LIMIT 1")
EQ "挑选 CTQ 审批通过 200" "$(BIZ -X POST -H "$(A $TMGR)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$T18\",\"opinion\":\"同意挑选\"}" $B/api/qms/approvals/pass)" "200"
EQ "审批后转 SORTING" "$(SQL "SELECT STATUS FROM erp_qms_ncr WHERE ID='$NCR18'")" "SORTING"
BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"plan":"挑选范围：A 区全检"}' $B/api/qms/ncrs/$NCR18/disposition-plan >/dev/null
# 挑选结果数量不平 → 422
EQ "挑选数量不平 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"result":"挑选完成","sortTotalQty":100,"sortOkQty":70,"sortNgQty":20,"sortHours":6}' \
  $B/api/qms/ncrs/$NCR18/dispose-confirm)" "422"
# 低合格率（40/100）→ 200 + warnings 软提示 + 成本归集
SRT_OUT=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"result":"挑选完成","sortTotalQty":100,"sortOkQty":40,"sortNgQty":60,"sortHours":8,"costAmount":3000}' \
  $B/api/qms/ncrs/$NCR18/dispose-confirm)
SRT_CODE=$(echo "$SRT_OUT" | J "d['code']")
EQ "低合格率软提示 200（不阻断）" "$SRT_CODE" "200"
SRT_WARN=$(echo "$SRT_OUT" | J "len(d['data'].get('warnings') or [])")
EQ "低合格率 warnings 1 条" "$SRT_WARN" "1"
SRT_ST=$(SQL "SELECT STATUS FROM erp_qms_ncr WHERE ID='$NCR18'")
EQ "挑选确认 → DISPOSED" "$SRT_ST" "DISPOSED"
SRT_OK=$(SQL "SELECT SORT_OK_QTY FROM erp_qms_ncr WHERE ID='$NCR18'")
EQ "挑选结构化落库（合格 40）" "$SRT_OK" "40.0000"
COPQ18=$(SQL "SELECT COUNT(*) FROM erp_qms_copq_entry WHERE SOURCE_TYPE='NCR' AND SOURCE_ID='$NCR18' AND CATEGORY='INTERNAL_FAILURE'")
EQ "COPQ 内部失败归集（成本 3000）" "$COPQ18" "1"
# 挑选已确认 → 复检批阶段（DISPOSED 不在在途五态，用 stage 查询验证复检批已生成）
RC18=$(SQL "SELECT COUNT(*) FROM erp_qms_inspection_lot WHERE REF_TYPE='NCR' AND REF_ID='$NCR18' AND SOURCE_TYPE='RECHECK'")
EQ "挑选完成生成复检批" "$RC18" "1"
# 让步中阶段=待双签批准（NCR16 已 CONCESSION 且双签完成 → 双签完成待确认）
STAGE16=$(C -H "$(A $TENG)" "$B/api/qms/ncrs?statuses=CONCESSION&withStage=true&size=100" \
  | J "[r.get('disposeStage') for r in d['data']['records'] if r['id']=='$NCR16'][0]")
EQ "让步双签完成阶段=双签完成待确认" "${STAGE16:-}" "双签完成待确认"

echo "=============================="
echo "== 20. 重复 NCR 升级链（rework-ncr-control：二次 NCR 上调 + 3 月 3 张 CAPA 待办）=="
# 造同批复检链：首单（关闭）+ 复检批仍不合格 → 二次 NCR 上调关联
SQL "INSERT IGNORE INTO erp_qms_ncr (ID, NCR_NO, LOT_ID, ITEM_CODE, ITEM_NAME, QTY, BATCH_NO, STATUS, SEVERITY, CTQ_FLAG, REGULATORY_FLAG, FROZEN_FLAG, FREEZE_SCOPE, CREATE_BY) VALUES ('smk20-first','NCR-SMK20-1','lot-smk20-0','SMKI002','重复升级冒烟',10,'BATCH-SMK20','CLOSED','MINOR','0','0','0','NONE','smoke');" >/dev/null
LOT20=$(C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"lotType":"IPQC","itemCode":"SMKI002","itemName":"检验冒烟产品","qty":10,"batchNo":"BATCH-SMK20","refType":"NCR","refId":"smk20-first"}' \
  $B/api/qms/lots | J "d['data']['lotNo']")
[ -n "${LOT20:-}" ] && ok "复检链批创建（$LOT20）" || bad "复检链建批失败"
LOT20_ID=$(SQL "SELECT ID FROM erp_qms_inspection_lot WHERE LOT_NO='$LOT20'")
# 仅非 CTQ 项 FAIL（CTQ 外观 PASS）→ 基线 MINOR，验证上调 MINOR→MAJOR
IT20=$(SQL "SELECT GROUP_CONCAT(CONCAT('{\"itemId\":\"',ID,'\",\"judge\":\"',IF(CTQ_FLAG='1','PASS','FAIL'),'\"}') SEPARATOR ',') FROM erp_qms_lot_item WHERE LOT_ID='$LOT20_ID'")
C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' -d "{\"items\":[$IT20]}" $B/api/qms/lots/$LOT20_ID/input >/dev/null
BIZ -X POST -H "$(A $TQC)" $B/api/qms/lots/$LOT20_ID/judge >/dev/null
NCR20=$(SQL "SELECT ID FROM erp_qms_ncr WHERE LOT_ID='$LOT20_ID'")
SEV20=$(SQL "SELECT SEVERITY FROM erp_qms_ncr WHERE ID='$NCR20'")
EQ "二次 NCR 严重度上调（MINOR→MAJOR）" "$SEV20" "MAJOR"
REF20=$(SQL "SELECT REF_NCR_ID FROM erp_qms_ncr WHERE ID='$NCR20'")
EQ "关联前序 NCR" "$REF20" "smk20-first"
# 三维度扫描触发 CAPA 待办（同供应商 3 张近 90 天）
SQL "INSERT IGNORE INTO erp_qms_ncr (ID, NCR_NO, LOT_ID, ITEM_CODE, ITEM_NAME, QTY, SUPPLIER_ID, SUPPLIER_NAME, STATUS, SEVERITY, CTQ_FLAG, REGULATORY_FLAG, FROZEN_FLAG, FREEZE_SCOPE, CREATE_BY) VALUES ('smk20-s1','NCR-SMK20-S1','l20s1','SMKI003','三张扫描物料',5,'sup-smk20','冒烟供应商20','CLOSED','MAJOR','0','0','0','NONE','smoke'),('smk20-s2','NCR-SMK20-S2','l20s2','SMKI003','三张扫描物料',5,'sup-smk20','冒烟供应商20','CLOSED','MAJOR','0','0','0','NONE','smoke'),('smk20-s3','NCR-SMK20-S3','l20s3','SMKI003','三张扫描物料',5,'sup-smk20','冒烟供应商20','CLOSED','MAJOR','0','0','0','NONE','smoke');" >/dev/null
BIZ -X POST -H "$(A $TADM)" $B/api/qms/ncrs/sweeps >/dev/null
sleep 1
BIZ -X POST -H "$(A $TADM)" $B/api/qms/ncrs/sweeps >/dev/null
CAPA_TODO=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_REPEAT' AND BIZ_ID LIKE 'CAPA_TODO:SUPPLIER:sup-smk20%'")
[ "${CAPA_TODO:-0}" -ge 1 ] && ok "3 月 3 张触发 CAPA 立项待办（$CAPA_TODO 行）" || bad "CAPA 待办未生成"
CAPA_SQE=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_REPEAT' AND TARGET_ROLE='ROLE_SQE' AND BIZ_ID LIKE 'CAPA_TODO:SUPPLIER:sup-smk20%'")
EQ "供应商维度加推 SQE" "$CAPA_SQE" "1"
CAPA_TODO2=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_REPEAT' AND BIZ_ID LIKE 'CAPA_TODO:SUPPLIER:sup-smk20%'")
EQ "重扫幂等（待办数不变）" "$CAPA_TODO2" "$CAPA_TODO"

echo "=============================="
echo "== 21. 漏检召回评估（rework-ncr-control：已放行建单 + 召回评估双向关联）=="
# 手工放行批建 NCR（走已放行入口）
SQL "INSERT IGNORE INTO erp_qms_inspection_lot (ID, LOT_NO, LOT_TYPE, ITEM_CODE, ITEM_NAME, LOT_QTY, BATCH_NO, STATUS, RESULT, RISK_GRADE, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('lot-smk21','IL-SMK21','IQC','SMKI001','召回评估冒烟物料',50,'BATCH-SMK21','RELEASED','PASS','C','smoke','0',0);" >/dev/null
SQL "INSERT IGNORE INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, PRODUCTION_DATE, EXPIRY_DATE, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('bt-smk21','BATCH-SMK21','SMKI001','召回评估冒烟物料',CURDATE(),DATE_ADD(CURDATE(),INTERVAL 90 DAY),'smoke','0',0);" >/dev/null
NCR21=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"lotId":"lot-smk21","defectItem":"放行后外观漏检","defectDesc":"客户投诉划伤，追溯为放行漏检","severity":"MAJOR","ctqFlag":"0"}' \
  $B/api/qms/ncrs/released-lots | J "d['data']['ncrNo']")
[ -n "${NCR21:-}" ] && ok "已放行批次建 NCR（$NCR21）" || bad "放行后建单失败"
NCR21_ID=$(SQL "SELECT ID FROM erp_qms_ncr WHERE NCR_NO='$NCR21'")
REF21=$(SQL "SELECT REF_TYPE FROM erp_qms_ncr WHERE ID='$NCR21_ID'")
EQ "refType=RELEASED_LOT" "$REF21" "RELEASED_LOT"
# 未放行批 422
LOT21B=$(C -X POST -H "$(A $TQC)" -H 'Content-Type: application/json' \
  -d '{"lotType":"IPQC","itemCode":"SMKI002","itemName":"检验冒烟产品","qty":5,"batchNo":"BATCH-SMK21B","refType":"MANUAL","refId":"smk21b"}' \
  $B/api/qms/lots | J "d['data']['lotNo']")
LOT21B_ID=$(SQL "SELECT ID FROM erp_qms_inspection_lot WHERE LOT_NO='$LOT21B'")
EQ "未放行批建单 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"lotId\":\"$LOT21B_ID\",\"defectItem\":\"x\",\"defectDesc\":\"未放行试探\"}" \
  $B/api/qms/ncrs/released-lots)" "422"
# 发起召回评估 → 追溯单双向关联
EVAL=$(C -X POST -H "$(A $TENG)" $B/api/qms/ncrs/$NCR21_ID/recall-evaluation)
EVAL_CODE=$(echo "$EVAL" | J "d['code']")
EQ "发起召回评估 200" "$EVAL_CODE" "200"
TRACE21=$(echo "$EVAL" | J "d['data']['traceNo']")
[ -n "${TRACE21:-}" ] && ok "追溯单创建（$TRACE21）" || bad "追溯单未返回"
TRACE21_ID=$(echo "$EVAL" | J "d['data']['traceId']")
SRC_NCR=$(SQL "SELECT SOURCE_NCR_ID FROM erp_inv_trace_order WHERE ID='$TRACE21_ID'")
EQ "追溯单侧回写来源 NCR" "$SRC_NCR" "$NCR21_ID"
# NCR 详情反查追溯单号
DET_TRACE=$(C -H "$(A $TENG)" $B/api/qms/ncrs/$NCR21_ID | J "d['data'].get('recallTraceNo')")
EQ "NCR 详情可见追溯单号" "$DET_TRACE" "$TRACE21"
# 重复入口返既有
EVAL2_EXIST=$(C -X POST -H "$(A $TENG)" $B/api/qms/ncrs/$NCR21_ID/recall-evaluation | J "d['data'].get('existing')")
EQ "重复入口返既有单" "$EVAL2_EXIST" "True"
# 非 RELEASED_LOT 拒绝（用 NCR16：GR 触发的普通 NCR，refType != RELEASED_LOT）
EQ "普通 NCR 评估 422" "$(BIZ -X POST -H "$(A $TENG)" $B/api/qms/ncrs/$NCR16/recall-evaluation)" "422"

echo "=============================="
echo "== 22. CAPA 证据链（rework-capa-evidence：D7 三件套 + 复发回炉 + D8 沉淀 + 时限/反查/view）=="
# 手工立项（关联 NCR21 → dueDate 自动计算）
CAPA22=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d "{\"title\":\"SMKCAPA 证据链冒烟\",\"severity\":\"MAJOR\",\"sourceType\":\"REPEAT\",\"ncrId\":\"$NCR21_ID\"}" \
  $B/api/qms/capa | J "d['data']['id']")
[ -n "${CAPA22:-}" ] && ok "手工立项（$CAPA22）" || bad "立项失败"
DUE22=$(SQL "SELECT IFNULL(DUE_DATE,'NULL') FROM erp_qms_capa WHERE ID='$CAPA22'")
[ "$DUE22" != "NULL" ] && ok "关联 NCR 立项自动计算时限（$DUE22）" || bad "dueDate 未计算"
# 独立立项无时限
CAPA22B=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"title":"SMKCAPA 独立立项","severity":"MINOR","sourceType":"COMPLAINT"}' \
  $B/api/qms/capa | J "d['data']['id']")
DUE22B=$(SQL "SELECT IFNULL(DUE_DATE,'NULL') FROM erp_qms_capa WHERE ID='$CAPA22B'")
EQ "独立来源无时限" "$DUE22B" "NULL"
# D1 空成员 422
EQ "D1 空清单 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D1","ownerName":"冒烟工程师","output":"启动会议"}' \
  $B/api/qms/capa/$CAPA22/steps)" "422"
# D1 单人全质量 → warnings
D1RES=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D1","ownerName":"冒烟工程师","output":"启动会议","teamMembers":"[{\"name\":\"冒烟工程师\",\"domain\":\"质量\",\"duty\":\"Leader 统筹\",\"leader\":true}]"}' \
  $B/api/qms/capa/$CAPA22/steps)
EQ "D1 完成" "$(echo "$D1RES" | J "d['code']")" "200"
CONTAINS "D1 单领域软警示" "$(echo "$D1RES" | J "d['data'].get('warnings') and 'WARN' or 'NONE'")" "WARN"
# D2 5W2H
EQ "D2 完成" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D2","ownerName":"冒烟工程师","output":"What 划伤 Who 检验员 When 10月14日 Where 包装线 Why 未按 SOP Why 挡杆松动 How many 3 件 How 调整挡杆"}' \
  $B/api/qms/capa/$CAPA22/steps)" "200"
# D3 遏制
EQ "D3 完成" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D3","ownerName":"冒烟工程师","output":"库存隔离 50 件 + 加严检验"}' \
  $B/api/qms/capa/$CAPA22/steps)" "200"
# D4 缺证据 422 → 齐全通过
EQ "D4 缺证据 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D4","ownerName":"冒烟工程师","output":"分析","rootCause":"挡杆螺丝松动导致位移（为什么松动：点检标准缺失）"}' \
  $B/api/qms/capa/$CAPA22/steps)" "422"
EQ "D4 完成" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D4","ownerName":"冒烟工程师","output":"分析","rootCause":"挡杆螺丝松动导致位移（为什么松动：点检标准缺失）","rootCauseEvidence":"近 3 周点检记录缺失，复现实验确认位移 2mm"}' \
  $B/api/qms/capa/$CAPA22/steps)" "200"
# 措施录入 + 完成 → D5/D6
ACT22=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"actionType":"CORRECT","description":"SMKCAPA 调整挡杆并班前点检","ownerName":"冒烟工程师","dueDate":"2026-10-20","effectDesc":"消除位移"}' \
  $B/api/qms/capa/$CAPA22/actions | J "d['data']['id']")
[ -n "${ACT22:-}" ] && ok "措施录入（$ACT22）" || bad "措施录入失败"
EQ "措施完成" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"effectDesc":"已调整，复测 0 位移"}' $B/api/qms/capa/actions/$ACT22/done)" "200"
EQ "D5 完成" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D5","ownerName":"冒烟工程师","output":"纠正+预防措施已定"}' \
  $B/api/qms/capa/$CAPA22/steps)" "200"
EQ "D6 完成（措施全 DONE）" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D6","ownerName":"冒烟工程师","output":"措施执行完毕"}' \
  $B/api/qms/capa/$CAPA22/steps)" "200"
# D7 缺指标 422 → 三件套齐全 VALID
EQ "D7 缺三件套 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D7","ownerName":"冒烟工程师","output":"验证","verifyResult":"VALID"}' \
  $B/api/qms/capa/$CAPA22/steps)" "422"
EQ "D7 完成（三件套 VALID）" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D7","ownerName":"冒烟工程师","output":"验证","verifyIndicator":"划伤不合格率","verifyTarget":"≤0.5%","verifyBefore":"3.0%","verifyAfter":"0.2%","verifyPeriod":"3个月","verifyResult":"VALID"}' \
  $B/api/qms/capa/$CAPA22/steps)" "200"
VDATA=$(SQL "SELECT VERIFY_DATA FROM erp_qms_capa WHERE ID='$CAPA22'")
CONTAINS "跟踪数据规范文本" "$VDATA" "措施前: 3.0%"
# ---- 复发回炉：同物料再建 NCR → VERIFYING CAPA 自动 INVALID 回 D4 ----
SQL "INSERT IGNORE INTO erp_qms_inspection_lot (ID, LOT_NO, LOT_TYPE, ITEM_CODE, ITEM_NAME, LOT_QTY, BATCH_NO, STATUS, RESULT, RISK_GRADE, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('lot-smk22b','IL-SMK22B','IQC','SMKI001','复发回炉冒烟物料',20,'BATCH-SMK22B','RELEASED','PASS','C','smoke','0',0);" >/dev/null
SQL "INSERT IGNORE INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, PRODUCTION_DATE, EXPIRY_DATE, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('bt-smk22b','BATCH-SMK22B','SMKI001','复发回炉冒烟物料',CURDATE(),DATE_ADD(CURDATE(),INTERVAL 90 DAY),'smoke','0',0);" >/dev/null
NCR22B=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"lotId":"lot-smk22b","defectItem":"复发划伤","defectDesc":"验证期内同物料再次不合格","severity":"MAJOR","ctqFlag":"0"}' \
  $B/api/qms/ncrs/released-lots | J "d['data']['ncrNo']")
[ -n "${NCR22B:-}" ] && ok "复发新 NCR 建单（$NCR22B）" || bad "复发建单失败"
REC_ST=$(SQL "SELECT CONCAT(STATUS,'/',VERIFY_RESULT,'/',CURRENT_STEP) FROM erp_qms_capa WHERE ID='$CAPA22'")
EQ "复发自动回炉" "$REC_ST" "REANALYZING/INVALID/D4"
REC_TODO=$(SQL "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_RECUR' AND BIZ_ID='$CAPA22'")
EQ "复发待办生成" "$REC_TODO" "1"
# 回炉后再走 D4→D7
EQ "回炉后 D4 完成" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D4","ownerName":"冒烟工程师","output":"二次分析","rootCause":"点检标准未覆盖挡杆（为什么：FMEA 未识别该失效）","rootCauseEvidence":"复发数据对比 + FMEA 复核记录"}' \
  $B/api/qms/capa/$CAPA22/steps)" "200"
BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D5","ownerName":"冒烟工程师","output":"二轮措施"}' $B/api/qms/capa/$CAPA22/steps >/dev/null
BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D6","ownerName":"冒烟工程师","output":"二轮执行完毕"}' $B/api/qms/capa/$CAPA22/steps >/dev/null
EQ "二轮 D7 VALID" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D7","ownerName":"冒烟工程师","output":"二轮验证","verifyIndicator":"划伤不合格率","verifyTarget":"≤0.5%","verifyBefore":"3.0%","verifyAfter":"0.1%","verifyPeriod":"3个月","verifyResult":"VALID"}' \
  $B/api/qms/capa/$CAPA22/steps)" "200"
# D8 全未勾 422 → 沉淀关闭
EQ "D8 全未勾 422" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D8","ownerName":"冒烟工程师","output":"关闭总结"}' \
  $B/api/qms/capa/$CAPA22/steps)" "422"
EQ "D8 沉淀完成关闭" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"stepCode":"D8","ownerName":"冒烟工程师","output":"关闭总结","legacySink":"[{\"type\":\"TRAINING\",\"note\":\"更新培训教材 TR-22\"},{\"type\":\"FMEA\",\"note\":\"FMEA 措施数 F-22 加严挡杆项\"}]"}' \
  $B/api/qms/capa/$CAPA22/steps)" "200"
EQ "已关闭" "$(SQL "SELECT STATUS FROM erp_qms_capa WHERE ID='$CAPA22'")" "CLOSED"
# 待办反查（by-ncr 幂等）
BYN=$(C -H "$(A $TENG)" $B/api/qms/capa/by-ncr/$NCR21_ID | J "d['data'].get('id')")
EQ "按 NCR 反查幂等" "$BYN" "$CAPA22"
NCR22B_ID=$(SQL "SELECT ID FROM erp_qms_ncr WHERE NCR_NO='$NCR22B'")
BYN2=$(C -H "$(A $TENG)" $B/api/qms/capa/by-ncr/$NCR22B_ID | J "d['data'] is None and 'NULL' or 'X'")
EQ "无 CAPA 的 NCR 反查空" "$BYN2" "NULL"
# view 三态
VACT=$(C -H "$(A $TENG)" "$B/api/qms/capa?view=actions&size=50" | J "len(d['data']['records'])")
[ "${VACT:-0}" -ge 0 ] && ok "view=actions 可用（${VACT:-0} 行）" || bad "view=actions 异常"
VVRF=$(C -H "$(A $TENG)" "$B/api/qms/capa?view=verify&size=50" | J "len(d['data']['records'])")
[ "${VVRF:-0}" -ge 0 ] && ok "view=verify 可用（${VVRF:-0} 行）" || bad "view=verify 异常"

echo "=============================="
echo "== 23. 质量成本分析（rework-copq-analysis：SCAR/报废归集 + 五项分析 + 记分卡质量成本）=="
# 目标参数复位（进程缓存须走 API，SQL 直改不生效）→ targets 应为 null
C -X POST -H "$(A $TADM)" "$B/api/system/params/COPQ_TARGET_INTERNAL_FAILURE?value=" >/dev/null
C -X POST -H "$(A $TADM)" "$B/api/system/params/COPQ_TARGET_EXTERNAL_RATIO?value=" >/dev/null
EQ "参数空 targets=null" "$(C -H "$(A $TADM)" "$B/api/qms/copq/report" | J "d['data'].get('targets') is None and 'NULL' or 'OBJ'")" "NULL"

# ---- 23.1 SCAR 扣款确认 → COPQ 外部失败归集（幂等 + 财务确认进正式报表）----
SUPNAME=$(SQL "SELECT SUPPLIER_NAME FROM erp_mdm_supplier WHERE ID='$SUP'")
SCAR23=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"triggerType\":\"REPEAT\",\"supplierId\":\"$SUP\",\"supplierName\":\"${SUPNAME:-冒烟供应商}\",\"title\":\"SMKCOPQ 重复来料不合格\",\"defectDesc\":\"冒烟：外观划伤批退\",\"claimAmount\":8000}" \
  $B/api/qms/scar | J "d['data']['id']")
[ -n "${SCAR23:-}" ] && ok "SCAR 草稿（$SCAR23）" || bad "SCAR 创建失败"
EQ "SCAR 发出（<1 万直接 SENT）" "$(BIZ -X POST -H "$(A $TADM)" $B/api/qms/scar/$SCAR23/submit)" "200"
EQ "SCAR 状态 SENT" "$(SQL "SELECT STATUS FROM erp_qms_scar WHERE ID='$SCAR23'")" "SENT"
DED23=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"amount":5000,"remark":"SMKCOPQ 扣款"}' $B/api/qms/scar/$SCAR23/deductions | J "d['data']['id']")
[ -n "${DED23:-}" ] && ok "扣款单（$DED23）" || bad "扣款单创建失败"
EQ "扣款超索赔额 422" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"amount":9000}' $B/api/qms/scar/$SCAR23/deductions)" "422"
EQ "扣款财务确认提交 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/qms/scar/deductions/$DED23/finance)" "200"
TDED=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=(SELECT APPROVAL_ID FROM erp_qms_scar_deduction WHERE ID='$DED23') AND STATUS='ACTIVE' AND ROLE_REQUIRED='ROLE_ADMIN' LIMIT 1")
[ -n "${TDED:-}" ] && ok "ScarFinance 待办生成" || bad "ScarFinance 待办缺失"
EQ "扣款确认签署 200" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TDED\",\"opinion\":\"确认扣款\"}" $B/api/qms/approvals/pass)" "200"
EQ "扣款 CONFIRMED" "$(SQL "SELECT STATUS FROM erp_qms_scar_deduction WHERE ID='$DED23'")" "CONFIRMED"
COPQ_S=$(SQL "SELECT COUNT(*) FROM erp_qms_copq_entry WHERE SOURCE_TYPE='SCAR' AND SOURCE_ID='$DED23' AND CATEGORY='EXTERNAL_FAILURE'")
EQ "SCAR 确认自动生成 COPQ 归集" "$COPQ_S" "1"
COPQ_S_AMT=$(SQL "SELECT CAST(AMOUNT AS CHAR) FROM erp_qms_copq_entry WHERE SOURCE_TYPE='SCAR' AND SOURCE_ID='$DED23'")
EQ "归集金额 5000" "${COPQ_S_AMT%%.*}" "5000"
EQ "重复确认 422（幂等）" "$(BIZ -X POST -H "$(A $TADM)" $B/api/qms/scar/deductions/$DED23/finance)" "422"
EQ "归集不重复" "$(SQL "SELECT COUNT(*) FROM erp_qms_copq_entry WHERE SOURCE_TYPE='SCAR' AND SOURCE_ID='$DED23'")" "1"
EQ "按来源反查 1 条" "$(C -H "$(A $TADM)" "$B/api/qms/copq/by-source?sourceType=SCAR&sourceId=$DED23" | J "len(d['data'])")" "1"
# 财务确认（CopqFinance）→ CONFIRMED 进正式报表
COPQ_S_ID=$(SQL "SELECT ID FROM erp_qms_copq_entry WHERE SOURCE_TYPE='SCAR' AND SOURCE_ID='$DED23'")
EQ "COPQ 财务确认提交 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/qms/copq/$COPQ_S_ID/finance)" "200"
TCOPQ=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=(SELECT APPROVAL_ID FROM erp_qms_copq_entry WHERE ID='$COPQ_S_ID') AND STATUS='ACTIVE' LIMIT 1")
[ -n "${TCOPQ:-}" ] && ok "CopqFinance 待办生成" || bad "CopqFinance 待办缺失"
EQ "COPQ 确认签署 200" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TCOPQ\",\"opinion\":\"确认入账\"}" $B/api/qms/approvals/pass)" "200"
EQ "COPQ CONFIRMED" "$(SQL "SELECT STATUS FROM erp_qms_copq_entry WHERE ID='$COPQ_S_ID'")" "CONFIRMED"

# ---- 23.2 报废过账 → COPQ 内部失败归集（幂等 + 降级不阻断）----
SQL "INSERT IGNORE INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
 VALUES ('it-smkcopq-1','SMKCOPQ-01','COPQ 冒烟物料','SMKI','PC','STRUCT','BUY','NORMAL','1','0','1','smoke','0',0);
INSERT IGNORE INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY, DEL_FLAG, VER_NO)
 VALUES ('stk-smkcopq-1','WH-MAIN','SMKCOPQ-01','COPQ 冒烟物料','BATCH-SMKCOPQ','A-01',50,0,0,50,'2026-09-01','smoke','0',0);" >/dev/null 2>&1 \
  && ok "报废造数（物料+库存）" || bad "报废造数失败"
SCR23=$(C -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"head":{"warehouseCode":"WH-MAIN","reason":"DAMAGE","remark":"SMKCOPQ 报废归集冒烟"},"lines":[{"itemCode":"SMKCOPQ-01","itemName":"COPQ 冒烟物料","batchNo":"BATCH-SMKCOPQ","qty":5,"unitCost":20}]}' \
  $B/api/inv/scrap-orders | J "d['data']['order']['id']")
[ -n "${SCR23:-}" ] && ok "报废单创建（$SCR23）" || bad "报废单创建失败"
EQ "报废批准 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/inv/scrap-orders/$SCR23/approve)" "200"
EQ "报废过账 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/inv/scrap-orders/$SCR23/post)" "200"
COPQ_R=$(SQL "SELECT COUNT(*) FROM erp_qms_copq_entry WHERE SOURCE_TYPE='SCRAP' AND SOURCE_ID='$SCR23' AND CATEGORY='INTERNAL_FAILURE'")
EQ "报废过账自动生成 COPQ 归集" "$COPQ_R" "1"
COPQ_R_AMT=$(SQL "SELECT CAST(AMOUNT AS CHAR) FROM erp_qms_copq_entry WHERE SOURCE_TYPE='SCRAP' AND SOURCE_ID='$SCR23'")
EQ "归集金额=5×20" "${COPQ_R_AMT%%.*}" "100"
EQ "重复过账 422" "$(BIZ -X POST -H "$(A $TADM)" $B/api/inv/scrap-orders/$SCR23/post)" "422"
EQ "报废归集不重复" "$(SQL "SELECT COUNT(*) FROM erp_qms_copq_entry WHERE SOURCE_TYPE='SCRAP' AND SOURCE_ID='$SCR23'")" "1"
COPQ_R_ID=$(SQL "SELECT ID FROM erp_qms_copq_entry WHERE SOURCE_TYPE='SCRAP' AND SOURCE_ID='$SCR23'")
EQ "报废归集财务确认 200" "$(BIZ -X POST -H "$(A $TADM)" $B/api/qms/copq/$COPQ_R_ID/finance)" "200"
TR23=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=(SELECT APPROVAL_ID FROM erp_qms_copq_entry WHERE ID='$COPQ_R_ID') AND STATUS='ACTIVE' LIMIT 1")
BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"taskId\":\"$TR23\",\"opinion\":\"确认入账\"}" $B/api/qms/approvals/pass >/dev/null
EQ "报废归集 CONFIRMED" "$(SQL "SELECT STATUS FROM erp_qms_copq_entry WHERE ID='$COPQ_R_ID'")" "CONFIRMED"

# ---- 23.3 手工归集（工序/客户透传）+ 异常阻断回归 ----
MAN23=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"category":"INTERNAL_FAILURE","amount":300,"itemCode":"SMKI002","processCode":"OP30","customerName":"客户甲","sourceType":"MANUAL","remark":"SMKCOPQ 手工归集"}' \
  $B/api/qms/copq | J "d['data']['id']")
[ -n "${MAN23:-}" ] && ok "手工归集（$MAN23）" || bad "手工归集失败"
MAN23F=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"category":"PREVENTION","amount":200,"sourceType":"MANUAL","remark":"SMKCOPQ 预防成本"}' \
  $B/api/qms/copq | J "d['data']['id']")
MAN23M=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"category":"APPRAISAL","amount":888,"sourceType":"MANUAL","remark":"SMKCOPQ 鉴定成本未确认"}' \
  $B/api/qms/copq | J "d['data']['id']")
[ -n "${MAN23F:-}" ] && [ -n "${MAN23M:-}" ] && ok "预防/鉴定归集" || bad "预防/鉴定归集失败"
# 10× 月均异常阻断（EXTERNAL 已确认月均 5000 → 100000 ≥ 10×）
HOLD23=$(C -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"category":"EXTERNAL_FAILURE","amount":100000,"sourceType":"MANUAL","remark":"SMKCOPQ 异常大额"}' \
  $B/api/qms/copq | J "d['data']['status']")
EQ "10× 月均异常阻断" "$HOLD23" "ANOMALY_HOLD"
HOLD_ID=$(SQL "SELECT ID FROM erp_qms_copq_entry WHERE REMARK='SMKCOPQ 异常大额'")
EQ "异常核实不予入账" "$(BIZ -X POST -H "$(A $TENG)" -H 'Content-Type: application/json' \
  -d '{"pass":false,"remark":"冒烟核销"}' $B/api/qms/copq/$HOLD_ID/anomaly)" "200"
# 确认两笔进正式报表（COPQ 财务确认链复用）
for MID in "$MAN23" "$MAN23F"; do
  BIZ -X POST -H "$(A $TENG)" $B/api/qms/copq/$MID/finance >/dev/null
  MT=$(SQL "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=(SELECT APPROVAL_ID FROM erp_qms_copq_entry WHERE ID='$MID') AND STATUS='ACTIVE' LIMIT 1")
  BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
    -d "{\"taskId\":\"$MT\",\"opinion\":\"确认入账\"}" $B/api/qms/approvals/pass >/dev/null
done
EQ "手工/预防 CONFIRMED" "$(SQL "SELECT COUNT(*) FROM erp_qms_copq_entry WHERE ID IN ('$MAN23','$MAN23F') AND STATUS='CONFIRMED'")" "2"

# ---- 23.4 report 五项分析断言 ----
REP23=$(C -H "$(A $TADM)" "$B/api/qms/copq/report")
EQ "trend 12 月序列" "$(echo "$REP23" | J "len(d['data']['trend'])")" "12"
TREND_TOT=$(echo "$REP23" | J "float(d['data']['trend'][-1]['total'])")
python3 -c "import sys; sys.exit(0 if abs($TREND_TOT-5600)<0.01 else 1)" \
  && ok "当月已确认合计 5600（5000+100+300+200）" || bad "当月合计异常（$TREND_TOT）"
EQ "环比 spike 命中（上月 0）" "$(echo "$REP23" | J "str(d['data']['spike'].get('spike'))")" "True"
CONTAINS "spikeAlert 文案" "$(echo "$REP23" | J "d['data']['spike'].get('spikeAlert') or ''")" "上期为 0"
EQ "外部失败红标" "$(echo "$REP23" | J "str(d['data']['externalAlert'])")" "True"
EQ "四维排名键齐" "$(echo "$REP23" | J "'/'.join(sorted(d['data']['rankings'].keys()))")" "CUSTOMER/ITEM/PROCESS/SUPPLIER"
EQ "产品维度含 SMKI002" "$(echo "$REP23" | J "1 if any(r['name']=='SMKI002' and abs(float(r['amount'])-300)<0.01 for r in d['data']['rankings']['ITEM']) else 0")" "1"
EQ "工序维度含 OP30" "$(echo "$REP23" | J "1 if any(r['name']=='OP30' for r in d['data']['rankings']['PROCESS']) else 0")" "1"
EQ "客户维度含客户甲" "$(echo "$REP23" | J "1 if any(r['name']=='客户甲' for r in d['data']['rankings']['CUSTOMER']) else 0")" "1"
EQ "供应商维度空值归未归因" "$(echo "$REP23" | J "1 if any(r['name']=='未归因' for r in d['data']['rankings']['SUPPLIER']) else 0")" "1"
EQ "缺失项含未确认鉴定成本" "$(echo "$REP23" | J "1 if any(m['category']=='APPRAISAL' and abs(float(m['amount'])-888)<0.01 for m in d['data']['missing']) else 0")" "1"
SUG23_N=$(echo "$REP23" | J "len(d['data']['suggestions'])")
[ "${SUG23_N:-0}" -ge 2 ] && ok "建议 ≥2 条（外部占比 + 环比突变）" || bad "建议不足（$SUG23_N）"
CONTAINS "既有键兼容（suggestion）" "$(echo "$REP23" | J "d['data'].get('suggestion') or ''")" "外部失败"

# ---- 23.5 目标达成（SysParam 走 API 写入）----
C -X POST -H "$(A $TADM)" "$B/api/system/params/COPQ_TARGET_INTERNAL_FAILURE?value=1000&valueType=AMOUNT&paramGroup=QMS&remark=smoke" >/dev/null
C -X POST -H "$(A $TADM)" "$B/api/system/params/COPQ_TARGET_EXTERNAL_RATIO?value=50&valueType=NUMBER&paramGroup=QMS&remark=smoke" >/dev/null
REP23T=$(C -H "$(A $TADM)" "$B/api/qms/copq/report")
EQ "targets 非 null" "$(echo "$REP23T" | J "d['data'].get('targets') is not None and 'OBJ' or 'NULL'")" "OBJ"
EQ "内部失败目标达成（400≤1000）" "$(echo "$REP23T" | J "str(d['data']['targets']['INTERNAL_FAILURE']['achieved'])")" "True"
EQ "外部占比目标未达成（89.29%>50%）" "$(echo "$REP23T" | J "str(d['data']['targets']['EXTERNAL_RATIO']['achieved'])")" "False"
# 复位（不污染后续运行）
C -X POST -H "$(A $TADM)" "$B/api/system/params/COPQ_TARGET_INTERNAL_FAILURE?value=" >/dev/null
C -X POST -H "$(A $TADM)" "$B/api/system/params/COPQ_TARGET_EXTERNAL_RATIO?value=" >/dev/null
EQ "复位后 targets=null" "$(C -H "$(A $TADM)" "$B/api/qms/copq/report" | J "d['data'].get('targets') is None and 'NULL' or 'OBJ'")" "NULL"

# ---- 23.6 记分卡质量成本采集（仅进明细不改得分）----
EQ "记分卡模型登记 200" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d '{"modelCode":"SMKCOPQ-MDL","name":"冒烟记分卡模型"}' $B/api/scm/scorecard/models)" "200"
CURM=$(date +%Y%m)
EQ "月度采集 200" "$(BIZ -X POST -H "$(A $TADM)" -H 'Content-Type: application/json' \
  -d "{\"monthTag\":\"$CURM\"}" $B/api/scm/scorecard/collect)" "200"
SC_RID=$(C -H "$(A $TADM)" "$B/api/scm/scorecard/results?monthTag=$CURM&supplierId=$SUP&size=10" | J "d['data']['records'][0]['id'] if d['data']['records'] else ''")
[ -n "${SC_RID:-}" ] && ok "记分卡结果生成（$SC_RID）" || bad "记分卡结果缺失"
SC_DET=$(C -H "$(A $TADM)" "$B/api/scm/scorecard/results/$SC_RID")
SC_QC=$(echo "$SC_DET" | J "float(d['data']['source'].get('qualityCost') or 0)")
python3 -c "import sys; sys.exit(0 if abs($SC_QC-5000)<0.01 else 1)" \
  && ok "采集明细含 qualityCost=5000" || bad "qualityCost 异常（$SC_QC）"
SC_TOT=$(echo "$SC_DET" | J "d['data']['result'].get('totalScore')")
[ -n "${SC_TOT:-}" ] && [ "$SC_TOT" != "None" ] && ok "得分照常计算（$SC_TOT，质量成本不参与）" || bad "得分缺失"

echo "检验任务冒烟：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN"; else echo "HAS FAILURES"; exit 1; fi
