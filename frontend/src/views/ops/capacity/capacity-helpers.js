/** 容量指标键 → 展示名（预警/扩容两页共用，拆分自原容量运维操作台）。 */
export function keyLabel(k) {
  return { DB_SIZE: 'DB 存储', CONN_POOL: '连接池', CPU: '进程 CPU' }[k] || k
}
