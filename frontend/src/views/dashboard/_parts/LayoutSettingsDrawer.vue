<template>
  <el-drawer :model-value="modelValue" title="布局设置" size="440px" @update:model-value="$emit('update:modelValue', $event)">
    <el-tabs v-model="tab">
      <!-- ============ 个人布局（所有用户） ============ -->
      <el-tab-pane label="个人布局" name="personal">
        <div class="ls-block">
          <div class="ls-label">布局构成（三层差量合并）</div>
          <div class="ls-chips">
            <el-tag :type="dash.layersInfo.value.global ? 'success' : 'info'" effect="plain">全局默认</el-tag>
            <el-tag :type="dash.layersInfo.value.role ? 'success' : 'info'" effect="plain">角色模板</el-tag>
            <el-tag :type="dash.layersInfo.value.user ? 'success' : 'info'" effect="plain">个人覆盖</el-tag>
          </div>
          <div class="ls-hint">
            未动过的组件跟随上层模板；您移除的组件不会随模板更新回补；管理员模板新增的组件会自动出现在画布。
          </div>
        </div>
        <div class="ls-block">
          <div class="ls-label">当前状态</div>
          <div class="ls-stat">
            <span>画布组件 <b>{{ dash.items.value.length }}</b></span>
            <span>已移除 <b>{{ dash.excluded.value.length }}</b></span>
            <span>快捷入口 <b>{{ dash.shortcuts.value.length }}</b></span>
          </div>
          <el-popconfirm title="恢复为角色模板默认布局？个人位置与移除记录将清除。" width="260" @confirm="emit('reset')">
            <template #reference>
              <el-button type="warning" plain style="margin-top: 12px">恢复默认布局</el-button>
            </template>
          </el-popconfirm>
        </div>
      </el-tab-pane>

      <!-- ============ 全局默认（ADMIN） ============ -->
      <el-tab-pane v-if="isAdmin" label="全局默认" name="global">
        <div class="ls-block">
          <div class="ls-label">编辑方式：画布即编辑器</div>
          <div class="ls-hint">
            先在工作台画布上摆好组件，再点击下方按钮把<b>当前画布</b>推送为全局默认；此后所有新用户的初始布局（无角色模板时）以此为准。
          </div>
          <div class="ls-stat">
            <span>全局层现状 <b>{{ globalCount }} 件</b></span>
          </div>
          <el-button type="primary" style="margin-top: 12px" :disabled="!dash.initialized.value"
                     @click="pushGlobal">将当前画布保存为全局默认</el-button>
        </div>
      </el-tab-pane>

      <!-- ============ 角色模板（ADMIN） ============ -->
      <el-tab-pane v-if="isAdmin" label="角色模板" name="role">
        <div class="ls-block">
          <div class="ls-label">选择角色</div>
          <el-select v-model="selectedRole" filterable placeholder="选择要编辑模板的角色" style="width: 100%"
                     @change="loadRoleLayer">
            <el-option v-for="r in roleOptions" :key="r" :label="r" :value="r" />
          </el-select>
          <div v-if="selectedRole" class="ls-stat" style="margin-top: 10px">
            <span>该角色模板 <b>{{ roleCount }} 件</b></span>
            <span v-if="roleCount === 0" class="ls-none">（未配置，成员回落全局默认）</span>
          </div>
          <div class="ls-hint" style="margin-top: 8px">
            选择角色后，点击下方把<b>当前画布</b>推送为该角色模板；该角色成员下次进入工作台即按新模板呈现（个人未覆盖部分）。
          </div>
          <el-button type="primary" style="margin-top: 12px" :disabled="!selectedRole || !dash.initialized.value"
                     @click="pushRole">将当前画布保存为此角色模板</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-drawer>
</template>

