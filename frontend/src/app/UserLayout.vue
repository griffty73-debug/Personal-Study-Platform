<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { Activity, Target, CalendarCheck2, ChartNoAxesColumnIncreasing, Heart, Menu, Sparkles, Settings, PawPrint, UserRound, Users, X } from 'lucide-vue-next'
import { useAuthStore } from '../modules/auth/auth.store'
import DesktopPet from '../modules/partners/DesktopPet.vue'
import WelcomeGuide from '../shared/ui/WelcomeGuide.vue'
import GlobalUnreadBar from '../shared/ui/GlobalUnreadBar.vue'
import OperationGuideBar from '../shared/ui/OperationGuideBar.vue'

const nav = [
  { to: '/today', label: '今日', icon: CalendarCheck2, mobile: 'primary' },
  { to: '/goals', label: '目标', icon: Target, mobile: 'primary' },
  { to: '/partners', label: '伙伴', icon: PawPrint, mobile: 'more' },
  { to: '/friends', label: '好友', icon: Users, mobile: 'more' },
  { to: '/attributes', label: '属性', icon: Activity, mobile: 'primary' },
  { to: '/insights', label: '洞察', icon: ChartNoAxesColumnIncreasing, mobile: 'more' },
  { to: '/ai', label: 'AI 助手', icon: Sparkles, mobile: 'primary' },
  { to: '/profile', label: '个人', icon: UserRound, mobile: 'primary' },
  { to: '/settings', label: '设置', icon: Settings, mobile: 'more' },
]
const mobileNav = nav.filter(item => item.mobile === 'primary')
const mobileMoreNav = nav.filter(item => item.mobile === 'more')

const auth = useAuthStore()
const route = useRoute()
const isDesktopCompanion = Boolean(window.betterSelfDesktop?.isDesktopApp)
const showWelcome = ref(false)
const showMobileMore = ref(false)
const rippleCanvas = ref<HTMLCanvasElement | null>(null)
const welcomeKey = computed(() => `better-self:welcome:${auth.user?.publicId ?? 'guest'}`)
const mobileMoreActive = computed(() => mobileMoreNav.some(item => route.path === item.to || route.path.startsWith(`${item.to}/`)))

type Rgb = { r: number; g: number; b: number }
type RipplePoint = { x: number; y: number }

const rippleEngine = {
  context: null as CanvasRenderingContext2D | null,
  texture: null as HTMLCanvasElement | null,
  textureContext: null as CanvasRenderingContext2D | null,
  imageData: null as ImageData | null,
  width: 0,
  height: 0,
  cssWidth: 0,
  cssHeight: 0,
  dpr: 1,
  current: new Float32Array(),
  previous: new Float32Array(),
  next: new Float32Array(),
  palette: null as { highlight: Rgb; secondary: Rgb; shadow: Rgb } | null,
  lastPointer: null as RipplePoint | null,
  frame: undefined as number | undefined,
  resizeFrame: undefined as number | undefined,
  activeUntil: 0,
  lastFrameTime: 0,
  elapsed: 0,
}
let appearanceObserver: MutationObserver | undefined
let motionQuery: MediaQueryList | undefined
let colorSchemeQuery: MediaQueryList | undefined
const brandInitial = computed(() => {
  const name = auth.user?.displayName?.trim() ?? ''
  if (!name) return '好'
  const characters = Array.from(name.replace(/\s+/g, ''))
  if (!characters.length) return '好'
  if (/\p{Script=Han}/u.test(characters[0])) return characters[0]
  const label = characters.slice(0, 2).join('')
  return label.charAt(0).toUpperCase() + label.slice(1)
})

function shouldShowWelcome() {
  return Boolean(auth.user?.publicId) && window.localStorage.getItem(welcomeKey.value) !== 'dismissed'
}

function openWelcome() {
  if (auth.user?.publicId) showWelcome.value = true
}

function dismissWelcome() {
  window.localStorage.setItem(welcomeKey.value, 'dismissed')
  showWelcome.value = false
}

function closeMobileMore() {
  showMobileMore.value = false
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}

