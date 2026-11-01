<template>
  <div>
    <el-page-header content="数据抽取（14.2）— 抽取配置/脱敏规则/质量校验三叶；波动暂停（BR-4.10-12）、断点续抽（BR-4.10-13）、质量分级阻断（BR-4.10-14）、schema 变更检测（BR-4.10-15）"
                    style="margin-bottom: 8px" />

    <el-tabs v-model="tab">
      <!-- ==================== 14.2.1 抽取配置 ==================== -->
      <el-tab-pane label="抽取配置" name="extract">
        <el-alert v-if="ready.ready !== undefined" :type="ready.ready ? 'success' : 'warning'"
                  :title="ready.ready ? `数据就绪（批次 ${ready.batchNo}，${ready.readyAt || ''}）`
                    : `数据待就绪（最近批次 ${ready.batchNo || '—'}：${ready.status || '无'}）`"
                  show-icon :closable="false" style="margin-bottom: 8px" />

        <h4>数据源（连接加密存储，schema 快照检测变更）</h4>
        <div class="toolbar">
          <el-button size="small" type="success" @click="openDsEdit(null)">新建数据源</el-button>
          <el-button size="small" @click="loadDs">刷新</el-button>
        </div>
        <el-table :data="datasources" border stripe size="small" max-height="220">
          <el-table-column prop="name" label="名称" width="140" />
          <el-table-column prop="type" label="类型" width="70" align="center" />
          <el-table-column prop="dbName" label="库名" width="110" />
          <el-table-column prop="connMasked" label="连接" width="110" show-overflow-tooltip />
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success'
                : row.status === 'SCHEMA_CHANGED' ? 'danger' : 'info'" size="small">
                {{ dsStatus(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="snapshotAt" label="快照时间" width="150" />
          <el-table-column label="操作" width="260" align="center">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="openDsEdit(row)">编辑</el-button>
              <el-button size="small" type="primary" link @click="onTestConn(row)">连通测试</el-button>
              <el-button size="small" type="warning" link @click="onCheckSchema(row)">结构比对</el-button>
              <el-button size="small" type="success" link @click="onRefreshSnap(row)">更新快照</el-button>
              <el-button size="small" type="danger" link @click="removeDs(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <h4 style="margin-top: 14px">抽取配置（绑定已发布模板；全量/增量 + 水位 + DAG 层序）</h4>
        <div class="toolbar">
          <el-select v-model="cfgDsFilter" placeholder="按数据源过滤" size="small" clearable
                     style="width: 180px" @change="loadConfigs">
            <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-button size="small" type="success" @click="openCfgEdit(null)">新建配置</el-button>
        </div>
        <el-table :data="configs" border stripe size="small" max-height="220">
          <el-table-column prop="code" label="编码" width="120" />
          <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
          <el-table-column label="策略" width="80" align="center">
            <template #default="{ row }">{{ row.mode === 'INCREMENTAL' ? '增量' : '全量' }}</template>
          </el-table-column>
          <el-table-column prop="watermarkField" label="水位字段" width="110" />
          <el-table-column prop="lastWatermark" label="当前水位" width="110" />
          <el-table-column prop="seq" label="层序" width="60" align="center" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                {{ row.status === 'ENABLED' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" align="center">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="openCfgEdit(row)">编辑</el-button>
              <el-button size="small" type="primary" link @click="toggleCfg(row)">
                {{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
              <el-button size="small" type="danger" link @click="removeCfg(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <h4 style="margin-top: 14px">批次运行（01:00-05:00 窗口；手动补跑绕过）</h4>
        <div class="toolbar">
          <el-date-picker v-model="runDate" type="date" value-format="YYYY-MM-DD" size="small"
                          style="width: 150px" @change="loadRuns" />
          <el-button size="small" type="primary" @click="onRunBatch">执行/推进批次</el-button>
          <el-button size="small" @click="loadRuns">刷新</el-button>
        </div>
        <el-table :data="runs" border stripe size="small" max-height="260">
          <el-table-column prop="configId" label="配置" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ cfgName(row.configId) }}</template>
          </el-table-column>
          <el-table-column prop="seq" label="层序" width="60" align="center" />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="runTag(row.status)" size="small">{{ runStatus(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="rowCount" label="行数" width="90" align="right" />
          <el-table-column prop="prevRowCount" label="前次行数" width="90" align="right" />
          <el-table-column prop="watermark" label="水位" width="100" />
          <el-table-column prop="retry" label="重试" width="60" align="center" />
          <el-table-column prop="elapsedMs" label="耗时ms" width="80" align="right" />
          <el-table-column prop="errorMsg" label="异常" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="150" align="center">
            <template #default="{ row }">
              <template v-if="row.status === 'PAUSED'">
                <el-button size="small" type="success" link @click="onConfirm(row)">确认继续</el-button>
                <el-button size="small" type="danger" link @click="onAbort(row)">确认放弃</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>

        <el-dialog v-model="dsVisible" :title="dsId ? '编辑数据源' : '新建数据源'" width="560px"
                   :close-on-click-modal="false">
          <el-form label-width="100px" size="small">
            <el-form-item label="名称" required>
              <el-input v-model="dsForm.name" :disabled="!!dsId" placeholder="唯一名称" />
            </el-form-item>
            <el-form-item label="类型">
              <el-select v-model="dsForm.type" style="width: 160px">
                <el-option label="数据库 DB" value="DB" />
                <el-option label="API（登记形态）" value="API" />
                <el-option label="文件 FILE（登记形态）" value="FILE" />
              </el-select>
            </el-form-item>
            <el-form-item label="逻辑库名">
              <el-input v-model="dsForm.dbName" placeholder="DB 类型登记；空 = 本库" />
            </el-form-item>
            <el-form-item label="连接信息">
              <el-input v-model="dsForm.conn" type="password" show-password
                        placeholder="加密存储，明文不出接口（FR-4.10-2-1）" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="dsForm.remark" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button size="small" @click="dsVisible = false">取消</el-button>
            <el-button size="small" type="primary" @click="saveDs">保存</el-button>
          </template>
        </el-dialog>

        <el-dialog v-model="cfgVisible" :title="cfgId ? '编辑抽取配置' : '新建抽取配置'" width="600px"
                   :close-on-click-modal="false">
          <el-form label-width="100px" size="small">
            <el-form-item label="编码" required>
              <el-input v-model="cfgForm.code" :disabled="!!cfgId" />
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="cfgForm.name" />
            </el-form-item>
            <el-form-item label="数据源" required>
              <el-select v-model="cfgForm.datasourceId" style="width: 220px">
                <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="报表模板" required>
              <el-select v-model="cfgForm.templateId" filterable style="width: 300px"
                         placeholder="仅已发布模板">
                <el-option v-for="t in publishedTemplates" :key="t.id"
                           :label="`${t.code} ${t.name}`" :value="t.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="抽取策略">
              <el-radio-group v-model="cfgForm.mode">
                <el-radio label="FULL">全量</el-radio>
                <el-radio label="INCREMENTAL">增量</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="cfgForm.mode === 'INCREMENTAL'" label="水位字段" required>
              <el-input v-model="cfgForm.watermarkField" placeholder="须在模板列清单内" />
            </el-form-item>
            <el-form-item label="DAG 层序">
              <el-input-number v-model="cfgForm.seq" :min="10" :step="10" size="small" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button size="small" @click="cfgVisible = false">取消</el-button>
            <el-button size="small" type="primary" @click="saveCfg">保存</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ==================== 14.2.2 脱敏规则 ==================== -->
      <el-tab-pane label="脱敏规则" name="mask">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 8px"
                  title="类型化掩码（PHONE 前3后4 / ID_CARD 前3后4 / SALARY *** / BANK 尾4 / GENERIC 首尾）；变更须数据治理专员审批后生效（仅 ENABLED 参与查询层判定）" />
        <div class="toolbar">
          <el-select v-model="maskTypeFilter" placeholder="规则类型" size="small" clearable
                     style="width: 170px" @change="loadMaskRules">
            <el-option label="列掩码 COL_MASK" value="COL_MASK" />
            <el-option label="敏感登记 COL_SENSITIVE" value="COL_SENSITIVE" />
          </el-select>
          <el-button size="small" type="success" @click="openMaskEdit(null)">新建规则</el-button>
        </div>
        <el-table :data="maskRules" border stripe size="small" max-height="380">
          <el-table-column prop="targetKey" label="目标字段" width="160" />
          <el-table-column label="类型" width="130" align="center">
            <template #default="{ row }">
              {{ row.ruleType === 'COL_MASK' ? '列掩码' : '敏感登记' }}
            </template>
          </el-table-column>
          <el-table-column label="样式" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small">{{ row.maskStyle }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="roleCode" label="适用角色" width="150">
            <template #default="{ row }">{{ row.roleCode || '全体' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="maskTag(row.status)" size="small">{{ maskStatus(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="updateBy" label="更新人" width="110" />
          <el-table-column label="操作" width="220" align="center">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="openMaskEdit(row)">编辑</el-button>
              <el-button v-if="row.status === 'DRAFT' || row.status === 'DISABLED'" size="small"
                        type="warning" link @click="onMaskSubmit(row)">提交审批</el-button>
              <el-button v-if="row.status === 'ENABLED'" size="small" type="warning" link
                        @click="onMaskDisable(row)">停用</el-button>
              <el-button size="small" type="primary" link @click="openMaskLogs(row)">历史</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-dialog v-model="maskVisible" :title="maskId ? '编辑规则（仅草稿）' : '新建规则'"
                   width="520px" :close-on-click-modal="false">
          <el-form label-width="100px" size="small">
            <el-form-item label="目标字段" required>
              <el-input v-model="maskForm.targetKey" :disabled="!!maskId"
                        placeholder="字段名或通配 *" />
            </el-form-item>
            <el-form-item label="类型">
              <el-select v-model="maskForm.ruleType" :disabled="!!maskId" style="width: 200px">
                <el-option label="列掩码 COL_MASK" value="COL_MASK" />
                <el-option label="敏感登记 COL_SENSITIVE" value="COL_SENSITIVE" />
              </el-select>
            </el-form-item>
            <el-form-item label="掩码样式">
              <el-select v-model="maskForm.maskStyle" style="width: 200px">
                <el-option label="GENERIC 首尾保留" value="GENERIC" />
                <el-option label="PHONE 前3后4" value="PHONE" />
                <el-option label="ID_CARD 前3后4" value="ID_CARD" />
                <el-option label="SALARY 固定掩码" value="SALARY" />
                <el-option label="BANK 保留后4位" value="BANK" />
              </el-select>
              <div class="mask-preview">预览：{{ maskPreview }}</div>
            </el-form-item>
            <el-form-item label="适用角色">
              <el-input v-model="maskForm.roleCode" placeholder="空 = 全体用户" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="maskForm.remark" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button size="small" @click="maskVisible = false">取消</el-button>
            <el-button size="small" type="primary" @click="saveMask">保存</el-button>
          </template>
        </el-dialog>

        <el-drawer v-model="logsVisible" title="变更历史（审计只增）" size="420px">
          <el-table :data="maskLogs" border stripe size="small">
            <el-table-column prop="action" label="动作" width="110" />
            <el-table-column prop="oldVal" label="旧值" width="120" show-overflow-tooltip />
            <el-table-column prop="newVal" label="新值" width="120" show-overflow-tooltip />
            <el-table-column prop="operator" label="操作人" width="100" />
            <el-table-column prop="opAt" label="时间" width="150" />
          </el-table>
        </el-drawer>
      </el-tab-pane>

      <!-- ==================== 14.2.3 质量校验 ==================== -->
      <el-tab-pane label="质量校验" name="quality">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 8px"
                  title="NOT_NULL 非空率（默认≥99.9%）/ RECONCILE 跨表勾稽+容差 / RANGE 数值范围；SEVERE 失败阻断批次就绪并告警，WARN 告警放行（BR-4.10-14）" />
        <div class="toolbar">
          <el-button size="small" type="success" @click="openRuleEdit(null)">新建规则</el-button>
          <el-button size="small" @click="loadQualityRules">刷新</el-button>
        </div>
        <el-table :data="qRules" border stripe size="small" max-height="260">
          <el-table-column prop="name" label="规则名" min-width="150" show-overflow-tooltip />
          <el-table-column label="类型" width="100" align="center">
            <template #default="{ row }">{{ qType(row.ruleType) }}</template>
          </el-table-column>
          <el-table-column label="目标" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.ruleType === 'RECONCILE' ? `${row.leftSql} vs ${row.rightSql}`
                : `${row.targetTable}.${row.targetColumn}` }}
            </template>
          </el-table-column>
          <el-table-column label="阈值/容差" width="120" align="center">
            <template #default="{ row }">
              {{ row.ruleType === 'NOT_NULL' ? `≥${row.notNullMin}%`
                : row.ruleType === 'RECONCILE' ? `±${row.tolerance}`
                : `${row.minVal ?? '-∞'} ~ ${row.maxVal ?? '+∞'}` }}
            </template>
          </el-table-column>
          <el-table-column label="级别" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.severeLevel === 'SEVERE' ? 'danger' : 'warning'" size="small">
                {{ row.severeLevel }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                {{ row.status === 'ENABLED' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" align="center">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="onRunNow(row)">立即校验</el-button>
              <el-button size="small" type="primary" link @click="openRuleEdit(row)">编辑</el-button>
              <el-button size="small" type="primary" link @click="toggleRule(row)">
                {{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
              <el-button size="small" type="danger" link @click="removeRule(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <h4 style="margin-top: 14px">校验报告</h4>
        <div class="toolbar">
          <el-input v-model="resBatchNo" placeholder="批次号" size="small" clearable
                    style="width: 160px" @keyup.enter="loadResults" />
          <el-button size="small" type="primary" @click="loadResults">查询</el-button>
          <el-button v-if="lastBlockedBatch" size="small" type="warning" @click="onRecheck">
            复验阻断批次 {{ lastBlockedBatch }}（通过自动就绪）
          </el-button>
        </div>
        <el-table :data="results" border stripe size="small" max-height="300">
          <el-table-column prop="batchNo" label="批次" width="110">
            <template #default="{ row }">{{ row.batchNo || '（手动）' }}</template>
          </el-table-column>
          <el-table-column prop="ruleName" label="规则" min-width="140" show-overflow-tooltip />
          <el-table-column label="类型" width="90" align="center">
            <template #default="{ row }">{{ qType(row.ruleType) }}</template>
          </el-table-column>
          <el-table-column prop="expected" label="期望" width="150" show-overflow-tooltip />
          <el-table-column prop="actual" label="实际" min-width="160" show-overflow-tooltip />
          <el-table-column label="结论" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.conclusion === 'PASS' ? 'success' : 'danger'" size="small">
                {{ row.conclusion }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="severeLevel" label="级别" width="80" align="center" />
          <el-table-column prop="execAt" label="执行时间" width="150" />
        </el-table>

        <el-dialog v-model="ruleVisible" :title="ruleId ? '编辑质量规则' : '新建质量规则'" width="620px"
                   :close-on-click-modal="false">
          <el-form label-width="100px" size="small">
            <el-form-item label="规则名" required>
              <el-input v-model="ruleForm.name" />
            </el-form-item>
            <el-form-item label="类型">
              <el-select v-model="ruleForm.ruleType" :disabled="!!ruleId" style="width: 200px">
                <el-option label="NOT_NULL 非空率" value="NOT_NULL" />
                <el-option label="RECONCILE 跨表勾稽" value="RECONCILE" />
                <el-option label="RANGE 数值范围" value="RANGE" />
              </el-select>
            </el-form-item>
            <template v-if="ruleForm.ruleType !== 'RECONCILE'">
              <el-form-item label="目标表" required>
                <el-input v-model="ruleForm.targetTable" placeholder="仅字母数字下划线" />
              </el-form-item>
              <el-form-item label="目标字段" required>
                <el-input v-model="ruleForm.targetColumn" />
              </el-form-item>
            </template>
            <el-form-item v-if="ruleForm.ruleType === 'NOT_NULL'" label="非空率阈值">
              <el-input-number v-model="ruleForm.notNullMin" :min="0.001" :max="100"
                               :step="0.1" :precision="3" size="small" />
              <span class="mask-preview">（默认 99.9%）</span>
            </el-form-item>
            <template v-if="ruleForm.ruleType === 'RECONCILE'">
              <el-form-item label="左侧 SQL" required>
                <el-input v-model="ruleForm.leftSql" placeholder="SELECT 合计（只读白名单）" />
              </el-form-item>
              <el-form-item label="右侧 SQL" required>
                <el-input v-model="ruleForm.rightSql" />
              </el-form-item>
              <el-form-item label="容差">
                <el-input-number v-model="ruleForm.tolerance" :min="0" :precision="4"
                                 size="small" />
              </el-form-item>
            </template>
            <template v-if="ruleForm.ruleType === 'RANGE'">
              <el-form-item label="下限">
                <el-input-number v-model="ruleForm.minVal" :precision="4" size="small" />
              </el-form-item>
              <el-form-item label="上限">
                <el-input-number v-model="ruleForm.maxVal" :precision="4" size="small" />
              </el-form-item>
            </template>
            <el-form-item label="严重级别">
              <el-radio-group v-model="ruleForm.severeLevel">
                <el-radio label="SEVERE">SEVERE（阻断就绪）</el-radio>
                <el-radio label="WARN">WARN（告警放行）</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="所属数据源">
              <el-select v-model="ruleForm.datasourceId" clearable style="width: 220px"
                         placeholder="批次自动校验归集范围">
                <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button size="small" @click="ruleVisible = false">取消</el-button>
            <el-button size="small" type="primary" @click="saveRule">保存</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDatasourcesApi, createDatasourceApi, updateDatasourceApi, deleteDatasourceApi,
  testConnectionApi, checkSchemaApi, refreshSnapshotApi,
  getExtractConfigsApi, createExtractConfigApi, updateExtractConfigApi, deleteExtractConfigApi,
  getRunsApi, runBatchApi, confirmRunApi, abortRunApi, getReadyApi,
  getMaskRulesApi, createMaskRuleApi, updateMaskRuleApi, disableMaskRuleApi,
  submitMaskRuleApi, getMaskRuleLogsApi,
  getQualityRulesApi, createQualityRuleApi, updateQualityRuleApi, deleteQualityRuleApi,
  runQualityNowApi, recheckBatchApi, getQualityResultsApi
} from '@/api/bi/data-extract'
import { getTemplatesApi } from '@/api/bi/report-template'

const tab = ref('extract')

// ==================== 14.2.1 抽取配置 ====================
const datasources = ref([])
const configs = ref([])
const runs = ref([])
const ready = ref({})
const runDate = ref(new Date().toISOString().slice(0, 10))
const cfgDsFilter = ref('')
const publishedTemplates = ref([])

const dsVisible = ref(false)
const dsId = ref('')
const dsForm = ref({ name: '', type: 'DB', dbName: '', conn: '', remark: '' })
const cfgVisible = ref(false)
const cfgId = ref('')
const cfgForm = ref({})

async function loadDs() {
  const res = await getDatasourcesApi()
  datasources.value = res.data || []
}
async function loadConfigs() {
  const res = await getExtractConfigsApi(cfgDsFilter.value || undefined)
  configs.value = res.data || []
}
async function loadRuns() {
  const res = await getRunsApi(runDate.value)
  runs.value = res.data || []
}
async function loadReady() {
  const res = await getReadyApi()
  ready.value = res.data || {}
}
async function loadTemplates() {
  const res = await getTemplatesApi({ status: 'PUBLISHED' })
  publishedTemplates.value = res.data || []
}

function openDsEdit(row) {
  dsId.value = row ? row.id : ''
  dsForm.value = row
    ? { name: row.name, type: row.type, dbName: row.dbName || '', conn: '', remark: row.remark || '' }
    : { name: '', type: 'DB', dbName: '', conn: '', remark: '' }
  dsVisible.value = true
}
async function saveDs() {
  const payload = { ...dsForm.value }
  if (!payload.conn) delete payload.conn
  if (dsId.value) {
    await updateDatasourceApi(dsId.value, payload)
  } else {
    await createDatasourceApi(payload)
  }
  ElMessage.success('已保存')
  dsVisible.value = false
  loadDs()
}
async function removeDs(row) {
  await ElMessageBox.confirm(`删除数据源「${row.name}」？`, '提示', { type: 'warning' })
  await deleteDatasourceApi(row.id)
  ElMessage.success('已删除')
  loadDs()
}
async function onTestConn(row) {
  const res = await testConnectionApi(row.id)
  ElMessage({ type: res.data.ok ? 'success' : 'error', message: res.data.msg })
}
async function onCheckSchema(row) {
  const res = await checkSchemaApi(row.id)
  if (res.data.changed) {
    ElMessage.warning(`检测到结构变更 ${res.data.diff.length} 处，其下配置已暂停自动执行`)
  } else {
    ElMessage.success('结构一致')
  }
  loadDs()
}
async function onRefreshSnap(row) {
  await refreshSnapshotApi(row.id)
  ElMessage.success('快照已更新，数据源恢复 ACTIVE')
  loadDs()
}

function openCfgEdit(row) {
  cfgId.value = row ? row.id : ''
  cfgForm.value = row
    ? { code: row.code, name: row.name, datasourceId: row.datasourceId, templateId: row.templateId,
        mode: row.mode, watermarkField: row.watermarkField || '', seq: row.seq }
    : { code: '', name: '', datasourceId: '', templateId: '', mode: 'FULL',
        watermarkField: '', seq: 10 }
  cfgVisible.value = true
}
async function saveCfg() {
  const payload = { ...cfgForm.value }
  if (cfgId.value) {
    delete payload.code
    await updateExtractConfigApi(cfgId.value, payload)
  } else {
    await createExtractConfigApi(payload)
  }
  ElMessage.success('已保存')
  cfgVisible.value = false
  loadConfigs()
}
async function toggleCfg(row) {
  await updateExtractConfigApi(row.id, { status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' })
  loadConfigs()
}
async function removeCfg(row) {
  await ElMessageBox.confirm(`删除配置「${row.name}」？有执行记录时将拒绝删除。`, '提示', { type: 'warning' })
  await deleteExtractConfigApi(row.id)
  ElMessage.success('已删除')
  loadConfigs()
}

async function onRunBatch() {
  const res = await runBatchApi(runDate.value, true)
  const d = res.data
  if (d.skipped) {
    ElMessage.info('窗口外自动调度不启动，请使用手动补跑')
  } else {
    ElMessage.success(`批次推进：${d.batchStatus || d.note}（执行 ${d.executed ?? 0} 个任务）`)
  }
  loadRuns(); loadReady()
}
async function onConfirm(row) {
  await confirmRunApi(row.id)
  ElMessage.success('已确认继续，重新执行批次推进')
  onRunBatch()
}
async function onAbort(row) {
  await ElMessageBox.confirm('放弃该暂停任务？将终止并留痕。', '提示', { type: 'warning' })
  await abortRunApi(row.id)
  ElMessage.success('已终止')
  loadRuns()
}

function cfgName(id) {
  const c = configs.value.find(x => x.id === id)
  return c ? c.code : id
}
function dsStatus(s) {
  return { ACTIVE: '正常', SCHEMA_CHANGED: '结构变更', DISABLED: '停用' }[s] || s
}
function runStatus(s) {
  return { PENDING: '待执行', RUNNING: '执行中', READY: '完成', PAUSED: '波动暂停', FAILED: '失败' }[s] || s
}
function runTag(s) {
  return { PENDING: 'info', RUNNING: 'primary', READY: 'success', PAUSED: 'warning', FAILED: 'danger' }[s] || 'info'
}

// ==================== 14.2.2 脱敏规则 ====================
const maskRules = ref([])
const maskTypeFilter = ref('')
const maskVisible = ref(false)
const maskId = ref('')
const maskForm = ref({})
const logsVisible = ref(false)
const maskLogs = ref([])

const PREVIEWS = {
  GENERIC: 'a***g（首尾保留）',
  PHONE: '138****1234（前3后4）',
  ID_CARD: '110****1234（前3后4）',
  SALARY: '***（固定掩码）',
  BANK: '****6789（保留后4位）'
}
const maskPreview = computed(() => PREVIEWS[maskForm.value.maskStyle] || '')

async function loadMaskRules() {
  const res = await getMaskRulesApi(maskTypeFilter.value || undefined)
  maskRules.value = res.data || []
}
function openMaskEdit(row) {
  maskId.value = row ? row.id : ''
  maskForm.value = row
    ? { targetKey: row.targetKey, ruleType: row.ruleType, maskStyle: row.maskStyle,
        roleCode: row.roleCode || '', remark: row.remark || '' }
    : { targetKey: '', ruleType: 'COL_MASK', maskStyle: 'GENERIC', roleCode: '', remark: '' }
  maskVisible.value = true
}
async function saveMask() {
  const payload = { ...maskForm.value }
  if (maskId.value) {
    await updateMaskRuleApi(maskId.value, { maskStyle: payload.maskStyle,
      roleCode: payload.roleCode, targetKey: payload.targetKey, remark: payload.remark })
  } else {
    await createMaskRuleApi(payload)
  }
  ElMessage.success('已保存（草稿态，提交审批后生效）')
  maskVisible.value = false
  loadMaskRules()
}
async function onMaskSubmit(row) {
  await ElMessageBox.confirm(`提交「${row.targetKey}（${row.maskStyle}）」至数据治理专员审批？`,
    '提交审批', { type: 'info' })
  await submitMaskRuleApi(row.id)
  ElMessage.success('已提交，等待数据治理专员单签')
  loadMaskRules()
}
async function onMaskDisable(row) {
  await ElMessageBox.confirm(`停用「${row.targetKey}」？停用后查询层立即不再掩码该字段。`,
    '停用', { type: 'warning' })
  await disableMaskRuleApi(row.id)
  ElMessage.success('已停用')
  loadMaskRules()
}
async function openMaskLogs(row) {
  const res = await getMaskRuleLogsApi(row.id)
  maskLogs.value = res.data || []
  logsVisible.value = true
}
function maskStatus(s) {
  return { DRAFT: '草稿', PENDING_APPROVAL: '审批中', ENABLED: '已生效', DISABLED: '已停用' }[s] || s
}
function maskTag(s) {
  return { DRAFT: 'info', PENDING_APPROVAL: 'warning', ENABLED: 'success', DISABLED: 'info' }[s] || 'info'
}

// ==================== 14.2.3 质量校验 ====================
const qRules = ref([])
const results = ref([])
const resBatchNo = ref('')
const lastBlockedBatch = ref('')
const ruleVisible = ref(false)
const ruleId = ref('')
const ruleForm = ref({})

async function loadQualityRules() {
  const res = await getQualityRulesApi()
  qRules.value = res.data || []
}
async function loadResults() {
  const res = await getQualityResultsApi({ batchNo: resBatchNo.value || undefined })
  results.value = res.data || []
}
function openRuleEdit(row) {
  ruleId.value = row ? row.id : ''
  ruleForm.value = row
    ? { name: row.name, ruleType: row.ruleType, targetTable: row.targetTable || '',
        targetColumn: row.targetColumn || '', leftSql: row.leftSql || '', rightSql: row.rightSql || '',
        notNullMin: Number(row.notNullMin ?? 99.9), tolerance: Number(row.tolerance ?? 0),
        minVal: row.minVal === null || row.minVal === undefined ? undefined : Number(row.minVal),
        maxVal: row.maxVal === null || row.maxVal === undefined ? undefined : Number(row.maxVal),
        severeLevel: row.severeLevel, datasourceId: row.datasourceId || '' }
    : { name: '', ruleType: 'NOT_NULL', targetTable: '', targetColumn: '', leftSql: '',
        rightSql: '', notNullMin: 99.9, tolerance: 0, severeLevel: 'SEVERE', datasourceId: '' }
  ruleVisible.value = true
}
async function saveRule() {
  const payload = { ...ruleForm.value }
  for (const k of ['minVal', 'maxVal']) {
    if (payload[k] === undefined || payload[k] === '') delete payload[k]
  }
  if (ruleId.value) {
    await updateQualityRuleApi(ruleId.value, payload)
  } else {
    await createQualityRuleApi(payload)
  }
  ElMessage.success('已保存')
  ruleVisible.value = false
  loadQualityRules()
}
async function toggleRule(row) {
  await updateQualityRuleApi(row.id, { status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' })
  loadQualityRules()
}
async function removeRule(row) {
  await ElMessageBox.confirm(`删除规则「${row.name}」？有报告时将拒绝删除。`, '提示', { type: 'warning' })
  await deleteQualityRuleApi(row.id)
  ElMessage.success('已删除')
  loadQualityRules()
}
async function onRunNow(row) {
  const res = await runQualityNowApi(row.id)
  const d = res.data
  ElMessage({ type: d.conclusion === 'PASS' ? 'success' : 'error',
    message: `${d.conclusion}：期望 ${d.expected} / 实际 ${d.actual}` })
  if (d.conclusion === 'FAIL' && d.severeLevel === 'SEVERE') {
    ElMessage.warning('SEVERE 级失败：如属某批次，批次将保持质量阻断，修复后请复验')
  }
  loadResults()
}
async function onRecheck() {
  const res = await recheckBatchApi(lastBlockedBatch.value)
  if (res.data.status === 'READY') {
    ElMessage.success('复验通过，批次已解除阻断置就绪')
  } else {
    ElMessage.warning(`仍存在 SEVERE 失败 ${res.data.severeFail} 条：${res.data.status}`)
  }
  loadResults()
}
function qType(t) {
  return { NOT_NULL: '非空率', RECONCILE: '跨表勾稽', RANGE: '数值范围' }[t] || t
}

onMounted(() => {
  loadDs(); loadConfigs(); loadRuns(); loadReady(); loadTemplates()
  loadMaskRules(); loadQualityRules(); loadResults()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap; }
h4 { margin: 4px 0 8px; }
.mask-preview { color: #909399; font-size: 12px; margin-left: 8px; }
</style>
