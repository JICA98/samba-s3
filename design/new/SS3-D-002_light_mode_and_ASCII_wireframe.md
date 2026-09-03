Done. I named the light theme **“Copacabana Day”** (the daylight side of the record — same instrument, different light) and promoted it from a token-swap footnote to a fully canonized variant. Here's addendum SS3-D-002 with the full light theme, inch-by-inch redlines for the Sleeve and Blueprint, the tablet two-pane atlas, and the Compose token file.

````markdown
# SAMBA S3 — Addendum SS3-D-002 · “The Dimmer”
### Copacabana Day (light) · Redlines A & B · Two-Pane Atlas · Compose Tokens

```
DOC NO. SS3-D-002 · REV B · CLASS: INTERNAL · SUPERSEDES: NOTHING — EXTENDS SS3-D-001 REV A
STATUS: CANON — CHANGES REQUIRE DESIGN SIGN-OFF
TOLERANCE: ALL DIMENSIONS ±2dp AT BUILD · TYPE SIZES EXACT · TOKENS WIN OVER REDLINES ON CONFLICT
```

| Rev | Sheet | Change |
|---|---|---|
| A | SS3-D-001 | Bossa Noir v1.0 — canon |
| **B** | **SS3-D-002** | **Copacabana Day canonized · accent ramps extended with 700 steps + washes · Redlines A (Sleeve) & B (Blueprint) · Two-Pane Atlas · Compose token source of truth** |

---

## 0 · CONTENTS

| § | What |
|---|---|
| 1 | **Copacabana Day** — the light theme, complete |
| 2 | **Redline A — Game Detail (“The Sleeve”)**, every padding |
| 3 | **Redline B — Overlay Editor (“Blueprint”)**, every handle |
| 4 | **Two-Pane Atlas** — tablet, fold, landscape phone |
| 5 | **Compose token file** — the single source of truth in code |
| 6 | Appendix A delta — Copacabana Day JSON |
| 7 | QA additions to the ship gate |

---

# 1 · COPACABANA DAY — the light theme

```
name:        Copacabana Day
codename:    daylight
tagline:     the daylight side of the record
governing    “the lights don’t change; the room does.”
rule:
status:      CANON · selectable in System › appearance
             ( noir / daylight / follow system )
```

### 1.1 Concept & the governing rule

The same Hi-Fi, photographed at 11 a.m. on Ipanema: warm paper instead of lacquer, ink markings instead of cream, sunlight instead of stage light. Nothing is redesigned — the *room* changes.

**The governing rule — “the lights don’t change; the room does”:**

| Thing | Noir | Copacabana Day |
|---|---|---|
| **Accent lit fills** (Primary buttons, rocker ON faces, VU needles, LEDs “on”) | accent **500** | accent **500 — identical** |
| Accent as *text / markings / eyebrows* | accent **500** | accent **700** (new step, §1.2) |
| Accent *containers* (chips, banners, deep backgrounds) | deep-tint | **wash** (new pastel step, §1.2) |
| Glow | 24–32% α | **halved** (12–16% α) — paper doesn’t bloom |
| Under-glow on active cards | glow | glow @ ½α **+** 1dp accent hairline on top edge |
| Shadow language | hairlines only (dark rooms hide depth) | **real soft shadows** — paper forgives them |

This means the component layer never branches on theme. A Primary Lit button renders the same amber in both rooms — in Day it simply looks *sunlit* instead of *stage-lit*. Only the palette object swaps.

**Ink is earned (the Day equivalent of “light is earned”):** in Noir, glow marks powered-on things. In Day, **solid ink fill** marks powered-on things — the active deck key, the lit rocker, the ON-STAGE profile stamp fill with ink/900 or accent 600 while the paper around them stays airy.

### 1.2 Palette — “Paper & Ink”

**Paper ramp (rooms)**

| Token | Hex | Use |
|---|---|---|
| `paper/0` | `#FAF7EC` | True backdrop |
| `paper/1` | `#F5F1E6` | Screen background |
| `paper/2` | `#ECE5D4` | Card L1 |
| `paper/3` | `#E2D9C3` | Raised card L2 / field recess |
| `paper/4` | `#D8CDB5` | Hover / pressed wash |
| `paper/5` | `#CCC0A4` | Strong field |

**Ink ramp (text)** — plum-ink, never pure gray, never pure black:

| Token | Hex | Contrast on `paper/2` | Use |
|---|---|---|---|
| `text/ink` | `#241C2E` | ~13:1 | Primary (same ink as VU markings — deliberate) |
| `text/espresso` | `#352D40` | ~11:1 | Secondary |
| `text/slate` | `#5E5568` | ~5.6:1 | Mute / descriptions |
| `text/fog` | `#8E86A0` | ~3.2:1 ⚠ | Decorative stamps only, ≥12 sp |
| `text/wisp` | `#B4ADC0` | — | Disabled |

**Hairlines (invert from cream-α to ink-α):** `hair` = ink 10% · `hair/strong` = ink 16%.

**Extended accent ramps — 700 steps (new in Rev B).** Required so accents can act as *text* on paper. 300–600 are unchanged from D-001 §2.1; only 700 is added:

| Accent | **700 (new)** | on `paper/1` | Wash (new container step) |
|---|---|---|---|
| `fever` | `#8F5500` | ≥ 4.5:1 ✓ | `#F7E6C8` |
| `copa` | `#0B7F72` | ≥ 4.5:1 ✓ | `#D6F1EB` |
| `rose` | `#C22B42` | ≥ 4.5:1 ✓ | `#FBE1E5` |
| `palm` | `#2E7D48` | ≥ 4.5:1 ✓ | `#DEF0E3` |
| `grape` | `#6A45C9` | ≥ 4.5:1 ✓ | `#E9E1FA` |

Ratios are build targets — enforced by the CI contrast lint (§7).

**Text on lit fills:** unchanged, `#2A1B04` on accent 500 — the reason lit fills could stay identical.

**Meter face inverts (the photographic negative):** face `#241C2E` (ink), markings `#F1E8D6` (cream), needle `fever/500`, red zone `rose/400` (pops on ink). The *scarcity rule survives*: the ink face is Day’s one rare premium surface, exactly as cream was Noir’s.

**Semantic mapping, severity colors, domain assignments:** unchanged (domains are structural, not decorative).

### 1.3 Surfaces & shadows

