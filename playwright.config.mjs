import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { defineConfig } from '@playwright/test'

const live = process.env.COMMITMENTS_BROWSER_MODE === 'live'
const port = process.env.COMMITMENTS_BROWSER_PORT || (live ? '9183' : '9182')
const address = 'http://127.0.0.1:' + port
const database = join(mkdtempSync(join(tmpdir(), 'dice-commitments-browser-')), 'commitments.sqlite')

export default defineConfig({
  testDir: './browser',
  testMatch: live ? 'live-commitments.spec.mjs' : 'commitments.spec.mjs',
  workers: 1,
  use: { baseURL: address },
  webServer: {
    command: live
      ? './mvnw -B spring-boot:test-run -Dspring-boot.run.main-class=example.dice.commitments.LiveBrowserFixture'
      : 'java -jar target/dice-customer-commitments.jar',
    env: { COMMITMENTS_PORT: port, COMMITMENTS_DB: database },
    url: live ? address + '/live.html' : address,
    reuseExistingServer: false,
    timeout: live ? 90_000 : 40_000,
  },
})
