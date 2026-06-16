/**
 * KIS .kis.yaml export / import
 *
 * export: BlockTree + 메타 → .kis.yaml 문자열
 * import: .kis.yaml 문자열 → { entry, exit, universe, tf } 추출
 *         상세 파싱은 backend /api/strategy/validate 위임
 *         frontend는 미리보기용 필드 추출만
 *
 * ADR 0022 §2-4 KIS 호환 형식 LOCK
 * 룰 5: LLM provider import 0건
 */

import { serializeBlockTree } from './serializer'
import type { BlockTree } from './blockTree'

export interface KisYamlMeta {
  name: string
  nameKo?: string
  universe?: string
  tf?: string
  clientName?: string
}

/**
 * BlockTree + 메타정보 → .kis.yaml 문자열
 * backend KisYamlDslParser 호환 형식
 */
export function kisYamlExport(tree: BlockTree, meta: KisYamlMeta): string {
  const { entry, exit } = serializeBlockTree(tree)
  const lines: string[] = [
    `# magicJar Strategy — ${meta.nameKo ?? meta.name}`,
    `# Generated: ${new Date().toISOString()}`,
    '',
    `name: ${meta.name}`,
  ]
  if (meta.nameKo) lines.push(`name_ko: ${meta.nameKo}`)
  if (meta.clientName) lines.push(`client_name: ${meta.clientName}`)
  if (meta.universe) lines.push(`universe: ${meta.universe}`)
  if (meta.tf) lines.push(`tf: ${meta.tf}`)
  lines.push('')

  if (entry) {
    lines.push(`buy_condition: "${entry.replace(/"/g, '\\"')}"`)
  }
  if (exit) {
    lines.push(`sell_condition: "${exit.replace(/"/g, '\\"')}"`)
  }

  return lines.join('\n')
}

export interface KisYamlImportResult {
  entry: string
  exit?: string
  universe?: string
  tf?: string
  name?: string
  nameKo?: string
}

/**
 * .kis.yaml 문자열 → 필드 추출
 *
 * 단순 key: value 파싱 — 따옴표 래핑 제거
 * YAML 완전 파싱은 backend /api/strategy/validate 위임
 *
 * 최대 1MB 제한 (§8 위험 #7 정합)
 */
export function kisYamlImport(yamlText: string): KisYamlImportResult {
  if (yamlText.length > 1024 * 1024) {
    throw new Error('YAML 파일이 너무 큽니다 (최대 1MB)')
  }

  const lines = yamlText.split('\n')
  const result: KisYamlImportResult = { entry: '' }

  for (const raw of lines) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue

    const colonIdx = line.indexOf(':')
    if (colonIdx < 0) continue

    const key = line.slice(0, colonIdx).trim()
    const val = line.slice(colonIdx + 1).trim()

    // 따옴표 제거
    const clean = val.replace(/^["']|["']$/g, '')

    switch (key) {
      case 'buy_condition':
        result.entry = clean
        break
      case 'sell_condition':
        result.exit = clean
        break
      case 'universe':
        result.universe = clean
        break
      case 'tf':
        result.tf = clean
        break
      case 'name':
        result.name = clean
        break
      case 'name_ko':
        result.nameKo = clean
        break
    }
  }

  if (!result.entry) {
    throw new Error('buy_condition 필드가 없습니다. 올바른 .kis.yaml 파일인지 확인하세요')
  }

  return result
}