| Level | Day construction |
|---|---|
| L0 backdrop | `paper/1` + Wave @ 8% α (copa-biased) + grain 2% |
| L1 card | `paper/2`, `r/lg`, 1dp `hair` **top edge** + shadow `0 1 2 rgba(36,28,46,.06)` |
| L2 raised | `paper/3` + shadow `0 4 12 rgba(36,28,46,.10)` + top hairline |
| L3 sheet | `paper/0` @ 92%, blur 24, `r/xl`, shadow `0 -8 32 rgba(36,28,46,.16)` |
| Field (input) | `paper/3` recessed: inner shadow `inset 0 1 3 rgba(36,28,46,.12)` |
| Toast | **always Noir** — ink/2 chip on paper (a backstage note; §1.7) |
| Banner | wash bg + accent 700 text + LED 600 |

### 1.4 Component conversion table — every signature component

| Component | Copacabana Day construction |
|---|---|
| **Wave** | 8% α, drifts **fever at 06:00–09:00 & 17:00–19:00** (golden hours), copa at midday. Living-stage logic is *sunrise/sunset*, not evening. |
| **VU/full** | Ink face, cream markings, amber needle, red zone rose/400. Physics unchanged. |
| **VU/strip · VU/led** | Track on paper/3; segments ink 20% → accent 600 → rose 600 in red zone. |
| **EQ loader** | Bars ink 55% α; resolve beat unchanged. |
| **Rocker** | OFF: cap `paper/5`, engraved `off` in espresso 40%. ON: **face stays accent 500 gradient** (lit), cap cream, `on` ink — identical geometry, it just reads as sunlight. LED: accent 600 + 8% glow. |
| **Fader** | Track paper/4, ticks ink 20%, thumb cream with ink center-line + shadow `0 1 3`. Drag glow = accent 700 ring. |
| **LED** | `on` = accent 600 + 12% glow · `off` = ink 20% · blink/err unchanged. |
| **Sleeve card** | Art gets ink `hair` ring + `0 2 8 rgba(36,28,46,.10)`. Badges: ink glyphs on cream 80% chips. |
| **Amp card** | Active card: wash container + **ink fill stamp** `ON STAGE` (ink 900, cream text) + ½α under-glow + accent hairline. |
| **Chip** | `paper/3` + hairline, ink text. Active: wash bg + accent 700 text + LED 600. |
| **Source selector** | Recessed field `paper/3`; active key = **ink 900 fill, cream text**, LED above 600. |
| **Buttons** | Primary Lit: identical amber fill. Ghost: ink hairline + ink 900 text. Key: `paper/3` + LED. Danger: rose 700 border/text. Hold ring: accent 600. |
| **Hold-to-confirm** | Ring accent 600; label crossfade unchanged; 800 ms. |
| **Sheet / Dialog** | Per §1.3. Grabber knurl: espresso 40%. |
| **Toast** | **Stays Noir** — ink/2 + cream text + hairline + shadow. One dark chip on paper reads as a backstage note, and toasts float over games/media where dark always wins. |
| **Banner** | Wash bg + 700 text + LED. |
| **Stepper** | Track on paper/3; complete = palm 600; failed = rose 600. |
| **Encore illustrations** | Stroke `text/espresso`, single accent `fever/600`, fills paper/2 @ 60%. Same drawings. |
| **Deck** | `paper/1`, top `hair`; active icon filled accent **600** (≥3:1 for graphics), label ink, underline 2dp accent 600. Attention LEDs 600/blink. |
| **Marquee** | `paper/1` transparent over wave; wordmark: `Samba` ink serif + `S3` Unbounded **fever/700**. Status icons dark. |
| **Hero scrims** | Inverted: top scrim `paper/0` 70→0 over 96dp; bottom scrim 60° `paper/0` 0→92% under the ink title. |
| **Phosphor traces** (scope, test bench, sticks) | Cream traces on ink panels — **these panels stay ink-faced** in Day (darkroom instruments). |
| **Blueprint grid** | Dots ink 8%, 8dp lattice; frame dim = paper scrim 30%; safe guides rose 600 dashed. |

### 1.5 The runtime is always Noir (hard rule)

The game itself is a dark room. Regardless of theme:

- **In-game overlay, Intermission, Quick rack, launch ritual — always Bossa Noir.**
- If the app is in Copacabana Day, the needle-drop ritual *dims into Noir* on purpose: house lights down, record spins, game boots. (Write the line in the ritual spec: **“the needle drop always happens in the dark.”**)
- Exit to Intermission stays Noir; returning to the Deck crossfades back to the user’s room.

### 1.6 Media & system integration

- Status/nav bar icons: **dark**. Splash: `paper/0` background, ink wordmark.
- Game art & screenshots: the ink hairline + soft shadow (§1.4) keeps artwork framed like prints, not floating thumbnails.
- Light header scrims ensure 4.5:1 for ink text over any blurred art.

### 1.7 Theme switching — “the dimmer”

- Setting lives in **System › appearance** — Source selector with three keys: `noir` · `daylight` · `follow system`, each key carrying a **mini preview card** (56dp: room swatch + amber rocker glyph so you can see the lights stay lit).
- Switch = **320 ms crossfade** at the root (`AnimatedContent`): rooms and hairlines dissolve, **lit fills hold steady through the whole crossfade** — you see the lights stay on while the room changes around them. That beat is the point; do not fade the accents.
- The Wave retints on the same 320 ms beat.
- No restart, no activity recreate. Token swap only.
- Setup wizard offers daylight **pre-selected** when the system is in light mode.

### 1.8 Copacabana Day QA checklist

1. No cream text on paper anywhere (lint the token dump — cream appears only inside ink faces and lit fills).
2. Accent-as-text uses 700 only; accent-as-glyph 600+; accent-as-fill 500.
3. VU faces are ink with cream markings; needle physics unchanged; red zone readable.
4. Glow audit: every glow at ≤ half its Noir alpha; active cards additionally carry the accent hairline.
5. Toasts and the entire runtime layer are still Noir.
6. Contrast lint passes: body ≥ 7:1, accent text ≥ 4.5:1, glyphs ≥ 3:1 on paper.
7. One 700-step family member never appears as a fill; one 500 never appears as text on paper.
8. The dimmer crossfade holds lit fills at constant color through the transition.
9. Encore illustrations re-tinted espresso/fever-600 (no cream-on-paper line art).
10. Status bar icons flip correctly at the 320 ms beat.

