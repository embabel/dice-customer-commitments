import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test } from '@playwright/test'

test('follows a bot citation to the amendment and replays the opening commitment on mobile', async ({ page }) => {
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 1040 })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Tandem Transit' })).toBeVisible()
  const ask = page.getByRole('button', { name: 'Ask', exact: true })
  await expect(ask).toBeEnabled()
  await ask.focus()
  await expect(ask).toHaveCSS('outline-color', 'rgb(222, 244, 140)')

  const signed = page.getByRole('region', { name: 'Signed commitments' })
  const source = page.locator('aside')
  await expect(signed).toContainText('25 Nov 2026')
  await expect(signed).toContainText('20 Nov 2026')
  await page.getByRole('button', { name: 'What did we promise?' }).click()
  await expect(page.locator('#answer')).toContainText('25 November 2026')
  await page.getByRole('button', { name: 'Open citation for Migrate depot accounts' }).click()
  await expect(source.getByRole('heading', { name: 'Onboarding addendum' })).toBeVisible()
  await expect(source.locator('blockquote')).toContainText('Rivet Systems will migrate')

  const staleTab = await page.context().newPage()
  await staleTab.goto('/')
  await expect(staleTab.getByRole('region', { name: 'Signed commitments' })).toContainText('25 Nov 2026')

  await page.getByRole('button', { name: 'Import signed amendment' }).click()
  await expect(signed).toContainText('2 Dec 2026')
  await staleTab.getByRole('button', { name: 'What changed?' }).click()
  await expect(staleTab.locator('#answer')).toContainText('2 December 2026')
  await expect(staleTab.getByRole('region', { name: 'Signed commitments' })).toContainText('2 Dec 2026')
  await expect(staleTab.locator('#answer').getByText('Signed amendment', { exact: true })).toBeVisible()
  await staleTab.close()
  await expect(signed).toContainText('2 Dec 2026')
  await expect(signed).toContainText('20 Nov 2026')
  await page.getByRole('textbox', { name: 'Ask about the agreement' }).fill('What changed?')
  await ask.click()
  await expect(page.locator('#answer')).toContainText('from 25 November 2026 to 2 December 2026')
  await page.getByRole('button', { name: 'Open citation for Migrate depot accounts', exact: true }).first().click()
  await expect(source.getByRole('heading', { name: 'Schedule amendment' })).toBeVisible()
  await expect(source.locator('blockquote')).toContainText('All other commitments')
  const impact = page.getByRole('region', { name: 'Amendment impact' })
  await expect(impact).toContainText('25 Nov 2026')
  await expect(impact).toContainText('2 Dec 2026')
  await impact.getByRole('button', { name: 'Open prior source' }).click()
  await expect(source.locator('blockquote')).toContainText('Rivet Systems will migrate')
  await impact.getByRole('button', { name: 'Open signed amendment' }).click()
  await expect(source.locator('blockquote')).toContainText('All other commitments')
  await capture(page, 'desktop')

  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: 'Opening record', exact: true }).click()
  await expect(signed).toContainText('25 Nov 2026')
  await expect(signed).not.toContainText('2 Dec 2026')
  await page.getByRole('button', { name: 'What’s open?' }).click()
  await expect(page.locator('#answer')).toContainText('still a task')
  const handoffCitation = page.locator('#answer').getByRole('button', { name: /Ask about a Saturday cutover/ })
  await expect(handoffCitation).toHaveCount(1)
  await handoffCitation.click()
  await expect(source.locator('blockquote')).toContainText('No Saturday cutover date was agreed.')
  await expect(page.locator('#source-detail')).toBeFocused()
  await expect(page.getByRole('button', { name: 'Import signed amendment' })).toBeDisabled()
  const fits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
  expect(fits).toBe(true)
  expect(errors).toEqual([])
  await capture(page, 'mobile')

  await page.getByRole('textbox', { name: 'Ask about the agreement' }).fill('Can we invoice this workspace?')
  await ask.click()
  await expect(page.locator('#answer')).toContainText('Would you like to know what we actually promised?')
  await expect(page.locator('#answer .citation')).toHaveCount(0)
})

test('keeps a failed question and recovery beside the mobile composer', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await page.route('**/api/commitments/ask', (route) => route.fulfill({
    status: 503,
    json: { detail: 'Answers are temporarily unavailable. Try again.' },
  }))

  const askPanel = page.getByRole('region', { name: 'Ask about this case' })
  const question = askPanel.getByRole('textbox', { name: 'Ask about the agreement' })
  const ask = askPanel.getByRole('button', { name: 'Ask', exact: true })
  await question.fill('What did we promise?')
  await ask.click()
  await expect(askPanel.getByRole('alert')).toHaveText('Answers are temporarily unavailable. Try again.')
  await expect(askPanel.getByRole('alert')).toBeInViewport()
  await expect(question).toHaveValue('What did we promise?')
  await expect(ask).toBeEnabled()

  await page.unroute('**/api/commitments/ask')
  await ask.click()
  await expect(askPanel.getByRole('alert')).toBeHidden()
  await expect(askPanel.locator('#answer')).toContainText('Rivet Systems')
  await expect(question).toHaveValue('')
})

test('keeps a new draft through a delayed answer and a suggested question', async ({ page }) => {
  await page.goto('/')
  const question = page.getByRole('textbox', { name: 'Ask about the agreement' })
  const ask = page.getByRole('button', { name: 'Ask', exact: true })
  let releaseResponse
  const holdResponse = new Promise((resolve) => { releaseResponse = resolve })
  await page.route('**/api/commitments/ask', async (route) => {
    const response = await route.fetch()
    await holdResponse
    await route.fulfill({ response })
  })

  await question.fill('What did we promise?')
  await ask.click()
  await expect(page.locator('#answer')).toContainText('Looking up the agreement')
  await question.fill('What changed?')
  releaseResponse()
  await expect(page.locator('#answer')).toContainText('Rivet Systems promised')
  await expect(question).toHaveValue('What changed?')

  await page.getByRole('button', { name: 'What’s open?' }).click()
  await expect(page.locator('#answer')).toContainText('Maya promised')
  await expect(question).toHaveValue('What changed?')
  await ask.click()
  await expect(page.locator('#answer .answer-question')).toHaveText('What changed?')
  await expect(question).toHaveValue('')
})

async function capture(page, name) {
  const directory = process.env.COMMITMENTS_ARTIFACTS
  if (directory) {
    mkdirSync(directory, { recursive: true })
    await page.evaluate(async () => {
      window.scrollTo(0, 0)
      await document.fonts.ready
    })
    await page.screenshot({ path: join(directory, name + '.png'), fullPage: true })
  }
}
