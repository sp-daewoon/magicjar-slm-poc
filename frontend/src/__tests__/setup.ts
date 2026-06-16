import '@testing-library/jest-dom'

// jsdom은 ResizeObserver 미지원 — LWC v4 차트 컴포넌트 테스트 시 polyfill
if (typeof ResizeObserver === 'undefined') {
  global.ResizeObserver = class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}