---

# 2 · REDLINE A — GAME DETAIL (“The Sleeve”)

**Reference canvas:** 412 × 892 dp, portrait, edge-to-edge, gesture nav. Status bar 24dp. Deck 72dp. All values absolute dp from screen top-left. Where the title wraps, formulas are given — the layout is **bottom-anchored at the sleeve**, not top-anchored, so short titles rise cleanly.

### 2.1 Master redline (portrait, 1-line title)

```
x:    0        16         128                              356  396 412
      │        │          │                                │    │    │
y=0   ├────────┼──────────┼────────────────────────────────┼────┼────┤
      │ ▓▓▓▓▓▓▓ HERO · 256dp · cover-crop · blur r=24 ▓▓▓▓ │    │    │
      │ ▓ scrim 60°: ink/0 0% (top) → 88% (bottom)       ▓ │    │    │
      │ ▓ status scrim: 70% → 0 over first 96dp          ▓ │    │    │
y=28  │ [◀ 48×48]                              [⋯ 48×48]  ▓ │    │    │
      │ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │    │    │
y=192 │  ┌──────────┐   from the crate  ← eyebrow · 18h · fever    │
      │  │ SLEEVE   │   DEMON’S SOULS   ← hero 40/44 · Unbounded   │
      │  │ 96 × 96  │                    (2-line clamp, 268dp max) │
      │  │ squircle │   BLUS30443 · PS3 · 4.90 · 26.4 GB ← m2 mono │
y=288 │  └──────────┘                                           │
y=308 │ [ ▶ PLAY  · flex 200 × 52 ]   [⚙ 48][🩹 48][🎮 48]      │
y=362 │    rpcs3 core · ready            ← m3 · ghost · w = PLAY  │
y=376 │  overview   settings   patches   ← tabs 48 · underline 24×2│
y=424 ├────────────────────────────────── hairline, full width ───┤
y=448 │  description · body 14/20 · 3 lines · margins 16          │
      │  ── ledger · rows 40 · dotted leaders ───────────────────  │
      │  developer ······· FromSoftware      rating ······· CERO D │
      │  ┌ performance snapshot · L1 card · r18 · pad 16 ────────┐│
      │  │ last sessions  ← eyebrow                              ││
      │  │ [VU 120×88][gap 10][VU 120×88][gap 10][VU 120×88]     ││
      │  │ [ sparkline · 48h · copa 2dp · 5 dots ]               ││
      │  │ played 41h 12m · last today 20:14        [play again] ││
      │  └───────────────────────────────────────────────────────┘│
      │            ⋮ content scrolls — bottom pad 96 (deck+24)    │
y=820 ├──────────────────────────────────────────────────────────┤
      │  crate    tune    pad    parts    scope          deck 72  │
y=892 └──────────────────────────────────────────────────────────┘
```

### 2.2 Coordinate tables

**A · Hero block**

| # | Element | x | y | w | h | Spec |
|---|---|---|---|---|---|---|
| 1 | status scrim | 0 | 0 | 412 | 96 | gradient ink/0 70→0 |
| 2 | hero backdrop | 0 | 0 | 412 | 256 | cover-crop art, blur r 24, 60° scrim 0→88% |
| 3 | back key | 8 | 28 | 48 | 48 | Ghost-over-art, 24dp icon, focus ring domain |
| 4 | more key | 356 | 28 | 48 | 48 | same |
| 5 | sleeve art | 16 | 192 | 96 | 96 | squircle n=5, 1dp hairline, shadow `0 4 16 40%`, shared-element anchor `art/{id}` |
| 6 | eyebrow | 128 | 196 | 268 | 18 | `from the crate` · fever 500 (Day 700) |
| 7 | title | 128 | 218 | 268 | 44 / 88 | hero 40/44; shrink ladder 100→92→85% then ellipsis |
| 8 | meta line | 128 | titleBottom+4 | 268 | 16 | m2 mono, single line, ` · ` with 6dp gaps |

**B · Action row** — `rowY = max(288, metaBottom) + 20` → 308 (1-line title) / 342 (2-line)

| Element | x | y | w | h | Spec |
|---|---|---|---|---|---|
| PLAY pill | 16 | rowY | 200 | 52 | Primary Lit; icon 20dp + t2/600; 1dp inner top highlight; grows to 216 if keys absent |
| sub-label | 16 | rowY+54 | =PLAY | 12 | m3 ghost: `rpcs3 core · ready` / `booting…` / `session · 00:12:44` |
| key · config | 228 | rowY+2 | 48 | 48 | Key; 24dp glyph; 6dp amber LED top-right inset 6 when overrides exist |
| key · patches | 288 | rowY+2 | 48 | 48 | grape LED + m3 count when patches enabled |
| key · input | 348 | rowY+2 | 48 | 48 | — |

**C · Tabs & content**

| Element | y | h | Spec |
|---|---|---|---|
| tab row | rowY+68 | 48 | 3 equal tabs 137dp; label `type/label` centered; active underline 24×2 centered 3dp above bottom hairline; hairline full width |
| content top | rowY+116 | — | +24 first block; margins 16; section gap 24; bottom padding 96 |
| ledger rows | — | 40 | label t2 mute (max 160dp) · dotted leader 1dp dash 4/6 @12% · value m2 right-aligned to 396 |

**D · Performance snapshot card** — L1, x16 w380, `r/lg` 18, padding 16

| Row | h | Spec |
|---|---|---|
| eyebrow `last sessions` | 18 | +8 below |
| VU row | 88 | 3 × (120×88), gaps 10 → 380 exact |
| sparkline | 48 | +12; 2dp copa 500 polyline, 5 sessions, 4dp dots, hairline baseline |
| footer | 24 | +12; playtime + last played (m2) left; ghost key `play again` 36dp right |
| **card total** | **242** | empty state: single VU at rest + `no sessions yet` eyebrow |

### 2.3 Condensed header & scroll choreography

- **Parallax:** hero translates at scroll × 0.35; sleeve/title/meta scroll at 1× (they are content, not hero).
- **Condensed header** arms at scroll ≥ 240dp, releases at < 200dp (40dp hysteresis — no flapping). Morph 240ms `ease/glide`:

