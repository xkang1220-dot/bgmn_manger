import type { LaborContractData } from './types'
import { amountToChinese, escapeHtml, formatDateCN } from './utils'

function renderIndentedParagraph(text: string) {
  return `<p class="doc-p doc-clause"><span class="doc-indent-spacer" aria-hidden="true">&nbsp;</span>${escapeHtml(text)}</p>`
}

function splitClauseParagraphs(content: string) {
  const text = String(content || '').replace(/\r\n/g, '\n').trim()
  if (!text) return []

  const byNewline = text.split(/\n+/).map((l) => l.trim()).filter(Boolean)
  if (byNewline.length > 1) return byNewline

  const parts = text
    .split(/(?=(?<!\d)\d+\.\d+\s)/)
    .map((l) => l.trim())
    .filter(Boolean)
  return parts.length ? parts : [text]
}

function renderFormalArticle(title: string, content: string) {
  const body = splitClauseParagraphs(content)
    .map((line) => renderIndentedParagraph(line))
    .join('')
  return `
    <div class="doc-article">
      <div class="doc-article-title">${escapeHtml(title)}</div>
      ${body}
    </div>
  `
}

/** 正式劳务协议预览 HTML */
export function renderFormalEmploymentPreview(data: LaborContractData) {
  const { partyA, partyB, meta, payment } = data
  const title = meta.title || '软件开发劳务协议'
  const titleEn = meta.titleEn || 'SOFTWARE DEVELOPMENT SERVICE AGREEMENT'
  const fee = payment?.total ? String(payment.total) : '________'
  const feeWords = data.amountInWords || amountToChinese(payment?.total)
  const project = data.projectName || '____________________'

  const cover = `
    <div class="doc-title">${escapeHtml(title)}</div>
    <div class="doc-title-en">${escapeHtml(titleEn)}</div>

    <table class="doc-info-table">
      <tr>
        <td class="doc-label">甲方（委托方）</td>
        <td class="doc-value">${escapeHtml(partyA.name || '____________________')}</td>
      </tr>
      <tr>
        <td class="doc-label">乙方（服务方）</td>
        <td class="doc-value">${escapeHtml(partyB.name || '____________________')}</td>
      </tr>
      <tr>
        <td class="doc-label">项目名称</td>
        <td class="doc-value">${escapeHtml(project)}</td>
      </tr>
      <tr>
        <td class="doc-label">合同金额</td>
        <td class="doc-value">人民币 ${escapeHtml(fee)} 元（大写：${escapeHtml(feeWords)}）</td>
      </tr>
      <tr>
        <td class="doc-label">签订日期</td>
        <td class="doc-value">${escapeHtml(formatDateCN(data.signDate || data.effectiveDate))}</td>
      </tr>
    </table>

    <div class="doc-section-title">合同主体</div>
    <p class="doc-p"><strong>甲方（委托方）</strong>　${escapeHtml(partyA.name || '____________________')}</p>
    <p class="doc-p">社会统一信用代码　${escapeHtml(partyA.creditCode || '____________________')}</p>
    <p class="doc-p">法定代表人/授权代表　${escapeHtml(partyA.legalRep || '____________________')}</p>
    <p class="doc-p"><strong>乙方（服务方）</strong></p>
    <p class="doc-p">姓名：${escapeHtml(partyB.name || '____________________')}</p>
    <p class="doc-p">身份证号　${escapeHtml(partyB.idNumber || '____________________')}</p>
  `

  const preamble = renderIndentedParagraph(
    '鉴于甲方拟委托乙方提供软件开发及相关技术服务，乙方具备完成相应服务的能力。双方依据《中华人民共和国民法典》及相关法律法规，在平等、自愿、诚实信用的基础上，就本项目合作事宜达成如下协议，共同遵照执行。',
  )

  const clauses = (data.clauses || [])
    .filter((c) => c.enabled)
    .map((c) => renderFormalArticle(c.title, c.content))
    .join('')

  const sign = `
    <div class="doc-sign-block">
      <div class="doc-section-title doc-sign-title">签署页</div>
      <p class="doc-p doc-clause"><span class="doc-indent-spacer" aria-hidden="true">&nbsp;</span>（以下无正文，为《${escapeHtml(title)}》签署页。）</p>
      <div class="doc-sign-grid">
        <div class="doc-sign-col">
          <div class="doc-sign-col-body">
            <p class="doc-p"><strong>甲方（盖章）：</strong></p>
            <p class="doc-p">${escapeHtml(partyA.name || '____________________')}</p>
            <p class="doc-p doc-sign-seal">（公章处）</p>
          </div>
          <p class="doc-p doc-sign-date">日期：${escapeHtml(formatDateCN(data.signDate || data.effectiveDate))}</p>
        </div>
        <div class="doc-sign-col">
          <div class="doc-sign-col-body">
            <p class="doc-p"><strong>乙方（签名）：</strong></p>
            <p class="doc-p">姓名：${escapeHtml(partyB.name || '____________________')}</p>
            <p class="doc-p">身份证号：${escapeHtml(partyB.idNumber || '____________________')}</p>
            <p class="doc-p doc-sign-seal">签名：____________________</p>
          </div>
          <p class="doc-p doc-sign-date">日期：_______年____月____日</p>
        </div>
      </div>
    </div>
  `

  return `<div class="doc-formal">${cover}${preamble}${clauses}${sign}</div>`
}
