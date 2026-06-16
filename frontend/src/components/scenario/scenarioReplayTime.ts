const KST_OFFSET_MS = 9 * 60 * 60 * 1000

function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

function formatKstInputFromShiftedUtc(date: Date): string {
  return [
    date.getUTCFullYear(),
    pad2(date.getUTCMonth() + 1),
    pad2(date.getUTCDate()),
  ].join('-') + `T${pad2(date.getUTCHours())}:${pad2(date.getUTCMinutes())}`
}

export function isoToKstInput(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    throw new Error('시각 형식이 올바르지 않습니다')
  }
  return formatKstInputFromShiftedUtc(new Date(date.getTime() + KST_OFFSET_MS))
}

export function kstInputToIso(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value)
  if (!match) throw new Error('시각 형식이 올바르지 않습니다')

  const [, yearText, monthText, dayText, hourText, minuteText] = match
  const year = Number(yearText)
  const month = Number(monthText)
  const day = Number(dayText)
  const hour = Number(hourText)
  const minute = Number(minuteText)

  const instant = new Date(Date.UTC(year, month - 1, day, hour - 9, minute, 0, 0))
  if (Number.isNaN(instant.getTime()) || isoToKstInput(instant.toISOString()) !== value) {
    throw new Error('시각 형식이 올바르지 않습니다')
  }
  return instant.toISOString()
}

export function defaultKstInput(
  daysAgo: number,
  hour: number,
  minute: number,
  now: Date = new Date(),
): string {
  const shifted = new Date(now.getTime() + KST_OFFSET_MS)
  shifted.setUTCDate(shifted.getUTCDate() - daysAgo)
  shifted.setUTCHours(hour, minute, 0, 0)
  return formatKstInputFromShiftedUtc(shifted)
}
