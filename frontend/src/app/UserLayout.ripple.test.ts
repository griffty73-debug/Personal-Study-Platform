import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import UserLayout from './UserLayout.vue'

vi.mock('../shared/ui/WelcomeGuide.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../modules/partners/DesktopPet.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../shared/ui/GlobalUnreadBar.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../shared/ui/OperationGuideBar.vue', () => ({ default: { template: '<div />' } }))

let wrapper: VueWrapper | undefined
let clock = 1000
let frameId = 0
const frames = new Map<number, FrameRequestCallback>()
const motionQuery = Object.assign(new EventTarget(), { matches: false })
const context = {
  clearRect: vi.fn(),
  setTransform: vi.fn(),
  drawImage: vi.fn(),
  putImageData: vi.fn(),
  createImageData: vi.fn((width: number, height: number) => ({ width, height, data: new Uint8ClampedArray(width * height * 4) })),
}

function runFrame(duration = 17) {
  clock += duration
  const callbacks = [...frames.values()]
  frames.clear()
  for (const callback of callbacks) callback(clock)
}

function movePointer(clientX = 420, clientY = 300) {
  window.dispatchEvent(new MouseEvent('pointermove', { clientX, clientY }))
}

async function mountLayout() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<section class="page"><h1>页面内容</h1></section>' } }],
  })
  await router.push('/settings')
  wrapper = mount(UserLayout, { global: { plugins: [createPinia(), router], stubs: { transition: false } } })
  return router
}

beforeEach(() => {
  clock = 1000
  frameId = 0
  frames.clear()
  motionQuery.matches = false
  vi.clearAllMocks()
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.spyOn(performance, 'now').mockImplementation(() => clock)
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as unknown as CanvasRenderingContext2D)
  vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => {
    const identifier = frameId++
    frames.set(identifier, callback)
    return identifier
  }))
  vi.stubGlobal('cancelAnimationFrame', vi.fn((identifier: number) => frames.delete(identifier)))
  vi.stubGlobal('matchMedia', vi.fn((query: string) => query.includes('reduced-motion') ? motionQuery : new EventTarget()))
  document.documentElement.dataset.background = 'ripple'
  document.documentElement.dataset.motion = 'standard'
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  for (const attribute of ['data-background', 'data-motion', 'data-theme', 'data-style', 'data-accent']) {
    document.documentElement.removeAttribute(attribute)
  }
})

