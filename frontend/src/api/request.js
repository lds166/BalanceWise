import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({ baseURL: '/api', timeout: 10000 })

request.interceptors.request.use(config => {
  const token = localStorage.getItem('bw_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  res => {
    const body = res.data
    if (body && body.code === 0) return body
    ElMessage.error((body && body.message) || '请求失败')
    return Promise.reject(new Error((body && body.message) || '请求失败'))
  },
  err => {
    if (err.response && err.response.status === 401) {
      localStorage.removeItem('bw_token')
      localStorage.removeItem('bw_user')
      if (router.currentRoute.value.path !== '/login') {
        router.push('/login')
        ElMessage.warning('登录已过期，请重新登录')
      }
    } else {
      ElMessage.error((err.response && err.response.data && err.response.data.message) || '网络异常')
    }
    return Promise.reject(err)
  }
)

export default request
