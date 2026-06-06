<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>消息管理</span>
          <el-button type="primary" @click="dialogVisible = true">发送消息</el-button>
        </div>
      </template>
      <el-table :data="messages" stripe v-loading="loading">
        <el-table-column prop="botId" label="Bot ID" width="100" />
        <el-table-column prop="targetUserId" label="目标用户" width="150" />
        <el-table-column prop="messageType" label="类型" width="80" />
        <el-table-column label="内容" show-overflow-tooltip>
          <template #default="scope">
            {{ truncateText(scope.row.content, 60) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag v-if="scope.row.status === 'SENT'" type="success">已发送</el-tag>
            <el-tag v-else-if="scope.row.status === 'PENDING'" type="warning">待发送</el-tag>
            <el-tag v-else-if="scope.row.status === 'FAILED'" type="danger">失败</el-tag>
            <el-tag v-else>{{ scope.row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发送时间" width="180">
          <template #default="scope">
            {{ scope.row.sentAt || scope.row.scheduledAt || '-' }}
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px"
        @current-change="loadMessages"
      />
    </el-card>

    <!-- 发送消息弹窗 -->
    <el-dialog v-model="dialogVisible" title="发送消息" width="500px" @close="resetForm">
      <el-form :model="form" label-width="100px">
        <el-form-item label="选择 Bot" required>
          <el-select v-model="form.botId" placeholder="请选择 Bot" style="width: 100%">
            <el-option v-for="bot in approvedBots" :key="bot.id" :label="bot.botName || bot.botId" :value="bot.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标用户ID" required>
          <el-input v-model="form.targetUserId" placeholder="输入微信用户ID" />
        </el-form-item>
        <el-form-item label="消息内容" required>
          <el-input v-model="form.content" type="textarea" :rows="4" placeholder="输入消息内容" />
        </el-form-item>
        <el-form-item label="定时发送">
          <el-date-picker
            v-model="form.scheduledAt"
            type="datetime"
            placeholder="选择发送时间（留空即立即发送）"
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="sending" @click="handleSend">发送</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getMessageList, sendMessage, getBotList } from '@/api'

const loading = ref(false)
const messages = ref([])
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)

const dialogVisible = ref(false)
const sending = ref(false)
const approvedBots = ref<any[]>([])

const form = ref({
  botId: null as string | null,
  targetUserId: '',
  content: '',
  scheduledAt: null as string | null
})

const truncateText = (text: string, maxLen: number) => {
  if (!text) return ''
  return text.length > maxLen ? text.substring(0, maxLen) + '...' : text
}

const loadMessages = async () => {
  loading.value = true
  try {
    const res: any = await getMessageList({ pageNum: pageNum.value, pageSize: pageSize.value })
    if (res.code === 200 && res.data) {
      messages.value = res.data.records || []
      total.value = Number(res.data.total || 0)
    }
  } catch (e) {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

const loadApprovedBots = async () => {
  try {
    const res: any = await getBotList({ pageNum: 1, pageSize: 100, status: 'APPROVED' })
    if (res.code === 200 && res.data) {
      approvedBots.value = res.data.records || []
    }
  } catch (e) {
    // ignore
  }
}

const handleSend = async () => {
  if (!form.value.botId || !form.value.targetUserId || !form.value.content) {
    ElMessage.warning('请填写完整信息')
    return
  }
  sending.value = true
  try {
    const data: any = {
      botId: form.value.botId,
      targetUserId: form.value.targetUserId,
      content: form.value.content,
      messageType: 'TEXT'
    }
    if (form.value.scheduledAt) {
      data.scheduledAt = form.value.scheduledAt
    }
    const res: any = await sendMessage(data)
    if (res.code === 200) {
      ElMessage.success(`发送成功，共 ${res.data?.successCount || 0} 条`)
      dialogVisible.value = false
      resetForm()
      loadMessages()
    } else {
      ElMessage.error(res.message || '发送失败')
    }
  } catch (e) {
    ElMessage.error('发送失败')
  } finally {
    sending.value = false
  }
}

const resetForm = () => {
  form.value = { botId: null, targetUserId: '', content: '', scheduledAt: null }
}

onMounted(() => {
  loadMessages()
  loadApprovedBots()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
