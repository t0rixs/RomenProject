// RomenViewer 受信サーバ (port 5174)
//
// 起動: npm run server
//
// THINKLET から `adb reverse tcp:5174 tcp:5174` 経由で
// http://localhost:5174/api/upload に multipart POST されるのを受け取り、
// ./data/recordings/{id}/ に保存する。
// Viewer (vite :5173) は同じ /api/* をこのサーバから読む。
import http from 'node:http'
import { promises as fs, createWriteStream, existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const DATA_ROOT = path.resolve(__dirname, 'data', 'recordings')
const LABEL_POOL_FILE = path.resolve(__dirname, 'data', 'label-pool.jsonl')
const PORT = Number(process.env.PORT ?? 5174)
const mergedCache = new Map()

await fs.mkdir(DATA_ROOT, { recursive: true })

// 受信状況の追跡（Viewer ステータス表示用）
const uploadState = {
  inProgress: 0,            // 現在受信中の件数
  lastStartedAt: 0,         // 直近に受信開始した時刻 (ms)
  lastFinishedAt: 0,        // 直近に受信完了した時刻 (ms)
  lastFinishedId: null,     // 直近に保存した recording id
  totalUploaded: 0,         // 累積件数
}

const server = http.createServer(async (req, res) => {
  // CORS（直接ブラウザから叩く時用）
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type')
  if (req.method === 'OPTIONS') {
    res.writeHead(204)
    res.end()
    return
  }

  try {
    const url = new URL(req.url ?? '/', `http://${req.headers.host}`)
    const pathname = url.pathname.replace(/\/+$/, '')

    if (pathname === '' || pathname === '/') return rootInfo(res)
    if (pathname === '/api/health') return json(res, {
      ok: true,
      app: 'RomenViewerServer',
      now: Date.now(),
      upload: { ...uploadState },
    })
    if (pathname === '/api/recordings' && req.method === 'GET') return await listRecordings(res)
    if (pathname === '/api/upload' && req.method === 'POST') return await handleUpload(req, res)

    const photosMatch = pathname.match(/^\/api\/recordings\/([^/]+)\/photos$/)
    if (photosMatch && req.method === 'GET') return await listPhotos(res, photosMatch[1])

    const labelsMatch = pathname.match(/^\/api\/recordings\/([^/]+)\/labels$/)
    if (labelsMatch && req.method === 'GET') return await listLabels(res, labelsMatch[1])
    if (labelsMatch && req.method === 'POST') return await addLabel(req, res, labelsMatch[1])

    const labelMatch = pathname.match(/^\/api\/recordings\/([^/]+)\/labels\/([^/]+)$/)
    if (labelMatch && req.method === 'DELETE') return await deleteLabel(res, labelMatch[1], decodeURIComponent(labelMatch[2]))

    const m = pathname.match(/^\/api\/recordings\/([^/]+)\/([^/]+)$/)
    if (m && req.method === 'GET') return await serveRecordingFile(res, m[1], m[2], url.searchParams)
    if (m && req.method === 'DELETE') return await deleteRecording(res, m[1])

    notFound(res, `no route: ${req.method} ${pathname}`)
  } catch (e) {
    console.error(e)
    res.writeHead(500, { 'Content-Type': 'text/plain' })
    res.end(`ERROR: ${(e instanceof Error ? e.message : String(e))}`)
  }
})

server.listen(PORT, () => {
  console.log(`RomenViewer receive server on http://localhost:${PORT}`)
  console.log(`Storage: ${DATA_ROOT}`)
  console.log(`On THINKLET-connected PC: adb reverse tcp:${PORT} tcp:${PORT}`)
})

// ---- handlers ----

function rootInfo(res) {
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
  res.end(`<!doctype html>
<meta charset="utf-8">
<title>RomenViewer Receive Server</title>
<style>body{font:14px/1.5 system-ui;padding:24px;max-width:640px}code{background:#eef;padding:2px 6px;border-radius:4px}</style>
<h1>RomenViewer 受信サーバ (port ${PORT})</h1>
<p>これは <strong>THINKLET からのデータ受信専用</strong> サーバです。<br>
ブラウザでデータを見るには次のURLを開いてください：</p>
<p><a href="http://localhost:5173">http://localhost:5173</a></p>
<h3>状態</h3>
<ul>
<li>API: <a href="/api/health">/api/health</a></li>
<li>セッション一覧: <a href="/api/recordings">/api/recordings</a></li>
<li>保存先: <code>${DATA_ROOT}</code></li>
</ul>
<h3>THINKLET 接続手順</h3>
<pre>adb reverse tcp:${PORT} tcp:${PORT}</pre>`)
}

async function listRecordings(res) {
  const entries = await fs.readdir(DATA_ROOT, { withFileTypes: true }).catch(() => [])
  const list = []
  for (const e of entries) {
    if (!e.isDirectory()) continue
    const metaPath = path.join(DATA_ROOT, e.name, 'meta.json')
    if (!existsSync(metaPath)) continue
    try {
      const meta = JSON.parse(await fs.readFile(metaPath, 'utf8'))
      list.push(meta)
    } catch {
      /* skip */
    }
  }
  list.sort((a, b) => (b.startedAtMs ?? 0) - (a.startedAtMs ?? 0))
  json(res, list)
}

async function listPhotos(res, id) {
  if (!isSafeName(id)) return notFound(res, 'bad id')
  const file = path.join(DATA_ROOT, id, 'photos.json')
  if (!existsSync(file)) return json(res, [])
  try {
    const parsed = JSON.parse(await fs.readFile(file, 'utf8'))
    json(res, Array.isArray(parsed) ? parsed : Array.isArray(parsed?.photos) ? parsed.photos : [])
  } catch {
    json(res, [])
  }
}

async function serveRecordingFile(res, id, name, searchParams = new URLSearchParams()) {
  if (!isSafeName(id) || !isSafeName(name)) return notFound(res, 'bad name')
  const file = path.join(DATA_ROOT, id, name)
  if (!existsSync(file)) return notFound(res, `missing: ${name}`)

  if (name === 'merged.json') {
    const maxSamples = clampInt(searchParams.get('maxSamples'), 0, 50000)
    if (maxSamples > 0) return await serveMergedJson(res, file, maxSamples)
  }

  const mime =
    name.endsWith('.json') ? 'application/json; charset=utf-8' :
    name.endsWith('.csv') ? 'text/csv; charset=utf-8' :
    name.endsWith('.jpg') || name.endsWith('.jpeg') ? 'image/jpeg' :
    'application/octet-stream'
  res.writeHead(200, { 'Content-Type': mime })
  const stream = (await fs.open(file)).createReadStream()
  stream.pipe(res)
}

async function serveMergedJson(res, file, maxSamples) {
  const stat = await fs.stat(file)
  const key = `${file}:${stat.mtimeMs}:${stat.size}:${maxSamples}`
  const cached = mergedCache.get(key)
  if (cached) {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(cached)
    return
  }

  const merged = JSON.parse(await fs.readFile(file, 'utf8'))
  const samples = Array.isArray(merged.samples) ? merged.samples : []
  const originalSampleCount = samples.length
  const downsampled = originalSampleCount > maxSamples
  const body = JSON.stringify({
    ...merged,
    originalSampleCount,
    sampleLimit: maxSamples,
    downsampled,
    samples: downsampled ? downsampleSamples(samples, maxSamples) : samples,
  })

  mergedCache.clear()
  mergedCache.set(key, body)
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(body)
}

function downsampleSamples(samples, maxSamples) {
  if (samples.length <= maxSamples) return samples
  if (maxSamples <= 2) return [samples[0], samples[samples.length - 1]].slice(0, maxSamples)

  const out = [samples[0]]
  const innerCount = maxSamples - 2
  for (let i = 0; i < innerCount; i++) {
    const start = 1 + Math.floor((i * (samples.length - 2)) / innerCount)
    const end = 1 + Math.floor(((i + 1) * (samples.length - 2)) / innerCount)
    let best = samples[start]
    let bestV = vibrationValue(best)
    for (let k = start + 1; k < end; k++) {
      const v = vibrationValue(samples[k])
      if (v > bestV) {
        best = samples[k]
        bestV = v
      }
    }
    out.push(best)
  }
  out.push(samples[samples.length - 1])
  out.sort((a, b) => (a.t ?? 0) - (b.t ?? 0))
  return out
}

function vibrationValue(sample) {
  return Number(sample?.vRms ?? sample?.vMax ?? sample?.vMean ?? 0)
}

async function deleteRecording(res, id) {
  if (!isSafeName(id)) return notFound(res, 'bad id')
  const dir = path.join(DATA_ROOT, id)
  await fs.rm(dir, { recursive: true, force: true })
  json(res, { ok: true })
}

async function listLabels(res, id) {
  if (!isSafeName(id)) return notFound(res, 'bad id')
  const merged = await readMerged(id)
  if (!merged) return notFound(res, 'missing: merged.json')
  if (await migrateLegacyLabels(id, merged)) await writeMerged(id, merged)
  json(res, labelsFromSamples(id, merged.samples))
}

async function addLabel(req, res, id) {
  if (!isSafeName(id)) return notFound(res, 'bad id')
  const merged = await readMerged(id)
  if (!merged) return notFound(res, 'missing: merged.json')
  await migrateLegacyLabels(id, merged)

  const body = await readJsonBody(req, 1024 * 1024)
  const label = body?.label
  if (label !== 'paved' && label !== 'unpaved') return badRequest(res, 'invalid label')

  const samples = merged.samples
  if (samples.length === 0) return badRequest(res, 'no samples')

  const a = clampInt(body?.startIndex, 0, samples.length - 1)
  const b = clampInt(body?.endIndex, 0, samples.length - 1)
  const startIndex = Math.min(a, b)
  const endIndex = Math.max(a, b)
  if (startIndex === endIndex) return badRequest(res, 'select two different samples')

  const createdAtMs = Date.now()
  const item = {
    id: `${createdAtMs}_${Math.random().toString(36).slice(2, 8)}`,
    recordingId: id,
    label,
    createdAtMs,
  }

  for (let i = startIndex; i <= endIndex; i++) {
    samples[i].roadLabel = label
    samples[i].roadLabelId = item.id
    samples[i].roadLabelCreatedAtMs = createdAtMs
  }
  merged.labelStorageVersion = 1
  await writeMerged(id, merged)

  const saved = makeLabelFromRange(item, samples, startIndex, endIndex)
  await appendLabelPoolEvent('upsert', saved)
  json(res, { ok: true, label: saved, labels: labelsFromSamples(id, samples) })
}

async function deleteLabel(res, id, labelId) {
  if (!isSafeName(id)) return notFound(res, 'bad id')
  if (!labelId) return badRequest(res, 'missing label id')
  const merged = await readMerged(id)
  if (!merged) return notFound(res, 'missing: merged.json')
  await migrateLegacyLabels(id, merged)

  let deleted = 0
  for (const sample of merged.samples) {
    if (sample.roadLabelId !== labelId) continue
    delete sample.roadLabel
    delete sample.roadLabelId
    delete sample.roadLabelCreatedAtMs
    deleted++
  }

  if (deleted === 0) {
    const label = labelsFromSamples(id, merged.samples).find((item) => item.id === labelId)
    if (label) {
      for (let i = label.startIndex; i <= label.endIndex; i++) {
        delete merged.samples[i].roadLabel
        delete merged.samples[i].roadLabelId
        delete merged.samples[i].roadLabelCreatedAtMs
        deleted++
      }
    }
  }

  if (deleted === 0) return notFound(res, 'missing label')

  await writeMerged(id, merged)
  await appendLabelPoolEvent('delete', {
    id: labelId,
    recordingId: id,
    deletedAtMs: Date.now(),
  })
  json(res, { ok: true, labels: labelsFromSamples(id, merged.samples) })
}

async function readMerged(id) {
  const file = path.join(DATA_ROOT, id, 'merged.json')
  if (!existsSync(file)) return null
  const merged = JSON.parse(await fs.readFile(file, 'utf8'))
  merged.samples = Array.isArray(merged.samples) ? merged.samples : []
  return merged
}

async function writeMerged(id, merged) {
  const file = path.join(DATA_ROOT, id, 'merged.json')
  await fs.writeFile(file, `${JSON.stringify(merged, null, 2)}\n`, 'utf8')
}

async function readLabels(id) {
  const file = path.join(DATA_ROOT, id, 'labels.json')
  if (!existsSync(file)) return []
  try {
    const labels = JSON.parse(await fs.readFile(file, 'utf8'))
    return Array.isArray(labels) ? labels : []
  } catch {
    return []
  }
}

async function writeLabels(id, labels) {
  const file = path.join(DATA_ROOT, id, 'labels.json')
  await fs.writeFile(file, `${JSON.stringify(labels, null, 2)}\n`, 'utf8')
}

async function migrateLegacyLabels(id, merged) {
  if (merged.labelStorageVersion >= 1) return false
  const samples = merged.samples
  const legacy = normalizeExistingLabels(await readLabels(id), samples)
  for (const sample of samples) {
    delete sample.roadLabel
    delete sample.roadLabelId
    delete sample.roadLabelCreatedAtMs
  }
  for (const label of legacy) {
    for (let i = label.startIndex; i <= label.endIndex; i++) {
      samples[i].roadLabel = label.label
      samples[i].roadLabelId = label.id
      samples[i].roadLabelCreatedAtMs = label.createdAtMs
    }
  }
  merged.labelStorageVersion = 1
  merged.labelMigratedAtMs = Date.now()
  return true
}

function labelsFromSamples(recordingId, samples) {
  const labels = []
  let startIndex = -1
  let currentLabel = null
  let currentId = null
  let currentCreatedAt = null

  const flush = (endIndex) => {
    if (startIndex < 0 || !currentLabel) return
    labels.push(
      makeLabelFromRange(
        {
          id: currentId ?? `${recordingId}_${startIndex}_${endIndex}_${currentLabel}`,
          recordingId,
          label: currentLabel,
          createdAtMs: currentCreatedAt ?? 0,
        },
        samples,
        startIndex,
        endIndex,
      ),
    )
  }

  for (let i = 0; i < samples.length; i++) {
    const sample = samples[i]
    const label = sample.roadLabel === 'paved' || sample.roadLabel === 'unpaved'
      ? sample.roadLabel
      : null
    const id = label ? sample.roadLabelId ?? null : null
    if (label !== currentLabel || id !== currentId) {
      flush(i - 1)
      startIndex = label ? i : -1
      currentLabel = label
      currentId = id
      currentCreatedAt = sample.roadLabelCreatedAtMs ?? null
    }
  }
  flush(samples.length - 1)
  return labels.filter(Boolean)
}

function normalizeExistingLabels(labels, samples) {
  const valid = labels
    .map((label) => sanitizeLabel(label, samples))
    .filter(Boolean)
    .sort((a, b) => (a.createdAtMs ?? 0) - (b.createdAtMs ?? 0))
  let resolved = []

  for (const label of valid) {
    if (label.label === 'unpaved') {
      resolved = removeRangeFromLabels(resolved, samples, label.startIndex, label.endIndex)
      resolved.push(label)
      continue
    }

    const unpaved = resolved.filter((item) => item.label === 'unpaved')
    let chunks = [label]
    for (const item of unpaved) {
      chunks = removeRangeFromLabels(chunks, samples, item.startIndex, item.endIndex)
    }
    const paved = removeRangeFromLabels(
      resolved.filter((item) => item.label === 'paved'),
      samples,
      label.startIndex,
      label.endIndex,
    )
    resolved = [...unpaved, ...paved, ...chunks]
  }

  return resolved
    .map((label) => sanitizeLabel(label, samples))
    .filter(Boolean)
    .sort((a, b) => a.startIndex - b.startIndex || a.endIndex - b.endIndex)
}

function removeRangeFromLabels(labels, samples, startIndex, endIndex) {
  const next = []
  for (const label of labels) {
    if (label.endIndex < startIndex || label.startIndex > endIndex) {
      next.push(label)
      continue
    }
    if (label.startIndex < startIndex) {
      next.push(makeLabelFromRange(label, samples, label.startIndex, startIndex - 1))
    }
    if (label.endIndex > endIndex) {
      next.push(makeLabelFromRange(label, samples, endIndex + 1, label.endIndex))
    }
  }
  return next.filter(Boolean)
}

function sanitizeLabel(label, samples) {
  if (!label || (label.label !== 'paved' && label.label !== 'unpaved')) return null
  if (samples.length === 0) return null
  const startIndex = Math.max(0, Math.min(Number(label.startIndex), samples.length - 1))
  const endIndex = Math.max(0, Math.min(Number(label.endIndex), samples.length - 1))
  if (!Number.isFinite(startIndex) || !Number.isFinite(endIndex) || startIndex > endIndex) return null
  return makeLabelFromRange(label, samples, startIndex, endIndex)
}

function makeLabelFromRange(base, samples, startIndex, endIndex) {
  if (startIndex > endIndex) return null
  const start = samples[startIndex]
  const end = samples[endIndex]
  const id =
    base.startIndex === startIndex && base.endIndex === endIndex
      ? base.id
      : `${base.id}_${startIndex}_${endIndex}`
  return {
    ...base,
    id,
    startIndex,
    endIndex,
    startTime: Number(start.t),
    endTime: Number(end.t),
    startLat: Number(start.lat),
    startLon: Number(start.lon),
    endLat: Number(end.lat),
    endLon: Number(end.lon),
    sampleCount: endIndex - startIndex + 1,
    distanceM: segmentDistance(samples, startIndex, endIndex),
  }
}

async function appendLabelPoolEvent(action, label) {
  await fs.appendFile(
    LABEL_POOL_FILE,
    `${JSON.stringify({ action, ...label, eventAtMs: Date.now() })}\n`,
    'utf8',
  )
}

function segmentDistance(samples, startIndex, endIndex) {
  let total = 0
  for (let i = startIndex + 1; i <= endIndex; i++) {
    total += haversineM(samples[i - 1], samples[i])
  }
  return Math.round(total * 100) / 100
}

function haversineM(a, b) {
  const R = 6371000
  const toRad = (deg) => (Number(deg) * Math.PI) / 180
  const dLat = toRad(b.lat) - toRad(a.lat)
  const dLon = toRad(b.lon) - toRad(a.lon)
  const la1 = toRad(a.lat)
  const la2 = toRad(b.lat)
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)))
}

