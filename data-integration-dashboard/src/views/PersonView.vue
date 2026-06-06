<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>人员查询</span>
          <div class="filter-bar">
            <el-select
              v-model="sourceProjectNum"
              filterable
              clearable
              placeholder="选择项目"
              style="width: 240px"
            >
              <el-option
                v-for="p in projectOptions"
                :key="p.sourceProjectNum"
                :label="p.projectName"
                :value="p.sourceProjectNum"
              />
            </el-select>
            <el-input
              v-model="personName"
              placeholder="姓名"
              clearable
              style="width: 120px; margin-left: 10px"
            />
            <el-input
              v-model="idCardNo"
              placeholder="身份证号"
              clearable
              style="width: 180px; margin-left: 10px"
            />
            <el-button type="primary" style="margin-left: 10px" @click="loadPersons">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="personList" stripe v-loading="loading">
        <el-table-column prop="projectName" label="项目名称" min-width="160" />
        <el-table-column prop="personName" label="姓名" width="100" />
        <el-table-column prop="idCardNo" label="身份证号" width="180" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="teamName" label="所属班组" min-width="140" />
        <el-table-column prop="workType" label="工种" width="100" />
        <el-table-column prop="jobStatus" label="在职状态" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.jobStatus === '1' ? 'success' : 'info'">
              {{ scope.row.jobStatus === '1' ? '在职' : '离职' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="registerTime" label="登记时间" width="160" />
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        :hide-on-single-page="false"
        layout="total, sizes, prev, pager, next, jumper"
        class="pagination"
        @current-change="loadPersons"
        @size-change="loadPersons"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getPersonList, getProjectOptions } from '@/api'

const loading = ref(false)
const personList = ref<any[]>([])
const projectOptions = ref<any[]>([])
const sourceProjectNum = ref('')
const personName = ref('')
const idCardNo = ref('')
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

const loadPersons = async () => {
  if (!sourceProjectNum.value) {
    ElMessage.warning('请先选择项目')
    return
  }
  loading.value = true
  try {
    const params: any = { pageNum: page.value, pageSize: pageSize.value, sourceProjectNum: sourceProjectNum.value }
    if (personName.value) {
      params.personName = personName.value
    }
    if (idCardNo.value) {
      params.idCardNo = idCardNo.value
    }
    const res: any = await getPersonList(params)
    if (res.code === 200) {
      personList.value = res.data?.records || []
      total.value = Number(res.data?.total || 0)
    }
  } finally {
    loading.value = false
  }
}

const loadProjectOptions = async () => {
  try {
    const res: any = await getProjectOptions()
    if (res.code === 200) {
      projectOptions.value = res.data || []
      // 默认选中第一个项目并查询
      if (projectOptions.value.length > 0) {
        sourceProjectNum.value = projectOptions.value[0].sourceProjectNum
        loadPersons()
      }
    }
  } catch (e) {
    console.error('加载项目列表失败', e)
  }
}

onMounted(() => {
  loadProjectOptions()
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
