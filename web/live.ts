import { HttpAgent } from '@ag-ui/client'
import { button as addButton, element, errorMessage, required, revisionCard } from './dom'
import type { Claim, Intake, ReviewDecision, ReviewReceipt, Snapshot, Source, Turn } from './live-model'

const api = '/api/live/commitments'
const ui = {
  fixtureLink: required('fixture-link'),
  agreementRecord: required<HTMLSelectElement>('agreement-record'),
  recordTime: required('record-time'),
  recordError: required('record-error'),
  recordErrorText: required('record-error-text'),
  retryRecord: required<HTMLButtonElement>('retry-record'),
  signedCount: required('signed-count'),
  signedClaims: required('signed-claims'),
  otherCount: required('other-count'),
  contextClaims: required('context-claims'),
  sourceCount: required('source-count'),
  evidenceList: required('evidence-list'),
  evidenceDetail: required('evidence-detail'),
  sourceForm: required<HTMLFormElement>('source-form'),
  sourceFields: required<HTMLFieldSetElement>('source-fields'),
  sourceAvailability: required('source-availability'),
  sourceTitle: required<HTMLInputElement>('source-title'),
  sourceKind: required<HTMLSelectElement>('source-kind'),
  signedAttestation: required('signed-attestation'),
  signedAttested: required<HTMLInputElement>('signed-attested'),
  sourceText: required<HTMLTextAreaElement>('source-text'),
  targetField: required('target-field'),
  replacementTarget: required<HTMLSelectElement>('replacement-target'),
  reviewSource: required<HTMLButtonElement>('review-source'),
  targetHelp: required('target-help'),
  sourceStatus: required('source-status'),
  sourceError: required('source-error'),
  intakeResult: required('intake-result'),
  conversation: required('conversation'),
  chatEmpty: required('chat-empty'),
  startingQuestions: required('starting-questions'),
  chatProgress: required('chat-progress'),
  chatForm: required<HTMLFormElement>('chat-form'),
  question: required<HTMLInputElement>('question'),
  sendQuestion: required<HTMLButtonElement>('send-question'),
  stopRun: required<HTMLButtonElement>('stop-run'),
  chatError: required('chat-error'),
  newConversation: required<HTMLButtonElement>('new-conversation'),
  amendmentImpact: required('amendment-impact'),
  amendmentCount: required('amendment-count'),
  signedTotal: required('signed-total'),
  reviewTotal: required('review-total'),
  sourceTotal: required('source-total'),
}
const displayDate = new Intl.DateTimeFormat('en-GB', {
  dateStyle: 'medium', timeStyle: 'medium', timeZone: 'UTC',
})
const signedKinds = new Set(['SIGNED_AGREEMENT', 'SIGNED_AMENDMENT'])
const signedOutcomes = new Set(['AGREED', 'REVISED'])
const names: Record<string, string> = {
  SIGNED_AGREEMENT: 'Signed agreement', SIGNED_AMENDMENT: 'Signed amendment',
  EMAIL: 'Email', CALL: 'Call transcript', ASSISTANT_HANDOFF: 'Assistant handoff',
  COMMITMENT: 'Commitment', AMENDMENT: 'Amendment', REQUEST: 'Request', TARGET: 'Target',
  REMINDER: 'Reminder', NEEDS_REVIEW: 'Needs review', AGREED: 'Agreed', REVISED: 'Revised',
  RECORDED: 'Recorded', REVIEW_NEEDED: 'Review needed', SUPERSEDED: 'Superseded',
}
const checkpoints = new Map<string, string>()
let snapshot: Snapshot | undefined
let selectedAt = ''
let retryAt = ''
let selectedSource: string | undefined
let activity: 'record' | 'intake' | 'chat' | undefined
let cancelChat: (() => void) | undefined
let threadId = crypto.randomUUID()
let messages: Turn[] = []

function option(label: string, value: string) {
  const node = element('option', '', label)
  node.value = value
  return node
}

function showMessage(node: HTMLElement, message: string) {
  node.textContent = message
  node.hidden = !message
}

