# Push Notification setup (WhatsApp jaisi) — bina card ke, free

App ka hissa tayyar hai. Bas FCM tak message pahunchane ke liye ek chhota
**Cloudflare Worker** chahiye (free, card nahi mangta). 5–7 minute ka kaam hai.

---

## 1) Firebase service account key

1. Firebase console → ⚙️ **Project settings** → **Service accounts**
2. **"Generate new private key"** → JSON file download hogi
   (isme `project_id`, `client_email`, `private_key` hote hain)
3. Is file ko **kisi ko mat bhejna** — sirf aap khud Worker mein paste karenge

---

## 2) Cloudflare account + Worker

1. https://dash.cloudflare.com/sign-up → account banao (email + password, **card nahi**)
2. Bayen menu → **Workers & Pages** → **Create** → **Create Worker**
3. Naam kuch bhi do (jaise `wp-push`) → **Deploy**
4. Deploy ke baad **"Edit code"** dabao
5. Jo code pehle se hai wo sab **delete** kar do, aur repo ki file
   `push-worker/worker.js` ka poora content paste kar do
6. **Save and Deploy**

---

## 3) Worker ke secrets (2)

Worker ke page par → **Settings → Variables → Environment Variables → Add variable**
(dono ko **Encrypt** karna hai):

| Name | Value |
|---|---|
| `PROJECT_ID` | `app-party-wpnative` |
| `GOOGLE_SA` | service account JSON ka **poora text** (ek hi line mein paste ho jayega) |

**Save and Deploy** karna na bhoolna.

---

## 4) Worker ka URL

Worker page par upar **"Visit"** ya `https://wp-push.<aapka-subdomain>.workers.dev`
likha hoga — wo copy kar lo.

Wo URL mujhe bhej do (ya khud `Push.kt` mein `RELAY_URL` mein daal do):

```kotlin
private const val RELAY_URL = "https://wp-push.xxxxx.workers.dev"
```

---

## 5) Test

1. Naya APK install karo (Actions → Artifacts)
2. App kholo → **Allow notifications** (permission aayega)
3. **Phone A**: message bhejo
4. **Phone B**: app ko **band** kar do (recent se hata do)
5. Phone B par notification aayegi → **Reply** dabao → seedha jawab likho → bhejo
6. Phone A ko jawab mil jayega ⚡

---

## Masla aaye to

- **Notification nahi aayi**: Worker URL check karo, aur phone B ke Firestore
  `users/{naam}` mein `fcmToken` maujood hai ya nahi
- **"token error"**: service account JSON theek paste hua ya nahi (poora JSON, quotes sahi)
- Worker ka test: browser se URL kholo → `WP push relay OK` likha aana chahiye
