export type StartupMotionPlan = Readonly<{
  reducedMotion: boolean
  logoInMs: number
  ringsMs: number
  wordmarkInMs: number
  wordmarkDelayMs: number
  ringDelayMs: number
  fadeOutMs: number
  minVisibleMs: number
  maxDurationMs: number
  ringCount: number
}>

/**
 * Single source of truth for the WebView startup hand-off. Values intentionally
 * stay short so a slow connection can never leave the first screen covered.
 */
export function getStartupMotionPlan(reducedMotion: boolean): StartupMotionPlan {
  if (reducedMotion) {
    return {
      reducedMotion: true,
      logoInMs: 180,
      ringsMs: 0,
      wordmarkInMs: 120,
      wordmarkDelayMs: 0,
      ringDelayMs: 0,
      fadeOutMs: 160,
      minVisibleMs: 180,
      maxDurationMs: 520,
      ringCount: 0,
    }
  }

  return {
    reducedMotion: false,
    logoInMs: 420,
    ringsMs: 980,
    wordmarkInMs: 560,
    wordmarkDelayMs: 180,
    ringDelayMs: 160,
    fadeOutMs: 360,
    minVisibleMs: 420,
    maxDurationMs: 1_680,
    ringCount: 3,
  }
}

export function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}