function timeLabel(value: string) {
  const time = new Date(value)
  return Number.isNaN(time.valueOf()) ? value : displayDate.format(time) + ' UTC'
}

function activeSigned(claim: Claim) {
  return claim.status === 'ACTIVE' && signedOutcomes.has(claim.outcome)
}

function setActivity(next?: typeof activity) {
  activity = next
  const busy = Boolean(next)
  const ready = Boolean(snapshot)
  ui.agreementRecord.disabled = busy || !ready
  ui.sourceFields.disabled = busy || !ready || Boolean(snapshot?.historical)
  ui.question.disabled = !ready
  ui.sendQuestion.disabled = busy || !ready
  ui.stopRun.hidden = next !== 'chat'
  ui.newConversation.disabled = busy
  ui.retryRecord.disabled = busy
  for (const suggestion of ui.startingQuestions.querySelectorAll('button')) {
    suggestion.disabled = busy || !ready
  }
  const replay = ui.evidenceDetail.querySelector<HTMLButtonElement>('.replay-source')
  if (replay) replay.disabled = busy
  updateSourceFields()
}

function updateSourceFields() {
  const kind = ui.sourceKind.value
  const amendment = kind === 'SIGNED_AMENDMENT'
  const signed = signedKinds.has(kind)
  ui.targetField.hidden = !amendment
  ui.replacementTarget.required = amendment
  ui.signedAttestation.hidden = !signed
  ui.signedAttested.required = signed
  const targetsMissing = amendment && ui.replacementTarget.options.length < 2
  ui.reviewSource.disabled = targetsMissing
  ui.targetHelp.textContent = targetsMissing
    ? 'Add a signed agreement before recording an amendment.'
    : 'Choose the active signed commitment this amendment revises.'
}

function sourcesFor(claims: Claim[]) {
  return [...new Map(claims.map(({ source }) => [source.id, source])).values()]
}

function reviewedSources(view: Snapshot) {
  const sources = new Map(sourcesFor(view.claims).map((source) => [source.id, source]))
  for (const { source } of view.receipts ?? []) sources.set(source.id, source)
  return [...sources.values()].sort((first, second) =>
    sortTimeKey(first.receivedAt).localeCompare(sortTimeKey(second.receivedAt)))
}

function sourceReview(receipt: ReviewReceipt, view: Snapshot) {
  const { source, decisions } = receipt
  const review = element('section', 'source-review')
  review.setAttribute('aria-label', 'Source review')
  review.append(element('h4', '', 'Source review'))
  if (source.signedAttested) {
    review.append(element('p', 'review-meta', 'Operator attested to reviewing the signed document.'))
  }
  if (source.selectedClaimId) {
    const selected = view.claims.find(({ id }) => id === source.selectedClaimId)
    if (selected) review.append(element('p', 'review-meta', 'Selected amendment target · ' + selected.text))
  }
  for (const decision of decisions) {
    const row = element('article', 'receipt-decision')
    row.append(decisionLabels(decision), element('h5', 'claim-title', decision.text))
    if (decision.reason) row.append(element('p', 'review-meta', decision.reason))
    if (decision.provider) row.append(element('p', 'review-meta', 'Decision model · ' + decision.provider))
    review.append(row)
  }
  if (!decisions.length) review.append(element('p', 'review-meta', 'No claims recorded for this source.'))
  return review
}

function showEvidence(source: Source | undefined, moveFocus = false, view = snapshot) {
  selectedSource = source?.id
  for (const button of ui.evidenceList.querySelectorAll('button')) {
    button.setAttribute('aria-pressed', String(button.dataset.sourceId === selectedSource))
  }
  if (!source) {
    ui.evidenceDetail.replaceChildren(element('p', 'live-empty', 'No source passages in this record.'))
    return
  }
  const metadata = [names[source.kind] ?? source.kind, timeLabel(source.receivedAt)].join(' · ')
  ui.evidenceDetail.replaceChildren(
    element('h3', '', source.title),
    element('p', 'reference', metadata),
    element('blockquote', '', source.text),
  )
  const receipt = view?.receipts.find(({ source: received }) => received.id === source.id)
  if (receipt && view) {
    if (!view.historical) {
      const replay = addButton('quiet-button replay-source', 'Open record at this source', () => loadRecord(source.receivedAt))
      replay.disabled = Boolean(activity)
      ui.evidenceDetail.append(replay)
    }
    ui.evidenceDetail.append(sourceReview(receipt, view))
  }
  ui.evidenceDetail.scrollTop = 0
  if (moveFocus) {
    ui.evidenceDetail.focus({ preventScroll: true })
    ui.evidenceDetail.scrollIntoView({ block: 'nearest' })
  }
}