describe('UserLayout ripple lifecycle', () => {
  it('draws visible moving water and lets the wave settle without a permanent animation loop', async () => {
    await mountLayout()
    movePointer()
    runFrame()

    const firstImage = context.putImageData.mock.calls.at(-1)![0] as ImageData
    const alpha = Array.from(firstImage.data).filter((_, index) => index % 4 === 3)
    expect(Math.max(...alpha)).toBeGreaterThan(70)
    expect(alpha.filter(value => value > 30).length).toBeGreaterThan(25)
    const initialPixels = firstImage.data.slice()

    for (let frame = 0; frame < 30; frame += 1) runFrame()
    expect(firstImage.data).not.toEqual(initialPixels)
    expect(context.drawImage).toHaveBeenCalled()

    for (let frame = 0; frame < 1500 && frames.size; frame += 1) runFrame()
    expect(frames.size).toBe(0)
  })

  it('stops and clears water when motion is off, retains route content, and resumes on re-enable', async () => {
    const router = await mountLayout()
    movePointer()
    expect(frames.size).toBe(1)
    document.documentElement.dataset.motion = 'off'
    await flushPromises()

    expect(frames.size).toBe(0)
    expect(cancelAnimationFrame).toHaveBeenCalledWith(0)
    expect(context.clearRect).toHaveBeenCalled()
    movePointer(620)
    expect(frames.size).toBe(0)

    await router.push('/today')
    await flushPromises()
    for (let frame = 0; frame < 3; frame += 1) runFrame()
    await flushPromises()
    expect(wrapper!.get('h1').text()).toBe('页面内容')
    expect(wrapper!.find('.sidebar').exists()).toBe(true)

    document.documentElement.dataset.motion = 'standard'
    await flushPromises()
    movePointer(520)
    runFrame()
    expect(context.putImageData).toHaveBeenCalled()
  })

  it('honors live system reduced-motion changes and cleans up listeners on unmount', async () => {
    await mountLayout()
    movePointer()
    motionQuery.matches = true
    motionQuery.dispatchEvent(new Event('change'))
    expect(frames.size).toBe(0)
    movePointer(620)
    expect(frames.size).toBe(0)

    motionQuery.matches = false
    motionQuery.dispatchEvent(new Event('change'))
    movePointer(520)
    expect(frames.size).toBe(1)
    wrapper!.unmount()
    wrapper = undefined
    expect(frames.size).toBe(0)
    movePointer(720)
    window.dispatchEvent(new Event('resize'))
    motionQuery.dispatchEvent(new Event('change'))
    expect(frames.size).toBe(0)
  })

  it('does not allocate water buffers for other backgrounds and stops after switching away', async () => {
    document.documentElement.dataset.background = 'paper'
    await mountLayout()
    movePointer()
    window.dispatchEvent(new Event('resize'))
    expect(context.createImageData).not.toHaveBeenCalled()
    expect(frames.size).toBe(0)

    document.documentElement.dataset.background = 'ripple'
    await flushPromises()
    movePointer()
    runFrame()
    expect(context.putImageData).toHaveBeenCalled()
    document.documentElement.dataset.background = 'mirror'
    await flushPromises()
    expect(frames.size).toBe(0)
  })

  it('keeps content usable when Canvas is unavailable', async () => {
    vi.mocked(HTMLCanvasElement.prototype.getContext).mockReturnValue(null)
    await mountLayout()
    movePointer()
    expect(frames.size).toBe(0)
    expect(wrapper!.get('h1').text()).toBe('页面内容')
  })

  it('uses the same wave propagation speed at different display refresh rates', async () => {
    await mountLayout()
    movePointer()
    for (let frame = 0; frame < 60; frame += 1) runFrame(16)
    const slowerImage = context.putImageData.mock.calls.at(-1)![0] as ImageData
    const slowerAlpha = Array.from(slowerImage.data).filter((_, index) => index % 4 === 3)
    wrapper!.unmount()
    clock = 1000

    await mountLayout()
    movePointer()
    for (let frame = 0; frame < 120; frame += 1) runFrame(8)
    const fasterImage = context.putImageData.mock.calls.at(-1)![0] as ImageData
    const fasterAlpha = Array.from(fasterImage.data).filter((_, index) => index % 4 === 3)
    expect(fasterAlpha).toEqual(slowerAlpha)
  })

  it('resizes for a narrow high-density screen without stretching the simulation grid', async () => {
    await mountLayout()
    vi.stubGlobal('innerWidth', 390)
    vi.stubGlobal('innerHeight', 844)
    vi.stubGlobal('devicePixelRatio', 2)
    window.dispatchEvent(new Event('resize'))
    runFrame()

    const canvas = wrapper!.get('canvas').element as HTMLCanvasElement
    expect(canvas.width).toBe(780)
    expect(canvas.height).toBe(1688)
    const [width, height] = context.createImageData.mock.calls.at(-1)!
    expect(width / height).toBeCloseTo(390 / 844, 2)
    movePointer(190, 300)
    runFrame()
    expect(context.putImageData).toHaveBeenCalled()
  })

  it('does not start drawing when the saved motion preference is off', async () => {
    document.documentElement.dataset.motion = 'off'
    await mountLayout()
    movePointer()
    expect(context.createImageData).not.toHaveBeenCalled()
    expect(frames.size).toBe(0)
    expect(wrapper!.get('h1').text()).toBe('页面内容')
  })
})
