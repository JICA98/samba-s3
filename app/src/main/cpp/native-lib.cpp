#include <algorithm>
#include <android/dlext.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <chrono>
#include <deque>
#include <dirent.h>
#include <dlfcn.h>
#include <fstream>
#include <jni.h>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <sys/resource.h>
#include <unistd.h>
#include <utility>
#include <vector>

#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/vfs.h>

#if defined(__aarch64__)
#include <adrenotools/driver.h>
#include <adrenotools/priv.h>
#endif

namespace s3_perf {
void* hooked_vkGetDeviceProcAddr(void* device, const char* pName);
void set_orig_vkGetDeviceProcAddr(void* fn);
void on_frame_presented(uint64_t now_us, int source);
}

namespace s3_iso_hook {

static bool is_proc_fd_path(const char* path, int* out_fd) {
  if (!path) return false;
  if (strncmp(path, "/proc/", 6) != 0) return false;
  const char* p = path + 6;
  if (strncmp(p, "self/fd/", 8) == 0) {
    p += 8;
  } else {
    while (*p >= '0' && *p <= '9') ++p;
    if (strncmp(p, "/fd/", 4) != 0) return false;
    p += 4;
  }
  char* end = nullptr;
  long fd = strtol(p, &end, 10);
  if (end == p) return false;
  if (*end != '\0' && (*end != '/' || *(end + 1) != '\0')) {
    return false;
  }
  if (out_fd) *out_fd = static_cast<int>(fd);
  return true;
}

typedef int (*pfn_open)(const char* path, int flags, ...);
typedef int (*pfn_open_2)(const char* path, int flags);
typedef int (*pfn_openat)(int dirfd, const char* path, int flags, ...);
typedef int (*pfn_stat)(const char* path, struct stat* buf);
typedef int (*pfn_lstat)(const char* path, struct stat* buf);
typedef int (*pfn_fstatat)(int dirfd, const char* path, struct stat* buf, int flags);
typedef int (*pfn_access)(const char* path, int mode);
typedef int (*pfn_statvfs)(const char* path, struct statvfs* buf);
typedef int (*pfn_statfs)(const char* path, struct statfs* buf);
typedef char* (*pfn_realpath)(const char* path, char* resolved_path);
typedef ssize_t (*pfn_readlink)(const char* path, char* buf, size_t bufsiz);

static pfn_open g_orig_open = nullptr;
static pfn_open_2 g_orig_open_2 = nullptr;
static pfn_openat g_orig_openat = nullptr;
static pfn_stat g_orig_stat = nullptr;
static pfn_lstat g_orig_lstat = nullptr;
static pfn_fstatat g_orig_fstatat = nullptr;
static pfn_access g_orig_access = nullptr;
static pfn_statvfs g_orig_statvfs = nullptr;
static pfn_statfs g_orig_statfs = nullptr;
static pfn_realpath g_orig_realpath = nullptr;
static pfn_readlink g_orig_readlink = nullptr;

static int hooked_open(const char* path, int flags, ...) {
  mode_t mode = 0;
  if (flags & O_CREAT) {
    va_list args;
    va_start(args, flags);
    mode = static_cast<mode_t>(va_arg(args, int));
    va_end(args);
  }
  int target_fd = -1;
  if (is_proc_fd_path(path, &target_fd)) {
    int new_fd = ::fcntl(target_fd, F_DUPFD_CLOEXEC, 0);
    if (new_fd < 0) {
      new_fd = ::dup(target_fd);
    }
    if (new_fd >= 0) {
      ::lseek(new_fd, 0, SEEK_SET);
    }
    __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                        "hooked open(\"%s\", 0x%x) -> dup(%d) = %d",
                        path, flags, target_fd, new_fd);
    return new_fd;
  }
  if (g_orig_open) return g_orig_open(path, flags, mode);
  return ::open(path, flags, mode);
}

static int hooked_openat(int dirfd, const char* path, int flags, ...) {
  mode_t mode = 0;
  if (flags & O_CREAT) {
    va_list args;
    va_start(args, flags);
    mode = static_cast<mode_t>(va_arg(args, int));
    va_end(args);
  }
  int target_fd = -1;
  if (is_proc_fd_path(path, &target_fd)) {
    int new_fd = ::fcntl(target_fd, F_DUPFD_CLOEXEC, 0);
    if (new_fd < 0) {
      new_fd = ::dup(target_fd);
    }
    if (new_fd >= 0) {
      ::lseek(new_fd, 0, SEEK_SET);
    }
    __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                        "hooked openat(\"%s\", 0x%x) -> dup(%d) = %d",
                        path, flags, target_fd, new_fd);
    return new_fd;
  }
  if (g_orig_openat) return g_orig_openat(dirfd, path, flags, mode);
  return ::openat(dirfd, path, flags, mode);
}

static int hooked___open_2(const char* path, int flags) {
  int target_fd = -1;
  if (is_proc_fd_path(path, &target_fd)) {
    int new_fd = ::fcntl(target_fd, F_DUPFD_CLOEXEC, 0);
    if (new_fd < 0) {
      new_fd = ::dup(target_fd);
    }
    if (new_fd >= 0) {
      ::lseek(new_fd, 0, SEEK_SET);
    }
    __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                        "hooked __open_2(\"%s\", 0x%x) -> dup(%d) = %d",
                        path, flags, target_fd, new_fd);
    return new_fd;
  }
  if (g_orig_open_2) return g_orig_open_2(path, flags);
  return ::__open_2(path, flags);
}

static int hooked_stat(const char* path, struct stat* buf) {
  int target_fd = -1;
  if (is_proc_fd_path(path, &target_fd)) {
    int res = ::fstat(target_fd, buf);
    __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                        "hooked stat(\"%s\") -> fstat(%d) = %d (mode=0%o size=%lld)",
                        path, target_fd, res,
                        (res == 0) ? static_cast<unsigned int>(buf->st_mode) : 0,
                        (res == 0) ? static_cast<long long>(buf->st_size) : -1LL);
    return res;
  }
  if (g_orig_stat) return g_orig_stat(path, buf);
  return ::stat(path, buf);
}

static int hooked_lstat(const char* path, struct stat* buf) {
  int target_fd = -1;
  if (is_proc_fd_path(path, &target_fd)) {
    int res = ::fstat(target_fd, buf);
    __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                        "hooked lstat(\"%s\") -> fstat(%d) = %d (mode=0%o size=%lld)",
                        path, target_fd, res,
                        (res == 0) ? static_cast<unsigned int>(buf->st_mode) : 0,
                        (res == 0) ? static_cast<long long>(buf->st_size) : -1LL);
    return res;
  }
  if (g_orig_lstat) return g_orig_lstat(path, buf);
  return ::lstat(path, buf);
}

static int hooked_fstatat(int dirfd, const char* path, struct stat* buf, int flags) {
  int target_fd = -1;
  if (is_proc_fd_path(path, &target_fd)) {
    int res = ::fstat(target_fd, buf);
    __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                        "hooked fstatat(\"%s\") -> fstat(%d) = %d (mode=0%o size=%lld)",
                        path, target_fd, res,
                        (res == 0) ? static_cast<unsigned int>(buf->st_mode) : 0,
                        (res == 0) ? static_cast<long long>(buf->st_size) : -1LL);
    return res;
  }
  if (g_orig_fstatat) return g_orig_fstatat(dirfd, path, buf, flags);
  return ::fstatat(dirfd, path, buf, flags);
}

static int hooked_access(const char* path, int mode) {
  int target_fd = -1;
  if (is_proc_fd_path(path, &target_fd)) {
    struct stat st;
    int res = ::fstat(target_fd, &st);
    __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                        "hooked access(\"%s\", %d) -> fstat(%d) = %d",
                        path, mode, target_fd, res);
    return res;
  }
  if (g_orig_access) return g_orig_access(path, mode);
  return ::access(path, mode);
}

static int hooked_statvfs(const char* path, struct statvfs* buf) {
  int target_fd = -1;
  if (is_proc_fd_path(path, &target_fd)) {
    int res = ::fstatvfs(target_fd, buf);
    __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                        "hooked statvfs(\"%s\") -> fstatvfs(%d) = %d",
                        path, target_fd, res);
    return res;
  }
  if (g_orig_statvfs) return g_orig_statvfs(path, buf);
  return ::statvfs(path, buf);
}

static int hooked_statfs(const char* path, struct statfs* buf) {
  int target_fd = -1;
  if (is_proc_fd_path(path, &target_fd)) {
    int res = ::fstatfs(target_fd, buf);
    __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                        "hooked statfs(\"%s\") -> fstatfs(%d) = %d",
                        path, target_fd, res);
    return res;
  }
  if (g_orig_statfs) return g_orig_statfs(path, buf);
  return ::statfs(path, buf);
}

static char* hooked_realpath(const char* path, char* resolved_path) {
  int target_fd = -1;
  if (is_proc_fd_path(path, &target_fd)) {
    (void)target_fd;
    const size_t length = strlen(path);
    if (resolved_path == nullptr) {
      resolved_path = static_cast<char*>(malloc(length + 1));
      if (resolved_path == nullptr) return nullptr;
    }
    memcpy(resolved_path, path, length + 1);
    __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                        "hooked realpath(\"%s\") -> preserved proc fd path",
                        path);
    return resolved_path;
  }
  if (g_orig_realpath) return g_orig_realpath(path, resolved_path);
  return ::realpath(path, resolved_path);
}

static ssize_t hooked_readlink(const char* path, char* buf, size_t bufsiz) {
  int target_fd = -1;
  if (is_proc_fd_path(path, &target_fd)) {
    (void)target_fd;
    const size_t length = strlen(path);
    const size_t copied = std::min(length, bufsiz);
    if (copied > 0) memcpy(buf, path, copied);
    __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                        "hooked readlink(\"%s\") -> preserved proc fd path",
                        path);
    return static_cast<ssize_t>(copied);
  }
  if (g_orig_readlink) return g_orig_readlink(path, buf, bufsiz);
  return ::readlink(path, buf, bufsiz);
}

