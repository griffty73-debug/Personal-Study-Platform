<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Download, LogOut, Monitor, Moon, Paintbrush, PanelsTopLeft, RotateCcw, Sparkles, Sun, Trash2, Zap } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { api } from '../../shared/api/client'
import { randomUUID } from '../../shared/uuid'
import { useAuthStore } from '../auth/auth.store'
import {
  accentOptions,
  backgroundOptions,
  densityOptions,
  motionOptions,
  radiusOptions,
  styleOptions,
  themeOptions,
  useAppearanceStore,
  type AccentTone,
  type BackgroundStyle,
  type Density,
  type MotionLevel,
  type RadiusStyle,
  type ThemeMode,
  type ThemeStyle,
} from '../../shared/ui/appearance.store'

const prefs = ref<any>(null)
const notifications = ref<any[]>([])
const exportJob = ref<any>(null)
const deletion = ref<any>(null)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()
const appearance = useAppearanceStore()
const currentAccent = computed(() => accentOptions.find(option => option.value === appearance.accent) ?? accentOptions[0])
const currentStyle = computed(() => styleOptions.find(option => option.value === appearance.style) ?? styleOptions[0])
const currentBackground = computed(() => backgroundOptions.find(option => option.value === appearance.background) ?? backgroundOptions[0])

appearance.hydrate()

const themeIcons: Record<ThemeMode, unknown> = { light: Sun, dark: Moon, system: Monitor }

onMounted(async () => {
  try {
    const [loadedPrefs, loadedNotifications, loadedDeletion] = await Promise.all([
      api.get<any>('/me/preferences'),
      api.get<any[]>('/me/notifications'),
      api.get<any>('/privacy/deletion').catch(() => null),
    ])
    prefs.value = loadedPrefs
    notifications.value = loadedNotifications
    deletion.value = loadedDeletion
  } catch {
    error.value = '设置暂时无法加载'
  }
})

async function retention(days: number) {
  await api.put('/privacy/ai/retention', { days })
  prefs.value.aiRetentionDays = days
}

async function toggleNotification(n: any) {
  const previous = n.enabled
  n.enabled = !previous
  try {
    Object.assign(n, await api.put<any>(`/me/notifications/${n.channel}`, { enabled: n.enabled, maxPerDay: n.maxPerDay }))
  } catch {
    n.enabled = previous
    error.value = '通知设置未保存，请重试'
  }
}

async function createExport() {
  exportJob.value = await api.post<any>('/privacy/exports', undefined, { 'Idempotency-Key': randomUUID() })
}

async function requestDeletion() {
  deletion.value = await api.post<any>('/privacy/deletion')
}

async function cancelDeletion() {
  deletion.value = await api.post<any>('/privacy/deletion/cancel')
}

async function logout() {
  await auth.logout()
  router.push('/auth')
}

function replayWelcome() {
  if (auth.user?.publicId) window.localStorage.removeItem(`better-self:welcome:${auth.user.publicId}`)
  window.dispatchEvent(new CustomEvent('better-self:show-welcome'))
}
</script>

