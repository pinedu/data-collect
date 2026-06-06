<template>
  <div class="sync-dashboard">
    <!-- 项目选择器 -->
    <el-card class="project-selector-card">
      <div class="selector-row">
        <span class="selector-label">选择项目：</span>
        <el-select
          v-model="selectedProject"
          placeholder="请选择项目"
          filterable
          style="width: 400px"
          @change="onProjectChange"
        >
          <el-option
            v-for="p in projectOptions"
            :key="p.sourceProjectNum"
            :label="p.projectName + ' (' + p.sourceProjectNum + ')'"
            :value="p.sourceProjectNum"
          />
        </el-select>
        <el-button type="primary" @click="refreshData" :loading="loading" style="margin-left: 12px">
          刷新
        </el-button>
        <el-tag v-if="lastRefreshTime" type="info" style="margin-left: 12px">
          上次刷新：{{ lastRefreshTime }}
        </el-tag>
      </div>
    </el-card>

    <!-- 空状态提示 -->
    <el-empty v-if="!selectedProject" description="请先选择一个项目查看同步数据" />

    <!-- 同步数据对比卡片 -->
    <div v-if="selectedProject && dashboardItems.length > 0" class="dashboard-cards">
      <el-row :gutter="20">
        <el-col :span="12" v-for="item in dashboardItems" :key="item.dataType" style="margin-bottom: 20px">
          <el-card class="data-compare-card" shadow="hover">
            <template #header>
              <div class="card-header">
                <span class="card-title">{{ item.dataTypeName }}数据对比</span>
                <el-tag :type="getSyncStatusType(item)" size="small">
                  {{ getSyncStatusText(item) }}
                </el-tag>
              </div>
            </template>
            <div class="compare-body">
              <div class="compare-row platform-row">
                <div class="compare-label">
                  <el-icon color="#409EFF" size="20"><DataLine /></el-icon>
                  <span>本平台{{ item.dataTypeName }}数</span>
                </div>
                <div class="compare-value platform-value">{{ formatNumber(item.localTotal) }}</div>
              </div>
              <div class="compare-divider"></div>
              <div class="compare-row third-party-row">
                <div class="compare-label">
                  <el-icon color="#E6A23C" size="20"><Connection /></el-icon>
                  <span>采集方{{ item.dataTypeName }}数</span>
                </div>
                <div class="compare-value third-party-value">{{ formatNumber(item.thirdPartyTotal) }}</div>
              </div>
              <div class="compare-footer">
                <div class="sync-rate">
                  <span>同步率：</span>
                  <el-progress
                    :percentage="getDiffPercent(item)"
                    :color="getProgressColor(item)"
                    :stroke-width="8"
                    style="width: 120px"
                  />
                  <span class="rate-text">{{ item.diffRate }}</span>
                </div>
                <div class="sync-time">
                  <el-icon size="14"><Clock /></el-icon>
                  <span>同步时间：{{ formatTime(item.lastSyncTime) }}</span>
                </div>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { getSyncDashboard, getProjectOptions } from '@/api'

interface ProjectOption {
  sourceProjectNum: string
  projectName: string
}

interface DashboardItem {
  dataType: string
  dataTypeName: string
  thirdPartyTotal: number
  localTotal: number
  lastSyncTime: string | null
  diffRate: string
}

const selectedProject = ref('')
const projectOptions = ref<ProjectOption[]>([])
const dashboardItems = ref<DashboardItem[]>([])
const loading = ref(false)
const lastRefreshTime = ref('')
let refreshTimer: ReturnType<typeof setInterval> | null = null

const loadProjects = async () => {
  try {
    const res: any = await getProjectOptions()
    if (res.code === 200) {
      projectOptions.value = res.data || []
    }
  } catch (e) {
    console.error('加载项目列表失败', e)
  }
}

const refreshData = async () => {
  if (!selectedProject.value) return
  loading.value = true
  try {
    const res: any = await getSyncDashboard(selectedProject.value)
    if (res.code === 200) {
      dashboardItems.value = res.data || []
      const now = new Date()
      lastRefreshTime.value = now.toLocaleTimeString()
    }
  } catch (e) {
    console.error('获取同步看板数据失败', e)
  } finally {
    loading.value = false
  }
}

const onProjectChange = () => {
  refreshData()
  // 切换项目后重新设置定时刷新
  if (refreshTimer) clearInterval(refreshTimer)
  if (selectedProject.value) {
    refreshTimer = setInterval(refreshData, 30000)
  }
}

const formatNumber = (val: number): string => {
  if (val == null || val === undefined) return '-'
  return val.toLocaleString()
}

const formatTime = (time: string | null): string => {
  if (!time) return '暂无'
  const d = new Date(time)
  return d.toLocaleString()
}

const getDiffPercent = (item: DashboardItem): number => {
  if (!item.thirdPartyTotal || item.thirdPartyTotal === 0) return 0
  return Math.round((item.localTotal / item.thirdPartyTotal) * 100)
}

const getProgressColor = (item: DashboardItem) => {
  const pct = getDiffPercent(item)
  if (pct >= 95) return '#67C23A'
  if (pct >= 70) return '#E6A23C'
  return '#F56C6C'
}

const getSyncStatusType = (item: DashboardItem): 'success' | 'warning' | 'danger' | 'info' => {
  if (!item.lastSyncTime) return 'info'
  const pct = getDiffPercent(item)
  if (pct >= 95) return 'success'
  if (pct >= 70) return 'warning'
  return 'danger'
}

const getSyncStatusText = (item: DashboardItem): string => {
  if (!item.lastSyncTime) return '未同步'
  const pct = getDiffPercent(item)
  if (pct >= 95) return '数据一致'
  if (pct >= 70) return '有差异'
  return '差异较大'
}

onMounted(() => {
  loadProjects()
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<style scoped>
.sync-dashboard {
  min-height: 100%;
}

.project-selector-card {
  margin-bottom: 20px;
}

.selector-row {
  display: flex;
  align-items: center;
}

.selector-label {
  font-size: 14px;
  color: #606266;
  font-weight: 500;
  white-space: nowrap;
}

.dashboard-cards {
  margin-top: 10px;
}

.data-compare-card {
  height: 100%;
}

.data-compare-card .card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.compare-body {
  padding: 8px 0;
}

.compare-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 12px;
  border-radius: 8px;
}

.platform-row {
  background-color: #f0f7ff;
}

.third-party-row {
  background-color: #fef7e8;
}

.compare-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  color: #606266;
}

.compare-value {
  font-size: 32px;
  font-weight: bold;
}

.platform-value {
  color: #409EFF;
}

.third-party-value {
  color: #E6A23C;
}

.compare-divider {
  height: 1px;
  background: #ebeef5;
  margin: 8px 12px;
}

.compare-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 12px 4px;
  flex-wrap: wrap;
  gap: 8px;
}

.sync-rate {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #909399;
}

.rate-text {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

.sync-time {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #c0c4cc;
}
</style>