/**
 * multipart/form-data で以下の一連を受け取る:
 *   - id           (text)        : Recording.id（ディレクトリ名）
 *   - meta.json    (file)        : メタJSON
 *   - merged.json  (file)        : ブラウザ表示用JSON
 *   - gps.csv      (file, 任意)  : 生CSV
 *   - accel.csv    (file, 任意)  : 生CSV
 */
async function handleUpload(req, res) {
  const ct = req.headers['content-type'] ?? ''
  const boundaryMatch = /boundary=([^;]+)/i.exec(ct)
  if (!boundaryMatch) return badRequest(res, 'multipart boundary missing')
  const boundary = boundaryMatch[1].trim().replace(/^"|"$/g, '')

  uploadState.inProgress += 1
  uploadState.lastStartedAt = Date.now()
  try {
    const body = await readAll(req, 200 * 1024 * 1024) // 200MB hard cap
    const parts = parseMultipart(body, boundary)

    const idPart = parts.find((p) => p.name === 'id')
    const id = idPart ? idPart.data.toString('utf8').trim() : ''
    if (!isSafeName(id)) return badRequest(res, 'invalid id')

    const dir = path.join(DATA_ROOT, id)
    await fs.mkdir(dir, { recursive: true })

    for (const p of parts) {
      if (!p.filename) continue
      const filename = path.basename(p.filename)
      if (!isSafeName(filename)) continue
      await fs.writeFile(path.join(dir, filename), p.data)
    }

    uploadState.lastFinishedAt = Date.now()
    uploadState.lastFinishedId = id
    uploadState.totalUploaded += 1

    console.log(`uploaded: ${id} (${parts.filter((p) => p.filename).length} files)`)
    json(res, { ok: true, id })
  } finally {
    uploadState.inProgress = Math.max(0, uploadState.inProgress - 1)
  }
}

