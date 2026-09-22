/**
 * 劳务协议 PDF 导出（移植自 contract-template，保留页边距 / 换页 / 页码修复）
 */
import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import type { LaborContractData } from './types'

export async function exportLaborAgreementPDF(
  sourceEl: HTMLElement,
  contractData: LaborContractData,
) {
  const PAGE_W = 794
  const MARGIN_X = 64
  const PDF_MARGIN_TOP_MM = 16
  const PDF_MARGIN_BOTTOM_MM = 18
  const PAGE_NUMBER_Y_MM = 297 - 10

  const root = document.createElement('div')
  root.id = 'contract-pdf-capture-root'
  root.setAttribute('data-pdf-export', '1')
  root.style.cssText = [
    'position:fixed',
    'left:0',
    'top:0',
    `width:${PAGE_W}px`,
    'background:#ffffff',
    'color:#000000',
    'opacity:0',
    'pointer-events:none',
    'z-index:2147483646',
    'box-sizing:border-box',
    "font-family:'Songti SC','Noto Serif SC','SimSun','宋体',serif",
    'font-size:12pt',
    'line-height:1.9',
  ].join(';')

  const row = document.createElement('div')
  row.style.cssText = 'display:flex;width:100%;box-sizing:border-box;'

  const leftPad = document.createElement('div')
  leftPad.style.cssText = `width:${MARGIN_X}px;flex-shrink:0;`

  const content = document.createElement('div')
  content.style.cssText = [
    'flex:1',
    'min-width:0',
    'box-sizing:border-box',
    "font-family:'Songti SC','Noto Serif SC','SimSun','宋体',serif",
    'font-size:12pt',
    'line-height:1.9',
    'color:#000',
  ].join(';')
  content.innerHTML = sourceEl.innerHTML

  // 缩进依赖 .doc-indent-spacer；预览 CSS 挂在 #labor-contract-preview 下，
  // 克隆到导出根节点后会丢宽度，必须全部写成内联样式
  const spacerStyle =
    'display:inline-block;width:2em;min-width:2em;max-width:2em;height:1em;vertical-align:baseline;overflow:hidden;'

  content.querySelectorAll('.doc-clause, .doc-indent').forEach((node) => {
    const el = node as HTMLElement
    el.style.paddingLeft = '0'
    el.style.textIndent = '0'
    el.style.marginLeft = '0'
    let spacer = el.querySelector('.doc-indent-spacer') as HTMLElement | null
    if (!spacer) {
      spacer = document.createElement('span')
      spacer.className = 'doc-indent-spacer'
      spacer.setAttribute('aria-hidden', 'true')
      el.insertBefore(spacer, el.firstChild)
    }
    spacer.style.cssText = spacerStyle
    // 空 span 在部分环境下会被压成 0 宽，塞入不可见占位
    if (!spacer.textContent) spacer.textContent = '\u00a0'
  })

  // 兜底：导出根内再声明一次，防止遗漏
  const styleTag = document.createElement('style')
  styleTag.textContent = `
    #contract-pdf-capture-root .doc-indent-spacer {
      display: inline-block !important;
      width: 2em !important;
      min-width: 2em !important;
      max-width: 2em !important;
      height: 1em !important;
      vertical-align: baseline !important;
      overflow: hidden !important;
    }
  `
  root.appendChild(styleTag)

  content.querySelectorAll('.doc-info-table').forEach((node) => {
    const table = node as HTMLElement
    table.style.cssText =
      'width:100%;border-collapse:collapse;margin:0 0 24px;font-size:12pt;'
    table.querySelectorAll('td').forEach((td) => {
      const cell = td as HTMLElement
      cell.style.border = '1px solid #000'
      cell.style.padding = '8px 12px'
      cell.style.verticalAlign = 'top'
    })
  })
  content.querySelectorAll('.doc-title').forEach((node) => {
    const el = node as HTMLElement
    el.style.cssText =
      'text-align:center;font-size:22pt;font-weight:700;letter-spacing:0.12em;margin:0 0 8px;line-height:1.4;'
  })
  content.querySelectorAll('.doc-title-en').forEach((node) => {
    const el = node as HTMLElement
    el.style.cssText =
      'text-align:center;font-size:10.5pt;font-weight:400;margin:0 0 28px;color:#222;'
  })
  content.querySelectorAll('.doc-section-title').forEach((node) => {
    const el = node as HTMLElement
    el.style.cssText =
      'text-align:center;font-size:14pt;font-weight:700;margin:28px 0 16px;letter-spacing:0.08em;'
  })
  content.querySelectorAll('.doc-article-title').forEach((node) => {
    const el = node as HTMLElement
    el.style.cssText = 'font-size:12pt;font-weight:700;margin:0 0 8px;'
  })
  content.querySelectorAll('.doc-p').forEach((node) => {
    const el = node as HTMLElement
    el.style.margin = el.style.margin || '0 0 10px'
    el.style.fontSize = '12pt'
    el.style.lineHeight = '1.9'
    el.style.textAlign = 'justify'
  })
  content.querySelectorAll('.doc-sign-block').forEach((node) => {
    const el = node as HTMLElement
    el.style.cssText = 'margin-top:56px;padding-top:28px;box-sizing:border-box;'
  })
  content.querySelectorAll('.doc-sign-grid').forEach((node) => {
    const el = node as HTMLElement
    el.style.cssText =
      'display:grid;grid-template-columns:1fr 1fr;gap:36px;margin-top:18px;align-items:stretch;'
  })
  content.querySelectorAll('.doc-sign-col').forEach((node) => {
    const el = node as HTMLElement
    el.style.cssText =
      'display:flex;flex-direction:column;min-height:200px;box-sizing:border-box;'
  })
  content.querySelectorAll('.doc-sign-col-body').forEach((node) => {
    const el = node as HTMLElement
    el.style.cssText = 'flex:1 1 auto;'
  })
  content.querySelectorAll('.doc-sign-seal').forEach((node) => {
    const el = node as HTMLElement
    el.style.cssText = 'margin:40px 0 8px;min-height:2.4em;font-size:12pt;line-height:1.9;'
  })
  content.querySelectorAll('.doc-sign-date').forEach((node) => {
    const el = node as HTMLElement
    el.style.cssText =
      'margin:auto 0 0;padding-top:12px;font-size:12pt;line-height:1.9;'
  })
  content.querySelectorAll('.doc-sign-col .doc-p').forEach((node) => {
    const el = node as HTMLElement
    if (!el.classList.contains('doc-sign-seal') && !el.classList.contains('doc-sign-date')) {
      el.style.marginBottom = '8px'
    }
  })

  const rightPad = document.createElement('div')
  rightPad.style.cssText = `width:${MARGIN_X}px;min-width:${MARGIN_X}px;flex-shrink:0;`
  rightPad.innerHTML = '&nbsp;'
  leftPad.innerHTML = '&nbsp;'
  leftPad.style.cssText = `width:${MARGIN_X}px;min-width:${MARGIN_X}px;flex-shrink:0;`

  row.appendChild(leftPad)
  row.appendChild(content)
  row.appendChild(rightPad)
  root.appendChild(row)
  document.body.appendChild(root)

  try {
    await new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r())))

    const canvas = await html2canvas(root, {
      scale: 2,
      useCORS: true,
      allowTaint: true,
      backgroundColor: '#ffffff',
      logging: false,
      width: PAGE_W,
      windowWidth: PAGE_W,
      scrollX: 0,
      scrollY: 0,
      onclone: (_doc, cloned) => {
        cloned.style.opacity = '1'
        cloned.style.left = '0'
        cloned.style.top = '0'
        cloned.querySelectorAll('.doc-indent-spacer').forEach((node) => {
          const el = node as HTMLElement
          el.style.display = 'inline-block'
          el.style.width = '2em'
          el.style.minWidth = '2em'
          el.style.maxWidth = '2em'
          el.style.height = '1em'
          el.style.verticalAlign = 'baseline'
          el.style.overflow = 'hidden'
          if (!el.textContent) el.textContent = '\u00a0'
        })
      },
    })

    const pdf = new jsPDF({
      orientation: 'portrait',
      unit: 'mm',
      format: 'a4',
      compress: true,
    })

    const pageWidth = 210
    const pageHeight = 297
    const contentHeightMm = pageHeight - PDF_MARGIN_TOP_MM - PDF_MARGIN_BOTTOM_MM
    const pxPerMm = canvas.width / pageWidth
    const idealPageHeightPx = Math.floor(contentHeightMm * pxPerMm)
    const minSlicePx = Math.floor(idealPageHeightPx * 0.55)

    const pageCanvas = document.createElement('canvas')
    const pageCtx = pageCanvas.getContext('2d')!
    pageCanvas.width = canvas.width

    const breakPoints = findCanvasPageBreaks(canvas, idealPageHeightPx, minSlicePx)
    const totalPages = breakPoints.length - 1

    for (let pageIndex = 0; pageIndex < totalPages; pageIndex++) {
      const sliceTop = breakPoints[pageIndex]
      const sliceBottom = breakPoints[pageIndex + 1]
      const sliceHeight = sliceBottom - sliceTop

      pageCanvas.height = sliceHeight
      pageCtx.fillStyle = '#ffffff'
      pageCtx.fillRect(0, 0, pageCanvas.width, pageCanvas.height)
      pageCtx.drawImage(
        canvas,
        0,
        sliceTop,
        canvas.width,
        sliceHeight,
        0,
        0,
        canvas.width,
        sliceHeight,
      )

      const imgData = pageCanvas.toDataURL('image/jpeg', 0.92)
      const sliceMm = Math.min((sliceHeight * pageWidth) / canvas.width, contentHeightMm)

      if (pageIndex > 0) pdf.addPage()
      pdf.addImage(imgData, 'JPEG', 0, PDF_MARGIN_TOP_MM, pageWidth, sliceMm, undefined, 'FAST')

      pdf.setFont('helvetica', 'normal')
      pdf.setFontSize(9)
      pdf.setTextColor(80, 80, 80)
      pdf.text(`${pageIndex + 1} / ${totalPages}`, pageWidth / 2, PAGE_NUMBER_Y_MM, {
        align: 'center',
      })
    }

    const safeName =
      (contractData.meta?.title || '劳务协议').replace(/[\\/:*?"<>|]/g, '_').trim() || '劳务协议'
    const ref = contractData.refNumber ? `_${contractData.refNumber}` : ''
    pdf.save(`${safeName}${ref}.pdf`)
  } finally {
    root.remove()
  }
}

