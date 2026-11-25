export type Source = {
  id: string
  title: string
  kind: string
  text: string
  receivedAt: string
  signedAttested: boolean
  selectedClaimId: string | null
}

export type ReviewDecision = {
  classification: string
  outcome: string
  text: string
  reason: string | null
  provider: string | null
}

export type Claim = {
  id: string
  text: string
  classification: string
  status: string
  outcome: string
  reason: string | null
  decisionProvider: string | null
  replacesClaimId: string | null
  source: Source
}

export type ReviewReceipt = { source: Source; decisions: ReviewDecision[] }
export type Snapshot = { asOf: string; baseline: string; historical: boolean; claims: Claim[]; receipts: ReviewReceipt[] }
export type Intake = { snapshot: Snapshot; claims: Claim[] }
export type Turn = { id: string; content: string; role: 'user' | 'assistant' }