<template>
  <section class="page settings-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">照顾你的使用感受</p>
        <h1>设置</h1>
      </div>
      <button class="secondary" @click="logout">
        <LogOut :size="17" />
        退出
      </button>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <section class="band appearance-section">
      <div class="section-title">
        <Paintbrush :size="19" />
        <div>
          <h2>外观风格</h2>
          <p class="muted">选择一种让你愿意每天回来看看自己的氛围。</p>
        </div>
      </div>

      <div class="appearance-grid">
        <fieldset class="option-group">
          <legend>主题</legend>
          <div class="segmented">
            <button
              v-for="option in themeOptions"
              :key="option.value"
              type="button"
              :aria-pressed="appearance.theme === option.value"
              @click="appearance.setTheme(option.value as ThemeMode)"
            >
              <component :is="themeIcons[option.value]" :size="16" />
              {{ option.label }}
            </button>
          </div>
        </fieldset>

        <fieldset class="option-group visual-group">
          <legend>主题风格</legend>
          <div class="style-options">
            <button
              v-for="option in styleOptions"
              :key="option.value"
              type="button"
              :class="['style-option', `style-option--${option.value}`]"
              :aria-pressed="appearance.style === option.value"
              @click="appearance.setStyle(option.value as ThemeStyle)"
            >
              <span class="style-preview" aria-hidden="true">
                <span class="style-preview-top"><b>{{ option.value === 'cyberpunk' ? 'SYS / 09' : option.value === 'xianxia' ? '山海志' : 'DAY / FOCUS' }}</b><i /></span>
                <span class="style-preview-body"><i /><i /><i /></span>
              </span>
              <span class="style-copy"><strong>{{ option.label }}</strong><small>{{ option.description }}</small></span>
            </button>
          </div>
        </fieldset>

        <fieldset class="option-group background-group">
          <legend>背景质感</legend>
          <div class="background-options">
            <button
              v-for="option in backgroundOptions"
              :key="option.value"
              type="button"
              :class="['background-option', `background-option--${option.value}`]"
              :aria-pressed="appearance.background === option.value"
              @click="appearance.setBackground(option.value as BackgroundStyle)"
            >
              <span class="background-sample" aria-hidden="true"><i /><i /><i /></span>
              <span><strong>{{ option.label }}</strong><small>{{ option.description }}</small></span>
            </button>
          </div>
        </fieldset>

        <fieldset class="option-group accent-group">
          <legend>配色倾向</legend>
          <div class="swatches">
            <button
              v-for="option in accentOptions"
              :key="option.value"
              type="button"
              :aria-label="option.label"
              :aria-pressed="appearance.accent === option.value"
              :style="{ '--preview-primary': option.swatch, '--preview-accent': option.accent, '--preview-surface': option.surface }"
              @click="appearance.setAccent(option.value as AccentTone)"
            >
              <span class="theme-preview" aria-hidden="true">
                <span class="preview-sidebar" />
                <span class="preview-content"><i /><i /><i /></span>
              </span>
              <span class="swatch-copy"><strong>{{ option.label }}</strong><small>{{ option.description }}</small></span>
            </button>
          </div>
        </fieldset>

        <fieldset class="option-group">
          <legend>界面密度</legend>
          <div class="segmented">
            <button
              v-for="option in densityOptions"
              :key="option.value"
              type="button"
              :aria-pressed="appearance.density === option.value"
              @click="appearance.setDensity(option.value as Density)"
            >
              <PanelsTopLeft :size="16" />
              {{ option.label }}
            </button>
          </div>
        </fieldset>

        <fieldset class="option-group">
          <legend>动画强度</legend>
          <div class="segmented">
            <button
              v-for="option in motionOptions"
              :key="option.value"
              type="button"
              :aria-pressed="appearance.motion === option.value"
              @click="appearance.setMotion(option.value as MotionLevel)"
            >
              <Zap :size="16" />
              {{ option.label }}
            </button>
          </div>
        </fieldset>

        <fieldset class="option-group">
          <legend>圆角风格</legend>
          <div class="segmented">
            <button
              v-for="option in radiusOptions"
              :key="option.value"
              type="button"
              :aria-pressed="appearance.radius === option.value"
              @click="appearance.setRadius(option.value as RadiusStyle)"
            >
              {{ option.label }}
            </button>
          </div>
        </fieldset>

        <div class="appearance-actions">
          <div class="current-style">
            <span class="current-style-mark" :class="`current-style-mark--${appearance.style}`" aria-hidden="true" />
            <div><small>{{ currentStyle.label }} · {{ currentBackground.label }}</small><strong>{{ currentAccent.label }}</strong></div>
          </div>
          <button type="button" class="secondary" @click="appearance.reset">
            <RotateCcw :size="17" />
            恢复默认
          </button>
          <button type="button" class="secondary" @click="replayWelcome">
            <Sparkles :size="17" />
            查看欢迎介绍
          </button>
        </div>
      </div>
    </section>

    <section v-if="prefs" class="band">
      <h2>AI 数据保留</h2>
      <p class="muted">对话到期后自动删除；安全事件按独立政策最小化保留。</p>
      <div class="actions">
        <button v-for="d in [7, 30, 90]" :key="d" class="secondary" :aria-pressed="prefs.aiRetentionDays === d" @click="retention(d)">
          {{ d }} 天
        </button>
      </div>
    </section>

    <section class="band">
      <h2>通知</h2>
      <div v-for="n in notifications" :key="n.channel" class="setting-row">
        <div>
          <strong>{{ n.channel }}</strong>
          <small>每日最多 {{ n.maxPerDay }} 条</small>
        </div>
        <input :checked="n.enabled" type="checkbox" :aria-label="`启用 ${n.channel}`" @change="toggleNotification(n)">
      </div>
    </section>

    <section class="band">
      <h2>数据导出</h2>
      <p class="muted">导出文件保留 24 小时，下载链接有效 15 分钟。</p>
      <div class="actions">
        <button class="secondary" @click="createExport">
          <Download :size="17" />
          创建导出
        </button>
        <a v-if="exportJob?.status === 'READY'" class="primary button" :href="`/api/v1/privacy/exports/${exportJob.publicId}/content`">下载 ZIP</a>
      </div>
    </section>

    <section class="band danger-zone">
      <h2>注销账户</h2>
      <template v-if="deletion?.status === 'COOLING_OFF'">
        <p>账户处于 7 天冷静期。计划处理时间：{{ new Date(deletion.processAfter).toLocaleString() }}</p>
        <button class="secondary" @click="cancelDeletion">撤销注销</button>
      </template>
      <template v-else>
        <p class="muted">请求后会立即暂停通知与业务访问，7 天内可以撤销。</p>
        <button class="danger" @click="requestDeletion">
          <Trash2 :size="17" />
          请求注销
        </button>
      </template>
    </section>
  </section>
