<template>
  <!-- 新建申请对话框（16.3.1 新建 / 16.3.3 到期续期 两页共用） -->
  <el-dialog v-model="createDlg" title="新建权限申请" width="640px">
    <el-form label-width="100px">
      <el-form-item label="被授权人" required>
        <el-select v-model="form.targetUserId" filterable placeholder="选择用户" style="width: 100%">
          <el-option v-for="u in users" :key="u.id"
            :label="u.username + (u.nickName ? '（' + u.nickName + '）' : '')" :value="u.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="目标角色" required>
        <el-select v-model="form.roleCodes" multiple filterable placeholder="选择角色"
          style="width: 100%">
          <el-option v-for="r in allRoles" :key="r.roleCode"
            :label="r.roleCode + '（' + r.roleName + '）'" :value="r.roleCode" />
        </el-select>
        <div class="muted">含特权角色须填故障单/项目编号；命中互斥将自动追加合规官</div>
      </el-form-item>
      <el-form-item label="数据范围" required>
        <el-input v-model="form.dataScope" placeholder="法人主体/组织单元/部门，如：LE-002 / OU-PROC-01" />
      </el-form-item>
      <el-form-item label="业务理由" required>
        <el-input v-model="form.reason" type="textarea" :rows="2"
          placeholder="不少于 10 字的业务理由" />
      </el-form-item>
      <el-form-item label="有效期(月)" required>
        <el-input-number v-model="form.validMonths" :min="1" :max="12" />
        <span class="muted">最长 12 个月，到期自动回收</span>
      </el-form-item>
      <el-form-item label="生效策略">
        <el-radio-group v-model="form.activationMode">
          <el-radio value="IMMEDIATE">立即（审批通过即生效）</el-radio>
          <el-radio value="SCHEDULED">指定时点</el-radio>
        </el-radio-group>
        <el-date-picker v-if="form.activationMode === 'SCHEDULED'" v-model="form.activateAt"
          type="datetime" placeholder="选择生效时点" style="margin-left: 12px" />
      </el-form-item>
      <el-form-item label="故障单号">
        <el-input v-model="form.ticketNo" placeholder="特权申请必填（INC- / 项目编号）" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createDlg = false">取消</el-button>
      <el-button type="primary" @click="submitCreate">提交审批</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 新建权限申请对话框（跨页共用，design D2：状态放 usePermissionRequest.js 单例）。
 * 供权限申请页「新建申请」与到期回收页「续期」渲染，提交校验/接口不变。
 */
import { usePermissionRequest } from '../usePermissionRequest'

const { createDlg, form, users, allRoles, submitCreate } = usePermissionRequest()
</script>

<style scoped>
.muted { color: #909399; font-size: 12px; }
</style>
