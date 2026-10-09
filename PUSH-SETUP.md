# Push notification — relay setup (free, bina card)

App ka hissa tayyar hai. FCM bhejne ke liye ek chhota **relay** chahiye
(Firebase ki server key app ke andar nahi rakhte — aur ye repo **public** hai).

---

## 🅰️ Google Apps Script — **TAREEQA 1: BINA KEY** (sabse aasaan, 3 minute)

Naye Firebase console mein "Generate new private key" button nahi rehta —
**hume uski zarurat hi nahi**. Script aapke khud ke Google account se FCM bhejegi
(aap hi project ke owner ho).

1. **script.google.com** → **New project**
2. Code wali jagah repo ki file **`push-relay/apps-script.gs`** ka poora content paste karo
   (`CLIENT_EMAIL` aur `PRIVATE_KEY` **khali** rehne do)
3. **Project Settings (⚙️, bayen taraf)** → **"Show appsscript.json manifest file"** ON karo
4. Wapas Editor → `appsscript.json` kholo → uska content repo ki file
   **`push-relay/appsscript.json`** se replace kar do (isme `oauthScopes` hai — zaroori)
5. **Deploy → New deployment → ⚙️ (gear) → Web app**
   - Execute as: **Me**
   - Who has access: **Anyone**
   - **Deploy** → pehli baar permission maangega → **Authorize** (apna hi account)
6. `/exec` wala URL copy kar ke **bhej do** ✅

🔒 Koi private key nahi, koi secret nahi — sab kuch aapke Google account mein.

> Test: browser mein URL kholo → `{"ok":true,"msg":"WP push relay chal raha hai"}` aana chahiye.

---

## 🅱️ Agar Tareeqa 1 FCM par "no access token" / 401 de

Tab service account key banani padegi (Firebase console se nahi, **Google Cloud Console** se):

1. Ye link kholo (usi Google account se):
   `https://console.cloud.google.com/iam-admin/serviceaccounts?project=app-party-wpnative`
2. List mein **`firebase-adminsdk-fbsvc@app-party-wpnative.iam.gserviceaccount.com`** par click karo
3. Upar **KEYS** tab → **ADD KEY → Create new key** → **JSON** → **Create**
4. JSON download hoga — usme se 3 cheezein `apps-script.gs` mein bhar do:
   - `PROJECT_ID` = `app-party-wpnative`
   - `CLIENT_EMAIL` = JSON ka `client_email`
   - `PRIVATE_KEY` = JSON ka `private_key` (poora, `\n` samet)
5. **Deploy → Manage deployments → ✏️ Edit → Version: New version → Deploy**

---

## 🅲️ Cloudflare Worker (bhi free — card nahi mangta)

Workers **Free** plan abhi bhi free hai: 100,000 requests/day, **no credit card**
([source](https://freetier.co/directory/products/cloudflare-workers)).
Agar paid wala screen aaye to ghalat jagah ho — **Workers Free** chuno
("Workers Paid $5" nahi, **R2 nahi** — R2 ko card chahiye).

1. **dash.cloudflare.com** → **Workers & Pages → Create → Create Worker** (Free plan)
2. `push-worker/worker.js` paste kar ke **Deploy**
3. Worker → **Settings → Variables** → 2 secrets:
   - `PROJECT_ID` = `app-party-wpnative`
   - `GOOGLE_SA` = service account JSON ka poora text
4. Worker ka URL bhej do

---

## Uske baad (main kar doonga)
`Push.kt` mein URL daal kar push on kar doonga.

## Test
1. Naya APK install → **Allow notifications**
2. Phone A: message bhejo
3. Phone B: app **band** kar do (recent se hatao)
4. Phone B par notification → **Reply** → seedha jawab likho → bhejo ⚡