</template>

<style scoped>
h2 {
  margin: 0 0 10px;
  font-size: 18px;
}

.section-title {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 18px;
}

.section-title > svg {
  margin-top: 3px;
  color: var(--primary);
}

.appearance-section {
  padding-top: 24px;
  padding-bottom: 26px;
}

.appearance-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 16px;
}

.option-group {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}

.visual-group,
.appearance-actions {
  grid-column: 1 / -1;
}

.style-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.style-option {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(150px, 42%) minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  min-height: 122px;
  padding: 10px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--ink);
  text-align: left;
}

.style-option:hover { border-color: var(--primary); }
.style-option[aria-pressed='true'] { border-color: var(--primary); box-shadow: 0 0 0 2px color-mix(in srgb, var(--primary) 18%, transparent), var(--shadow-soft); }

.style-preview {
  min-width: 0;
  height: 96px;
  display: grid;
  grid-template-rows: 28px 1fr;
  overflow: hidden;
  border: 1px solid var(--preview-border, var(--border));
  background: var(--preview-surface, var(--surface-muted));
}

.style-preview-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 8px;
  background: var(--preview-primary, var(--primary));
  color: var(--preview-ink, white);
  font-size: 9px;
  letter-spacing: .08em;
}

.style-preview-top i { width: 20px; height: 4px; background: var(--preview-accent, var(--accent)); }
.style-preview-body { display: grid; align-content: center; gap: 7px; padding: 12px; }
.style-preview-body i { height: 7px; width: 82%; background: color-mix(in srgb, var(--preview-primary, var(--primary)) 28%, var(--preview-surface, white)); }
.style-preview-body i:first-child { width: 58%; height: 12px; background: var(--preview-primary, var(--primary)); }
.style-preview-body i:last-child { width: 44%; background: var(--preview-accent, var(--accent)); }

