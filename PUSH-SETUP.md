# Push notification — relay setup (free, bina card)

App ka hissa tayyar hai. FCM bhejne ke liye bas ek chhota **relay** chahiye
(Firebase ki server key app ke andar nahi rakhte — aur ye repo **public** hai).

Do raaste hain — **jo aasaan lage wo chun lo**:

---

## 🅰️ Google Apps Script (sabse aasaan — wahi Google account)

1. Firebase console → ⚙️ **Project settings → Service accounts → Generate new private key**
   → JSON milega (`project_id`, `client_email`, `private_key`)
2. **script.google.com** → **New project**
3. Repo ki file `push-relay/apps-script.gs` ka poora content paste karo
4. Upar 3 cheezein bharo (JSON se copy):
   - `PROJECT_ID` = `app-party-wpnative`
   - `CLIENT_EMAIL` = JSON ka `client_email`
   - `PRIVATE_KEY` = JSON ka `private_key` (poora, `\n` samet)
5. **Deploy → New deployment → ⚙️ Web app**
   - Execute as: **Me**
   - Who has access: **Anyone**
   - **Deploy** → URL milega (`/exec` wala) → copy kar lo
6. Wo URL mujhe bhej do

🔒 Private key sirf **aapke Google account** mein rahegi — repo mein kabhi nahi aayegi.

---

## 🅱️ Cloudflare Worker (bhi free hai — card nahi mangta)

Workers **Free** plan abhi bhi free hai: 100,000 requests/day, **no credit card**
([source](https://freetier.co/directory/products/cloudflare-workers)).
Agar paid wala screen aaye to ghalat plan select ho gaya — **Workers Free** chuno
("Workers Paid $5" nahi, R2 nahi — R2 ko card chahiye).

1. **dash.cloudflare.com** → **Workers & Pages → Create → Create Worker** (Free plan)
2. `push-worker/worker.js` ka code paste kar ke **Deploy**
3. Worker → **Settings → Variables** → 2 secrets:
   - `PROJECT_ID` = `app-party-wpnative`
   - `GOOGLE_SA` = service account JSON ka poora text
4. Worker ka URL (`https://xxxx.workers.dev`) bhej do

---

## Uske baad (main kar doonga)

`Push.kt` mein URL daal kar push on kar doonga.

## Test

1. Naya APK install → **Allow notifications**
2. Phone A: message bhejo
3. Phone B: app **band** kar do (recent se hatao)
4. Phone B par notification → **Reply** → seedha jawab likho → bhejo ⚡
