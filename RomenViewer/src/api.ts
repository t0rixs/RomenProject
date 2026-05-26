// RomenLogger HTTPサーバ (port 8080) との通信 API。
// Vite dev server の proxy 経由で `/api/...` がそのまま転送される。
//
// 本番ビルド (vite build) で別ホストに置く場合は VITE_LOGGER_URL を設定する。

const BASE = (import.meta.env.VITE_LOGGER_URL ?? '').replace(/\/$/, '')

export interface Recording {
  id: string
  startedAtMs: number
  endedAtMs?: number
  gpsCount: number
  accelCount: number
}

export interface MergedSample {
  t: number
  lat: number
  lon: number
  vMax: number
  vMean: number
  /** 軸別重み付き偏差ベクトルの RMS（路面評価の主指標）。古い merged.json では未定義 */
  vRms?: number
}

export interface MergedData {
  id: string
  bucketMs: number
  startedAtMs: number
  endedAtMs?: number
  gpsCount: number
  accelBucketCount: number
  originalSampleCount?: number
  sampleLimit?: number
  downsampled?: boolean
  samples: MergedSample[]
}

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`)
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}: ${path}`)
  return (await res.json()) as T
}

export interface UploadState {
  inProgress: number
  lastStartedAt: number
  lastFinishedAt: number
  lastFinishedId: string | null
  totalUploaded: number
}

export interface HealthResponse {
  ok: boolean
  app: string
  now?: number
  upload?: UploadState
}

export const api = {
  health: () => getJson<HealthResponse>('/api/health'),
  list: () => getJson<Recording[]>('/api/recordings'),
  merged: (id: string) => getJson<MergedData>(`/api/recordings/${id}/merged.json`),
  csvUrl: (id: string, name: 'gps.csv' | 'accel.csv') =>
    `${BASE}/api/recordings/${id}/${name}`,
}