```
y=0   ┌────────────────────────────────────────────────────┐
      │ status scrim 70→0 (24dp)                           │
y=24  │ [sleeve 32]  TITLE · t1 16/22 · 1 line · ellipsis  │
      │  16            56 ←─ 232 wide ──→     [▶ 44][⋯ 44] │  bar 40
y=64  ├────────────────────────────────────────────────────┤
      │  overview · settings · patches   tabs (already     │
      │                                 sticky at y=0)     │
```

Sleeve animates 96→32 via shared bounds; PLAY collapses to a 44dp lit key; more key stays. Hero crossfades out during the same 240ms.

- **Tabs** pin at y=0 (with 24dp status scrim) once the tab row reaches the top; underline transitions 180ms `ease/step`.

### 2.4 Landscape phone (740 × 360)

Hero collapses to a **120dp band**: back (8, 24, 48, 48); sleeve 64×64 at (16, 40); eyebrow (96, 44, h18); title `d2` 20/26 single-line (96, 64, w 400); meta (96, 92). Action row y=136: PLAY fixed 160 + 3 keys, right-aligned to 724. Tabs y=200, content scrolls beneath; deck is the right-side 56dp rail; bottom padding 80.

### 2.5 Tablet (pane variant, see Atlas §4.1)

Margins 24 · sleeve **128** · hero **320** · meters scale to `(paneWidth − 32 − 20) / 3` keeping 120:88 aspect · **no condensed header** (the rail carries context — the pane never docks).

### 2.6 States & edge cases

| State | Treatment |
|---|---|
| No artwork | Sleeve = ink/2 squircle, initial letter Unbounded 800 cream 40sp, serial m3 stamp; hero = grain + radial fever 4% glow |
| Unknown title | `Unknown title` t1 + serial as meta |
| Game running | PLAY label → `resume`, fever LED blinking, sub-label `session · hh:mm:ss` |
| Patches pending | patches key grape LED + m3 count badge |
| Long title (34+ ch) | shrink ladder then ellipsis — hero never wraps past 2 lines |
| Remove flow | more › Remove (Hold) → sheet: summary, checkbox “also delete imported files” (row 56, off), Hold 52 |

### 2.7 Focus & TalkBack order

back → more → sleeve (announced as title + serial) → title → meta → PLAY → keys L→R → tabs → content. VU meters announce value + interpretation; sparkline announces trend (“improving over last 3 sessions”).

---

# 3 · REDLINE B — OVERLAY EDITOR (“Blueprint”)

**Reference canvas:** 412 × 892. Deck & Marquee **hidden** (immersive). Status bar visible.

```
x:  0    8       64        208    256    304 312     352 356   396  412
    │    │       │         │      │      │   │       │    │     │    │
y=0 ├──────────────────────────────────────────────────────────────────┤
    │ status scrim 24 · header bar 48 · header total 72               │
y=24│[◀ 48] GAME NAME · t2 (136dp, ellipsize) [♩44][▦44][↶40][↷40]   │
    │           test      grid    undo    redo                        │
y=72├──────────────────────────────────────────────────────────────────┤
    │ CANVAS 412 × 756                                                  │
    │ · paused frame @ 30% dim (no blur — frame stays recognizable)    │
    │ · grid: 8dp dot lattice · dots r1 · cream 6% (Day: ink 8%)       │
    │ · safe guides: rose dashed 1dp · dash 12/8 · top24/bot40/l+r 16  │
    │ · reach band: bottom 24% (181dp) rose 4% + m3 stamp “thumb reach”│
    │      ┌╌╌╌╌╌╌╌╌╌╌╌╌╌╌┐                                       │
    │      ╎ (L2) ════     ╎   ╱╱ selected: dashed fever 2dp,        │
    │      ╎               ╎   ╱╱ dash 6/4, bounds + 8dp pad        │
    │   (○ stick)      ✕ ▒▒ handles: corners Ø16 fever fill,        │
    │      └╌╌╌╌╌╌╌╌╌╌╌╌╌╌┘  ▒▒ ink ring 1dp; edges 28×8; slop 12    │
    │                                            [1.4×] zoom badge   │
y=812├── toolbar 64 ──────────────────────────────────────────────────┤
y=816│  opacity ▬          scale ▬        labels m3 above tracks     │
y=820│[⇉ 44][＋ 44]  ▬▬ 114 ▬ ▬▬ 114 ▬                [↺ 44]        │
    │ select  add     faders (flex, min 114)          reset(hold)     │
y=876├──────────────────────────────────────────────────────────────────┤ 16 inset
```

### 3.1 Coordinate tables

**Header (72dp)**

| Element | x | y | w | h | Spec |
|---|---|---|---|---|---|
| back | 8 | 24 | 48 | 48 | Ghost |
| title | 64 | 36 | 136 | 24 | t2, game name, middle-ellipsize |
| test key | 208 | 26 | 44 | 44 | play glyph; toggles live test mode |
| grid key | 260 | 26 | 44 | 44 | toggle (state: pressed = grid on) |
| undo / redo | 312 / 356 | 28 | 40 | 40 | disabled at stack bounds (40% α) |

**Toolbar (64dp)**

| Element | x | w | Spec |
|---|---|---|
| select/pan toggle | 8 | 44 | mode key, lit when pan |
| add key | 60 | 44 | opens Add sheet |
| opacity fader group | 112 | 114 | label m3 y=816 h12; track 4dp y=848; thumb 24×18 |
| scale fader group | 234 | 114 | disabled (40%) unless exactly 1 selection |
| reset key | 360 | 44 | **Hold** — resets current layout profile |

**Layout space (the coordinate model)** — controls are stored in a normalized **1000 × 1834 layout grid** (portrait 9:19.5), rendered by lerp into the live canvas minus safe insets. Layouts survive rotation, resolution, and device changes untouched. Scale values are relative to a 412dp baseline width.

### 3.2 Selection & handle geometry

| Element | Geometry | Behavior |
|---|---|---|
| selection ring | bounds + 8dp pad, dashed fever 2dp (6/4) | drawn as superellipse for pads/buttons |
| corner handles ×4 | Ø16 at corners, fever fill, ink 900 ring 1dp | uniform scale about opposite corner, 0.7–1.5×, `spring/needle` release |
| edge handles ×4 | 28×8 (8×24 on horizontal edges) at midpoints | axis stretch — trigger bars only; round controls fall back to uniform |
| min sizes | stick base 72 · dpad 64 · button 40 · trigger height 32 | clamp during resize |
| locked control | ring solid rose 2dp + lock badge 16dp top-right; handles hidden | nudge attempts → 4dp rubber-band + toast once (`control is locked`) |
| multi-select | lasso bounds box, dashed copa | nudge with arrows; scale disabled; `n selected` m3 stamp |

