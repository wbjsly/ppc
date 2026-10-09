/**
 * 税码批量导入解析与模板（1.6.3，design D5）。
 * 8 列：税码编号,税率值,生效日期,失效日期,适用范围,政策文号,计税方式,税率类型
 * 规则沿 rate-import 同套：BOM 剥离 / 换行归一 / Tab 或逗号探测 / 跳表头·#说明行·空行 /
 *      前 6 列必需（后两列可缺省回填 GENERAL/STANDARD），不足标 _short 行级失败
 * rate-import.js 源码零改动（两域列结构不同，各自实现）。
 */

const HEADER_FIRST = /税码编号|taxCode|TAX_CODE/i

/** @returns {{rows: Array, skipped: number}} rowNo = 源文本物理行号 */
export function parseTaxRows(text) {
  if (!text || !text.trim()) return { rows: [], skipped: 0 }
  const clean = text.replace(/^﻿/, '').replace(/\r\n?/g, '\n')
  const lines = clean.split('\n')
  const sep = lines.find(l => l.trim() && !l.trim().startsWith('#'))?.includes('\t') ? '\t' : ','
  const rows = []
  let skipped = 0
  lines.forEach((line, idx) => {
    const rowNo = idx + 1
    // 不可整体 trim（会吃掉空列行尾分隔符）——仅判空与注释
    if (!line.trim() || line.trim().startsWith('#')) return
    const cells = line.split(sep).map(c => c.trim())
    if (HEADER_FIRST.test(cells[0] || '')) return // 表头
    if (cells.length < 6) {
      // 前 6 列必需；计税方式/税率类型可缺省
      rows.push({ rowNo, taxCode: cells[0] || '', taxRate: cells[1] || '',
        effectiveDate: cells[2] || '', expireDate: cells[3] || '',
        scope: cells[4] || '', policyNo: cells[5] || '',
        calcType: cells[6] || '', rateKind: cells[7] || '', _short: true })
      skipped++
      return
    }
    rows.push({
      rowNo,
      taxCode: (cells[0] || '').toUpperCase(),
      taxRate: cells[1] || '',
      effectiveDate: cells[2] || '',
      expireDate: cells[3] || '',
      scope: (cells[4] || '').toUpperCase(),
      policyNo: (cells[5] || '').trim(),
      calcType: (cells[6] || '').toUpperCase(),
      rateKind: (cells[7] || '').toUpperCase()
    })
  })
  return { rows, skipped }
}

/** 下载模板 CSV（BOM 头保证中文 Excel 兼容） */
export function downloadTaxTemplate() {
  const header = '税码编号,税率值,生效日期,失效日期,适用范围,政策文号,计税方式,税率类型'
  const comments = [
    '# 税码批量导入模板（FR-4.1-3-3）',
    '# 税码编号：2~32 位大写字母/数字/短横线（如 VAT-13），创建后不可改（C-4.1-01）',
    '# 税率值：0~100，4 位小数；0 仅允许 税率类型=ZERO/EXEMPT',
    '# 日期：yyyy-MM-dd，失效日 ≥ 生效日；同税码区间须首尾衔接（BR-4.1-17）',
    '# 适用范围：DOMESTIC 国内 / EXPORT 出口 / EXEMPT 免税',
    '# 政策文号：可留空（页面整批公共值回填），C-4.1-04 必附',
    '# 计税方式：GENERAL / SIMPLIFIED / DIFFERENTIAL（缺省 GENERAL）',
    '# 税率类型：STANDARD / LOW / ZERO / EXEMPT（缺省 STANDARD）'
  ]
  const sample = 'VAT-13,13,2027-01-01,2027-06-30,DOMESTIC,税总公告2027年第1号,GENERAL,STANDARD'
  const csv = '﻿' + [header, ...comments, sample].join('\r\n') + '\r\n'
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = '税码批量导入模板.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}

/** 结果报告 CSV 下载 */
export function downloadTaxReport(details) {
  const header = '行号,税码编号,结果,原因'
  const lines = details.map(d =>
    [d.rowNo, d.taxCode, d.result, (d.reason || '').replace(/[",\n]/g, ' ')].join(','))
  const csv = '﻿' + [header, ...lines].join('\r\n') + '\r\n'
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `税码导入结果报告_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}
