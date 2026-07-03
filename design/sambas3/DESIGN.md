---
version: alpha
name: SambaS3
description: |
  Design system for SambaS3, a PS3 emulator for Android. Built for controller-first navigation with a deep navy + gold retro console aesthetic, evoking the golden era of living-room gaming through premium materials and deliberate visual texture.
colors:
  background: '#0A0D1A'
  surface: '#0F1528'
  surface-elevated: '#172040'
  surface-overlay: '#1C2850'
  primary: '#C9A84C'
  primary-dim: '#8C6E2A'
  primary-muted: '#2A2010'
  on-primary: '#0A0D1A'
  text-primary: '#F0E8D0'
  text-secondary: '#9A8E72'
  text-disabled: '#3D3A30'
  focus-ring: '#F0C040'
  focus-glow: '#C9A84C'
  error: '#E05252'
  on-error: '#F0E8D0'
  vignette: '#000000'
  noise-overlay: '#FFFFFF'
  chromatic-red: '#FF2040'
  chromatic-blue: '#2040FF'
  surface-dim: '#16130d'
  surface-bright: '#3d3931'
  surface-container-lowest: '#100e08'
  surface-container-low: '#1e1b15'
  surface-container: '#221f19'
  surface-container-high: '#2d2a23'
  surface-container-highest: '#38342d'
  on-surface: '#e9e1d7'
  on-surface-variant: '#d0c5b2'
  inverse-surface: '#e9e1d7'
  inverse-on-surface: '#343029'
  outline: '#99907e'
  outline-variant: '#4d4637'
  surface-tint: '#e6c364'
  primary-container: '#c9a84c'
  on-primary-container: '#503d00'
  inverse-primary: '#755b00'
  secondary: '#c0c5df'
  on-secondary: '#2a3044'
  secondary-container: '#43485e'
  on-secondary-container: '#b2b7d1'
  tertiary: '#b9c4ff'
  on-tertiary: '#1e2b66'
  tertiary-container: '#9ba8eb'
  on-tertiary-container: '#2e3b77'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#ffe08f'
  primary-fixed-dim: '#e6c364'
  on-primary-fixed: '#241a00'
  on-primary-fixed-variant: '#584400'
  secondary-fixed: '#dce1fc'
  secondary-fixed-dim: '#c0c5df'
  on-secondary-fixed: '#151b2e'
  on-secondary-fixed-variant: '#40465b'
  tertiary-fixed: '#dde1ff'
  tertiary-fixed-dim: '#b9c3ff'
  on-tertiary-fixed: '#041451'
  on-tertiary-fixed-variant: '#35437e'
  on-background: '#e9e1d7'
  surface-variant: '#38342d'
typography:
  display:
    fontFamily: Press Start 2P
    fontSize: 1.5rem
    fontWeight: 400
    lineHeight: 1.6
    letterSpacing: 0.04em
  heading:
    fontFamily: Rajdhani
    fontSize: 1.25rem
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: 0.06em
  body:
    fontFamily: Rajdhani
    fontSize: 1rem
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0.02em
  label:
    fontFamily: Rajdhani
    fontSize: 0.75rem
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: 0.1em
  metadata:
    fontFamily: Share Tech Mono
    fontSize: 0.7rem
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: 0.05em
  display-lg:
    fontFamily: Space Mono
    fontSize: 24px
    fontWeight: '400'
    lineHeight: 38px
    letterSpacing: 0.04em
  headline-md:
    fontFamily: Hanken Grotesk
    fontSize: 20px
    fontWeight: '700'
    lineHeight: 26px
    letterSpacing: 0.06em
  body-md:
    fontFamily: Hanken Grotesk
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: 0.02em
  label-sm:
    fontFamily: Hanken Grotesk
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.1em
  metadata-xs:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 15px
    letterSpacing: 0.05em
rounded:
  none: 0px
  sm: 4px
  md: 8px
  lg: 12px
  pill: 999px
  DEFAULT: 0.5rem
  xl: 1.5rem
  full: 9999px
spacing:
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 40px
  2xl: 64px
components:
  game-card:
    backgroundColor: '{colors.surface}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.md}'
    padding: 0px
    borderColor: '{colors.surface-elevated}'
    borderWidth: 1px
  game-card-focused:
    backgroundColor: '{colors.surface-elevated}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.md}'
    borderColor: '{colors.focus-ring}'
    borderWidth: 2px
  nav-bar:
    backgroundColor: '{colors.surface}'
    textColor: '{colors.text-secondary}'
  nav-bar-focused:
    backgroundColor: '{colors.surface}'
    textColor: '{colors.primary}'
  button-primary:
    backgroundColor: '{colors.primary}'
    textColor: '{colors.on-primary}'
    rounded: '{rounded.sm}'
    padding: 12px
  button-primary-focused:
    backgroundColor: '{colors.focus-ring}'
    textColor: '{colors.on-primary}'
    rounded: '{rounded.sm}'
    padding: 12px
  button-ghost:
    backgroundColor: transparent
    textColor: '{colors.text-secondary}'
    rounded: '{rounded.sm}'
    padding: 12px
  button-ghost-focused:
    backgroundColor: '{colors.primary-muted}'
    textColor: '{colors.primary}'
    rounded: '{rounded.sm}'
    padding: 12px
  bottom-sheet:
    backgroundColor: '{colors.surface-elevated}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.lg}'
  modal-overlay:
    backgroundColor: '{colors.background}'
  tag:
    backgroundColor: '{colors.primary-muted}'
    textColor: '{colors.primary}'
    rounded: '{rounded.sm}'
  toast:
    backgroundColor: '{colors.surface-elevated}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.md}'
  settings-row:
    backgroundColor: '{colors.surface}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.none}'
  settings-row-focused:
    backgroundColor: '{colors.surface-elevated}'
    textColor: '{colors.primary}'
    rounded: '{rounded.none}'
---