.style-option--cyberpunk .style-preview { --preview-primary: #7df9ff; --preview-accent: #ff3cac; --preview-surface: #111827; --preview-border: #29435c; --preview-ink: #071018; box-shadow: inset 0 0 20px rgb(125 249 255 / 14%); }
.style-option--xianxia .style-preview { --preview-primary: #a44731; --preview-accent: #c5a15a; --preview-surface: #f5eddd; --preview-border: #d5c4a4; --preview-ink: #fffaf0; border-radius: 16px 4px 16px 4px; }
.style-option--default .style-preview { --preview-primary: #39776d; --preview-accent: #c85f47; --preview-surface: #fffdfa; --preview-border: #d7dfd8; --preview-ink: #fffdfa; }
.style-option--cyberpunk .style-preview-top { font-family: 'Orbitron', sans-serif; }
.style-option--xianxia .style-preview-top { font-family: 'Ma Shan Zheng', 'Songti SC', serif; font-size: 12px; }

.style-copy { min-width: 0; display: grid; gap: 5px; }
.style-copy strong { font-size: 15px; }
.style-copy small { color: var(--muted); font-size: 12px; line-height: 1.5; }

.background-group { grid-column: 1 / -1; }
.background-options { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.background-option { min-width: 0; display: grid; gap: 8px; padding: 9px; border: 1px solid var(--border); background: var(--surface); color: var(--ink); text-align: left; }
.background-option:hover { border-color: var(--primary); }
.background-option[aria-pressed='true'] { border-color: var(--primary); background: color-mix(in srgb, var(--primary-soft) 42%, var(--surface)); box-shadow: inset 0 -3px var(--primary), var(--shadow-soft); }
.background-option > span:last-child { display: grid; gap: 3px; }
.background-option strong { font-size: 13px; }
.background-option small { color: var(--muted); font-size: 11px; line-height: 1.35; }
.background-sample { position: relative; display: block; height: 54px; overflow: hidden; border: 1px solid var(--border); background: var(--surface-muted); }
.background-sample i { position: absolute; display: block; }
.background-option--smooth .background-sample { background: linear-gradient(135deg, color-mix(in srgb, var(--primary) 18%, var(--surface)), var(--surface)); }
.background-option--smooth .background-sample i { inset: 12px 18px auto; height: 9px; border-radius: 999px; background: color-mix(in srgb, var(--primary) 38%, var(--surface)); filter: blur(5px); }
.background-option--mirror .background-sample { background: linear-gradient(180deg, var(--surface), color-mix(in srgb, var(--primary) 22%, var(--surface)) 48%, var(--surface)); }
.background-option--mirror .background-sample i { left: 0; right: 0; height: 1px; background: color-mix(in srgb, var(--primary) 42%, transparent); }
.background-option--mirror .background-sample i:nth-child(1) { top: 17px; }.background-option--mirror .background-sample i:nth-child(2) { top: 27px; opacity: .55; }.background-option--mirror .background-sample i:nth-child(3) { top: 37px; opacity: .28; }
.background-option--ripple .background-sample { background: repeating-radial-gradient(ellipse at 50% 100%, transparent 0 7px, color-mix(in srgb, var(--primary) 24%, transparent) 8px 9px, transparent 10px 17px), var(--surface-muted); }
.background-option--ripple .background-sample i { width: 12px; height: 12px; left: 50%; bottom: 7px; border: 1px solid var(--primary); border-radius: 50%; transform: translateX(-50%); opacity: .65; }
.background-option--ripple .background-sample i:nth-child(2) { width: 28px; height: 10px; bottom: 3px; opacity: .4; }.background-option--ripple .background-sample i:nth-child(3) { width: 45px; height: 8px; bottom: 0; opacity: .22; }
.background-option--paper .background-sample { background: repeating-linear-gradient(164deg, transparent 0 8px, color-mix(in srgb, var(--primary) 15%, transparent) 9px 10px, transparent 11px 18px), var(--surface); }
.background-option--paper .background-sample i { inset: 0 auto 0 25%; width: 1px; background: color-mix(in srgb, var(--border) 72%, transparent); transform: rotate(7deg); }.background-option--paper .background-sample i:nth-child(2) { left: 50%; transform: rotate(-4deg); }.background-option--paper .background-sample i:nth-child(3) { left: 75%; transform: rotate(5deg); }

.option-group legend {
  margin-bottom: 8px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 700;
}

.segmented {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(0, 1fr);
  gap: 3px;
  padding: 3px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-muted);
}

.segmented button {
  min-width: 0;
  border: 0;
  border-radius: calc(var(--radius) - 2px);
  background: transparent;
  color: var(--muted);
  padding: 0 10px;
}

.segmented button[aria-pressed='true'] {
  background: var(--surface);
  color: var(--primary-strong);
  font-weight: 700;
  box-shadow: var(--shadow-soft);
}

.swatches {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(154px, 1fr));
  gap: 10px;
}

.swatches button {
  min-width: 0;
  min-height: 142px;
  display: grid;
  grid-template-rows: 82px auto;
  gap: 10px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--ink);
  padding: 9px;
  text-align: left;
}

.theme-preview {
  width: 100%;
  height: 82px;
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--preview-primary) 22%, var(--border));
  border-radius: 6px;
  background: var(--preview-surface);
}

