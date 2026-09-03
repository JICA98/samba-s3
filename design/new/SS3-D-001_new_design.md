Here's the complete design spec. I went with a direction I'm calling **"Bossa Noir"** — the PS3's piano-black 2006 futurism fused with warm Rio-night Hi-Fi equipment (VU meters, faders, rocker switches). Nothing about it looks like a template app.

````markdown
# SAMBA S3 — Design Specification
### Design language: **Bossa Noir** · v1.0 “Copacabana”

```
DOC NO. SS3-D-001 · REV A · CLASS: INTERNAL
SCOPE: ALL SURFACES — PHONE / TABLET / FOLDABLE / LANDSCAPE
STATUS: CANON — CHANGES REQUIRE DESIGN SIGN-OFF
CUT, SOLDERED & LACQUERED BY HAND.
```

---

## 0 · HOW TO READ THIS DOCUMENT

| Notation | Meaning |
|---|---|
| `token/name` | Design token — see Appendix A. Single source of truth. |
| `dp` / `sp` | Density-independent pixel / scale-independent pixel. |
| 🔴🟡🟢🔵 | LED states (see §3.6 — everything status-like renders as an LED). |
| ⚠ RESTART | Setting requires emulator restart; feeds the “pending changes” system (§6.7). |
| **Hold** | Hold-to-confirm interaction (§3.12). Never a plain confirm dialog. |

Every screen spec contains: **Purpose → Anatomy (measured) → Behavior → Motion → States → Microcopy → Edge cases.** If a state isn’t drawn, it doesn’t ship.

---

## 1 · CONCEPT

### 1.1 The story

Samba S3 is a PS3 emulator frontend, but the name is a dance. So the app is not “a launcher” — it is **a sound system**. The phone becomes a piece of warm, late-night Hi-Fi equipment: the library is a **record crate**, settings are a **mixing desk**, GPU drivers are **amp heads**, the pause menu is an **intermission**, performance is shown on **analog VU meters with real needle physics**.

The visual soul is the collision of two worlds:

- **2006 piano-black futurism** — the PS3 era: gloss, hairline highlights, the drifting XMB ribbon, chrome-adjacent ambers and teals.
- **Rio at midnight** — warm amber stage light, teal lagoon reflections, plum-black ink, a faint film grain like an old record sleeve. Bossa nova record-cover typography: a geometric grotesque stacked against a single expressive italic serif.

Almost every Android app today is cold: blue or purple on near-black, glassmorphism, Material defaults. **Bossa Noir is warm on dark.** Amber needles. Cream meter faces. It should feel like an object you own, not a screen you scroll.

### 1.2 The ten rules