function sourceButton(className: string, source: Source, label = source.title, view = snapshot) {
  return addButton(className, label, () => showEvidence(source, true, view))
}

function decisionLabels({ classification, outcome, status }: Pick<ReviewDecision, 'classification' | 'outcome'> & { status?: string }) {
  const labels = element('div', 'claim-labels')
  const outcomeClass = signedOutcomes.has(outcome) ? 'signed-type'
    : outcome === 'REVIEW_NEEDED' ? 'review-type' : 'recorded-type'
  labels.append(
    element('span', 'type', 'Model · ' + (names[classification] ?? classification)),
    element('span', 'type ' + outcomeClass, 'Policy · ' + (names[outcome] ?? outcome)),
  )
  if (status === 'SUPERSEDED') labels.append(element('span', 'type', names.SUPERSEDED))
  return labels
}

function claimRow(claim: Claim) {
  const node = element('article', 'live-claim')
  const details = element('details', 'decision-detail')
  details.append(element('summary', '', 'Decision details'))
  if (claim.reason) details.append(element('p', '', claim.reason))
  if (claim.decisionProvider) details.append(element('p', '', 'Decision model · ' + claim.decisionProvider))
  if (claim.replacesClaimId) {
    const previous = snapshot?.claims.find(({ id }) => id === claim.replacesClaimId)
    if (previous) {
      details.append(element('p', '', 'Replaces: ' + previous.text))
      details.append(sourceButton('claim-source', previous.source, 'Open prior source'))
    }
  }
  node.append(
    decisionLabels(claim),
    element('h3', 'claim-title', claim.text),
    sourceButton('claim-source', claim.source),
    details,
  )
  return node
}

function claimList(container: HTMLElement, claims: Claim[], emptyText: string) {
  container.replaceChildren(...(claims.length ? claims.map(claimRow) : [element('p', 'live-empty', emptyText)]))
}

function sortTimeKey(time: string) {
  // Compare the full UTC instant. Date.parse would discard precision beyond milliseconds.
  return time.replace(/(?:\.(\d+))?Z$/, (_, fraction = '') => '.' + fraction.padEnd(9, '0') + 'Z')
}

function renderCheckpoints(view: Snapshot, sources: Source[]) {
  if (view.baseline) checkpoints.set(view.baseline, 'Before first source')
  if (!view.historical && view.asOf && !checkpoints.has(view.asOf)) {
    checkpoints.set(view.asOf, 'Recorded checkpoint')
  }
  for (const source of sources) {
    if (source.receivedAt && (!checkpoints.has(source.receivedAt) || checkpoints.get(source.receivedAt) === 'Recorded checkpoint')) {
      checkpoints.set(source.receivedAt, source.title)
    }
  }
  const historicalOptions = [...checkpoints].sort(([left], [right]) => sortTimeKey(right).localeCompare(sortTimeKey(left)))
  ui.agreementRecord.replaceChildren(option('Current record', ''), ...historicalOptions.map(([at, title]) =>
    option(title + ' · ' + timeLabel(at), at)))
  if (selectedAt && !checkpoints.has(selectedAt)) {
    ui.agreementRecord.append(option('Historical record · ' + timeLabel(selectedAt), selectedAt))
  }
  ui.agreementRecord.value = selectedAt
}

