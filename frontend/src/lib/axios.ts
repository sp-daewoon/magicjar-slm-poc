/**
 * axios 인스턴스 설정 + 401 ONBOARDING_REQUIRED interceptor
 *
 * - baseURL: /api (Vite dev proxy → localhost:8080)
 * - 401 + code:'ONBOARDING_REQUIRED' 수신 시 /setup 자동 라우팅
 */
import axios from 'axios'

export const apiClient = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 10000,
})

/**
 * 401 응답 interceptor
 * backend OnboardingGuardFilter가 셋업 미완 시 반환하는 401 캐치 → /setup으로 라우팅
 */
apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      const data = error.response.data as { code?: string }
      if (data?.code === 'ONBOARDING_REQUIRED') {
        // 현재 경로가 /setup이 아닌 경우에만 이동
        if (window.location.pathname !== '/setup') {
          window.location.href = '/setup'
        }
      }
    }
    return Promise.reject(error)
  },
)

export default apiClient
