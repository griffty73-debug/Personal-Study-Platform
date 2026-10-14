import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it } from 'vitest'
import { compileStyle, parse } from 'vue/compiler-sfc'

const scopeId = 'data-v-layout-regression'
const layoutSource = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'UserLayout.vue'), 'utf8')
const { descriptor } = parse(layoutSource)
const stylesheet = descriptor.styles.map(style => compileStyle({
  source: style.content,
  filename: 'UserLayout.vue',
  id: scopeId,
  scoped: style.scoped,
}).code).join('\n')

afterEach(() => {
  document.head.querySelector('[data-layout-test]')?.remove()
  document.body.querySelector('[data-layout-test]')?.remove()
  for (const attribute of ['data-style', 'data-background', 'data-motion']) {
    document.documentElement.removeAttribute(attribute)
  }
})

describe('UserLayout compiled styles', () => {
  it.each(['default', 'cyberpunk', 'xianxia'])('keeps %s content visible when motion is off and restores only the water layer', style => {
    const stylesheetElement = document.createElement('style')
    stylesheetElement.setAttribute('data-layout-test', '')
    stylesheetElement.textContent = stylesheet
    document.head.append(stylesheetElement)

    const container = document.createElement('div')
    container.setAttribute('data-layout-test', '')
    const canvas = document.createElement('canvas')
    canvas.className = 'ripple-canvas'
    canvas.setAttribute(scopeId, '')
    const content = document.createElement('main')
    content.className = 'workspace'
    content.setAttribute(scopeId, '')
    container.append(canvas, content)
    document.body.append(container)

    const root = document.documentElement
    root.dataset.style = style
    root.dataset.background = 'ripple'
    root.dataset.motion = 'off'

    expect(getComputedStyle(root).opacity).not.toBe('0')
    expect(getComputedStyle(content).opacity).not.toBe('0')
    expect(getComputedStyle(canvas).opacity).toBe('0')
    expect(getComputedStyle(canvas).pointerEvents).toBe('none')

    root.dataset.motion = 'standard'
    expect(Number(getComputedStyle(canvas).opacity)).toBeGreaterThanOrEqual(0.8)

    root.dataset.background = 'smooth'
    expect(getComputedStyle(canvas).opacity).toBe('0')
  })

  it.each(['default', 'cyberpunk', 'xianxia'])('never applies %s component styling to the document root', style => {
    const root = document.documentElement
    root.dataset.style = style
    root.dataset.background = 'ripple'
    root.dataset.motion = 'off'
    for (const block of descriptor.styles) {
      const compiled = compileStyle({ source: block.content, filename: 'UserLayout.vue', id: scopeId, scoped: block.scoped })
      expect(compiled.errors).toEqual([])
      compiled.rawResult?.root.walkRules(rule => {
        if (rule.selector.includes(':root')) expect(root.matches(rule.selector), rule.selector).toBe(false)
      })
    }
  })
})