function parseRgb(value: string, fallback: Rgb): Rgb {
  const normalized = value.trim()
  const hex = normalized.match(/^#([\da-f]{3}|[\da-f]{6})$/i)
  if (hex) {
    const source = hex[1].length === 3
      ? hex[1].split('').map(character => `${character}${character}`).join('')
      : hex[1]
    return {
      r: Number.parseInt(source.slice(0, 2), 16),
      g: Number.parseInt(source.slice(2, 4), 16),
      b: Number.parseInt(source.slice(4, 6), 16),
    }
  }
  const rgb = normalized.match(/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/i)
  if (rgb) return { r: Number(rgb[1]), g: Number(rgb[2]), b: Number(rgb[3]) }
  return fallback
}

function mixRgb(from: Rgb, to: Rgb, amount: number): Rgb {
  const ratio = clamp(amount, 0, 1)
  return {
    r: from.r + (to.r - from.r) * ratio,
    g: from.g + (to.g - from.g) * ratio,
    b: from.b + (to.b - from.b) * ratio,
  }
}

function get2dContext(canvas: HTMLCanvasElement) {
  try {
    return canvas.getContext('2d')
  } catch {
    return null
  }
}

function getRipplePalette() {
  const root = document.documentElement
  const styles = getComputedStyle(root)
  const read = (name: string, fallback: Rgb) => parseRgb(styles.getPropertyValue(name), fallback)
  const primary = read('--primary', { r: 57, g: 119, b: 109 })
  const accent = read('--accent', { r: 200, g: 95, b: 71 })
  const amber = read('--amber', { r: 189, g: 132, b: 34 })
  const ink = read('--ink', { r: 59, g: 49, b: 44 })
  const canvas = read('--canvas', { r: 248, g: 244, b: 238 })
  const dark = canvas.r * 0.2126 + canvas.g * 0.7152 + canvas.b * 0.0722 < 128
  const white = { r: 255, g: 255, b: 255 }
  const shadow = dark ? mixRgb(canvas, { r: 0, g: 0, b: 0 }, 0.7) : ink

  if (root.dataset.style === 'xianxia') {
    return { highlight: mixRgb(accent, white, 0.8), secondary: mixRgb(amber, white, 0.6), shadow }
  }
  return { highlight: mixRgb(primary, white, 0.72), secondary: mixRgb(accent, white, 0.55), shadow }
}

function isReducedMotion() {
  return document.documentElement.dataset.motion === 'reduced' || Boolean(motionQuery?.matches)
}

function isRippleEnabled() {
  const root = document.documentElement
  return root.dataset.background === 'ripple' && root.dataset.motion !== 'off' && !motionQuery?.matches && !document.hidden
}

function resizeRippleCanvas() {
  const canvas = rippleCanvas.value
  if (!canvas || !rippleEngine.context) return

  const cssWidth = Math.max(1, window.innerWidth)
  const cssHeight = Math.max(1, window.innerHeight)
  const dpr = clamp(window.devicePixelRatio || 1, 1, 2)
  const cellSize = Math.max(6, cssWidth / 320, cssHeight / 240)
  const width = Math.max(3, Math.round(cssWidth / cellSize))
  const height = Math.max(3, Math.round(cssHeight / cellSize))

  if (cssWidth === rippleEngine.cssWidth && cssHeight === rippleEngine.cssHeight && dpr === rippleEngine.dpr) return

  canvas.width = Math.floor(cssWidth * dpr)
  canvas.height = Math.floor(cssHeight * dpr)
  rippleEngine.context.setTransform(dpr, 0, 0, dpr, 0, 0)
  rippleEngine.context.clearRect(0, 0, cssWidth, cssHeight)
  rippleEngine.cssWidth = cssWidth
  rippleEngine.cssHeight = cssHeight
  rippleEngine.dpr = dpr

  if (!rippleEngine.texture) rippleEngine.texture = document.createElement('canvas')
  rippleEngine.texture.width = width
  rippleEngine.texture.height = height
  rippleEngine.textureContext = get2dContext(rippleEngine.texture)
  if (!rippleEngine.textureContext) return

  rippleEngine.width = width
  rippleEngine.height = height
  rippleEngine.imageData = rippleEngine.textureContext.createImageData(width, height)
  rippleEngine.current = new Float32Array(width * height)
  rippleEngine.previous = new Float32Array(width * height)
  rippleEngine.next = new Float32Array(width * height)
  rippleEngine.lastPointer = null
  rippleEngine.lastFrameTime = 0
  rippleEngine.elapsed = 0
}

function clearRippleCanvas() {
  rippleEngine.current.fill(0)
  rippleEngine.previous.fill(0)
  rippleEngine.next.fill(0)
  rippleEngine.lastPointer = null
  rippleEngine.activeUntil = 0
  rippleEngine.lastFrameTime = 0
  rippleEngine.elapsed = 0
  if (rippleEngine.context) {
    rippleEngine.context.setTransform(rippleEngine.dpr, 0, 0, rippleEngine.dpr, 0, 0)
    rippleEngine.context.clearRect(0, 0, rippleEngine.cssWidth, rippleEngine.cssHeight)
  }
}

function injectRipple(x: number, y: number, strength: number) {
  const { width, height, current, previous } = rippleEngine
  if (!width || !height) return
  const radius = 3.2
  const minX = Math.max(1, Math.floor(x - radius * 2.2))
  const maxX = Math.min(width - 2, Math.ceil(x + radius * 2.2))
  const minY = Math.max(1, Math.floor(y - radius * 2.2))
  const maxY = Math.min(height - 2, Math.ceil(y + radius * 2.2))

  for (let iy = minY; iy <= maxY; iy += 1) {
    for (let ix = minX; ix <= maxX; ix += 1) {
      const distance = Math.hypot(ix - x, iy - y)
      const distanceSquared = (distance * distance) / (radius * radius)
      const displacement = strength * (1 - distanceSquared) * Math.exp(-distanceSquared)
      const index = iy * width + ix
      current[index] = clamp(current[index] + displacement, -2, 2)
      previous[index] = clamp(previous[index] + displacement, -2, 2)
    }
  }
}

function stepRipple() {
  const { width, height, current, previous, next } = rippleEngine
  if (!width || !height) return
  next.fill(0)
  const damping = isReducedMotion() ? 0.978 : 0.99

  for (let y = 1; y < height - 1; y += 1) {
    const row = y * width
    for (let x = 1; x < width - 1; x += 1) {
      const index = row + x
      next[index] = (
        (current[index - 1] + current[index + 1] + current[index - width] + current[index + width]) * 0.5
        - previous[index]
      ) * damping
    }
  }

  rippleEngine.previous = current
  rippleEngine.current = next
  rippleEngine.next = previous
}

function renderRipple(time: number) {
  const { width, height, current, textureContext, imageData, context, palette } = rippleEngine
  if (!width || !height || !textureContext || !imageData || !context || !palette) return

  const pixels = imageData.data
  const darkShadow = mixRgb(palette.shadow, palette.highlight, 0.12)
  const reduced = isReducedMotion()

  for (let y = 1; y < height - 1; y += 1) {
    for (let x = 1; x < width - 1; x += 1) {
      const index = y * width + x
      const slopeX = (current[index + 1] - current[index - 1]) * 0.5
      const slopeY = (current[index + width] - current[index - width]) * 0.5
      const light = clamp(slopeX * -3.2 + slopeY * -4.2, -1, 1)
      const waveEnergy = 1 - Math.exp(-Math.abs(light) * 2)
      const pixel = index * 4

      if (waveEnergy < 0.008) {
        pixels[pixel + 3] = 0
        continue
      }

      const specular = clamp((light - 0.06) * 1.35, 0, 1)
      const shimmer = specular * (0.58 + Math.sin(time * 0.00055 + x * 0.11 + y * 0.07) * 0.18)
      const color = light >= 0
        ? mixRgb(palette.highlight, palette.secondary, clamp(0.16 + shimmer * 0.62, 0, 1))
        : mixRgb(darkShadow, palette.highlight, clamp(Math.abs(light) * 0.16, 0, 1))
      const alpha = clamp((waveEnergy * (light >= 0 ? 0.58 : 0.36) + specular * 0.14) * (reduced ? 0.6 : 1), 0, 0.56)

      pixels[pixel] = Math.round(color.r)
      pixels[pixel + 1] = Math.round(color.g)
      pixels[pixel + 2] = Math.round(color.b)
      pixels[pixel + 3] = Math.round(alpha * 255)
    }
  }

  textureContext.putImageData(imageData, 0, 0)
  context.setTransform(rippleEngine.dpr, 0, 0, rippleEngine.dpr, 0, 0)
  context.clearRect(0, 0, rippleEngine.cssWidth, rippleEngine.cssHeight)
  context.imageSmoothingEnabled = true
  context.drawImage(rippleEngine.texture!, 0, 0, rippleEngine.cssWidth, rippleEngine.cssHeight)
}

function requestRippleFrame() {
  if (rippleEngine.frame === undefined && isRippleEnabled() && rippleEngine.imageData) {
    if (!rippleEngine.lastFrameTime) rippleEngine.lastFrameTime = performance.now()
    rippleEngine.frame = requestAnimationFrame(animateRipple)
  }
}

function animateRipple(time: number) {
  rippleEngine.frame = undefined
  if (!isRippleEnabled()) {
    clearRippleCanvas()
    return
  }

  const interval = isReducedMotion() ? 1000 / 30 : 1000 / 60
  rippleEngine.elapsed += clamp(time - rippleEngine.lastFrameTime, 0, 50)
  rippleEngine.lastFrameTime = time
  let updated = false
  while (rippleEngine.elapsed >= interval) {
    stepRipple()
    rippleEngine.elapsed -= interval
    updated = true
  }
  if (updated) renderRipple(time)

  let maxAmplitude = 0
  for (let index = 0; index < rippleEngine.current.length; index += 1) {
    maxAmplitude = Math.max(maxAmplitude, Math.abs(rippleEngine.current[index]), Math.abs(rippleEngine.previous[index]))
  }

  if (maxAmplitude > 0.004 || time < rippleEngine.activeUntil) requestRippleFrame()
  else clearRippleCanvas()
}

function followPointer(event: PointerEvent) {
  if (!isRippleEnabled() || !rippleEngine.imageData || event.pointerType === 'touch') return
  const x = clamp(event.clientX / Math.max(window.innerWidth, 1), 0, 1) * (rippleEngine.width - 1)
  const y = clamp(event.clientY / Math.max(window.innerHeight, 1), 0, 1) * (rippleEngine.height - 1)
  const previous = rippleEngine.lastPointer
  const distance = previous ? Math.hypot(x - previous.x, y - previous.y) : 0
  if (previous && distance < 0.65) return

  const strength = (previous ? clamp(0.3 + distance * 0.025, 0.3, 0.85) : 0.8) * (isReducedMotion() ? 0.6 : 1)
  const samples = previous ? Math.min(12, Math.max(1, Math.ceil(distance / 2))) : 1
  for (let sample = 1; sample <= samples; sample += 1) {
    const fraction = sample / samples
    injectRipple(previous ? previous.x + (x - previous.x) * fraction : x, previous ? previous.y + (y - previous.y) * fraction : y, strength / Math.sqrt(samples))
  }
  rippleEngine.lastPointer = { x, y }
  rippleEngine.activeUntil = performance.now() + 3200
  requestRippleFrame()
}

function syncRippleAppearance() {
  rippleEngine.palette = getRipplePalette()
  if (!isRippleEnabled()) {
    if (rippleEngine.frame !== undefined) cancelAnimationFrame(rippleEngine.frame)
    if (rippleEngine.resizeFrame !== undefined) cancelAnimationFrame(rippleEngine.resizeFrame)
    rippleEngine.frame = undefined
    rippleEngine.resizeFrame = undefined
    clearRippleCanvas()
    return
  }
  if (!rippleEngine.context && rippleCanvas.value) rippleEngine.context = get2dContext(rippleCanvas.value)
  resizeRippleCanvas()
}

function scheduleRippleResize() {
  if (rippleEngine.resizeFrame !== undefined || !isRippleEnabled()) return
  rippleEngine.resizeFrame = requestAnimationFrame(() => {
    rippleEngine.resizeFrame = undefined
    resizeRippleCanvas()
  })
}

watch(() => route.fullPath, closeMobileMore)

onMounted(() => {
  showWelcome.value = shouldShowWelcome()
  window.addEventListener('better-self:show-welcome', openWelcome)
  window.addEventListener('pointermove', followPointer, { passive: true })
  window.addEventListener('resize', scheduleRippleResize, { passive: true })
  document.addEventListener('visibilitychange', syncRippleAppearance)
  if (typeof window.matchMedia === 'function') {
    motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
    motionQuery.addEventListener?.('change', syncRippleAppearance)
    colorSchemeQuery = window.matchMedia('(prefers-color-scheme: dark)')
    colorSchemeQuery.addEventListener?.('change', syncRippleAppearance)
  }
  syncRippleAppearance()
  if (typeof MutationObserver !== 'undefined') {
    appearanceObserver = new MutationObserver(syncRippleAppearance)
    appearanceObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-background', 'data-style', 'data-theme', 'data-accent', 'data-motion'],
    })
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('better-self:show-welcome', openWelcome)
  window.removeEventListener('pointermove', followPointer)
  window.removeEventListener('resize', scheduleRippleResize)
  document.removeEventListener('visibilitychange', syncRippleAppearance)
  motionQuery?.removeEventListener?.('change', syncRippleAppearance)
  colorSchemeQuery?.removeEventListener?.('change', syncRippleAppearance)
  appearanceObserver?.disconnect()
  if (rippleEngine.frame !== undefined) cancelAnimationFrame(rippleEngine.frame)
  if (rippleEngine.resizeFrame !== undefined) cancelAnimationFrame(rippleEngine.resizeFrame)
})
</script>
<template>
  <canvas ref="rippleCanvas" class="ripple-canvas" aria-hidden="true" />
  <div class="shell">
    <aside class="sidebar">
      <RouterLink class="brand" to="/today"><span class="brand-mark" :aria-label="`${auth.user?.displayName ?? '用户'}的标识`">{{ brandInitial }}</span><strong>更好的自己</strong></RouterLink>
      <nav aria-label="主导航"><RouterLink v-for="item in nav" :key="item.to" :to="item.to"><component :is="item.icon" :size="19"/><span>{{ item.label }}</span></RouterLink></nav>
      <p class="sidebar-note"><Heart :size="16" fill="currentColor" /><span>今天完成一点，也很好。</span></p>
    </aside>
    <main class="workspace">
      <OperationGuideBar />
      <RouterView v-slot="{ Component, route }">
        <Transition name="route-view" mode="out-in">
          <component :is="Component" :key="route.path" />
        </Transition>
      </RouterView>
    </main>
    <Transition name="mobile-more">
      <div v-if="showMobileMore" class="mobile-more-layer">
        <button class="mobile-more-backdrop" type="button" aria-label="关闭更多功能" @click="closeMobileMore" />
        <section id="mobile-more-menu" class="mobile-more-sheet" aria-labelledby="mobile-more-title">
          <header>
            <div><p class="eyebrow">完整导航</p><h2 id="mobile-more-title">更多功能</h2></div>
            <button class="icon-button" type="button" aria-label="关闭更多功能" @click="closeMobileMore"><X :size="20" /></button>
          </header>
          <nav class="mobile-more-links" aria-label="更多功能">
            <RouterLink v-for="item in mobileMoreNav" :key="item.to" :to="item.to" @click="closeMobileMore">
              <component :is="item.icon" :size="21" />
              <span>{{ item.label }}</span>
            </RouterLink>
          </nav>
        </section>
      </div>
    </Transition>
    <nav class="mobile-nav" aria-label="主导航">
      <RouterLink v-for="item in mobileNav" :key="item.to" :to="item.to"><component :is="item.icon" :size="20"/><span>{{ item.label }}</span></RouterLink>
      <button type="button" :class="{ 'is-active': mobileMoreActive }" :aria-expanded="showMobileMore" aria-controls="mobile-more-menu" @click="showMobileMore = !showMobileMore"><Menu :size="20"/><span>更多</span></button>
    </nav>
    <DesktopPet v-if="!isDesktopCompanion" />
    <GlobalUnreadBar />
    <WelcomeGuide v-if="showWelcome" @dismiss="dismissWelcome" />
  </div>
