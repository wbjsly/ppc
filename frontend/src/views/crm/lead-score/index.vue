<template>
  <div class="lead-score-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="线索评分（11.1.2）"
      description="五维加权：需求 30% / 预算 25% / 决策链 20% / 紧迫 15% / 竞争 10%；每维 1-5 分，缺项按 0 分计并提示。等级 A≥80、B 60-79、C 40-59、D<40，D 级自动入线索池。"
    />

    <el-tabs v-model="tab">
      <!-- ==================== 评分与等级看板 ==================== -->
      <el-tab-pane label="评分与看板" name="board">
        <!-- 等级看板 -->
        <el-row :gutter="12" style="margin-bottom: 14px;">
          <el-col v-for="c in cards" :key="c.grade" :span="6">
            <el-card shadow="hover">
              <div style="display: flex; justify-content: space-between; align-items: center;">
                <div>
                  <div style="color: #909399; font-size: 13px;">{{ c.label }}</div>
                  <div style="font-size: 26px; font-weight: bold;">{{ c.count }}</div>
                </div>
                <el-tag :type="c.type" size="large">{{ c.grade || '未评' }}</el-tag>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <el-card>
          <template #header>
            <div class="card-header">
              <span style="font-weight: bold; font-size: 16px;">待评分 / 已评分线索</span>
              <div class="header-actions">
                <el-select v-model="query.grade" placeholder="等级" clearable style="width: 110px;">
                  <el-option label="未评分" value="UNSCORED" />
                  <el-option v-for="g in ['A','B','C','D']" :key="g" :label="g" :value="g" />
                </el-select>
                <el-button type="primary" @click="loadData(1)">查询</el-button>
              </div>
            </div>
          </template>

          <el-table :data="rows" v-loading="loading" stripe>
            <el-table-column prop="leadNo" label="线索编号" width="150" />
            <el-table-column prop="companyName" label="客户名称" min-width="150" show-overflow-tooltip />
            <el-table-column prop="contactName" label="联系人" width="90" />
            <el-table-column label="五维（需/预/链/紧/竞）" width="170">
              <template #default="{ row }">
                <span v-if="row.scoreNeed != null">
                  {{ row.scoreNeed }}/{{ row.scoreBudget }}/{{ row.scoreChain }}/{{ row.scoreUrgency }}/{{ row.scoreCompete }}
                </span>
                <span v-else style="color: #909399;">未评分</span>
              </template>
            </el-table-column>
            <el-table-column prop="score" label="总分" width="80">
              <template #default="{ row }">{{ row.score == null ? '-' : row.score }}</template>
            </el-table-column>
            <el-table-column prop="grade" label="等级" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.grade" :type="gradeType(row.grade)" size="small">{{ row.grade }}</el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column prop="scoreModelVersion" label="模型版本" width="90">
              <template #default="{ row }">{{ row.scoreModelId ? 'v' + row.scoreModelVersion : '-' }}</template>
            </el-table-column>
            <el-table-column prop="scoreAt" label="评分时间" width="160">
              <template #default="{ row }">{{ (row.scoreAt || '').replace('T', ' ') }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openScore(row)">评分</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination style="margin-top: 14px; justify-content: flex-end;"
                         layout="total, prev, pager, next" :total="total"
                         :page-size="query.size" :current-change="loadData" />
        </el-card>
      </el-tab-pane>

      <!-- ==================== 模型配置 ==================== -->
      <el-tab-pane label="模型配置" name="model">
        <el-card>
          <template #header>
            <div class="card-header">
              <span style="font-weight: bold; font-size: 16px;">评分模型（BR-4.8-07 按行业/产品线差异化）</span>
              <el-button type="primary" :icon="Plus" @click="openModel()">新建模型</el-button>
            </div>
          </template>
          <el-table :data="models" v-loading="modelLoading" stripe>
            <el-table-column prop="modelKey" label="模型键" width="140" />
            <el-table-column prop="modelName" label="名称" min-width="140" show-overflow-tooltip />
            <el-table-column prop="industry" label="行业" width="110">
              <template #default="{ row }">{{ row.industry || '默认' }}</template>
            </el-table-column>
            <el-table-column prop="productLine" label="产品线" width="110">
              <template #default="{ row }">{{ row.productLine || '不限' }}</template>
            </el-table-column>
            <el-table-column label="权重（需/预/链/紧/竞）" width="180">
              <template #default="{ row }">
                {{ row.wNeed }}/{{ row.wBudget }}/{{ row.wChain }}/{{ row.wUrgency }}/{{ row.wCompete }}
              </template>
            </el-table-column>
            <el-table-column label="等级阈值" width="150">
              <template #default="{ row }">A≥{{ row.gradeAMin }} B≥{{ row.gradeBMin }} C≥{{ row.gradeCMin }}</template>
            </el-table-column>
            <el-table-column prop="version" label="版本" width="70">
              <template #default="{ row }">v{{ row.version }}</template>
            </el-table-column>
            <el-table-column prop="isCurrent" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.isCurrent ? 'success' : 'info'" size="small">
                  {{ row.isCurrent ? '生效中' : '历史' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openModel(row)">改权重</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 评分弹窗 -->
    <el-dialog v-model="scoreVisible" :title="`评分 — ${current.companyName || ''}`" width="520px">
      <el-alert v-if="scoreResult" :type="scoreResult.warning ? 'warning' : 'success'"
                :closable="false" show-icon style="margin-bottom: 10px;"
                :title="scoreResult.warning || `评分成功：${scoreResult.score} 分，等级 ${scoreResult.grade}`" />
      <el-form label-width="130px">
        <el-form-item v-for="d in dims" :key="d.k" :label="`${d.label}（${d.weight}%）`">
          <el-select v-model="scoreForm[d.k]" style="width: 220px;" placeholder="1-5 分，留空按 0 分计">
            <el-option v-for="n in 5" :key="n" :label="`${n} 分`" :value="n" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scoreVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitScore">提交评分</el-button>
      </template>
    </el-dialog>

    <!-- 模型弹窗 -->
    <el-dialog v-model="modelVisible" :title="modelForm.modelKey && !modelForm.isNew ? '调整模型（升版本）' : '新建模型'"
               width="600px">
      <el-alert v-if="weightSum !== 100" type="error" :closable="false" show-icon style="margin-bottom: 10px;"
                :title="`权重合计须为 100%，当前 ${weightSum}%`" />
      <el-form label-width="140px">
        <el-form-item label="模型键" required>
          <el-input v-model="modelForm.modelKey" :disabled="!modelForm.isNew" maxlength="64"
                    :placeholder="modelForm.isNew ? '如：ELECTRONICS' : '保存将生成新版本，键不可改'" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="modelForm.modelName" maxlength="128" />
        </el-form-item>
        <el-form-item label="适用行业">
          <el-input v-model="modelForm.industry" maxlength="64" placeholder="留空 = 默认模型" />
        </el-form-item>
        <el-form-item label="适用产品线">
          <el-input v-model="modelForm.productLine" maxlength="64" placeholder="留空 = 不限" />
        </el-form-item>
        <el-row v-for="d in dims" :key="d.k">
          <el-col :span="24">
            <el-form-item :label="`${d.label} 权重%`">
              <el-input-number v-model="modelForm[d.k]" :min="0" :max="100" style="width: 200px;" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="A 级下限">
          <el-input-number v-model="modelForm.gradeAMin" :min="1" :max="100" style="width: 200px;" />
        </el-form-item>
        <el-form-item label="B 级下限">
          <el-input-number v-model="modelForm.gradeBMin" :min="1" :max="100" style="width: 200px;" />
        </el-form-item>
        <el-form-item label="C 级下限">
          <el-input-number v-model="modelForm.gradeCMin" :min="0" :max="100" style="width: 200px;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="weightSum !== 100" @click="submitModel">
          保存（生成新版本）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { getLeadsApi, scoreLeadApi, getModelsApi, saveModelApi } from '@/api/crm/lead'

const dims = [
  { k: 'need', label: '需求明确度', weight: 30 },
  { k: 'budget', label: '预算确认度', weight: 25 },
  { k: 'chain', label: '决策链清晰度', weight: 20 },
  { k: 'urgency', label: '时间紧迫度', weight: 15 },
  { k: 'compete', label: '竞争态势', weight: 10 }
]
const gradeType = g => ({ A: 'success', B: '', C: 'warning', D: 'danger' }[g] || 'info')

const tab = ref('board')
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ current: 1, size: 10, grade: '' })

