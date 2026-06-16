export interface JournalInstrumentDisplay {
  primary: string
  code: string | null
}

export function resolveJournalInstrumentDisplay(
  instrumentCode: string | null,
  instrumentName: string | null,
): JournalInstrumentDisplay | null {
  const code = instrumentCode?.trim()
  if (!code) return null

  const name = instrumentName?.trim()
  if (name && name !== code) {
    return { primary: name, code }
  }

  return { primary: code, code: null }
}
