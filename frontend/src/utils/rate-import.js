/**
 * 批量导入行解析器（design add-exchange-rate-batch D1，零依赖）。
 * 模板固定 7 列：基础币种,报价币种,汇率类型,生效日期,失效日期,汇率值,来源文件编号
 * 规则：BOM 剥离 / 换行归一 / Tab 或逗号探测 / 跳过空行·#说明行·表头 /
 *      列数 <7 该行标错、>7 截断标警告、字段不含逗号（模板约定，无引号转义）
 */

const HEADER_FIRST = /基础币种|baseCcy|BASE_CCY/i

/**
 * @returns {{rows: Array<{rowNo:number, baseCcy:string, quoteCcy:string, rateType:string,
 *   effectiveDate:string, expireDate:string, rate:any, sourceFileNo:string}>, skipped:number}}
 * rowNo = 源文本物理行号（含表头/注释计数，便于对照源文件）
 */
export function parseRows(text) {
  if (!text || !text.trim()) return { rows: [], skipped: 0 }
  const clean = text.replace(/^﻿/, '').replace(/\r\n?/g, '\n')
  const lines = clean.split('\n')
  const sep = lines.find(l => l.trim() && !l.trim().startsWith('#'))?.includes('\t') ? '\t' : ','
  const rows = []
  let skipped = 0
  lines.forEach((line, idx) => {
    const rowNo = idx + 1
    // 注意：不可对 line 整体 trim（会吃掉空第 7 列的行尾分隔符）——仅判空与注释
    if (!line.trim() || line.trim().startsWith('#')) return
    const cells = line.split(sep).map(c => c.trim())
    if (HEADER_FIRST.test(cells[0] || '')) return // 表头
    if (cells.length < 6) {
      // 前 6 列必需；第 7 列（来源文件编号）可缺省 = 空（公共值回填/校验层拦截）
      rows.push({ rowNo, baseCcy: cells[0] || '', quoteCcy: cells[1] || '', rateType: cells[2] || '',
        effectiveDate: cells[3] || '', expireDate: cells[4] || '', rate: cells[5] || '',
        sourceFileNo: cells[6] || '', _short: true })
      skipped++
      return
    }
    rows.push({
      rowNo,
      baseCcy: (cells[0] || '').toUpperCase(),
      quoteCcy: (cells[1] || '').toUpperCase(),
      rateType: (cells[2] || '').toUpperCase(),
      effectiveDate: cells[3] || '',
      expireDate: cells[4] || '',
      rate: cells[5] || '',
      sourceFileNo: (cells[6] || '').trim()
    })
  })
  return { rows, skipped }
}

/** 下载模板 CSV（BOM 头保证中文 Excel 兼容） */
export function downloadTemplate() {
  const header = '基础币种,报价币种,汇率类型,生效日期,失效日期,汇率值,来源文件编号'
  const comments = [
    '# 汇率批量导入模板（FR-4.1-3-3）',
    '# 币种：3 位大写 ISO 4217（CNY/USD/EUR/JPY...），基础≠报价',
    '# 类型：MIDDLE 中间价 / BUY 买入价 / SELL 卖出价',
    '# 日期：yyyy-MM-dd，失效日 ≥ 生效日；同币对×类型区间须首尾衔接（BR-4.1-17）',
    '# 汇率值：> 0，6 位小数精度',
    '# 来源文件编号：可留空（页面整批公共值回填），C-4.1-04 必附'
  ]
  const sample = 'CNY,USD,MIDDLE,2027-01-01,2027-06-30,7.05,央行公告2027-01'
  const csv = '﻿' + [header, ...comments, sample].join('\r\n') + '\r\n'
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = '汇率批量导入模板.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}

/** 结果报告 CSV 下载 */
export function downloadReport(details) {
  const header = '行号,币对,结果,原因'
  const lines = details.map(d => [d.rowNo, d.baseQuote, d.result, (d.reason || '').replace(/[",\n]/g, ' ')].join(','))
  const csv = '﻿' + [header, ...lines].join('\r\n') + '\r\n'
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `汇率导入结果报告_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}
