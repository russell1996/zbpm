// WO-OPS-18 criterion 1 — nginx trusts ONLY the real production edge segment.
// Read via node:fs (same pattern as style.css.test.ts): the built nginx.conf is not
// part of the Vite module graph, so it cannot be imported.
// The CIDR below is pinned by a live capture on the prod host (CTO, 2026-09-24:
// tcpdump on dst port 8081 during a real request caught 10.189.131.242 ->
// 10.189.131.111:8081; host itself 10.189.131.111/24, ens160) — not guessed.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const conf = readFileSync(fileURLToPath(new URL('../nginx.conf', import.meta.url)), 'utf8')

describe('nginx.conf WO-OPS-18', () => {
  it('criterion 1: trusts the real edge segment 10.189.131.0/24', () => {
    expect(conf).toContain('set_real_ip_from 10.189.131.0/24;')
  })

  it('criterion 1: the wide 10.0.0.0/8 placeholder is gone', () => {
    expect(conf).not.toContain('10.0.0.0/8')
  })

  it('criterion 1: the TODO(CTO) placeholder marker is gone', () => {
    expect(conf).not.toContain('TODO(CTO)')
  })

  it('guard: real-ip rewriting + rate-limit gate still wired (no accidental deletion)', () => {
    expect(conf).toContain('real_ip_header X-Forwarded-For;')
    expect(conf).toContain('real_ip_recursive on;')
    expect(conf).toContain('limit_req_zone $binary_remote_addr')
  })
})
