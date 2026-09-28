.text
gate:
  // load pointer from pc-relative offset
  ldr x16, ptr_compiled
  // load atomic pointer from that address
  ldar x17, [x16]
  cbz x17, fallback
  isb
  br x17
fallback:
  ldr x16, ptr_interpreter
  ldr x17, [x16]
  br x17
.align 3
ptr_compiled: .dword 0
ptr_interpreter: .dword 0