function renderSnapshot(next: Snapshot) {
  snapshot = next
  const { claims, historical, asOf } = next
  const signed = claims.filter(activeSigned)
  const other = claims.filter((claim) => !activeSigned(claim))
  const sources = reviewedSources(next)
  renderCheckpoints(next, sources)
  ui.recordTime.textContent = (historical ? 'As recorded ' : 'Recorded through ') + timeLabel(asOf)
  ui.recordTime.toggleAttribute('data-historical', historical)
  ui.signedCount.textContent = signed.length ? signed.length + ' active' : ''
  ui.otherCount.textContent = other.length ? String(other.length) : ''
  ui.sourceCount.textContent = sources.length ? String(sources.length) : ''
  ui.sourceAvailability.textContent = historical ? 'Switch to Current record to add a source.' : ''

  claimList(ui.signedClaims, signed, historical
    ? 'No signed commitments were recorded at this point.'
    : 'No signed commitments yet. Add a signed agreement to begin.')
  claimList(ui.contextClaims, other, 'Requests, estimates, reminders and revised commitments appear here.')
  ui.signedTotal.textContent = String(signed.length)
  ui.reviewTotal.textContent = String(other.filter(({ outcome, status }) => outcome === 'REVIEW_NEEDED' && status === 'ACTIVE').length)
  ui.sourceTotal.textContent = String(sources.length)
  renderAmendments(next)
  ui.evidenceList.replaceChildren(...sources.map((source) => {
    const button = addButton('source-tab', undefined, () => showEvidence(source))
    button.dataset.sourceId = source.id
    button.append(
      element('span', 'source-tab-name', source.title),
      element('span', 'source-tab-date', names[source.kind] ?? source.kind),
    )
    return button
  }))
  showEvidence(sources.find(({ id }) => id === selectedSource) ?? sources.at(-1))

  const previousTarget = ui.replacementTarget.value
  ui.replacementTarget.replaceChildren(option('Select a signed commitment', ''), ...signed.map((claim) =>
    option(claim.text, claim.id)))
  if (signed.some(({ id }) => id === previousTarget)) ui.replacementTarget.value = previousTarget
  updateSourceFields()
}

function renderAmendments(view: Snapshot) {
  const byId = new Map(view.claims.map((claim) => [claim.id, claim]))
  const comparisons = view.claims.flatMap((claim) => {
    if (claim.outcome !== 'REVISED' || !claim.replacesClaimId) return []
    const before = byId.get(claim.replacesClaimId)
    if (!before) return []
    return [revisionCard(
      { commitment: before.text, source: sourceButton('claim-source', before.source, 'Open prior source', view) },
      { commitment: claim.text, source: sourceButton('claim-source', claim.source, 'Open signed amendment', view) },
    )]
  })
  ui.amendmentCount.textContent = comparisons.length ? comparisons.length + ' recorded' : ''
  ui.amendmentImpact.replaceChildren(...(comparisons.length ? comparisons
    : [element('p', 'live-empty', 'No signed amendments in this record.')]))
}

async function requestError(response: Response) {
  const problem = await response.json().catch(() => null)
  return Object.assign(new Error(problem?.detail || 'The request could not be completed. Try again.'), { status: response.status })
}

async function request<T>(path = '', options?: RequestInit): Promise<T> {
  const response = await fetch(api + path, options)
  if (!response.ok) throw await requestError(response)
  return response.json()
}

function updateChatControls() {
  const empty = messages.length === 0
  ui.chatEmpty.hidden = !empty
  ui.newConversation.hidden = empty
  ui.startingQuestions.hidden = !empty
}

function resetConversation() {
  threadId = crypto.randomUUID()
  messages = []
  ui.conversation.replaceChildren()
  ui.chatProgress.textContent = ''
  ui.chatError.textContent = ''
  updateChatControls()
}

