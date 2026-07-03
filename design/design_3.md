---
version: alpha
name: SambaS3
description: >
  Design system for SambaS3, a PS3 emulator for Android. Built for
  controller-first navigation with a deep navy + gold retro console aesthetic,
  evoking the golden era of living-room gaming through premium materials and
  deliberate visual texture.

colors:
  # Backgrounds
  background:        "#0A0D1A"
  surface:           "#0F1528"
  surface-elevated:  "#172040"
  surface-overlay:   "#1C2850"

  # Primary brand
  primary:           "#C9A84C"
  primary-dim:       "#8C6E2A"
  primary-muted:     "#2A2010"
  on-primary:        "#0A0D1A"

  # Text
  text-primary:      "#F0E8D0"
  text-secondary:    "#9A8E72"
  text-disabled:     "#3D3A30"

  # Focus / controller selection
  focus-ring:        "#F0C040"
  focus-glow:        "#C9A84C"

  # Status
  error:             "#E05252"
  on-error:          "#F0E8D0"

  # Effects
  vignette:          "#000000"
  noise-overlay:     "#FFFFFF"
  chromatic-red:     "#FF2040"
  chromatic-blue:    "#2040FF"

typography:
  display:
    fontFamily: "Press Start 2P"
    fontSize: 1.5rem
    fontWeight: 400
    lineHeight: 1.6
    letterSpacing: 0.04em

  heading:
    fontFamily: "Rajdhani"
    fontSize: 1.25rem
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: 0.06em

  body:
    fontFamily: "Rajdhani"
    fontSize: 1rem
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0.02em

  label:
    fontFamily: "Rajdhani"
    fontSize: 0.75rem
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: 0.1em

  metadata:
    fontFamily: "Share Tech Mono"
    fontSize: 0.7rem
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: 0.05em

rounded:
  none:  0px
  sm:    4px
  md:    8px
  lg:    12px
  pill:  999px

spacing:
  xs:  4px
  sm:  8px
  md:  16px
  lg:  24px
  xl:  40px
  2xl: 64px

components:
  game-card:
    backgroundColor:     "{colors.surface}"
    textColor:           "{colors.text-primary}"
    rounded:             "{rounded.md}"
    padding:             0px
    borderColor:         "{colors.surface-elevated}"
    borderWidth:         1px

  game-card-focused:
    backgroundColor:     "{colors.surface-elevated}"
    textColor:           "{colors.text-primary}"
    rounded:             "{rounded.md}"
    borderColor:         "{colors.focus-ring}"
    borderWidth:         2px

  nav-bar:
    backgroundColor:     "{colors.surface}"
    textColor:           "{colors.text-secondary}"

  nav-bar-focused:
    backgroundColor:     "{colors.surface}"
    textColor:           "{colors.primary}"

  button-primary:
    backgroundColor:     "{colors.primary}"
    textColor:           "{colors.on-primary}"
    rounded:             "{rounded.sm}"
    padding:             12px

  button-primary-focused:
    backgroundColor:     "{colors.focus-ring}"
    textColor:           "{colors.on-primary}"
    rounded:             "{rounded.sm}"
    padding:             12px

  button-ghost:
    backgroundColor:     "transparent"
    textColor:           "{colors.text-secondary}"
    rounded:             "{rounded.sm}"
    padding:             12px

  button-ghost-focused:
    backgroundColor:     "{colors.primary-muted}"
    textColor:           "{colors.primary}"
    rounded:             "{rounded.sm}"
    padding:             12px

  bottom-sheet:
    backgroundColor:     "{colors.surface-elevated}"
    textColor:           "{colors.text-primary}"
    rounded:             "{rounded.lg}"

  modal-overlay:
    backgroundColor:     "{colors.background}"

  tag:
    backgroundColor:     "{colors.primary-muted}"
    textColor:           "{colors.primary}"
    rounded:             "{rounded.sm}"

  toast:
    backgroundColor:     "{colors.surface-elevated}"
    textColor:           "{colors.text-primary}"
    rounded:             "{rounded.md}"

  settings-row:
    backgroundColor:     "{colors.surface}"
    textColor:           "{colors.text-primary}"
    rounded:             "{rounded.none}"

  settings-row-focused:
    backgroundColor:     "{colors.surface-elevated}"
    textColor:           "{colors.primary}"
    rounded:             "{rounded.none}"
---

# SambaS3 · DESIGN.md

## Overview

SambaS3 is a PS3 emulator for Android built for the couch. The interface must be navigable entirely via a Bluetooth or USB gamepad — no touch required, though touch is supported as a secondary input. Every interactive element must be reachable by D-pad/thumbstick, every focus state must be unmistakably legible at TV-viewing distance, and every modal must be dismissible with the controller's back/circle button.