### 3.3 Gesture table

| Gesture | Context | Result |
|---|---|---|
| tap | control | select (ring + handles, 180ms step) |
| double-tap | selected | Properties sheet |
| long-press 350ms + drag | canvas | lasso (dashed copa rect; selects on intersect) |
| drag | selection body | move; snap 8dp; edge magnet at 16dp from safe guide + `EFFECT_TICK` |
| second finger held during drag | any | fine move ×0.25 |
| drag | corner handle | uniform scale |
| drag | edge handle | axis stretch (bars only) |
| pinch | **pan mode only** | canvas zoom 1×–2×; badge m3 top-right |
| double-tap | pan mode | toggle 1×/2× |

Pinch never scales a selection — handles and faders own scaling. (Fat-finger insurance.)

### 3.4 Add sheet (max 78%)

Search field 56 → group headers (eyebrow, 32dp): `sticks / face / pads / triggers / system` → rows 56: 32dp preview glyph (squircle) + name t2 + add glyph. New control lands at canvas center ± 24dp jitter (never stacks exactly), auto-selected, `EFFECT_TICK`.

### 3.5 Properties sheet (double-tap selection)

| Row | h | Control |
|---|---|---|
| header: eyebrow `properties` + name d2 | 64 | — |
| enabled | 56 | rocker |
| lock position | 56 | rocker |
| opacity | 72 | fader 10–90% |
| size | 72 | fader 0.7–1.5× |
| shape | 56 | source selector: round / squircle |
| label visible | 56 | rocker |
| haptics | 56 | rocker |
| deadzone (sticks only) | 72 | fader 0–30% |
| actions | 60 | duplicate key 44 · delete **Hold** 48 |

### 3.6 Undo, zoom, test mode

- **Undo stack 20** entries; each records `moved {id} → (x,y)`, `scaled {id} → 1.2×`, `opacity {id} → 60%` in m2; history sheet rows 48dp.
- **Test mode** (header ♩ key): toolbar + handles fade (120ms), overlay goes **live at full spec opacity** over the static frame, scope-style phosphor traces follow thumbs (cream 2dp, 2s fade, 24-point trail). Exit chip 48×36 top-right. First entry triggers the coach mark: “feel the layout before you save.”
- **Save** instant on change; exit with pending layout migration asks once (sheet).

### 3.7 States

| State | Toolbar | Canvas |
|---|---|---|
| nothing selected | scale disabled; opacity = **global** | bare grid + guides |
| single selection | both faders live | ring + handles |
| multi (n) | scale disabled; opacity applies to all | lasso box + per-control rings |
| locked | unchanged | rose ring, no handles |
| test mode | hidden | live overlay + traces, exit chip |
| zoomed (pan) | unchanged | pan enabled, zoom badge |

### 3.8 Tablet variant (see Atlas)

Toolbar becomes a **persistent right rail 240dp** — selection block, faders, shape, actions, and history list stacked; Properties *sheet disappears* (rail replaces it). Canvas takes the remainder.

---

# 4 · TWO-PANE ATLAS — tablet · fold · landscape phone

### 4.0 Rules

1. Two-pane when **width ≥ 600dp AND height ≥ 480dp**. Landscape phones (tall-ness < 480) use the right-rail deck + single column.
2. Rail **280dp** fixed (fold: hinge-aware, §4.6). Rail bg = `screen`, pane bg = `backdrop` + 1dp hairline divider — the *studio split*.
3. Selection model: rail tap → pane updates + rail row highlights (LED + primary text); focus follows for TalkBack; **back from pane returns focus to the rail row**.
4. Pane content follows every redline in *pane variant* (margins 24, no condensed headers — the rail is the context).
5. Fold/unfold = 240ms cross-dissolve, zero state loss (ViewModel-backed).

### 4.1 Crate (reference tablet 800×1280)

```
┌──────────────────────────────────────────────────────────────┐800
│ Samba s3 ─── ticker ──────────────────────────────── ( ◉ M ) │ Marquee 64
├───────────┬─ 1dp hairline ───────────────────────────────────┤
│ RAIL 280  │ PANE 520 · backdrop                             │
│ the crate │  ┌ Redline A · pane variant ─────────────────┐   │
│ ┌───────┐ │  │ margins 24 · sleeve 128 · hero 320        │   │
│ │search │ │  │ meters (472−32−20)/3 = 140×102            │   │
│ │  56   │ │  │ shared-element art lands inside the pane  │   │
│ └───────┘ │  └───────────────────────────────────────────┘   │
│ rows 64:  │                                                  │
│ [art 48]  │                                                  │
│ [art 48]⋮ │                                                  │
│ footer:   │                                                  │
│ scan key  │                                                  │
├───────────┴──────────────────────────────────────────────────┤
│  crate   tune   pad   parts   scope                deck 72   │
└──────────────────────────────────────────────────────────────┘
```

### 4.2 Tune

```
├───────────┬──────────────────────────────────────────────────┤
│ RAIL 280  │ PANE                                               │
│ search 56 │  cpu & spu        ← eyebrow · copa                 │
│ presets:  │  CPU & SPU        ← d1                             │
│ (silent   │  01 · ppu ─────── hairline leader                  │
│  cinema)  │  rows 76 (faders 88) — full §5.5.1 anatomy,        │
│ (main     │  unchanged; content max-w 640 centered             │
│  stage)   │                                                    │
│ (arena)   │                                                    │
│ ───────   │                                                    │
│ 7 categ.  │                                                    │
│ rows 64   │                                                    │
```

### 4.3 Pad

```
├───────────┬──────────────────────────────────────────────────┤
│ RAIL 280  │ PANE                                               │
│ devices:  │  pad diagram 260dp → scales to pane min(w,h)      │
│ [pad 64]  │  remap rows / test bench / deadzone faders        │
│ [pad 64]  │  profiles menu in pane header                     │
│ none →    │  (no device selected → coiled-cable encore)       │
```