const cards = ref([
  { grade: 'A', label: 'A 级（≥80）', count: 0, type: 'success' },
  { grade: 'B', label: 'B 级（60-79）', count: 0, type: '' },
  { grade: 'C', label: 'C 级（40-59）', count: 0, type: 'warning' },
  { grade: '', label: 'D 级 / 未评分', count: 0, type: 'info' }
])

const modelLoading = ref(false)
const models = ref([])

const scoreVisible = ref(false)
const scoreForm = ref({})
const scoreResult = ref(null)
const current = ref({})

const modelVisible = ref(false)
const modelForm = ref({})

const weightSum = computed(() => {
  const f = modelForm.value
  return dims.reduce((s, d) => s + (Number(f[d.k]) || 0), 0)
})

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const params = { current: query.value.current, size: query.value.size }
    // UNSCORED → 取全量后由看板统计呈现；服务端按 grade 精确过滤
    if (query.value.grade && query.value.grade !== 'UNSCORED') params.grade = query.value.grade
    const res = await getLeadsApi(params)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

/** 看板：按等级统计（A/B/C 与 D+未评分合并） */
async function loadCards() {
  const res = await getLeadsApi({ current: 1, size: 1000 })
  const all = res.data.records || []
  const cnt = g => all.filter(x => x.grade === g).length
  cards.value[0].count = cnt('A')
  cards.value[1].count = cnt('B')
  cards.value[2].count = cnt('C')
  cards.value[3].count = all.filter(x => !x.grade || x.grade === 'D').length
}

