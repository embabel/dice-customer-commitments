import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test } from '@playwright/test'

const addendum = {
  title: 'Migration addendum',
  kind: 'SIGNED_AGREEMENT',
  text: "This addendum is signed by Shoreline Labs and Cedar Transit. Shoreline Labs commits to migrate Cedar Transit's dispatcher accounts by January 15, 2027. Cedar Transit commits to deliver its security checklist by January 12, 2027.",
}
const request = {
  title: 'Earlier cutover request',
  kind: 'EMAIL',
  text: 'Could Shoreline Labs move our dispatcher account migration to January 9, 2027? Please confirm whether you can commit to that date.',
}
const amendment = {
  title: 'Revised migration schedule',
  kind: 'SIGNED_AMENDMENT',
  text: 'This amendment is signed by Shoreline Labs and Cedar Transit. It replaces the January 15, 2027 dispatcher-account migration deadline with January 22, 2027. All other obligations in the addendum are unchanged.',
}

// R1 Intake accepts new source text and an attested signed document.
// E1 The model's claim classification and the app's authority outcome are observable.
// E2 The agent consumes prior turns and cites the evidence in the selected AG-UI snapshot.
// I1 An unsigned request preserves the signed date. An amendment changes only its selected claim.
// L1 Replaying the original receipt restores its signed date and original citation.
test('R1 E1 E2 I1 L1 import, discuss and replay a live agreement', async ({ page }) => {
  await page.goto('/live.html')
  const opening = await submitSource(page, addendum)
  const original = opening.claims.find((claim) => claim.text.includes('2027-01-15'))
  const security = opening.claims.find((claim) => claim.text.includes('2027-01-12'))
  expect(original).toMatchObject({ classification: 'COMMITMENT', outcome: 'AGREED', source: { text: addendum.text } })
  expect(security).toMatchObject({ classification: 'COMMITMENT', outcome: 'AGREED' })

  const proposal = await submitSource(page, request)
  const proposedClaim = proposal.claims.find((claim) => claim.text.includes('2027-01-09'))
  expect(proposedClaim).toMatchObject({ classification: 'REQUEST', outcome: 'RECORDED' })
  expect(proposal.snapshot.claims.find((claim) => claim.id === original.id).status).toBe('ACTIVE')
  await expect(page.locator('#intake-result')).toContainText('Model · Request')
  await expect(page.locator('#intake-result')).toContainText('Policy · Recorded')

  const first = await ask(page, 'Did we agree to cut over on January 9?')
  expect(first.answer).toContain('January 9 was a request. The signed date is January 15, 2027.')
  expect(first.citationIds).toEqual([proposedClaim.id, original.id])
  const followUp = await ask(page, 'What date should I tell them?')
  expect(followUp.request.messages.map((message) => message.role)).toEqual(['user', 'assistant', 'user'])
  expect(followUp.answer).toContain('Tell Cedar Transit January 15, 2027.')
  expect(followUp.citationIds).toEqual([original.id])

  const revision = await submitSource(page, amendment, original.id)
  const revised = revision.claims.find((claim) => claim.text.includes('2027-01-22'))
  expect(revised).toMatchObject({ classification: 'AMENDMENT', outcome: 'REVISED', replacesClaimId: original.id })
  expect(revision.snapshot.claims.find((claim) => claim.id === original.id).status).toBe('SUPERSEDED')
  expect(revision.snapshot.claims.find((claim) => claim.id === security.id).status).toBe('ACTIVE')

  const latest = await ask(page, 'Which deadline stayed in place when the migration moved?')
  expect(latest.answer).toContain('Migration moved to January 22, 2027. The security checklist stayed due January 12, 2027.')
  expect(latest.citationIds).toEqual([revised.id, security.id])
  await capture(page, 'live-current')

  const checkpoint = original.source.receivedAt
  const loadedHistory = page.waitForResponse((response) =>
    matches(response, 'GET', '/api/live/commitments') && new URL(response.url()).searchParams.get('at') === checkpoint)
  await page.getByLabel('Agreement record', { exact: true }).selectOption(checkpoint)
  expect((await loadedHistory).ok()).toBe(true)

  await page.setViewportSize({ width: 390, height: 844 })
  const replay = await ask(page, 'What was the migration date in this record?')
  expect(replay.snapshot).toMatchObject({ historical: true, asOf: checkpoint })
  expect(replay.answer).toContain('The migration date in this record is January 15, 2027.')
  expect(replay.citationIds).toEqual([original.id])

  await page.locator('#conversation').getByRole('button', { name: 'Migration addendum', exact: true }).last().click()
  await expect(page.getByRole('complementary', { name: 'Source evidence' })).toContainText(addendum.text)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await capture(page, 'live-history')
})

async function submitSource(page, source, replacesClaimId) {
  const title = page.getByLabel('Source title', { exact: true })
  await expect(title).toBeEnabled()
  await title.fill(source.title)
  await page.getByLabel('Source type', { exact: true }).selectOption(source.kind)
  await page.getByLabel('Source text', { exact: true }).fill(source.text)
  if (source.kind.startsWith('SIGNED_')) {
    await page.getByRole('checkbox', { name: /reviewed the signed document/i }).check()
  }
  if (replacesClaimId) {
    await page.getByLabel('Commitment to replace', { exact: true }).selectOption(replacesClaimId)
  }

  const posted = page.waitForResponse((response) => matches(response, 'POST', '/api/live/commitments/sources'))
  await page.getByRole('button', { name: 'Review source', exact: true }).click()
  const response = await posted
  expect(response.ok(), await response.text()).toBe(true)
  const result = await response.json()
  await expect(page.locator('#intake-result')).toBeVisible()
  return result
}

async function ask(page, question) {
  const sent = page.waitForResponse((response) => matches(response, 'POST', '/api/live/commitments/agent'))
  await page.getByLabel('Ask about the agreement', { exact: true }).fill(question)
  await page.getByRole('button', { name: 'Ask', exact: true }).click()
  const response = await sent
  expect(response.ok()).toBe(true)
  expect(response.headers()['content-type']).toContain('text/event-stream')

  const events = (await response.text()).split(/\r?\n/)
    .filter((line) => line.startsWith('data:'))
    .map((line) => JSON.parse(line.slice(5).trimStart()))
  expect(events[0].type).toBe('RUN_STARTED')
  expect(events.at(-1).type).toBe('RUN_FINISHED')
  const answer = events.filter((event) => event.type === 'TEXT_MESSAGE_CONTENT').map((event) => event.delta).join('')
  await expect(page.getByText(answer, { exact: true })).toBeVisible()
  return {
    answer,
    request: response.request().postDataJSON(),
    snapshot: events.find((event) => event.type === 'STATE_SNAPSHOT').snapshot,
    citationIds: events.find((event) => event.type === 'CUSTOM' && event.name === 'commitments.citations').value.ids,
  }
}

function matches(response, method, path) {
  return response.request().method() === method && new URL(response.url()).pathname === path
}

async function capture(page, name) {
  const directory = process.env.COMMITMENTS_ARTIFACTS
  if (directory) {
    mkdirSync(directory, { recursive: true })
    await page.evaluate(() => document.fonts.ready)
    await page.screenshot({ path: join(directory, name + '.png'), fullPage: true })
  }
}
