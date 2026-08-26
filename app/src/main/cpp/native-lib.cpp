#include <algorithm>
#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <optional>
#include <string>
#include <string_view>
#include <sys/resource.h>
#include <unistd.h>
#include <utility>

#if defined(__aarch64__)
#include <adrenotools/driver.h>
#include <adrenotools/priv.h>
#endif

struct RPCSXApi {
  bool (*overlayPadData)(int digital1, int digital2, int leftStickX,
                         int leftStickY, int rightStickX, int rightStickY);
  bool (*initialize)(std::string_view rootDir, std::string_view user);
  bool (*processCompilationQueue)(JNIEnv *env);
  bool (*startMainThreadProcessor)(JNIEnv *env);
  bool (*setCompileProgressListener)(JNIEnv *env, jobject callback);
  bool (*supportsCompileProgressEvents)(JNIEnv *env, jobject thiz);
  bool (*collectGameInfo)(JNIEnv *env, std::string_view rootDir,
                          long progressId);
  void (*shutdown)();
  int (*boot)(std::string_view path_);
  int (*getState)();
  void (*kill)();
  void (*resume)();
  void (*openHomeMenu)();
  std::string (*getTitleId)();
  bool (*surfaceEvent)(JNIEnv *env, jobject surface, jint event);
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
  std::string (*getVersion)();
  std::string (*patchEngineVersion)();
  std::string (*patchesList)();
  bool (*patchSetEnabled)(std::string_view hash, std::string_view description, bool enabled);
    const char* (*getPpuManifestKey)();
    const char* (*getPpuManifestKeyForTitle)(const char* titleId);
    const char* (*getSambaBuildId)();
    void *(*setCustomDriver)(void *driverHandle);
    int (*extractIsoPreview)(int fd, const char* destPath);
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
    result.collectGameInfo = reinterpret_cast<decltype(collectGameInfo)>(dlsym(handle, "_rpcsx_collectGameInfo"));
    result.shutdown = reinterpret_cast<decltype(shutdown)>(dlsym(handle, "_rpcsx_shutdown"));
    result.boot = reinterpret_cast<decltype(boot)>(dlsym(handle, "_rpcsx_boot"));
    result.getState = reinterpret_cast<decltype(getState)>(dlsym(handle, "_rpcsx_getState"));
    result.kill = reinterpret_cast<decltype(kill)>(dlsym(handle, "_rpcsx_kill"));
    result.resume = reinterpret_cast<decltype(resume)>(dlsym(handle, "_rpcsx_resume"));
    result.openHomeMenu = reinterpret_cast<decltype(openHomeMenu)>(dlsym(handle, "_rpcsx_openHomeMenu"));
    result.getTitleId = reinterpret_cast<decltype(getTitleId)>(dlsym(handle, "_rpcsx_getTitleId"));
    result.surfaceEvent = reinterpret_cast<decltype(surfaceEvent)>(dlsym(handle, "_rpcsx_surfaceEvent"));
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
    result.getVersion = reinterpret_cast<decltype(getVersion)>(dlsym(handle, "_rpcsx_getVersion"));
    result.patchEngineVersion = reinterpret_cast<decltype(patchEngineVersion)>(dlsym(handle, "_rpcsx_patchEngineVersion"));
    result.patchesList = reinterpret_cast<decltype(patchesList)>(dlsym(handle, "_rpcsx_patchesList"));
    result.patchSetEnabled = reinterpret_cast<decltype(patchSetEnabled)>(dlsym(handle, "_rpcsx_patchSetEnabled"));
    result.getPpuManifestKey = reinterpret_cast<decltype(getPpuManifestKey)>(dlsym(handle, "_rpcsx_getPpuManifestKey"));
    result.getPpuManifestKeyForTitle = reinterpret_cast<decltype(getPpuManifestKeyForTitle)>(dlsym(handle, "_rpcsx_getPpuManifestKeyForTitle"));
    result.getSambaBuildId = reinterpret_cast<decltype(getSambaBuildId)>(dlsym(handle, "_rpcsx_sambaBuildId"));
    result.setCustomDriver = reinterpret_cast<decltype(setCustomDriver)>(dlsym(handle, "_rpcsx_setCustomDriver"));
    result.extractIsoPreview = reinterpret_cast<decltype(extractIsoPreview)>(dlsym(handle, "_rpcsx_extractIsoPreview"));
    result.setCompileProgressListener = reinterpret_cast<decltype(setCompileProgressListener)>(dlsym(handle, "_rpcsx_setCompileProgressListener"));
    result.supportsCompileProgressEvents = reinterpret_cast<decltype(supportsCompileProgressEvents)>(dlsym(handle, "_rpcsx_supportsCompileProgressEvents"));
    // clang-format on

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
  return rpcsxLib.boot(unwrap(env, jpath));
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

extern "C" JNIEXPORT void JNICALL Java_com_zenithblue_sambas3_RPCSX_openHomeMenu(JNIEnv *env,
                                                                    jobject) {
  if (!rpcsxLib.openHomeMenu) {
    __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI",
                        "openHomeMenu unavailable");
    return;
  }
  return rpcsxLib.openHomeMenu();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zenithblue_sambas3_RPCSX_getTitleId(JNIEnv *env, jobject) {
  if (!rpcsxLib.getTitleId) return wrap(env, std::string{});
  return wrap(env, rpcsxLib.getTitleId());
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_zenithblue_sambas3_RPCSX_surfaceEvent(
    JNIEnv *env, jobject, jobject surface, jint event) {
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
  return rpcsxLib.settingsSet(unwrap(env, jpath), unwrap(env, jvalue));
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

extern "C" JNIEXPORT jint JNICALL
Java_com_zenithblue_sambas3_RPCSX_extractIsoPreview(JNIEnv *env, jobject, jint fd, jstring jDest) {
  if (!rpcsxLib.extractIsoPreview) {
    __android_log_print(ANDROID_LOG_WARN, "RPCSX-UI", "extractIsoPreview not available in core (old .so)");
    return -999;
  }
  std::string dest = unwrap(env, jDest);
  return rpcsxLib.extractIsoPreview(fd, dest.c_str());
}