async function loadModels() {
  modelLoading.value = true
  try {
    const res = await getModelsApi({})
    models.value = res.data || []
  } finally {
    modelLoading.value = false
  }
}

function openScore(row) {
  current.value = row
  scoreResult.value = null
  scoreForm.value = {
    need: row.scoreNeed, budget: row.scoreBudget, chain: row.scoreChain,
    urgency: row.scoreUrgency, compete: row.scoreCompete
  }
  scoreVisible.value = true
}

async function submitScore() {
  saving.value = true
  try {
    const res = await scoreLeadApi(current.value.id, scoreForm.value)
    scoreResult.value = res.data
    ElMessage.success(`评分完成：${res.data.score} 分（${res.data.grade} 级）`)
    scoreVisible.value = false
    loadData()
    loadCards()
  } finally {
    saving.value = false
  }
}

function openModel(row) {
  modelForm.value = row
    ? { ...row, isNew: false }
    : {
        isNew: true, modelKey: '', modelName: '', industry: '', productLine: '',
        wNeed: 30, wBudget: 25, wChain: 20, wUrgency: 15, wCompete: 10,
        gradeAMin: 80, gradeBMin: 60, gradeCMin: 40
      }
  modelVisible.value = true
}

async function submitModel() {
  if (weightSum.value !== 100) {
    ElMessage.error(`权重合计须为 100%，当前 ${weightSum.value}%`)
    return
  }
  saving.value = true
  try {
    const f = { ...modelForm.value }
    delete f.isNew
    const res = await saveModelApi(f)
    ElMessage.success(`已保存，生成 v${res.data.version}`)
    modelVisible.value = false
    loadModels()
  } finally {
    saving.value = false
  }
}

onMounted(() => { loadData(); loadCards(); loadModels() })
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-actions { display: flex; gap: 8px; }
</style>
