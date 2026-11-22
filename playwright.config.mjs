import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { defineConfig } from '@playwright/test'

const port = process.env.COMMITMENTS_BROWSER_PORT || '9182'
const address = 'http://127.0.0.1:' + port
const database = join(mkdtempSync(join(tmpdir(), 'dice-commitments-browser-')), 'commitments.sqlite')

export default defineConfig({
  testDir: './browser',
  workers: 1,
  use: { baseURL: address },
  webServer: {
    command: 'java -jar target/dice-customer-commitments.jar',
    env: { COMMITMENTS_PORT: port, COMMITMENTS_DB: database },
    url: address,
    reuseExistingServer: false,
    timeout: 40_000,
  },
})
