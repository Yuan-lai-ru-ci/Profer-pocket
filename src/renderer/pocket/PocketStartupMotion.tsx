import { useEffect, useMemo, useRef, useState } from 'react'
import { POCKET_BRAND_GEOMETRY as geometry } from './brand-geometry'
import { getStartupMotionPlan, prefersReducedMotion, type StartupMotionPlan } from './startup-motion'

export type StartupMotionReady = () => void

type StartupPhase = 'visible' | 'fading' | 'hidden'

type PocketStartupMotionProps = Readonly<{
  ready?: boolean
}>

function BrandMark(): React.ReactElement {
  return (
    <svg className="pocket-startup-logo" viewBox={geometry.viewBox} role="img" aria-label="Profer">
      <rect x={geometry.outer.x} y={geometry.outer.y} width={geometry.outer.width} height={geometry.outer.height} rx={geometry.outer.radius} fill={geometry.colors.outer} />
      <rect x={geometry.surface.x} y={geometry.surface.y} width={geometry.surface.width} height={geometry.surface.height} rx={geometry.surface.radius} fill={geometry.colors.surface} />
      <rect x={geometry.shadow.x} y={geometry.shadow.y} width={geometry.shadow.width} height={geometry.shadow.height} fill={geometry.colors.shadow} />
      <rect x={geometry.accent.x} y={geometry.accent.y} width={geometry.accent.width} height={geometry.accent.height} fill={geometry.colors.accent} />
    </svg>
  )
}

function getReadyFadeDelay(plan: StartupMotionPlan, readyAt: number, mountedAt: number): number {
  const elapsed = Math.max(0, readyAt - mountedAt)
  return Math.max(0, plan.minVisibleMs - elapsed)
}

export function PocketStartupMotion({ ready = false }: PocketStartupMotionProps): React.ReactElement | null {
  const plan = useMemo(() => getStartupMotionPlan(prefersReducedMotion()), [])
  const [phase, setPhase] = useState<StartupPhase>('visible')
  const mountedAtRef = useRef<number | null>(null)

  useEffect(() => {
    const mountedAt = mountedAtRef.current ?? performance.now()
    mountedAtRef.current = mountedAt
    let fadeTimer: number | undefined
    let hideTimer: number | undefined
    let disposed = false

    const fade = (delayMs: number): void => {
      fadeTimer = window.setTimeout(() => {
        if (disposed) return
        setPhase('fading')
        hideTimer = window.setTimeout(() => {
          if (!disposed) setPhase('hidden')
        }, plan.fadeOutMs)
      }, delayMs)
    }

    if (ready) {
      fade(getReadyFadeDelay(plan, performance.now(), mountedAt))
    } else {
      fade(plan.maxDurationMs - plan.fadeOutMs)
    }

    return () => {
      disposed = true
      if (fadeTimer !== undefined) window.clearTimeout(fadeTimer)
      if (hideTimer !== undefined) window.clearTimeout(hideTimer)
    }
  }, [plan, ready])

  if (phase === 'hidden') return null

  return (
    <div
      className={`pocket-startup-motion pocket-startup-motion-${phase}${plan.reducedMotion ? ' pocket-startup-motion-reduced' : ''}`}
      aria-hidden="true"
      data-ring-count={plan.ringCount}
      style={{
        '--pocket-startup-logo-in-ms': `${plan.logoInMs}ms`,
        '--pocket-startup-rings-ms': `${plan.ringsMs}ms`,
        '--pocket-startup-wordmark-in-ms': `${plan.wordmarkInMs}ms`,
        '--pocket-startup-wordmark-delay-ms': `${plan.wordmarkDelayMs}ms`,
        '--pocket-startup-ring-delay-ms': `${plan.ringDelayMs}ms`,
        '--pocket-startup-fade-out-ms': `${plan.fadeOutMs}ms`,
      } as React.CSSProperties}
    >
      <div className="pocket-startup-rings" aria-hidden="true">
        {Array.from({ length: plan.ringCount }, (_, index) => (
          <span key={index} className="pocket-startup-ring" style={{ '--ring-index': index } as React.CSSProperties} />
        ))}
      </div>
      <div className="pocket-startup-brand">
        <BrandMark />
        <span className="pocket-startup-wordmark">Profer</span>
      </div>
    </div>
  )
}

export function getStartupReadyFadeDelay(plan: StartupMotionPlan, readyAt: number, mountedAt: number): number {
  return getReadyFadeDelay(plan, readyAt, mountedAt)
}
