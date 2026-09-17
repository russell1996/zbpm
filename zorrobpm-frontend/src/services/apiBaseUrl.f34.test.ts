import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'

/**
 * WO-QW-1 F34: the SPA must be buildable for both topologies — the default
 * (external proxy, VITE_API_URL=/) and standalone compose (VITE_API_URL=/api).
 * The Dockerfile exposes the choice as a build ARG with a documented default;
 * api.ts keeps the '/api' runtime fallback for dev.
 */
describe('F34 standalone-topology build arg', () => {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
  const dockerfile = readFileSync(resolve(root, 'Dockerfile'), 'utf-8')
  const example = readFileSync(resolve(root, '.env.production.example'), 'utf-8')

  it('Dockerfile declares ARG VITE_API_URL defaulting to /', () => {
    expect(dockerfile).toMatch(/^ARG VITE_API_URL=\/$/m)
    expect(dockerfile).toMatch(/^ENV VITE_API_URL=\$\{VITE_API_URL\}$/m)
  })

  it('Dockerfile no longer hardcodes ENV VITE_API_URL=/', () => {
    expect(dockerfile).not.toMatch(/^ENV VITE_API_URL=\/$/m)
  })

  it('api.ts keeps the /api fallback (dev + standalone default)', () => {
    const api = readFileSync(resolve(root, 'src/services/api.ts'), 'utf-8')
    expect(api).toContain("import.meta.env.VITE_API_URL || '/api'")
  })

  it('example env documents both topologies', () => {
    expect(example).toContain('--build-arg VITE_API_URL=/api')
    // S1 (nginx CIDR placeholder) is explicitly a different issue.
    expect(example).toContain('S1')
  })

  it('nginx still proxies /api/ to the backend (standalone path intact)', () => {
    const nginx = readFileSync(resolve(root, 'nginx.conf'), 'utf-8')
    expect(nginx).toMatch(/location \/api\/ \{/)
    expect(existsSync(resolve(root, 'Dockerfile'))).toBe(true)
  })
})
