/**
 * parseTaxRows 单元测试（1.6.3，design D5）
 * 8 列：税码编号,税率值,生效日期,失效日期,适用范围,政策文号,计税方式,税率类型
 *
 * 覆盖：空入参 / BOM 剥离 / 换行归一 / Tab 与逗号探测（含 # 注释行先于数据行）/
 *      表头·注释·空行跳过且占物理行号 / 物理行号 / 5 列 _short / 6 列缺省后两列 /
 *      8 列完整 / 四列大写归一 / policyNo trim 不转大写 / 超 8 列忽略
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { parseTaxRows } from '../src/utils/tax-import.js'

const HEADER = '税码编号,税率值,生效日期,失效日期,适用范围,政策文号,计税方式,税率类型'

/** 按列拼一行（默认逗号分隔） */
const csv = (cells, sep = ',') => cells.join(sep)

/** 8 列完整行的规范单元格 */
const FULL = ['VAT-13', '13.0000', '2027-01-01', '2027-06-30', 'DOMESTIC', '税总公告2027年第1号', 'GENERAL', 'STANDARD']

// ---------------------------------------------------------------- 空入参

test('空字符串返回空结果且 skipped 为 0', () => {
  assert.deepEqual(parseTaxRows(''), { rows: [], skipped: 0 })
})

test('纯空白（空格 / 制表符 / 换行）返回空结果', () => {
  assert.deepEqual(parseTaxRows('   '), { rows: [], skipped: 0 })
  assert.deepEqual(parseTaxRows('\t \t'), { rows: [], skipped: 0 })
  assert.deepEqual(parseTaxRows('\n\r\n\n'), { rows: [], skipped: 0 })
})

test('undefined 与 null 返回空结果而非抛异常', () => {
  assert.deepEqual(parseTaxRows(undefined), { rows: [], skipped: 0 })
  assert.deepEqual(parseTaxRows(null), { rows: [], skipped: 0 })
})

// ---------------------------------------------------------------- 编码归一

test('BOM 被剥离：首行数据行首列不含 U+FEFF', () => {
  const text = '\uFEFF' + [csv(FULL), csv(['VAT-06', '6.0000', '2027-02-01', '2027-02-28', 'DOMESTIC', '公告2', 'GENERAL', 'LOW'])].join('\n')

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 0)
  assert.equal(rows.length, 2)
  // 注：BOM 由"剥离开头 U+FEFF"与"逐列 trim"双重兜底（U+FEFF 属 ES trim 空白集），
  // 本用例锁的是输出行为，不锁具体实现行
  assert.equal(rows[0].taxCode, 'VAT-13')
  assert.equal(rows[0].taxCode.length, 6)
  assert.equal(rows[1].taxCode, 'VAT-06')
})

test('CRLF 换行归一：行号按 \\n 计数且字段不含回车', () => {
  const text = [HEADER, csv(FULL), csv(['VAT-06', '6.0000', '2027-02-01', '2027-02-28', 'DOMESTIC', '公告2', 'GENERAL', 'LOW'])].join('\r\n')

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 0)
  assert.equal(rows.length, 2)
  // 表头占第 1 行，第一条数据落在第 2 行
  assert.equal(rows[0].rowNo, 2)
  assert.equal(rows[1].rowNo, 3)
  // 注：单元格 trim 本身也会吃掉行尾 \r，此断言与归一互为印证而非唯一判别手段
  for (const row of rows) {
    for (const value of Object.values(row)) {
      assert.ok(typeof value === 'number' || !value.includes('\r'), `字段残留回车: ${JSON.stringify(value)}`)
    }
  }
  assert.equal(rows[0].rateKind, 'STANDARD')
})

test('纯 CR 换行：同样归一并按物理行号计数', () => {
  const text = [HEADER, csv(FULL), csv(['VAT-06', '6.0000', '2027-02-01', '2027-02-28', 'DOMESTIC', '公告2', 'GENERAL', 'LOW'])].join('\r')

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 0)
  // 纯 CR 不会被逐列 trim 兜住（\r 落在行间而非单元格末尾），是归一行的唯一判别手段
  assert.equal(rows.length, 2)
  assert.deepEqual(rows.map(r => r.rowNo), [2, 3])
  assert.equal(rows[0].taxCode, 'VAT-13')
  assert.equal(rows[1].rateKind, 'LOW')
})

// ---------------------------------------------------------------- 分隔符探测

test('分隔符探测：Tab 文件按 \\t 切分', () => {
  const text = csv(FULL, '\t')

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].taxCode, 'VAT-13')
  assert.equal(rows[0].rateKind, 'STANDARD')
  assert.equal(rows[0]._short, undefined, '8 列 Tab 行不应被判为 _short')
})

