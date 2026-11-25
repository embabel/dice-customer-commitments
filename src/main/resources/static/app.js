const endpoint = '/api/commitments'
const $ = (id) => document.getElementById(id)
const page = {
  amendment: $('amendment'), opening: $('opening'), current: $('current'),
  time: $('as-of'), error: $('error'), signed: $('signed-claims'), other: $('other-claims'),
  edition: $('signed-edition'), sourceCount: $('source-count'), sources: $('source-list'),
  detail: $('source-detail'), scope: $('ask-scope'), form: $('ask-form'),
  question: $('question'), ask: $('ask-submit'), answer: $('answer'), askError: $('ask-error'),
}

const titles = {
  MIGRATION: ['Migrate depot accounts', 'Rivet Systems'],
  SECURITY: ['Deliver the SSO security checklist', 'Tandem Transit'],
  TARGET: ['Early estimate for depot migration', 'Rivet Systems'],
  REQUEST: ['Earlier maintenance window', 'Tandem Transit'],
  SPOKEN: ['Send the migration runbook', 'Maya Chen'],
  TASK: ['Ask about a Saturday cutover', 'Maya Chen'],
}
const type = { TARGET: 'Sales target', REQUEST: 'Customer request', SPOKEN: 'Spoken commitment', TASK: 'Internal task' }
const order = ['sales-call', 'signed-addendum', 'customer-email', 'assistant-handoff', 'signed-amendment']
const day = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
const moment = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'UTC',
})
const html = (value) => String(value ?? '').replace(/[&<>"']/g, (character) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
})[character])
const arrow = '<span class="arrow" aria-hidden="true"></span>'

let view
let busy = true
let previousQuestions = []
let chosen = 'signed-addendum'

async function request(path = '', options) {
  const response = await fetch(endpoint + path, options)
  if (!response.ok) {
    const detail = await response.json().catch(() => null)
    throw new Error(detail?.detail || 'The request did not complete. Try it again.')
  }
  return response.json()
}

function onlySources() {
  const unique = new Map(view.claims.map((claim) => [claim.source.id, claim.source]))
  return order.flatMap((id) => unique.has(id) ? [unique.get(id)] : [])
}

function claimRow(claim, signed) {
  const [title, party] = titles[claim.kind]
  const label = claim.status === 'SUPERSEDED' ? 'Superseded' : type[claim.kind]
  const heading = signed
    ? '<h3 class="claim-title">' + html(title) + '</h3><p class="claim-party">' + html(party) + '</p>'
    : '<span class="type ' + html(claim.kind) + '">' + html(label) + '</span><h3 class="claim-title">' + html(title)
        + '</h3><p class="claim-party">' + html(party) + '</p>'
  const date = claim.valueDate
    ? '<time class="claim-date" datetime="' + html(claim.valueDate) + '">' + html(day.format(new Date(claim.valueDate))) + '</time>'
    : '<span class="claim-party">No date</span>'
  return '<article class="claim"><div>' + heading + '</div>' + date
    + '<button type="button" class="claim-source" data-source="' + html(claim.source.id) + '">'
    + html(claim.source.type) + '</button></article>'
}

function updateControls() {
  page.amendment.disabled = busy || !view || view.historical || view.amended
  page.current.disabled = busy
  page.opening.disabled = busy || !view
  page.ask.disabled = busy || !view
  for (const button of document.querySelectorAll('[data-question]')) button.disabled = busy || !view
}

function updateView() {
  const active = (kind) => view.claims.find((claim) => claim.kind === kind && claim.status === 'ACTIVE')
  const signed = ['MIGRATION', 'SECURITY'].map(active).filter(Boolean)
  const signedIds = new Set(signed.map((claim) => claim.id))
  const extras = ['TARGET', 'REQUEST', 'SPOKEN', 'TASK', 'MIGRATION']
    .flatMap((kind) => view.claims.filter((claim) => claim.kind === kind && !signedIds.has(claim.id)))
  page.signed.innerHTML = signed.map((claim) => claimRow(claim, true)).join('')
  page.other.innerHTML = extras.map((claim) => claimRow(claim, false)).join('')
  page.edition.textContent = view.amended ? 'ADD-02 applied' : 'Opening agreement'
  page.scope.textContent = view.historical ? 'Opening record' : 'Current record'
  page.time.textContent = moment.format(new Date(view.asOf)) + ' UTC'
  page.current.setAttribute('aria-pressed', String(!view.historical))
  page.opening.setAttribute('aria-pressed', String(view.historical))
  page.amendment.innerHTML = view.amended ? 'Amendment imported' : 'Import signed amendment' + arrow
  page.answer.replaceChildren()
  const sources = onlySources()
  if (!sources.some((source) => source.id === chosen)) chosen = 'signed-addendum'
  showEvidence()
}

