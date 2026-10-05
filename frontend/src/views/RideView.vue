<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const batches = ref([])
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

const reviewDialog = ref(false)
const reviewRide = ref(null)
const reviewLoading = ref(false)
const reviewSaving = ref(false)
const reviewReleasing = ref(false)
const reviewBatches = ref([])
const selectedBatchId = ref(null)
const inspectorName = ref('')
const approverName = ref('')
const itemForms = ref({})

const activeBatchMap = computed(() => {
  const map = {}
  for (const batch of batches.value) {
    if (['检修中', '待放行'].includes(batch.status) && !map[batch.rideId]) {
      map[batch.rideId] = batch
    }
  }
  return map
})

const selectedBatch = computed(() =>
  reviewBatches.value.find((b) => b.id === selectedBatchId.value) || null
)
const selectedItems = computed(() => selectedBatch.value?.items || [])
const allRequiredPassed = computed(() => {
  const required = ['brake', 'restraint', 'emergency_stop', 'no_load_trial']
  return required.every((code) => itemForms.value[code]?.result === '合格')
})
const hasFailedItem = computed(() =>
  Object.values(itemForms.value).some((item) => item.result === '不合格')
)
const samePerson = computed(() =>
  Boolean(approverName.value && approverName.value.trim() === selectedBatch.value?.inspectorName)
)

function itemName(code) {
  return {
    brake: '制动装置',
    restraint: '约束装置',
    emergency_stop: '紧急停机',
    no_load_trial: '空载试运行'
  }[code] || code
}

