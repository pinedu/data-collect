import { createRouter, createWebHistory } from 'vue-router'
import Layout from '@/components/Layout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/LoginView.vue')
    },
    {
      path: '/',
      component: Layout,
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '数据概览', icon: 'DataLine' }
        },
        {
          path: 'projects',
          name: 'Projects',
          component: () => import('@/views/ProjectView.vue'),
          meta: { title: '项目列表', icon: 'FolderOpened' }
        },
        {
          path: 'teams',
          name: 'Teams',
          component: () => import('@/views/TeamView.vue'),
          meta: { title: '班组查询', icon: 'UserFilled' }
        },
        {
          path: 'persons',
          name: 'Persons',
          component: () => import('@/views/PersonView.vue'),
          meta: { title: '人员查询', icon: 'User' }
        },
        {
          path: 'attendance',
          name: 'Attendance',
          component: () => import('@/views/AttendanceView.vue'),
          meta: { title: '考勤查询', icon: 'Calendar' }
        },
        {
          path: 'payroll',
          name: 'Payroll',
          component: () => import('@/views/PayrollView.vue'),
          meta: { title: '工资查询', icon: 'Money' }
        },
        {
          path: 'sync',
          name: 'Sync',
          component: () => import('@/views/SyncView.vue'),
          meta: { title: '同步任务', icon: 'Refresh' }
        },
        {
          path: 'sync-dashboard',
          name: 'SyncDashboard',
          component: () => import('@/views/SyncDashboardView.vue'),
          meta: { title: '同步数据看板', icon: 'DataAnalysis' }
        },
        {
          path: 'token',
          name: 'Token',
          component: () => import('@/views/TokenView.vue'),
          meta: { title: 'Token管理', icon: 'Key' }
        },
        {
          path: 'admin/bots',
          name: 'BotManage',
          component: () => import('@/views/BotManageView.vue'),
          meta: { title: 'Bot管理', icon: 'ChatDotRound' }
        },
        {
          path: 'admin/users',
          name: 'UserManage',
          component: () => import('@/views/UserManageView.vue'),
          meta: { title: '用户管理', icon: 'UserFilled' }
        },
        {
          path: 'admin/messages',
          name: 'MessageManage',
          component: () => import('@/views/MessageManageView.vue'),
          meta: { title: '消息管理', icon: 'Message' }
        }
      ]
    },
    {
      path: '/admin/bind',
      name: 'BindBot',
      component: () => import('@/views/BindBotView.vue'),
      meta: { title: '扫码绑定' }
    }
  ]
})

export default router