### 4.4 Parts

```
├───────────┬──────────────────────────────────────────────────┤
│ RAIL 280  │ PANE                                               │
│ firmware ▸│LED  firmware card / amp room / stitching room /   │
│ drivers  ▸│LED  profiles — each full §5.7–5.13 spec, pane     │
│ patches  ▸│LED  margins 24. Attention LEDs live here.          │
│ profiles ▸│LED                                                │
```

### 4.5 Scope

```
├───────────┬──────────────────────────────────────────────────┤
│ RAIL 280  │ PANE                                               │
│ severity: │  log stream · virtualized · follow pill bottom-R  │
│ (F)(E)(W) │  of pane (not screen)                             │
│ (I)(D)(ok)│                                                    │
│ subsystem │                                                    │
│ health:   │                                                    │
│ vulkan ▂▄▆│ ← 12dp LED strip, error-rate history per           │
│ kernel ▁▁▂│   subsystem — the rail is a health rack            │
└───────────┴──────────────────────────────────────────────────┘
```

### 4.6 Foldable unfolded (reference 673×841)

```
┌─────────────────────────────────────┐ 673
│ Marquee 64                          │
├───────────┬─┬───────────────────────┤
│ RAIL 262  │s│ PANE 387              │
│           │p│                       │
│           │i│ spine: 2dp cream-10%  │
│           │n│ hairline · nothing    │
│           │e│ interactive within    │
│           │ │ ±16dp of the seam ·   │
│           │ │ 24dp gutter total     │
├───────────┴─┴───────────────────────┤
│ deck 72 · full width, seam-aware    │
└─────────────────────────────────────┘
```

Split lands on the reported hinge bounds; if the hinge is off-center, the rail shrinks before the pane does (list tolerates compression better than redlined content).

### 4.7 Landscape phone (740×360)

```
┌───────────────────────────────────────────────────┬────┐
│ Samba s3 ────── ticker ────────────────── ( ◉ )   │ c  │ 48 marquee
│ 4-col grid · art 151dp · gutters 16               │ r  │
│ (412−…) — (740−56−5×16)/4 = 151                  │ a  │ 56 deck rail,
│                                                   │ n  │ icons only
│ single column · scroll · no two-pane (h < 480)    │ e  │
└───────────────────────────────────────────────────┴────┘
```

---

# 5 · COMPOSE TOKEN FILE — source of truth in code

