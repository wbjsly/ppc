<template>
  <div class="i18n-page">
    <PageInfo title="本地合规：字段扩展、格式校验、字段加载" description="引擎+复用既有列（决策5）：包定义/正则规则集中配置禁硬编码（BR-5.7-13）；字段挂 TAXPAYER_NO 等既有列；注册地→包映射。" style="margin-bottom:12px" />

    <!-- 17.5.3 字段加载 -->
    <el-form inline size="small">
      <el-form-item label="法人主体注册地">
        <el-select v-model="ld.regPlace" style="width:140px" @change="doLoad">
          <el-option label="JP（日本）" value="JP" /><el-option label="CN（中国）" value="CN" />
          <el-option label="EU（欧盟）" value="EU" /><el-option label="US（美国，无包）" value="US" />
        </el-select>
      </el-form-item>
      <el-button type="primary" size="small" @click="doLoad">加载字段清单</el-button>
    </el-form>
    <el-table :data="ld.fields" border size="small">
      <el-table-column prop="packCode" label="字段包" width="140" />
      <el-table-column prop="fieldName" label="字段" width="150" />
      <el-table-column prop="columnName" label="挂载既有列" width="150" />
      <el-table-column prop="formatRegex" label="格式规则" min-width="200" show-overflow-tooltip />
      <el-table-column prop="errorHint" label="提示" min-width="220" show-overflow-tooltip />
    </el-table>
    <el-alert v-if="ld.regPlace === 'US'" type="success" :closable="false" style="margin-top:10px"
      title="US 无合规字段包 → 空清单，界面不出现冗余字段（17.5.3 / S-5.7-06）" />
    <el-alert v-else type="info" :closable="false" style="margin-top:10px"
      title="按注册地加载：无关法人主体界面 MUST NOT 出现冗余字段（spec i18n-localization）" />
  </div>
</template>

<script setup>
/**
 * 17.5.3 字段加载（拆分自原本地合规页）：按注册地加载字段清单。
 * 与字段扩展、格式校验页无共享状态（design D2：单页逻辑留页内）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref } from 'vue'
import { loadComplianceApi } from '@/api/i18n/i18n'

const ld = ref({ regPlace: 'JP', fields: [] })

async function doLoad() {
  try {
    const r = await loadComplianceApi(ld.value.regPlace)
    ld.value.fields = r.data || []
  } catch { ld.value.fields = [] }
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
