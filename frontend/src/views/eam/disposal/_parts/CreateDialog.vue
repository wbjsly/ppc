<template>
  <el-dialog v-model="createVisible" :title="createForm.disposalType === 'SCRAP' ? '新建报废单' : '新建出售单'" width="880px">
    <el-form label-width="110px">
      <el-form-item label="处置原因">
        <el-select v-model="createForm.reasonCode" style="width:180px">
          <el-option label="技术淘汰" value="TECH" />
          <el-option label="损坏" value="DAMAGE" />
          <el-option label="闲置" value="IDLE" />
          <el-option label="政策性淘汰" value="POLICY" />
        </el-select>
        <el-input v-model="createForm.reasonNote" placeholder="原因说明" style="margin-left:8px" />
      </el-form-item>
      <el-form-item label="评估残值">
        <el-input-number v-model="createForm.estResidual" :min="0" :precision="2" />
        <span style="margin-left:8px;color:#909399">出售=市场价 / 报废=残料回收价；&gt; NBV×10% 须复核说明</span>
      </el-form-item>
      <el-form-item v-if="residualAlert" label="复核说明" required>
        <el-input v-model="createForm.reviewNote" placeholder="残值评估偏高，须填写复核说明（C-4.13-05）" />
      </el-form-item>
      <el-form-item label="评估报告号">
        <el-input v-model="createForm.evalReportNo" placeholder="出售且评估价&lt;NBV 时必填（BR-31）" />
      </el-form-item>
      <el-divider content-position="left">处置卡片（批量）</el-divider>
      <el-form-item label="选卡">
        <el-input v-model="cardKeyword" placeholder="输入卡号/名称搜索" style="width:260px" @keyup.enter="searchCards">
          <template #append><el-button @click="searchCards">搜索</el-button></template>
        </el-input>
      </el-form-item>
      <el-table :data="createLines" border size="small">
        <el-table-column prop="cardNo" label="卡号" width="130" />
        <el-table-column prop="assetName" label="名称" />
        <el-table-column label="原值" width="100">
          <template #default="{ row }">{{ row._cost }}</template>
        </el-table-column>
        <el-table-column label="NBV" width="100">
          <template #default="{ row }">{{ row._nbv }}</template>
        </el-table-column>
        <el-table-column v-if="createForm.disposalType === 'SALE'" label="处置价" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.lineProceeds" :min="0" :precision="2" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ $index }">
            <el-button size="small" type="danger" link @click="createLines.splice($index, 1)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-divider content-position="left">残料回收明细（可选；执行确认时一键入库）</el-divider>
      <el-table :data="createRecycles" border size="small">
        <el-table-column label="品名" width="180">
          <template #default="{ row }"><el-input v-model="row.itemName" size="small" /></template>
        </el-table-column>
        <el-table-column label="物料编码" width="160">
          <template #default="{ row }"><el-input v-model="row.itemCode" size="small" /></template>
        </el-table-column>
        <el-table-column label="数量" width="120">
          <template #default="{ row }"><el-input-number v-model="row.qty" :min="0" size="small" /></template>
        </el-table-column>
        <el-table-column label="估价" width="130">
          <template #default="{ row }"><el-input-number v-model="row.estPrice" :min="0" :precision="2" size="small" /></template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ $index }">
            <el-button size="small" type="danger" link @click="createRecycles.splice($index, 1)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" style="margin:8px 0" @click="createRecycles.push({ itemName: '', itemCode: '', qty: 1, estPrice: 0 })">+ 加回收明细</el-button>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" @click="doCreate">创建草稿</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/** 新建处置单对话框（拆分自原资产处置操作台，报废申请与资产出售两页共用）。 */
import { useDisposal } from '../useDisposal'
const { createVisible, createForm, createLines, createRecycles, cardKeyword,
  residualAlert, searchCards, doCreate } = useDisposal()
</script>
