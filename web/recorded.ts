import { button, element, errorMessage, required, revisionCard } from './dom'

const endpoint = '/api/commitments'
const page = {
  amendment: required<HTMLButtonElement>('amendment'),
  opening: required<HTMLButtonElement>('opening'),
  current: required<HTMLButtonElement>('current'),
  time: required('as-of'), error: required('error'),
  signed: required('signed-claims'), other: required('other-claims'),
  impact: required('amendment-impact'), edition: required('signed-edition'),
  sourceCount: required('source-count'), sources: required('source-list'),
  detail: required('source-detail'), scope: required('ask-scope'),
  form: required<HTMLFormElement>('ask-form'),
  question: required<HTMLInputElement>('question'),
  ask: required<HTMLButtonElement>('ask-submit'),
  answer: required('answer'), askError: required('ask-error'),
}
const titles = {
  MIGRATION: ['Migrate depot accounts', 'Rivet Systems'],
  SECURITY: ['Deliver the SSO security checklist', 'Tandem Transit'],
  TARGET: ['Early estimate for depot migration', 'Rivet Systems'],
  REQUEST: ['Earlier maintenance window', 'Tandem Transit'],
  SPOKEN: ['Send the migration runbook', 'Maya Chen'],
  TASK: ['Ask about a Saturday cutover', 'Maya Chen'],
}
type Kind = keyof typeof titles
type Passage = { id: string; type: string; title: string; received: string; reference: string; passage: string }
type Claim = { id: string; kind: Kind; status: string; valueDate: string | null; source: Passage }
type View = { asOf: string; baseline: string; historical: boolean; amended: boolean; claims: Claim[] }
type Answer = { supported: boolean; text: string; citations: string[]; view: View }
type Citation = { source: Passage; label: string; subjects: string[] }

const type: Partial<Record<Kind, string>> = {
  TARGET: 'Sales target', REQUEST: 'Customer request', SPOKEN: 'Spoken commitment', TASK: 'Internal task',
}
const order = ['sales-call', 'signed-addendum', 'customer-email', 'assistant-handoff', 'signed-amendment']
const day = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
const moment = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'UTC',
})
const suggestions = document.querySelectorAll<HTMLButtonElement>('[data-question]')

let view: View | undefined
let busy = false
let previousQuestions: string[] = []
let chosen = 'signed-addendum'

async function request<T>(path = '', options?: RequestInit): Promise<T> {
  const response = await fetch(endpoint + path, options)
  if (!response.ok) {
    const detail = await response.json().catch(() => null)
    throw new Error(detail?.detail || 'The request did not complete. Try it again.')
  }
  return response.json()
}

function onlySources(current: View) {
  const unique = new Map(current.claims.map(({ source }) => [source.id, source]))
  return order.flatMap((id) => unique.get(id) ?? [])
}

function claimDate(claim: Claim) {
  return claim.valueDate ? day.format(new Date(claim.valueDate)) : 'No date'
}

function sourceButton(className: string, label: string, source: Passage) {
  return button(className, label, () => selectSource(source.id, true))
}

function claimRow(claim: Claim, signed: boolean) {
  const [title, party] = titles[claim.kind]
  const heading = element('div')
  if (!signed) heading.append(element('span', 'type ' + claim.kind,
    claim.status === 'SUPERSEDED' ? 'Superseded' : type[claim.kind]))
  heading.append(element('h3', 'claim-title', title), element('p', 'claim-party', party))
  const row = element('article', 'claim')
  const date = element('time', 'claim-date', claimDate(claim))
  if (claim.valueDate) date.dateTime = claim.valueDate
  row.append(heading, date, sourceButton('claim-source', claim.source.type, claim.source))
  return row
}

function updateControls() {
  page.amendment.disabled = busy || !view || view.historical || view.amended
  page.current.disabled = busy
  page.opening.disabled = busy || !view
  page.ask.disabled = busy || !view
  suggestions.forEach((entry) => { entry.disabled = busy || !view })
}

function updateView(next: View) {
  view = next
  const active = (kind: Kind) => next.claims.find((claim) => claim.kind === kind && claim.status === 'ACTIVE')
  const signed = [active('MIGRATION'), active('SECURITY')].flatMap((claim) => claim ?? [])
  const signedIds = new Set(signed.map(({ id }) => id))
  const extras: Kind[] = ['TARGET', 'REQUEST', 'SPOKEN', 'TASK', 'MIGRATION']
  page.signed.replaceChildren(...signed.map((claim) => claimRow(claim, true)))
  page.other.replaceChildren(...extras.flatMap((kind) => next.claims
    .filter((claim) => claim.kind === kind && !signedIds.has(claim.id))
    .map((claim) => claimRow(claim, false))))
  page.edition.textContent = next.amended ? 'ADD-02 applied' : 'Opening agreement'
  page.scope.textContent = next.historical ? 'Opening record' : 'Current record'
  page.time.textContent = moment.format(new Date(next.asOf)) + ' UTC'
  page.time.toggleAttribute('data-historical', next.historical)
  page.current.setAttribute('aria-pressed', String(!next.historical))
  page.opening.setAttribute('aria-pressed', String(next.historical))
  page.amendment.textContent = next.amended ? 'Amendment imported' : 'Import signed amendment'
  if (!next.amended) page.amendment.append(element('span', 'arrow'))
  page.answer.replaceChildren()

  const before = next.claims.find((claim) => claim.kind === 'MIGRATION' && claim.status === 'SUPERSEDED')
  const after = active('MIGRATION')
  page.impact.replaceChildren(before && after ? revisionCard(
    { commitment: claimDate(before), source: sourceButton('claim-source', 'Open prior source', before.source) },
    { commitment: claimDate(after), source: sourceButton('claim-source', 'Open signed amendment', after.source) },
  ) : element('p', 'loading', 'No signed amendments in this record.'))

  const sources = onlySources(next)
  if (!sources.some((source) => source.id === chosen)) chosen = 'signed-addendum'
  showEvidence(sources)
}