test('分隔符探测：# 注释行出现在数据行之前时仍识别为 Tab', () => {
  const text = [
    '# 税码批量导入模板（说明行，不含制表符）',
    '# 第二行注释',
    csv(FULL, '\t')
  ].join('\n')

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  // 若误判为逗号，该行只切出 1 列 → _short row，且 taxCode 会是整行文本
  assert.equal(rows[0]._short, undefined, '注释行不该影响 Tab 探测')
  assert.equal(rows[0].taxCode, 'VAT-13')
  assert.equal(rows[0].rateKind, 'STANDARD')
  assert.equal(rows[0].rowNo, 3, '注释行占行号，数据行落在第 3 行')
})

test('分隔符探测：逗号文件（含 # 注释行）按 , 切分', () => {
  const text = ['# 注释：用逗号', csv(FULL)].join('\n')

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].taxCode, 'VAT-13')
  assert.equal(rows[0].calcType, 'GENERAL')
  assert.equal(rows[0].rowNo, 2)
})

test('分隔符探测：Tab 文件中的逗号属于单元格内容不参与切分', () => {
  const cells = ['VAT-13', '13.0000', '2027-01-01', '2027-06-30', 'DOMESTIC', '公告A,B号', 'GENERAL', 'STANDARD']

  const { rows } = parseTaxRows(csv(cells, '\t'))

  assert.equal(rows.length, 1)
  // 逗号未被当作分隔符，policyNo 原样保留
  assert.equal(rows[0].policyNo, '公告A,B号')
  assert.equal(rows[0].rateKind, 'STANDARD')
})

// ---------------------------------------------------------------- 跳过规则

test('表头行被跳过且不计入 skipped（三种写法）', () => {
  for (const header of [
    '税码编号,税率值,生效日期,失效日期,适用范围,政策文号,计税方式,税率类型',
    'taxCode,rate,effective,expire,scope,policyNo,calcType,rateKind',
    'TAX_CODE,RATE,EFFECTIVE,EXPIRE,SCOPE,POLICY,CALC,RATEKIND'
  ]) {
    const { rows, skipped } = parseTaxRows([header, csv(FULL)].join('\n'))
    assert.equal(skipped, 0, `表头不应计入 skipped: ${header}`)
    assert.equal(rows.length, 1)
    assert.equal(rows[0].rowNo, 2)
    assert.equal(rows[0].taxCode, 'VAT-13')
  }
})

test('空行与 # 注释行被跳过：占物理行号但不计入 skipped', () => {
  const text = [
    '',                       // 1 空行
    '# 说明行',               // 2 注释
    csv(FULL),                // 3 数据
    '   ',                    // 4 空白行
    '# 又一条说明',           // 5 注释
    csv(['VAT-06', '6.0000', '2027-02-01', '2027-02-28', 'DOMESTIC', '公告2', 'GENERAL', 'LOW']) // 6 数据
  ].join('\n')

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 0, '空行与注释行不计入 skipped')
  assert.equal(rows.length, 2)
  assert.deepEqual(rows.map(r => r.rowNo), [3, 6])
})

test('非法行的 skipped 计数，且被跳过行仍占物理行号', () => {
  const text = [
    '# 表头说明',                                            // 1
    HEADER,                                                  // 2
    csv(FULL),                                               // 3
    '',                                                      // 4
    'VAT-06,6.0000,2027-02-01',                              // 5 → 3 列，_short
    csv(['VAT-09', '9.0000', '2027-03-01', '2027-03-31', 'DOMESTIC', '公告3', 'GENERAL', 'LOW']), // 6
    'VAT-00'                                                 // 7 → 1 列，_short
  ].join('\n')

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(rows.length, 4, '2 条正常行 + 2 条 _short 行')
  assert.equal(skipped, 2, '仅 _short 行计入 skipped')
  assert.deepEqual(rows.map(r => r.rowNo), [3, 5, 6, 7])
  assert.deepEqual(rows.map(r => !!r._short), [false, true, false, true])
})

// ---------------------------------------------------------------- 列宽分支

test('5 列不足：标记 _short，skipped 递增，且只 trim 不做大写归一', () => {
  const text = 'vat-13,13.0000,2027-01-01,2027-06-30,Domestic'

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 1)
  assert.equal(rows.length, 1)
  const row = rows[0]
  assert.equal(row._short, true)
  assert.equal(row.rowNo, 1)
  // 关键：_short 分支不做大写归一
  assert.equal(row.taxCode, 'vat-13')
  assert.equal(row.scope, 'Domestic')
  // 缺失列落空串
  assert.equal(row.policyNo, '')
  assert.equal(row.calcType, '')
  assert.equal(row.rateKind, '')
  assert.equal(row.effectiveDate, '2027-01-01')
})

test('_short 行仍做逐列 trim', () => {
  const text = '  VAT-13  ,  13.0000  ,  2027-01-01  ,  2027-06-30  ,  DOMESTIC  '

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 1)
  assert.equal(rows[0]._short, true)
  assert.equal(rows[0].taxCode, 'VAT-13')
  assert.equal(rows[0].taxRate, '13.0000')
  assert.equal(rows[0].scope, 'DOMESTIC')
})

