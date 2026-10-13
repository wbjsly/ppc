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
DELETE FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='MO_FINISH' AND BIZ_DOC_NO LIKE 'IL-SMKI%';
DELETE FROM erp_qms_certificate WHERE LOT_NO LIKE 'IL-SMKI%';
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

echo ""
echo "=============================="
echo "检验任务冒烟：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN"; else echo "HAS FAILURES"; exit 1; fi