function showEvidence(animate = false) {
  const sources = onlySources()
  const source = sources.find((candidate) => candidate.id === chosen)
  page.sourceCount.textContent = sources.length + ' passages'
  page.sources.innerHTML = sources.map((candidate) => '<button class="source-tab" type="button" data-source="'
    + html(candidate.id) + '" aria-pressed="' + (candidate.id === chosen) + '">'
    + '<span class="source-tab-name">' + html(candidate.type) + '</span><span class="source-tab-date">'
    + html(candidate.received) + '</span></button>').join('')
  page.detail.innerHTML = '<h3>' + html(source.title) + '</h3><p class="reference">'
    + html(source.reference) + '</p><blockquote>' + html(source.passage) + '</blockquote>'
    + '<p class="support">Exact passage supplied to DICE</p>'
  page.detail.toggleAttribute('data-changed', animate)
}

async function run(operation) {
  if (busy) return
  busy = true
  updateControls()
  page.error.hidden = true
  try {
    await operation()
  } catch (error) {
    page.error.textContent = error.message
    page.error.hidden = false
  } finally {
    busy = false
    updateControls()
  }
}

async function showAnswer(question, { clearDraft = false } = {}) {
  const text = question.trim()
  if (!text) return
  await run(async () => {
    page.askError.textContent = ''
    page.answer.innerHTML = '<p class="answer-body">Looking up the agreement…</p>'
    try {
      const answer = await request('/ask', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, at: view.historical ? view.asOf : null, previousQuestions }),
      })
      // Materialize the same view used by the answer. Another tab may have imported an amendment.
      if (answer.supported) previousQuestions = [...previousQuestions, text].slice(-8)
      view = answer.view
      updateView()
      const citations = distinctCitations(answer.citations)
      page.answer.innerHTML = '<p class="answer-question">' + html(text) + '</p>'
        + '<p class="answer-body">' + html(answer.text) + '</p>'
        + (citations.length === 0 ? '' : '<div class="citations">' + citations.map(({ source, label, subjects }) =>
          '<button type="button" class="citation" data-source="' + html(source.id)
            + '" aria-label="Open citation for ' + html(subjects.join(' and ')) + '">'
            + html(label) + '</button>').join('') + '</div>')
      if (clearDraft && page.question.value === question) page.question.value = ''
    } catch (error) {
      page.answer.replaceChildren()
      page.askError.textContent = error.message
      page.askError.scrollIntoView({ block: 'nearest', behavior: 'instant' })
    }
  })
}

function distinctCitations(ids) {
  const passages = new Map()
  for (const id of ids) {
    const claim = view.claims.find((candidate) => candidate.id === id)
    if (!claim) continue
    const source = claim.source
    const isPrior = claim.kind === 'MIGRATION' && claim.status === 'SUPERSEDED'
    const entry = passages.get(source.id) || { source, label: source.type, subjects: [] }
    if (isPrior) entry.label = 'Prior signed addendum'
    entry.subjects.push(titles[claim.kind][0])
    passages.set(source.id, entry)
  }
  return [...passages.values()]
}

page.current.addEventListener('click', () => run(async () => {
  previousQuestions = []
  view = await request()
  updateView()
}))
page.opening.addEventListener('click', () => run(async () => {
  previousQuestions = []
  view = await request('?at=' + encodeURIComponent(view.baseline))
  chosen = 'signed-addendum'
  updateView()
}))
page.amendment.addEventListener('click', () => run(async () => {
  view = await request('/amendment', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ documentId: 'signed-amendment' }),
  })
  chosen = 'signed-amendment'
  updateView()
}))
page.form.addEventListener('submit', (event) => {
  event.preventDefault()
  showAnswer(page.question.value, { clearDraft: true })
})
document.addEventListener('click', (event) => {
  const suggestion = event.target.closest('[data-question]')
  if (suggestion) {
    showAnswer(suggestion.dataset.question)
    return
  }
  const source = event.target.closest('[data-source]')
  if (!source || !view) return
  chosen = source.dataset.source
  showEvidence(true)
  if (source.classList.contains('source-tab')) {
    page.sources.querySelector('[aria-pressed="true"]')?.focus({ preventScroll: true })
  } else {
    page.detail.focus({ preventScroll: true })
    page.detail.scrollIntoView({ block: 'nearest' })
  }
})

busy = false
run(async () => {
  view = await request()
  updateView()
})
