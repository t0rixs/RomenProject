import { useEffect, useMemo, useState } from 'react'
import { MapContainer, TileLayer, CircleMarker, Polyline, Popup, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  ReferenceLine,
} from 'recharts'
import { api, type MergedData, type MergedSample, type RecordingPhoto, type RoadLabel, type RoadLabelKind } from './api'
import { formatTime } from './format'

// 振動レベルの量子化段数。Polyline をこの本数までにまとめる。
const COLOR_LEVELS = 16
// チャート描画上限点数。これを超えるとバケット集約してダウンサンプリング。
const CHART_MAX_POINTS = 2000

type ChartPoint = {
  i: number
  sec: number
  v: number
  vMean: number
}

function roadLabelText(label: RoadLabelKind): string {
  return label === 'paved' ? '舗装路' : '未舗装'
}

function roadLabelColor(label: RoadLabelKind): string {
  return label === 'paved' ? '#16a34a' : '#92400e'
}

// 正規化値 t (0..1) → 青→赤の HSL カラー。
// V_MAX_CLIP に依存しない動的色域用。
function colorForT(t: number): string {
  const c = Math.max(0, Math.min(1, t))
  return `hsl(${240 - 240 * c}, 90%, 50%)`
}

// 地理院地図 (GSI) タイル定義。
// 利用規約: 出典の明示が必要。
// https://maps.gsi.go.jp/development/ichiran.html
type TileKey = 'pale' | 'std' | 'photo'
const TILES: Record<TileKey, { url: string; attribution: string; label: string; maxZoom: number }> = {
  pale: {
    url: 'https://cyberjapandata.gsi.go.jp/xyz/pale/{z}/{x}/{y}.png',
    attribution:
      '出典: <a href="https://maps.gsi.go.jp/development/ichiran.html" target="_blank" rel="noreferrer">国土地理院</a>',
    label: '淡色',
    maxZoom: 18,
  },
  std: {
    url: 'https://cyberjapandata.gsi.go.jp/xyz/std/{z}/{x}/{y}.png',
    attribution:
      '出典: <a href="https://maps.gsi.go.jp/development/ichiran.html" target="_blank" rel="noreferrer">国土地理院</a>',
    label: '標準',
    maxZoom: 18,
  },
  photo: {
    url: 'https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto/{z}/{x}/{y}.jpg',
    attribution:
      '出典: <a href="https://maps.gsi.go.jp/development/ichiran.html" target="_blank" rel="noreferrer">国土地理院</a>',
    label: '写真',
    maxZoom: 18,
  },
}

interface Props {
  recordingId: string
  onBack: () => void
}