```kotlin
@file:Suppress("unused")

// ──────────────────────────────────────────────────────────────
//  SAMBA S3 · Bossa design system — single source of truth
//  Docs: SS3-D-001 App A · SS3-D-002 §1 (Copacabana Day) · §5
//  Law: components never hardcode a color, font, radius, or
//  curve. If it isn’t a token here, it doesn’t exist.
// ──────────────────────────────────────────────────────────────

package samba.s3.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// import samba.s3.R // wire once fonts are bundled (D-001 §9):
//   R.font.unbounded_600/700/800 · grotesk_400/500/600
//   jbmono_400/500/700 · instrument_serif_italic

// ── §2.2 Domain tints ─────────────────────────────────────────

enum class Domain(
    val label: String,
    internal val pick: (BossaColors) -> Accent,
) {
    Crate("crate", { it.fever }),
    Tune("tune",   { it.copa }),
    Pad("pad",     { it.grape }),
    Parts("parts", { it.palm }),
    Scope("scope", { it.rose }),
}

fun BossaColors.accent(d: Domain): Accent = d.pick(this)

// ── Accents · D-001 §2.1 + D-002 §1.2 ──────────────────────────
// c500 is the “lit fill” — IDENTICAL in both themes
// (“the lights don’t change; the room does”).
// c700, container and glow are theme-resolved.

@Immutable
data class Accent(
    val c300: Color, val c400: Color, val c500: Color,
    val c600: Color, val c700: Color,
    val container: Color,          // deep-tint (Noir) / wash (Day)
    val glow: Color,               // pre-themed glow alpha
) {
    val fill: Color get() = c500           // buttons, rocker ON, LEDs
    val fillDeep: Color get() = c600       // pressed / warn fill
    val onFill: Color get() = InkOnAccent  // text on lit fills
}

val InkOnAccent = Color(0xFF2A1B04)

// ── Palettes ───────────────────────────────────────────────────

@Immutable
data class BossaColors(
    // the room
    val backdrop: Color, val screen: Color,
    val surface1: Color, val surface2: Color,
    val hover: Color,      // pressed / hover wash
    val field: Color,      // recessed input (jack, not bump)
    // text
    val textPrimary: Color, val textSecondary: Color,
    val textMute: Color, val textGhost: Color, val textFaint: Color,
    // lines
    val hairline: Color, val hairlineStrong: Color,
    // stage lights
    val fever: Accent, val copa: Accent, val rose: Accent,
    val palm: Accent, val grape: Accent,
    // the meter face — the one rare premium surface
    val meterFace: Color, val meterMark: Color,
    val meterNeedle: Color, val meterRed: Color,
    // ambience
    val grainAlpha: Float, val waveAlpha: Float,
    val isLight: Boolean,
) {
    /** accent as TEXT/markings: 700 on paper, 500 on ink */
    fun mark(a: Accent): Color = if (isLight) a.c700 else a.c500
    fun mark(d: Domain): Color = mark(accent(d))
    /** large active glyphs (deck icons): 600 on paper, 500 on ink */
    fun glyph(d: Domain): Color =
        accent(d).let { if (isLight) it.c600 else it.c500 }
}

fun bossaNoir(): BossaColors = BossaColors(
    backdrop = Color(0xFF060409), screen = Color(0xFF0D0A12),
    surface1 = Color(0xFF151020), surface2 = Color(0xFF1D1830),
    hover = Color(0xFF272040), field = Color(0xFF151020),
    textPrimary = Color(0xFFF7F2E7), textSecondary = Color(0xFFE8E1D0),
    textMute = Color(0xFFA49BB0), textGhost = Color(0xFF6B6380),
    textFaint = Color(0xFF4B4460),
    hairline = Color(0x14F7F2E7), hairlineStrong = Color(0x24F7F2E7),
    fever = Accent(
        Color(0xFFFFD9A0), Color(0xFFFFC878), Color(0xFFFFB454),
        Color(0xFFE89A33), Color(0xFF8F5500),
        Color(0xFF3A2A10), Color(0x52FFB454)),
    copa = Accent(
        Color(0xFFA9F0E4), Color(0xFF7BE7D8), Color(0xFF45D9C6),
        Color(0xFF2CB4A4), Color(0xFF0B7F72),
        Color(0xFF0F332F), Color(0x4745D9C6)),
    rose = Accent(
        Color(0xFFFF8397), Color(0xFFFF7286), Color(0xFFFF5D73),
        Color(0xFFD9465B), Color(0xFFC22B42),
        Color(0xFF3A1420), Color(0x47FF5D73)),
    palm = Accent(
        Color(0xFF9BE8A9), Color(0xFF85E296), Color(0xFF6FDB8F),
        Color(0xFF4CB46E), Color(0xFF2E7D48),
        Color(0xFF123322), Color(0x3D6FDB8F)),
    grape = Accent(
        Color(0xFFD5C0FF), Color(0xFFBCA3FF), Color(0xFFA584FF),
        Color(0xFF8A63F2), Color(0xFF6A45C9),
        Color(0xFF221840), Color(0x42A584FF)),
    meterFace = Color(0xFFF1E8D6), meterMark = Color(0xFF241C2E),
    meterNeedle = Color(0xFFFFB454), meterRed = Color(0xFFD9465B),
    grainAlpha = 0.04f, waveAlpha = 0.12f, isLight = false,
)

fun copacabanaDay(): BossaColors = BossaColors(
    backdrop = Color(0xFFFAF7EC), screen = Color(0xFFF5F1E6),
    surface1 = Color(0xFFECE5D4), surface2 = Color(0xFFE2D9C3),
    hover = Color(0xFFD8CDB5), field = Color(0xFFD8CDB5),
    textPrimary = Color(0xFF241C2E), textSecondary = Color(0xFF352D40),
    textMute = Color(0xFF5E5568), textGhost = Color(0xFF8E86A0),
    textFaint = Color(0xFFB4ADC0),
    hairline = Color(0x1A241C2E), hairlineStrong = Color(0x29241C2E),
    fever = Accent(
        Color(0xFFFFD9A0), Color(0xFFFFC878), Color(0xFFFFB454),
        Color(0xFFE89A33), Color(0xFF8F5500),
        Color(0xFFF7E6C8), Color(0x29FFB454)),
    copa = Accent(
        Color(0xFFA9F0E4), Color(0xFF7BE7D8), Color(0xFF45D9C6),
        Color(0xFF2CB4A4), Color(0xFF0B7F72),
        Color(0xFFD6F1EB), Color(0x1F45D9C6)),
    rose = Accent(
        Color(0xFFFF8397), Color(0xFFFF7286), Color(0xFFFF5D73),
        Color(0xFFD9465B), Color(0xFFC22B42),
        Color(0xFFFBE1E5), Color(0x1FFF5D73)),
    palm = Accent(
        Color(0xFF9BE8A9), Color(0xFF85E296), Color(0xFF6FDB8F),
        Color(0xFF4CB46E), Color(0xFF2E7D48),
        Color(0xFFDEF0E3), Color(0x1C6FDB8F)),
    grape = Accent(
        Color(0xFFD5C0FF), Color(0xFFBCA3FF), Color(0xFFA584FF),
        Color(0xFF8A63F2), Color(0xFF6A45C9),
        Color(0xFFE9E1FA), Color(0x21A584FF)),
    meterFace = Color(0xFF241C2E), meterMark = Color(0xFFF1E8D6),
    meterNeedle = Color(0xFFFFB454), meterRed = Color(0xFFFF7286),
    grainAlpha = 0.02f, waveAlpha = 0.08f, isLight = true,
)

// ── §2.3 Type ──────────────────────────────────────────────────

private val Unbounded = FontFamily(
    Font(R.font.unbounded_600, FontWeight.SemiBold),
    Font(R.font.unbounded_700, FontWeight.Bold),
    Font(R.font.unbounded_800, FontWeight.ExtraBold),
)
private val Grotesk = FontFamily(
    Font(R.font.grotesk_400, FontWeight.Normal),
    Font(R.font.grotesk_500, FontWeight.Medium),
    Font(R.font.grotesk_600, FontWeight.SemiBold),
)
private val Mono = FontFamily(
    Font(R.font.jbmono_400, FontWeight.Normal),
    Font(R.font.jbmono_500, FontWeight.Medium),
    Font(R.font.jbmono_700, FontWeight.Bold),
)
private val Soul = FontFamily(
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

@Immutable
data class BossaType(
    val hero: TextStyle, val d1: TextStyle, val d2: TextStyle,
    val t1: TextStyle, val t2: TextStyle, val body: TextStyle,
    val label: TextStyle, val micro: TextStyle, val eyebrow: TextStyle,
    val m1: TextStyle, val m2: TextStyle, val m3: TextStyle,
)

val BossaTypes: BossaType = BossaType(
    hero = TextStyle(
        fontFamily = Unbounded, fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.4).sp),
    d1 = TextStyle(
        fontFamily = Unbounded, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.14).sp),
    d2 = TextStyle(
        fontFamily = Unbounded, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp),
    t1 = TextStyle(
        fontFamily = Grotesk, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp),
    t2 = TextStyle(
        fontFamily = Grotesk, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp),
    body = TextStyle(
        fontFamily = Grotesk, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp),
    label = TextStyle(
        fontFamily = Grotesk, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.48.sp),
    micro = TextStyle(
        fontFamily = Grotesk, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.88.sp),
    eyebrow = TextStyle(
        fontFamily = Soul, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 18.sp, fontStyle = FontStyle.Italic),
    m1 = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 22.sp),
    m2 = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp),
    m3 = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Normal,
        fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

// ── §2.5 Shape · §2.4 Space · §2.9 Motion ─────────────────────

object BossaR {
    val xs = 4.dp; val sm = 8.dp; val ctl = 14.dp
    val lg = 18.dp; val xl = 26.dp
    // squircle (game art ONLY): continuous-corner path n=5;
    // fallback RoundedCornerShape(percent = 22)
}

object BossaSp {
    val s1 = 4.dp;  val s2 = 8.dp;  val s3 = 12.dp; val s4 = 16.dp
    val s5 = 20.dp; val s6 = 24.dp; val s8 = 32.dp; val s10 = 40.dp
    val s12 = 48.dp; val s16 = 64.dp
}

object BossaM {
    val Step  = CubicBezierEasing(0.22f, 1.14f, 0.32f, 1f)  // 180ms
    val Glide = CubicBezierEasing(0.40f, 0.00f, 0.20f, 1f)  // 240ms
    val Drop  = CubicBezierEasing(0.34f, 0.80f, 0.24f, 1f)  // 300ms
    const val STEP_MS = 180
    const val GLIDE_MS = 240
    const val DROP_MS = 300
    const val SWAY_MS = 1400       // ambient loops (wave, ticker)
    // needle spring: stiffness 170f, dampingRatio 0.75f
    // sheet spring: stiffness 220f, dampingRatio 0.90f
    const val HOLD_MS = 800        // hold-to-confirm ring
    const val DIMMER_MS = 320      // theme crossfade (D-002 §1.7)
}

// ── Plumbing ───────────────────────────────────────────────────

val LocalBossaColors = staticCompositionLocalOf { bossaNoir() }
val LocalBossaType = staticCompositionLocalOf { BossaTypes }

object Bossa {
    val C: BossaColors get() = LocalBossaColors.current
    val T: BossaType get() = LocalBossaType.current
    val R: BossaR get() = BossaR
    val Sp: BossaSp get() = BossaSp
    val M: BossaM get() = BossaM
}

enum class Appearance(val stamp: String) {
    Noir("bossa noir"),
    Day("copacabana day"),
    System("follow system"),
}

@Composable
fun SambaS3Theme(
    appearance: Appearance = Appearance.System,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val colors = remember(appearance, dark) {
        when (appearance) {
            Appearance.Noir -> bossaNoir()
            Appearance.Day -> copacabanaDay()
            Appearance.System -> if (dark) bossaNoir() else copacabanaDay()
        }
    }
    // §1.7 “the dimmer”: host swaps content inside a 320ms
    // AnimatedContent crossfade; lit fills hold constant through
    // the whole beat — the wave retints on the same tick.
    CompositionLocalProvider(
        LocalBossaColors provides colors,
        LocalBossaType provides BossaTypes,
    ) { content() }
}

// ── Two canon micro-components (token discipline, demonstrated) ─

@Composable
fun Eyebrow(text: String, domain: Domain, modifier: Modifier = Modifier) {
    // lowercase, always (rule 10). One per screen region.
    Text(
        text = text,
        style = Bossa.T.eyebrow,
        color = Bossa.C.mark(domain),
        modifier = modifier,
    )
}

@Composable
fun Stamp(text: String, modifier: Modifier = Modifier) {
    // m3 mono ghost — corner stamps, tick labels, version marks
    Text(
        text = text,
        style = Bossa.T.m3,
        color = Bossa.C.textGhost,
        modifier = modifier,
    )
}
```