async function loadRecord(at: string) {
  if (activity) return
  retryAt = at
  ui.fixtureLink.hidden = true
  showMessage(ui.recordErrorText, '')
  ui.recordError.hidden = true
  setActivity('record')
  ui.recordTime.textContent = 'Loading agreement…'
  try {
    const next = await request<Snapshot>(at ? '?at=' + encodeURIComponent(at) : '')
    if (selectedAt !== at) {
      resetConversation()
      ui.intakeResult.replaceChildren()
      ui.sourceStatus.textContent = ''
      showMessage(ui.sourceError, '')
    }
    selectedAt = at
    renderSnapshot(next)
  } catch (error) {
    ui.agreementRecord.value = selectedAt
    ui.recordTime.textContent = snapshot ? 'Recorded through ' + timeLabel(snapshot.asOf) : 'Agreement unavailable'
    const liveUnavailable = error instanceof Error && 'status' in error && error.status === 404
    ui.fixtureLink.hidden = !liveUnavailable
    showMessage(ui.recordErrorText, liveUnavailable
      ? 'Start the app with the live profile or open the recorded walkthrough.'
      : errorMessage(error))
    ui.recordError.hidden = false
    if (!snapshot) {
      claimList(ui.signedClaims, [], 'Agreement data is unavailable.')
      claimList(ui.contextClaims, [], 'Recorded claims are unavailable.')
    }
  } finally {
    setActivity()
  }
}

async function submitSource(event: SubmitEvent) {
  event.preventDefault()
  if (activity || !snapshot || snapshot.historical) return
  const title = ui.sourceTitle.value.trim()
  const text = ui.sourceText.value
  const kind = ui.sourceKind.value
  const upload = {
    title, kind, text, signedAttested: signedKinds.has(kind) && ui.signedAttested.checked,
    replacesClaimId: kind === 'SIGNED_AMENDMENT' ? ui.replacementTarget.value : undefined,
  }
  if (!title || !text.trim()) {
    showMessage(ui.sourceError, 'Enter a source title and the original text.')
    return
  }
  if (title.length > ui.sourceTitle.maxLength || text.length > ui.sourceText.maxLength) {
    showMessage(ui.sourceError, 'Use a shorter title or passage.')
    return
  }
  showMessage(ui.sourceError, '')
  ui.sourceStatus.textContent = 'Extracting claims and checking what can change…'
  ui.intakeResult.replaceChildren()
  setActivity('intake')
  try {
    const result = await request<Intake>('/sources', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(upload),
    })
    selectedSource = result.claims[0]?.source.id ?? selectedSource
    renderSnapshot(result.snapshot)
    ui.sourceForm.reset()
    ui.sourceStatus.textContent = result.claims.length
      ? 'Review complete. ' + result.claims.length + (result.claims.length === 1 ? ' claim below.' : ' claims below.')
      : 'Review complete. No claims were recorded for this source.'
    ui.intakeResult.replaceChildren(...result.claims.map(claimRow))
  } catch (error) {
    ui.sourceStatus.textContent = ''
    showMessage(ui.sourceError, errorMessage(error))
  } finally {
    setActivity()
  }
}

function recentTurns() {
  const turns: Turn[] = []
  let remaining = 42000
  for (let index = messages.length - 2; index >= 0 && turns.length < 12; index -= 2) {
    const question = messages[index]
    const answer = { ...messages[index + 1], content: messages[index + 1].content.slice(0, 8000) }
    remaining -= question.content.length + answer.content.length
    if (remaining < 0) return turns
    turns.unshift(question, answer)
  }
  return turns
}

function messageNode(role: Turn['role'], text: string) {
  const node = element('li', 'chat-message ' + role)
  const body = element('p', 'chat-content', text)
  node.append(element('p', 'chat-speaker', role === 'assistant' ? 'Assistant' : 'You'), body)
  return { node, body }
}

function scrollConversation() {
  ui.conversation.scrollTop = ui.conversation.scrollHeight
}

function renderCitations(message: HTMLElement, ids: string[], view: Snapshot) {
  const sources = sourcesFor(ids.flatMap((id) => view.claims.find((claim) => claim.id === id) ?? []))
  if (!sources.length) return
  const citations = element('div', 'citations')
  citations.setAttribute('aria-label', 'Sources cited')
  citations.append(...sources.map((source) => sourceButton('citation', source, source.title, view)))
  message.append(citations)
}