<script setup>
/**
 * 布局设置抽屉（add-configurable-workbench，spec「全局与角色模板管理」，design D6）：
 * - 个人布局：三层构成可视化 + 恢复默认（emit reset 由容器重建）；
 * - 全局默认 / 角色模板（仅 ROLE_ADMIN 渲染 Tab，接口侧 hasRole(ADMIN) 兜底）：
 *   编辑器 = 复用主画布（画布即编辑器）→「推送为模板」，避免第二个 gridstack 实例。
 * 需要容器提供 getCurrentCanvas()（grid.save 快照）。
 */
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { useUserStore } from '@/store/user'
import { useDashboard } from '../useDashboard'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 容器注入：() => [{id,x,y,w,h}]（grid.save 快照） */
  getCurrentCanvas: { type: Function, required: true },
})
const emit = defineEmits(['update:modelValue', 'reset'])

const dash = useDashboard()
const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))

const tab = ref('personal')
const roleOptions = ref([])
const selectedRole = ref('')
const globalCount = ref(0)
const roleCount = ref(0)

async function refreshLayers() {
  if (!isAdmin.value) return
  try {
    const res = await request({ url: '/dash/layout/layers', method: 'get', silent: true })
    const g = res.data?.global
    globalCount.value = g ? (JSON.parse(g).items || []).length : 0
  } catch { /* 回显失败不阻塞 */ }
}

async function loadRoleOptions() {
  if (!isAdmin.value || roleOptions.value.length) return
  try {
    const res = await request({ url: '/system/roles', method: 'get', silent: true })
    const list = res.data?.records || res.data || []
    roleOptions.value = (Array.isArray(list) ? list : []).map(r => r.roleCode || r.code || r.id || r.name).filter(Boolean)
  } catch { roleOptions.value = [] }
}

async function loadRoleLayer() {
  roleCount.value = 0
  if (!selectedRole.value) return
  try {
    const res = await request({
      url: '/dash/layout/layer', method: 'get',
      params: { scope: 'ROLE', refId: selectedRole.value }, silent: true,
    })
    roleCount.value = res.data ? (JSON.parse(res.data).items || []).length : 0
  } catch { roleCount.value = 0 }
}

function canvasPayload() {
  return { v: 1, items: props.getCurrentCanvas(), excluded: dash.excluded.value, shortcuts: dash.shortcuts.value }
}

async function pushGlobal() {
  try {
    await request({ url: '/dash/layout/global', method: 'put', data: canvasPayload(), silent: true })
    ElMessage.success('已保存为全局默认')
    refreshLayers()
  } catch (e) {
    ElMessage.error(e?.response?.status === 403 ? '无权限（需 ROLE_ADMIN）' : '保存失败')
  }
}

async function pushRole() {
  if (!selectedRole.value) return
  try {
    await request({
      url: `/dash/layout/role/${encodeURIComponent(selectedRole.value)}`, method: 'put',
      data: canvasPayload(), silent: true,
    })
    ElMessage.success(`已保存为 ${selectedRole.value} 的角色模板`)
    loadRoleLayer()
  } catch (e) {
    ElMessage.error(e?.response?.status === 403 ? '无权限（需 ROLE_ADMIN）' : '保存失败')
  }
}

watch(() => props.modelValue, v => {
  if (v) { refreshLayers(); loadRoleOptions() }
}, { immediate: true })
onMounted(() => { if (props.modelValue) { refreshLayers(); loadRoleOptions() } })
</script>

<style scoped>
.ls-block { margin-bottom: 18px; }
.ls-label { font-size: 13px; font-weight: 600; color: var(--el-text-color-primary); margin-bottom: 8px; }
.ls-chips { display: flex; gap: 8px; flex-wrap: wrap; }
.ls-hint { margin-top: 8px; font-size: 12px; line-height: 1.7; color: var(--el-text-color-secondary); }
.ls-stat { display: flex; gap: 16px; font-size: 13px; color: var(--el-text-color-regular); }
.ls-stat b { color: var(--el-color-primary); }
.ls-none { color: var(--el-text-color-placeholder); font-size: 12px; }
</style>
