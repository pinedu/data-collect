<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>考勤查询</span>
          <div class="filter-bar">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              value-format="YYYY-MM-DD"
            />
            <el-select v-model="projectNum" filterable clearable placeholder="选择项目" style="width: 240px; margin-left: 10px">
              <el-option
                v-for="p in projectOptions"
                :key="p.sourceProjectNum"
                :label="p.projectName"
                :value="p.sourceProjectNum"
              />
            </el-select>
            <el-button type="primary" style="margin-left: 10px" @click="loadAttendance">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="attendanceList" stripe v-loading="loading">
        <el-table-column prop="projectName" label="项目名称" min-width="160" />
        <el-table-column prop="personName" label="姓名" width="100" />
        <el-table-column prop="idCard" label="身份证号" width="180" />
        <el-table-column prop="attendanceTime" label="考勤时间" width="160" />
        <el-table-column prop="attendanceType" label="类型" width="80">
          <template #default="scope">
            <el-tag :type="scope.row.attendanceType === 'IN' ? 'success' : 'warning'">
              {{ scope.row.attendanceType === 'IN' ? '进场' : '出场' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="deviceName" label="设备名称" />
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        :hide-on-single-page="false"
        layout="total, sizes, prev, pager, next, jumper"
        class="pagination"
        @current-change="loadAttendance"
        @size-change="loadAttendance"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getAttendanceList, getProjectOptions } from '@/api'

const loading = ref(false)
const attendanceList = ref([])
const projectOptions = ref<any[]>([])
const dateRange = ref([])
const projectNum = ref('')
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

const loadAttendance = async () => {
  if (!projectNum.value) {
    ElMessage.warning('请先选择项目')
    return
  }
  loading.value = true
  try {
    const params: any = { pageNum: page.value, pageSize: pageSize.value, sourceProjectNum: projectNum.value }
    if (dateRange.value?.length === 2) {
      params.beginDate = dateRange.value[0]
      params.endDate = dateRange.value[1]
    }
    const res: any = await getAttendanceList(params)
    if (res.code === 200) {
      attendanceList.value = res.data?.records || []
      total.value = Number(res.data?.total || 0)
    }
  } finally {
    loading.value = false
  }
}

const loadProjects = async () => {
  try {
    const res: any = await getProjectOptions()
    if (res.code === 200) {
      projectOptions.value = res.data || []
      // 默认选中第一个项目并查询
      if (projectOptions.value.length > 0) {
        projectNum.value = projectOptions.value[0].sourceProjectNum
        loadAttendance()
      }
    }
  } catch (e) {
    console.error('加载项目列表失败', e)
  }
}

onMounted(() => {
  loadProjects()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-bar {
  display: flex;
  align-items: center;
}

.pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
