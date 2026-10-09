/**
 * Watch Party — PUSH RELAY (Cloudflare Worker)
 * Free, bina card ke chalega.
 *
 * Kaam: app se {token,title,body,from,chatId} le kar FCM (Firebase) tak pahuncha deta hai.
 * App khud FCM nahi bhej sakti (us ke liye server-side key chahiye, jo app me rakhna unsafe hai).
 */

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: cors() })
    if (request.method !== 'POST') return new Response('WP push relay OK', { status: 200 })

    let p
    try { p = await request.json() } catch (e) { return new Response('bad json', { status: 400 }) }
    const { token, title, body, from, chatId } = p || {}
    if (!token) return new Response('token missing', { status: 400 })

    try {
      const sa = JSON.parse(env.GOOGLE_SA)

      // 1) Google service account se access token
      const jwt = await makeJwt(sa)
      const t = await fetch('https://oauth2.googleapis.com/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
          assertion: jwt
        })
      }).then(r => r.json())

      if (!t.access_token) return new Response('token error: ' + JSON.stringify(t), { status: 502 })

      // 2) FCM v1 — data-only message (app khud notification banayega + Reply button)
      const res = await fetch(
        `https://fcm.googleapis.com/v1/projects/${env.PROJECT_ID}/messages:send`,
        {
          method: 'POST',
          headers: {
            'Authorization': 'Bearer ' + t.access_token,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            message: {
              token: token,
              data: {
                title: String(title || ''),
                body: String(body || ''),
                from: String(from || ''),
                chatId: String(chatId || '')
              },
              android: { priority: 'HIGH' }
            }
          })
        }
      )
      const txt = await res.text()
      return new Response(txt, { status: res.ok ? 200 : 502, headers: cors() })
    } catch (e) {
      return new Response('error: ' + (e && e.message), { status: 500, headers: cors() })
    }
  }
}

function cors() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Allow-Methods': 'POST,OPTIONS'
  }
}

/* ---------- Google service account -> JWT (RS256) ---------- */
async function makeJwt(sa) {
  const now = Math.floor(Date.now() / 1000)
  const head = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
  const claim = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600
  }))
  const input = head + '.' + claim
  const key = await crypto.subtle.importKey(
    'pkcs8',
    pemToBuf(sa.private_key),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign']
  )
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(input))
  return input + '.' + b64urlBuf(sig)
}

function pemToBuf(pem) {
  const b64 = pem
    .replace(/-----BEGIN[^-]+-----/g, '')
    .replace(/-----END[^-]+-----/g, '')
    .replace(/\s+/g, '')
  const bin = atob(b64)
  const buf = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i)
  return buf
}

function b64url(s) {
  return btoa(String(s)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function b64urlBuf(buf) {
  let s = ''
  const b = new Uint8Array(buf)
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
