<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>用户管理</span>
        </div>
      </template>
      <el-table :data="users" stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="nickname" label="昵称" />
        <el-table-column label="角色" width="140">
          <template #default="scope">
            <el-tag v-if="scope.row.role === 'SUPER_ADMIN'" type="danger">超级管理员</el-tag>
            <el-tag v-else-if="scope.row.role === 'ADMIN'" type="warning">管理员</el-tag>
            <el-tag v-else>普通用户</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag v-if="scope.row.status === 'ACTIVE'" type="success">启用</el-tag>
            <el-tag v-else type="info">禁用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="scope">
            {{ scope.row.createdAt || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="scope">
            <el-select
              :model-value="scope.row.role"
              size="small"
              style="width: 120px; margin-right: 8px"
              :disabled="scope.row.role === 'SUPER_ADMIN'"
              @change="(val: string) => handleChangeRole(scope.row.id, val)"
            >
              <el-option label="超级管理员" value="SUPER_ADMIN" />
              <el-option label="管理员" value="ADMIN" />
              <el-option label="普通用户" value="USER" />
            </el-select>
            <el-button
              v-if="scope.row.role !== 'SUPER_ADMIN'"
              link
              :type="scope.row.status === 'ACTIVE' ? 'danger' : 'success'"
              @click="handleToggleStatus(scope.row.id, scope.row.status !== 'ACTIVE')"
            >
              {{ scope.row.status === 'ACTIVE' ? '禁用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px"
        @current-change="loadUsers"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getUserList, changeUserRole, toggleUserStatus } from '@/api'

const loading = ref(false)
const users = ref([])
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)

const loadUsers = async () => {
  loading.value = true
  try {
    const res: any = await getUserList({ pageNum: pageNum.value, pageSize: pageSize.value })
    if (res.code === 200 && res.data) {
      users.value = res.data.records || []
      total.value = Number(res.data.total || 0)
    }
  } catch (e) {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

const handleChangeRole = async (userId: string, role: string) => {
  try {
    const res: any = await changeUserRole(userId, role)
    if (res.code === 200) {
      ElMessage.success('角色修改成功')
      loadUsers()
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const handleToggleStatus = async (userId: string, enable: boolean) => {
  try {
    const res: any = await toggleUserStatus(userId, enable)
    if (res.code === 200) {
      ElMessage.success(enable ? '已启用' : '已禁用')
      loadUsers()
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

onMounted(loadUsers)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
