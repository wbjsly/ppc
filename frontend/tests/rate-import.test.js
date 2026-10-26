import test from 'node:test'
import assert from 'node:assert/strict'
import { parseRows } from '../src/utils/rate-import.js'

const DATA_ROW = 'CNY,USD,MIDDLE,2027-01-01,2027-06-30,7.05,央行公告2027-01'

const FULL = {
  rowNo: 1,
  baseCcy: 'CNY',
  quoteCcy: 'USD',
  rateType: 'MIDDLE',
  effectiveDate: '2027-01-01',
  expireDate: '2027-06-30',
  rate: '7.05',
  sourceFileNo: '央行公告2027-01'
}

test('空输入：空串 / 纯空白 / undefined / null 均返回空结果', () => {
  assert.deepEqual(parseRows(''), { rows: [], skipped: 0 })
  assert.deepEqual(parseRows('   '), { rows: [], skipped: 0 })
  assert.deepEqual(parseRows(' \n\t \r\n '), { rows: [], skipped: 0 })
  assert.deepEqual(parseRows(undefined), { rows: [], skipped: 0 })
  assert.deepEqual(parseRows(null), { rows: [], skipped: 0 })
})

test('仅 BOM 字符（U+FEFF）也视为空输入', () => {
  assert.deepEqual(parseRows('\uFEFF'), { rows: [], skipped: 0 })
})

test('剥离开头 BOM：BOM 不出现在首个字段里', () => {
  const { rows } = parseRows('\uFEFF' + DATA_ROW)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].baseCcy, 'CNY')
  assert.ok(!rows[0].baseCcy.includes('\uFEFF'))
  assert.deepEqual(rows[0], FULL)
})

test('CRLF 归一为 LF：回车符不残留、行号连续', () => {
  const { rows } = parseRows('CNY,USD,MIDDLE,2027-01-01,2027-06-30,7.05,F1\r\nUSD,JPY,BUY,2027-02-01,2027-03-01,150.5,F2\r\n')
  assert.equal(rows.length, 2)
  assert.deepEqual(rows.map(r => r.rowNo), [1, 2])
  assert.equal(rows[1].baseCcy, 'USD')
  assert.equal(rows[1].rate, '150.5')
  for (const row of rows) {
    for (const [k, v] of Object.entries(row)) {
      if (typeof v === 'string') assert.ok(!v.includes('\r'), k + ' 残留回车符')
    }
  }
})

test('纯 CR 换行同样归一为 LF', () => {
  const { rows, skipped } = parseRows('CNY,USD,MIDDLE,2027-01-01,2027-06-30,7.05,F1\rEUR,USD,SELL,2027-02-01,2027-03-01,1.08,F2\r')
  assert.equal(skipped, 0)
  assert.deepEqual(rows.map(r => r.rowNo), [1, 2])
  assert.equal(rows[0].sourceFileNo, 'F1')
  assert.equal(rows[1].rateType, 'SELL')
})

test('Tab 分隔探测：首条有效行含制表符时整份文本按 Tab 切分', () => {
  const text = 'CNY\tUSD\tMIDDLE\t2027-01-01\t2027-06-30\t7.05\t央行公告,2027-01\nUSD\tJPY\tBUY\t2027-02-01\t2027-03-01\t150.5\t'
  const { rows, skipped } = parseRows(text)
  assert.equal(skipped, 0)
  assert.equal(rows.length, 2)
  assert.deepEqual(rows[0], { ...FULL, sourceFileNo: '央行公告,2027-01' })
  assert.equal(rows[1].quoteCcy, 'JPY')
  assert.equal(rows[1].sourceFileNo, '')
})

test('逗号分隔探测：不含 Tab 时按逗号切分（空第 7 列的 7 列行不算 _short）', () => {
  const { rows, skipped } = parseRows(DATA_ROW + '\nUSD,JPY,BUY,2027-02-01,2027-03-01,150.5,')
  assert.equal(skipped, 0)
  assert.equal(rows.length, 2)
  assert.deepEqual(rows[0], FULL)
  assert.equal(rows[1].sourceFileNo, '')
  assert.ok(!('_short' in rows[1]))
})

test('探测分隔符时跳过注释行：注释里的制表符不影响后续逗号切分', () => {
  const { rows } = parseRows('# 说明\t带制表符' + '\n' + DATA_ROW)
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0], { ...FULL, rowNo: 2 })
})

test('注释行跳过：不计 skipped，但占据物理行号', () => {
  const { rows, skipped } = parseRows('# 注释一\n# 注释二\n  # 缩进注释\n' + DATA_ROW)
  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].rowNo, 4)
  assert.deepEqual(rows[0], { ...FULL, rowNo: 4 })
})

