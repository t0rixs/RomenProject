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
  /** 手動ラベル。各サンプルにつき最大1つ */
  roadLabel?: RoadLabelKind
  roadLabelId?: string
  roadLabelCreatedAtMs?: number
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

export interface RecordingPhoto {
  id: string
  fileName: string
  capturedAtMs: number
  lat: number
  lon: number
  accuracyM?: number | null
}

export type RoadLabelKind = 'paved' | 'unpaved'

export interface RoadLabel {
  id: string
  recordingId: string
  label: RoadLabelKind
  startIndex: number
  endIndex: number
  startTime: number
  endTime: number
  startLat: number
  startLon: number
  endLat: number
  endLon: number
  sampleCount: number
  distanceM: number
  createdAtMs: number
}

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`)
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}: ${path}`)
  return (await res.json()) as T
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}: ${path}`)
  return (await res.json()) as T
}

async function deleteJson<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: 'DELETE' })
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
  photos: (id: string) => getJson<RecordingPhoto[]>(`/api/recordings/${id}/photos`),
  photoUrl: (id: string, fileName: string) =>
    `${BASE}/api/recordings/${id}/${encodeURIComponent(fileName)}`,
  labels: (id: string) => getJson<RoadLabel[]>(`/api/recordings/${id}/labels`),
  addLabel: (id: string, label: RoadLabelKind, startIndex: number, endIndex: number) =>
    postJson<{ ok: true; label: RoadLabel; labels: RoadLabel[] }>(`/api/recordings/${id}/labels`, {
      label,
      startIndex,
      endIndex,
    }),
  deleteLabel: (recordingId: string, labelId: string) =>
    deleteJson<{ ok: true; labels: RoadLabel[] }>(
      `/api/recordings/${recordingId}/labels/${encodeURIComponent(labelId)}`,
    ),
  csvUrl: (id: string, name: 'gps.csv' | 'accel.csv') =>
    `${BASE}/api/recordings/${id}/${name}`,
}
