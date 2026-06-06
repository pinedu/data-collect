import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000
})

api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('wechat_token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

api.interceptors.response.use(
  (response) => {
    // 检查业务状态码，401 表示未登录/Token过期
    const data = response.data
    if (data && data.code === 401) {
      localStorage.removeItem('wechat_token')
      // 避免重复跳转
      if (window.location.pathname !== '/admin/bind') {
        window.location.href = '/admin/bind'
      }
      return Promise.reject(new Error(data.message || '未登录'))
    }
    return data
  },
  (error) => {
    // HTTP 层面的 401（后续如果后端改为返回 HTTP 401 也能兜底）
    if (error.response?.status === 401) {
      localStorage.removeItem('wechat_token')
      if (window.location.pathname !== '/admin/bind') {
        window.location.href = '/admin/bind'
      }
    }
    return Promise.reject(error)
  }
)

export default api

export const getStatistics = () => api.get('/data/statistics/overview')
export const getProjectList = (params: any) => api.get('/data/project/list', { params })
export const getProjectOptions = (keyword?: string) => api.get('/data/project/options', { params: { keyword } })
export const getTeamList = (params: any) => api.get('/data/team/list', { params })
export const getPersonList = (params: any) => api.get('/data/person/list', { params })
export const getAttendanceList = (params: any) => api.get('/data/attendance/list', { params })
export const getPayrollList = (params: any) => api.get('/data/payroll/list', { params })
export const getProjectConfigList = () => api.get('/data/project-config/list')
export const getTokenStatus = () => api.get('/token/status')
export const refreshToken = () => api.post('/token/refresh')

// ==================== 微信Bot管理 API ====================
export const generateBotQrCode = () => api.post('/wechat/bot/qrcode')
export const checkBotScanStatus = (ticket: string) => api.get('/wechat/bot/check-status', { params: { ticket } })
export const botCallback = (data: any) => api.post('/wechat/bot/callback', data)
export const getBotList = (params: any) => api.get('/wechat/bot/list', { params })
export const approveBot = (data: any) => api.post('/wechat/bot/approve', data)

export const getUserList = (params: any) => api.get('/wechat/user/list', { params })
export const getCurrentUser = () => api.get('/wechat/user/me')
export const changeUserRole = (userId: string, role: string) => api.put(`/wechat/user/${userId}/role?role=${role}`)
export const toggleUserStatus = (userId: string, enable: boolean) => api.put(`/wechat/user/${userId}/status?enable=${enable}`)

export const sendMessage = (data: any) => api.post('/wechat/message/send', data)
export const getMessageList = (params: any) => api.get('/wechat/message/list', { params })

// ==================== 同步数据看板 API ====================
export const getSyncDashboard = (sourceProjectNum: string) => api.get(`/data/sync-dashboard/${sourceProjectNum}`)