---

## 6 · APPENDIX A · DELTA — Copacabana Day token JSON

```json
{
  "theme": "copacabanaDay",
  "codename": "daylight",
  "room": {
    "paper": ["#FAF7EC", "#F5F1E6", "#ECE5D4", "#E2D9C3", "#D8CDB5", "#CCC0A4"]
  },
  "text": {
    "ink": "#241C2E", "espresso": "#352D40", "slate": "#5E5568",
    "fog": "#8E86A0", "wisp": "#B4ADC0"
  },
  "hairline": "rgba(36,28,46,0.10)",
  "hairlineStrong": "rgba(36,28,46,0.16)",
  "ramp700": {
    "fever": "#8F5500", "copa": "#0B7F72", "rose": "#C22B42",
    "palm": "#2E7D48", "grape": "#6A45C9"
  },
  "wash": {
    "fever": "#F7E6C8", "copa": "#D6F1EB", "rose": "#FBE1E5",
    "palm": "#DEF0E3", "grape": "#E9E1FA"
  },
  "meterFace": { "face": "#241C2E", "mark": "#F1E8D6",
                 "needle": "#FFB454", "red": "#FF7286" },
  "onLitFill": "#2A1B04",
  "glow": "half alpha of noir",
  "grain": 0.02, "wave": 0.08,
  "shadows": {
    "l1": "0 1 2 rgba(36,28,46,.06)",
    "l2": "0 4 12 rgba(36,28,46,.10)",
    "l3": "0 -8 32 rgba(36,28,46,.16)"
  },
  "alwaysNoir": ["inGameOverlay", "intermission", "quickRack",
                 "launchRitual", "toast"]
}
```

---

## 7 · QA ADDITIONS TO THE SHIP GATE (D-001 §10)

16. Copacabana Day passes its own checklist (§1.8) — full app walkthrough in daylight.
17. The dimmer: lit fills constant through the 320ms crossfade; status icons flip on beat.
18. Build matches Redline A & B within ±2dp (screenshot-diff both themes, both redlines).
19. Two-pane at ≥600×480 on **all five decks**; landscape phone uses the right-rail deck; fold seam clear ±16dp.
20. Contrast lint in CI: every `mark()` pairing ≥ 4.5:1, every glyph ≥ 3:1 — fails the build, not the review.

---

*End of addendum — SS3-D-002 Rev B. Same studio, daylight hours.*
````

Quick summary of what changed:

- **Copacabana Day** is now a full canon theme built on one governing rule — *“the lights don’t change; the room does”* — so lit amber fills are identical in both themes (which is why the Compose token layer needs zero component branching). New 700 accent steps + wash containers make accents AA-legible as text on paper, the VU face inverts to an ink “photographic negative,” and the entire runtime layer (overlay, intermission, ritual, toasts) deliberately stays Noir — *the needle drop always happens in the dark*.
- **Redlines A & B** are measured to the dp on a 412×892 reference: every key, gap, handle, sheet row, and hysteresis threshold (e.g., condensed header arms at 240dp scroll, releases at 200dp), plus the normalized 1000×1834 layout-space model for the overlay editor.
- **The Two-Pane Atlas** covers all five decks on tablet, the fold with its “spine” seam rule, and the landscape-phone exception.
- **The Kotlin token file** is the literal single source of truth — colors, type, radii, curves, the theme dimmer, and two sample canon components.

Want me to do redlines for a third surface next (I'd suggest the Intermission menu or the Setup Wizard's device-check step — both are signature moments), or produce the squircle/needle-drop specs as pseudo-code for the custom `Canvas` composables?