async function ask(question: string, clearDraft = false) {
  const content = question.trim()
  if (activity || !snapshot || !content) return
  const user: Turn = { id: crypto.randomUUID(), role: 'user', content }
  const run = new HttpAgent({
    url: api + '/agent',
    threadId,
    initialState: { at: selectedAt || null },
    initialMessages: [...recentTurns(), user],
    fetch: async (url, options) => {
      const response = await fetch(url, options)
      if (!response.ok) throw await requestError(response)
      return response
    },
  })
  let stopped = false
  cancelChat = () => {
    stopped = true
    run.abortRun()
  }
  let currentView: Snapshot | undefined
  let assistant: ReturnType<typeof messageNode> | undefined
  let reply: Turn | undefined
  let completed = false
  let failure: string | undefined
  let citationIds: string[] = []
  const { node: questionNode } = messageNode('user', content)
  ui.conversation.append(questionNode)
  scrollConversation()
  ui.chatEmpty.hidden = true
  ui.startingQuestions.hidden = true
  ui.chatError.textContent = ''
  ui.chatProgress.textContent = 'Looking up the agreement…'
  setActivity('chat')
  try {
    await run.runAgent({}, {
      onStateSnapshotEvent: ({ event }) => {
        const next = event.snapshot as Snapshot
        if (!next || !Array.isArray(next.claims) || !Array.isArray(next.receipts)) {
          throw new Error('The assistant sent an invalid agreement record.')
        }
        currentView = next
        renderSnapshot(next)
      },
      onTextMessageStartEvent: ({ event }) => {
        if (reply || event.role !== 'assistant') throw new Error('The assistant sent an invalid message.')
        reply = { id: event.messageId, role: 'assistant', content: '' }
        assistant = messageNode('assistant', '')
        ui.conversation.append(assistant.node)
        scrollConversation()
      },
      onTextMessageContentEvent: ({ event }) => {
        if (!reply || !assistant || event.messageId !== reply.id) {
          throw new Error('The assistant sent invalid message text.')
        }
        reply.content += event.delta
        assistant.body.append(document.createTextNode(event.delta))
        scrollConversation()
      },
      onCustomEvent: ({ event }) => {
        if (event.name === 'commitments.citations' && reply && event.value?.messageId === reply.id
            && Array.isArray(event.value.ids)) {
          citationIds = event.value.ids.filter((id: unknown): id is string => typeof id === 'string')
        }
      },
      onRunFinishedEvent: ({ outcome }) => { completed = outcome === 'success' },
      onRunErrorEvent: ({ event }) => { failure = event.message },
    })
    if (stopped || failure || !completed || !currentView || !reply?.content || !assistant) {
      throw new Error(failure || 'The assistant did not complete its answer. Try again.')
    }
    renderCitations(assistant.node, citationIds, currentView)
    scrollConversation()
    messages.push(user, reply)
    if (clearDraft && ui.question.value === question) ui.question.value = ''
    ui.chatProgress.textContent = ''
  } catch (error) {
    questionNode.remove()
    assistant?.node.remove()
    ui.chatProgress.textContent = stopped ? 'Question stopped.' : ''
    if (!stopped) ui.chatError.textContent = failure || errorMessage(error)
    if (stopped) ui.question.focus()
  } finally {
    cancelChat = undefined
    updateChatControls()
    setActivity()
  }
}

ui.agreementRecord.addEventListener('change', () => loadRecord(ui.agreementRecord.value))
ui.retryRecord.addEventListener('click', () => loadRecord(retryAt))
ui.sourceKind.addEventListener('change', () => {
  ui.replacementTarget.value = ''
  ui.signedAttested.checked = false
  updateSourceFields()
})
ui.sourceForm.addEventListener('submit', submitSource)
ui.chatForm.addEventListener('submit', (event) => {
  event.preventDefault()
  ask(ui.question.value, true)
})
ui.stopRun.addEventListener('click', () => cancelChat?.())
ui.newConversation.addEventListener('click', () => {
  resetConversation()
  ui.question.focus()
})
for (const button of ui.startingQuestions.querySelectorAll('button')) {
  button.addEventListener('click', () => ask(button.dataset.question ?? ''))
}

loadRecord('')