1. **Warm on dark, always.** No pure black (#000), no pure white (#FFF), no cold blues as neutrals.
2. **Light is earned, not sprayed.** Glow and lit surfaces appear only on things that are literally *powered on* (running game, active driver, live VU). Everything else is matte ink.
3. **Numbers are mono, names are grotesk, soul is serif.** Three typefaces, three jobs, never mixed up.
4. **Analog honesty.** Performance is a needle, progress is a metered bar, loading is an equalizer. The app behaves like hardware.
5. **One accent per region.** Domain tints (§2.2) color-code the five decks; they appear as LEDs, icons, and eyebrows — never as large fills.
6. **The deck tells you what needs tuning.** Attention LEDs on navigation (§6.6) replace badge spam.
7. **Destructive actions cost time.** Hold-to-confirm, 800 ms, with a ring filling. No accidental deletes.
8. **Never a bare system dialog.** Permission primers, file pickers framed by our sheets, errors with personality.
9. **Motion lands, it doesn’t slide by.** Every transition *settles* with 2% overshoot, like a needle dropping into a groove.
10. **Lowercase serif eyebrows.** Every section title is preceded by a small Instrument Serif italic line (`cpu & spu`, `the amp room`). This is the signature. It appears exactly once per screen region — never twice.

### 1.3 References & anti-references

**Yes:** XMB ribbon (2006) · Braun/TASCAM mixing consoles · VU meters on reel machines · Bossa Nova sleeve typography (warm paper, geometric caps + one italic) · PS3 piano-black lacquer · Korg knob knurling · oscilloscope phosphor.

**No:** default Material purple · frosted glass soup · neon cyberpunk · gradient-everything · emoji in UI · generic rounded card stacks.

---

## 2 · FOUNDATIONS

### 2.1 Color — the “Ink & Stage” palette

Neutrals are **plum-tinted inks**, text is **warm cream**. Never cool gray.

**Ink (surfaces)**

| Token | Hex | Use |
|---|---|---|
| `ink/0` | `#060409` | True backdrop behind everything |
| `ink/1` | `#0D0A12` | Screen background |
| `ink/2` | `#151020` | Card level (L1) |
| `ink/3` | `#1D1830` | Raised card / field (L2) |
| `ink/4` | `#272040` | Hover / pressed wash |
| `ink/5` | `#332A4F` | Strong field / divider fill |

**Text**

| Token | Hex | Contrast on `ink/2` | Use |
|---|---|---|---|
| `text/cream` | `#F7F2E7` | ~16:1 | Primary text |
| `text/bone` | `#E8E1D0` | ~12:1 | Secondary |
| `text/mute` | `#A49BB0` | ~6.5:1 | Descriptions, timestamps |
| `text/ghost` | `#6B6380` | ~3.8:1 ⚠ | Decorative stamps only, ≥12 sp, never sole info |
| `text/faint` | `#4B4460` | — | Disabled |
| `line/hair` | `#F7F2E7` @ 8% | — | 1dp hairlines |
| `line/strong` | `#F7F2E7` @ 14% | — | Emphasized borders |

**Stage lights (accents)** — each has a 5-step ramp; 500 is the working step.

| Name | 300 / 400 / **500** / 600 / deep-tint | Glow (32% α) |
|---|---|---|
| `fever` (amber) | `#FFD9A0` `#FFC878` **`#FFB454`** `#E89A33` `#3A2A10` | `rgba(255,180,84,.32)` |
| `copa` (teal) | `#A9F0E4` `#7BE7D8` **`#45D9C6`** `#2CB4A4` `#0F332F` | `rgba(69,217,198,.28)` |
| `rose` (coral) | `#FF8397` `#FF7286` **`#FF5D73`** `#D9465B` `#3A1420` | `rgba(255,93,115,.28)` |
| `palm` (green) | `#9BE8A9` `#85E296` **`#6FDB8F`** `#4CB46E` `#123322` | `rgba(111,219,143,.24)` |
| `grape` (violet) | `#D5C0FF` `#BCA3FF` **`#A584FF`** `#8A63F2` `#221840` | `rgba(165,132,255,.26)` |

**Cream surface (the VU face):** `#F1E8D6` with markings in `#241C2E`. Used *only* inside meters and the boot ritual. It is the single most premium surface in the system — scarcity is what makes it read as craft.

**Semantic mapping**

| Meaning | Token |
|---|---|
| Primary action / active / “on stage” | `fever/500` |
| Informational / data / secondary highlight | `copa/500` |
| Destructive / fatal / error | `rose/500` |
| Success / OK / installed | `palm/500` |
| Special (firmware, patches, experimental) | `grape/500` |
| Warning / pending | `fever/600` |

**Log severity:** fatal `rose/600` · error `rose/500` · warn `fever/600` · info `copa/500` · ok `palm/500` · debug `text/ghost`.

**Rules:** accent fills only on controls ≤ 20% of screen area. Large surfaces stay ink. Accent-on-ink text at 500+ steps only. Never put two accents in the same component (a card is grape OR amber, never both — the VU face is exempt; its needle is amber by physics, not by accent budget).

### 2.2 Domain tints — the five decks

Each top-level destination owns a tint. It colors: the deck icon (active), section eyebrows, focus rings, and attention LEDs *within* that domain. Nothing larger.

| Deck | Tint | Why |
|---|---|---|
| **Crate** (games) | `fever` | The stage light. The show. |
| **Tune** (settings) | `copa` | Cool, technical, dialing-in. |
| **Pad** (controllers) | `grape` | Playful, tactile. |
| **Parts** (firmware/drivers/patches/profiles) | `palm` | Components installed & healthy. |
| **Scope** (logs/debug) | `rose` | Diagnostics: where it hurts. |

### 2.3 Typography

| Role | Font | Weights | Notes |
|---|---|---|---|
| Display / titles / big numbers | **Unbounded** | 600 / 700 / 800 | Wide 2000s geometric. Titles & numerals **only** — never body text. |
| UI / body / labels | **Space Grotesk** | 400 / 500 / 600 | The workhorse. Humanist-tech. |
| Technical values | **JetBrains Mono** | 400 / 500 / 700 | Serials, FPS, hex, paths, timestamps, version stamps. |
| Soul / eyebrows / wordmark | **Instrument Serif** | 400 italic | Lowercase italic, always. |

**Scale (sp / line-height)**

| Token | Size/LH | Font | Tracking | Use |
|---|---|---|---|---|
| `type/hero` | 40 / 44 | Unbounded 800 | −1% | Game detail title |
| `type/d1` | 28 / 32 | Unbounded 700 | −0.5% | Screen titles |
| `type/d2` | 20 / 26 | Unbounded 600 | 0 | Card titles, sheet headers |
| `type/t1` | 16 / 22 | SG 600 | 0 | Setting row titles, list titles |
| `type/t2` | 14 / 20 | SG 500 | 0 | Subtitles, buttons |
| `type/body` | 14 / 20 | SG 400 | 0 | Body, descriptions |
| `type/label` | 12 / 16 | SG 600 | +4% | Chips, tab labels |
| `type/micro` | 11 / 14 | SG 600 | +8%, CAPS | Stamps, deck labels |
| `type/eyebrow` | 14 / 18 | Instrument Serif italic, lowercase | 0 | Section eyebrows — domain tint |
| `type/m1` | 16 / 22 | JBM 500 | 0 | Live values, fader readouts |
| `type/m2` | 12 / 16 | JBM 400 | 0 | Serials, timestamps, hashes |
| `type/m3` | 10 / 14 | JBM 400 | +4% | Corner stamps, tick labels |

**Wordmark:** `Samba` in Instrument Serif italic 28 sp `text/cream`, baseline-aligned with `S3` in Unbounded 800 20 sp `fever/500`, 2dp negative gap. Lockup is never broken, never restacked.

**Rules:** max 2 families + mono per screen. Body text never centered (hero titles may). Lines ≤ 64 characters. All-caps only at `type/micro`. Game titles in *lists* use `type/t1` Space Grotesk (Unbounded is too wide for long names); Unbounded is reserved for the detail hero.

### 2.4 Layout grid

- Base unit **4dp**. Spacing tokens: `4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48 / 64`.
- Phone margins: 16dp portrait / 24dp landscape. Content max-width **640dp**, centered beyond.
- **Tablet / foldable unfolded:** two-pane. Left rail 280dp (list), right pane detail. Rail background `ink/1`, pane `ink/0` + hairline divider — the “studio split”.
- Screen chrome: `Marquee` 64dp top · `Deck` 72dp bottom (phone) · rail 72dp (landscape phone keeps the deck; overlay/editor screens hide both).
- Edge-to-edge: content draws behind system bars; status bar icons light; scrims: top gradient `ink/0` 70%→0 over 96dp, bottom `ink/0` 80%→0 over 96dp.
- Landscape phone: deck becomes a 64dp right-side rail (icons only). All settings pages support landscape via scroll, not re-layout.

### 2.5 Shape

| Token | Value | Use |
|---|---|---|
| `r/xs` | 4 | LEDs housings, tags |
| `r/sm` | 8 | Small buttons, inputs |
| `r/ctl` | 14 | Standard controls |
| `r/lg` | 18 | Cards |
| `r/xl` | 26 | Sheets, hero surfaces |
| `r/pill` | full | Chips, rocker, primary buttons |
| `r/squircle` | continuous-corner, ~22% of width | **Game art tiles only** — the PS3 icon silhouette |

Game tiles use a true superellipse (n≈5 / “squircle”) mask, falling back to `0.22 × width` radius. Everything else uses conventional radii so the superellipse stays *specific to games* — a subtle, learned association.

### 2.6 Surfaces — “the shelf system”

| Level | Surface | Construction |
|---|---|---|
| L0 backdrop | `ink/1` | + Wave (§3.1) + grain (§2.7) |
| L1 card | `ink/2` | `r/lg`, 1dp hairline **top edge only** at `line/hair` — the “lacquer highlight” catching light from above |
| L2 raised | `ink/3` | + shadow `0 8 24 rgba(0,0,0,.45)` + same top hairline |
| L3 sheet | `ink/3` @ 92% | Blur 24dp, `r/xl` top corners, knurled grabber (§3.13) |
| Field (inputs) | `ink/2` | Recessed: inner bottom shadow `inset 0 -2 4 rgba(0,0,0,.5)` — a jack, not a bump |
| **Live surface** | any + under-glow | Domain glow, 24dp blur, 18–32% α, bleeding *below* the card, when the thing it represents is active |
| **Cream surface** | `#F1E8D6` | Meters & boot ritual only |

Shadows are used sparingly; depth is communicated by *hairlines and glow*, in that order.

### 2.7 Texture

One **grain tile**: 128×128 fractal noise PNG, 4% alpha, `overlay` blend, tiled at 1× (never scaled). Applied once to the root of every screen. On L3 sheets at 3%. Cheap (one draw), invisible on screenshots under 200%, but the reason the black never feels like a video-wall.

### 2.8 Iconography — custom set “Corda”

24dp grid · stroke **1.75dp** · round caps & joins · 2dp internal corner radius · terminals snapped to a 2dp lattice · optical centering allowed (max 0.5dp nudge). Duotone option: secondary path at 40% stroke alpha. Active deck icons use the **filled** variant.

Construction quirks (what makes the set ours):

- **Play** = filled triangle inside a superellipse ring (mirrors the game-tile shape).
- **Tune** (settings) = a **fader bank** — three vertical tracks with knobs at staggered heights. Not a gear. Gears are banned.
- **Parts** = chip with pins, one pin bent (the hand-made tell).
- **Profile** = bust wearing a **fedora** (carnival).
- **Firmware** = cartridge with a wave etched on it.
- **Patches** = square with **dashed** border + needle crossing it.
- **Scan** = radar arcs over a leaning disc.
- **Scope** = oscilloscope ring with a live sine.
- Toggle-paired icons rotate 15° when their setting flips (e.g., the knob icons).

Full inventory (58 glyphs) ships as its own export pack (§9). Every icon must be describable in one sentence, or it gets redesigned.

### 2.9 Motion

| Token | Curve / physics | Duration | Use |
|---|---|---|---|
| `ease/step` | `cubic-bezier(.22,1.14,.32,1)` | 180ms | Control feedback, tabs — slight overshoot, the “settle” |
| `ease/glide` | `cubic-bezier(.4,0,.2,1)` | 240ms | Fades, content swap |
| `ease/drop` | `cubic-bezier(.34,.8,.24,1)` | 300ms | Sheets & dialogs arriving |
| `ease/sway` | `cubic-bezier(.45,.05,.55,.95)` | 1400ms loop | Ambient (wave, marquee ticker) |
| `spring/needle` | stiffness 170, damping ratio 0.75 | — | VU needles, rockers, knob caps |
| `spring/sheet` | stiffness 220, damping 0.9 | — | Sheet dismissal |

**Choreography rules:** list entrances stagger 24ms/item, capped at 8 items. Shared element: game tile art → detail hero, 420ms `ease/glide`, corner-radius animates squircle→`r/xl`. Nothing animates larger than 110% of its rest size. Ambient motion ≤ 30fps and pauses when the app is backgrounded or battery-saver is on.

**Reduced motion (§7):** overshoot curves → 80ms linear; wave & ticker freeze; needle springs → 150ms glide with peak-hold retained; EQ loader → single pulsing dot.

### 2.10 Haptics map

| Event | Effect |
|---|---|
| Toggle flip, fader detent, tab select | `EFFECT_TICK` |
| Segment select, rocker land | `CONTEXT_CLICK` |
| Navigation forward/back | `EFFECT_CLICK` |
| Game launch (needle drop) | `EFFECT_HEAVY_CLICK` + `EFFECT_TICK` at 90ms |
| Hold-to-confirm complete | `EFFECT_HEAVY_CLICK` |
| Error toast | `EFFECT_CLICK` ×2, 80ms apart |

All haptics behind one master toggle (System → this app), default **on**.

### 2.11 Sound (optional, default OFF)

Marimba-forward, −18dB ceiling, respects device silent mode, one toggle.

| Event | Sound |
|---|---|
| Boot | Three warm marimba notes D–B–F♯, 520ms |
| Toggle | Woodblock, 950Hz, 40ms |
| Launch | Vinyl needle-drop sweep, 700ms |
| Complete (install/scan) | Two-note bossa “tick-tock” |
| Error | Muted thud + single low note |

---

## 3 · SIGNATURE COMPONENTS

*(Anatomy → variants → states → behavior. These are the reason the app doesn’t look generic.)*

### 3.1 The Wave

Homage to the XMB ribbon. Three translucent strokes drifting across the top 40% of L0.

- Math: `y = A·sin(0.006x + 0.4t) + A/2·sin(0.011x − 0.23t)`, A ∈ {18, 24, 28}dp per ribbon.
- Style: 2dp stroke, gradient along path `copa/400`→`fever/400` at 8–12% alpha, additive blend.
- Parallax: vertical scroll displaces the wave by `scroll × 0.2`, capped at 40dp.
- “Living stage” (default on): hue drifts ±8% toward teal at 05:00–10:00 local and toward amber at 18:00–23:00. Subtle enough to be felt, not seen.
- Present on: home, settings hubs, wizard. Absent during gameplay and in Scope (scopes stay still).
- Perf: 30fps target, single Canvas layer, static render in power-save mode.

### 3.2 VU Meter

The performance heart. Cream face `#F1E8D6`, ink markings `#241C2E`, amber needle, red zone final 15% of scale, **peak-hold marker** (1.5s decay).

**Variants:**

| Variant | Size | Face | Scale | Use |
|---|---|---|---|---|
| `vu/full` | 120×88dp | Cream, engraved ticks | 0→max linear + red zone | Game detail, wizard device check, test bench |
| `vu/strip` | 64×20dp | Ink, cream ticks | Horizontal | In-game HUD (fps) |
| `vu/led` | width×12dp | Ink | 24 LED segments, 4% cream → fever → rose in red zone | Downloads, installs, scan progress |

**Needle physics:** `spring/needle`, driven to value; value changes are *targets*, never teleports. Peak-hold line drawn 2dp above needle path. TalkBack label: “frames per second, 59, near target.” In `vu/led`, segments light left→right; a bright “peak” segment holds at the highest reached value.

### 3.3 EQ Loader

The spinner. Five vertical bars, 4dp wide, heights animating as a phase-shifted sine (bar i: `h = 8 + 14·sin(t·2π/1.1s + i·0.7)`), cream at 60% alpha. Sizes: 24dp (inline), 48dp (centered). When work completes, bars “resolve” to equal height for 180ms then fade. Replaced by pulsing dot under reduced motion.

### 3.4 Rocker (toggle)

A Hi-Fi rocker switch, 52×30dp, pill. The cap is a 24×26dp tilted plate (±4°); flipping animates the tilt through **0°** — it rocks, it doesn’t slide. OFF: cap `ink/4`, engraved `off` at `type/m3` ghost. ON: face lit `fever/500` gradient (domain tint), cap cream, engraved `on` in ink, 6dp LED beside the label glows. Physics: `spring/needle`. Haptic `EFFECT_TICK`. Disabled: 40% alpha, no LED.

Tri-state variant (per-game config, §5.6): a small **selector** of three engraved positions — `inh` (inherit) / `on` / `off` — 88×30dp; the middle position shows the parent’s value ghosted (e.g., `on·global`).

### 3.5 Fader (slider)

A console fader, horizontal. Track: 4dp, `ink/4`, with **tick marks** every 10% (1dp cream 20%) and **detents** at meaningful values (spec per setting). Thumb: 28×22dp plate, cream center-line 2dp, `spring/needle` release. Value readout `type/m1` mono, right-aligned above thumb while dragging (drag state: readout scales to 110%, thumb gains domain glow). Detent snap: within 3% of a detent value, snap + `EFFECT_TICK`. Disabled: track 40%, no ticks.

### 3.6 LED

6dp circle, 2dp ink ring, optional 6dp glow. States: `off` (ink/4) · `on` (color 500 + glow) · `blink` (600ms on / 400ms off — activity in progress) · `err` (rose, blink 300/300). LEDs always pair with a text label or are covered by TalkBack — **never color alone** (§7).

### 3.7 Sleeve Card (game)

Grid tile: art in `r/squircle` mask, 1dp hairline, subtle top-edge highlight; beneath, title `type/t1` (2-line clamp), row of `type/m2` meta + micro-badges (★ favorite · ⚙ override · 🩹 patches — as LED dots + glyphs, 12dp). Long-press: quick actions sheet (§5.3). List row (64dp): 48dp art, title, `type/m2` serial, last-played, trailing chevron.

### 3.8 Amp Card (driver) & part cards

Driver card: like an amp head — name `type/d2` Unbounded, version `type/m2` mono, type chip (SYSTEM/BUNDLED/CUSTOM), capability chips (FP16, SPV, …), power LED (lit = active) and when active a **fever under-glow**. “Recommended” gets a small brass-foil stamp chip.

### 3.9 Chip

Pill, `r/pill`, 28dp height (24 compact), `ink/3` + hairline, `type/label`. Active: domain deep-tint bg + domain 500 text + LED. Filter chips carry counts in `type/m2`.

### 3.10 Source Selector (segmented)

Amp input buttons. Segments are individual 44dp-tall “keys” in a shared recessed field (`ink/2` inset); active key is cream-lit with ink text and a 4dp LED above its label. Selection: `CONTEXT_CLICK`, active key settles with `ease/step`. >5 options → becomes a **menu sheet** instead (never a cramped segmented).

### 3.11 Buttons

| Variant | Look | Use |
|---|---|---|
| **Primary “Lit”** | `fever/500`→`fever/400` vertical gradient, ink text `#2A1B04`, `r/pill`, 52dp | One per screen: Play, Install, Save |
| **Ghost** | Hairline border, cream text, `r/pill` | Secondary |
| **Key** | `ink/3` key with LED | Tertiary / in sheets |
| **Danger** | `rose` border + text; fill only during hold | Destructive |
| **Hold** | Any of the above + progress ring | §3.12 |

Press: scale 96% + `EFFECT_CLICK`. Primary buttons have a 1dp inner top highlight — they’re lacquered too.

### 3.12 Hold-to-Confirm

Danger buttons morph on touch: label crossfades to the consequence (“remove game”), and a 2dp ring around the button fills over **800ms** (`ease/glide`). Release early = cancel, ring drains 240ms, haptic tick. Complete = `EFFECT_HEAVY_CLICK` + action. Copies: “hold to remove”, “hold to uninstall”, “hold to delete profile”. Deleting a game *never* deletes the imported files without an explicit second checkbox in the follow-up sheet.

### 3.13 Sheet, Dialog, Toast, Banner

- **Sheet:** L3, `r/xl` top, knurled grabber (36×4dp, repeating 4dp linear ridges — the knurl), `ease/drop` in, drag-to-dismiss with velocity. Max height 78%; internal scroll.
- **Dialog:** centered, L2, 24dp padding, `r/xl`, **no title case** — headers are `type/d2` Unbounded with serif eyebrow. Used only for blocking choices.
- **Toast:** bottom-floating, 12dp above deck, `ink/3`+hairline, `r/lg`, icon + `type/t2`, optional action label in domain 500, auto-dismiss 4s, max 1 at a time (queue). Success toasts end with a 100ms LED blink.
- **Banner:** 40dp strip pinned under Marquee, deep-tint bg + domain 600 text + LED. Used for offline / low-storage / unsupported-driver / pending-restart. One at a time (priority: error > warning > info). Dismiss (×) or action.

### 3.14 Stepper (install flows)

Vertical steps, each: LED + `type/label` name + status in `type/m2` mono + `vu/led` progress when active. Steps light palm when complete, rose when failed (with retry key). Used by firmware, driver install, wizard.

### 3.15 Empty state “Encore”

Centered block: 160×120dp line illustration (1.75dp stroke ink-bone, single amber accent, paper-cream 8% fill), `type/eyebrow` domain-tint line, `type/d2` Unbounded title, one `type/body` line, up to two keys. Illustration motif per domain: empty crate w/ one leaning disc (Crate), a lone fader (Tune), an unplugged cable coiled like a snake (Pad), a bare chip socket (Parts), flatlined scope (Scope).

---

## 4 · INFORMATION ARCHITECTURE

### 4.1 Map

```
Splash ──► First Night (wizard, first run only)
              │
              ▼
   ┌────────── THE DECK (5 destinations) ──────────┐
   │                                                │
   │  CRATE        TUNE         PAD      PARTS   SCOPE
   │  library      settings     pads     parts   logs
   │  ├ scan       ├ presets    ├ devices├ firm.  ├ monitor
   │  ├ detail     ├ cpu/spu    ├ remap  ├ drivers├ debug
   │  │  ├ config  ├ graphics   ├ touch  ├ patches└ about
   │  │  ├ patches ├ vulkan     └ overlay└ profiles
   │  │  └ input   ├ audio        editor
   │  └ import     ├ system
   │               ├ network
   │               └ advanced
   └────────────────────────────────────────────────┘
   IN-GAME LAYER (runtime): HUD · overlay · Intermission · quick rack
```

### 4.2 The Deck (bottom nav, phones)

72dp, `ink/2` + top hairline + bottom scrim. Five items: 26dp icon + `type/micro` label + optional **attention LED** (§6.6). Active: filled icon in domain 500, label cream, a 24dp domain underline *under* the item (2dp, `ease/step`). Landscape/tablet: 64–72dp right rail or left rail in two-pane.

### 4.3 The Marquee (top bar)

64dp, transparent over wave. Left: wordmark (32dp lockup). Right: profile chip (24dp monogram + name, opens profile switcher sheet). Optional center: **status ticker** — a slow `ease/sway` scrolling `type/m2` mono line (“fw 4.90 · turnip 25.1 · 12 records · 64 patches”), toggled in System settings, default off. During setup: ticker shows wizard progress instead.

### 4.4 Back & routing

Predictive back enabled; sheets/dialogs scale-preview. Deep links: `samba://game/{id}`, `samba://tune/graphics`. Launcher long-press shortcuts: *Resume · Continue setup · Scan for games*. Cold-start always lands on Crate.

---

## 5 · SCREENS

---

### 5.0 Boot & Splash — “House Lights”

**Purpose:** set tone in 1.2 seconds; establish the wordmark as an object.

**Sequence (total 1200ms):**
1. `ink/0`, 200ms hold.
2. Wordmark rises: letters of `SAMBA` translate up 12dp + fade, 40ms stagger, `ease/step`; `s3` swings in from −8° rotation at +120ms, `spring/needle`.
3. Wave draws left→right across the lower third (400ms, single stroke).
4. EQ bars pulse once; `type/m3` version stamp fades in bottom-left (`v1.4.2 · bossa noir`).
5. Crossfade `ease/glide` to destination (wizard on first run, else Crate).

Tap skips after 400ms. Launch haptic fires only when proceeding into a game, not here. Never longer than 1.4s on any device.

---

### 5.1 Setup Wizard — “First Night”

Seven steps, full-screen, no deck. Progress = **EQ strip**: 7 segments across the top, lit segments fever, current one blinking. Back arrow top-left; exit asks “save progress?” sheet. Each step: illustration zone (160dp) → eyebrow (`step 3 · firmware`) → `type/d1` title → content → fixed bottom bar (Ghost *Back* / Primary *Next*, disabled until valid).

| # | Step | Content & controls | Validation / states |
|---|---|---|---|
| 0 | **Welcome** | Wordmark large, “let’s set the stage.” List of what you’ll need (games, firmware file or internet, ~10 min) as 3 sleeve-style rows w/ LEDs | Next always enabled |
| 1 | **Permissions** | Card per permission: storage (all-files or SAF), notifications. Each: icon, why-text in `type/body`, Key *Grant*, LED → palm on grant | Denied → inline retry + SAF fallback path; can proceed with warning banner |
| 2 | **Device check** — *the audition* | `vu/full` trio (CPU class / RAM / Vulkan score) + spec readout `type/m2` (SoC, RAM, GPU). Verdict banner: “main stage ready” / “standing room — expect tuning” | No Vulkan → hard stop screen with explanation + link to Parts |
| 3 | **Firmware** | Pick local `PS3UPDAT.PUP` (file sheet) or download (URL + region select). Install = stepper: download → verify sha256 → decrypt → install, each with `vu/led` | Bad hash / wrong file: rose step + retry; skip allowed w/ attention LED on Parts |
| 4 | **GPU driver** | Amp Card of system driver (active, LED) + “add a custom driver” key + compatibility note | Unsupported → warning banner, can continue |
| 5 | **Game scan** | Folder picker (SAF tree) + scan now / skip. Runs mini radar (§5.4 style) inline, results count live | Zero found → gentle explain state |
| 6 | **Finish** | “the stage is set.” Summary card (mono spec sheet: firmware, driver, games found) + profile name input (Field) + accent picker (5 stage lights) + Primary *Open the Crate* | Name 1–16 chars, defaults “Player 1” |

Motion: steps crossfade + 8dp upward drift; EQ segment lights with `ease/step` + tick haptic per step.

---

### 5.2 Home — “The Crate” (game library)

**Anatomy (portrait):**

```
┌──────────────────────────────────────────────┐
│ Samba s3  · · · ticker · · ·        ( ◉ M ) │ Marquee 64
├──────────────────────────────────────────────┤
│ ▶ NOW ON STAGE — Demon’s Souls        ⏯     │ Stage rail 56 (only while running)
├──────────────────────────────────────────────┤
│ [ ⌕ search the crate… ]      ⇅  ▦ ▤         │ Toolbar 56
│ ( all 12 ) ( ★ 3 ) ( recent ) ( unplayed )   │ Filter chips 40
├───────────────┬───────────────┬──────────────┤
│   ┌───────┐   │   ┌───────┐   │   ┌───────┐  │
│   │ squirc │   │   │ squirc │   │   │ squirc │  │ 3-col, 12 gut
│   │  art   │   │   │  art   │   │   │  art   │  │ art = col width
│   └───────┘   │   └───────┘   │   └───────┘  │
│   title 2ln   │   title       │   title      │
│   BLUS30443 ★ │   BLUS…       │   …          │
├───────────────┴───────────────┴──────────────┤
│  crate   tune    pad    parts    scope       │ Deck 72
└──────────────────────────────────────────────┘
```

- **Stage rail:** appears only when a game is running; fever deep-tint bg, LED blinking, Play/Pause keys; tapping opens Intermission (foregrounds the game).
- **Toolbar:** recessed search Field (§2.6), sort chip (opens menu sheet: recently played / title / serial / size), grid⇄list toggle (animates layout morph 240ms — tiles scale into rows, art shrinks, titles reflow).
- **Chips:** All (count), Favorites, Recently played, Never played; active = fever deep-tint.
- **Tiles:** 12dp gutters, 16dp screen margin. Badges: ★ (top-right on art, 16dp, cream/ink), dot-row under title: ⚙ amber dot (per-game overrides), 🩿 grape dot (patches enabled). Long-press tile → quick sheet: *Play · Settings · Patches · Favorite · Hide · Remove (hold)*.
- **Tap** → Game Detail with shared-element art expansion.

**Behavior:** pull-to-refresh rescans metadata (not the disk); search filters as typed, highlights matches in fever; empty search results state: encore illustration + “no records match ‘kenza’” + clear key.

**States:** empty (first run or all hidden) → encore: crate illustration, “the crate is empty — bring your records,” keys *Scan storage* / *How to dump your games* (help sheet). Loading metadata: tiles show art + shimmering title placeholder (warm-tinted 4% pulse). Hidden games listed under a collapsed “backstage” row at bottom (unhide flow).

**Motion:** first paint staggers tiles 24ms ×8; scroll parallax on wave; badge LEDs blink 600/400 while a scan is active.

---

### 5.3 Scan & Import — “Record Hunting”

Full-screen radar. Concentric pulse rings (2dp, copa 20%, `ease/sway` outward, 3 staggered); center: scan icon; below: live monologue in `type/m2` mono (“walking /storage/emulated/0/PS3 GAMES …”); found games slide in from the right as small sleeve rows stacking into a “crate” stack at the bottom, counter in `type/d1` Unbounded (“07 found”).

**States:** folder error → rose banner + re-pick; unsupported/incomplete folders listed in results with reason (`type/m2`: “missing EBOOT.BIN”); already-imported shown dimmed with palm “in crate” chip. Cancel key (Ghost) mid-scan; resume supported.

**Done → Summary sheet:** “New arrivals” list (art, title, serial, size), each with include rocker (default on); Primary *Import*; after import, each row animates a brief fever flash + tick. Conflict resolution for duplicate serials: “replace metadata / keep both / skip” per row.

---

### 5.4 Game Detail — “The Sleeve”

```
┌──────────────────────────────────────────────┐
│ ←                                ( ⋯ more )  │  over hero
│░░░░░░░ blurred art, full-bleed, scrim ░░░░░░░│  hero 256dp
│  ┌────────┐  DEMON’S SOULS                    │
│  │ squirc │  (type/hero, 2-line clamp)        │  sleeve 96dp,
│  │  art   │  BLUS30443 · PS3 · 4.90 · 26.4 GB │  overlapping
│  └────────┘  [▶ PLAY]  (⚙)(🩹)(🎮)           │  action row
├──────────────────────────────────────────────┤
│   overview      settings      patches         │  tabs 48
├──────────────────────────────────────────────┤
│  overview / performance snapshot …            │
```

- **Hero:** game art blurred ×24 + 60% scrim (60° gradient). Sleeve art in `r/squircle` floats overlapping hero/body by 32dp — shared-element origin.
- **Meta line:** `type/m2` mono, dots separated. Action row: **Primary Lit PLAY** (52dp, sublabel under it in `type/m3`: “rpcs3 core · booting”) + three Key buttons (per-game config, patches, input profile).
- **Tabs:** `type/label`, active = fever underline 2dp + label cream.
- **Overview tab:** description (`type/body`), info grid (2-col, mono values), **performance snapshot**: 3× `vu/full` (avg FPS / CPU / GPU load from last sessions) + sparkline of last 5 sessions beneath (2dp, copa), “no sessions yet” encore until data exists. Buttons: *Play again*, quick stats in mono.
- **Settings tab:** per-game config (§5.6). **Patches tab:** patch manager pre-filtered to this game (§5.12).
- **More (⋯):** Open folder · Refresh metadata · Hide · Remove (hold, then a sheet with “also delete imported files” checkbox, off by default).

**The launch ritual (signature moment):** on PLAY — Marquee & Deck slide away, screen dims, the sleeve art centers and **spins one full rotation** (900ms, `ease/sway`-in, decelerating), needle-drop haptic + sound, wave accelerates 2×, and a 20sp mono “boot ticker” streams kernel lines along the bottom edge for the duration of core boot; then crossfade into the game. This ritual *is* the brand — it must never exceed the actual boot time (loop quietly if the core is slow).

---

### 5.5 Global Settings — “The Mixing Desk”

Hub screen: eyebrow “tuning” · `type/d1` “Settings” · search Field (searches all setting titles/keywords) · **preset chips** row · category list.

**Presets** (chips with LED): `Silent Cinema` (battery: 100% res, 30 fps cap, mute, vsync on) · `Main Stage` (balanced default) · `Arena` (200% res, async shaders, 60 cap). Selecting shows a diff sheet (“this will change 9 settings”) with *Apply* / *Dismiss*; toast confirms with an undo action for 6s.

**Categories** (rows 64dp, icon in domain tint, title + one-line summary, chevron):
CPU & SPU · Graphics · GPU / Vulkan · Audio · System · Network · Advanced. Each opens a page built from identical anatomy:

```
┌──────────────────────────────────────────────┐
│ ←   cpu & spu        (search)  (↺ reset)     │ header 64, copa eyebrow
├──────────────────────────────────────────────┤
│ 01 · ppu                                     │ group: eyebrow + hairline leader
│ ┌──────────────────────────────────────────┐ │
│ │ ⚙  PPU decoder          [ Interpreter ▾] │ │ row 76
│ │    how the main cpu is translated        │ │
│ ├──────────────────────────────────────────┤ │
│ │ ⚙  LLVM threads               0───●── 8  │ │ fader row 88
 └──────────────────────────────────────────┘ │
```

**Row anatomy:** 24dp leading icon · title `type/t1` · desc `type/t2` mute (≤2 lines) · trailing control. Row height 76 (88 for faders), divider hairline inset 56dp. Press: `ink/4` wash. Groups numbered `01 ·`-`06 ·` in `type/m3` mono ghost with a hairline leader line running to the right edge — the channel-strip motif. Danger group (“clear caches, reset”) sits last, rose-tinted.

**Page header:** eyebrow in domain tint · `type/d1` title · settings-search icon · reset (opens diff sheet listing defaults → *Hold to reset*).

Every changed setting feeds the **pending-changes system** (§6.7).

#### 5.5.1 CPU & SPU

| Group | Setting | Control | Options / range | Default | Notes |
|---|---|---|---|---|---|
| 01 · ppu | PPU decoder | Selector→sheet | Interpreter / Precise / LLVM | LLVM | ⚠ RESTART |
| 01 | LLVM threads | Fader, detents 0·2·4·8 | 0–8 (0 = auto) | 0 | |
| 02 · spu | SPU decoder | Selector | Interpreter / Precise / LLVM | LLVM | ⚠ RESTART |
| 02 | SPU block size | Selector | Safe / Mega / Unsafe | Safe | desc warns per option |
| 02 | Preferred SPU threads | Fader | 0–12 (0 = auto) | 0 | |
| 02 | SPU XFloat precision | Selector | Accurate / Approximate / Relaxed | Approximate | grape LED “affects accuracy” |
| 02 | SPU loop detection | Rocker | — | Off | |
| 03 · threads | Thread scheduler | Selector | OS / S3 scheduler / S3 alt | OS | ⚠ RESTART |
| 03 | Pin threads to cores | Rocker | — | Off | device-dependent hint |

#### 5.5.2 Graphics

| Group | Setting | Control | Range / options | Default |
|---|---|---|---|---|
| 01 · output | Resolution scale | Fader, detents 50·100·150·200·300 | 50–800% | 100% |
| 01 | Aspect ratio | Source selector | Auto / 16:9 / 4:3 / 21:9 | Auto |
| 01 | VSync | Rocker | — | Off (mobile battery note) |
| 01 | Frame limit | Fader, detents Off·30·60·120 | 0(off)–120 | Off |
| 02 · quality | MSAA | Selector | Auto / 2× / 4× | Auto |
| 02 | Anisotropic filter | Source selector | 1×–16× | 16× |
| 02 | Shader mode | Selector | Legacy / Recompiler (async) / Async + skip | Async |
| 03 · special | Write color buffers | Rocker | — | Off · grape LED “heavy” |
| 03 | Write depth buffer | Rocker | — | Off |
| 03 | Read color buffers | Rocker | — | Off |
| 03 | Disable ZCull queries | Rocker | — | Off |

Special group carries a banner: “these fix specific games and break others — check the compatibility notes.”

#### 5.5.3 GPU / Vulkan

Active driver Amp Card (opens Amp Room §5.8) · capability readout (mono: FP16, subgroup, driver ver) · async shader compiler (Rocker, on) · async texture decoding (Rocker) · texture decode threads (Fader 0–8) · Danger: *Clear shader cache* (Hold).

#### 5.5.4 Audio

Backend (Selector: Auto / AAudios / OpenSL) · buffer duration (Fader 20–200ms, detents 40·60·100; warning LED < 40ms: “may crackle”) · master volume (Fader) · duck when app loses focus (Rocker, on) · mute on call (Rocker, on) · dump audio to file (Rocker, grape LED debug).

#### 5.5.5 System

*Console:* enter button — **Source selector with cross/circle glyphs** and the classic regional note (“Japan selects with ○”) · console language (Selector) · emulator data location (path Field → SAF sheet).
*This app:* theme variant picker (§8) · wave toggle · grain toggle · living-stage toggle · ticker toggle · sound toggle · haptics toggle.

#### 5.5.6 Network

Status card (LED + mono readout) · enable network stack (Rocker) · DNS override (Field) · “allow PSN-era services” (Rocker, warning banner).

#### 5.5.7 Advanced

LLVM precompile on boot (Rocker) · PPU/SPU debug (Rockers, rose banner “severe slowdown”) · log level (Selector → links to Scope) · staging flags list (grape LEDs, “experimental — things may catch fire”).

---

### 5.6 Per-Game Configuration

Mirrors the global pages, scoped: header shows a 32dp sleeve chip + game title; every row’s control becomes **tri-state** (§3.4): `inherit` (default — shows parent value ghosted) / explicit value. A **diff bar** floats above content when overrides exist: “7 overrides” + *Review* (sheet listing each, with per-item revert) + *Hold to reset all* (fever-tinted, not rose — this is not destructive, just reset). Saving is instant; launching the game applies.

---

### 5.7 GPU Driver Manager — “The Amp Room”

Eyebrow `palm` · list of Amp Cards (§3.8): name (Unbounded `type/d2`), version mono, type chip, capability chips, power LED; **active card has fever under-glow + “ON STAGE” brass stamp**. Actions per card: *Activate* (Key; deactivates previous with an animated power-down glow fade) · *Update* (if newer) · *Uninstall* (Hold, system driver exempt) · *Import from file* (FAB-less — a Ghost key in the header + in the empty state) · *Download catalog* (sheet: remote list with compat notes, palm/rose LEDs per device match, install via Stepper §3.14 with sha256 verify step).

**States:** incompatible driver → rose banner on that card + blocked Activate; no drivers → encore (bare chip socket) + import keys; install failed → rose stepper step with retry + log link (“open in Scope”).

---

### 5.8 Firmware — “The Boot Chip”

Single hero card: firmware version in `type/d1` Unbounded, install date + component list (mono: flash, dev_flash, …) with palm LEDs. Install paths: *Pick PS3UPDAT.PUP* (file sheet) or *Download* (region select → URL). Install = Stepper: download (`vu/led` + speed in mono) → verify sha256 → decrypt → install. Failure states per step w/ retry & Scope link. Uninstall not offered (firmware is foundational) — replaced by *Reinstall (Hold)*.

---

### 5.9 Controllers — “The Band”

**Device list:** Pad cards: connection glyph (BT wave / USB plug), name, battery (mono % + 12dp LED strip), “player 1–4” LED cluster (4 dots, active lit), default-star. Buttons: *Remap*, *Test bench*, forget (Hold). Empty: coiled-cable encore + “pair a controller — Settings › Bluetooth” + “or use the touchscreen” (deep-link to touch pad setup).

**Remap screen:** center: front-view PS3 pad diagram (line illustration, 260dp). Tap a PS3 control (button/stick/dpad) → it highlights fever → “listening” state: pad pulses radar rings + “press any button…” in `type/eyebrow`. Assignment lands with tick haptic + the pad glyph stamps onto the target. Alternate list view (rows: PS3 control → assigned Android input, mono). Conflict = both rows flash rose + banner; auto-suggest swap. Per-game binding profiles via header menu. Settings group: analog deadzone faders (L/R), swap sticks (Rocker), rumble test key (single test pattern w/ LED), player number selector.

**Test bench:** live pad diagram that lights on input; sticks draw cream traces that fade over 2s (phosphor scope effect); a `vu/full` measuring “input latency estimate”. This screen doubles as the TalkBack showcase — every element labeled live.

---

### 5.10 Touchscreen Controller & Overlay Editor — “Blueprint”

**Runtime overlay (in-game):** translucent controls over the frame. Materials: `ink/3` @ 40–70% (per-control opacity), 1dp cream 20% hairline, press state = 70% + domain glow ring 120ms. Layout: sticks 96dp bases / 44dp knobs (dynamic origin — stick re-centers where the thumb lands, drift zone 12dp); D-pad 88dp cross; face buttons 56dp circles in **canonical PS3 colors** at 70% (△ `#3EC98C` · ○ `#F0565C` · ✕ `#4FA3F5` · □ `#E58BD8`, glyphs stroked cream); L1/R1 at corners, L2/R2 as horizontal trigger bars with fill-on-press; Start/Select 36dp. Edge-snap margins respect safe areas. Coach marks first run: “sticks follow your thumb” with an animated thumb ghost.

**Editor — Blueprint mode (signature screen):**

```
┌──────────────────────────────────────────────┐
│ ←  blueprint: Demon’s Souls   (▦ grid)(↶↷)   │ header 56
├──────────────────────────────────────────────┤
│ ░░░ dimmed game frame (paused screenshot) ░░ │
│   ┌grid 8dp dots, cream 6%░░░░░░░░░░┐        │
│   │   (L2)══      ╳▒▒ (selected:    │        │
│   │   [stick○]    ▒▒  handles ▒▒)   │        │
│   └───────────────────────────────┘          │
├──────────────────────────────────────────────┤
│ (select)(＋ add)(opacity ▬)(scale ▬)(reset)  │ toolbar 64
└──────────────────────────────────────────────┘
```

- Backdrop: paused frame at 30% + blueprint grid (8dp dots at 6% cream, toggle) + safe-area guides (rose dashed).
- Selection: fever dashed superellipse + 4 corner handles (16dp) + edge scale handles; drag moves (snap to 8dp; hold shift-free “fine” mode = drag with 0.25× multiplier via two-finger touch); handles resize; rotation is deliberately **not** offered (pads stay upright — a constraint that keeps layouts sane).
- Toolbar: select/pan toggle · *add* (sheet: every control type incl. combos) · global opacity fader · selected-scale fader · reset layout (Hold) · undo/redo (↶↷ in header, 20 steps).
- **Properties sheet** (on selection): opacity fader (10–90%), size (0.7–1.5×), shape (round/squircle), label on/off, haptics rocker, stick deadzone fader, *enabled* rocker, *lock position* rocker, duplicate, delete (Hold).
- Multi-select: long-press drag lasso; group nudge with arrows.
- Profiles: **Global** + per-game layouts (inherit model like config); presets: `Casual` (big buttons, no select), `Pro` (compact, edge-snap), `Southpaw` (mirrored). Export/import layout as `.json` share sheet.
- **Test mode:** overlay goes live over a static frame with the Scope-style input trace — you feel the layout before saving.
- Save = instant; exit with unsaved edits asks once (sheet).

---

### 5.11 In-Game Layer

**HUD strip (top, 28dp, auto-hide 4s):** `vu/strip` (fps, peak-hold) · battery LED · clock mono · pause key (36dp). Reappears on touch near top or on performance anomaly (fps in red zone > 5s — LED blinks rose).

**Intermission (pause menu):** game dims to 20% + 60° scrim; left-aligned column of large menu items (Unbounded `type/d2`, hover/selection = fever underline + tick):

```
▶ resume
⚙   game settings        (per-game, live-apply subset)
🎚  global settings
🎛  core menu             (emulator native overlay)
🎮  controls              (overlay editor / touch pad on-off)
⏏   exit game            (Hold)
```

Right side: mini stats card — `vu/strip`, session length mono, last-5-min fps sparkline. Motion: menu items stagger 24ms; “resume” pulses softly (LED breathing) after 30s idle.

**Quick rack** (in-game quick settings): edge-swipe from right (or core-menu item) opens a 280dp side rack with the six settings that live-apply: resolution scale, frame limit, shader mode, async shaders, volume, overlay opacity — each a compact fader with mono readout, changes apply instantly, an “applied ✓” LED blinks per change. Non-live settings show “takes effect on next boot” in ghost mono instead of a control.

---

### 5.12 Patch Manager — “The Stitching Room”

Grape domain. Two entries: per-game tab (§5.4) and Parts → Patches (all games, grouped).

**List:** search Field + filter chips (enabled / disabled / incompatible) + group headers (game sleeve chip + title). Patch row: hash `type/m2` mono + name `type/t1` + author · expandable description (tap chevron, 200ms reveal) · version/compat chips · **enable rocker** (grape-lit). Incompatible rows are dimmed with rose chip (“needs fw 4.80”) and disabled rocker.

**Sources:** *Import* (file `.zip`/patch file, or URL sheet) — import runs a validation stepper (parse → hash check → compat scan); *Download catalog* (online list with per-patch palm/rose compat LEDs against installed firmware); batch actions via long-press multi-select: *Enable all / Disable all / Export selection*. Applying patches requires game restart → patches feed the pending-changes system with a targeted copy: “restart to stitch 3 patches into Demon’s Souls.”

---

### 5.13 Profiles — “The Cast”

Cards (72dp): monogram superellipse on a two-tone gradient built from the profile’s chosen stage light + ink; name; role line mono (`user 1 · active`); **“ON STAGE” stamp + fever under-glow** for the current profile. Actions: *Switch* (confirm sheet: “switching users swaps saves & emulator settings for this profile”) · *Edit* (name, accent) · *Delete* (Hold — plus a second sheet listing what’s lost). *New profile* key → mini-wizard (name + accent + optional import of existing data dir). Guest profile available (nothing persists).

---

### 5.14 Log Monitor — “The Scope”

```
┌──────────────────────────────────────────────┤
│ ←  the scope    (⏸ follow)(⤓ export)(⌕)      │
├──────────────────────────────────────────────┤
│ (F 2)(E 14)(W 31)(I 220)(D 1804)   severity  │ chips w/ counts
│ (vulkan)(gpu drv)(kernel)(app)(core)          │ subsystem chips
├──────────────────────────────────────────────┤
│ 14:22:07.412 ▌E▐ [VULKAN] device lost —     │ row: LED, tag,
│              attempting recovery             │ timestamp mono,
│ 14:22:07.448 ▌W▐ [GPU DRV] fallback path    │ message 3-line
├──────────────────────────────────────────────┤
│              ▲ 142 new · tap to follow       │ follow pill
└──────────────────────────────────────────────┘
```

- Row: 10sp mono timestamp · severity LED (color + letter for a11y) · subsystem tag chip (subtle domain-tinted) · message wraps to 3 lines, tap expands full text with copy/share.
- **Follow mode:** auto-scroll on; manual scroll detaches — the floating “▲ 142 new” pill reattaches with `ease/step`. Paused rows render at full cream; live rows stream in at 60% and settle to full after 2s (a phosphor-trace effect).
- Filters are AND of severity × subsystem; counts live-update. Search highlights matches in fever. Export writes a `.log` share sheet. Performance: virtualized list, 50k rows guaranteed, timestamps toggle-relative/absolute.
- Fatal error burst → rose banner + “copy for bug report” action (packages last 200 lines).

---

### 5.15 Debug Tools — “The Lab”

Rose domain + banner: “lab coat required — these tools can confuse the emulator.” Blocks:

- **Ghost pad (input injection):** grid of PS3 controls (press-and-hold keys; analog sticks as 2-axis mini-pads; triggers as faders), a macro recorder (record/stop/play, mono event list), live event stream (Scope-style rows).
- **Self-test runner:** checklist (shader compile, audio path, memory, input pipeline) each with LED + ms timing in mono; summary card with pass rate on `vu/full`.
- **Crash dumps:** list w/ timestamp + open (mono viewer) + share.
- **Caches:** clear (Hold) per cache type.

---

### 5.16 About

Wordmark large · version stamp mono · credits in two columns (Unbounded names, SG roles) · GitHub link · OSS licenses (sheet) · “designed in Bossa Noir” + a tiny wave flourish. Deliberately quiet — the one screen with nothing lit.

---

## 6 · GLOBAL PATTERNS & STATES

### 6.1 Loading hierarchy
Inline EQ (24dp) → centered EQ (48dp) → skeleton “ghost shelves” (cards with 4% warm shimmer pulse). Never block navigation that works.

### 6.2 Error taxonomy
**Recoverable:** banner + retry key + Scope link. **Blocking:** full-screen encore (rose LED), plain-language cause, two actions max, one is always *Copy diagnostics*. Error copy template: what happened → why (one line) → what to do. Never raw stack traces outside Scope.

### 6.3 Permission primers
Every system permission is preceded by a primer sheet: icon, serif eyebrow (“one thing first”), why in `type/body`, what we’ll never do (storage: “your photos are none of our business”), Key *Continue* → then the system dialog. Denied → inline guidance with *Try again* + SAF alternative.

### 6.4 Offline / low storage
Offline: 40dp copa banner “offline — catalogs unavailable, everything local still plays.” Low storage (< 2 GB): fever banner with the three biggest cache items and *Clear* key.

### 6.5 Toast etiquette
Success = icon + one line + optional undo (6s). Never toasts for destructive success — those get a confirmation sheet with a summary instead.

### 6.6 Attention LED system
The Deck and the Parts hub surface the app’s health without badges/numbers: Crate LED = game running · Tune = pending changes · Pad = controller disconnected mid-session · Parts = firmware missing / driver outdated / patches pending · Scope = unseen errors. LED + dot count (≥9 → “9+”). Blinking = in progress. Tapping the deck item routes to the attention source. This replaces 90% of notifications — the app stays quiet unless asked.

### 6.7 Pending changes system
Any ⚠ RESTART setting change accumulates: Marquee grows a small fever LED chip “3 · changes pending” → sheet lists each (page, setting, from → to in mono) with per-item revert, *Restart now* (only in-game), *Apply on next boot*. Nothing is ever lost silently.

### 6.8 Tooltips & coach marks
Long-press any icon → 8dp-offset mini card, eyebrow label + one line, auto-dismiss 3s. Coach marks: once per feature, dark scrim + spotlight ring + one line + “got it”; stored per-device, resettable in System.

### 6.9 Orientation & foldables
All screens portrait+landscape. Fold unfold: content re-flows to two-pane (list | detail) with a 240ms cross-dissolve; never a hard reload. Games list on tablet: rail 280dp + pane; library grid scales to 5–8 columns with 16dp gutters.

---

## 7 · ACCESSIBILITY

- **Contrast:** all body text ≥ 7:1; interactive accents ≥ 4.5:1 (`fever/500` on `ink/1` ≈ 10:1). `text/ghost` restricted to decorative stamps.
- **Targets:** all interactive ≥ 48dp; deck items 72dp; fader thumbs 28dp tall with 48dp hit slop.
- **Focus:** visible 2dp domain-tint ring + 2dp offset on every focusable; order follows reading order; decks wrap.
- **TalkBack:** full map. Meters announce values+interpretation (“cpu load 82 percent, in the red zone”); rockers announce state and tri-state parent value; wave is decorative (hidden); every LED has adjacent text or label.
- **Color-blindness:** no state depends on color alone (LEDs pair with letters/labels; patch compat uses chips + text).
- **Dynamic type:** supports 100–130%; Unbounded titles auto-scale down to 85% before wrapping (they’re wide); no text under 10sp.
- **Reduced motion:** §2.9 mapping; wave, ticker, phosphor traces, needle springs all degrade gracefully.
- **Haptics & sound:** independently disableable; all audio cues have visual twins.

---

## 8 · THEMES & VARIANTS

All variants are **token swaps only** (Appendix A) — components never restyle.

| Variant | Changes | Default? |
|---|---|---|
| **Bossa Noir** | As specified | ✔ |
| **Copa Noir** | `fever` ↔ `copa` roles swapped (teal primary) | opt-in |
| **Copacabana Day** | `ink/1..5` → warm paper ramp (`#F5F1E6`, `#ECE5D4`, `#E2D9C3`…), text → ink ramp, `fever/500`→`fever/700` `#B96A00`, cream VU face inverts to ink face w/ cream markings, grain 2%, wave at 10% α | opt-in |
| **High Contrast** | ink levels pushed apart, hairlines to 20%, accents only 500/600, glow off | auto-offer during setup if system a11y flags detected |

Domain tints stay fixed across variants (they’re structural, not decorative).

---

## 9 · ASSETS & PRODUCTION NOTES

- **Superellipse mask:** export 5 master sizes (48/96/160/256/512) as SVG-clip or shader; runtime uses continuous-corner path (n=5).
- **Grain tile:** 128px PNG, fractal noise, alpha 4%, `overlay`; sheet variant 3%.
- **Icons:** SVG pack, 24dp grid, 1.75 stroke, `currentColor`; filled active set; namespaced `corda/*`.
- **Illustrations:** encore set (5), permission primers (3), wizard spots (7) — line art 1.75dp `text/bone`, single amber accent, cream 8% fills. Hand-drawn wobble allowed ±0.5dp (it reads as craft at 160dp).
- **Wave:** implement as one Canvas layer; parameters in §3.1; export test renders at 3 time-slices for review.
- **Fonts:** Google Fonts — Unbounded (600–800), Space Grotesk (400–600), JetBrains Mono (400–700), Instrument Serif (italic). Bundle offline (emulator apps must work offline).
- **Implementation hints:** Compose; tokens as a single `BossaTheme` object; VU/needle = custom `Canvas` with a physics-driven `Animatable`; rocker/fader as shared controls; deck & marquee as scaffold composables; predictive-back enabled globally.

---

## 10 · DESIGN QA CHECKLIST (ship gate)

1. No pure #000 or #FFF anywhere (lint the token dump).
2. Exactly one serif eyebrow per screen region; one Primary Lit button per screen.
3. Glow only on powered-on things (game running / active driver / lit controls) — audit every screen.
4. Every toggle has haptic + label; every LED has text or TalkBack label.
5. Every destructive action is Hold-to-confirm (800ms ring).
6. Empty / loading / error states exist and use the encore system for every list.
7. All ⚠ RESTART settings feed pending-changes; verify chip count matches.
8. Deck attention LEDs fire for: game running, firmware missing, driver outdated, patch pending, unseen errors, controller drop.
9. Motion audit: no element exceeds 110% rest size; reduced-motion passes all ten signature moments.
10. Long game titles (34+ chars) don’t break hero, tiles, or sheet headers.
11. Two-pane at 600dp+; fold unfold without state loss; landscape phone keeps deck as rail.
12. VU meters report believable physics (needle never teleports; peak-hold decays at 1.5s).
13. Logs: 50k rows scroll at 60fps; follow pill works; export writes valid file.
14. Launch ritual never exceeds real boot time; skippable by tap.
15. Wordmark lockup unbroken everywhere it appears.

---

## APPENDIX A · CORE TOKEN SHEET

```json
{
  "color": {
    "ink":    ["#060409", "#0D0A12", "#151020", "#1D1830", "#272040", "#332A4F"],
    "text":   { "cream": "#F7F2E7", "bone": "#E8E1D0", "mute": "#A49BB0",
                "ghost": "#6B6380", "faint": "#4B4460" },
    "fever":  ["#FFD9A0", "#FFC878", "#FFB454", "#E89A33", "#3A2A10"],
    "copa":   ["#A9F0E4", "#7BE7D8", "#45D9C6", "#2CB4A4", "#0F332F"],
    "rose":   ["#FF8397", "#FF7286", "#FF5D73", "#D9465B", "#3A1420"],
    "palm":   ["#9BE8A9", "#85E296", "#6FDB8F", "#4CB46E", "#123322"],
    "grape":  ["#D5C0FF", "#BCA3FF", "#A584FF", "#8A63F2", "#221840"],
    "creamSurface": "#F1E8D6",
    "hairline": "rgba(247,242,231,0.08)"
  },
  "type": {
    "display": "Unbounded", "ui": "Space Grotesk",
    "mono": "JetBrains Mono", "soul": "Instrument Serif (italic)",
    "scale": { "hero": "40/44", "d1": "28/32", "d2": "20/26", "t1": "16/22",
               "t2": "14/20", "body": "14/20", "label": "12/16",
               "micro": "11/14", "eyebrow": "14/18",
               "m1": "16/22", "m2": "12/16", "m3": "10/14" }
  },
  "radius":  { "xs": 4, "sm": 8, "ctl": 14, "lg": 18, "xl": 26,
               "squircle": "0.22w continuous" },
  "space":   [4, 8, 12, 16, 20, 24, 32, 40, 48, 64],
  "motion":  { "step": "c(.22,1.14,.32,1) 180ms", "glide": "c(.4,0,.2,1) 240ms",
               "drop": "c(.34,.8,.24,1) 300ms", "sway": "1400ms loop",
               "needle": "spring k=170 ζ=0.75" },
  "domain":  { "crate": "fever", "tune": "copa", "pad": "grape",
               "parts": "palm", "scope": "rose" }
}
```

## APPENDIX B · LEXICON (voice)

| App-speak | Means | Use |
|---|---|---|
| the crate | game library | eyebrows, empty states |
| on stage | running / active | LEDs, stamps |
| the needle drop | launching a game | ritual, toasts |
| intermission | pause menu | in-game only |
| tuning | changing settings | settings copy |
| the amp room | GPU driver manager | eyebrow only |
| encore | empty state | never in body copy |

Body copy stays plain and useful. Personality lives in eyebrows, stamps, and empty states — **never** in labels, errors, or destructive confirms.

---

*End of sheet — SS3-D-001 Rev A. Cut on the dotted line.*
````

A few notes on the decisions inside:

- **The core idea** — “the emulator as a piece of Hi-Fi equipment” — gives every screen one coherent metaphor: VU meters for performance, faders with detents for settings, rocker switches for toggles, amp-head cards for GPU drivers, an equalizer for loading, an oscilloscope for logs. Nothing is decorative; the metaphor does the work.
- **Most unique moments**: the analog VU needle with spring physics + peak-hold, the spinning-sleeve “needle drop” launch ritual, the blueprint-mode overlay editor with safe-area guides, the attention-LED system on the deck instead of notification spam, and the tri-state per-game config with the “7 overrides” diff bar.
- **Hand-crafted tells**: warm plum-black inks instead of pure black, film grain, one serif italic eyebrow rule, numbered channel-strip groups with hairline leaders, and hold-to-confirm rings instead of confirm dialogs.

Want me to go deeper on any single surface next — e.g., full redline specs (every padding/measurement) for the Game Detail or Overlay Editor, a Compose token file, or ASCII wireframes for the tablet two-pane layouts?