export function RecordingDetail({ recordingId, onBack }: Props) {
  const [data, setData] = useState<MergedData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [hoverIdx, setHoverIdx] = useState<number | null>(null)
  const [tile, setTile] = useState<TileKey>('pale')
  const [isChartFullscreen, setIsChartFullscreen] = useState(false)
  const [isEditMode, setIsEditMode] = useState(false)
  const [labels, setLabels] = useState<RoadLabel[]>([])
  const [photos, setPhotos] = useState<RecordingPhoto[]>([])
  const [selectedLabelPoints, setSelectedLabelPoints] = useState<number[]>([])
  const [labelError, setLabelError] = useState<string | null>(null)
  const [isSavingLabel, setIsSavingLabel] = useState(false)
  // 移動平均の半径 [m]。0 だと平滑化なし。
  const [smoothMeters, setSmoothMeters] = useState<number>(0)
  // 色スケール上限 (m/s²)。'auto' はセッション最大値。
  const [vCeilMode, setVCeilMode] = useState<number | 'auto'>(10)

  useEffect(() => {
    setData(null)
    setError(null)
    setIsChartFullscreen(false)
    setSelectedLabelPoints([])
    setLabelError(null)
    api
      .merged(recordingId)
      .then(setData)
      .catch((e: Error) => setError(e.message))
  }, [recordingId])

  useEffect(() => {
    setLabels([])
    api
      .labels(recordingId)
      .then(setLabels)
      .catch((e: Error) => setLabelError(e.message))
  }, [recordingId])

  useEffect(() => {
    setPhotos([])
    api.photos(recordingId).then(setPhotos).catch(() => setPhotos([]))
  }, [recordingId])

  useEffect(() => {
    if (!isChartFullscreen) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setIsChartFullscreen(false)
    }
    document.addEventListener('keydown', onKeyDown)
    document.body.classList.add('modal-open')
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.classList.remove('modal-open')
    }
  }, [isChartFullscreen])

  const center = useMemo<[number, number] | null>(() => {
    if (!data || data.samples.length === 0) return null
    let lat = 0
    let lon = 0
    for (const p of data.samples) {
      lat += p.lat
      lon += p.lon
    }
    return [lat / data.samples.length, lon / data.samples.length]
  }, [data])

  const path = useMemo<[number, number][]>(() => {
    if (!data) return []
    return data.samples.map((s) => [s.lat, s.lon] as [number, number])
  }, [data])

  // 累積距離 [m]。Haversine で隣接点ごとに足し込み。
  const cumDist = useMemo<Float64Array>(() => {
    if (!data) return new Float64Array(0)
    const n = data.samples.length
    const out = new Float64Array(n)
    if (n === 0) return out
    const R = 6371000
    const toRad = (deg: number) => (deg * Math.PI) / 180
    for (let i = 1; i < n; i++) {
      const a = data.samples[i - 1]
      const b = data.samples[i]
      const dLat = toRad(b.lat - a.lat)
      const dLon = toRad(b.lon - a.lon)
      const la1 = toRad(a.lat)
      const la2 = toRad(b.lat)
      const h =
        Math.sin(dLat / 2) ** 2 +
        Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) ** 2
      const d = 2 * R * Math.asin(Math.min(1, Math.sqrt(h)))
      out[i] = out[i - 1] + d
    }
    return out
  }, [data])

  // 振動指標 v (主軸)。vRms があれば優先、無ければ vMax にフォールバック。
  const vRaw = useMemo<Float64Array>(() => {
    if (!data) return new Float64Array(0)
    const n = data.samples.length
    const out = new Float64Array(n)
    for (let i = 0; i < n; i++) {
      const s = data.samples[i]
      out[i] = s.vRms ?? s.vMax
    }
    return out
  }, [data])

  // 距離 ±smoothMeters の移動平均で平滑化（two-pointer, O(N)）。
  // smoothMeters=0 のときは元の値そのまま。
  const smoothedV = useMemo<Float64Array>(() => {
    const n = vRaw.length
    if (n === 0) return vRaw
    if (smoothMeters <= 0) return vRaw
    const out = new Float64Array(n)
    let lo = 0
    let hi = 0
    let sum = 0
    for (let i = 0; i < n; i++) {
      const target = cumDist[i]
      while (hi < n && cumDist[hi] <= target + smoothMeters) {
        sum += vRaw[hi]
        hi++
      }
      while (lo < n && cumDist[lo] < target - smoothMeters) {
        sum -= vRaw[lo]
        lo++
      }
      const cnt = hi - lo
      out[i] = cnt > 0 ? sum / cnt : vRaw[i]
    }
    return out
  }, [vRaw, cumDist, smoothMeters])

  // 動的色域: 平滑化後 v の最大値を「赤」、0 を「青」にマップ。
  // セッションごとに自動スケール。
  const vCeilAuto = useMemo(() => {
    if (smoothedV.length === 0) return 1
    let m = 0
    for (let i = 0; i < smoothedV.length; i++) if (smoothedV[i] > m) m = smoothedV[i]
    return m > 0 ? m : 1
  }, [smoothedV])
  // 既定は 10。ユーザが変更可。"auto" は smoothedV の最大値。
  const vCeil = vCeilMode === 'auto' ? vCeilAuto : vCeilMode

  // 振動レベル別にサンプルを連続区間でまとめる。
  // 結果: level (0..N-1) → そのレベルの線分配列 (LatLng[][])。
  // 数十万点でも Polyline が高々 N 本なので軽い。
  const segmentsByLevel = useMemo<Array<Array<[number, number][]>>>(() => {
    const groups: Array<Array<[number, number][]>> = Array.from(
      { length: COLOR_LEVELS },
      () => [],
    )
    if (!data || data.samples.length === 0) return groups
    const samples = data.samples
    const levelOf = (v: number) => {
      const t = Math.max(0, Math.min(1, v / vCeil))
      return Math.min(COLOR_LEVELS - 1, Math.floor(t * COLOR_LEVELS))
    }

    let curLevel = levelOf(smoothedV[0])
    let segStart = 0
    for (let i = 1; i < samples.length; i++) {
      const lv = levelOf(smoothedV[i])
      if (lv !== curLevel) {
        const seg: [number, number][] = []
        for (let k = segStart; k <= i; k++) seg.push([samples[k].lat, samples[k].lon])
        groups[curLevel].push(seg)
        curLevel = lv
        segStart = i
      }
    }
    const seg: [number, number][] = []
    for (let k = segStart; k < samples.length; k++) seg.push([samples[k].lat, samples[k].lon])
    groups[curLevel].push(seg)
    return groups
  }, [data, smoothedV, vCeil])

  // レベル中央値の代表色（動的レンジ）。
  const colorOfLevel = useMemo(() => {
    return Array.from({ length: COLOR_LEVELS }, (_, lv) =>
      colorForT((lv + 0.5) / COLOR_LEVELS),
    )
  }, [])

  // チャートデータ。サンプル数が多い場合はバケット集約してダウンサンプリング。
  // sec は数値で持ち、XAxis を type="number" にして連続軸として扱う。
  const fullChartData = useMemo<ChartPoint[]>(() => {
    if (!data || data.samples.length === 0) return []
    const t0 = data.samples[0].t
    return data.samples.map((s, i) => ({
      i,
      sec: (s.t - t0) / 1000,
      v: smoothedV[i],
      vMean: s.vMean,
    }))
  }, [data, smoothedV])

  const chartData = useMemo<ChartPoint[]>(() => {
    if (!data || data.samples.length === 0) return []
    const samples = data.samples
    const t0 = samples[0].t
    const N = samples.length
    if (N <= CHART_MAX_POINTS) {
      return samples.map((s, i) => ({
        i,
        sec: (s.t - t0) / 1000,
        v: smoothedV[i],
        vMean: s.vMean,
      }))
    }
    const bucket = Math.ceil(N / CHART_MAX_POINTS)
    const out: ChartPoint[] = []
    for (let i = 0; i < N; i += bucket) {
      const end = Math.min(N, i + bucket)
      let vMax = -Infinity
      let vMaxIdx = i
      let sumMean = 0
      for (let k = i; k < end; k++) {
        const v = smoothedV[k]
        if (v > vMax) {
          vMax = v
          vMaxIdx = k
        }
        sumMean += samples[k].vMean
      }
      const rep = samples[vMaxIdx]
      out.push({
        i: vMaxIdx,
        sec: (rep.t - t0) / 1000,
        v: vMax,
        vMean: sumMean / (end - i),
      })
    }
    return out
  }, [data, smoothedV])

  const labelSegments = useMemo(() => {
    if (!data) return [] as Array<{ label: RoadLabel; points: [number, number][] }>
    return labels
      .map((label) => {
        const startIndex = Math.max(0, Math.min(label.startIndex, data.samples.length - 1))
        const endIndex = Math.max(0, Math.min(label.endIndex, data.samples.length - 1))
        const points = data.samples
          .slice(startIndex, endIndex + 1)
          .map((s) => [s.lat, s.lon] as [number, number])
        return { label, points }
      })
      .filter((seg) => seg.points.length >= 2)
  }, [data, labels])

  const selectedLabelSegment = useMemo<[number, number][]>(() => {
    if (!data || selectedLabelPoints.length !== 2) return []
    const [a, b] = selectedLabelPoints
    const startIndex = Math.min(a, b)
    const endIndex = Math.max(a, b)
    return data.samples
      .slice(startIndex, endIndex + 1)
      .map((s) => [s.lat, s.lon] as [number, number])
  }, [data, selectedLabelPoints])

  const hoverSample: MergedSample | null =
    hoverIdx != null && data?.samples[hoverIdx] ? data.samples[hoverIdx] : null

  const selectLabelPoint = (idx: number) => {
    setLabelError(null)
    setSelectedLabelPoints((prev) => {
      if (prev.length >= 2) return [idx]
      if (prev.includes(idx)) return prev.filter((p) => p !== idx)
      return [...prev, idx]
    })
  }

  const saveRoadLabel = async (label: RoadLabelKind) => {
    if (selectedLabelPoints.length !== 2) return
    setIsSavingLabel(true)
    setLabelError(null)
    try {
      const [a, b] = selectedLabelPoints
      const res = await api.addLabel(recordingId, label, a, b)
      setLabels(res.labels)
      setSelectedLabelPoints([])
    } catch (e) {
      setLabelError(e instanceof Error ? e.message : String(e))
    } finally {
      setIsSavingLabel(false)
    }
  }

  const deleteRoadLabel = async (labelId: string) => {
    setIsSavingLabel(true)
    setLabelError(null)
    try {
      const res = await api.deleteLabel(recordingId, labelId)
      setLabels(res.labels)
    } catch (e) {
      setLabelError(e instanceof Error ? e.message : String(e))
    } finally {
      setIsSavingLabel(false)
    }
  }

  return (
    <div className="page detail">
      <header className="page-header">
        <button onClick={onBack}>← 戻る</button>
        <h1>{data ? formatTime(data.startedAtMs) : recordingId}</h1>
        <div className="tile-switch" role="group" aria-label="地図種別">
          {(Object.keys(TILES) as TileKey[]).map((k) => (
            <button
              key={k}
              onClick={() => setTile(k)}
              className={tile === k ? 'active' : ''}
              aria-pressed={tile === k}
            >
              {TILES[k].label}
            </button>
          ))}
        </div>
        <button
          type="button"
          onClick={() => {
            setIsEditMode((v) => !v)
            setSelectedLabelPoints([])
            setLabelError(null)
          }}
          aria-pressed={isEditMode}
        >
          edit
        </button>
      </header>

      {error && <div className="error">読込失敗: {error}</div>}
      {!data && !error && <p>読込中…</p>}

      {data && data.samples.length === 0 && (
        <p>このセッションには表示できるデータがありません（GPS または振動が空）。</p>
      )}

      {data && data.samples.length > 0 && center && (
        <div className="detail-grid">
          <div className="map-wrap">
            <MapContainer
              center={center}
              zoom={17}
              style={{ height: '100%', width: '100%' }}
              scrollWheelZoom
              preferCanvas
            >
              <TileLayer
                key={tile}
                attribution={TILES[tile].attribution}
                url={TILES[tile].url}
                maxZoom={TILES[tile].maxZoom}
              />
              <FitBounds points={path} />
              {/* レベル別に1本ずつ Polyline (内部で複数セグメント) */}
              {segmentsByLevel.map((segs, lv) =>
                segs.length === 0 ? null : (
                  <Polyline
                    key={lv}
                    positions={segs}
                    pathOptions={{
                      color: colorOfLevel[lv],
                      weight: 4,
                      opacity: 0.9,
                      lineCap: 'round',
                      lineJoin: 'round',
                    }}
                  />
                ),
              )}
              {isEditMode &&
                labelSegments.map(({ label, points }) => (
                  <Polyline
                    key={label.id}
                    positions={points}
                    pathOptions={{
                      color: roadLabelColor(label.label),
                      weight: 10,
                      opacity: 0.72,
                      lineCap: 'round',
                      lineJoin: 'round',
                    }}
                  />
                ))}
              {isEditMode && selectedLabelSegment.length >= 2 && (
                <Polyline
                  positions={selectedLabelSegment}
                  pathOptions={{
                    color: '#111827',
                    weight: 8,
                    opacity: 0.78,
                    dashArray: '8 6',
                    lineCap: 'round',
                    lineJoin: 'round',
                  }}
                />
              )}
              {photos.map((photo) => (
                <CircleMarker
                  key={photo.id}
                  center={[photo.lat, photo.lon]}
                  radius={8}
                  pathOptions={{
                    color: '#ffffff',
                    weight: 3,
                    fillColor: '#7c3aed',
                    fillOpacity: 1,
                  }}
                >
                  <Popup minWidth={240}>
                    <div className="photo-popup">
                      <img
                        src={api.photoUrl(recordingId, photo.fileName)}
                        alt={formatTime(photo.capturedAtMs)}
                      />
                      <strong>{formatTime(photo.capturedAtMs)}</strong>
                      <span>
                        {photo.lat.toFixed(6)}, {photo.lon.toFixed(6)}
                      </span>
                      {photo.accuracyM != null && <span>精度: {photo.accuracyM.toFixed(1)} m</span>}
                    </div>
                  </Popup>
                </CircleMarker>
              ))}
              {isEditMode &&
                data.samples.map((sample, idx) => {
                  const selected = selectedLabelPoints.includes(idx)
                  return (
                    <CircleMarker
                      key={idx}
                      center={[sample.lat, sample.lon]}
                      radius={selected ? 7 : 3}
                      eventHandlers={{ click: () => selectLabelPoint(idx) }}
                      pathOptions={{
                        color: selected ? '#111827' : '#475569',
                        weight: selected ? 3 : 1,
                        fillColor: selected ? '#facc15' : '#ffffff',
                        fillOpacity: selected ? 1 : 0.82,
                      }}
                    />
                  )
                })}
              {/* チャート ホバー時のみ強調マーカーを 1 個だけ */}
              {hoverSample && hoverIdx != null && (
                <CircleMarker
                  center={[hoverSample.lat, hoverSample.lon]}
                  radius={9}
                  pathOptions={{
                    color: '#000',
                    weight: 2,
                    fillColor: colorForT((smoothedV[hoverIdx] ?? hoverSample.vMax) / vCeil),
                    fillOpacity: 1,
                  }}
                />
              )}
            </MapContainer>
          </div>

          <div className="side-panel">
            <div className="info-card">
              <div>記録ID: {data.id}</div>
              <div>
                表示点数: {data.samples.length}
                {data.originalSampleCount && data.originalSampleCount !== data.samples.length
                  ? ` / 元データ ${data.originalSampleCount}`
                  : ''}
              </div>
              <div>バケット幅: {data.bucketMs} ms</div>
              <div>GPS点数: {data.gpsCount}</div>
              <div>写真: {photos.length}枚（紫の点）</div>
            </div>

            {isEditMode && (
              <div className="info-card edit-panel">
                <div className="edit-panel-row">
                  <span>editモード</span>
                  <strong>ラベル済み: {labels.length}</strong>
                </div>
                <div>
                  選択: {selectedLabelPoints.length}/2
                  {selectedLabelPoints.length === 2
                    ? ` (${Math.min(...selectedLabelPoints)} - ${Math.max(...selectedLabelPoints)})`
                    : ''}
                </div>
                {selectedLabelPoints.length === 2 && (
                  <div className="label-buttons">
                    <button
                      type="button"
                      onClick={() => saveRoadLabel('paved')}
                      disabled={isSavingLabel}
                    >
                      舗装路
                    </button>
                    <button
                      type="button"
                      onClick={() => saveRoadLabel('unpaved')}
                      disabled={isSavingLabel}
                    >
                      未舗装
                    </button>
                  </div>
                )}
                {labelError && <div className="edit-error">ラベル保存エラー: {labelError}</div>}
                {labels.length > 0 && (
                  <div className="label-list">
                    {labels.map((label) => (
                      <div key={label.id} className="label-list-item">
                        <span>
                          <span
                            className="label-swatch"
                            style={{ background: roadLabelColor(label.label) }}
                          />
                          {roadLabelText(label.label)} {label.startIndex}-{label.endIndex}
                        </span>
                        <button
                          type="button"
                          onClick={() => deleteRoadLabel(label.id)}
                          disabled={isSavingLabel}
                        >
                          削除
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            <div className="info-card">
              <div
                style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginBottom: 6 }}
              >
                <span>平滑化半径</span>
                <span>
                  <strong>{smoothMeters}</strong> m {smoothMeters === 0 && '(なし)'}
                </span>
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                {[0, 1, 2, 3, 4, 5, 10, 20, 40, 50].map((m) => (
                  <button
                    key={m}
                    onClick={() => setSmoothMeters(m)}
                    aria-pressed={smoothMeters === m}
                    style={{
                      flex: '1 0 auto',
                      minWidth: 36,
                      padding: '4px 6px',
                      fontSize: 12,
                      borderRadius: 4,
                      border: '1px solid #cbd5e1',
                      background: smoothMeters === m ? '#1976d2' : '#fff',
                      color: smoothMeters === m ? '#fff' : '#334155',
                      cursor: 'pointer',
                    }}
                  >
                    {m}
                  </button>
                ))}
              </div>
            </div>

            <div className="legend">
              <div
                style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 12, marginBottom: 4 }}
              >
                <span>振動 (m/s²)</span>
                <span style={{ opacity: 0.7 }}>
                  上限: <strong>{vCeilMode === 'auto' ? `auto (${vCeilAuto.toFixed(2)})` : vCeilMode}</strong>
                </span>
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 6 }}>
                {([1, 2, 5, 10, 20, 50, 'auto'] as Array<number | 'auto'>).map((m) => (
                  <button
                    key={String(m)}
                    onClick={() => setVCeilMode(m)}
                    aria-pressed={vCeilMode === m}
                    style={{
                      flex: '1 0 auto',
                      minWidth: 36,
                      padding: '4px 6px',
                      fontSize: 12,
                      borderRadius: 4,
                      border: '1px solid #cbd5e1',
                      background: vCeilMode === m ? '#1976d2' : '#fff',
                      color: vCeilMode === m ? '#fff' : '#334155',
                      cursor: 'pointer',
                    }}
                  >
                    {m === 'auto' ? 'auto' : `${m}+`}
                  </button>
                ))}
              </div>
              <div
                style={{
                  height: 12,
                  borderRadius: 6,
                  background: `linear-gradient(to right, ${colorForT(0)}, ${colorForT(0.5)}, ${colorForT(1)})`,
                }}
              />
              <div
                style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, marginTop: 2 }}
              >
                <span>0</span>
                <span>{(vCeil / 2).toFixed(2)}</span>
                <span>
                  {vCeil.toFixed(2)}
                  {vCeilMode !== 'auto' ? '+' : ''}
                </span>
              </div>
            </div>

            <div className="chart-wrap">
              <div className="chart-title-row">
                <h3>振動 [m/s²]{smoothMeters > 0 ? ` (平滑化 ±${smoothMeters}m)` : ''}</h3>
                <button type="button" className="chart-expand-button" onClick={() => setIsChartFullscreen(true)}>
                  全画面
                </button>
              </div>
              <button
                type="button"
                className="chart-click-target"
                onClick={() => setIsChartFullscreen(true)}
                aria-label="振動グラフを全画面表示"
              >
                <VibrationChart
                  data={chartData}
                  height={200}
                  hoverIdx={hoverIdx}
                  onHover={setHoverIdx}
                />
              </button>
            </div>

            {hoverIdx != null && data.samples[hoverIdx] && (
              <div className="info-card">
                <div>t: {formatTime(data.samples[hoverIdx].t)}</div>
                <div>
                  位置: {data.samples[hoverIdx].lat.toFixed(6)},{' '}
                  {data.samples[hoverIdx].lon.toFixed(6)}
                </div>
                <div>
                  振動: <strong>{(smoothedV[hoverIdx] ?? data.samples[hoverIdx].vMax).toFixed(2)}</strong> m/s² /
                  mean: {data.samples[hoverIdx].vMean.toFixed(2)}
                </div>
              </div>
            )}

            <div className="downloads">
              <a href={api.csvUrl(recordingId, 'gps.csv')} download>
                gps.csv ダウンロード
              </a>
              <a href={api.csvUrl(recordingId, 'accel.csv')} download>
                accel.csv ダウンロード
              </a>
            </div>
          </div>
        </div>
      )}

      {data && isChartFullscreen && (
        <div
          className="chart-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="振動グラフ 全画面表示"
          onClick={() => setIsChartFullscreen(false)}
        >
          <div className="chart-modal" onClick={(e) => e.stopPropagation()}>
            <div className="chart-modal-header">
              <h2>振動 [m/s²]{smoothMeters > 0 ? ` (平滑化 ±${smoothMeters}m)` : ''}</h2>
              <button type="button" onClick={() => setIsChartFullscreen(false)}>
                閉じる
              </button>
            </div>
            <div className="chart-modal-body">
              <VibrationChart
                data={fullChartData}
                height="100%"
                hoverIdx={hoverIdx}
                onHover={setHoverIdx}
              />
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function VibrationChart({
  data,
  height,
  hoverIdx,
  onHover,
}: {
  data: ChartPoint[]
  height: number | `${number}%`
  hoverIdx: number | null
  onHover: (idx: number | null) => void
}) {
  const hoverSec = hoverIdx != null ? data.find((d) => d.i === hoverIdx)?.sec : undefined

  return (
    <ResponsiveContainer width="100%" height={height}>
      <LineChart
        data={data}
        onMouseMove={(e) => {
          const idx = e?.activeTooltipIndex
          if (idx == null) return
          const chartIdx = typeof idx === 'string' ? parseInt(idx, 10) : idx
          onHover(data[chartIdx]?.i ?? null)
        }}
        onMouseLeave={() => onHover(null)}
        margin={{ top: 8, right: 16, bottom: 8, left: 0 }}
      >
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis
          dataKey="sec"
          type="number"
          domain={['dataMin', 'dataMax']}
          tick={{ fontSize: 10 }}
          tickFormatter={(v: number) => v.toFixed(0)}
          label={{ value: 'sec', position: 'insideBottomRight', offset: -4 }}
        />
        <YAxis tick={{ fontSize: 10 }} />
        <Tooltip />
        <Line type="monotone" dataKey="v" stroke="#d32f2f" dot={false} name="v" />
        <Line type="monotone" dataKey="vMean" stroke="#1976d2" dot={false} name="vMean" />
        {hoverSec != null && (
          <ReferenceLine x={hoverSec} stroke="#444" strokeDasharray="3 3" />
        )}
      </LineChart>
    </ResponsiveContainer>
  )
}

function FitBounds({ points }: { points: [number, number][] }) {
  const map = useMap()
  useEffect(() => {
    if (points.length === 0) return
    // 注意: Math.min(...arr) は要素数が大きい (~1万超) と
    // RangeError: Maximum call stack size exceeded になる。
    let minLat = points[0][0], maxLat = points[0][0]
    let minLon = points[0][1], maxLon = points[0][1]
    for (let i = 1; i < points.length; i++) {
      const [la, lo] = points[i]
      if (la < minLat) minLat = la
      else if (la > maxLat) maxLat = la
      if (lo < minLon) minLon = lo
      else if (lo > maxLon) maxLon = lo
    }
    const bounds: [[number, number], [number, number]] = [
      [minLat, minLon],
      [maxLat, maxLon],
    ]
    map.fitBounds(bounds, { padding: [24, 24] })
  }, [points, map])
  return null
}