static void install_direct_iso_hooks(void* symbol_inside_core) {
  if (symbol_inside_core == nullptr) return;

  Dl_info dlinfo;
  if (::dladdr(symbol_inside_core, &dlinfo) == 0 || dlinfo.dli_fbase == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, "S3HOOK", "dladdr failed to find library base");
    return;
  }

  uintptr_t base = reinterpret_cast<uintptr_t>(dlinfo.dli_fbase);
  const auto* ehdr = reinterpret_cast<const ElfW(Ehdr)*>(base);
  if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) {
    __android_log_print(ANDROID_LOG_ERROR, "S3HOOK", "Invalid ELF magic at base %p", dlinfo.dli_fbase);
    return;
  }

  const auto* phdr = reinterpret_cast<const ElfW(Phdr)*>(base + ehdr->e_phoff);
  const ElfW(Dyn)* dyn_section = nullptr;

  for (ElfW(Half) i = 0; i < ehdr->e_phnum; ++i) {
    if (phdr[i].p_type == PT_DYNAMIC) {
      dyn_section = reinterpret_cast<const ElfW(Dyn)*>(base + phdr[i].p_vaddr);
      break;
    }
  }

  if (!dyn_section) {
    __android_log_print(ANDROID_LOG_ERROR, "S3HOOK", "PT_DYNAMIC not found in %s",
                        dlinfo.dli_fname ? dlinfo.dli_fname : "core");
    return;
  }

  const ElfW(Rela)* jmprel = nullptr;
  size_t plt_rel_sz = 0;
  const ElfW(Rela)* rela = nullptr;
  size_t rela_sz = 0;
  const ElfW(Sym)* symtab = nullptr;
  const char* strtab = nullptr;

  auto resolve_ptr = [base](ElfW(Addr) ptr) -> uintptr_t {
    if (ptr >= base) return ptr;
    return base + ptr;
  };

  for (const auto* d = dyn_section; d->d_tag != DT_NULL; ++d) {
    switch (d->d_tag) {
      case DT_JMPREL: jmprel = reinterpret_cast<const ElfW(Rela)*>(resolve_ptr(d->d_un.d_ptr)); break;
      case DT_PLTRELSZ: plt_rel_sz = d->d_un.d_val; break;
      case DT_RELA: rela = reinterpret_cast<const ElfW(Rela)*>(resolve_ptr(d->d_un.d_ptr)); break;
      case DT_RELASZ: rela_sz = d->d_un.d_val; break;
      case DT_SYMTAB: symtab = reinterpret_cast<const ElfW(Sym)*>(resolve_ptr(d->d_un.d_ptr)); break;
      case DT_STRTAB: strtab = reinterpret_cast<const char*>(resolve_ptr(d->d_un.d_ptr)); break;
    }
  }

  if (!symtab || !strtab) {
    __android_log_print(ANDROID_LOG_ERROR, "S3HOOK", "SYMTAB or STRTAB missing in %s",
                        dlinfo.dli_fname ? dlinfo.dli_fname : "core");
    return;
  }

  int hooked_count = 0;
  auto hook_table = [&](const ElfW(Rela)* rel_table, size_t rel_bytes, const char* table_name) {
    if (!rel_table || rel_bytes == 0) return;
    size_t count = rel_bytes / sizeof(ElfW(Rela));
    uintptr_t page_size = static_cast<uintptr_t>(getpagesize());

    for (size_t i = 0; i < count; ++i) {
      uint32_t sym_idx = ELF64_R_SYM(rel_table[i].r_info);
      const char* sym_name = strtab + symtab[sym_idx].st_name;
      void* target_hook = nullptr;

      if (strcmp(sym_name, "stat") == 0) {
        target_hook = reinterpret_cast<void*>(hooked_stat);
      } else if (strcmp(sym_name, "lstat") == 0) {
        target_hook = reinterpret_cast<void*>(hooked_lstat);
      } else if (strcmp(sym_name, "fstatat") == 0) {
        target_hook = reinterpret_cast<void*>(hooked_fstatat);
      } else if (strcmp(sym_name, "open") == 0) {
        target_hook = reinterpret_cast<void*>(hooked_open);
      } else if (strcmp(sym_name, "__open_2") == 0) {
        target_hook = reinterpret_cast<void*>(hooked___open_2);
      } else if (strcmp(sym_name, "openat") == 0) {
        target_hook = reinterpret_cast<void*>(hooked_openat);
      } else if (strcmp(sym_name, "access") == 0) {
        target_hook = reinterpret_cast<void*>(hooked_access);
      } else if (strcmp(sym_name, "statvfs") == 0) {
        target_hook = reinterpret_cast<void*>(hooked_statvfs);
      } else if (strcmp(sym_name, "statfs") == 0) {
        target_hook = reinterpret_cast<void*>(hooked_statfs);
      } else if (strcmp(sym_name, "realpath") == 0) {
        target_hook = reinterpret_cast<void*>(hooked_realpath);
      } else if (strcmp(sym_name, "readlink") == 0) {
        target_hook = reinterpret_cast<void*>(hooked_readlink);
      } else if (strcmp(sym_name, "vkGetDeviceProcAddr") == 0) {
        target_hook = reinterpret_cast<void*>(s3_perf::hooked_vkGetDeviceProcAddr);
      }

      if (target_hook != nullptr) {
        uintptr_t got_addr = base + rel_table[i].r_offset;
        void** slot = reinterpret_cast<void**>(got_addr);

        uintptr_t page_start = got_addr & ~(page_size - 1);
        mprotect(reinterpret_cast<void*>(page_start), page_size, PROT_READ | PROT_WRITE);
        uintptr_t page_end = (got_addr + sizeof(void*) - 1) & ~(page_size - 1);
        if (page_end != page_start) {
          mprotect(reinterpret_cast<void*>(page_end), page_size, PROT_READ | PROT_WRITE);
        }

        if (*slot != target_hook) {
          if (strcmp(sym_name, "stat") == 0 && !g_orig_stat) g_orig_stat = reinterpret_cast<pfn_stat>(*slot);
          else if (strcmp(sym_name, "lstat") == 0 && !g_orig_lstat) g_orig_lstat = reinterpret_cast<pfn_lstat>(*slot);
          else if (strcmp(sym_name, "fstatat") == 0 && !g_orig_fstatat) g_orig_fstatat = reinterpret_cast<pfn_fstatat>(*slot);
          else if (strcmp(sym_name, "open") == 0 && !g_orig_open) g_orig_open = reinterpret_cast<pfn_open>(*slot);
          else if (strcmp(sym_name, "__open_2") == 0 && !g_orig_open_2) g_orig_open_2 = reinterpret_cast<pfn_open_2>(*slot);
          else if (strcmp(sym_name, "openat") == 0 && !g_orig_openat) g_orig_openat = reinterpret_cast<pfn_openat>(*slot);
          else if (strcmp(sym_name, "access") == 0 && !g_orig_access) g_orig_access = reinterpret_cast<pfn_access>(*slot);
          else if (strcmp(sym_name, "statvfs") == 0 && !g_orig_statvfs) g_orig_statvfs = reinterpret_cast<pfn_statvfs>(*slot);
          else if (strcmp(sym_name, "statfs") == 0 && !g_orig_statfs) g_orig_statfs = reinterpret_cast<pfn_statfs>(*slot);
          else if (strcmp(sym_name, "realpath") == 0 && !g_orig_realpath) g_orig_realpath = reinterpret_cast<pfn_realpath>(*slot);
          else if (strcmp(sym_name, "readlink") == 0 && !g_orig_readlink) g_orig_readlink = reinterpret_cast<pfn_readlink>(*slot);
          else if (strcmp(sym_name, "vkGetDeviceProcAddr") == 0) s3_perf::set_orig_vkGetDeviceProcAddr(*slot);

          *slot = target_hook;
          hooked_count++;
          __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                              "Hooked %s in %s GOT at %p (offset 0x%lx)",
                              sym_name, table_name, slot, static_cast<unsigned long>(rel_table[i].r_offset));
        }
      }
    }
  };

  hook_table(jmprel, plt_rel_sz, "JMPREL");
  hook_table(rela, rela_sz, "RELA");

  __android_log_print(ANDROID_LOG_INFO, "S3HOOK",
                      "Installed %d direct ISO hooks into %s (base %p)",
                      hooked_count, dlinfo.dli_fname ? dlinfo.dli_fname : "core", reinterpret_cast<void*>(base));
}

}  // namespace s3_iso_hook

