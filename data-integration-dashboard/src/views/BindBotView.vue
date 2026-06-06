<template>
  <div class="bind-bot-page">
    <el-card style="max-width: 500px; margin: 100px auto">
      <template #header>
        <div class="card-header">
          <span>绑定微信 Bot</span>
        </div>
      </template>
      <div class="qrcode-area">
        <div v-if="!qrCodeLoaded" style="text-align: center">
          <el-button type="primary" :loading="loading" @click="generateQrCode">生成二维码</el-button>
        </div>
        <div v-else class="qrcode-container">
          <img v-if="qrCodeBase64" :src="'data:image/png;base64,' + qrCodeBase64" alt="二维码" class="qrcode-img" />
          <div v-else class="qrcode-placeholder">
            <el-icon size="80"><Picture /></el-icon>
            <p>请使用微信扫描二维码</p>
          </div>
          <el-alert :title="statusText" :type="statusType" :closable="false" style="margin-top: 16px" />
          <el-progress v-if="status === 'wait' || status === 'scaned'" :percentage="progress" :indeterminate="status === 'scaned'" style="margin-top: 12px" />
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { generateBotQrCode, checkBotScanStatus } from '@/api'

const router = useRouter()
const loading = ref(false)
const qrCodeLoaded = ref(false)
const qrCodeBase64 = ref('')
const ticket = ref('')
const status = ref('')
const statusText = ref('')
const statusType = ref<'info' | 'success' | 'warning' | 'error'>('info')
const progress = ref(0)
let pollTimer: number | null = null

const generateQrCode = async () => {
  loading.value = true
  try {
    const res: any = await generateBotQrCode()
    if (res.code === 200 && res.data) {
      qrCodeLoaded.value = true
      qrCodeBase64.value = res.data.qrCodeBase64 || ''
      ticket.value = res.data.ticket
      status.value = 'wait'
      statusText.value = '请使用微信扫描二维码'
      statusType.value = 'info'
      progress.value = 0
      startPolling()
    } else {
      ElMessage.error('生成二维码失败')
    }
  } catch (e) {
    ElMessage.error('请求失败')
  } finally {
    loading.value = false
  }
}

const startPolling = () => {
  if (pollTimer) clearInterval(pollTimer)
  const totalSecs = 300
  const startTime = Date.now()
  pollTimer = window.setInterval(async () => {
    const elapsed = Math.floor((Date.now() - startTime) / 1000)
    progress.value = Math.min(Math.floor((elapsed / totalSecs) * 100), 99)

    if (elapsed >= totalSecs) {
      stopPolling()
      status.value = 'expired'
      statusText.value = '二维码已过期，请重新生成'
      statusType.value = 'warning'
      return
    }

    try {
      const res: any = await checkBotScanStatus(ticket.value)
      if (res.code === 200 && res.data) {
        const s = res.data.status
        if (s === 'scaned') {
          status.value = 'scaned'
          statusText.value = '已扫码，请在微信上确认登录...'
          statusType.value = 'info'
        } else if (s === 'confirmed') {
          stopPolling()
          status.value = 'confirmed'
          statusText.value = '绑定成功！即将跳转...'
          statusType.value = 'success'
          progress.value = 100
          // 保存 token
          if (res.data.token) {
            localStorage.setItem('wechat_token', res.data.token)
          }
          setTimeout(() => {
            router.push('/admin/bots')
          }, 1500)
        } else if (s === 'expired') {
          stopPolling()
          status.value = 'expired'
          statusText.value = '二维码已过期'
          statusType.value = 'warning'
        }
      }
    } catch (e) {
      // 轮询失败继续
    }
  }, 2000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onUnmounted(stopPolling)
</script>

<style scoped>
.bind-bot-page {
  min-height: 100vh;
  background-color: #f0f2f5;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 100px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.qrcode-area {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.qrcode-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
}

.qrcode-img {
  width: 250px;
  height: 250px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
}

.qrcode-placeholder {
  width: 250px;
  height: 250px;
  border: 1px dashed #c0c4cc;
  border-radius: 4px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #909399;
}
</style>
