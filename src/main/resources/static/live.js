const api = '/api/live/commitments'
const ids = [
  'fixture-link', 'agreement-record', 'record-time', 'record-error', 'record-error-text', 'retry-record',
  'signed-count', 'signed-claims', 'other-count', 'context-claims', 'source-count', 'evidence-list',
  'evidence-detail', 'source-form', 'source-fields', 'source-availability', 'source-title', 'source-kind',
  'signed-attestation', 'signed-attested', 'source-text', 'target-field', 'replacement-target',
  'review-source', 'target-help', 'source-status', 'source-error', 'intake-result', 'conversation', 'chat-empty',
  'starting-questions', 'chat-progress', 'chat-form', 'question', 'send-question', 'stop-run',
  'chat-error', 'new-conversation',
]
const ui = Object.fromEntries(ids.map((id) => [
  id.replace(/-([a-z])/g, (_, char) => char.toUpperCase()), document.getElementById(id),
]))
const displayDate = new Intl.DateTimeFormat('en-GB', {
  dateStyle: 'medium', timeStyle: 'medium', timeZone: 'UTC',
})
const signedKinds = new Set(['SIGNED_AGREEMENT', 'SIGNED_AMENDMENT'])
const signedOutcomes = new Set(['AGREED', 'REVISED'])
const names = {
  SIGNED_AGREEMENT: 'Signed agreement', SIGNED_AMENDMENT: 'Signed amendment',
  EMAIL: 'Email', CALL: 'Call transcript', ASSISTANT_HANDOFF: 'Assistant handoff',
  COMMITMENT: 'Commitment', AMENDMENT: 'Amendment', REQUEST: 'Request', TARGET: 'Target',
  REMINDER: 'Reminder', NEEDS_REVIEW: 'Needs review', AGREED: 'Agreed', REVISED: 'Revised',
  RECORDED: 'Recorded', REVIEW_NEEDED: 'Review needed', SUPERSEDED: 'Superseded',
}
const checkpoints = new Map()
let snapshot
let selectedAt = ''
let retryAt = ''
let selectedSource
let activity
let controller
let threadId = crypto.randomUUID()
let messages = []

function element(tag, className, content) {
  const node = document.createElement(tag)
  if (className) node.className = className
  if (content != null) node.textContent = content
  return node
}

function addButton(className, label, click) {
  const button = element('button', className, label)
  button.type = 'button'
  button.addEventListener('click', click)
  return button
}

function option(label, value) {
  const node = element('option', '', label)
  node.value = value
  return node
}

function showMessage(node, message) {
  node.textContent = message
  node.hidden = !message
}

function timeLabel(value) {
  const time = new Date(value)
  return Number.isNaN(time.valueOf()) ? value : displayDate.format(time) + ' UTC'
}

function activeSigned(claim) {
  return claim.status === 'ACTIVE' && signedOutcomes.has(claim.outcome)
}

