<template>
  <el-dialog v-model="execVisible" :title="`执行记录（${execForm.woNo}）`" width="560px">
    <el-form label-width="110px">
      <el-form-item label="实际内容">
        <el-input v-model="execForm.execDesc" type="textarea" :rows="3" />
      </el-form-item>
      <el-form-item label="发现的问题">
        <el-input v-model="execForm.issueDesc" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="更换部件">
        <el-input v-model="execForm.partsChanged" />
      </el-form-item>
      <el-form-item label="回填工时">
        <div style="display:flex;gap:8px;">
          <el-input-number v-model="execForm.hours" :min="0" :precision="2" placeholder="工时" />
          <el-select v-model="execForm.postType" style="width:130px;">
            <el-option label="工程师" value="ENGR" /><el-option label="维修工" value="TECH" />
            <el-option label="其他" value="OTHER" />
          </el-select>
          <el-button @click="addTime">追加工时</el-button>
        </div>
      </el-form-item>
    </el-form>
    <div v-if="execTimes.length" class="muted" style="margin-bottom:8px;">
      已回填：{{ execTimes.map(t => `${t.postType} ${t.hours}h`).join('，') }}
    </div>
    <template #footer>
      <el-button @click="execVisible = false">关闭</el-button>
      <el-button type="primary" :loading="saving" @click="doSaveExecution">保存执行记录</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/** 执行记录对话框（拆分自原运维管理操作台，点检保养与故障维修两页共用）。 */
import { execVisible, execForm, execTimes, saving, addTime, doSaveExecution } from '../useMaintenance'
</script>

<style scoped>
.muted { color: #909399; font-size: 12px; }
</style>