namespace s3_perf {

struct TimedSample {
  uint64_t timestamp_us;
  float value;
};

static std::mutex g_perf_mutex;
static std::atomic<bool> g_perf_enabled{false};
static std::atomic<int> g_perf_interval_ms{300};
static std::atomic<uint64_t> g_presented_frames{0};
static std::atomic<uint64_t> g_last_present_us{0};
static std::atomic<float> g_latest_fps{0.0f};
static std::atomic<float> g_latest_frametime_ms{0.0f};
static std::atomic<int> g_fps_source{0}; // 0 unknown, 1 surface, 2 vk_present, 3 emu_flip

static std::deque<TimedSample> g_fps_samples;
static std::deque<TimedSample> g_frametime_samples;
static std::deque<uint64_t> g_frame_timestamps_us;

static int (*g_orig_queueBuffer)(void* window, void* buffer, int fenceFd) = nullptr;
static void* g_hooked_window = nullptr;

using PFN_vkVoidFunction = void* (*)();
using PFN_vkGetDeviceProcAddr = PFN_vkVoidFunction (*)(void* device, const char* name);
using PFN_vkQueuePresentKHR = int (*)(void* queue, const void* present_info);

static PFN_vkGetDeviceProcAddr g_orig_vkGetDeviceProcAddr = nullptr;
static PFN_vkQueuePresentKHR g_orig_vkQueuePresentKHR = nullptr;

static uint64_t get_time_us() {
  using namespace std::chrono;
  return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

void on_frame_presented(uint64_t now_us, int source) {
  // Vulkan present and ANativeWindow queueBuffer can fire for the same flip.
  uint64_t prev_us = g_last_present_us.load(std::memory_order_relaxed);
  if (prev_us > 0 && now_us >= prev_us && (now_us - prev_us) < 800ULL) {
    return;
  }

  g_presented_frames.fetch_add(1, std::memory_order_relaxed);
  if (source > 0) g_fps_source.store(source, std::memory_order_relaxed);

  std::lock_guard<std::mutex> lock(g_perf_mutex);
  prev_us = g_last_present_us.exchange(now_us, std::memory_order_relaxed);
  if (prev_us > 0 && now_us > prev_us && (now_us - prev_us) >= 800ULL) {
    float ft_ms = static_cast<float>(now_us - prev_us) / 1000.0f;
    g_latest_frametime_ms.store(ft_ms, std::memory_order_relaxed);

    g_frametime_samples.push_back({now_us, ft_ms});
    while (g_frametime_samples.size() > 240) {
      g_frametime_samples.pop_front();
    }
  }

  g_frame_timestamps_us.push_back(now_us);
  while (!g_frame_timestamps_us.empty() && now_us - g_frame_timestamps_us.front() > 1000000ULL) {
    g_frame_timestamps_us.pop_front();
  }

  if (g_frame_timestamps_us.size() >= 2) {
    uint64_t window_us = now_us - g_frame_timestamps_us.front();
    if (window_us > 0) {
      float fps = static_cast<float>((g_frame_timestamps_us.size() - 1) * 1000000.0 / window_us);
      g_latest_fps.store(fps, std::memory_order_relaxed);

      if (g_fps_samples.empty() || now_us - g_fps_samples.back().timestamp_us >= 50000ULL) {
        g_fps_samples.push_back({now_us, fps});
        while (g_fps_samples.size() > 60) {
          g_fps_samples.pop_front();
        }
      }
    }
  }
}

static int hooked_vkQueuePresentKHR(void* queue, const void* present_info) {
  on_frame_presented(get_time_us(), 2);
  if (g_orig_vkQueuePresentKHR != nullptr) {
    return g_orig_vkQueuePresentKHR(queue, present_info);
  }
  return 0;
}

void set_orig_vkGetDeviceProcAddr(void* fn) {
  if (fn != nullptr && g_orig_vkGetDeviceProcAddr == nullptr) {
    g_orig_vkGetDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(fn);
  }
}

void* hooked_vkGetDeviceProcAddr(void* device, const char* pName) {
  PFN_vkVoidFunction fn = nullptr;
  if (g_orig_vkGetDeviceProcAddr != nullptr) {
    fn = g_orig_vkGetDeviceProcAddr(device, pName);
  }
  if (pName != nullptr && fn != nullptr && strcmp(pName, "vkQueuePresentKHR") == 0) {
    g_orig_vkQueuePresentKHR = reinterpret_cast<PFN_vkQueuePresentKHR>(fn);
    static std::atomic<int> s_vk_hook_logs{0};
    if (s_vk_hook_logs.fetch_add(1) < 3) {
      __android_log_print(ANDROID_LOG_INFO, "S3PERF", "wrapped vkQueuePresentKHR orig=%p", fn);
    }
    return reinterpret_cast<void*>(hooked_vkQueuePresentKHR);
  }
  return reinterpret_cast<void*>(fn);
}

typedef int (*pfn_ANativeWindow_setQueueBufferInterceptor)(
    ANativeWindow* window,
    int (*interceptor)(ANativeWindow*, int (*)(ANativeWindow*, void*, int), void*, void*, int),
    void* data);

static int s3_interceptor_queueBuffer(
    ANativeWindow* window,
    int (*orig_queueBuffer)(ANativeWindow*, void*, int),
    void* /*data*/,
    void* buffer,
    int fenceFd) {
  uint64_t now_us = get_time_us();
  on_frame_presented(now_us, 1);
  static std::atomic<int> s_intercept_hits{0};
  if (s_intercept_hits.fetch_add(1) < 3) {
    __android_log_print(ANDROID_LOG_INFO, "S3PERF",
                        "queueBuffer interceptor fired win=%p total=%llu", window,
                        (unsigned long long)g_presented_frames.load());
  }
  if (orig_queueBuffer != nullptr) {
    return orig_queueBuffer(window, buffer, fenceFd);
  }
  return 0;
}

static int s3_hooked_queueBuffer(void* window, void* buffer, int fenceFd) {
  uint64_t now_us = get_time_us();
  on_frame_presented(now_us, 1);
  static std::atomic<int> s_slot_hits{0};
  if (s_slot_hits.fetch_add(1) < 3) {
    __android_log_print(ANDROID_LOG_INFO, "S3PERF",
                        "queueBuffer slot hook fired win=%p total=%llu", window,
                        (unsigned long long)g_presented_frames.load());
  }
  if (g_orig_queueBuffer != nullptr) {
    return g_orig_queueBuffer(window, buffer, fenceFd);
  }
  return 0;
}

#define S3_NATIVE_WINDOW_MAGIC 0x5f776e64

static void try_hook_native_window(ANativeWindow* win) {
  if (win == nullptr) return;

  static pfn_ANativeWindow_setQueueBufferInterceptor set_interceptor_hook = nullptr;
  static std::once_flag interceptor_once;
  std::call_once(interceptor_once, []() {
    set_interceptor_hook = reinterpret_cast<pfn_ANativeWindow_setQueueBufferInterceptor>(
        dlsym(RTLD_DEFAULT, "ANativeWindow_setQueueBufferInterceptor"));
    if (set_interceptor_hook == nullptr) {
      void* nw = dlopen("libnativewindow.so", RTLD_NOW | RTLD_NOLOAD);
      if (nw == nullptr) nw = dlopen("libnativewindow.so", RTLD_NOW);
      if (nw != nullptr) {
        set_interceptor_hook = reinterpret_cast<pfn_ANativeWindow_setQueueBufferInterceptor>(
            dlsym(nw, "ANativeWindow_setQueueBufferInterceptor"));
      }
    }
  });
  __android_log_print(ANDROID_LOG_INFO, "S3PERF",
                      "try_hook win=%p interceptor_api=%p hooked_window=%p",
                      win, (void*)set_interceptor_hook, g_hooked_window);
  if (set_interceptor_hook != nullptr) {
    int ret = set_interceptor_hook(win, s3_interceptor_queueBuffer, nullptr);
    __android_log_print(ANDROID_LOG_INFO, "S3PERF",
                        "ANativeWindow_setQueueBufferInterceptor registered: ret=%d", ret);
    if (ret == 0) {
      g_hooked_window = win;
      return;
    }
  }

  uint32_t* magic_ptr = reinterpret_cast<uint32_t*>(win);
  if (*magic_ptr != S3_NATIVE_WINDOW_MAGIC) {
    __android_log_print(ANDROID_LOG_WARN, "S3PERF", "ANativeWindow magic mismatch: 0x%x", *magic_ptr);
    return;
  }

#if defined(__aarch64__) || defined(__x86_64__)
  constexpr size_t queueBuffer_offset = 160;
#else
  constexpr size_t queueBuffer_offset = 80;
#endif

  auto* fn_slot = reinterpret_cast<int (**)(void*, void*, int)>(reinterpret_cast<char*>(win) + queueBuffer_offset);
  if (*fn_slot != s3_hooked_queueBuffer) {
    g_orig_queueBuffer = *fn_slot;
    *fn_slot = s3_hooked_queueBuffer;
    g_hooked_window = win;
    __android_log_print(ANDROID_LOG_INFO, "S3PERF", "Hooked ANativeWindow::queueBuffer at %p (orig=%p)",
                        fn_slot, g_orig_queueBuffer);
  }
}

static void unhook_native_window(ANativeWindow* win) {
  if (win == nullptr) return;
  static auto set_interceptor = reinterpret_cast<pfn_ANativeWindow_setQueueBufferInterceptor>(
      dlsym(RTLD_DEFAULT, "ANativeWindow_setQueueBufferInterceptor"));
  if (set_interceptor != nullptr) {
    set_interceptor(win, nullptr, nullptr);
  }

  if (g_orig_queueBuffer != nullptr) {
#if defined(__aarch64__) || defined(__x86_64__)
    constexpr size_t queueBuffer_offset = 160;
#else
    constexpr size_t queueBuffer_offset = 80;
#endif
    auto* fn_slot = reinterpret_cast<int (**)(void*, void*, int)>(reinterpret_cast<char*>(win) + queueBuffer_offset);
    if (*fn_slot == s3_hooked_queueBuffer) {
      *fn_slot = g_orig_queueBuffer;
      __android_log_print(ANDROID_LOG_INFO, "S3PERF", "Restored ANativeWindow::queueBuffer");
    }
  }
  g_orig_queueBuffer = nullptr;
  g_hooked_window = nullptr;
}

static void set_enabled(bool enabled, int interval_ms) {
  g_perf_enabled.store(enabled);
  g_perf_interval_ms.store(interval_ms);
  if (!enabled) {
    std::lock_guard<std::mutex> lock(g_perf_mutex);
    g_fps_samples.clear();
    g_frametime_samples.clear();
    g_frame_timestamps_us.clear();
    g_latest_fps.store(0.0f);
    g_latest_frametime_ms.store(0.0f);
    g_last_present_us.store(0);
  }
}

struct ProcCpuStats {
  uint64_t last_check_us = 0;
  uint64_t last_proc_ticks = 0;
  uint64_t last_ppu_ticks = 0;
  uint64_t last_spu_ticks = 0;
  uint64_t last_rsx_ticks = 0;
  float proc_cpu = 0.0f;
  float ppu_cpu = 0.0f;
  float spu_cpu = 0.0f;
  float rsx_cpu = 0.0f;
  int ppu_threads = 0;
  int spu_threads = 0;
  int host_threads = 0;
};
static ProcCpuStats g_cpu_stats;

static void update_proc_cpu_stats() {
  uint64_t now_us = get_time_us();
  if (g_cpu_stats.last_check_us > 0 && now_us - g_cpu_stats.last_check_us < 200000ULL) {
    return;
  }

  uint64_t proc_ticks = 0;
  {
    std::ifstream stat_file("/proc/self/stat");
    if (stat_file.is_open()) {
      std::string line;
      if (std::getline(stat_file, line)) {
        auto close_paren = line.rfind(')');
        if (close_paren != std::string::npos && close_paren + 2 < line.size()) {
          std::istringstream iss(line.substr(close_paren + 2));
          std::string token;
          for (int i = 3; i <= 13 && iss >> token; ++i) {}
          uint64_t utime = 0, stime = 0;
          if (iss >> utime >> stime) {
            proc_ticks = utime + stime;
          }
        }
      }
    }
  }

  int total_threads = 0;
  int ppu_count = 0;
  int spu_count = 0;
  uint64_t ppu_ticks = 0;
  uint64_t spu_ticks = 0;
  uint64_t rsx_ticks = 0;

  DIR* task_dir = opendir("/proc/self/task");
  if (task_dir != nullptr) {
    struct dirent* entry;
    while ((entry = readdir(task_dir)) != nullptr) {
      if (entry->d_name[0] == '.') continue;
      total_threads++;
      char comm_path[64];
      snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%s/comm", entry->d_name);
      std::ifstream comm_file(comm_path);
      if (!comm_file.is_open()) continue;
      std::string comm;
      std::getline(comm_file, comm);

      bool is_ppu = (comm.rfind("PPU", 0) == 0);
      bool is_spu = (comm.rfind("SPU", 0) == 0);
      bool is_rsx = (comm == "rsx::thread" || comm.rfind("rsx", 0) == 0);

      if (is_ppu) ppu_count++;
      if (is_spu) spu_count++;

      if (is_ppu || is_spu || is_rsx) {
        char stat_path[64];
        snprintf(stat_path, sizeof(stat_path), "/proc/self/task/%s/stat", entry->d_name);
        std::ifstream t_stat(stat_path);
        if (t_stat.is_open()) {
          std::string t_line;
          if (std::getline(t_stat, t_line)) {
            auto cp = t_line.rfind(')');
            if (cp != std::string::npos && cp + 2 < t_line.size()) {
              std::istringstream iss(t_line.substr(cp + 2));
              std::string tok;
              for (int i = 3; i <= 13 && iss >> tok; ++i) {}
              uint64_t u = 0, s = 0;
              if (iss >> u >> s) {
                uint64_t t = u + s;
                if (is_ppu) ppu_ticks += t;
                if (is_spu) spu_ticks += t;
                if (is_rsx) rsx_ticks += t;
              }
            }
          }
        }
      }
    }
    closedir(task_dir);
  }

  long clk_tck = sysconf(_SC_CLK_TCK);
  if (clk_tck <= 0) clk_tck = 100;

  if (g_cpu_stats.last_check_us > 0 && now_us > g_cpu_stats.last_check_us) {
    double elapsed_sec = static_cast<double>(now_us - g_cpu_stats.last_check_us) / 1000000.0;
    if (elapsed_sec > 0.05) {
      if (proc_ticks >= g_cpu_stats.last_proc_ticks) {
        g_cpu_stats.proc_cpu = static_cast<float>((proc_ticks - g_cpu_stats.last_proc_ticks) / (elapsed_sec * clk_tck) * 100.0);
      }
      if (ppu_ticks >= g_cpu_stats.last_ppu_ticks) {
        g_cpu_stats.ppu_cpu = static_cast<float>((ppu_ticks - g_cpu_stats.last_ppu_ticks) / (elapsed_sec * clk_tck) * 100.0);
      }
      if (spu_ticks >= g_cpu_stats.last_spu_ticks) {
        g_cpu_stats.spu_cpu = static_cast<float>((spu_ticks - g_cpu_stats.last_spu_ticks) / (elapsed_sec * clk_tck) * 100.0);
      }
      if (rsx_ticks >= g_cpu_stats.last_rsx_ticks) {
        g_cpu_stats.rsx_cpu = static_cast<float>((rsx_ticks - g_cpu_stats.last_rsx_ticks) / (elapsed_sec * clk_tck) * 100.0);
      }
    }
  }

  g_cpu_stats.last_check_us = now_us;
  g_cpu_stats.last_proc_ticks = proc_ticks;
  g_cpu_stats.last_ppu_ticks = ppu_ticks;
  g_cpu_stats.last_spu_ticks = spu_ticks;
  g_cpu_stats.last_rsx_ticks = rsx_ticks;
  g_cpu_stats.ppu_threads = ppu_count;
  g_cpu_stats.spu_threads = spu_count;
  g_cpu_stats.host_threads = total_threads;
}

static std::string build_fallback_json() {
  uint64_t now_us = get_time_us();
  update_proc_cpu_stats();

  std::lock_guard<std::mutex> lock(g_perf_mutex);
  uint64_t frames = g_presented_frames.load(std::memory_order_relaxed);
  uint64_t last_us = g_last_present_us.load(std::memory_order_relaxed);
  bool fresh = (frames > 0 && last_us > 0 && (now_us >= last_us) && (now_us - last_us < 2000000ULL));
  float fps = fresh ? g_latest_fps.load(std::memory_order_relaxed) : 0.0f;
  float ft_ms = fresh ? g_latest_frametime_ms.load(std::memory_order_relaxed) : 0.0f;

  std::ostringstream json;
  json << "{"
       << "\"version\":2,"
       << "\"enabled\":" << (g_perf_enabled.load() ? "true" : "false") << ","
       << "\"timestampUs\":" << now_us << ","
       << "\"presentedFrameCount\":" << frames << ","
       << "\"frameSampleFresh\":" << (fresh ? "true" : "false") << ","
       << "\"fpsSource\":\"" << (g_fps_source.load() == 2 ? "vk_present" : (g_fps_source.load() == 3 ? "emu_flip" : "surface")) << "\","
       << "\"hostCpu\":" << g_cpu_stats.proc_cpu << ","
       << "\"ppuCpu\":" << g_cpu_stats.ppu_cpu << ","
       << "\"spuCpu\":" << g_cpu_stats.spu_cpu << ","
       << "\"rsxCpu\":" << g_cpu_stats.rsx_cpu << ","
       << "\"rsxLoad\":" << std::min(100, static_cast<int>(g_cpu_stats.rsx_cpu)) << ","
       << "\"ppuThreads\":" << g_cpu_stats.ppu_threads << ","
       << "\"spuThreads\":" << g_cpu_stats.spu_threads << ","
       << "\"hostThreads\":" << g_cpu_stats.host_threads;

  if (fresh) {
    json << ",\"fps\":" << fps
         << ",\"frametimeMs\":" << ft_ms;
  }

  json << ",\"fpsSamples\":[";
  for (size_t i = 0; i < g_fps_samples.size(); ++i) {
    if (i > 0) json << ",";
    json << "{\"timestampUs\":" << g_fps_samples[i].timestamp_us
         << ",\"value\":" << g_fps_samples[i].value << "}";
  }
  json << "],\"frametimeSamples\":[";
  for (size_t i = 0; i < g_frametime_samples.size(); ++i) {
    if (i > 0) json << ",";
    json << "{\"timestampUs\":" << g_frametime_samples[i].timestamp_us
         << ",\"value\":" << g_frametime_samples[i].value << "}";
  }
  json << "]}";

  return json.str();
}

} // namespace s3_perf

struct RPCSXApi {
  bool (*overlayPadData)(int digital1, int digital2, int leftStickX,
                         int leftStickY, int rightStickX, int rightStickY);
  bool (*initialize)(std::string_view rootDir, std::string_view user);
  bool (*processCompilationQueue)(JNIEnv *env);
  bool (*startMainThreadProcessor)(JNIEnv *env);
  bool (*isMainThreadProcessorReady)();
  bool (*setCompileProgressListener)(JNIEnv *env, jobject callback);
  bool (*supportsCompileProgressEvents)(JNIEnv *env, jobject thiz);
  bool (*collectGameInfo)(JNIEnv *env, std::string_view rootDir,
                           long progressId);
  void (*shutdown)();
  int (*boot)(std::string_view path_);
  int (*getState)();
  void (*kill)();
  void (*resume)();
  std::string (*getTitleId)();
  int (*bootSavestate)(std::string_view savestatePath, std::string_view originalGamePath);
  void (*clearSavestateProgress)();
  bool (*surfaceEvent)(JNIEnv *env, jobject surface, jint event);
  bool (*surfaceEventV2)(JNIEnv *env, jobject surface, jint event, jlong generation);
  bool (*usbDeviceEvent)(int fd, int vendorId, int productId, int event);
  bool (*installFw)(JNIEnv *env, int fd, long progressId);
  bool (*isInstallableFile)(jint fd);
  jstring (*getDirInstallPath)(JNIEnv *env, jint fd);
  bool (*install)(JNIEnv *env, int fd, long progressId);
  bool (*installKey)(JNIEnv *env, int fd, long progressId,
                      std::string_view gamePath);
  std::string (*systemInfo)();
  void (*loginUser)(std::string_view userId);
  std::string (*getUser)();
  std::string (*settingsGet)(std::string_view path);
  bool (*settingsSet)(std::string_view path, std::string_view valueString);
  std::string (*settingsGetGlobal)(std::string_view path);
  bool (*settingsSetGlobal)(std::string_view path, std::string_view valueString);
  std::string (*gameSettingsOverridesGet)(std::string_view titleId);
  bool (*gameSettingsOverrideSet)(std::string_view titleId, std::string_view path, std::string_view valueString);
  bool (*gameSettingsOverrideClear)(std::string_view titleId, std::string_view path);
  bool (*gameSettingsOverridesClear)(std::string_view titleId);
  std::string (*settingsGetEffective)(std::string_view titleId, std::string_view path);
  std::string (*getVersion)();
  std::string (*getPerfMetricsJson)();
  bool (*setPerfMetricsEnabled)(bool enabled, int intervalMs);
  std::string (*patchEngineVersion)();
  std::string (*patchesList)();
  bool (*patchSetEnabled)(std::string_view hash, std::string_view description, bool enabled);
     const char* (*getPpuManifestKey)();
    const char* (*getPpuManifestKeyForTitle)(const char* titleId);
    const char* (*getSambaBuildId)();
    void *(*setCustomDriver)(void *driverHandle);
    int (*extractIsoPreview)(int fd, const char* destPath);
    int (*prepareRuntimePpu)(const char* path, unsigned long long sessionId);
    bool (*cancelRuntimePpuPreparation)(unsigned long long sessionId);
    const char* (*compileInstallPpuBatch)(const char* titleId, const char* gamePath, unsigned long long logicalJobId, unsigned int maxNewObjects);
    const char* (*compileRuntimePpuBatch)(const char* titleId, const char* gamePath, unsigned long long logicalJobId, unsigned int maxNewObjects);
    void (*cancelInstallPpuBatch)();
  // Frontend Home Menu ownership — optional symbols
  bool (*beginFrontendMenu)();
  void (*endFrontendMenu)(bool resumeIfOwned);
  bool (*isFrontendMenuOpen)();
  bool (*setFrontendEventListener)(JNIEnv *env, jobject callback);
  std::string (*inGameMenuCapabilities)();
  bool (*requestScreenshot)();
  bool (*toggleRecording)();
  bool (*restartGame)();
  bool (*gracefulShutdown)();
  std::string (*getSaveStateInfo)();
  bool (*saveState)(int slot);
  bool (*loadSaveState)(int slot);
  std::string (*getCurrentTrophies)();
  std::string (*getTrophiesForTitle)(const char* titleId);
  std::string (*getFriends)();
  bool (*friendAction)(std::string_view action, std::string_view username);
  bool (*beginInGameSettingsSession)();
  bool (*settingsSetTransient)(std::string_view path, std::string_view valueString);
  bool (*commitInGameSettingsSession)();
  bool (*discardInGameSettingsSession)();
  bool (*hasDirtyInGameSettings)();
  void (*endInGameSettingsSession)();
};

struct RPCSXLibrary : RPCSXApi {
  void *handle = nullptr;

