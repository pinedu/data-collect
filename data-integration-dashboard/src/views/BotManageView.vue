<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>Bot 管理</span>
          <div>
            <el-select v-model="filterStatus" placeholder="按状态筛选" clearable style="width: 140px; margin-right: 10px" @change="loadBots">
              <el-option label="待审核" value="PENDING" />
              <el-option label="已通过" value="APPROVED" />
              <el-option label="已拒绝" value="REJECTED" />
            </el-select>
            <el-button type="primary" @click="$router.push('/admin/bind')">绑定新Bot</el-button>
          </div>
        </div>
      </template>
      <el-table :data="bots" stripe v-loading="loading">
        <el-table-column prop="botId" label="Bot ID" width="200" />
        <el-table-column prop="botName" label="Bot名称" />
        <el-table-column label="绑定用户" width="120">
          <template #default="scope">
            {{ scope.row.userId || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="scope">
            <el-tag v-if="scope.row.status === 'APPROVED'" type="success">已通过</el-tag>
            <el-tag v-else-if="scope.row.status === 'PENDING'" type="warning">待审核</el-tag>
            <el-tag v-else-if="scope.row.status === 'REJECTED'" type="danger">已拒绝</el-tag>
            <el-tag v-else>{{ scope.row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="scope">
            {{ scope.row.createdAt || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="scope">
            <template v-if="scope.row.status === 'PENDING'">
              <el-button link type="success" @click="handleApprove(scope.row.id, 'APPROVE')">通过</el-button>
              <el-button link type="danger" @click="handleApprove(scope.row.id, 'REJECT')">拒绝</el-button>
            </template>
            <span v-else style="color: #909399">-</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px"
        @current-change="loadBots"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getBotList, approveBot } from '@/api'

const loading = ref(false)
const bots = ref([])
const filterStatus = ref('')
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)

const token = localStorage.getItem('wechat_token') || ''

const loadBots = async () => {
  loading.value = true
  try {
    const params: any = { pageNum: pageNum.value, pageSize: pageSize.value }
    if (filterStatus.value) params.status = filterStatus.value
    const res: any = await getBotList(params)
    if (res.code === 200 && res.data) {
      bots.value = res.data.records || []
      total.value = Number(res.data.total || 0)
    }
  } catch (e) {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

const handleApprove = async (botId: string, action: string) => {
  try {
    const res: any = await approveBot({ botId, action })
    if (res.code === 200) {
      ElMessage.success(action === 'APPROVE' ? '审核通过' : '已拒绝')
      loadBots()
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

onMounted(loadBots)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