function findCanvasPageBreaks(canvas: HTMLCanvasElement, idealPageHeightPx: number, minSlicePx: number) {
  const breaks = [0]
  let cursor = 0

  while (cursor < canvas.height - 1) {
    const remaining = canvas.height - cursor
    if (remaining <= idealPageHeightPx) {
      breaks.push(canvas.height)
      break
    }

    const idealEnd = cursor + idealPageHeightPx
    const safeEnd = findSafeBreakY(canvas, cursor, idealEnd, minSlicePx)
    breaks.push(safeEnd)
    cursor = safeEnd

    if (breaks.length > 200) {
      breaks.push(canvas.height)
      break
    }
  }

  return breaks
}

function findSafeBreakY(
  canvas: HTMLCanvasElement,
  startY: number,
  idealEnd: number,
  minSlicePx: number,
) {
  const hardEnd = Math.min(idealEnd, canvas.height)
  if (hardEnd >= canvas.height) return canvas.height

  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  const scale = canvas.width / 794
  const lookback = Math.max(Math.floor(100 * scale), 40)
  const minWhiteRun = Math.max(Math.floor(3 * scale), 2)
  const searchFrom = Math.max(startY + minSlicePx, hardEnd - lookback)

  let bestBreak = hardEnd
  let bestScore = -Infinity
  let runStart: number | null = null

  const considerRun = (from: number, to: number) => {
    const len = to - from + 1
    if (len < minWhiteRun) return
    const breakAt = Math.min(to + 1, hardEnd)
    if (breakAt <= startY + minSlicePx) return
    const closeness = 1 - (hardEnd - breakAt) / lookback
    const score = len * 8 + closeness * 120
    if (score > bestScore) {
      bestScore = score
      bestBreak = breakAt
    }
  }

  for (let y = searchFrom; y <= hardEnd; y++) {
    if (isCanvasRowMostlyWhite(ctx, y, canvas.width, scale)) {
      if (runStart === null) runStart = y
    } else if (runStart !== null) {
      considerRun(runStart, y - 1)
      runStart = null
    }
  }
  if (runStart !== null) considerRun(runStart, hardEnd)

  return bestBreak
}

function isCanvasRowMostlyWhite(
  ctx: CanvasRenderingContext2D,
  y: number,
  width: number,
  scale: number,
) {
  const step = Math.max(Math.floor(6 * scale), 4)
  const data = ctx.getImageData(0, y, width, 1).data
  let dark = 0
  let samples = 0
  for (let x = 0; x < width; x += step) {
    const i = x * 4
    if (data[i] < 248 || data[i + 1] < 248 || data[i + 2] < 248) dark++
    samples++
  }
  return samples > 0 && dark / samples <= 0.015
}