  RPCSXLibrary() = default;
  RPCSXLibrary(const RPCSXLibrary &) = delete;
  RPCSXLibrary(RPCSXLibrary &&other) { swap(other); }
  RPCSXLibrary &operator=(RPCSXLibrary &&other) {
    swap(other);
    return *this;
  }
  ~RPCSXLibrary() {
    if (handle) {
      ::dlclose(handle);
    }
  }

  void swap(RPCSXLibrary &other) noexcept {
    std::swap(handle, other.handle);
    std::swap(static_cast<RPCSXApi &>(*this), static_cast<RPCSXApi &>(other));
  }

  static std::optional<RPCSXLibrary> Open(const char *path) {
    void *handle = ::dlopen(path, RTLD_LOCAL | RTLD_NOW);
    if (handle == nullptr) {
      __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI",
                          "Failed to open RPCSX library at %s, error %s", path,
                          ::dlerror());
      return {};
    }

    RPCSXLibrary result;
    result.handle = handle;

    // clang-format off
    result.overlayPadData = reinterpret_cast<decltype(overlayPadData)>(dlsym(handle, "_rpcsx_overlayPadData"));
    result.initialize = reinterpret_cast<decltype(initialize)>(dlsym(handle, "_rpcsx_initialize"));
    result.processCompilationQueue = reinterpret_cast<decltype(processCompilationQueue)>(dlsym(handle, "_rpcsx_processCompilationQueue"));
    result.startMainThreadProcessor = reinterpret_cast<decltype(startMainThreadProcessor)>(dlsym(handle, "_rpcsx_startMainThreadProcessor"));
    result.isMainThreadProcessorReady = reinterpret_cast<decltype(isMainThreadProcessorReady)>(dlsym(handle, "_rpcsx_isMainThreadProcessorReady"));
    result.collectGameInfo = reinterpret_cast<decltype(collectGameInfo)>(dlsym(handle, "_rpcsx_collectGameInfo"));
    result.shutdown = reinterpret_cast<decltype(shutdown)>(dlsym(handle, "_rpcsx_shutdown"));
    result.boot = reinterpret_cast<decltype(boot)>(dlsym(handle, "_rpcsx_boot"));
    result.getState = reinterpret_cast<decltype(getState)>(dlsym(handle, "_rpcsx_getState"));
    result.kill = reinterpret_cast<decltype(kill)>(dlsym(handle, "_rpcsx_kill"));
    result.resume = reinterpret_cast<decltype(resume)>(dlsym(handle, "_rpcsx_resume"));
    result.getTitleId = reinterpret_cast<decltype(getTitleId)>(dlsym(handle, "_rpcsx_getTitleId"));
    result.bootSavestate = reinterpret_cast<decltype(bootSavestate)>(dlsym(handle, "_rpcsx_bootSavestate"));
    result.clearSavestateProgress = reinterpret_cast<decltype(clearSavestateProgress)>(dlsym(handle, "_rpcsx_clearSavestateProgress"));
    result.surfaceEvent = reinterpret_cast<decltype(surfaceEvent)>(dlsym(handle, "_rpcsx_surfaceEvent"));
    result.surfaceEventV2 = reinterpret_cast<decltype(surfaceEventV2)>(dlsym(handle, "_rpcsx_surfaceEventV2"));
    result.usbDeviceEvent = reinterpret_cast<decltype(usbDeviceEvent)>(dlsym(handle, "_rpcsx_usbDeviceEvent"));
    result.installFw = reinterpret_cast<decltype(installFw)>(dlsym(handle, "_rpcsx_installFw"));
    result.isInstallableFile = reinterpret_cast<decltype(isInstallableFile)>(dlsym(handle, "_rpcsx_isInstallableFile"));
    result.getDirInstallPath = reinterpret_cast<decltype(getDirInstallPath)>(dlsym(handle, "_rpcsx_getDirInstallPath"));
    result.install = reinterpret_cast<decltype(install)>(dlsym(handle, "_rpcsx_install"));
    result.installKey = reinterpret_cast<decltype(installKey)>(dlsym(handle, "_rpcsx_installKey"));
    result.systemInfo = reinterpret_cast<decltype(systemInfo)>(dlsym(handle, "_rpcsx_systemInfo"));
    result.loginUser = reinterpret_cast<decltype(loginUser)>(dlsym(handle, "_rpcsx_loginUser"));
    result.getUser = reinterpret_cast<decltype(getUser)>(dlsym(handle, "_rpcsx_getUser"));
    result.settingsGet = reinterpret_cast<decltype(settingsGet)>(dlsym(handle, "_rpcsx_settingsGet"));
    result.settingsSet = reinterpret_cast<decltype(settingsSet)>(dlsym(handle, "_rpcsx_settingsSet"));
    result.settingsGetGlobal = reinterpret_cast<decltype(settingsGetGlobal)>(dlsym(handle, "_rpcsx_settingsGetGlobal"));
    result.settingsSetGlobal = reinterpret_cast<decltype(settingsSetGlobal)>(dlsym(handle, "_rpcsx_settingsSetGlobal"));
    result.gameSettingsOverridesGet = reinterpret_cast<decltype(gameSettingsOverridesGet)>(dlsym(handle, "_rpcsx_gameSettingsOverridesGet"));
    result.gameSettingsOverrideSet = reinterpret_cast<decltype(gameSettingsOverrideSet)>(dlsym(handle, "_rpcsx_gameSettingsOverrideSet"));
    result.gameSettingsOverrideClear = reinterpret_cast<decltype(gameSettingsOverrideClear)>(dlsym(handle, "_rpcsx_gameSettingsOverrideClear"));
    result.gameSettingsOverridesClear = reinterpret_cast<decltype(gameSettingsOverridesClear)>(dlsym(handle, "_rpcsx_gameSettingsOverridesClear"));
    result.settingsGetEffective = reinterpret_cast<decltype(settingsGetEffective)>(dlsym(handle, "_rpcsx_settingsGetEffective"));
    result.getVersion = reinterpret_cast<decltype(getVersion)>(dlsym(handle, "_rpcsx_getVersion"));
    result.getPerfMetricsJson = reinterpret_cast<decltype(getPerfMetricsJson)>(dlsym(handle, "_rpcsx_getPerfMetricsJson"));
    result.setPerfMetricsEnabled = reinterpret_cast<decltype(setPerfMetricsEnabled)>(dlsym(handle, "_rpcsx_setPerfMetricsEnabled"));
    result.patchEngineVersion = reinterpret_cast<decltype(patchEngineVersion)>(dlsym(handle, "_rpcsx_patchEngineVersion"));
    result.patchesList = reinterpret_cast<decltype(patchesList)>(dlsym(handle, "_rpcsx_patchesList"));
    result.patchSetEnabled = reinterpret_cast<decltype(patchSetEnabled)>(dlsym(handle, "_rpcsx_patchSetEnabled"));
    result.getPpuManifestKey = reinterpret_cast<decltype(getPpuManifestKey)>(dlsym(handle, "_rpcsx_getPpuManifestKey"));
    result.getPpuManifestKeyForTitle = reinterpret_cast<decltype(getPpuManifestKeyForTitle)>(dlsym(handle, "_rpcsx_getPpuManifestKeyForTitle"));
    result.getSambaBuildId = reinterpret_cast<decltype(getSambaBuildId)>(dlsym(handle, "_rpcsx_sambaBuildId"));
    result.setCustomDriver = reinterpret_cast<decltype(setCustomDriver)>(dlsym(handle, "_rpcsx_setCustomDriver"));
    result.extractIsoPreview = reinterpret_cast<decltype(extractIsoPreview)>(dlsym(handle, "_rpcsx_extractIsoPreview"));
    result.prepareRuntimePpu = reinterpret_cast<decltype(prepareRuntimePpu)>(dlsym(handle, "_rpcsx_prepareRuntimePpu"));
    result.cancelRuntimePpuPreparation = reinterpret_cast<decltype(cancelRuntimePpuPreparation)>(dlsym(handle, "_rpcsx_cancelRuntimePpuPreparation"));
    result.compileInstallPpuBatch = reinterpret_cast<decltype(compileInstallPpuBatch)>(dlsym(handle, "_rpcsx_compileInstallPpuBatch"));
    result.compileRuntimePpuBatch = reinterpret_cast<decltype(compileRuntimePpuBatch)>(dlsym(handle, "_rpcsx_compileRuntimePpuBatch"));
    result.cancelInstallPpuBatch = reinterpret_cast<decltype(cancelInstallPpuBatch)>(dlsym(handle, "_rpcsx_cancelInstallPpuBatch"));
    result.setCompileProgressListener = reinterpret_cast<decltype(setCompileProgressListener)>(dlsym(handle, "_rpcsx_setCompileProgressListener"));
    result.supportsCompileProgressEvents = reinterpret_cast<decltype(supportsCompileProgressEvents)>(dlsym(handle, "_rpcsx_supportsCompileProgressEvents"));
    result.beginFrontendMenu = reinterpret_cast<decltype(beginFrontendMenu)>(dlsym(handle, "_rpcsx_beginFrontendMenu"));
    result.endFrontendMenu = reinterpret_cast<decltype(endFrontendMenu)>(dlsym(handle, "_rpcsx_endFrontendMenu"));
    result.isFrontendMenuOpen = reinterpret_cast<decltype(isFrontendMenuOpen)>(dlsym(handle, "_rpcsx_isFrontendMenuOpen"));
    result.setFrontendEventListener = reinterpret_cast<decltype(setFrontendEventListener)>(dlsym(handle, "_rpcsx_setFrontendEventListener"));
    result.inGameMenuCapabilities = reinterpret_cast<decltype(inGameMenuCapabilities)>(dlsym(handle, "_rpcsx_inGameMenuCapabilities"));
    result.requestScreenshot = reinterpret_cast<decltype(requestScreenshot)>(dlsym(handle, "_rpcsx_requestScreenshot"));
    result.toggleRecording = reinterpret_cast<decltype(toggleRecording)>(dlsym(handle, "_rpcsx_toggleRecording"));
    result.restartGame = reinterpret_cast<decltype(restartGame)>(dlsym(handle, "_rpcsx_restartGame"));
    result.gracefulShutdown = reinterpret_cast<decltype(gracefulShutdown)>(dlsym(handle, "_rpcsx_gracefulShutdown"));
    result.getSaveStateInfo = reinterpret_cast<decltype(getSaveStateInfo)>(dlsym(handle, "_rpcsx_getSaveStateInfo"));
    result.saveState = reinterpret_cast<decltype(saveState)>(dlsym(handle, "_rpcsx_saveState"));
    result.loadSaveState = reinterpret_cast<decltype(loadSaveState)>(dlsym(handle, "_rpcsx_loadSaveState"));
    result.getCurrentTrophies = reinterpret_cast<decltype(getCurrentTrophies)>(dlsym(handle, "_rpcsx_getCurrentTrophies"));
    result.getTrophiesForTitle = reinterpret_cast<decltype(getTrophiesForTitle)>(dlsym(handle, "_rpcsx_getTrophiesForTitle"));
    result.getFriends = reinterpret_cast<decltype(getFriends)>(dlsym(handle, "_rpcsx_getFriends"));
    result.friendAction = reinterpret_cast<decltype(friendAction)>(dlsym(handle, "_rpcsx_friendAction"));
    result.beginInGameSettingsSession = reinterpret_cast<decltype(beginInGameSettingsSession)>(dlsym(handle, "_rpcsx_beginInGameSettingsSession"));
    result.settingsSetTransient = reinterpret_cast<decltype(settingsSetTransient)>(dlsym(handle, "_rpcsx_settingsSetTransient"));
    result.commitInGameSettingsSession = reinterpret_cast<decltype(commitInGameSettingsSession)>(dlsym(handle, "_rpcsx_commitInGameSettingsSession"));
    result.discardInGameSettingsSession = reinterpret_cast<decltype(discardInGameSettingsSession)>(dlsym(handle, "_rpcsx_discardInGameSettingsSession"));
    result.hasDirtyInGameSettings = reinterpret_cast<decltype(hasDirtyInGameSettings)>(dlsym(handle, "_rpcsx_hasDirtyInGameSettings"));
    result.endInGameSettingsSession = reinterpret_cast<decltype(endInGameSettingsSession)>(dlsym(handle, "_rpcsx_endInGameSettingsSession"));
    // clang-format on