function batchTagType(status) {
  return {
    检修中: 'warning',
    待放行: 'primary',
    已放行: 'success',
    已失效: 'info',
    已过期: 'danger'
  }[status] || 'info'
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const [rideData, batchData] = await Promise.all([
      api.listRides(statusFilter.value ? { status: statusFilter.value } : {}),
      api.listSafetyBatches()
    ])
    rows.value = rideData || []
    batches.value = batchData || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
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
    tip.value = target === '检修'
      ? `${row.rideName} 已进入检修，并自动生成安全复核批次`
      : `${row.rideName} 已转为「${target}」`
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

async function openReview(row) {
  reviewRide.value = row
  reviewDialog.value = true
  reviewBatches.value = []
  selectedBatchId.value = null
  inspectorName.value = ''
  approverName.value = ''
  itemForms.value = {}
  error.value = ''; tip.value = ''
  await reloadReviewBatches()
}

async function reloadReviewBatches() {
  if (!reviewRide.value) return
  reviewLoading.value = true
  try {
    const data = await api.listSafetyBatches(reviewRide.value.id)
    reviewBatches.value = data || []
    const current = reviewBatches.value.find((b) => ['检修中', '待放行'].includes(b.status))
    const target = current || reviewBatches.value[0]
    if (target) await selectBatch(target.id)
  } catch (e) {
    error.value = e.message
  } finally {
    reviewLoading.value = false
  }
}

async function selectBatch(id) {
  try {
    const detail = await api.getSafetyBatch(id)
    const index = reviewBatches.value.findIndex((b) => b.id === id)
    if (index >= 0) reviewBatches.value[index] = detail
    selectedBatchId.value = id
    inspectorName.value = detail.inspectorName || ''
    approverName.value = ''
    itemForms.value = {}
    for (const item of detail.items || []) {
      itemForms.value[item.itemCode] = {
        result: item.result === '待检' ? '' : item.result,
        remark: item.remark || ''
      }
    }
  } catch (e) {
    error.value = e.message
  }
}

async function startBatch() {
  reviewSaving.value = true; error.value = ''
  try {
    const batch = await api.startSafetyBatch(reviewRide.value.id)
    tip.value = `已生成新复核批次 ${batch.batchNo}`
    await load()
    reviewRide.value = rows.value.find((r) => r.id === reviewRide.value.id) || reviewRide.value
    await reloadReviewBatches()
    await selectBatch(batch.id)
  } catch (e) {
    error.value = e.message
  } finally {
    reviewSaving.value = false
  }
}

async function submitReview() {
  error.value = ''
  if (!inspectorName.value.trim()) {
    error.value = '请填写执行逐项复核的安全员姓名'
    return
  }
  if (hasFailedItem.value) {
    error.value = '存在不合格项，必须整改后重新检查；所有必检项合格才能提交'
    return
  }
  if (!allRequiredPassed.value) {
    error.value = '制动、约束装置、紧急停机、空载试运行均须逐项记录为合格'
    return
  }
  reviewSaving.value = true
  try {
    const items = Object.entries(itemForms.value).map(([code, v]) => ({
      itemCode: code,
      result: v.result,
      remark: v.remark,
      inspectorName: inspectorName.value.trim()
    }))
    await api.submitSafetyBatch(selectedBatchId.value, {
      inspectorName: inspectorName.value.trim(),
      items
    })
    tip.value = '安全员复核已提交，等待另一名运营负责人最终放行'
    await load()
    reviewRide.value = rows.value.find((r) => r.id === reviewRide.value.id) || reviewRide.value
    await reloadReviewBatches()
  } catch (e) {
    error.value = e.message
  } finally {
    reviewSaving.value = false
  }
}

async function reviseBatch() {
  reviewSaving.value = true; error.value = ''
  try {
    const fresh = await api.reviseSafetyBatch(selectedBatchId.value)
    tip.value = '原批次已留痕失效，已生成新的复核批次'
    await load()
    reviewRide.value = rows.value.find((r) => r.id === reviewRide.value.id) || reviewRide.value
    await reloadReviewBatches()
    await selectBatch(fresh.id)
  } catch (e) {
    error.value = e.message
  } finally {
    reviewSaving.value = false
  }
}

async function releaseBatch() {
  error.value = ''
  if (!approverName.value.trim()) {
    error.value = '请填写最终放行人（运营负责人）姓名'
    return
  }
  if (samePerson.value) {
    error.value = '提交人不能担任最终放行人，请更换运营负责人'
    return
  }
  reviewReleasing.value = true
  try {
    const result = await api.releaseSafetyBatch(selectedBatchId.value, {
      approverName: approverName.value.trim()
    })
    tip.value = result.repeated
      ? '该放行请求已处理过，设施仍为开放状态'
      : '复开放行成功，设施已立即按最新身高/年龄限制开放'
    reviewDialog.value = false
    await load()
  } catch (e) {
    // 后端在并发失败时会返回设施当前状态；刷新列表，避免旧页面把开放状态回滚。
    error.value = e.message
    await load()
    reviewRide.value = rows.value.find((r) => r.id === reviewRide.value.id) || reviewRide.value
    await reloadReviewBatches()
  } finally {
    reviewReleasing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">游乐设施 · 检修安全复开放行</span>
        <span class="panel-desc">进入检修生成批次；四项必检合格后安全员提交，另一名负责人放行；结果 24 小时有效</span>
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
        <el-table-column prop="maxAge" label="最高年龄" width="100" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '开放' ? 'success' : row.status === '检修' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前复核" width="120">
          <template #default="{ row }">
            <el-button
              v-if="activeBatchMap[row.id]"
              link
              :type="activeBatchMap[row.id].status === '待放行' ? 'primary' : 'warning'"
              @click="openReview(row)"
            >
              {{ activeBatchMap[row.id].status }}
            </el-button>
            <span v-else-if="row.status === '检修'">待建批次</span>
            <el-button v-else link type="info" @click="openReview(row)">历史</el-button>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="350" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openBoard(row)">乘坐校验</el-button>
            <el-button size="small" :disabled="row.status === '检修'" @click="transfer(row, '检修')">检修</el-button>
            <el-button size="small" :disabled="row.status !== '开放'" @click="transfer(row, '关闭')">关闭</el-button>
            <el-button
              v-if="row.status === '检修'"
              size="small"
              type="success"
              plain
              @click="openReview(row)"
            >
              安全放行
            </el-button>
            <el-button
              v-else-if="row.status === '关闭'"
              size="small"
              type="success"
              plain
              @click="transfer(row, '开放')"
            >
              开放
            </el-button>
            <el-button v-else size="small" type="success" plain @click="openReview(row)">复核历史</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建设施" width="520px">
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="设施编号"><el-input v-model="form.rideCode" placeholder="如 R-009" /></el-form-item>
        <el-form-item label="设施名称"><el-input v-model="form.rideName" /></el-form-item>
        <el-form-item label="最低身高(cm)"><el-input v-model="form.minHeightCm" type="number" placeholder="不限留空" /></el-form-item>
        <el-form-item label="最高年龄"><el-input v-model="form.maxAge" type="number" placeholder="不限留空" /></el-form-item>
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

    <el-dialog v-model="reviewDialog" :title="`检修安全复核 · ${reviewRide?.rideName || ''}`" width="980px" top="5vh">
      <div v-loading="reviewLoading">
        <el-alert
          title="闭环要求：安全员逐项记录四项必检结果并提交；提交人不得放行。负责人仅能放行仍处检修、内容未过期且限制未变化的批次。"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
        />

        <div class="review-toolbar">
          <div>
            <el-tag :type="reviewRide?.status === '开放' ? 'success' : reviewRide?.status === '检修' ? 'warning' : 'info'">
              设施当前：{{ reviewRide?.status }}
            </el-tag>
            <span class="review-limit">
              最低身高 {{ reviewRide?.minHeightCm ?? '不限' }} cm / 最高年龄 {{ reviewRide?.maxAge ?? '不限' }}
            </span>
          </div>
          <el-button
            type="primary"
            plain
            :loading="reviewSaving"
            :disabled="reviewRide?.status !== '检修'"
            @click="startBatch"
          >
            生成/打开本次批次
          </el-button>
        </div>

        <el-table :data="reviewBatches" size="small" border style="margin: 12px 0">
          <el-table-column prop="batchNo" label="批次号" width="170" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="batchTagType(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="inspectorName" label="安全员" width="90" />
          <el-table-column prop="approverName" label="放行人" width="90" />
          <el-table-column prop="invalidReason" label="失效/追溯说明" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link type="primary" @click="selectBatch(row.id)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>

        <template v-if="selectedBatch">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="批次号">{{ selectedBatch.batchNo }}</el-descriptions-item>
            <el-descriptions-item label="批次状态">
              <el-tag size="small" :type="batchTagType(selectedBatch.status)">{{ selectedBatch.status }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="有效期至">
              {{ selectedBatch.validUntil || '提交后 24 小时内有效' }}
            </el-descriptions-item>
            <el-descriptions-item label="提交安全员">{{ selectedBatch.inspectorName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="最终放行人">{{ selectedBatch.approverName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="提交时限制">
              {{ selectedBatch.snapshotMinHeightCm ?? '不限' }}cm / {{ selectedBatch.snapshotMaxAge ?? '不限' }}岁
            </el-descriptions-item>
          </el-descriptions>

          <el-form label-width="110px" class="dialog-form" style="margin-top: 12px">
            <el-form-item label="安全员姓名">
              <el-input
                v-model="inspectorName"
                placeholder="记录四项检查结果的安全员"
                style="width: 260px"
                :disabled="selectedBatch.status !== '检修中'"
              />
            </el-form-item>
          </el-form>

          <el-table :data="selectedItems" size="small" border>
            <el-table-column prop="itemName" label="必检项" width="150" />
            <el-table-column label="结果" width="210">
              <template #default="{ row }">
                <el-radio-group
                  v-if="selectedBatch.status === '检修中'"
                  v-model="itemForms[row.itemCode].result"
                  size="small"
                >
                  <el-radio value="合格">合格</el-radio>
                  <el-radio value="不合格">不合格</el-radio>
                </el-radio-group>
                <el-tag v-else size="small" :type="row.result === '合格' ? 'success' : row.result === '不合格' ? 'danger' : 'info'">
                  {{ row.result }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="备注 / 整改记录" min-width="220">
              <template #default="{ row }">
                <el-input
                  v-if="selectedBatch.status === '检修中'"
                  v-model="itemForms[row.itemCode].remark"
                  size="small"
                  placeholder="可填写检查说明；历史提交后锁定"
                />
                <span v-else>{{ row.remark || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="检查人" width="100">
              <template #default="{ row }">{{ row.inspectorName || '—' }}</template>
            </el-table-column>
          </el-table>

          <div v-if="selectedBatch.status === '待放行'" class="release-box">
            <el-input
              v-model="approverName"
              placeholder="运营负责人姓名（不能与提交安全员为同一人）"
              style="width: 360px"
              :class="{ 'person-error': samePerson }"
            />
            <el-button
              type="success"
              :loading="reviewReleasing"
              :disabled="samePerson || !approverName.trim()"
              @click="releaseBatch"
            >
              确认恢复开放
            </el-button>
            <el-button plain :loading="reviewSaving" @click="reviseBatch">需要重检，作废旧批次</el-button>
          </div>

          <div v-if="['已失效', '已过期'].includes(selectedBatch.status)" class="release-box">
            <span>该批次不可放行；历史结果已保留。</span>
            <el-button
              v-if="reviewRide?.status === '检修'"
              type="primary"
              :loading="reviewSaving"
              @click="startBatch"
            >
              生成新批次重新复核
            </el-button>
          </div>
        </template>
      </div>

      <template #footer>
        <el-button
          v-if="selectedBatch?.status === '检修中'"
          type="primary"
          :loading="reviewSaving"
          @click="submitReview"
        >
          安全员提交复核
        </el-button>
        <el-button @click="reviewDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.review-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.review-limit {
  margin-left: 12px;
  color: #606266;
}

.release-box {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  padding: 12px;
  background: #f7fbff;
  border: 1px solid #d9ecff;
  border-radius: 6px;
}

.person-error :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px #f56c6c inset;
}
</style>
