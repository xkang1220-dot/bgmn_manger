/** 劳务协议工具函数 */

export function formatDateCN(dateStr?: string) {
  if (!dateStr) return '____年__月__日'
  const m = String(dateStr).match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (!m) return '____年__月__日'
  return `${m[1]}年${parseInt(m[2], 10)}月${parseInt(m[3], 10)}日`
}

export function amountToChinese(amount: string | number | null | undefined) {
  if (amount === '' || amount == null) return '【大写金额】'
  const num = Math.round(parseFloat(String(amount)) * 100) / 100
  if (isNaN(num)) return '【大写金额】'

  const digits = ['零', '壹', '贰', '叁', '肆', '伍', '陆', '柒', '捌', '玖']
  const units = ['', '拾', '佰', '仟']
  const bigUnits = ['', '万', '亿']

  function sectionToChinese(n: number) {
    let str = ''
    let zero = false
    for (let i = 0; i < 4 && n > 0; i++) {
      const d = n % 10
      if (d === 0) {
        if (!zero && str) {
          str = digits[0] + str
          zero = true
        }
      } else {
        str = digits[d] + units[i] + str
        zero = false
      }
      n = Math.floor(n / 10)
    }
    return str.replace(/零+$/, '').replace(/零+/g, '零')
  }

  const yuan = Math.floor(num)
  const jiao = Math.floor((num * 100) % 100)
  if (yuan === 0 && jiao === 0) return '零元整'

  let result = ''
  let y = yuan
  let bi = 0
  while (y > 0 || bi === 0) {
    const sec = y % 10000
    if (sec !== 0) {
      result = sectionToChinese(sec) + bigUnits[bi] + result
    } else if (result && !result.startsWith('零')) {
      result = '零' + result
    }
    y = Math.floor(y / 10000)
    bi++
    if (bi > 2) break
  }
  result = (result || '零') + '元'

  if (jiao === 0) {
    result += '整'
  } else {
    const j = Math.floor(jiao / 10)
    const f = jiao % 10
    if (j > 0) result += digits[j] + '角'
    else if (yuan > 0) result += '零'
    if (f > 0) result += digits[f] + '分'
  }
  return result
}

export function calcServiceMonths(start?: string, end?: string) {
  if (!start || !end) return ''
  const a = new Date(`${start}T00:00:00`)
  const b = new Date(`${end}T00:00:00`)
  if (isNaN(a.getTime()) || isNaN(b.getTime()) || b < a) return ''
  const months = (b.getFullYear() - a.getFullYear()) * 12 + (b.getMonth() - a.getMonth())
  const days = b.getDate() - a.getDate()
  if (months <= 0 && days >= 0) return days > 0 ? '不足1' : '0'
  return String(Math.max(1, months || 1))
}

export function generateRefNumber() {
  const year = new Date().getFullYear()
  const rand = Math.floor(Math.random() * 9000) + 1000
  return `HT-${year}-${rand}`
}

export function escapeHtml(str?: string | null) {
  if (!str) return ''
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

export function todayISO() {
  return new Date().toISOString().slice(0, 10)
}
