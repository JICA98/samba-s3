#!/usr/bin/env python3
"""RPCSX core provenance: identity stamp, per-ABI manifest, packaging verify.

Stdlib only. Invoked by build_rpcsx.sh and scripts/verify-apk-core.sh.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Iterable

SCHEMA_VERSION = 1
REQUIRED_ABIS = ("arm64-v8a",)
LIBRARY_NAME = "librpcsx-android.so"
MANIFEST_NAME = "librpcsx-android.manifest.json"
PLACEHOLDER_IDENTITY_REL = "android/src/samba-build-id.cpp"
SPU_SHUFB_TBL1_CMAKE_OPTION = "SAMBA_EXPERIMENTAL_SPU_SHUFB_TBL1"

INTEGRATION_RELPATHS = (
    "build_rpcsx.sh",
    "app/src/main/cpp/rpcsx/android/CMakeLists.txt",
    "patches/rpcsx-submodule-changes.patch",
    "scripts/verify-apk-core.sh",
    "scripts/lib/core_provenance.py",
)

APPLY_FAIL_HINT = """\
Error: patches/rpcsx-submodule-changes.patch does not apply cleanly (neither forward nor reverse).
Engine Android integration edits are expected to live in the rpcsx submodule (samba-android).
The parent patch file must be comment-only/empty, or a real git diff that applies to this revision.
If you added new engine edits, commit them in the submodule or regenerate a real patch:
  git -C app/src/main/cpp/rpcsx diff > patches/rpcsx-submodule-changes.patch
