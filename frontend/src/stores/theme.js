import { defineStore } from 'pinia'

const STORAGE_KEY = 'bw-theme'
export const DEFAULT_THEME = 'journal'

export const THEMES = [
  { id: 'journal', name: '温暖手账', dot: '#f28c38', desc: '奶油暖橙 · 默认' },
  { id: 'mint', name: '清新薄荷', dot: '#2fb98c', desc: '浅绿便签 · 轻快' },
  { id: 'morandi', name: '雾粉莫兰迪', dot: '#c08a97', desc: '低饱和 · 柔和' },
  { id: 'night', name: '暗夜质感', dot: '#f2a24d', desc: '深色磨砂 · 酷' },
]

function applyToDom(id) {
  document.documentElement.dataset.theme = id
}

export const useThemeStore = defineStore('theme', {
  state: () => ({
    current: localStorage.getItem(STORAGE_KEY) || DEFAULT_THEME,
  }),
  getters: {
    themes: () => THEMES,
  },
  actions: {
    init() {
      if (!THEMES.some(t => t.id === this.current)) this.current = DEFAULT_THEME
      applyToDom(this.current)
    },
    setTheme(id) {
      if (!THEMES.some(t => t.id === id) || id === this.current) return
      this.current = id
      localStorage.setItem(STORAGE_KEY, id)
      applyToDom(id)
    },
  },
})
