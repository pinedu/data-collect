<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>工资查询</span>
          <div class="filter-bar">
            <el-date-picker
              v-model="month"
              type="month"
              placeholder="选择月份"
              value-format="YYYY-MM"
            />
            <el-select v-model="projectNum" filterable clearable placeholder="选择项目" style="width: 240px; margin-left: 10px">
              <el-option
                v-for="p in projectOptions"
                :key="p.sourceProjectNum"
                :label="p.projectName"
                :value="p.sourceProjectNum"
              />
            </el-select>
            <el-button type="primary" style="margin-left: 10px" @click="loadPayroll">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="payrollList" stripe v-loading="loading">
        <el-table-column prop="projectName" label="项目名称" min-width="160" />
        <el-table-column prop="payMonth" label="工资月份" width="100" />
        <el-table-column prop="personName" label="姓名" width="100" />
        <el-table-column prop="idCard" label="身份证号" width="180" />
        <el-table-column prop="totalAmount" label="应发金额" width="120">
          <template #default="scope">
            <span style="color: #F56C6C; font-weight: bold">{{ scope.row.totalAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="actualAmount" label="实发金额" width="120">
          <template #default="scope">
            <span style="color: #67C23A; font-weight: bold">{{ scope.row.actualAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="payDate" label="发放日期" width="120" />
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        :hide-on-single-page="false"
        layout="total, sizes, prev, pager, next, jumper"
        class="pagination"
        @current-change="loadPayroll"
        @size-change="loadPayroll"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getPayrollList, getProjectOptions } from '@/api'

const loading = ref(false)
const payrollList = ref([])
const projectOptions = ref<any[]>([])
const month = ref('')
const projectNum = ref('')
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

const loadPayroll = async () => {
  if (!projectNum.value) {
    ElMessage.warning('请先选择项目')
    return
  }
  loading.value = true
  try {
    const params: any = { pageNum: page.value, pageSize: pageSize.value, sourceProjectNum: projectNum.value }
    if (month.value) {
      params.salaryMonth = month.value
    }
    const res: any = await getPayrollList(params)
    if (res.code === 200) {
      payrollList.value = res.data?.records || []
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
        loadPayroll()
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
