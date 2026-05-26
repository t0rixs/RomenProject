import { useEffect, useRef, useState } from 'react'
import { api, type HealthResponse } from './api'

type Status = 'idle' | 'receiving' | 'recent' | 'offline'

/**
 * 受信サーバの状態をポーリング表示する小さなインジケータ。
 *  - inProgress > 0           : 受信中（緑点滅）
 *  - 直近 5s 以内に完了        : 完了直後（緑）
 *  - サーバ未到達              : オフライン（赤）
 *  - それ以外                  : 待機中（灰）
 *
 * onUploadFinished: 受信完了を検知したとき呼ばれる（一覧の自動更新用）
 */
export function UploadIndicator(props: { onUploadFinished?: (id: string) => void }) {
  const [health, setHealth] = useState<HealthResponse | null>(null)
  const [error, setError] = useState(false)
  const lastFinishedIdRef = useRef<string | null>(null)

  useEffect(() => {
    let alive = true
    let timer: number | undefined

    const tick = async () => {
      try {
        const h = await api.health()
        if (!alive) return
        setHealth(h)
        setError(false)
        const id = h.upload?.lastFinishedId
        if (id && id !== lastFinishedIdRef.current) {
          if (lastFinishedIdRef.current !== null) {
            props.onUploadFinished?.(id)
          }
          lastFinishedIdRef.current = id
        }
      } catch {
        if (!alive) return
        setError(true)
      } finally {
        if (alive) timer = window.setTimeout(tick, 1500)
      }
    }
    tick()
    return () => {
      alive = false
      if (timer) clearTimeout(timer)
    }
  }, [props])

  const status: Status = (() => {
    if (error) return 'offline'
    if (!health || !health.upload) return 'idle'
    if (health.upload.inProgress > 0) return 'receiving'
    const elapsed = (health.now ?? Date.now()) - health.upload.lastFinishedAt
    if (health.upload.lastFinishedAt > 0 && elapsed < 5000) return 'recent'
    return 'idle'
  })()

  const styles: Record<Status, { bg: string; fg: string; dot: string; label: string }> = {
    receiving: { bg: '#e6f9ec', fg: '#0a7a32', dot: '#22c55e', label: '受信中…' },
    recent:    { bg: '#e6f9ec', fg: '#0a7a32', dot: '#22c55e', label: '受信完了' },
    idle:      { bg: '#f1f5f9', fg: '#475569', dot: '#94a3b8', label: '待機中' },
    offline:   { bg: '#fde8e8', fg: '#991b1b', dot: '#ef4444', label: 'サーバ未接続' },
  }
  const s = styles[status]

  return (
    <div
      title={
        error
          ? '受信サーバに接続できません (npm run dev で起動してください)'
          : health
          ? `累計受信: ${health.upload?.totalUploaded ?? 0} 件`
          : ''
      }
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 8,
        padding: '4px 10px',
        borderRadius: 999,
        background: s.bg,
        color: s.fg,
        fontSize: 12,
        fontWeight: 500,
        border: `1px solid ${s.dot}33`,
      }}
    >
      <span
        style={{
          width: 8,
          height: 8,
          borderRadius: '50%',
          background: s.dot,
          animation: status === 'receiving' ? 'romen-pulse 1s ease-in-out infinite' : 'none',
        }}
      />
      <span>{s.label}</span>
      {health && health.upload && health.upload.totalUploaded > 0 && (
        <span style={{ opacity: 0.7 }}>({health.upload.totalUploaded})</span>
      )}
      <style>{`
        @keyframes romen-pulse {
          0%, 100% { opacity: 1; transform: scale(1); }
          50% { opacity: 0.4; transform: scale(1.4); }
        }
      `}</style>
    </div>
  )
}
