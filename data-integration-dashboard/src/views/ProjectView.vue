<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>项目列表</span>
          <el-input v-model="searchKey" placeholder="搜索项目名称" style="width: 300px" clearable>
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </div>
      </template>
      <el-table :data="filteredProjects" stripe v-loading="loading">
        <el-table-column prop="projectName" label="项目名称" min-width="180" />
        <el-table-column prop="projectStatus" label="项目状态" width="100">
          <template #default="scope">
            <el-tag :type="statusTagType(scope.row.projectStatus)">
              {{ scope.row.projectStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="commencementDate" label="开工日期" width="120" />
        <el-table-column prop="syncTime" label="最新同步时间" width="180" />
        <el-table-column label="地理位置" width="100" align="center">
          <template #default="scope">
            <el-button
              link
              type="primary"
              :disabled="!scope.row.lon || !scope.row.lat"
              @click="openMap(scope.row)"
            >
              查看
            </el-button>
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
        @current-change="loadProjects"
        @size-change="loadProjects"
      />
    </el-card>

    <!-- 地理位置弹窗 -->
    <el-dialog
      v-model="mapVisible"
      :title="`${mapTitle} - 地理位置`"
      width="720px"
      destroy-on-close
      top="8vh"
    >
      <AMapView
        v-if="mapVisible"
        :lon="currentLon"
        :lat="currentLat"
        :title="mapTitle"
      />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getProjectList } from '@/api'
import AMapView from '@/components/AMapView.vue'

const loading = ref(false)
const projects = ref<any[]>([])
const searchKey = ref('')
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

// 地图弹窗状态
const mapVisible = ref(false)
const currentLon = ref(0)
const currentLat = ref(0)
const mapTitle = ref('')

const statusTagType = (status: string) => {
  const map: Record<string, string> = {
    '在建': 'success',
    '竣工': 'warning',
    '停工': 'info',
    '完工': 'danger'
  }
  return map[status] ?? 'info'
}

const filteredProjects = computed(() => {
  if (!searchKey.value) return projects.value
  return projects.value.filter((p: any) =>
    p.projectName?.includes(searchKey.value)
  )
})

const openMap = (row: any) => {
  currentLon.value = Number(row.lon)
  currentLat.value = Number(row.lat)
  mapTitle.value = row.projectName ?? '项目位置'
  mapVisible.value = true
}

const loadProjects = async () => {
  loading.value = true
  try {
    const res: any = await getProjectList({ pageNum: page.value, pageSize: pageSize.value })
    if (res.code === 200) {
      projects.value = res.data?.records || []
      total.value = Number(res.data?.total || 0)
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadProjects)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
