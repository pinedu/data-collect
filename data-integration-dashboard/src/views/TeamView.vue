<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>班组查询</span>
          <div class="filter-bar">
            <el-select
              v-model="sourceProjectNum"
              filterable
              clearable
              placeholder="选择项目"
              style="width: 240px"
              @visible-change="onProjectSelectVisible"
            >
              <el-option
                v-for="p in projectOptions"
                :key="p.sourceProjectNum"
                :label="p.projectName"
                :value="p.sourceProjectNum"
              />
            </el-select>
            <el-input
              v-model="teamName"
              placeholder="班组名称"
              clearable
              style="width: 160px; margin-left: 10px"
            />
            <el-button type="primary" style="margin-left: 10px" @click="loadTeams">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="teamList" stripe v-loading="loading">
        <el-table-column prop="projectName" label="项目名称" min-width="160" />
        <el-table-column prop="teamName" label="班组名称" min-width="140" />
        <el-table-column prop="contractorId" label="承包单位" width="150" />
        <el-table-column prop="leaderName" label="班组长" width="100" />
        <el-table-column prop="workType" label="工种" width="100" />
        <el-table-column prop="approachDate" label="进场日期" width="120" />
        <el-table-column prop="departureDate" label="离场日期" width="120" />
        <el-table-column prop="teamStatus" label="状态" width="80">
          <template #default="scope">
            <el-tag :type="scope.row.teamStatus === '1' ? 'success' : 'info'">
              {{ scope.row.teamStatus === '1' ? '在场' : '离场' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        :hide-on-single-page="false"
        layout="total, sizes, prev, pager, next, jumper"
        class="pagination"
        @current-change="loadTeams"
        @size-change="loadTeams"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getTeamList, getProjectOptions } from '@/api'

const loading = ref(false)
const teamList = ref<any[]>([])
const projectOptions = ref<any[]>([])
const sourceProjectNum = ref('')
const teamName = ref('')
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

const loadTeams = async () => {
  loading.value = true
  try {
    const params: any = { pageNum: page.value, pageSize: pageSize.value }
    if (sourceProjectNum.value) {
      params.sourceProjectNum = sourceProjectNum.value
    }
    if (teamName.value) {
      params.teamName = teamName.value
    }
    const res: any = await getTeamList(params)
    if (res.code === 200) {
      teamList.value = res.data?.records || []
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
    }
  } catch (e) {
    console.error('加载项目列表失败', e)
  }
}

const onProjectSelectVisible = (visible: boolean) => {
  if (visible && projectOptions.value.length === 0) {
    loadProjectOptions()
  }
}

onMounted(() => {
  loadProjectOptions()
  loadTeams()
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