If this patch is leftover from a previous revision, replace it with a comment-only file.
"""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _run(cmd: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(cmd, cwd=cwd, text=True, capture_output=True)
    except FileNotFoundError:
        return subprocess.CompletedProcess(cmd, 127, "", "not found")


def git_output(repo: Path, *args: str) -> str:
    r = _run(["git", "-C", str(repo), *args])
    if r.returncode != 0:
        return ""
    return r.stdout.strip()


def normalize_patch_text(text: str) -> str:
    """Normalize patch content by ignoring comments (#), blank lines, and git index headers."""
    normalized_lines: list[str] = []
    for line in text.splitlines():
        line = line.rstrip()
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        # Ignore git index hash lines, e.g. "index 1111111..2222222 100644"
        if stripped.startswith("index "):
            continue
        # Normalize file headers with timestamps: "--- a/file\t2026-..." -> "--- a/file"
        if line.startswith("--- ") or line.startswith("+++ "):
            line = re.sub(r"[\t\s]+[0-9]{4}-[0-9]{2}-[0-9]{2}.*$", "", line)
        normalized_lines.append(line)
    return "\n".join(normalized_lines)


def patch_is_noop(path: Path) -> bool:
    """Empty or comment-only (# / blank lines) patches are a documented skip."""
    if not path.is_file():
        return True
    raw = path.read_bytes()
    if not raw.strip():
        return True
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return len(normalize_patch_text(text)) == 0


def patch_digest(path: Path) -> str:
    if patch_is_noop(path):
        return "none"
    try:
        text = path.read_text(encoding="utf-8")
        normalized = normalize_patch_text(text)
        if not normalized:
            return "none"
        return sha256_bytes(normalized.encode("utf-8"))
    except Exception:
        return sha256_file(path)


def apply_patch(rpcsx_dir: Path, patch_file: Path) -> str:
    """Apply or skip the parent patch. Returns a status token. Raises SystemExit on failure."""
    if not patch_file.is_file() or patch_is_noop(patch_file):
        print("RPCSX submodule patch is empty (edits pinned in submodule commit) — skipping")
        return "empty-skip"

    def check(reverse: bool) -> bool:
        cmd = ["git", "-C", str(rpcsx_dir), "apply", "--check"]
        if reverse:
            cmd.append("--reverse")
        cmd.append(str(patch_file))
        return subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0

    if check(reverse=True):
        print("RPCSX submodule patch already applied")
        return "already-applied"
    if check(reverse=False):
        applied = subprocess.run(
            ["git", "-C", str(rpcsx_dir), "apply", str(patch_file)],
            capture_output=True,
            text=True,
        )
        if applied.returncode != 0:
            print(APPLY_FAIL_HINT, file=sys.stderr)
            print(applied.stderr, file=sys.stderr)
            raise SystemExit(1)
        print(f"Applied {patch_file}")
        return "applied"
    print(APPLY_FAIL_HINT, file=sys.stderr)
    raise SystemExit(1)


def integration_digest(root: Path) -> str:
    h = hashlib.sha256()
    for rel in INTEGRATION_RELPATHS:
        path = root / rel
        h.update(rel.encode("utf-8"))
        h.update(b"\0")
        if path.is_file():
            if rel == "patches/rpcsx-submodule-changes.patch":
                h.update(patch_digest(path).encode("ascii"))
            else:
                h.update(sha256_file(path).encode("ascii"))
        else:
            h.update(b"missing")
        h.update(b"\n")
    return h.hexdigest()


def nested_revisions(rpcsx_dir: Path) -> dict[str, str]:
    """Identify every nested submodule's commit SHA recursively.
    
    Fails closed if submodule inspection fails.
    """
    gitmodules = rpcsx_dir / ".gitmodules"
    if not gitmodules.is_file():
        return {}
    r = _run(["git", "-C", str(rpcsx_dir), "submodule", "status", "--recursive"])
    if r.returncode != 0:
        raise SystemExit(f"Error: git submodule status --recursive failed in {rpcsx_dir}: {r.stderr.strip()}")
    revisions: dict[str, str] = {}
    for line in r.stdout.splitlines():
        if not line:
            continue
        status_char = line[0]
        rest = line[1:].strip().split()
        if len(rest) < 2:
            continue
        sha = rest[0]
        path = rest[1]
        if status_char in ("-", "U"):
            raise SystemExit(f"Error: uninitialized or conflicting submodule '{path}' in {rpcsx_dir} ({status_char})")
        revisions[path] = sha
    if "3rdparty/llvm/llvm" in revisions:
        revisions["llvm"] = revisions["3rdparty/llvm/llvm"]
    return dict(sorted(revisions.items()))


def source_digest(rpcsx_dir: Path) -> str | None:
    """Content digest of dirty backend source. None when the worktree is clean.

    Uses porcelain + diff + untracked file hashes recursively across rpcsx and
    all nested submodules. Excludes the tracked identity placeholder.
    Fails closed if git inspection fails.
    """
    if not (rpcsx_dir / ".git").exists() and not rpcsx_dir.is_dir():
        return None
    r_porc = _run(["git", "-C", str(rpcsx_dir), "status", "--porcelain", "--ignore-submodules=none"])
    if r_porc.returncode != 0:
        raise SystemExit(f"Error: git status failed in {rpcsx_dir}: {r_porc.stderr.strip()}")
    parent_porcelain = r_porc.stdout

    # Discover nested submodules recursively
    sub_revs = nested_revisions(rpcsx_dir)
    # Filter to real directory paths (exclude alias keys like "llvm")
    submodule_paths = [p for p in sub_revs.keys() if p != "llvm" and (rpcsx_dir / p).is_dir()]

    # Collect dirty info from submodules
    sub_dirty: list[tuple[str, str, str, list[tuple[str, str]]]] = []
    for sub_rel in sorted(submodule_paths):
        sub_dir = rpcsx_dir / sub_rel
        sp = _run(["git", "-C", str(sub_dir), "status", "--porcelain", "--ignore-submodules=none"])
        if sp.returncode != 0:
            raise SystemExit(f"Error: git status failed in submodule {sub_dir}: {sp.stderr.strip()}")
        if not sp.stdout.strip():
            continue
        sd = _run(["git", "-C", str(sub_dir), "diff", "HEAD"])
        if sd.returncode != 0:
            raise SystemExit(f"Error: git diff failed in submodule {sub_dir}: {sd.stderr.strip()}")
        su = _run(["git", "-C", str(sub_dir), "ls-files", "-o", "--exclude-standard"])
        if su.returncode != 0:
            raise SystemExit(f"Error: git ls-files failed in submodule {sub_dir}: {su.stderr.strip()}")
        untracked_hashes: list[tuple[str, str]] = []
        for u_rel in su.stdout.splitlines():
            if not u_rel:
                continue
            u_path = sub_dir / u_rel
            if u_path.is_file():
                untracked_hashes.append((u_rel, sha256_file(u_path)))
        sub_dirty.append((sub_rel, sp.stdout, sd.stdout, untracked_hashes))

    if not parent_porcelain.strip() and not sub_dirty:
        return None

    h = hashlib.sha256()
    h.update(parent_porcelain.encode("utf-8", "replace"))
    h.update(b"\n")
    parent_diff = _run(["git", "-C", str(rpcsx_dir), "diff", "HEAD"])
    if parent_diff.returncode != 0:
        raise SystemExit(f"Error: git diff failed in {rpcsx_dir}: {parent_diff.stderr.strip()}")
    h.update(parent_diff.stdout.encode("utf-8", "replace"))

    parent_untracked = _run(["git", "-C", str(rpcsx_dir), "ls-files", "-o", "--exclude-standard"])
    if parent_untracked.returncode != 0:
        raise SystemExit(f"Error: git ls-files failed in {rpcsx_dir}: {parent_untracked.stderr.strip()}")
    for rel in parent_untracked.stdout.splitlines():
        if not rel or rel.replace("\\", "/").endswith(PLACEHOLDER_IDENTITY_REL):
            continue
        path = rpcsx_dir / rel
        if not path.is_file():
            continue
        h.update(rel.encode("utf-8"))
        h.update(b"\0")
        h.update(sha256_file(path).encode("ascii"))
        h.update(b"\n")

    # Hash dirty submodules content recursively
    for sub_rel, sp_out, sd_out, u_list in sub_dirty:
        h.update(b"submodule:")
        h.update(sub_rel.encode("utf-8"))
        h.update(b"\n")
        h.update(sp_out.encode("utf-8", "replace"))
        h.update(b"\n")
        h.update(sd_out.encode("utf-8", "replace"))
        h.update(b"\n")
        for u_rel, u_sha in sorted(u_list):
            h.update(u_rel.encode("utf-8"))
            h.update(b"\0")
            h.update(u_sha.encode("ascii"))
            h.update(b"\n")

    return h.hexdigest()


def _sanitize_token(value: str, limit: int = 96) -> str:
    cleaned = re.sub(r"\s+", "_", value.strip())
    cleaned = re.sub(r"[^A-Za-z0-9._+-]", "", cleaned)
    return cleaned[:limit] or "unavailable"


def ndk_identity(ndk_dir: str | None) -> str:
    if not ndk_dir:
        return "unavailable"
    sp = Path(ndk_dir) / "source.properties"
    if sp.is_file():
        for line in sp.read_text(errors="replace").splitlines():
            if "Pkg.Revision" in line and "=" in line:
                return _sanitize_token(line.split("=", 1)[1])
    return _sanitize_token(Path(ndk_dir).name)


def cmake_identity() -> str:
    candidates: list[str] = ["cmake"]
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/opt/android-sdk"
    for extra in (
        Path(android_home) / "cmake" / "3.22.1" / "bin" / "cmake",
        Path("/opt/android-sdk/cmake/3.22.1/bin/cmake"),
    ):
        if extra.is_file():
            candidates.append(str(extra))
    for cand in candidates:
        r = _run([cand, "--version"])
        if r.returncode != 0 or not r.stdout:
            continue
        first = r.stdout.splitlines()[0]
        m = re.search(r"(\d+\.\d+(?:\.\d+)?)", first)
        return m.group(1) if m else _sanitize_token(first)
    return "unavailable"


def compiler_identity_from_path(compiler_path: Path) -> str:
    if not compiler_path.is_file():
        return "unavailable"
    r = _run([str(compiler_path), "--version"])
    if r.returncode != 0 or not r.stdout:
        return "unavailable"
    first = r.stdout.splitlines()[0]
    m = re.search(r"clang version\s+(\S+)", first, re.I)
    if m:
        return f"clang-{_sanitize_token(m.group(1))}"
    return "compiler-" + sha256_bytes(first.encode())[:12]


def compiler_identity(ndk_dir: str | None) -> str:
    cxx = os.environ.get("CXX", "")
    candidates: list[Path] = []
    if cxx:
        candidates.append(Path(cxx))
    if ndk_dir:
        prebuilt = Path(ndk_dir) / "toolchains" / "llvm" / "prebuilt"
        if prebuilt.is_dir():
            candidates.extend(sorted(prebuilt.glob("*/bin/clang++")))
    for cand in candidates:
        ident = compiler_identity_from_path(cand)
        if ident != "unavailable":
            return ident
    return "unavailable"


def effective_cmake_config(build_dir: Path | None) -> dict[str, str]:
    config: dict[str, str] = {}
    loaded_metadata = False
    if build_dir and build_dir.is_dir():
        cfg_file = build_dir / "cmake-effective-config.json"
        if cfg_file.is_file():
            try:
                loaded = json.loads(cfg_file.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    config.update({k: str(v) for k, v in loaded.items()})
                    loaded_metadata = True
            except Exception:
                pass

    # This option controls a source-local compile definition and therefore must
    # come from the actual configure cache. In particular, do not trust the
    # generated JSON metadata (or a requested command-line/environment value)
    # to identify which code CMake configured.
    cache_file = build_dir / "CMakeCache.txt" if build_dir else None
    option_state = "unavailable"
    if cache_file and cache_file.is_file():
        option_state = "absent"
        for line in cache_file.read_text(errors="replace").splitlines():
            line = line.strip()
            if line.startswith("#") or not line or "=" not in line:
                continue
            key_type, value = line.split("=", 1)
            key, separator, cache_type = key_type.partition(":")
            value = value.strip()
            # Preserve the legacy CMakeCache fallback when generated effective
            # metadata is missing or malformed. A valid generated metadata file
            # remains the higher-level source for these general fields.
            if not loaded_metadata:
                if key == "CMAKE_CXX_COMPILER" and cache_type == "FILEPATH":
                    config["compiler_path"] = value
                elif key == "CMAKE_CXX_FLAGS" and cache_type == "STRING":
                    config["cxx_flags"] = value
                elif key == "CMAKE_SHARED_LINKER_FLAGS" and cache_type == "STRING":
                    config["shared_linker_flags"] = value
                elif key == "USE_ARCH" and cache_type == "STRING":
                    config["use_arch"] = value
                elif key == "CMAKE_BUILD_TYPE" and cache_type == "STRING":
                    config["build_type"] = value
            if key == SPU_SHUFB_TBL1_CMAKE_OPTION and separator and cache_type == "BOOL":
                normalized = value.upper()
                if normalized in {"ON", "YES", "TRUE", "Y", "1"}:
                    option_state = "ON"
                elif normalized in {"OFF", "NO", "FALSE", "N", "0", "", "IGNORE", "NOTFOUND"} or normalized.endswith("-NOTFOUND"):
                    option_state = "OFF"
                else:
                    option_state = "invalid"
                    config["spu_shufb_tbl1_raw"] = value

    # Replace any JSON value for this key unconditionally with cache-derived
    # state. It is intentionally `unavailable` when the cache is missing and
    # `absent` when a cache exists but does not contain the option.
    # The raw cache value is only meaningful for an invalid CMake BOOL value;
    # never carry a similarly named field from generated metadata.
    if option_state != "invalid":
        config.pop("spu_shufb_tbl1_raw", None)
    config["spu_shufb_tbl1"] = option_state
    return config


def normalize_options(options: str) -> str:
    tokens = [t.strip() for t in options.split() if t.strip()]
    return " ".join(sorted(tokens))


def derive_flags_token(config: dict[str, str]) -> tuple[str | None, str, str, str]:
    cxx_opts = normalize_options(config.get("cxx_flags", ""))
    link_opts = normalize_options(config.get("shared_linker_flags", ""))
    use_arch = config.get("use_arch", "").strip()
    parts: list[str] = []
    if cxx_opts:
        parts.append(f"cxx={cxx_opts}")
    if link_opts:
        parts.append(f"link={link_opts}")
    if use_arch:
        parts.append(f"arch={use_arch}")
    if "spu_shufb_tbl1" in config:
        parts.append(f"spu_shufb_tbl1={config['spu_shufb_tbl1']}")
    if not parts:
        return None, "", "", use_arch
    combined = " ".join(parts)
    token = sha256_bytes(combined.encode("utf-8"))[:12]
    if use_arch:
        token = f"{token}-{_sanitize_token(use_arch, 32)}"
    return token, cxx_opts, link_opts, use_arch


def llvm_declared_version(cmake_lists: Path, rpcsx_dir: Path | None = None) -> str:
    # First check 3rdparty/llvm/CMakeLists.txt USE_LLVM_VERSION
    if rpcsx_dir:
        llvm_cm = rpcsx_dir / "3rdparty" / "llvm" / "CMakeLists.txt"
        if llvm_cm.is_file():
            m = re.search(r"set\(\s*USE_LLVM_VERSION\s+([0-9.]+)\s*\)", llvm_cm.read_text(errors="replace"))
            if m:
                return m.group(1)
    if cmake_lists.is_file():
        m = re.search(r"set\(\s*(?:USE_)?LLVM_VERSION\s+([0-9.]+)\s*\)", cmake_lists.read_text(errors="replace"))
        if m:
            return m.group(1)
    return "20.1.3"


def llvm_submodule_sha(rpcsx_dir: Path) -> str:
    llvm = rpcsx_dir / "3rdparty" / "llvm" / "llvm"
    if not (llvm / ".git").exists() and not llvm.is_dir():
        return "unavailable"
    sha = git_output(llvm, "rev-parse", "HEAD")
    return sha or "unavailable"


def format_identity(
    *,
    rpcsx_sha: str,
    integration_digest_hex: str,
    patch_digest_hex: str,
    build_type: str,
    abi: str,
    ndk: str,
    cmake: str,
    compiler: str,
    flags: str | None = None,
    src_digest: str | None = None,
    spu_shufb_tbl1: str | None = None,
) -> str:
    parts = [
        f"rpcsx={rpcsx_sha}",
        f"samba={integration_digest_hex}",
        f"patch_sha256={patch_digest_hex}",
        f"build_type={build_type}",
        f"abi={abi}",
        f"ndk={ndk}",
        f"cmake={cmake}",
        f"compiler={compiler}",
    ]
    if flags:
        parts.append(f"flags={flags}")
    if spu_shufb_tbl1 is not None:
        parts.append(f"spu_shufb_tbl1={spu_shufb_tbl1}")
    if src_digest:
        parts.append(f"src_digest={src_digest}")
    ident = " ".join(parts)
    if "library_sha256=" in ident:
        raise RuntimeError("embedded identity must not include library_sha256")
    return ident


def identity_cpp(identity: str) -> str:
    if '"' in identity or "\n" in identity:
        raise ValueError("identity must not contain quotes or newlines")
    return (
        "#include <string>\n"
        "\n"
        "static std::string g_samba_build_id =\n"
        f'    "{identity}";\n'
        "\n"
        'extern "C" const char* _rpcsx_sambaBuildId() {\n'
        "    return g_samba_build_id.c_str();\n"
        "}\n"
    )


def write_if_changed(path: Path, content: str) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file() and path.read_text(encoding="utf-8") == content:
        return False
    path.write_text(content, encoding="utf-8")
    return True


def normalize_build_id(value: str | None) -> str:
    if value is None:
        return "unknown"
    stripped = value.strip()
    return stripped if stripped else "unknown"


def extract_embedded_identity(blob: bytes) -> str:
    for match in re.finditer(rb"rpcsx=[ -~]{10,800}", blob):
        text = match.group().decode("ascii", "ignore")
        if "patch_sha256=" in text:
            return normalize_build_id(text.strip())
    return "unknown"


def input_archives(build_dir: Path) -> dict[str, str]:
    found: dict[str, str] = {}
    if not build_dir.is_dir():
        return found
    # FFmpeg archives
    for path in sorted(build_dir.glob("ffmpeg-*.tar.gz")):
        found[path.name] = sha256_file(path)
    # LLVM archives
    for path in sorted(build_dir.glob("**/*llvm*.7z")):
        found[path.name] = sha256_file(path)
    # Recorded archive manifests (e.g. llvm-archive-manifest.json)
    for man_path in sorted(build_dir.glob("**/llvm-archive-manifest.json")):
        try:
            data = json.loads(man_path.read_text(encoding="utf-8"))
            if isinstance(data, dict) and "archive" in data and "sha256" in data:
                found[data["archive"]] = data["sha256"]
        except Exception:
            pass
    for man_path in sorted(build_dir.glob("**/*llvm*.manifest.json")):
        try:
            data = json.loads(man_path.read_text(encoding="utf-8"))
            if isinstance(data, dict) and "archive" in data and "sha256" in data:
                found[data["archive"]] = data["sha256"]
        except Exception:
            pass
    return found


def ffmpeg_archives(build_dir: Path) -> dict[str, str]:
    return input_archives(build_dir)


def stamp(
    *,
    output: Path,
    rpcsx_dir: Path,
    root: Path,
    abi: str,
    build_type: str,
    ndk_dir: str | None,
    patch_file: Path,
    build_dir: Path | None = None,
    allow_dirty: bool = False,
) -> str:
    rpcsx_sha = git_output(rpcsx_dir, "rev-parse", "HEAD") or "unknown"
    integ = integration_digest(root)
    pdigest = patch_digest(patch_file)
    src = source_digest(rpcsx_dir)

    is_release = build_type.lower() in ("release", "relwithdebinfo")
    if is_release and not allow_dirty and src is not None:
        raise SystemExit(
            f"Error: release build requires clean committed backend source. "
            f"Found uncommitted edits (source_digest={src[:12]}). "
            f"Commit your changes or pass --allow-dirty / ALLOW_DIRTY=1."
        )

    # Derive compiler and options from effective CMake configuration if available
    cfg = effective_cmake_config(build_dir)
    comp_ident = compiler_identity(ndk_dir)
    if cfg.get("compiler"):
        comp_ident = _sanitize_token(cfg["compiler"])
    elif cfg.get("compiler_path"):
        ci = compiler_identity_from_path(Path(cfg["compiler_path"]))
        if ci != "unavailable":
            comp_ident = ci

    flags_token, _, _, _ = derive_flags_token(cfg)

    ident = format_identity(
        rpcsx_sha=rpcsx_sha,
        integration_digest_hex=integ,
        patch_digest_hex=pdigest,
        build_type=build_type,
        abi=abi,
        ndk=ndk_identity(ndk_dir),
        cmake=cmake_identity(),
        compiler=comp_ident,
        flags=flags_token,
        src_digest=src,
        spu_shufb_tbl1=cfg.get("spu_shufb_tbl1"),
    )
    changed = write_if_changed(output, identity_cpp(ident))
    output.with_suffix(".txt").write_text(ident + "\n", encoding="utf-8")
    action = "Stamped" if changed else "Unchanged"
    print(f"{action} samba-build-id ({abi} {build_type}): {ident}")
    return ident


def write_manifest(
    *,
    output: Path,
    library: Path,
    rpcsx_dir: Path,
    root: Path,
    abi: str,
    build_type: str,
    ndk_dir: str | None,
    patch_file: Path,
    build_dir: Path,
    embedded_identity: str,
) -> dict:
    if not library.is_file():
        raise SystemExit(f"Error: produced library missing: {library}")
    lib_sha = sha256_file(library)
    if lib_sha in embedded_identity:
        raise SystemExit("Error: library hash leaked into embedded identity")
    src = source_digest(rpcsx_dir)
    dirty = src is not None
    if dirty and not src:
        raise SystemExit("Error: dirty backend worktree has no source_digest")
    cmake_lists = rpcsx_dir / "android" / "CMakeLists.txt"
    resolved_llvm_ver = llvm_declared_version(cmake_lists, rpcsx_dir)
    cfg = effective_cmake_config(build_dir)
    flags_token, cxx_opts, link_opts, use_arch = derive_flags_token(cfg)

    comp_ident = compiler_identity(ndk_dir)
    if cfg.get("compiler"):
        comp_ident = _sanitize_token(cfg["compiler"])
    elif cfg.get("compiler_path"):
        ci = compiler_identity_from_path(Path(cfg["compiler_path"]))
        if ci != "unavailable":
            comp_ident = ci

    manifest = {
        "schema_version": SCHEMA_VERSION,
        "backend_revision": git_output(rpcsx_dir, "rev-parse", "HEAD") or "unknown",
        "nested_revisions": nested_revisions(rpcsx_dir),
        "source_digest": src,
        "backend_dirty": dirty,
        "integration_digest": integration_digest(root),
        "patch_digest": patch_digest(patch_file),
        "abi": abi,
        "build_type": build_type,
        "ndk_identity": ndk_identity(ndk_dir),
        "cmake_identity": cmake_identity(),
        "compiler_identity": comp_ident,
        "effective_compiler": cfg.get("compiler_path", ""),
        "normalized_compile_options": cxx_opts,
        "normalized_link_options": link_opts,
        "use_arch": use_arch,
        "flags_identity": flags_token,
        "effective_spu_shufb_tbl1": cfg.get("spu_shufb_tbl1", "unavailable"),
        "effective_spu_shufb_tbl1_raw": cfg.get("spu_shufb_tbl1_raw"),
        "llvm_declared_version": resolved_llvm_ver,
        "llvm_resolved_version": resolved_llvm_ver,
        "llvm_submodule_sha": llvm_submodule_sha(rpcsx_dir),
        "library_sha256": lib_sha,
        "embedded_identity": embedded_identity,
        "input_archives": input_archives(build_dir),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Wrote {output} library_sha256={lib_sha[:12]}")
    return manifest


def load_manifest(path: Path) -> dict:
    if not path.is_file():
        raise SystemExit(f"Error: missing provenance manifest {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Error: malformed provenance manifest {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise SystemExit(f"Error: malformed provenance manifest {path}")
    return data


def verify_jnilibs(
    root: Path,
    required_abis: Iterable[str] = REQUIRED_ABIS,
    rpcsx_dir: Path | None = None,
) -> None:
    if rpcsx_dir is None:
        rpcsx_dir = root / "app" / "src" / "main" / "cpp" / "rpcsx"
    if not rpcsx_dir.is_dir():
        print(f"Error: RPCSX backend directory not found: {rpcsx_dir}", file=sys.stderr)
        raise SystemExit(1)
    current_rev = git_output(rpcsx_dir, "rev-parse", "HEAD")
    if not current_rev:
        print(f"Error: failed to inspect backend git revision in {rpcsx_dir}", file=sys.stderr)
        raise SystemExit(1)
    current_src = source_digest(rpcsx_dir)

    jni = root / "app" / "src" / "main" / "jniLibs"
    missing: list[str] = []
    for abi in required_abis:
        lib = jni / abi / LIBRARY_NAME
        man = jni / abi / MANIFEST_NAME
        if not lib.is_file():
            missing.append(f"{abi}: missing {lib}")
            continue
        if not man.is_file():
            missing.append(f"{abi}: missing {man}")
            continue
        manifest = load_manifest(man)
        if manifest.get("abi") != abi:
            missing.append(f"{abi}: manifest abi={manifest.get('abi')!r}")
            continue
        file_sha = sha256_file(lib)
        declared = manifest.get("library_sha256")
        if declared != file_sha:
            missing.append(f"{abi}: jniLibs sha {file_sha} != manifest {declared}")
            continue
        ident = normalize_build_id(manifest.get("embedded_identity"))
        if ident == "unknown":
            missing.append(f"{abi}: embedded identity unknown")
            continue
        man_rev = manifest.get("backend_revision")
        if man_rev != current_rev:
            missing.append(
                f"{abi}: manifest backend_revision {man_rev} != current source revision {current_rev}"
            )
        man_src = manifest.get("source_digest")
        if man_src != current_src:
            missing.append(
                f"{abi}: manifest source_digest {man_src!r} != current source digest {current_src!r}"
            )
    if missing:
        print("Error: RPCSX core packaging prerequisites failed:", file=sys.stderr)
        for item in missing:
            print(f"  {item}", file=sys.stderr)
        print(
            "Required ABIs: " + ", ".join(required_abis) + ". "
            "A single-ABI TARGET_ABI build does not satisfy packaging.",
            file=sys.stderr,
        )
        raise SystemExit(1)
    print("RPCSX jniLibs provenance OK for " + ", ".join(required_abis))


def _package_lib_bytes(artifact: Path, required_abis: Iterable[str] = REQUIRED_ABIS) -> dict[str, bytes]:
    found: dict[str, bytes] = {}
    with zipfile.ZipFile(artifact) as zf:
        names = set(zf.namelist())
        suffix = artifact.suffix.lower()
        if suffix == ".aab":
            for abi in required_abis:
                direct = f"base/lib/{abi}/{LIBRARY_NAME}"
                if direct in names:
                    found[abi] = zf.read(direct)
            if "base.zip" in names:
                with zipfile.ZipFile(io.BytesIO(zf.read("base.zip"))) as base:
                    for abi in required_abis:
                        nested = f"lib/{abi}/{LIBRARY_NAME}"
                        if nested in base.namelist() and abi not in found:
                            found[abi] = base.read(nested)
        else:
            for abi in required_abis:
                name = f"lib/{abi}/{LIBRARY_NAME}"
                if name in names:
                    found[abi] = zf.read(name)
    return found


def verify_package(
    root: Path,
    artifact: Path,
    required_abis: Iterable[str] = REQUIRED_ABIS,
    rpcsx_dir: Path | None = None,
) -> None:
    if not artifact.is_file():
        raise SystemExit(f"ERROR: artifact not found: {artifact}")
    if rpcsx_dir is None:
        rpcsx_dir = root / "app" / "src" / "main" / "cpp" / "rpcsx"
    if not rpcsx_dir.is_dir():
        print(f"Error: RPCSX backend directory not found: {rpcsx_dir}", file=sys.stderr)
        raise SystemExit(1)
    current_rev = git_output(rpcsx_dir, "rev-parse", "HEAD")
    if not current_rev:
        print(f"Error: failed to inspect backend git revision in {rpcsx_dir}", file=sys.stderr)
        raise SystemExit(1)
    current_src = source_digest(rpcsx_dir)

    jni = root / "app" / "src" / "main" / "jniLibs"
    packaged = _package_lib_bytes(artifact, required_abis=required_abis)
    print(f"Artifact: {artifact}")
    print(f"Artifact SHA-256: {sha256_file(artifact)}")
    fail: list[str] = []
    for abi in required_abis:
        man_path = jni / abi / MANIFEST_NAME
        lib_path = jni / abi / LIBRARY_NAME
        if abi not in packaged:
            fail.append(f"{abi}: missing {LIBRARY_NAME} in artifact (required)")
            continue
        blob = packaged[abi]
        pkg_sha = sha256_bytes(blob)
        ident = extract_embedded_identity(blob)
        print(f"\nABI {abi}:")
        print(f"  packaged SHA-256: {pkg_sha}")
        print(f"  S3CORE build ID: {ident}")
        if ident == "unknown":
            fail.append(f"{abi}: empty/missing build ID is unknown, not evidence")
            continue
        if not man_path.is_file():
            fail.append(f"{abi}: missing packaging-stage manifest {man_path}")
            continue
        manifest = load_manifest(man_path)
        declared = manifest.get("library_sha256")
        man_ident = normalize_build_id(manifest.get("embedded_identity"))
        man_abi = manifest.get("abi")
        if man_abi != abi:
            fail.append(f"{abi}: manifest abi {man_abi!r} does not match")
        if man_ident == "unknown":
            fail.append(f"{abi}: manifest embedded identity unknown")
        if ident != man_ident:
            fail.append(f"{abi}: packaged identity {ident!r} != manifest {man_ident!r}")
        if declared != pkg_sha:
            fail.append(
                f"{abi}: packaged bytes {pkg_sha} != manifest library_sha256 {declared} "
                "(identity match does not waive a byte mismatch)"
            )
        if lib_path.is_file():
            jni_sha = sha256_file(lib_path)
            if jni_sha != pkg_sha:
                fail.append(
                    f"{abi}: packaged bytes {pkg_sha} != jniLibs {jni_sha} "
                    "(legacy packaging must match; no identity-only waiver)"
                )
        man_rev = manifest.get("backend_revision")
        if man_rev != current_rev:
            fail.append(
                f"{abi}: manifest backend_revision {man_rev} != current source revision {current_rev}"
            )
        man_src = manifest.get("source_digest")
        if man_src != current_src:
            fail.append(
                f"{abi}: manifest source_digest {man_src!r} != current source digest {current_src!r}"
            )
        print(f"  manifest SHA-256: {declared}")
    print()
    if fail:
        print("RESULT: FAIL — packaged core does not match provenance", file=sys.stderr)
        for item in fail:
            print(f"  {item}", file=sys.stderr)
        raise SystemExit(1)
    print("RESULT: PASS — packaged core matches per-ABI provenance")


def find_default_artifact(root: Path) -> Path | None:
    candidates: list[Path] = []
    for pattern in (
        "app/build/outputs/apk/standard/release/*.apk",
        "app/build/outputs/apk/standard/debug/*.apk",
        "app/build/outputs/bundle/standardRelease/*.aab",
        "app/build/outputs/apk/*/*/*.apk",
    ):
        candidates.extend(sorted(root.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True))
    return candidates[0] if candidates else None


def _rel_from_root(path: Path, root: Path) -> Path:
    return path if path.is_absolute() else root / path


def check_clean(rpcsx_dir: Path) -> None:
    src = source_digest(rpcsx_dir)
    if src is not None:
        print(
            f"Error: release build requires clean committed backend source. "
            f"Found uncommitted edits in {rpcsx_dir} (source_digest={src[:12]}). "
            f"Commit your changes or pass --allow-dirty / ALLOW_DIRTY=1.",
            file=sys.stderr,
        )
        raise SystemExit(1)
    print(f"Backend working tree is clean: {rpcsx_dir}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="RPCSX core provenance helper")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_apply = sub.add_parser("apply-patch")
    p_apply.add_argument("--rpcsx-dir", required=True, type=Path)
    p_apply.add_argument("--patch-file", required=True, type=Path)

    p_clean = sub.add_parser("check-clean")
    p_clean.add_argument("--rpcsx-dir", required=True, type=Path)

    p_stamp = sub.add_parser("stamp")
    p_stamp.add_argument("--output", required=True, type=Path)
    p_stamp.add_argument("--rpcsx-dir", required=True, type=Path)
    p_stamp.add_argument("--root", required=True, type=Path)
    p_stamp.add_argument("--abi", required=True)
    p_stamp.add_argument("--build-type", required=True)
    p_stamp.add_argument("--ndk-dir", default="")
    p_stamp.add_argument("--patch-file", required=True, type=Path)
    p_stamp.add_argument("--build-dir", type=Path, default=None)
    p_stamp.add_argument("--allow-dirty", action="store_true", default=False)

    p_man = sub.add_parser("manifest")
    p_man.add_argument("--output", required=True, type=Path)
    p_man.add_argument("--library", required=True, type=Path)
    p_man.add_argument("--rpcsx-dir", required=True, type=Path)
    p_man.add_argument("--root", required=True, type=Path)
    p_man.add_argument("--abi", required=True)
    p_man.add_argument("--build-type", required=True)
    p_man.add_argument("--ndk-dir", default="")
    p_man.add_argument("--patch-file", required=True, type=Path)
    p_man.add_argument("--build-dir", required=True, type=Path)
    p_man.add_argument("--identity", required=True)

    p_digest = sub.add_parser("digest")
    p_digest.add_argument("--root", required=True, type=Path)
    p_digest.add_argument("--kind", choices=("integration",), default="integration")

    p_jni = sub.add_parser("verify-jnilibs")
    p_jni.add_argument("--root", required=True, type=Path)
    p_jni.add_argument("--rpcsx-dir", type=Path, default=None)
    p_jni.add_argument("--required-abis", nargs="*", default=None)

    p_pkg = sub.add_parser("verify-package")
    p_pkg.add_argument("--root", required=True, type=Path)
    p_pkg.add_argument("--artifact", type=Path, default=None)
    p_pkg.add_argument("--rpcsx-dir", type=Path, default=None)
    p_pkg.add_argument("--required-abis", nargs="*", default=None)

    args = parser.parse_args(argv)
    if args.cmd == "apply-patch":
        apply_patch(args.rpcsx_dir, args.patch_file)
        return 0
    if args.cmd == "check-clean":
        check_clean(args.rpcsx_dir)
        return 0
    if args.cmd == "stamp":
        stamp(
            output=args.output,
            rpcsx_dir=args.rpcsx_dir,
            root=args.root,
            abi=args.abi,
            build_type=args.build_type,
            ndk_dir=args.ndk_dir or None,
            patch_file=args.patch_file,
            build_dir=args.build_dir,
            allow_dirty=args.allow_dirty,
        )
        return 0
    if args.cmd == "manifest":
        write_manifest(
            output=args.output,
            library=args.library,
            rpcsx_dir=args.rpcsx_dir,
            root=args.root,
            abi=args.abi,
            build_type=args.build_type,
            ndk_dir=args.ndk_dir or None,
            patch_file=args.patch_file,
            build_dir=args.build_dir,
            embedded_identity=args.identity,
        )
        return 0
    if args.cmd == "digest":
        print(integration_digest(args.root))
        return 0
    if args.cmd == "verify-jnilibs":
        req = tuple(args.required_abis) if args.required_abis else REQUIRED_ABIS
        verify_jnilibs(args.root, required_abis=req, rpcsx_dir=args.rpcsx_dir)
        return 0
    if args.cmd == "verify-package":
        artifact = args.artifact
        if artifact is None:
            artifact = find_default_artifact(args.root)
        if artifact is None:
            print("ERROR: APK/AAB not found. Usage: verify-apk-core.sh <path>", file=sys.stderr)
            return 1
        req = tuple(args.required_abis) if args.required_abis else REQUIRED_ABIS
        verify_package(args.root, _rel_from_root(artifact, args.root), required_abis=req, rpcsx_dir=args.rpcsx_dir)
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
