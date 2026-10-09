# Watch Party — Native Design Spec (website se nikal kar likha gaya)

> **Ye file design ki "nakal" hai, code ki nahi.**
> App **A to Z pure native** hai: koi WebView nahi, koi HTML/CSS/JS nahi, koi assets nahi,
> koi external library nahi (sirf Android SDK + Kotlin).
>
> Website (`party-final1.html`) sirf **design reference** thi — us ke rang, size aur spacing
> yahan note kar liye gaye hain taake aage kabhi website kholne ki zaroorat hi na pare.
> Reference ki copies sandbox me `~/wp-site` (website repo) aur `~/wp-website` (purana WebView app) hain —
> **native build ka un par koi farz nahi.**

## 0. Buniyad (app/build.gradle.kts)

| Cheez | Value |
|---|---|
| namespace / applicationId | `app.party.wpnative` |
| minSdk / targetSdk | 24 / 35 |
| compileSdk | 35 |
| dependencies | **koi nahi** (sirf Android SDK + Kotlin stdlib) |
| theme | `@android:style/Theme.Material.NoActionBar` |
| UI banane ka tareeqa | poori UI Kotlin code se (koi XML layout nahi) |

---

## 1. Chat screen (`ChatActivity.kt`) — website `#dm-view-chat` / `#dm-thread`

### Screen background (radial, upar se neeche)
```
radial-gradient(120% 62% at 50% 0%,
  #241456 0%, #130933 45%, #05030d 80%, #000 100%)
base color: #05030d
```

### Header (`.dm-bar`)
| Hissa | Spec |
|---|---|
| padding | 12dp, gap 6dp |
| ← back | 28x28, radius 9, `bg rgba(255,255,255,.10)`, border 1dp `rgba(255,255,255,.16)`, 15sp |
| avatar | 34dp circle, ring 2dp gradient `#22d3ee → #a855f7` |
| naam | 13.5sp bold `#ffffff` |
| sub | 10sp `#cfc6ee` |
| 📞 / ☰ buttons | 36x36, radius 11, `bg rgba(255,255,255,.10)`, border 1dp `rgba(255,255,255,.14)` |
| neeche line | 1dp `rgba(255,255,255,.10)` |
| **Nahi hai** | E2E badge (`display:none !important`) aur ✕ close — website ne dono chhupaye hain |

### Message row (`.msg-row`)
- row max width **85%**, gap 8dp, `align-items: flex-end`
- apna (`own`) = `flex-direction: row-reverse` + right align; doosra = left
- avatar: **34dp** circle — apna gradient `#ff5ebc → #a855f7`, doosra = friend color

### Bubble
| | Apna (own) | Doosra (other) |
|---|---|---|
| background | gradient `135deg #5b21b6 0%, #9333ea 55%, #db2777 100%` | `rgba(255,255,255,.09)` |
| border | koi nahi | 1dp `rgba(255,255,255,.14)` |
| radius | 22dp | 22dp |
| text | 13.5sp `#ffffff` | 13.5sp `#f3efff` |
| padding | 9dp upar/neeche, 13dp dayen/bayen | same |
| line-height | 1.45 | 1.45 |
| shadow | `0 6px 20px rgba(147,51,234,.35)` | koi nahi |

- bubble ke upar **naam**: 11sp bold — apna `#f9a8d4` ("You"), doosra = friend color
- bubble ke neeche **time**: 9.5sp `rgba(255,255,255,.55)` + `✓✓`
  - parha hua (read) tick = `#5bdcff`, warna `rgba(255,255,255,.62)`
- **Day separator**: 10.5sp bold `rgba(255,255,255,.55)`, center, koi pill nahi
- **Reply quote** bubble ke andar: bayen kinare 2dp `rgba(255,255,255,.85)`,
  `bg rgba(0,0,0,.22)`, radius 4, naam 11sp (apna `#ffe6a8`, doosra `#c4b5fd`), text 11.5sp
- **Reaction chip**: `bg rgba(8,8,24,.8)`, border 1dp `rgba(255,255,255,.24)`, radius 12,
  12sp; meri reaction ho to `bg rgba(244,114,182,.34)` + border `#f472b6`

### Composer (`.dm-inbar` > `.ig4pill`)
| Hissa | Spec |
|---|---|
| bar | padding 9dp/10dp, gap 8dp, `bg rgba(0,0,0,.35)`, upar 1dp `rgba(255,255,255,.12)` |
| pill | height 40dp, radius 21, `bg rgba(255,255,255,.09)`, border 1dp `rgba(255,255,255,.16)` |
| 🖼 photo | 32dp, radius 12, gradient `#f59e0b → #ec4899 → #8b5cf6` |
| GIF | 32dp, radius 12, gradient `#f472b6 → #a78bfa`, 11sp bold |
| 🎤 mic | 32dp, radius 12, gradient `#06b6d4 → #3b82f6 → #8b5cf6` |
| input | 13sp, hint `rgba(255,255,255,.55)`, transparent bg |
| ➤ send | **40dp gol**, gradient `#a855f7 → #22d3ee`, icon `#0a0616` |

### Actions sheet (bubble ko zor se dabaane par)
- emojis: `❤️ 😂 😮 😢 👍 🔥` (33dp gol) → reaction
- buttons: `↩ Reply` · `📋 Copy` · `🗑 Delete`

### Empty state
`👋` 26sp + `"{naam} ko salam bhejo!"` 12sp `#a9a6c8`, line-height 1.7

---

## 2. Baqi screens (pehle se bane hue)

| Screen | Source | Note |
|---|---|---|
| Join (MainActivity) | website join screen | 6 themes, 12 bubble colors, DP picker |
| Messages (InboxActivity) | `#dm-view-inbox` | neeche `#yp-bar` (BottomNav), ☰ = Pinned chats / Chat clear |
| Calls (CallsActivity) | website calls view | ☰ = Call history clear / Clear missed only |
| Bottom bar (BottomNav.kt) | `#yp-bar` | Party / Chat / Call, shared |
| Menu (MenuPopup.kt) | `.dm-pop4` | `showDropMenu()` + `confirmThen()` |

---

## 3. Abhi tak ka status

- [x] Join page
- [x] Messages (inbox) + bottom bar + menus
- [x] Calls page
- [x] **Chat screen (design + demo features)** ← abhi ka kaam
- [ ] Party page (agla step)
- [ ] Asli chat / E2E (demo abhi in-memory)
- [ ] Pins aur clear ko save karna
- [ ] Bubble ke 12 style options
