// 振動強度 (m/s²) → 色 のマッピング。
// 0 (青) → 5 (緑) → 10+ (赤)
export function vibrationColor(v: number): string {
  const clamped = Math.max(0, Math.min(10, v))
  const t = clamped / 10
  // simple HSL: 240 (blue) → 0 (red)
  const hue = 240 - 240 * t
  return `hsl(${hue}, 90%, 50%)`
}

export function formatTime(ms: number): string {
  const d = new Date(ms)
  const pad = (n: number) => n.toString().padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

export function formatDuration(ms: number): string {
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => n.toString().padStart(2, '0')
  if (h > 0) return `${h}:${pad(m)}:${pad(s)}`
  return `${m}:${pad(s)}`
}
