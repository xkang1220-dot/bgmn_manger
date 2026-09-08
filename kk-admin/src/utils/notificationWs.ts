import { ElNotification } from 'element-plus'
import type { NotificationItem } from '@/api/notification'

export type NotificationPushPayload = {
  type?: string
  id?: number
  title?: string
  content?: string
  bizType?: string
  bizId?: number
  link?: string
  readFlag?: number
}

type ConnectOptions = {
  getToken: () => string | null | undefined
  onPush: (item: NotificationPushPayload) => void
  onOpen?: (item: NotificationPushPayload) => void
}

function buildWsUrl(token: string) {
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${proto}//${window.location.host}/ws/notification?token=${encodeURIComponent(token)}`
}

export function createNotificationSocket(options: ConnectOptions) {
  let socket: WebSocket | null = null
  let closedByUser = false
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let pingTimer: ReturnType<typeof setInterval> | null = null
  let attempt = 0
  const seenIds = new Set<number>()

  function clearTimers() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (pingTimer) {
      clearInterval(pingTimer)
      pingTimer = null
    }
  }

  function scheduleReconnect() {
    if (closedByUser) return
    const delay = Math.min(30000, 1000 * 2 ** Math.min(attempt, 4))
    attempt += 1
    reconnectTimer = setTimeout(connect, delay)
  }

  function startPing() {
    if (pingTimer) clearInterval(pingTimer)
    pingTimer = setInterval(() => {
      if (socket?.readyState === WebSocket.OPEN) {
        socket.send('ping')
      }
    }, 25000)
  }

  function handleMessage(raw: string) {
    if (!raw || raw === 'pong') return
    let data: NotificationPushPayload
    try {
      data = JSON.parse(raw)
    } catch {
      return
    }
    if (data.type && data.type !== 'notification') return
    if (data.id != null) {
      const id = Number(data.id)
      if (seenIds.has(id)) return
      seenIds.add(id)
      data.id = id
      if (seenIds.size > 200) {
        const first = seenIds.values().next().value
        if (first != null) seenIds.delete(first)
      }
    }
    options.onPush(data)
    ElNotification({
      title: data.title || '新通知',
      message: data.content || '',
      position: 'top-right',
      duration: 5000,
      onClick: () => options.onOpen?.(data),
    })
  }

  function bindSocket(next: WebSocket) {
    socket = next
    next.onopen = () => {
      if (socket !== next || closedByUser) return
      attempt = 0
      startPing()
    }
    next.onmessage = (ev) => {
      if (socket !== next) return
      if (typeof ev.data === 'string') handleMessage(ev.data)
    }
    next.onerror = () => {
      if (socket !== next) return
      try {
        next.close()
      } catch {
        /* ignore */
      }
    }
    next.onclose = () => {
      if (socket === next) {
        socket = null
        clearTimers()
        scheduleReconnect()
      }
    }
  }

  function connect() {
    if (closedByUser) return
    clearTimers()
    const token = options.getToken()
    if (!token) {
      scheduleReconnect()
      return
    }
    if (socket) {
      const prev = socket
      socket = null
      try {
        prev.onclose = null
        prev.close()
      } catch {
        /* ignore */
      }
    }
    try {
      bindSocket(new WebSocket(buildWsUrl(token)))
    } catch {
      scheduleReconnect()
    }
  }

  function disconnect() {
    closedByUser = true
    clearTimers()
    const current = socket
    socket = null
    if (current) {
      try {
        current.onclose = null
        current.close()
      } catch {
        /* ignore */
      }
    }
  }

  connect()

  return { disconnect }
}

export function toNoticeItem(payload: NotificationPushPayload): NotificationItem | null {
  if (payload.id == null) return null
  return {
    id: payload.id,
    userId: 0,
    title: payload.title || '',
    content: payload.content,
    bizType: payload.bizType,
    bizId: payload.bizId,
    link: payload.link,
    readFlag: payload.readFlag ?? 0,
  }
}
