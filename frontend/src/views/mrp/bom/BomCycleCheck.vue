<template>
  <div class="page">
    <el-page-header content="循环校验（5.1.4）— 按父项或全量扫描 BOM 组件图，检出环路径并高亮"
                    style="margin-bottom: 8px" />

    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="父项">
        <el-select v-model="parentItemCode" filterable remote clearable
                   :remote-method="searchParents" :loading="parentLoading"
                   placeholder="留空 = 全量扫描" style="width: 300px;">
          <el-option v-for="it in parentOptions" :key="it.itemCode"
                     :label="`${it.itemCode} ${it.itemName}`" :value="it.itemCode" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="scanning" @click="doScan">开始扫描</el-button>
      </el-form-item>
      <span class="tip">口径：各父项在用版本（草稿∪待审核∪已发布）行并集；最大嵌套层数取系统参数 BOM_MAX_NESTING_DEPTH</span>
    </el-form>

    <el-card v-if="report" class="report" shadow="never">
      <template #header>
        <div class="card-head">
          <span>校验报告</span>
          <el-tag :type="report.passed ? 'success' : 'danger'" size="small">
            {{ report.passed ? '通过：未发现循环引用' : `发现 ${report.cycles.length} 个环` }}
          </el-tag>
          <el-tag v-if="report.depthExceeded" type="warning" size="small">
            存在超过 {{ report.maxDepth }} 层的分支（超限提示）
          </el-tag>
        </div>
      </template>

      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="扫描范围">{{ report.scope }}</el-descriptions-item>
        <el-descriptions-item label="起始父项数">{{ report.scannedRoots }}</el-descriptions-item>
        <el-descriptions-item label="在用 BOM 数">{{ report.scannedBoms }}</el-descriptions-item>
        <el-descriptions-item label="遍历节点数">{{ report.visitedNodes }}</el-descriptions-item>
        <el-descriptions-item label="层数上限" :span="4">BOM_MAX_NESTING_DEPTH = {{ report.maxDepth }}</el-descriptions-item>
      </el-descriptions>

      <template v-if="report.cycles.length">
        <div class="cycle-title">环路径（高亮展示，保存时即以此路径阻断）：</div>
        <div v-for="(path, idx) in report.cycles" :key="idx" class="cycle-path">
          <span class="cycle-index">#{{ idx + 1 }}</span>
          <template v-for="(node, i) in splitPath(path)" :key="i">
            <span class="node">{{ node }}</span>
            <span v-if="i < splitPath(path).length - 1" class="arrow">→</span>
          </template>
        </div>
      </template>
    </el-card>

    <el-empty v-if="!report && !scanning" description="尚未扫描" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import request from '@/utils/request'
import { scanBomApi } from '@/api/mrp/bom'

// 5.1.4 循环校验：与保存内嵌校验共用后端 BomCycleChecker（同一口径）
const parentItemCode = ref('')
const parentOptions = ref([])
const parentLoading = ref(false)
const scanning = ref(false)
const report = ref(null)

async function searchParents(kw) {
  parentLoading.value = true
  try {
    const res = await request.get('/mdm/items', {
      params: { current: 1, size: 20, keyword: kw || '', status: '' }
    })
    parentOptions.value = res.data.records || []
  } finally {
    parentLoading.value = false
  }
}

async function doScan() {
  scanning.value = true
  try {
    const params = {}
    if (parentItemCode.value) params.parentItemCode = parentItemCode.value
    const res = await scanBomApi(params)
    report.value = res.data
  } catch (e) {
    console.warn('[bom-cycle] scan failed', e)
  } finally {
    scanning.value = false
  }
}

function splitPath(path) {
  return String(path || '').split('→')
}
</script>

<style scoped>
.filter-bar {
  margin-bottom: 4px;
}
.tip {
  color: #909399;
  font-size: 12px;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cycle-title {
  margin-top: 12px;
  font-weight: 600;
  font-size: 13px;
}
.cycle-path {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding: 8px 10px;
  background: #fef0f0;
  border: 1px solid #fbc4c4;
  border-radius: 6px;
}
.cycle-index {
  color: #909399;
  font-size: 12px;
  margin-right: 4px;
}
.node {
  background: #f56c6c;
  color: #fff;
  font-family: monospace;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
}
.arrow {
  color: #f56c6c;
  font-weight: 700;
}
</style>