function setActivity(next) {
  activity = next
  const busy = Boolean(next)
  const ready = Boolean(snapshot)
  ui.agreementRecord.disabled = busy || !ready
  ui.sourceFields.disabled = busy || !ready || snapshot.historical
  ui.question.disabled = !ready
  ui.sendQuestion.disabled = busy || !ready
  ui.stopRun.hidden = next !== 'chat'
  ui.newConversation.disabled = busy
  ui.retryRecord.disabled = busy
  for (const suggestion of ui.startingQuestions.querySelectorAll('button')) {
    suggestion.disabled = busy || !ready
  }
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

function sourcesFor(claims) {
  return [...new Map(claims.map(({ source }) => [source.id, source])).values()]
}

function reviewedSources(view) {
  const sources = new Map(sourcesFor(view.claims).map((source) => [source.id, source]))
  for (const { source } of view.receipts ?? []) sources.set(source.id, source)
  return [...sources.values()].sort((first, second) =>
    sortTimeKey(first.receivedAt).localeCompare(sortTimeKey(second.receivedAt)))
}

function sourceReview(receipt, view) {
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

function showEvidence(source, moveFocus = false, view = snapshot) {
  selectedSource = source?.id
  for (const button of ui.evidenceList.children) {
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
  const receipt = view.receipts?.find(({ source: received }) => received.id === source.id)
  if (receipt) ui.evidenceDetail.append(sourceReview(receipt, view))
  ui.evidenceDetail.scrollTop = 0
  if (moveFocus) {
    ui.evidenceDetail.focus({ preventScroll: true })
    ui.evidenceDetail.scrollIntoView({ block: 'nearest' })
  }
}

function sourceButton(className, source, label = source.title, view = snapshot) {
  return addButton(className, label, () => showEvidence(source, true, view))
}

function decisionLabels({ classification, outcome, status }) {
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

function claimRow(claim) {
  const node = element('article', 'live-claim')
  const details = element('details', 'decision-detail')
  details.append(element('summary', '', 'Decision details'))
  if (claim.reason) details.append(element('p', '', claim.reason))
  if (claim.decisionProvider) details.append(element('p', '', 'Decision model · ' + claim.decisionProvider))
  if (claim.replacesClaimId) {
    const previous = snapshot.claims.find(({ id }) => id === claim.replacesClaimId)
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

function claimList(container, claims, emptyText) {
  container.replaceChildren(...(claims.length ? claims.map(claimRow) : [element('p', 'live-empty', emptyText)]))
}

function sortTimeKey(time) {
  // Compare the full UTC instant. Date.parse would discard precision beyond milliseconds.
  return time.replace(/(?:\.(\d+))?Z$/, (_, fraction = '') => '.' + fraction.padEnd(9, '0') + 'Z')
}

function renderCheckpoints(sources) {
  if (snapshot.baseline) checkpoints.set(snapshot.baseline, 'Before first source')
  if (!snapshot.historical && snapshot.asOf && !checkpoints.has(snapshot.asOf)) {
    checkpoints.set(snapshot.asOf, 'Recorded checkpoint')
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

function renderSnapshot(next) {
  snapshot = next
  const { claims, historical, asOf } = next
  const signed = claims.filter(activeSigned)
  const other = claims.filter((claim) => !activeSigned(claim))
  const sources = reviewedSources(next)
  renderCheckpoints(sources)
  ui.recordTime.textContent = (historical ? 'As recorded ' : 'Recorded through ') + timeLabel(asOf)
  ui.signedCount.textContent = signed.length ? signed.length + ' active' : ''
  ui.otherCount.textContent = other.length ? String(other.length) : ''
  ui.sourceCount.textContent = sources.length ? String(sources.length) : ''
  ui.sourceAvailability.textContent = historical ? 'Switch to Current record to add a source.' : ''

  claimList(ui.signedClaims, signed, historical
    ? 'No signed commitments were recorded at this point.'
    : 'No signed commitments yet. Add a signed agreement to begin.')
  claimList(ui.contextClaims, other, 'Requests, estimates, reminders and revised commitments appear here.')
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

async function requestError(response) {
  const problem = await response.json().catch(() => null)
  const error = new Error(problem?.detail || 'The request could not be completed. Try again.')
  error.status = response.status
  return error
}

async function request(path = '', options) {
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

async function loadRecord(at) {
  if (activity) return
  retryAt = at
  ui.fixtureLink.hidden = true
  showMessage(ui.recordErrorText, '')
  ui.recordError.hidden = true
  setActivity('record')
  ui.recordTime.textContent = 'Loading agreement…'
  try {
    const next = await request(at ? '?at=' + encodeURIComponent(at) : '')
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
    const liveUnavailable = error.status === 404
    ui.fixtureLink.hidden = !liveUnavailable
    showMessage(ui.recordErrorText, liveUnavailable
      ? 'Start the app with the live profile or open the recorded walkthrough.'
      : error.message)
    ui.recordError.hidden = false
    if (!snapshot) {
      claimList(ui.signedClaims, [], 'Agreement data is unavailable.')
      claimList(ui.contextClaims, [], 'Recorded claims are unavailable.')
    }
  } finally {
    setActivity()
  }
}

async function submitSource(event) {
  event.preventDefault()
  if (activity || !snapshot || snapshot.historical) return
  const title = ui.sourceTitle.value.trim()
  const text = ui.sourceText.value
  const kind = ui.sourceKind.value
  const upload = { title, kind, text, signedAttested: signedKinds.has(kind) && ui.signedAttested.checked }
  if (kind === 'SIGNED_AMENDMENT') upload.replacesClaimId = ui.replacementTarget.value
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
    const result = await request('/sources', {
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
    showMessage(ui.sourceError, error.message)
  } finally {
    setActivity()
  }
}

async function* events(body) {
  const reader = body.pipeThrough(new TextDecoderStream()).getReader()
  let pending = ''
  let data = []
  try {
    for (;;) {
      const { value, done } = await reader.read()
      pending += value ?? ''
      // A bare CR at a chunk boundary may be followed by LF. At EOF it stands on its own.
      const lineEnding = done ? /\r\n|\r|\n/ : /\r\n|\r(?!$)|\n/
      let separator
      while ((separator = lineEnding.exec(pending))) {
        const line = pending.slice(0, separator.index)
        pending = pending.slice(separator.index + separator[0].length)
        if (line === '' && data.length) {
          yield JSON.parse(data.join('\n'))
          data = []
        } else if (line.startsWith('data:')) {
          data.push(line.slice(5).replace(/^ /, ''))
        }
      }
      if (done) break
    }
  } finally {
    reader.releaseLock()
  }
}

function recentTurns() {
  const turns = []
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

function messageNode(role, text) {
  const node = element('li', 'chat-message ' + role)
  const body = element('p', 'chat-content', text)
  node.append(element('p', 'chat-speaker', role === 'assistant' ? 'Assistant' : 'You'), body)
  return { node, body }
}

function scrollConversation() {
  ui.conversation.scrollTop = ui.conversation.scrollHeight
}

function renderCitations(message, ids, view) {
  const sources = sourcesFor(ids.map((id) => view.claims.find((claim) => claim.id === id)).filter(Boolean))
  if (!sources.length) return
  const citations = element('div', 'citations')
  citations.setAttribute('aria-label', 'Sources cited')
  citations.append(...sources.map((source) => sourceButton('citation', source, source.title, view)))
  message.append(citations)
}

async function ask(question, clearDraft = false) {
  const content = question.trim()
  if (activity || !snapshot || !content) return
  const user = { id: crypto.randomUUID(), role: 'user', content }
  const runId = crypto.randomUUID()
  const browserAbort = new AbortController()
  controller = browserAbort
  let currentView
  let assistant
  let reply
  let complete = false
  let started = false
  let ended = false
  let citations = []
  const { node: questionNode } = messageNode('user', content)
  ui.conversation.append(questionNode)
  scrollConversation()
  ui.chatEmpty.hidden = true
  ui.startingQuestions.hidden = true
  ui.chatError.textContent = ''
  ui.chatProgress.textContent = 'Looking up the agreement…'
  setActivity('chat')

  try {
    const response = await fetch(api + '/agent', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({
        threadId, runId, state: { at: selectedAt || null },
        messages: [...recentTurns(), user], tools: [], context: [], forwardedProps: {},
      }),
      signal: browserAbort.signal,
    })
    if (!response.ok) throw await requestError(response)
    if (!response.body || !response.headers.get('content-type')?.startsWith('text/event-stream')) {
      throw new Error('The assistant did not send a conversation stream.')
    }
    for await (const event of events(response.body)) {
      if (complete || (!started && event.type !== 'RUN_STARTED')) {
        throw new Error('The assistant sent an unexpected conversation event.')
      }
      switch (event.type) {
        case 'RUN_STARTED':
          if (started || event.runId !== runId || event.threadId !== threadId) {
            throw new Error('The conversation response did not match this request.')
          }
          started = true
          break
        case 'STATE_SNAPSHOT':
          if (currentView || !Array.isArray(event.snapshot?.claims)) {
            throw new Error('The assistant sent an invalid agreement record.')
          }
          currentView = event.snapshot
          renderSnapshot(currentView)
          break
        case 'TEXT_MESSAGE_START':
          if (reply || event.role !== 'assistant' || !event.messageId) {
            throw new Error('The assistant sent an invalid message.')
          }
          reply = { id: event.messageId, role: 'assistant', content: '' }
          assistant = messageNode('assistant', '')
          ui.conversation.append(assistant.node)
          scrollConversation()
          break
        case 'TEXT_MESSAGE_CONTENT':
          if (!reply || reply.id !== event.messageId || ended || typeof event.delta !== 'string' || !event.delta) {
            throw new Error('The assistant sent invalid message text.')
          }
          reply.content += event.delta
          assistant.body.textContent = reply.content
          scrollConversation()
          break
        case 'TEXT_MESSAGE_END':
          if (!reply || reply.id !== event.messageId || ended) {
            throw new Error('The assistant ended an unknown message.')
          }
          ended = true
          break
        case 'CUSTOM':
          if (event.name === 'commitments.citations' && event.value?.messageId === reply?.id) {
            citations = Array.isArray(event.value.ids) ? event.value.ids : []
          }
          break
        case 'RUN_FINISHED':
          complete = event.runId === runId && event.threadId === threadId && ended
          break
        case 'RUN_ERROR':
          throw new Error(event.message || 'The assistant could not answer this question.')
      }
    }
    if (!complete || !currentView || !reply?.content) {
      throw new Error('The assistant did not complete its answer. Try again.')
    }
    renderCitations(assistant.node, citations, currentView)
    scrollConversation()
    messages.push(user, reply)
    if (clearDraft && ui.question.value === question) ui.question.value = ''
    ui.chatProgress.textContent = ''
  } catch (error) {
    questionNode.remove()
    assistant?.node.remove()
    ui.chatProgress.textContent = browserAbort.signal.aborted ? 'Question stopped.' : ''
    if (!browserAbort.signal.aborted) ui.chatError.textContent = error.message
    if (browserAbort.signal.aborted) ui.question.focus()
  } finally {
    browserAbort.abort()
    controller = undefined
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
ui.stopRun.addEventListener('click', () => controller?.abort())
ui.newConversation.addEventListener('click', () => {
  resetConversation()
  ui.question.focus()
})
for (const button of ui.startingQuestions.querySelectorAll('button')) {
  button.addEventListener('click', () => ask(button.dataset.question))
}

loadRecord('')
