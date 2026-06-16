import { useEffect, useRef, useState } from 'react'

interface UseInViewportOptions {
  rootMargin?: string
  threshold?: number
}

export function useInViewport<T extends Element>({
  rootMargin = '480px 0px',
  threshold = 0,
}: UseInViewportOptions = {}) {
  const ref = useRef<T | null>(null)
  const [isVisible, setIsVisible] = useState(false)
  const [hasEntered, setHasEntered] = useState(false)

  useEffect(() => {
    const element = ref.current
    if (!element) return

    if (typeof window === 'undefined' || !('IntersectionObserver' in window)) {
      setIsVisible(true)
      setHasEntered(true)
      return
    }

    const observer = new IntersectionObserver(
      ([entry]) => {
        const nextVisible = entry.isIntersecting
        setIsVisible(nextVisible)
        if (nextVisible) {
          setHasEntered(true)
        }
      },
      { rootMargin, threshold },
    )

    observer.observe(element)
    return () => observer.disconnect()
  }, [rootMargin, threshold])

  return { ref, isVisible, hasEntered }
}