The visual identity pulls from the aesthetic of premium home-cinema hardware circa 2005–2010: **deep midnight navy** as the chassis, **aged gold** as the accent material, and subtle analogue texture (grain, vignette, chromatic fringing on focus) to evoke a warm CRT living room rather than a cold emulator shell. The app is called SambaS3 and its name should appear in the display typeface in the top-center of the home screen — the way a console XMB rendered the system name.

The single signature element of this design is the **focus glow**: when a game card receives controller focus, it radiates a diffuse gold luminescence that spills onto adjacent cards and the background, recreating the warm bloom of a tube television.

---

## Colors

The palette consists of a near-black navy base, two surface layers for depth, a gold primary for interactive affordances, and warm cream text to evoke aged paper rather than sterile white.

| Role             | Token                | Value     | Usage                                                             |
|------------------|----------------------|-----------|-------------------------------------------------------------------|
| Canvas           | `background`         | `#0A0D1A` | Full-screen backgrounds, splash, out-of-panel areas               |
| Base surface     | `surface`            | `#0F1528` | Cards, drawers, nav bars, list rows                               |
| Raised surface   | `surface-elevated`   | `#172040` | Focused card backgrounds, dialogs, bottom sheets                  |
| Overlay surface  | `surface-overlay`    | `#1C2850` | Tooltips, dropdown menus                                          |
| Gold primary     | `primary`            | `#C9A84C` | Active icons, selected labels, progress fills, focus borders      |
| Gold dim         | `primary-dim`        | `#8C6E2A` | Inactive or pressed state of primary elements                     |
| Gold muted bg    | `primary-muted`      | `#2A2010` | Chip/tag backgrounds that reference primary without competing     |
| On-primary       | `on-primary`         | `#0A0D1A` | Text or icons placed directly on gold surfaces                    |
| Cream text       | `text-primary`       | `#F0E8D0` | Body copy, card titles, all readable foreground text              |
| Warm mid         | `text-secondary`     | `#9A8E72` | Subtitles, metadata, placeholder text, inactive nav labels        |
| Disabled         | `text-disabled`      | `#3D3A30` | Greyed-out items, unavailable library entries                     |
| Focus ring       | `focus-ring`         | `#F0C040` | 2 dp border on any currently focused element                      |
| Focus glow       | `focus-glow`         | `#C9A84C` | Box-shadow / elevation glow on focused game cards                 |
| Error            | `error`              | `#E05252` | Load-failure badge, controller-disconnect banner                  |
| Vignette         | `vignette`           | `#000000` | Radial gradient edge darkening at 40 % opacity                    |
| Noise overlay    | `noise-overlay`      | `#FFFFFF` | Monochrome grain texture layer at 3–5 % opacity                   |
| Chromatic red    | `chromatic-red`      | `#FF2040` | Right-channel shift of glitch chromatic-aberration effect         |
| Chromatic blue   | `chromatic-blue`     | `#2040FF` | Left-channel shift of glitch chromatic-aberration effect          |

