#!/usr/bin/env node

import http from 'node:http'
import { randomUUID } from 'node:crypto'
import { spawn } from 'node:child_process'

const host = process.env.CLAUDE_DISCOVERY_BRIDGE_HOST ?? '127.0.0.1'
const port = Number(process.env.CLAUDE_DISCOVERY_BRIDGE_PORT ?? '8787')
const command = process.env.CLAUDE_DISCOVERY_COMMAND ?? 'claude'
const token = process.env.CLAUDE_DISCOVERY_BRIDGE_TOKEN ?? ''
const maxBodyBytes = 64 * 1024
const maxOutputBytes = positiveNumber(process.env.CLAUDE_DISCOVERY_MAX_OUTPUT_BYTES, 256 * 1024)
const maxTimeoutSeconds = positiveNumber(process.env.CLAUDE_DISCOVERY_MAX_TIMEOUT_SECONDS, 300)

let activeRequest = null

function positiveNumber(value, fallback) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : fallback
}

function log(event, payload = {}) {
  console.log(JSON.stringify({ ts: new Date().toISOString(), event, ...payload }))
}

function json(res, status, payload) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(payload))
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > maxBodyBytes) {
        reject(new Error('request body too large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function signalProcessGroup(child, signal) {
  if (!child.pid) return
  try {
    process.kill(-child.pid, signal)
  } catch {
    try {
      child.kill(signal)
    } catch {
      // The process may already be gone.
    }
  }
}

function runClaude(prompt, requestedTimeoutSeconds, requestId) {
  return new Promise((resolve, reject) => {
    const startedAt = Date.now()
    const timeoutSeconds = Math.min(positiveNumber(requestedTimeoutSeconds, 300), maxTimeoutSeconds)
    const child = spawn(command, ['-p', prompt], {
      detached: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    })
    let stdout = ''
    let stderr = ''
    let stdoutBytes = 0
    let stderrBytes = 0
    let settled = false

    function finish(fn) {
      if (settled) return
      settled = true
      clearTimeout(timer)
      fn()
    }

    function terminate() {
      signalProcessGroup(child, 'SIGTERM')
      setTimeout(() => signalProcessGroup(child, 'SIGKILL'), 1000).unref()
    }

    const timer = setTimeout(() => {
      terminate()
      finish(() => reject(new Error(`Claude CLI timeout after ${timeoutSeconds}s`)))
    }, timeoutSeconds * 1000)

    child.stdout.on('data', (chunk) => {
      stdoutBytes += chunk.length
      if (stdoutBytes > maxOutputBytes) {
        terminate()
        finish(() => reject(new Error(`Claude CLI output exceeded ${maxOutputBytes} bytes`)))
        return
      }
      stdout += chunk.toString('utf8')
    })

    child.stderr.on('data', (chunk) => {
      stderrBytes += chunk.length
      if (stderrBytes <= maxOutputBytes) {
        stderr += chunk.toString('utf8')
      }
    })

    child.on('spawn', () => {
      log('claude.spawn', { requestId, pid: child.pid, timeoutSeconds, promptBytes: Buffer.byteLength(prompt, 'utf8') })
    })

    child.on('error', (error) => {
      finish(() => reject(new Error(`Claude CLI start failed: ${error.message}`)))
    })

    child.on('close', (code, signal) => {
      const elapsedMs = Date.now() - startedAt
      finish(() => {
        if (code === 0) {
          log('claude.finish', { requestId, pid: child.pid, elapsedMs, stdoutBytes, stderrBytes })
          resolve(stdout)
          return
        }
        const status = signal ? `signal=${signal}` : `exit=${code}`
        reject(new Error(`Claude CLI failed (${status}): ${stderr || stdout}`.trim()))
      })
    })
  })
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    json(res, 200, { status: 'UP', command })
    return
  }
  if (req.method !== 'POST' || req.url !== '/discover') {
    json(res, 404, { error: 'not found' })
    return
  }
  if (token && req.headers['x-claude-discovery-token'] !== token) {
    json(res, 401, { error: 'unauthorized' })
    return
  }
  if (activeRequest) {
    json(res, 429, {
      error: `Claude bridge is busy. activeRequestId=${activeRequest.requestId} elapsedMs=${Date.now() - activeRequest.startedAt}`,
    })
    return
  }

  const requestId = randomUUID()
  try {
    const body = JSON.parse(await readBody(req))
    const prompt = typeof body.prompt === 'string' ? body.prompt : ''
    const timeoutSeconds = Number(body.timeoutSeconds ?? 300)
    if (!prompt.trim()) {
      json(res, 400, { error: 'prompt is required' })
      return
    }
    if (activeRequest) {
      json(res, 429, {
        error: `Claude bridge is busy. activeRequestId=${activeRequest.requestId} elapsedMs=${Date.now() - activeRequest.startedAt}`,
      })
      return
    }
    activeRequest = { requestId, startedAt: Date.now() }
    log('discover.start', {
      requestId,
      requestedTimeoutSeconds: timeoutSeconds,
      maxTimeoutSeconds,
      promptBytes: Buffer.byteLength(prompt, 'utf8'),
    })
    const output = await runClaude(prompt, timeoutSeconds, requestId)
    json(res, 200, { output })
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    const status = message.includes('timeout') ? 504 : 503
    log('discover.error', { requestId, status, message })
    json(res, status, { error: message })
  } finally {
    if (activeRequest?.requestId === requestId) {
      activeRequest = null
    }
  }
})

server.listen(port, host, () => {
  log('bridge.listen', { url: `http://${host}:${port}`, command, maxTimeoutSeconds, maxOutputBytes })
})