    if (result.boot) {
      s3_iso_hook::install_direct_iso_hooks(reinterpret_cast<void*>(result.boot));
    } else if (result.initialize) {
      s3_iso_hook::install_direct_iso_hooks(reinterpret_cast<void*>(result.initialize));
    }

    return result;
  }
};

static RPCSXLibrary rpcsxLib;

static std::string unwrap(JNIEnv *env, jstring string) {
  auto resultBuffer = env->GetStringUTFChars(string, nullptr);
  std::string result(resultBuffer);
  env->ReleaseStringUTFChars(string, resultBuffer);
  return result;
}
static jstring wrap(JNIEnv *env, const std::string &string) {
  return env->NewStringUTF(string.c_str());
}
static jstring wrap(JNIEnv *env, const char *string) {
  return env->NewStringUTF(string);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_openLibrary(JNIEnv *env, jobject, jstring path) {
  if (auto library = RPCSXLibrary::Open(unwrap(env, path).c_str())) {
    rpcsxLib = std::move(*library);
    return true;
  }

  return false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getLibraryVersion(JNIEnv *env, jobject, jstring path) {
  if (auto library = RPCSXLibrary::Open(unwrap(env, path).c_str())) {
    if (auto getVersion = library->getVersion) {
      return wrap(env, getVersion());
    }
  }

  return {};
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_zenithblue_sambas3_RPCSX_overlayPadData(
    JNIEnv *, jobject, jint digital1, jint digital2, jint leftStickX,
    jint leftStickY, jint rightStickX, jint rightStickY) {
  if (!rpcsxLib.overlayPadData) return false;
  return rpcsxLib.overlayPadData(digital1, digital2, leftStickX, leftStickY,
                                 rightStickX, rightStickY);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_zenithblue_sambas3_RPCSX_initialize(
    JNIEnv *env, jobject, jstring rootDir, jstring user) {
  if (!rpcsxLib.initialize) return false;
  return rpcsxLib.initialize(unwrap(env, rootDir), unwrap(env, user));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_processCompilationQueue(JNIEnv *env, jobject) {
  return rpcsxLib.processCompilationQueue(env);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_startMainThreadProcessor(JNIEnv *env, jobject) {
  return rpcsxLib.startMainThreadProcessor(env);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_isMainThreadProcessorReady(JNIEnv *, jobject) {
  if (!rpcsxLib.isMainThreadProcessorReady) return JNI_FALSE;
  return rpcsxLib.isMainThreadProcessorReady() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_zenithblue_sambas3_RPCSX_collectGameInfo(
    JNIEnv *env, jobject, jstring jrootDir, jlong progressId) {
  return rpcsxLib.collectGameInfo(env, unwrap(env, jrootDir), progressId);
}

extern "C" JNIEXPORT void JNICALL Java_com_zenithblue_sambas3_RPCSX_shutdown(JNIEnv *env,
                                                                jobject) {
  return rpcsxLib.shutdown();
}

extern "C" JNIEXPORT jint JNICALL Java_com_zenithblue_sambas3_RPCSX_boot(JNIEnv *env,
                                                            jobject,
                                                            jstring jpath) {
  if (!rpcsxLib.boot) return 1; // GenericError
  s3_iso_hook::install_direct_iso_hooks(reinterpret_cast<void*>(rpcsxLib.boot));
  return rpcsxLib.boot(unwrap(env, jpath));
}

extern "C" JNIEXPORT jint JNICALL Java_com_zenithblue_sambas3_RPCSX_bootSavestate(
    JNIEnv *env, jobject, jstring jsavestatePath, jstring joriginalGamePath) {
  if (!rpcsxLib.bootSavestate) return 1; // GenericError
  return rpcsxLib.bootSavestate(unwrap(env, jsavestatePath),
                                unwrap(env, joriginalGamePath));
}

extern "C" JNIEXPORT void JNICALL Java_com_zenithblue_sambas3_RPCSX_clearSavestateProgress(
    JNIEnv *, jobject) {
  if (rpcsxLib.clearSavestateProgress) rpcsxLib.clearSavestateProgress();
}

extern "C" JNIEXPORT jint JNICALL Java_com_zenithblue_sambas3_RPCSX_getState(JNIEnv *env,
                                                                jobject) {
  if (!rpcsxLib.getState) return 0; // Stopped — library not yet dlopened (cold RPCSXActivity after force-stop)
  return rpcsxLib.getState();
}

extern "C" JNIEXPORT void JNICALL Java_com_zenithblue_sambas3_RPCSX_kill(JNIEnv *env,
                                                            jobject) {
  if (!rpcsxLib.kill) return;
  return rpcsxLib.kill();
}

extern "C" JNIEXPORT void JNICALL Java_com_zenithblue_sambas3_RPCSX_resume(JNIEnv *env,
                                                              jobject) {
  if (!rpcsxLib.resume) return;
  return rpcsxLib.resume();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_beginFrontendMenu(JNIEnv*, jobject) {
  return rpcsxLib.beginFrontendMenu ? rpcsxLib.beginFrontendMenu() : false;
}
extern "C" JNIEXPORT void JNICALL
Java_com_zenithblue_sambas3_RPCSX_endFrontendMenu(JNIEnv*, jobject, jboolean resumeIfOwned) {
  if (!rpcsxLib.endFrontendMenu) return;
  rpcsxLib.endFrontendMenu(resumeIfOwned);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_isFrontendMenuOpen(JNIEnv*, jobject) {
  return rpcsxLib.isFrontendMenuOpen ? rpcsxLib.isFrontendMenuOpen() : false;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_setFrontendEventListener(JNIEnv* env, jobject thiz, jobject callback) {
  if (!rpcsxLib.setFrontendEventListener) {
    __android_log_print(ANDROID_LOG_WARN, "RPCSX-UI", "setFrontendEventListener not available in this core (old .so)");
    return false;
  }
  return rpcsxLib.setFrontendEventListener(env, callback);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_inGameMenuCapabilities(JNIEnv* env, jobject) {
  if (!rpcsxLib.inGameMenuCapabilities) return wrap(env, std::string(R"({"apiVersion":1,"frontendOwnsHomeMenu":false})"));
  return wrap(env, rpcsxLib.inGameMenuCapabilities());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_requestScreenshot(JNIEnv*, jobject) {
  return rpcsxLib.requestScreenshot ? rpcsxLib.requestScreenshot() : false;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_toggleRecording(JNIEnv*, jobject) {
  return rpcsxLib.toggleRecording ? rpcsxLib.toggleRecording() : false;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_restartGame(JNIEnv*, jobject) {
  return rpcsxLib.restartGame ? rpcsxLib.restartGame() : false;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_gracefulShutdown(JNIEnv*, jobject) {
  return rpcsxLib.gracefulShutdown ? rpcsxLib.gracefulShutdown() : false;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getSaveStateInfo(JNIEnv* env, jobject) {
  if (!rpcsxLib.getSaveStateInfo) return wrap(env, std::string(R"({"supported":false})"));
  return wrap(env, rpcsxLib.getSaveStateInfo());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_saveState(JNIEnv*, jobject, jint slot) {
  return rpcsxLib.saveState ? rpcsxLib.saveState(slot) : false;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_loadSaveState(JNIEnv*, jobject, jint slot) {
  return rpcsxLib.loadSaveState ? rpcsxLib.loadSaveState(slot) : false;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getCurrentTrophies(JNIEnv* env, jobject) {
  if (!rpcsxLib.getCurrentTrophies) return wrap(env, std::string(R"({"available":false})"));
  return wrap(env, rpcsxLib.getCurrentTrophies());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getTrophiesForTitle(JNIEnv* env, jobject, jstring jtitle_id) {
  if (!rpcsxLib.getTrophiesForTitle) return wrap(env, std::string(R"({"available":false,"status":"unsupported"})"));
  const std::string title_id = unwrap(env, jtitle_id);
  return wrap(env, rpcsxLib.getTrophiesForTitle(title_id.c_str()));
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getFriends(JNIEnv* env, jobject) {
  if (!rpcsxLib.getFriends) return wrap(env, std::string(R"({"available":false})"));
  return wrap(env, rpcsxLib.getFriends());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_friendAction(JNIEnv* env, jobject, jstring jaction, jstring juser) {
  if (!rpcsxLib.friendAction) return false;
  return rpcsxLib.friendAction(unwrap(env, jaction), unwrap(env, juser));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_beginInGameSettingsSession(JNIEnv*, jobject) {
  return rpcsxLib.beginInGameSettingsSession ? rpcsxLib.beginInGameSettingsSession() : false;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_settingsSetTransient(JNIEnv* env, jobject, jstring jpath, jstring jvalue) {
  if (!rpcsxLib.settingsSetTransient) return false;
  return rpcsxLib.settingsSetTransient(unwrap(env, jpath), unwrap(env, jvalue));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_commitInGameSettingsSession(JNIEnv*, jobject) {
  return rpcsxLib.commitInGameSettingsSession ? rpcsxLib.commitInGameSettingsSession() : false;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_discardInGameSettingsSession(JNIEnv*, jobject) {
  return rpcsxLib.discardInGameSettingsSession ? rpcsxLib.discardInGameSettingsSession() : false;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_hasDirtyInGameSettings(JNIEnv*, jobject) {
  return rpcsxLib.hasDirtyInGameSettings ? rpcsxLib.hasDirtyInGameSettings() : false;
}
extern "C" JNIEXPORT void JNICALL
Java_com_zenithblue_sambas3_RPCSX_endInGameSettingsSession(JNIEnv*, jobject) {
  if (!rpcsxLib.endInGameSettingsSession) return;
  rpcsxLib.endInGameSettingsSession();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getTitleId(JNIEnv *env, jobject) {
  if (!rpcsxLib.getTitleId) return wrap(env, std::string{});
  return wrap(env, rpcsxLib.getTitleId());
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_zenithblue_sambas3_RPCSX_surfaceEvent(
    JNIEnv *env, jobject, jobject surface, jint event) {
  if (surface != nullptr) {
    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
    if (win != nullptr) {
      if (event == 0 || event == 1) {
        s3_perf::try_hook_native_window(win);
      } else if (event == 2) {
        s3_perf::unhook_native_window(win);
      }
      ANativeWindow_release(win);
    }
  }
  if (!rpcsxLib.surfaceEvent) return false;
  return rpcsxLib.surfaceEvent(env, surface, event);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_zenithblue_sambas3_RPCSX_surfaceEventV2(
    JNIEnv *env, jobject, jobject surface, jint event, jlong generation) {
  if (surface != nullptr) {
    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
    if (win != nullptr) {
      if (event == 0 || event == 1) {
        s3_perf::try_hook_native_window(win);
      } else if (event == 2) {
        s3_perf::unhook_native_window(win);
      }
      ANativeWindow_release(win);
    }
  }
  if (rpcsxLib.surfaceEventV2) {
    return rpcsxLib.surfaceEventV2(env, surface, event, generation);
  }
  if (!rpcsxLib.surfaceEvent) return false;
  return rpcsxLib.surfaceEvent(env, surface, event);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_zenithblue_sambas3_RPCSX_usbDeviceEvent(
    JNIEnv *env, jobject, jint fd, jint vendorId, jint productId, jint event) {
  return rpcsxLib.usbDeviceEvent(fd, vendorId, productId, event);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_zenithblue_sambas3_RPCSX_installFw(
    JNIEnv *env, jobject, jint fd, jlong progressId) {
  return rpcsxLib.installFw(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_isInstallableFile(JNIEnv *env, jobject, jint fd) {
  return rpcsxLib.isInstallableFile(fd);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getDirInstallPath(JNIEnv *env, jobject, jint fd) {
  return rpcsxLib.getDirInstallPath(env, fd);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_install(JNIEnv *env, jobject, jint fd, jlong progressId) {
  return rpcsxLib.install(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_zenithblue_sambas3_RPCSX_installKey(
    JNIEnv *env, jobject, jint fd, jlong progressId, jstring gamePath) {
  return rpcsxLib.installKey(env, fd, progressId, unwrap(env, gamePath));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_systemInfo(JNIEnv *env, jobject) {
  return wrap(env, rpcsxLib.systemInfo());
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenithblue_sambas3_RPCSX_loginUser(JNIEnv *env, jobject, jstring user_id) {
  return rpcsxLib.loginUser(unwrap(env, user_id));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_zenithblue_sambas3_RPCSX_getUser(JNIEnv *env,
                                                                  jobject) {
  return wrap(env, rpcsxLib.getUser());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_settingsGet(JNIEnv *env, jobject, jstring jpath) {
  return wrap(env, rpcsxLib.settingsGet(unwrap(env, jpath)));
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_zenithblue_sambas3_RPCSX_settingsSet(
    JNIEnv *env, jobject, jstring jpath, jstring jvalue) {
  if (!rpcsxLib.settingsSet) return false;
  return rpcsxLib.settingsSet(unwrap(env, jpath), unwrap(env, jvalue));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_settingsGetGlobal(JNIEnv *env, jobject, jstring jpath) {
  // Settings-compatibility fallback (not PPU): packaged cores export only
  // _rpcsx_settingsGet/_rpcsx_settingsSet today, so Global reads fall back to
  // the base settings node. Keep until the core gains real Global symbols.
  if (!rpcsxLib.settingsGetGlobal) {
    if (rpcsxLib.settingsGet) {
      return wrap(env, rpcsxLib.settingsGet(unwrap(env, jpath)));
    }
    return wrap(env, std::string("{}"));
  }
  return wrap(env, rpcsxLib.settingsGetGlobal(unwrap(env, jpath)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_settingsSetGlobal(JNIEnv *env, jobject, jstring jpath, jstring jvalue) {
  // Settings-compatibility fallback (not PPU): see settingsGetGlobal above.
  if (!rpcsxLib.settingsSetGlobal) {
    if (rpcsxLib.settingsSet) {
      return rpcsxLib.settingsSet(unwrap(env, jpath), unwrap(env, jvalue));
    }
    return false;
  }
  return rpcsxLib.settingsSetGlobal(unwrap(env, jpath), unwrap(env, jvalue));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_gameSettingsOverridesGet(JNIEnv *env, jobject, jstring jtitleId) {
  if (!rpcsxLib.gameSettingsOverridesGet) return wrap(env, std::string("{}"));
  return wrap(env, rpcsxLib.gameSettingsOverridesGet(unwrap(env, jtitleId)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_gameSettingsOverrideSet(
    JNIEnv *env, jobject, jstring jtitleId, jstring jpath, jstring jvalue) {
  if (!rpcsxLib.gameSettingsOverrideSet) return false;
  return rpcsxLib.gameSettingsOverrideSet(
      unwrap(env, jtitleId), unwrap(env, jpath), unwrap(env, jvalue));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_gameSettingsOverrideClear(
    JNIEnv *env, jobject, jstring jtitleId, jstring jpath) {
  if (!rpcsxLib.gameSettingsOverrideClear) return false;
  return rpcsxLib.gameSettingsOverrideClear(unwrap(env, jtitleId), unwrap(env, jpath));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_gameSettingsOverridesClear(
    JNIEnv *env, jobject, jstring jtitleId) {
  if (!rpcsxLib.gameSettingsOverridesClear) return false;
  return rpcsxLib.gameSettingsOverridesClear(unwrap(env, jtitleId));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_settingsGetEffective(
    JNIEnv *env, jobject, jstring jtitleId, jstring jpath) {
  // Settings-compatibility fallback (not PPU): without a core Effective
  // symbol, resolve through Global/base nodes. Per-title isolation is owned
  // app-side by the scoped settings lease (GameSettingsOverrides).
  if (!rpcsxLib.settingsGetEffective) {
    if (rpcsxLib.settingsGetGlobal) {
      return wrap(env, rpcsxLib.settingsGetGlobal(unwrap(env, jpath)));
    }
    if (rpcsxLib.settingsGet) {
      return wrap(env, rpcsxLib.settingsGet(unwrap(env, jpath)));
    }
    return wrap(env, std::string("{}"));
  }
  return wrap(env, rpcsxLib.settingsGetEffective(unwrap(env, jtitleId), unwrap(env, jpath)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_supportsCustomDriverLoading(JNIEnv *env,
                                                 jobject instance) {
  return access("/dev/kgsl-3d0", F_OK) == 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getVersion(JNIEnv *env, jobject) {
  return wrap(env, rpcsxLib.getVersion());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_setCustomDriver(JNIEnv *env, jobject, jstring jpath,
                                     jstring jlibraryName, jstring jhookDir) {
#ifdef __aarch64__
  if (rpcsxLib.setCustomDriver == nullptr) {
    return false;
  }

  auto path = unwrap(env, jpath);
  void *loader = nullptr;

  if (!path.empty()) {
      auto hookDir = unwrap(env, jhookDir);
      auto libraryName = unwrap(env, jlibraryName);
      __android_log_print(ANDROID_LOG_INFO, "RPCSX-UI", "Loading custom driver %s",
                          path.c_str());

      ::dlerror();
      loader = adrenotools_open_libvulkan(
              RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, (hookDir + "/").c_str(),
              (path + "/").c_str(), libraryName.c_str(), nullptr, nullptr);

      if (loader == nullptr) {
          __android_log_print(ANDROID_LOG_INFO, "RPCSX-UI",
                              "Failed to load custom driver at '%s': %s",
                              path.c_str(), ::dlerror());
          return false;
      }
  }

  auto prevLoader = rpcsxLib.setCustomDriver(loader);
  if (prevLoader != nullptr) {
    ::dlclose(prevLoader);
  }

  return true;
#else
  return false;
#endif // __aarch64__
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_patchEngineVersion(JNIEnv *env, jobject) {
  if (!rpcsxLib.patchEngineVersion) return wrap(env, std::string{});
  return wrap(env, rpcsxLib.patchEngineVersion());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_patchesList(JNIEnv *env, jobject) {
  if (!rpcsxLib.patchesList) return wrap(env, std::string{});
  return wrap(env, rpcsxLib.patchesList());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_patchSetEnabled(JNIEnv *env, jobject,
                                                   jstring jhash,
                                                   jstring jdescription,
                                                   jboolean jenabled) {
  if (!rpcsxLib.patchSetEnabled) return false;
  return rpcsxLib.patchSetEnabled(unwrap(env, jhash),
                                   unwrap(env, jdescription), jenabled);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_setCompileProgressListener(JNIEnv *env, jobject thiz, jobject callback) {
  if (!rpcsxLib.setCompileProgressListener) {
    __android_log_print(ANDROID_LOG_WARN, "RPCSX-UI", "setCompileProgressListener not available in this core (old .so)");
    return false;
  }
  return rpcsxLib.setCompileProgressListener(env, callback);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_supportsCompileProgressEvents(JNIEnv *env, jobject thiz) {
  return rpcsxLib.setCompileProgressListener != nullptr && rpcsxLib.supportsCompileProgressEvents != nullptr
      ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getPpuManifestKey(JNIEnv *env, jobject, jstring jtitleId) {
  std::string title = jtitleId ? unwrap(env, jtitleId) : std::string{};
  if (rpcsxLib.getPpuManifestKeyForTitle) {
    const char* key = rpcsxLib.getPpuManifestKeyForTitle(title.c_str());
    if (key && key[0] != '\0') {
      __android_log_print(ANDROID_LOG_INFO, "RPCSX-UI", "getPpuManifestKey per-title title='%s' hit", title.c_str());
      return wrap(env, std::string(key));
    }
  }
  if (!rpcsxLib.getPpuManifestKey) return wrap(env, std::string{});
  // Fallback: global export (old core); log and return global key.
  if (!title.empty()) {
    __android_log_print(ANDROID_LOG_WARN, "RPCSX-UI", "getPpuManifestKey per-title not available, falling back to global for title='%s'", title.c_str());
  }
  const char* key = rpcsxLib.getPpuManifestKey();
  return wrap(env, key ? std::string(key) : std::string{});
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getCoreBuildId(JNIEnv *env, jobject) {
  if (!rpcsxLib.getSambaBuildId) return wrap(env, std::string{});
  const char* id = rpcsxLib.getSambaBuildId();
  return wrap(env, id ? std::string(id) : std::string{});
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_hasBootSavestateExport(JNIEnv *, jobject) {
  return rpcsxLib.bootSavestate ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_hasLoadSaveStateExport(JNIEnv *, jobject) {
  return rpcsxLib.loadSaveState ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_hasSurfaceEventV2Export(JNIEnv *, jobject) {
  return rpcsxLib.surfaceEventV2 ? JNI_TRUE : JNI_FALSE;
}

extern "C" void _rpcsx_android_perf_frame_presented() {
  using namespace std::chrono;
  const uint64_t now_us =
      duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
  s3_perf::on_frame_presented(now_us, 3);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_hasPerfMetricsExport(JNIEnv *, jobject) {
  // Always true: native bridge provides s3_perf telemetry fallback when core symbols are absent
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_hasTrophyExports(JNIEnv *, jobject) {
  return rpcsxLib.getCurrentTrophies && rpcsxLib.getTrophiesForTitle ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getPerfMetricsJson(JNIEnv *env, jobject) {
  if (rpcsxLib.getPerfMetricsJson) {
    std::string str = rpcsxLib.getPerfMetricsJson();
    if (!str.empty()) {
      return wrap(env, str);
    }
  }
  return wrap(env, s3_perf::build_fallback_json());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getFallbackPerfJson(JNIEnv *env, jobject) {
  // Surface-measured frames (ANativeWindow queueBuffer hook). The core export
  // reports emu_flip counters; when it reports presented=0 while frames are
  // visibly presenting, the UI merges this fallback's fresh fps/samples.
  return wrap(env, s3_perf::build_fallback_json());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_setPerfMetricsEnabled(JNIEnv *, jobject, jboolean enabled, jint intervalMs) {
  s3_perf::set_enabled(enabled == JNI_TRUE, intervalMs);
  if (rpcsxLib.setPerfMetricsEnabled) {
    return rpcsxLib.setPerfMetricsEnabled(enabled == JNI_TRUE, intervalMs) ? JNI_TRUE : JNI_FALSE;
  }
  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zenithblue_sambas3_RPCSX_extractIsoPreview(JNIEnv *env, jobject, jint fd, jstring jDest) {
  if (!rpcsxLib.extractIsoPreview) {
    __android_log_print(ANDROID_LOG_WARN, "RPCSX-UI", "extractIsoPreview not available in core (old .so)");
    return -999;
  }
  std::string dest = unwrap(env, jDest);
  return rpcsxLib.extractIsoPreview(fd, dest.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zenithblue_sambas3_RPCSX_prepareRuntimePpu(JNIEnv *env, jobject, jstring jPath, jlong sessionId) {
  if (!rpcsxLib.prepareRuntimePpu) {
    __android_log_print(ANDROID_LOG_WARN, "RPCSX-UI", "prepareRuntimePpu not available in core (old .so)");
    return -999;
  }
  std::string path = unwrap(env, jPath);
  return rpcsxLib.prepareRuntimePpu(path.c_str(), static_cast<unsigned long long>(sessionId));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenithblue_sambas3_RPCSX_cancelRuntimePpuPreparation(JNIEnv *env, jobject, jlong sessionId) {
  if (!rpcsxLib.cancelRuntimePpuPreparation) {
    __android_log_print(ANDROID_LOG_WARN, "RPCSX-UI", "cancelRuntimePpuPreparation not available");
    return false;
  }
  return rpcsxLib.cancelRuntimePpuPreparation(static_cast<unsigned long long>(sessionId));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_compileInstallPpuBatch(
    JNIEnv *env, jobject, jstring jTitleId, jstring jGamePath, jlong logicalJobId, jint maxNewObjects) {
  if (!rpcsxLib.compileInstallPpuBatch) {
    __android_log_print(ANDROID_LOG_WARN, "RPCSX-UI", "compileInstallPpuBatch not available in core (old .so)");
    return wrap(env, std::string("{\"status\":\"failed\",\"message\":\"symbol_not_found\"}"));
  }
  std::string title = unwrap(env, jTitleId);
  std::string path = unwrap(env, jGamePath);
  const char* res = rpcsxLib.compileInstallPpuBatch(
      title.c_str(),
      path.c_str(),
      static_cast<unsigned long long>(logicalJobId),
      static_cast<unsigned int>(maxNewObjects));
  return wrap(env, res ? std::string(res) : std::string("{\"status\":\"failed\",\"message\":\"null_result\"}"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_compileRuntimePpuBatch(
    JNIEnv *env, jobject, jstring jTitleId, jstring jGamePath, jlong logicalJobId, jint maxNewObjects) {
  if (!rpcsxLib.compileRuntimePpuBatch) {
    __android_log_print(ANDROID_LOG_WARN, "RPCSX-UI", "compileRuntimePpuBatch not available in core (old .so)");
    return wrap(env, std::string("{\"status\":\"failed\",\"message\":\"symbol_not_found\"}"));
  }
  std::string title = unwrap(env, jTitleId);
  std::string path = unwrap(env, jGamePath);
  const char* res = rpcsxLib.compileRuntimePpuBatch(
      title.c_str(),
      path.c_str(),
      static_cast<unsigned long long>(logicalJobId),
      static_cast<unsigned int>(maxNewObjects));
  return wrap(env, res ? std::string(res) : std::string("{\"status\":\"failed\",\"message\":\"null_result\"}"));
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenithblue_sambas3_RPCSX_cancelInstallPpuBatch(JNIEnv *env, jobject) {
  if (!rpcsxLib.cancelInstallPpuBatch) {
    __android_log_print(ANDROID_LOG_WARN, "RPCSX-UI", "cancelInstallPpuBatch not available");
    return;
  }
  rpcsxLib.cancelInstallPpuBatch();
}