**WCAG note:** `text-primary` (#F0E8D0) on `background` (#0A0D1A) achieves a contrast ratio of ≈ 16:1, well above AA. `primary` (#C9A84C) on `background` achieves ≈ 8.4:1, passing AA for all text sizes.

---

## Typography

Three families are used, each with a distinct role. Do not substitute.

**Press Start 2P** — pixel / retro bitmap face. Reserved exclusively for the app logotype, section headers on the home screen, and the boot splash. Never use below 14sp on mobile; it becomes illegible. Pair with generous line-height (1.6) to breathe.

**Rajdhani** — condensed sans-serif with a military-tech personality. All headings, labels, UI copy, and body text. At weight 700 it has strong geometric angularity that reads as "hardware." At weight 400 it stays legible as list metadata.

**Share Tech Mono** — monospaced terminal face. Used only for runtime stats (FPS counter, resolution badge, CPU/GPU load), version strings, and hex-format game IDs. Never use for headings.

| Token      | Family            | Size   | Weight | Line-height | Letter-spacing | Usage                              |
|------------|-------------------|--------|--------|-------------|----------------|------------------------------------|
| `display`  | Press Start 2P    | 1.5rem | 400    | 1.6         | +0.04em        | App name, boot splash headline     |
| `heading`  | Rajdhani          | 1.25rem| 700    | 1.3         | +0.06em        | Screen titles, section headers     |
| `body`     | Rajdhani          | 1rem   | 400    | 1.5         | +0.02em        | Game titles in carousel, list rows |
| `label`    | Rajdhani          | 0.75rem| 600    | 1.2         | +0.10em        | Controller-hint badges, tab labels |
| `metadata` | Share Tech Mono   | 0.70rem| 400    | 1.4         | +0.05em        | FPS, resolution, build strings     |

Minimum touch / focus target: **48 × 48 dp**. Minimum font size in production: **12sp**.

---

## Layout

### Grid & Spacing

The app uses an 8 dp base grid throughout. Padding inside cards, panels, and modals should be multiples of 8 dp. The XMB-style horizontal carousel on the Home screen is the central structural motif: a single horizontal row of game covers that extends edge-to-edge at mid-screen height.

### Controller-first Navigation Model

Every screen must define a two-dimensional focus map:
- **D-pad / left thumbstick** — moves focus between tiles and interactive elements.
- **A / Cross** — confirms / selects the focused element.
- **B / Circle** — goes back or dismisses the active modal/sheet.
- **Start / Options** — opens the system pause menu from inside a game.
- **Y / Triangle** — context action (e.g. "Game Info" when a carousel card is focused).
- **LB / RB or L1 / R1** — switches between top-level tabs/sections (Library, Settings, About).

Focus must never become trapped or ambiguous. When a modal opens, focus locks inside it. When it closes, focus returns to the element that triggered it.

Focus indicators use a **2 dp `focus-ring` gold border** plus a **diffuse outer box-shadow** in `focus-glow` at 60 % opacity and 12 dp blur — the "tube TV bloom" signature effect.

### Home / Library Screen (XMB Carousel)

```
┌──────────────────────────────────────────────────────────────────┐
│  [≡]               S A M B A S 3               [⚙]  [+]         │  ← nav bar (surface)
│                    ─────────────                                  │
│                                                                   │
│   ·  ·  ·  ·  ░░░░░░░░░░░░░░░░░░░░░░░░░  ·  ·  ·  ·            │  ← context label / art above focused card
│                ░                        ░                         │
│   ┌──┐  ┌──┐  ░   FOCUSED GAME ART     ░  ┌──┐  ┌──┐            │
│   │  │  │  │  ░   [PRIMARY CARD]       ░  │  │  │  │            │  ← carousel row (mid-screen)
│   └──┘  └──┘  ░                        ░  └──┘  └──┘            │
│                ░░░░░░░░░░░░░░░░░░░░░░░░░                         │
│                                                                   │
│          GTA: San Andreas          (game title, body)             │
│          SCES-123456  •  1080p  •  60 FPS     (metadata)          │
│                                                                   │
│   [✕ Play]   [△ Info]   [○ Back]                                  │  ← controller hint strip (label)
└──────────────────────────────────────────────────────────────────┘
```

- Carousel cards: aspect ratio **2:3** (portrait game cover), 120 dp wide on phone, 180 dp on tablet/TV.
- Focused card scales to **1.12×** and glows. Adjacent cards stay at **1.0×** with 60 % opacity.
- Cards scroll horizontally with momentum. If fewer than 5 games, center the row.
- The game title and metadata below the carousel update on every focus change.
- Controller hint strip at the bottom is always visible, showing only the buttons relevant to the current focus context.

### Settings Screen

Full-width list, grouped into sections (Emulation, Graphics, Controller, Audio, About). Each row is a `settings-row` with a left-aligned label and a right-aligned value or toggle. Navigation is **vertical D-pad only**. Focused row highlights with `settings-row-focused` and a subtle left-border accent in `primary`.

### Game Detail / Info Sheet (Bottom Sheet)

Slides up from the bottom when Triangle is pressed on a focused game card. Contains: full cover art, title, publisher, region, serial, compatibility badge, and action buttons (Play, Add Shortcut, Delete). Focus is trapped inside until dismissed with Circle.

### Pause Menu (In-Game)

Centered modal, darkened scrim behind it. Options: Resume, Save State, Load State, Screenshot, Settings, Quit. D-pad navigates the vertical list.

---

## Elevation & Depth

Depth is communicated through background color layer and glow, not drop shadows — shadows would fight the retro CRT aesthetic.

| Level | Surface token          | Used for                               |
|-------|------------------------|----------------------------------------|
| 0     | `background`           | Screen canvas                          |
| 1     | `surface`              | Cards (resting), nav bar, list rows    |
| 2     | `surface-elevated`     | Focused cards, bottom sheets, dialogs  |
| 3     | `surface-overlay`      | Tooltips, context menus                |

The focus glow acts as an additional perceptual level: a focused element at level 2 appears to float above the resting level-1 elements through its radiated gold bloom.

---

## Shapes

Corners are kept minimal to reinforce the hardware chassis feel.

| Token   | Value  | Used for                                                  |
|---------|--------|-----------------------------------------------------------|
| `none`  | 0 px   | Settings rows, full-width banners, dividers               |
| `sm`    | 4 px   | Buttons, tags, chips, toast notifications                 |
| `md`    | 8 px   | Game cards in the carousel, focused card                  |
| `lg`    | 12 px  | Bottom sheets, dialogs, game detail panel top corners     |
| `pill`  | 999 px | Controller-hint button labels (e.g. "✕ Play")            |

Never use fully circular (50 %) shapes for interactive elements; pill is the maximum rounding for buttons.

---

## Components

### Game Card (Resting)

- Size: 120 × 180 dp (phone), 180 × 270 dp (tablet/TV)
- Background: `surface`; border: 1 dp `surface-elevated`; rounded: `md`
- Cover art fills the full card face, no padding
- A 1 dp inner vignette gradient darkens the bottom 30 % — text if overlaid is always legible
- Opacity: 100 %

### Game Card (Focused)

All properties of resting, plus:
- Background: `surface-elevated`; border: 2 dp `focus-ring`
- Scale transform: 1.12× (CSS `transform: scale(1.12)`, hardware-accelerated)
- Box-shadow: `0 0 0 2px {focus-ring}, 0 0 24px 8px {focus-glow}` at 60 % opacity
- Transition: 120 ms ease-out for scale and opacity; 80 ms for focus ring
- Sibling cards: opacity drops to 60 % during focus

### Controller Hint Strip

A persistent strip pinned to the bottom of every screen. Shows up to 4 button hints in `label` typography. Each hint is an icon (rendered as the platform's native button glyph, e.g. PlayStation symbols) followed by a short action label. Background: transparent; overlays the screen canvas. Never obscures a focused card.

### Focus Ring Rule

**Every** tappable or D-pad-navigable element must display the `focus-ring` gold border + `focus-glow` box-shadow when it receives focus from a controller. There are no exceptions. Elements that clip overflow must apply the focus indicator outside their own bounding box using `outline` rather than `box-shadow` if necessary.

### Bottom Sheet (Game Info / Context)

- Rounded top corners: `lg`
- Background: `surface-elevated`
- Slide-in animation: 280 ms ease-out from bottom
- Scrim behind: `background` at 70 % opacity
- Max height: 80 % of screen height; scrollable inside if content exceeds

### FPS / Performance Overlay (In-Game)

Small floating chip, top-right corner. Font: `metadata`. Background: `background` at 75 % opacity. Shows FPS, resolution, frame time. Not focusable by controller — informational only.

### Empty Library State

Centered vertically and horizontally. Illustration: a simplified CRT outline in `surface-elevated`. Heading in `heading` typography: "No games yet." Body in `body` typography: "Press + to add a game from your device." Primary action button focused by default when this state is shown.

---

## Do's and Don'ts

**Do:**
- Always show a visible gold focus indicator for the currently active controller element.
- Use `Press Start 2P` only for the logotype and major section headers. Treat it as a headline accent, not body copy.
- Apply the noise/grain texture as a CSS `background-image` SVG filter or PNG overlay at 3–5 % opacity over the full canvas — it must be subtle enough to be subconscious.
- Apply the vignette as a radial gradient pseudo-element over the canvas, darkening edges at 35–45 % opacity.
- Apply chromatic aberration (a 2–4 px RGB channel offset) **only** on the app's boot splash and as a brief (300 ms) glitch animation when navigating between top-level sections — not on idle screens.
- Keep controller-hint labels in UPPERCASE `label` typography to match the hardware button vernacular.
- Snap horizontal carousel scrolling to card boundaries — never stop mid-card.
- Return focus to the triggering element when a modal or sheet is dismissed.

**Don't:**
- Don't use pure white (#FFFFFF) anywhere — it breaks the warm analogue feel. Use `text-primary` (#F0E8D0) for all foreground text.
- Don't use `Press Start 2P` for body text, list items, settings labels, or anything below 14 sp.
- Don't apply the chromatic aberration effect on idle or resting UI — it should feel like a deliberate momentary glitch, not a constant state.
- Don't allow any interactive element to lose focus visibility — if a component library strips `:focus` styles, override them.
- Don't use animated noise at >5 % opacity; above this it becomes distracting and accessibility-hostile.
- Don't mix rounded corners — game cards use `md` everywhere; don't mix with `sm` or `lg` in the same component.
- Don't float UI chrome (menus, tooltips) outside the defined elevation hierarchy; always use the correct surface token for the depth level.
- Don't use platform-agnostic emoji as button icons; use SVG glyphs matching the connected controller type (PlayStation / Xbox / generic) sourced at runtime.
