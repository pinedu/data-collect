<template>
  <div class="dashboard">
    <!-- 统计卡片 -->
    <el-row :gutter="20">
      <el-col :span="6" v-for="card in statCards" :key="card.title">
        <el-card class="stat-card" shadow="hover">
          <div class="stat-icon" :style="{ backgroundColor: card.color }">
            <el-icon size="32" color="#fff">
              <component :is="card.icon" />
            </el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ card.value }}</div>
            <div class="stat-title">{{ card.title }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 图表区域 -->
    <el-row :gutter="20" class="chart-row">
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>各项目数据分布</span>
          </template>
          <v-chart class="chart" :option="pieOption" autoresize />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>近30天考勤趋势</span>
          </template>
          <v-chart class="chart" :option="lineOption" autoresize />
        </el-card>
      </el-col>
    </el-row>

    <!-- 同步任务状态 -->
    <el-card class="sync-card">
      <template #header>
        <span>同步任务状态</span>
        <el-button text @click="refreshData">刷新</el-button>
      </template>
      <el-table :data="syncTasks" stripe>
        <el-table-column prop="taskName" label="任务名称" />
        <el-table-column prop="dataType" label="数据类型" />
        <el-table-column prop="syncType" label="同步类型" />
        <el-table-column prop="lastSyncTime" label="最后同步时间" />
        <el-table-column prop="status" label="状态">
          <template #default="scope">
            <el-tag :type="scope.row.status === 'SUCCESS' ? 'success' : 'danger'">
              {{ scope.row.status === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { PieChart, LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { getStatistics } from '@/api'

use([CanvasRenderer, PieChart, LineChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent])

const statCards = ref([
  { title: '项目数量', value: 0, icon: 'FolderOpened', color: '#409EFF' },
  { title: '人员数量', value: 0, icon: 'User', color: '#67C23A' },
  { title: '考勤记录', value: 0, icon: 'Calendar', color: '#E6A23C' },
  { title: '工资记录', value: 0, icon: 'Money', color: '#F56C6C' }
])

const syncTasks = ref([])

const pieOption = ref({
  tooltip: { trigger: 'item' },
  legend: { top: '5%', left: 'center' },
  series: [{
    type: 'pie',
    radius: ['40%', '70%'],
    avoidLabelOverlap: false,
    itemStyle: { borderRadius: 10, borderColor: '#fff', borderWidth: 2 },
    label: { show: false, position: 'center' },
    emphasis: { label: { show: true, fontSize: 20, fontWeight: 'bold' } },
    data: []
  }]
})

const lineOption = ref({
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: [] },
  yAxis: { type: 'value' },
  series: [{ data: [], type: 'line', smooth: true }]
})

const refreshData = async () => {
  try {
    const res: any = await getStatistics()
    if (res.code === 200) {
      const data = res.data
      statCards.value[0].value = data.projectCount || 0
      statCards.value[1].value = data.personCount || 0
      statCards.value[2].value = data.attendanceCount || 0
      statCards.value[3].value = data.payrollCount || 0
      syncTasks.value = data.syncLogs || []

      // 更新图表数据
      if (data.projectDistribution) {
        pieOption.value.series[0].data = data.projectDistribution
      }
      if (data.attendanceTrend) {
        lineOption.value.xAxis.data = data.attendanceTrend.dates
        lineOption.value.series[0].data = data.attendanceTrend.values
      }
    }
  } catch (e) {
    console.error('获取统计数据失败', e)
  }
}

onMounted(() => {
  refreshData()
  setInterval(refreshData, 30000)
})
</script>

<style scoped>
.stat-card {
  display: flex;
  align-items: center;
  padding: 10px;
}

.stat-icon {
  width: 60px;
  height: 60px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 16px;
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  color: #303133;
}

.stat-title {
  font-size: 14px;
  color: #909399;
  margin-top: 4px;
}

.chart-row {
  margin-top: 20px;
}

.chart {
  height: 300px;
}

.sync-card {
  margin-top: 20px;
}
</style>
