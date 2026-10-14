import { defineStore } from 'pinia'

export type ThemeMode = 'light' | 'dark' | 'system'
export type ThemeStyle = 'default' | 'cyberpunk' | 'xianxia'
export type BackgroundStyle = 'smooth' | 'mirror' | 'ripple' | 'paper'
export type AccentTone = 'forest' | 'ocean' | 'plum' | 'amber' | 'graphite'
export type Density = 'comfortable' | 'compact'
export type MotionLevel = 'standard' | 'reduced' | 'off'
export type RadiusStyle = 'modern' | 'crisp'

export const themeOptions: { value: ThemeMode; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' },
]

export const styleOptions: { value: ThemeStyle; label: string; description: string; swatch: string; accent: string; surface: string }[] = [
  { value: 'default', label: '默认', description: '清晰、平滑、适合长期使用', swatch: '#39776d', accent: '#c85f47', surface: '#fffdfa' },
  { value: 'cyberpunk', label: '赛博朋克', description: '霓虹、锐角、夜间终端感', swatch: '#7df9ff', accent: '#ff3cac', surface: '#111827' },
  { value: 'xianxia', label: '仙侠', description: '宣纸、墨色、金红与留白', swatch: '#b84b35', accent: '#c5a15a', surface: '#f5eddd' },
]

export const backgroundOptions: { value: BackgroundStyle; label: string; description: string }[] = [
  { value: 'smooth', label: '平滑', description: '柔和渐变，视觉最安静' },
  { value: 'mirror', label: '镜面', description: '有反射感的横向光带' },
  { value: 'ripple', label: '水波', description: '移动鼠标泛起涟漪，交叠后自然散去' },
  { value: 'paper', label: '纸面', description: '带有细微褶皱的自然肌理' },
]

export const accentOptions: { value: AccentTone; label: string; description: string; swatch: string; accent: string; surface: string }[] = [
  { value: 'forest', label: '暖阳珊瑚', description: '温暖、明快、有行动感', swatch: '#c85f47', accent: '#4f856a', surface: '#fff7e8' },
  { value: 'ocean', label: '青瓷微风', description: '安静、自然、适合专注', swatch: '#39776d', accent: '#d26e50', surface: '#e8eee9' },
  { value: 'plum', label: '莓果晚霞', description: '柔和、充满生命力', swatch: '#a95468', accent: '#c9813f', surface: '#f3e4e3' },
  { value: 'amber', label: '纸页暖光', description: '朴素、松弛、像一本手账', swatch: '#9b663d', accent: '#64856a', surface: '#f1e6d3' },
  { value: 'graphite', label: '木炭静夜', description: '克制、稳重、保留温度', swatch: '#5d5550', accent: '#b9624c', surface: '#eae6e1' },
]

export const densityOptions: { value: Density; label: string }[] = [
  { value: 'comfortable', label: '舒适' },
  { value: 'compact', label: '紧凑' },
]

export const motionOptions: { value: MotionLevel; label: string }[] = [
  { value: 'standard', label: '标准' },
  { value: 'reduced', label: '减弱' },
  { value: 'off', label: '关闭' },
]

export const radiusOptions: { value: RadiusStyle; label: string }[] = [
  { value: 'modern', label: '现代' },
  { value: 'crisp', label: '克制' },
]

const appearanceKey = 'better-self:appearance'

type AppearanceState = {
  theme: ThemeMode
  style: ThemeStyle
  background: BackgroundStyle
  accent: AccentTone
  density: Density
  motion: MotionLevel
  radius: RadiusStyle
  hydrated: boolean
}

function storageAvailable() {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined'
}

export const useAppearanceStore = defineStore('appearance', {
  state: (): AppearanceState => ({
    theme: 'light',
    style: 'default',
    background: 'smooth',
    accent: 'forest',
    density: 'comfortable',
    motion: 'standard',
    radius: 'modern',
    hydrated: false,
  }),
  actions: {
    hydrate() {
      if (this.hydrated) return
      if (storageAvailable()) {
        const saved = window.localStorage.getItem(appearanceKey)
        if (saved) {
          try {
            const parsed = JSON.parse(saved) as Partial<AppearanceState>
            if (parsed.theme && ['light', 'dark', 'system'].includes(parsed.theme)) this.theme = parsed.theme
            if (parsed.style && ['default', 'cyberpunk', 'xianxia'].includes(parsed.style)) this.style = parsed.style
            if (parsed.background && ['smooth', 'mirror', 'ripple', 'paper'].includes(parsed.background)) this.background = parsed.background
            if ((parsed.background as string) === 'flat') this.background = 'paper'
            if (parsed.accent && accentOptions.some(option => option.value === parsed.accent)) this.accent = parsed.accent
            if (parsed.density && ['comfortable', 'compact'].includes(parsed.density)) this.density = parsed.density
            if (parsed.motion && ['standard', 'reduced', 'off'].includes(parsed.motion)) this.motion = parsed.motion
            if (parsed.radius && ['modern', 'crisp'].includes(parsed.radius)) this.radius = parsed.radius
          } catch {
            window.localStorage.removeItem(appearanceKey)
          }
        }
      }
      this.hydrated = true
      this.apply()
      this.$subscribe(() => {
        if (storageAvailable()) {
          window.localStorage.setItem(appearanceKey, JSON.stringify({
            theme: this.theme,
            style: this.style,
            background: this.background,
            accent: this.accent,
            density: this.density,
            motion: this.motion,
            radius: this.radius,
          }))
        }
        this.apply()
      })
    },
    setTheme(theme: ThemeMode) {
      this.theme = theme
    },
    setStyle(style: ThemeStyle) {
      this.style = style
    },
    setBackground(background: BackgroundStyle) {
      this.background = background
    },
    setAccent(accent: AccentTone) {
      this.accent = accent
    },
    setDensity(density: Density) {
      this.density = density
    },
    setMotion(motion: MotionLevel) {
      this.motion = motion
    },
    setRadius(radius: RadiusStyle) {
      this.radius = radius
    },
    reset() {
      this.theme = 'light'
      this.style = 'default'
      this.background = 'smooth'
      this.accent = 'forest'
      this.density = 'comfortable'
      this.motion = 'standard'
      this.radius = 'modern'
    },
    apply() {
      if (typeof document === 'undefined') return
      const root = document.documentElement
      root.dataset.theme = this.theme
      root.dataset.style = this.style
      root.dataset.background = this.background
      root.dataset.accent = this.accent
      root.dataset.density = this.density
      root.dataset.motion = this.motion
      root.dataset.radius = this.radius
    },
  },
})
