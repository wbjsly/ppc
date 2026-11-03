<template>
  <div>
    <PdaShell :title="'设备巡检 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <!-- 13.3.4 拍照留证（选中任务上传） -->
      <PageInfo title="异常点检项须拍照留证后方可提交（缺图 422 补拍提示）" style="margin-bottom:8px" />
      <el-input v-model="photoUrl" placeholder="照片 URL（演示：直接填写）" />
      <el-button size="small" type="primary" style="margin-top:8px"
                 :disabled="!current" @click="submitWithPhoto">带图提交当前任务</el-button>

      <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:8px"
                :title="result.title" :description="result.desc" />
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.3.4 拍照留证（拆分自原设备巡检操作台）：对已选中任务补图提交。
 * 当前任务/照片/点检项、加载态、结果条与扫码签到见 useInspection.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import { inspectSubmitApi } from '@/api/iot/inspect'
import { useInspection } from './useInspection'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '设备巡检')

const {
  current, photoUrl, items, result, loadTasks, onScan, doSync,
} = useInspection()

async function submitWithPhoto() {
  try {
    await inspectSubmitApi(current.value.id, {
      itemsJson: JSON.stringify(items.value.length ? items.value
        : [{ item: '外观', value: '正常', abnormal: false }]),
      photoUrls: photoUrl.value ? `[\"${photoUrl.value}\"]` : null,
    })
    ElMessage.success('带图提交成功')
    loadTasks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  }
}
</script>
