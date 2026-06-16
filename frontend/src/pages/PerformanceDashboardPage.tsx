/**
 * PerformanceDashboardPage — /performance 호환 라우트
 *
 * 성과 화면은 대시보드 내부 탭으로 통합한다.
 * 기존 북마크/링크는 /?view=performance 로 보낸다.
 */
import { Navigate } from 'react-router-dom'

export function PerformanceDashboardPage() {
  return <Navigate to="/?view=performance" replace />
}
