<template>
  <div class="version-timeline">
    <el-timeline v-if="versions.length">
      <el-timeline-item
        v-for="v in versions"
        :key="v.id"
        :type="timelineType(v.status)"
        :hollow="v.status === 'DRAFT'"
        :timestamp="`V${v.versionNo} · ${verStatusName(v.status)}`"
      >
        <div class="vt-body">
          <div class="vt-row">
            <el-tag size="small" :type="verStatusTag(v.status)">{{ verStatusName(v.status) }}</el-tag>
            <span class="vt-date">
              {{ v.effectiveFrom || '—' }} ~ {{ v.effectiveTo || '现行' }}
            </span>
          </div>
          <div class="vt-reason">{{ v.changeReason || '—' }}</div>
          <!-- 发布流水线与双签进度（签名来自 pending-versions 聚合，按 versionId 合并） -->
          <div v-if="v.status === 'PENDING_APPROVE'" class="vt-pipeline">
            <el-divider content-position="left">发布流水线</el-divider>
            <div class="vt-steps">
              <el-tag size="small" type="success">已提交发布</el-tag>
              <el-icon><ArrowRight /></el-icon>
              <el-tag size="small" type="warning">质量经理审批</el-tag>
              <template v-if="signOf(v).total > 1">
                <el-icon><ArrowRight /></el-icon>
                <el-tag size="small" :type="signOf(v).passed >= 2 ? 'success' : 'info'">技术负责人会签</el-tag>
              </template>
              <el-icon><ArrowRight /></el-icon>
              <el-tag size="small" type="info">发布生效</el-tag>
            </div>
            <div v-if="signOf(v).nodes && signOf(v).nodes.length" class="vt-nodes">
              <div v-for="(n, i) in signOf(v).nodes" :key="i" class="vt-node">
                <el-tag size="small" :type="nodeTag(n.status)">{{ n.nodeName || n.roleRequired }}</el-tag>
                <span v-if="n.signerName">{{ n.signerName }} {{ n.opTime || '' }}</span>
                <span v-else-if="n.status === 'ACTIVE'" style="color: #e6a23c;">待签署</span>
              </div>
            </div>
            <div class="vt-hint">签署在「审批待办」完成（/m/qms-approval）</div>
          </div>
        </div>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-else description="尚无版本，点击「新建版本」开始" :image-size="70" />
  </div>
</template>

<script setup>
import { ArrowRight } from '@element-plus/icons-vue'

const props = defineProps({
  versions: { type: Array, default: () => [] },
  doubleSigns: { type: Object, default: () => ({}) } // versionId -> doubleSign status
})

/** 合并双签状态：优先外部聚合映射（pending-versions），其次版本行自带 */
function signOf(v) {
  return props.doubleSigns[v.id] || v.doubleSign || {}
}

function verStatusName(s) { return { DRAFT: '草稿', PENDING_APPROVE: '审批中', RELEASED: '已发布', RETIRED: '已退役' }[s] || s }
function verStatusTag(s) { return { DRAFT: 'info', PENDING_APPROVE: 'warning', RELEASED: 'success', RETIRED: 'danger' }[s] || 'info' }
function timelineType(s) { return { DRAFT: 'info', PENDING_APPROVE: 'warning', RELEASED: 'success', RETIRED: 'danger' }[s] || 'info' }
function nodeTag(s) { return { PASSED: 'success', ACTIVE: 'warning', REJECTED: 'danger', SKIPPED: 'info' }[s] || 'info' }
</script>

<style scoped>
.vt-body { width: 100%; }
.vt-row { display: flex; gap: 10px; align-items: center; }
.vt-date { color: #909399; font-size: 12px; }
.vt-reason { color: #606266; font-size: 13px; margin-top: 4px; }
.vt-pipeline { margin-top: 8px; background: #f8f8f9; border-radius: 6px; padding: 8px 12px; }
.vt-steps { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12px; }
.vt-nodes { margin-top: 6px; display: flex; flex-direction: column; gap: 4px; }
.vt-node { display: flex; gap: 8px; align-items: center; font-size: 12px; color: #606266; }
.vt-hint { margin-top: 6px; font-size: 12px; color: #909399; }
</style>
