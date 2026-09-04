const SELECTOR = '.page-card, .glass-panel, .stat-card'

export function bindGlassLight() {
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)')

  document.addEventListener(
    'pointermove',
    (event) => {
      if (reduceMotion.matches) return
      const card = (event.target as Element | null)?.closest?.(SELECTOR) as HTMLElement | null
      if (!card) return
      const rect = card.getBoundingClientRect()
      card.style.setProperty('--glass-mx', `${event.clientX - rect.left}px`)
      card.style.setProperty('--glass-my', `${event.clientY - rect.top}px`)
    },
    { passive: true },
  )
}
