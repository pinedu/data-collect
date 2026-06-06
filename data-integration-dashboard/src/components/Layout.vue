<template>
  <el-container class="layout-container">
    <el-aside width="200px" class="aside">
      <div class="logo">
        <el-icon size="24"><DataLine /></el-icon>
        <span>数据看板</span>
      </div>
      <el-menu
        :default-active="$route.path"
        router
        class="menu"
        background-color="#001529"
        text-color="#fff"
        active-text-color="#409EFF"
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon>
            <component :is="item.icon" />
          </el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="header-title">{{ pageTitle }}</div>
        <div class="header-right">
          <el-tag type="success">在线</el-tag>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const menuItems = [
  { path: '/dashboard', title: '数据概览', icon: 'DataLine' },
  { path: '/projects', title: '项目列表', icon: 'FolderOpened' },
  { path: '/teams', title: '班组查询', icon: 'UserFilled' },
  { path: '/persons', title: '人员查询', icon: 'User' },
  { path: '/attendance', title: '考勤查询', icon: 'Calendar' },
  { path: '/payroll', title: '工资查询', icon: 'Money' },
  { path: '/sync', title: '同步任务', icon: 'Refresh' },
  { path: '/sync-dashboard', title: '同步数据看板', icon: 'DataAnalysis' },
  { path: '/token', title: 'Token管理', icon: 'Key' },
  { path: '/admin/bots', title: 'Bot管理', icon: 'ChatDotRound' },
  { path: '/admin/users', title: '用户管理', icon: 'UserFilled' },
  { path: '/admin/messages', title: '消息管理', icon: 'Message' }
]

const pageTitle = computed(() => {
  const item = menuItems.find(i => i.path === route.path)
  return item ? item.title : '数据看板'
})
</script>

<style scoped>
.layout-container {
  height: 100vh;
}

.aside {
  background-color: #001529;
}

.logo {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  font-weight: bold;
  gap: 8px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
}

.menu {
  border-right: none;
}

.header {
  background-color: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 1px 4px rgba(0,0,0,0.1);
}

.header-title {
  font-size: 18px;
  font-weight: 500;
}

.main {
  background-color: #f0f2f5;
  padding: 20px;
  overflow-y: auto;
}
</style>