</template>
<style scoped>
.shell { min-height: 100vh; padding-left: var(--sidebar); }
.sidebar { position: fixed; inset: 0 auto 0 0; width: var(--sidebar); border-right: 1px solid var(--border); background: color-mix(in srgb, var(--surface) 96%, var(--canvas)); padding: 18px 14px; display: flex; flex-direction: column; z-index: 10; box-shadow: 7px 0 24px color-mix(in srgb, var(--ink) 5%, transparent); }
.sidebar::before { content: ""; position: absolute; inset: 0 0 auto; height: 3px; background: var(--topline); }
.brand { display: flex; align-items: center; gap: 10px; color: var(--ink); text-decoration: none; padding: 7px 8px 22px; }
.brand-mark { position: relative; width: 36px; height: 36px; display: grid; place-items: center; border-radius: 8px; background: var(--primary); color: white; font-weight: 900; box-shadow: 0 10px 20px color-mix(in srgb, var(--primary) 22%, transparent); }
.brand-mark::after { content: ""; position: absolute; right: -3px; bottom: -3px; width: 10px; height: 10px; border: 2px solid var(--surface); border-radius: 50%; background: var(--accent); }
nav { display: grid; gap: 5px; }
nav a { position: relative; min-height: 44px; display: flex; align-items: center; gap: 12px; padding: 0 12px; border-radius: var(--radius); color: var(--muted); text-decoration: none; font-size: 14px; }
nav a:hover { background: color-mix(in srgb, var(--surface-muted) 72%, var(--surface)); color: var(--ink); }
nav a.router-link-active { background: color-mix(in srgb, var(--primary-soft) 76%, var(--surface)); color: var(--primary-strong); font-weight: 800; box-shadow: inset 4px 0 0 var(--primary); }
.sidebar-note { margin-top: auto; display: grid; grid-template-columns: 18px minmax(0, 1fr); gap: 8px; align-items: start; padding: 13px 12px; border: 1px solid color-mix(in srgb, var(--amber) 30%, var(--border)); border-radius: var(--radius); background: color-mix(in srgb, var(--amber) 8%, var(--surface)); color: var(--muted); font-size: 12px; line-height: 1.65; }
.sidebar-note svg { margin-top: 2px; color: var(--primary); }
.workspace { min-width: 0; }
.ripple-canvas { position: fixed; inset: 0; z-index: 0; width: 100%; height: 100%; pointer-events: none; opacity: 0; transition: opacity var(--motion-medium) ease; }
:root[data-background='ripple'] .ripple-canvas { opacity: 1; }
:root[data-motion='off'] .ripple-canvas { opacity: 0; }
.mobile-nav { display: none; }
.mobile-more-layer { display: none; }
:root[data-style='cyberpunk'] .shell { position: relative; isolation: isolate; }
:root[data-style='cyberpunk'] .sidebar { background: color-mix(in srgb, var(--surface) 90%, transparent); backdrop-filter: blur(12px); box-shadow: 8px 0 0 rgb(125 249 255 / 6%), 14px 0 32px rgb(0 0 0 / 28%); }
:root[data-style='cyberpunk'] .sidebar::after { content: ""; position: absolute; inset: 84px 0 auto; height: 1px; background: repeating-linear-gradient(90deg, var(--primary) 0 18px, transparent 18px 26px); opacity: .65; }
:root[data-style='cyberpunk'] .brand { font-family: 'Share Tech Mono', 'PingFang SC', sans-serif; text-transform: uppercase; letter-spacing: .06em; }
:root[data-style='cyberpunk'] .brand-mark { border-radius: 1px; color: #071018; box-shadow: 0 0 18px rgb(125 249 255 / 32%); }
:root[data-style='cyberpunk'] nav a.router-link-active { box-shadow: inset 3px 0 var(--primary), 0 0 18px rgb(125 249 255 / 8%); }
:root[data-style='cyberpunk'] .sidebar-note { border-style: dashed; border-radius: 1px; background: rgb(17 24 39 / 72%); }
:root[data-style='xianxia'] .sidebar { background: color-mix(in srgb, var(--surface) 88%, transparent); box-shadow: 8px 0 28px rgb(77 56 28 / 8%); }
:root[data-style='xianxia'] .brand { font-family: var(--font-display); font-size: 18px; }
:root[data-style='xianxia'] .brand-mark { border-radius: 18px 5px 18px 5px; box-shadow: 0 8px 18px rgb(164 71 49 / 22%); }
:root[data-style='xianxia'] nav a.router-link-active { box-shadow: inset 3px 0 var(--primary); }
:root[data-style='xianxia'] .sidebar-note { border-radius: 24px 5px 24px 5px; }
@media (max-width:760px) {
  .shell { padding-left: 0; }
  .sidebar { display:none; }
  .workspace { padding-bottom: calc(70px + env(safe-area-inset-bottom)); }
  .mobile-nav { position: fixed; display:grid; grid-template-columns:repeat(6,minmax(0,1fr)); inset:auto 0 0; z-index:20; background: color-mix(in srgb, var(--surface) 96%, transparent); backdrop-filter: blur(16px); border-top:1px solid var(--border); box-shadow:0 -10px 26px color-mix(in srgb, var(--ink) 8%, transparent); padding:6px 4px calc(6px + env(safe-area-inset-bottom)); }
  .mobile-nav a,
  .mobile-nav button { min-width:0; min-height:54px; display:flex; align-items:center; justify-content:center; flex-direction:column; gap:3px; padding:2px; border:0; border-radius:var(--radius); background:transparent; color:var(--muted); font:inherit; font-size:10px; cursor:pointer; }
  .mobile-nav button[aria-expanded='true'],
  .mobile-nav button.is-active { background:color-mix(in srgb, var(--primary-soft) 76%, var(--surface)); color:var(--primary-strong); font-weight:800; box-shadow:inset 4px 0 0 var(--primary); }
  .mobile-more-layer { display:block; }
  .mobile-more-backdrop { position:fixed; inset:0 0 calc(66px + env(safe-area-inset-bottom)); z-index:18; width:100%; border:0; background:color-mix(in srgb, var(--ink) 24%, transparent); }
  .mobile-more-sheet { position:fixed; inset:auto 10px calc(76px + env(safe-area-inset-bottom)); z-index:19; padding:18px; border:1px solid var(--border); border-radius:var(--radius); background:var(--surface); box-shadow:0 18px 48px color-mix(in srgb, var(--ink) 24%, transparent); }
  .mobile-more-sheet header { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-bottom:14px; }
  .mobile-more-sheet h2 { margin:2px 0 0; font-size:20px; }
  .mobile-more-sheet .icon-button { min-width:42px; min-height:42px; }
  .mobile-more-links { grid-template-columns:repeat(4,minmax(0,1fr)); gap:8px; }
  .mobile-more-links a { min-width:0; min-height:76px; justify-content:center; flex-direction:column; gap:7px; padding:8px 4px; border:1px solid var(--border); background:var(--surface-muted); font-size:12px; }
  .mobile-more-links a.router-link-active { box-shadow:inset 0 3px 0 var(--primary); }
  .mobile-more-enter-active,
  .mobile-more-leave-active { transition:opacity var(--motion-fast) ease; }
  .mobile-more-enter-active .mobile-more-sheet,
  .mobile-more-leave-active .mobile-more-sheet { transition:transform var(--motion-medium) ease, opacity var(--motion-fast) ease; }
  .mobile-more-enter-from,
  .mobile-more-leave-to { opacity:0; }
  .mobile-more-enter-from .mobile-more-sheet,
  .mobile-more-leave-to .mobile-more-sheet { transform:translateY(12px); opacity:0; }
}
@media (prefers-reduced-motion: reduce) { .ripple-canvas { opacity: 0 !important; transition: none; } }
@media (prefers-reduced-motion: no-preference) { .brand-mark { transition: transform var(--motion-medium) ease; } .brand:hover .brand-mark { transform: rotate(-8deg) scale(1.04); } nav a.router-link-active svg { animation: nav-pop var(--motion-medium) ease-out; } .route-view-enter-active, .route-view-leave-active { transition: opacity var(--motion-medium) ease, transform var(--motion-medium) ease; } .route-view-enter-from { opacity: 0; transform: translateY(8px); } .route-view-leave-to { opacity: 0; transform: translateY(-4px); } }
@keyframes nav-pop { 0% { transform: scale(.88); } 70% { transform: scale(1.08); } 100% { transform: scale(1); } }
</style>