function selectSource(id: string, focus: boolean) {
  if (!view) return
  chosen = id
  showEvidence(onlySources(view))
  if (focus) {
    page.detail.focus({ preventScroll: true })
    page.detail.scrollIntoView({ block: 'nearest' })
  } else {
    page.sources.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus({ preventScroll: true })
  }
}

function showEvidence(sources: Passage[]) {
  const source = sources.find((candidate) => candidate.id === chosen)
  page.sourceCount.textContent = sources.length + ' passages'
  page.sources.replaceChildren(...sources.map((entry) => {
    const control = button('source-tab', undefined, () => selectSource(entry.id, false))
    control.setAttribute('aria-pressed', String(entry.id === chosen))
    control.append(element('span', 'source-tab-name', entry.type), element('span', 'source-tab-date', entry.received))
    return control
  }))
  page.detail.replaceChildren(...(source ? [
    element('h3', '', source.title),
    element('p', 'reference', source.reference),
    element('blockquote', '', source.passage),
  ] : [element('p', 'loading', 'No source passages in this record.')]))
}

async function run(operation: () => Promise<void>) {
  if (busy) return
  busy = true
  updateControls()
  page.error.hidden = true
  try {
    await operation()
  } catch (error) {
    page.error.textContent = errorMessage(error)
    page.error.hidden = false
  } finally {
    busy = false
    updateControls()
  }
}

async function showAnswer(question: string, clearDraft = false) {
  const text = question.trim()
  const selected = view
  if (!text || !selected) return
  await run(async () => {
    page.askError.textContent = ''
    page.answer.replaceChildren(element('p', 'answer-body', 'Looking up the agreement…'))
    try {
      const answer = await request<Answer>('/ask', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, at: selected.historical ? selected.asOf : null, previousQuestions }),
      })
      if (answer.supported) previousQuestions = [...previousQuestions, text].slice(-8)
      // A second tab can move the current agreement while this tab is open.
      updateView(answer.view)
      page.answer.replaceChildren(element('p', 'answer-question', text), element('p', 'answer-body', answer.text))
      const citations = distinctCitations(answer.citations, answer.view)
      if (citations.length) {
        const list = element('div', 'citations')
        for (const { source, label, subjects } of citations) {
          const citation = sourceButton('citation', label, source)
          citation.setAttribute('aria-label', 'Open citation for ' + subjects.join(' and '))
          list.append(citation)
        }
        page.answer.append(list)
      }
      if (clearDraft && page.question.value === question) page.question.value = ''
    } catch (error) {
      page.answer.replaceChildren()
      page.askError.textContent = errorMessage(error)
      page.askError.scrollIntoView({ block: 'nearest', behavior: 'instant' })
    }
  })
}

function distinctCitations(ids: string[], current: View) {
  const passages = new Map<string, Citation>()
  for (const id of ids) {
    const claim = current.claims.find((candidate) => candidate.id === id)
    if (!claim) continue
    const source = claim.source
    const entry = passages.get(source.id) ?? { source, label: source.type, subjects: [] }
    if (claim.kind === 'MIGRATION' && claim.status === 'SUPERSEDED') entry.label = 'Prior signed addendum'
    entry.subjects.push(titles[claim.kind][0])
    passages.set(source.id, entry)
  }
  return [...passages.values()]
}

page.current.addEventListener('click', () => run(async () => {
  previousQuestions = []
  updateView(await request<View>())
}))
page.opening.addEventListener('click', () => run(async () => {
  if (!view) return
  previousQuestions = []
  const next = await request<View>('?at=' + encodeURIComponent(view.baseline))
  chosen = 'signed-addendum'
  updateView(next)
}))
page.amendment.addEventListener('click', () => run(async () => {
  const next = await request<View>('/amendment', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ documentId: 'signed-amendment' }),
  })
  chosen = 'signed-amendment'
  updateView(next)
}))
page.form.addEventListener('submit', (event) => {
  event.preventDefault()
  void showAnswer(page.question.value, true)
})
suggestions.forEach((entry) => entry.addEventListener('click', () => { void showAnswer(entry.dataset.question ?? '') }))
void run(async () => updateView(await request<View>()))
