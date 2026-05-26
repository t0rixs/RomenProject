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
