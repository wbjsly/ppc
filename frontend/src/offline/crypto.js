/**
 * 离线字段加密（add-iot-mobile 5.4，AES-GCM + WebCrypto）。
 * 密钥：登录会话派生（测试/演示用固定应用盐 + 会话随机因子）；
 * 每记录随机 IV（12 字节），密文格式：v1.<iv-b64>.<cipher-b64>。
 * node 20+/浏览器均可用（globalThis.crypto.subtle）。
 */

const enc = new TextEncoder()
const dec = new TextDecoder()

/** 会话密钥派生（HKDF 简化：SHA-256 盐+会话因子 → AES-256-GCM） */
export async function deriveKey(sessionSeed) {
  const base = await crypto.subtle.importKey('raw', enc.encode(sessionSeed || 'iot-default'),
      { name: 'HKDF' }, false, ['deriveKey'])
  return crypto.subtle.deriveKey(
      { name: 'HKDF', hash: 'SHA-256', salt: enc.encode('erp-iot-v1'), info: enc.encode('offline-queue') },
      base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
}

/** 字段加密（null/空原样返回） */
export async function encryptField(key, plain) {
  if (plain === null || plain === undefined || plain === '') {
    return plain
  }
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const cipher = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, enc.encode(String(plain)))
  return 'v1.' + b64(iv) + '.' + b64(new Uint8Array(cipher))
}

/** 字段解密（非 v1 密文原样返回——兼容明文存量） */
export async function decryptField(key, value) {
  if (value === null || value === undefined || typeof value !== 'string'
      || !value.startsWith('v1.')) {
    return value
  }
  const parts = value.split('.')
  const iv = unb64(parts[1])
  const cipher = unb64(parts[2])
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, cipher)
  return dec.decode(plain)
}

function b64(bytes) {
  let s = ''
  for (const b of bytes) {
    s += String.fromCharCode(b)
  }
  return btoa(s)
}

function unb64(s) {
  const bin = atob(s)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) {
    out[i] = bin.charCodeAt(i)
  }
  return out
}
