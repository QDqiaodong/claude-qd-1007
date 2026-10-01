<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')
const statuses = ['开放', '检修', '关闭']

const dialog = ref(false)
const saving = ref(false)
const form = ref(blank())
function blank() {
  return { rideCode: '', rideName: '', minHeightCm: '', maxAge: '', status: '开放' }
}

const boardDialog = ref(false)
const boarding = ref(false)
const boardForm = ref({ id: null, rideName: '', heightCm: '', age: '' })
const boardResult = ref('')

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listRides(statusFilter.value ? { status: statusFilter.value } : {})
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }
async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      ...form.value,
      minHeightCm: form.value.minHeightCm === '' ? null : Number(form.value.minHeightCm),
      maxAge: form.value.maxAge === '' ? null : Number(form.value.maxAge)
    }
    await api.createRide(d)
    tip.value = `设施 ${d.rideCode} 已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function transfer(row, target) {
  error.value = ''; tip.value = ''
  try {
    await api.transferRide(row.id, target)
    tip.value = `${row.rideName} 已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

function openBoard(row) {
  boardForm.value = { id: row.id, rideName: row.rideName, heightCm: '', age: '' }
  boardResult.value = ''
  boardDialog.value = true
  error.value = ''; tip.value = ''
}
async function doBoard() {
  boarding.value = true; error.value = ''
  try {
    await api.boardRide(
      boardForm.value.id,
      boardForm.value.heightCm === '' ? null : Number(boardForm.value.heightCm),
      boardForm.value.age === '' ? null : Number(boardForm.value.age)
    )
    boardResult.value = '核验通过，可以乘坐 🎉'
  } catch (e) {
    boardResult.value = ''
    error.value = e.message
  } finally { boarding.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">游乐设施 · 乘坐校验</span>
        <span class="panel-desc">乘坐前校验：设施须开放、身高达标、年龄不超上限</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">设施数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">开放</div><div class="v">{{ rows.filter(r => r.status === '开放').length }}</div></div>
        <div class="stat"><div class="k">检修</div><div class="v">{{ rows.filter(r => r.status === '检修').length }}</div></div>
        <div class="stat"><div class="k">关闭</div><div class="v">{{ rows.filter(r => r.status === '关闭').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建设施</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="rideCode" label="编号" width="100" />
        <el-table-column prop="rideName" label="名称" min-width="130" />
        <el-table-column prop="minHeightCm" label="最低身高(cm)" width="120" />
        <el-table-column prop="maxAge" label="最大年龄" width="100" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '开放' ? 'success' : row.status === '检修' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openBoard(row)">乘坐校验</el-button>
            <el-button size="small" :disabled="row.status === '检修'" @click="transfer(row, '检修')">检修</el-button>
            <el-button size="small" :disabled="row.status === '关闭'" @click="transfer(row, '关闭')">关闭</el-button>
            <el-button size="small" type="success" plain :disabled="row.status === '开放'" @click="transfer(row, '开放')">开放</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建设施" width="520px">
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="设施编号"><el-input v-model="form.rideCode" placeholder="如 R-009" /></el-form-item>
        <el-form-item label="设施名称"><el-input v-model="form.rideName" /></el-form-item>
        <el-form-item label="最低身高(cm)"><el-input v-model="form.minHeightCm" type="number" placeholder="不限留空" /></el-form-item>
        <el-form-item label="最大年龄"><el-input v-model="form.maxAge" type="number" placeholder="不限留空" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio v-for="s in statuses" :key="s" :value="s">{{ s }}</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="boardDialog" :title="`乘坐校验 · ${boardForm.rideName}`" width="460px">
      <el-form label-width="110px" class="dialog-form">
        <el-form-item label="游客身高(cm)"><el-input v-model="boardForm.heightCm" type="number" /></el-form-item>
        <el-form-item label="游客年龄"><el-input v-model="boardForm.age" type="number" /></el-form-item>
        <el-form-item v-if="boardResult" label="结果">
          <span style="color:#1e8e4e;font-weight:600">{{ boardResult }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="boardDialog = false">关闭</el-button>
        <el-button type="primary" :loading="boarding" @click="doBoard">核验</el-button>
      </template>
    </el-dialog>
  </div>
</template>
