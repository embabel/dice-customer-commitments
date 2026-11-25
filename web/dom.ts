export function required<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id)
  if (!node) throw new Error('Missing workbench element: ' + id)
  return node as T
}

export function element<K extends keyof HTMLElementTagNameMap>(tag: K, className = '', content?: string) {
  const node = document.createElement(tag)
  if (className) node.className = className
  if (content != null) node.textContent = content
  return node
}

export function button(className: string, label: string | undefined, click: () => void) {
  const node = element('button', className, label)
  node.type = 'button'
  node.addEventListener('click', click)
  return node
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'The request could not be completed. Try again.'
}

type RevisionSide = { commitment: string; source: HTMLButtonElement }

export function revisionCard(before: RevisionSide, after: RevisionSide) {
  const comparison = element('article', 'revision-card')
  const side = (label: string, entry: RevisionSide) => {
    const panel = element('div', 'revision-side')
    panel.append(element('p', 'revision-label', label), element('p', 'revision-text', entry.commitment), entry.source)
    return panel
  }
  const arrow = element('span', 'revision-arrow', '→')
  arrow.setAttribute('aria-hidden', 'true')
  comparison.append(side('Before amendment', before), arrow, side('After amendment', after))
  return comparison
}