test('6 列（后两列缺省）：calcType 与 rateKind 为空串且不算 _short', () => {
  const text = 'vat-13,13.0000,2027-01-01,2027-06-30,domestic,公告1'

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 0, '6 列是合法行，不计入 skipped')
  assert.equal(rows.length, 1)
  const row = rows[0]
  assert.equal(Object.hasOwn(row, '_short'), false, '6 列不应带 _short 标记')
  assert.equal(row.calcType, '')
  assert.equal(row.rateKind, '')
  // 正常分支：taxCode / scope 大写归一
  assert.equal(row.taxCode, 'VAT-13')
  assert.equal(row.scope, 'DOMESTIC')
  assert.equal(row.policyNo, '公告1')
})

test('7 列（缺最后一列）：calcType 有值、rateKind 为空串且不算 _short', () => {
  const text = 'VAT-13,13.0000,2027-01-01,2027-06-30,DOMESTIC,公告1,GENERAL'

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  const row = rows[0]
  assert.equal(Object.hasOwn(row, '_short'), false)
  assert.equal(row.calcType, 'GENERAL')
  assert.equal(row.rateKind, '')
})

test('8 列完整：全部字段落位且 rowNo 为物理行号 1', () => {
  const { rows, skipped } = parseTaxRows(csv(FULL))

  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0], {
    rowNo: 1,
    taxCode: 'VAT-13',
    taxRate: '13.0000',
    effectiveDate: '2027-01-01',
    expireDate: '2027-06-30',
    scope: 'DOMESTIC',
    policyNo: '税总公告2027年第1号',
    calcType: 'GENERAL',
    rateKind: 'STANDARD'
  })
  assert.equal(Object.hasOwn(rows[0], '_short'), false)
})

test('超过 8 列：多余列被忽略，不抛错也不产生额外字段', () => {
  const text = [...FULL, 'EXTRA-1', 'EXTRA-2'].join(',')

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 0)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].taxCode, 'VAT-13')
  assert.equal(rows[0].rateKind, 'STANDARD')
  // 只保留 8 列 + rowNo，多余列不落位
  assert.deepEqual(
    Object.keys(rows[0]).sort(),
    ['calcType', 'effectiveDate', 'expireDate', 'policyNo', 'rateKind', 'rowNo', 'scope', 'taxCode', 'taxRate']
  )
  assert.ok(!Object.values(rows[0]).includes('EXTRA-1'))
  assert.ok(!Object.values(rows[0]).includes('EXTRA-2'))
})

// ---------------------------------------------------------------- 字段归一

test('四个大写归一列：taxCode / scope / calcType / rateKind 转大写', () => {
  const text = ['vat-13', '13.0000', '2027-01-01', '2027-06-30', 'domestic', '公告1', 'general', 'low'].join(',')

  const { rows } = parseTaxRows(text)
  const row = rows[0]

  assert.equal(row.taxCode, 'VAT-13')
  assert.equal(row.scope, 'DOMESTIC')
  assert.equal(row.calcType, 'GENERAL')
  assert.equal(row.rateKind, 'LOW')
})

test('不做大小写归一的列：taxRate / 日期 / policyNo 原样保留', () => {
  const text = ['VAT-13', '13.1234', '2027-01-01', '2027-06-30', 'DOMESTIC', 'Policy-AbC123', 'GENERAL', 'STANDARD'].join(',')

  const { rows } = parseTaxRows(text)
  const row = rows[0]

  assert.equal(row.taxRate, '13.1234')
  assert.equal(row.effectiveDate, '2027-01-01')
  assert.equal(row.expireDate, '2027-06-30')
  // policyNo 不转大写
  assert.equal(row.policyNo, 'Policy-AbC123')
})

test('policyNo 前后空格被 trim，内部空格保留', () => {
  const text = 'VAT-13,13.0000,2027-01-01,2027-06-30,DOMESTIC,   税总公告 2027 年第 1 号   ,GENERAL,STANDARD'

  const { rows } = parseTaxRows(text)

  assert.equal(rows[0].policyNo, '税总公告 2027 年第 1 号')
})

test('各列首尾空格被 trim 后仍走正常归一分支', () => {
  const text = ['  vat-13  ', '  13.0000  ', '  2027-01-01  ', '  2027-06-30  ', '  domestic  ', '  公告1  ', '  general  ', '  low  '].join(',')

  const { rows, skipped } = parseTaxRows(text)

  assert.equal(skipped, 0)
  const row = rows[0]
  assert.equal(row.taxCode, 'VAT-13')
  assert.equal(row.taxRate, '13.0000')
  assert.equal(row.effectiveDate, '2027-01-01')
  assert.equal(row.expireDate, '2027-06-30')
  assert.equal(row.scope, 'DOMESTIC')
  assert.equal(row.policyNo, '公告1')
  assert.equal(row.calcType, 'GENERAL')
  assert.equal(row.rateKind, 'LOW')
})
