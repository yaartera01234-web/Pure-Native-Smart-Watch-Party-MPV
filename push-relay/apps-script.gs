/**
 * Watch Party — PUSH RELAY (Google Apps Script)
 * Free, bina card, wahi Google account jo Firebase wala hai.
 * Private key SIRF aapke Google account mein rahegi — repo (public) mein kabhi nahi.
 *
 * ===== SETUP (2 minute) =====
 * 1) Firebase console -> Project settings -> Service accounts
 *    -> "Generate new private key" -> JSON milega
 * 2) Neeche 3 jagah bharo: PROJECT_ID, CLIENT_EMAIL, PRIVATE_KEY
 *    (JSON se copy karo; PRIVATE_KEY mein \n waise hi rehne do)
 * 3) script.google.com -> New project -> ye code paste karo -> Save
 * 4) Deploy -> New deployment -> (gear) Web app
 *      Execute as: Me
 *      Who has access: Anyone        <-- zaroori hai (app bina login call karegi)
 *    -> Deploy -> URL copy karo (/exec wala)
 * 5) Wo URL mujhe bhej do
 */

var PROJECT_ID = 'app-party-wpnative';
var CLIENT_EMAIL = 'FIREBASE-ADMIN-SDK-ID@app-party-wpnative.iam.gserviceaccount.com';
var PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nABCDEFG...\n-----END PRIVATE KEY-----\n";

/* Chhota sa secret (optional) — app aur script dono mein same hona chahiye. */
var APP_KEY = 'wp-2026';

function doPost(e) {
  var data;
  try { data = JSON.parse(e.postData.contents); }
  catch (err) { return out({ ok: false, error: 'bad json' }); }

  if (APP_KEY && data.key !== APP_KEY) return out({ ok: false, error: 'bad key' });

  var token = data.token;
  if (!token) return out({ ok: false, error: 'no token' });

  var access = getAccessToken();
  if (!access) return out({ ok: false, error: 'no access token' });

  var payload = {
    message: {
      token: token,
      data: {
        title: String(data.title || ''),
        body: String(data.body || ''),
        from: String(data.from || ''),
        chatId: String(data.chatId || '')
      },
      android: { priority: 'HIGH' }
    }
  };

  var res = UrlFetchApp.fetch(
    'https://fcm.googleapis.com/v1/projects/' + PROJECT_ID + '/messages:send',
    {
      method: 'post',
      contentType: 'application/json',
      headers: { Authorization: 'Bearer ' + access },
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    }
  );
  return out({ ok: res.getResponseCode() === 200, code: res.getResponseCode(), body: res.getContentText() });
}

function doGet() {
  return out({ ok: true, msg: 'WP push relay chal raha hai' });
}

/* ---------- Google service account -> access token ---------- */
function getAccessToken() {
  var now = Math.floor(Date.now() / 1000);
  var jwt = makeJwt(now);
  var res = UrlFetchApp.fetch('https://oauth2.googleapis.com/token', {
    method: 'post',
    contentType: 'application/x-www-form-urlencoded',
    payload: {
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: jwt
    },
    muteHttpExceptions: true
  });
  try {
    var j = JSON.parse(res.getContentText());
    return j.access_token || null;
  } catch (e) { return null; }
}

function makeJwt(now) {
  var head = b64(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  var claims = b64(JSON.stringify({
    iss: CLIENT_EMAIL,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600
  }));
  var input = head + '.' + claims;
  var sig = Utilities.computeRsaSha256Signature(input, PRIVATE_KEY);
  return input + '.' + b64b(sig);
}

function b64(s) { return Utilities.base64EncodeWebSafe(s).replace(/=+$/, ''); }
function b64b(bytes) { return Utilities.base64EncodeWebSafe(bytes).replace(/=+$/, ''); }
function out(o) {
  return ContentService
    .createTextOutput(JSON.stringify(o))
    .setMimeType(ContentService.MimeType.JSON);
}