test('空行与纯空白行跳过：不计 skipped，但占据物理行号', () => {
  const { rows, skipped } = parseRows('\n   \n\t\n' + DATA_ROW + '\n\nUSD,JPY,BUY,2027-02-01,2027-03-01,150.5,F2')
  assert.equal(skipped, 0)
  assert.deepEqual(rows.map(r => r.rowNo), [4, 6])
  assert.equal(rows[0].baseCcy, 'CNY')
  assert.equal(rows[1].sourceFileNo, 'F2')
})

test('中文表头跳过：首列匹配 /基础币种/i 的行不产出、不改 skipped', () => {
  const { rows, skipped } = parseRows('基础币种,报价币种,汇率类型,生效日期,失效日期,汇率值,来源文件编号\n' + DATA_ROW)
  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0], { ...FULL, rowNo: 2 })
})

test('英文表头跳过：baseCcy 与 BASE_CCY 均被识别（大小写不敏感）', () => {
  const text = 'baseCcy,quoteCcy,rateType,effectiveDate,expireDate,rate,sourceFileNo\n'
    + 'BASE_CCY,QUOTE_CCY,RATE_TYPE,EFFECTIVE_DATE,EXPIRE_DATE,RATE,SOURCE\n'
    + DATA_ROW
  const { rows, skipped } = parseRows(text)
  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0], { ...FULL, rowNo: 3 })
})

test('物理行号：注释行 + 空行 + 表头之后，数据行 rowNo = 4', () => {
  const text = '# 导出说明\n\n基础币种,报价币种,汇率类型,生效日期,失效日期,汇率值,来源文件编号\n' + DATA_ROW
  const { rows, skipped } = parseRows(text)
  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].rowNo, 4)
})

test('6 列（无第 7 列）：sourceFileNo 为空串且不算 _short、不计 skipped', () => {
  const { rows, skipped } = parseRows('CNY,USD,MIDDLE,2027-01-01,2027-06-30,7.05')
  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].sourceFileNo, '')
  assert.ok(!('_short' in rows[0]))
  assert.deepEqual(rows[0], { ...FULL, sourceFileNo: '' })
})

test('5 列（列数 <6）：产出 _short 行、字段不做大写归一、skipped 计 1', () => {
  const { rows, skipped } = parseRows(' cny , usd , middle , 2027-01-01 , 2027-06-30 ')
  assert.equal(skipped, 1)
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0], {
    rowNo: 1,
    baseCcy: 'cny',
    quoteCcy: 'usd',
    rateType: 'middle',
    effectiveDate: '2027-01-01',
    expireDate: '2027-06-30',
    rate: '',
    sourceFileNo: '',
    _short: true
  })
})

test('列数 <6 的短行与正常行混合：skipped 只统计短行数', () => {
  const { rows, skipped } = parseRows('CNY,USD,MIDDLE\n' + DATA_ROW + '\nEUR,USD,SELL,2027-03-01,2027-04-01,1.08')
  assert.equal(skipped, 1)
  assert.deepEqual(rows.map(r => r.rowNo), [1, 2, 3])
  assert.equal(rows[0]._short, true)
  assert.ok(!('_short' in rows[1]))
  assert.equal(rows[1].baseCcy, 'CNY')
  assert.ok(!('_short' in rows[2]))
  assert.equal(rows[2].effectiveDate, '2027-03-01')
})

test('超过 7 列：多余列被忽略，不产生 _short、不计 skipped、无额外字段', () => {
  const { rows, skipped } = parseRows('CNY,USD,MIDDLE,2027-01-01,2027-06-30,7.05,央行公告2027-01,多余,再多余')
  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0], FULL)
  assert.deepEqual(Object.keys(rows[0]).sort(), [
    'baseCcy', 'effectiveDate', 'expireDate', 'quoteCcy', 'rate', 'rateType', 'rowNo', 'sourceFileNo'
  ])
})

test('大写归一只作用于前三列：baseCcy/quoteCcy/rateType 转大写，日期与汇率保持原样', () => {
  const { rows } = parseRows('cny,usd,middle,2027-01-0a,2027-06-3b,7.05abc,f1x')
  assert.equal(rows[0].baseCcy, 'CNY')
  assert.equal(rows[0].quoteCcy, 'USD')
  assert.equal(rows[0].rateType, 'MIDDLE')
  assert.equal(rows[0].effectiveDate, '2027-01-0a')
  assert.equal(rows[0].expireDate, '2027-06-3b')
  assert.equal(rows[0].rate, '7.05abc')
  assert.equal(rows[0].sourceFileNo, 'f1x')
})

test('每个单元格都会被 trim（含首尾空格与第 7 列）', () => {
  const { rows } = parseRows('  CNY  , USD ,  middle , 2027-01-01 , 2027-06-30 , 7.05 ,  央行公告2027-01   ')
  assert.deepEqual(rows[0], FULL)
})
