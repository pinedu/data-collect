<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>同步任务管理</span>
          <el-button type="primary" @click="loadTasks">刷新</el-button>
        </div>
      </template>
      <el-table :data="tasks" stripe>
        <el-table-column prop="jobName" label="任务名称" />
        <el-table-column prop="jobHandler" label="执行器" />
        <el-table-column prop="scheduleType" label="调度类型" width="100" />
        <el-table-column prop="scheduleConf" label="调度配置" width="120" />
        <el-table-column prop="glueType" label="运行模式" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.triggerStatus === 1 ? 'success' : 'info'">
              {{ scope.row.triggerStatus === 1 ? '运行中' : '已停止' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="scope">
            <el-button link type="primary" @click="triggerJob(scope.row.id)">手动执行</el-button>
            <el-button link type="success" @click="viewLog(scope.row.id)">查看日志</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'

interface SyncTask {
  id: string
  jobName: string
  jobHandler: string
  scheduleType: string
  scheduleConf: string
  glueType: string
  triggerStatus: number
}

const tasks = ref<SyncTask[]>([])

const loadTasks = async () => {
  // 实际应调用XXL-JOB Admin API
  tasks.value = [
    { id: '1', jobName: '考勤全量同步', jobHandler: 'attendanceFullSyncJob', scheduleType: 'CRON', scheduleConf: '0 0 21 * * ?', glueType: 'BEAN', triggerStatus: 1 },
    { id: '2', jobName: '考勤增量同步', jobHandler: 'attendanceIncrementalSyncJob', scheduleType: 'CRON', scheduleConf: '0 0 * * * ?', glueType: 'BEAN', triggerStatus: 1 },
    { id: '3', jobName: '工资全量同步', jobHandler: 'payrollSyncJob', scheduleType: 'CRON', scheduleConf: '0 30 21 * * ?', glueType: 'BEAN', triggerStatus: 1 },
    { id: '4', jobName: '人员信息同步', jobHandler: 'personSyncJob', scheduleType: 'CRON', scheduleConf: '0 0 2 * * ?', glueType: 'BEAN', triggerStatus: 1 },
    { id: '5', jobName: '班组信息同步', jobHandler: 'teamSyncJob', scheduleType: 'CRON', scheduleConf: '0 0 3 * * ?', glueType: 'BEAN', triggerStatus: 1 },
    { id: '6', jobName: 'Token续期', jobHandler: 'tokenRefreshJob', scheduleType: 'CRON', scheduleConf: '0 0 */6 * * ?', glueType: 'BEAN', triggerStatus: 1 }
  ]
}

const triggerJob = (id: string) => {
  ElMessage.success(`已触发任务 #${id}`)
}

const viewLog = (id: string) => {
  ElMessage.info(`查看任务 #${id} 日志`)
}

onMounted(loadTasks)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