// ---- helpers ----

function json(res, obj) {
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(obj))
}

function notFound(res, msg) {
  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
  res.end(msg)
}

function badRequest(res, msg) {
  res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' })
  res.end(msg)
}

async function readJsonBody(req, maxBytes) {
  const raw = await readAll(req, maxBytes)
  try {
    return JSON.parse(raw.toString('utf8'))
  } catch {
    throw new Error('invalid json')
  }
}

function clampInt(value, min, max) {
  const n = Number.parseInt(value ?? '', 10)
  if (!Number.isFinite(n)) return min
  return Math.max(min, Math.min(max, n))
}

function isSafeName(s) {
  return !!s && /^[A-Za-z0-9._-]+$/.test(s)
}

function readAll(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let total = 0
    req.on('data', (c) => {
      total += c.length
      if (total > maxBytes) {
        reject(new Error('payload too large'))
        req.destroy()
        return
      }
      chunks.push(c)
    })
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

/**
 * 軽量 multipart/form-data パーサ（ライブラリなし）。
 * Buffer ベースで動き、各パートを {name, filename, data: Buffer} で返す。
 */
function parseMultipart(buf, boundary) {
  const dashBoundary = Buffer.from(`--${boundary}`)
  const crlf = Buffer.from('\r\n')
  const out = []

  let start = indexOfBuf(buf, dashBoundary, 0)
  if (start < 0) return out

  while (start >= 0) {
    let cursor = start + dashBoundary.length
    // closing boundary "--"
    if (buf[cursor] === 0x2d && buf[cursor + 1] === 0x2d) break
    if (buf[cursor] === 0x0d && buf[cursor + 1] === 0x0a) cursor += 2
    const headerEnd = indexOfBuf(buf, Buffer.from('\r\n\r\n'), cursor)
    if (headerEnd < 0) break
    const headers = buf.slice(cursor, headerEnd).toString('utf8')
    const dataStart = headerEnd + 4
    const nextBoundary = indexOfBuf(buf, dashBoundary, dataStart)
    if (nextBoundary < 0) break
    // strip trailing CRLF before boundary
    let dataEnd = nextBoundary
    if (buf[dataEnd - 2] === 0x0d && buf[dataEnd - 1] === 0x0a) dataEnd -= 2
    const data = buf.slice(dataStart, dataEnd)

    const disp = /content-disposition:\s*form-data;([^\r\n]*)/i.exec(headers)
    let name = ''
    let filename
    if (disp) {
      const nm = /name="([^"]*)"/i.exec(disp[1])
      const fn = /filename="([^"]*)"/i.exec(disp[1])
      if (nm) name = nm[1]
      if (fn) filename = fn[1]
    }
    out.push({ name, filename, data })
    start = nextBoundary
  }
  return out
}

function indexOfBuf(haystack, needle, from) {
  return haystack.indexOf(needle, from)
}
