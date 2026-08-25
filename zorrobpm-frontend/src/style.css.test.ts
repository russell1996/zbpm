// WO-ACL-10 criteria 2, 16, 17 — checked against the real style.css file.
// Read via node:fs — the tailwind vite plugin intercepts raw/query CSS imports
// and returns an empty string, so import.meta.glob cannot read CSS content.
// @types/node is a devDependency so the build's tsc accepts node:fs.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const css = readFileSync(fileURLToPath(new URL('./style.css', import.meta.url)), 'utf8')

describe('style.css WO-ACL-10', () => {
  it('criterion 2: app background and sidebar are plain white (#ffffff)', () => {
    expect(css).toContain('--color-background: #ffffff')
    expect(css).toContain('--color-sidebar: #ffffff')
    expect(css).not.toContain('#f7f8fa')
    // the brand accent must not have been touched while changing backgrounds
    expect(css).toContain('--color-primary: #0075e3')
  })

  it('criterion 16: every color token from the light palette also exists in .dark', () => {
    const lightBlock = css.slice(0, css.indexOf('.dark {'))
    const darkBlock = css.slice(css.indexOf('.dark {'))
    const tokens = (block: string) =>
      [...block.matchAll(/--color-([a-z-]+):/g)].map(m => m[1]).sort()
    const light = [...new Set(tokens(lightBlock))]
    const dark = [...new Set(tokens(darkBlock))]
    expect(dark).toEqual(light)
  })

  it('criterion 17: no "KT Docs" mentions remain in style.css', () => {
    expect(css).not.toContain('KT Docs')
  })
})