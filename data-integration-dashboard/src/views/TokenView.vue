<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>Token管理</span>
          <el-button type="primary" @click="refreshAll">全部刷新</el-button>
        </div>
      </template>
      <el-table :data="configs" stripe v-loading="loading">
        <el-table-column prop="sourceProjectNum" label="项目编号" width="150" />
        <el-table-column prop="projectName" label="项目名称" />
        <el-table-column prop="account" label="账号" width="150" />
        <el-table-column label="Token状态" width="200">
          <template #default="scope">
            <div v-if="scope.row.tokenStatus">
              <el-progress
                :percentage="scope.row.tokenStatus.percentage"
                :status="scope.row.tokenStatus.percentage < 20 ? 'exception' : ''"
                :stroke-width="8"
              />
              <span style="font-size: 12px; color: #909399">
                剩余 {{ scope.row.tokenStatus.remainingHours }} 小时
              </span>
            </div>
            <el-tag v-else type="danger">未获取</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="scope">
            <el-button link type="primary" @click="refreshToken(scope.row.sourceProjectNum)">刷新</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card style="margin-top: 20px">
      <template #header>
        <span>扫码获取Token</span>
      </template>
      <el-alert
        title="请使用微信扫描第三方平台二维码，获取Token后通过接口提交"
        type="info"
        :closable="false"
      />
      <div style="margin-top: 20px">
        <el-input v-model="scanToken" placeholder="粘贴扫码获取的Token" style="width: 400px" />
        <el-button type="primary" style="margin-left: 10px" @click="submitToken">提交</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getProjectConfigList, getTokenStatus, refreshToken as refreshTokenApi } from '@/api'

const loading = ref(false)
const configs = ref([])
const scanToken = ref('')

const loadConfigs = async () => {
  loading.value = true
  try {
    const res: any = await getProjectConfigList()
    if (res.code === 200) {
      const list = res.data || []
      // 获取每个项目的Token状态
      for (const item of list) {
        try {
          const statusRes: any = await getTokenStatus()
          if (statusRes.code === 200) {
            item.tokenStatus = statusRes.data
          }
        } catch (e) {
          item.tokenStatus = null
        }
      }
      configs.value = list
    }
  } finally {
    loading.value = false
  }
}

const refreshAll = async () => {
  try {
    await refreshTokenApi()
    ElMessage.success('全部刷新成功')
    loadConfigs()
  } catch (e) {
    ElMessage.error('刷新失败')
  }
}

const refreshToken = async (projectNum: string) => {
  try {
    await refreshTokenApi()
    ElMessage.success(`项目 ${projectNum} Token刷新成功`)
    loadConfigs()
  } catch (e) {
    ElMessage.error('刷新失败')
  }
}

const submitToken = () => {
  if (!scanToken.value) {
    ElMessage.warning('请输入Token')
    return
  }
  ElMessage.success('Token已提交')
  scanToken.value = ''
}

onMounted(loadConfigs)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