.preview-sidebar { background: var(--preview-primary); }
.preview-content { display: grid; align-content: center; gap: 7px; padding: 10px; }
.preview-content i { display: block; height: 8px; border-radius: 3px; background: color-mix(in srgb, var(--preview-primary) 24%, white); }
.preview-content i:first-child { width: 64%; height: 11px; background: var(--preview-primary); }
.preview-content i:last-child { width: 46%; background: var(--preview-accent); }
.swatch-copy { min-width: 0; display: grid; gap: 3px; }
.swatch-copy strong { overflow-wrap: anywhere; font-size: 13px; }
.swatch-copy small {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.35;
  white-space: normal;
}

.swatches button[aria-pressed='true'] {
  border-color: var(--primary);
  background: color-mix(in srgb, var(--primary-soft) 48%, var(--surface));
  box-shadow: inset 0 -3px 0 var(--primary), var(--shadow-soft);
}

.swatches button[aria-pressed='true'] .swatch-copy strong {
  color: var(--primary-strong);
  font-weight: 800;
}

.appearance-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 9px;
}

.current-style {
  flex: 1 1 160px;
  min-height: var(--control);
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--ink);
}
.current-style > span { width: 30px; height: 30px; flex: 0 0 auto; border: 3px solid var(--surface); border-radius: 50%; box-shadow: 0 0 0 1px var(--border); }
.current-style-mark--cyberpunk { background: linear-gradient(135deg, #7df9ff 0 50%, #ff3cac 50% 100%); }
.current-style-mark--xianxia { background: linear-gradient(135deg, #a44731 0 50%, #c5a15a 50% 67%, #2e665d 67% 100%); }
.current-style div { display: grid; gap: 1px; }
.current-style small { color: var(--muted); font-size: 10px; }
.current-style strong { font-size: 13px; }

.setting-row {
  min-height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid var(--border);
}

.setting-row small {
  display: block;
  color: var(--muted);
  margin-top: 4px;
}

.setting-row input[type='checkbox'] { width: 18px; height: 18px; }

.button {
  display: inline-flex;
  min-height: var(--control);
  align-items: center;
  padding: 0 14px;
  border-radius: var(--radius);
  text-decoration: none;
}

.danger-zone {
  color: var(--danger);
}

@media (prefers-reduced-motion: no-preference) {
  .appearance-section {
    animation: settings-rise var(--motion-medium) ease-out both;
  }
}

@keyframes settings-rise {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 840px) {
  .appearance-grid {
    grid-template-columns: 1fr;
  }

  .style-options { grid-template-columns: 1fr; }
  .background-options { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 520px) {
  .appearance-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: stretch;
  }

  .appearance-actions .secondary {
    width: 100%;
    min-width: 0;
  }

  .swatches {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .swatches button { min-height: 132px; grid-template-rows: 72px auto; }
  .theme-preview { height: 72px; }
  .style-option { grid-template-columns: 1fr; }
  .style-preview { height: 84px; }
  .current-style { grid-column: 1 / -1; }
}
</style>
