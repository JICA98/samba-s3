.text
gate:
  adr x15, gate
  ldr x16, ptr_compiled
  ldar x17, [x16]
  cmp x17, x15
  b.eq fallback
  cbz x17, fallback
  isb
  br x17
fallback:
  ldr x16, ptr_dispatch
  ldr x17, [x16]
  br x17
.align 3
ptr_compiled: .dword 0x1111111111111111
ptr_dispatch: .dword 0x2222222222222222
