import { defineStore } from 'pinia'
import request from '../api/request'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('bw_token') || '',
    user: JSON.parse(localStorage.getItem('bw_user') || 'null'),
  }),
  actions: {
    async login(form) {
      const res = await request.post('/auth/login', form)
      this.token = res.data.token
      this.user = res.data
      localStorage.setItem('bw_token', this.token)
      localStorage.setItem('bw_user', JSON.stringify(res.data))
    },
    async register(form) {
      await request.post('/auth/register', form)
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('bw_token')
      localStorage.removeItem('bw_user')
    },
  },
})
