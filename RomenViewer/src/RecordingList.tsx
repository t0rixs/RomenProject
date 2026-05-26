import { useEffect, useState } from 'react'
import { api, type Recording } from './api'
import { formatTime, formatDuration } from './format'

interface Props {
  onSelect: (id: string) => void
  reloadSignal?: number
  headerSlot?: React.ReactNode
}

export function RecordingList({ onSelect, reloadSignal, headerSlot }: Props) {
  const [items, setItems] = useState<Recording[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const reload = async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await api.list()
      setItems(list)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    reload()
  }, [reloadSignal])

  return (
    <div className="page">
      <header className="page-header">
        <h1>RomenViewer</h1>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          {headerSlot}
          <button onClick={reload} disabled={loading}>
            {loading ? '読込中…' : '再読込'}
          </button>
        </div>
      </header>

      {error && (
        <div className="error">
          <strong>接続エラー:</strong> {error}
          <p className="hint">
            受信サーバが起動していない可能性があります。<code>npm run dev</code> を実行してください。
          </p>
        </div>
      )}

      {items && items.length === 0 && <p>記録がありません。</p>}

      {items && items.length > 0 && (
        <ul className="rec-list">
          {items.map((r) => {
            const dur = r.endedAtMs ? formatDuration(r.endedAtMs - r.startedAtMs) : '進行中'
            return (
              <li key={r.id}>
                <button className="rec-card" onClick={() => onSelect(r.id)}>
                  <div className="rec-title">{formatTime(r.startedAtMs)}</div>
                  <div className="rec-meta">
                    時間 {dur} / GPS {r.gpsCount} / Accel {r.accelCount}
                  </div>
                  <div className="rec-id">{r.id}</div>
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
