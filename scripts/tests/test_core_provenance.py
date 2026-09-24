"""Host tests for RPCSX core provenance. No NDK required."""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS / "lib"))

import core_provenance as cp  # noqa: E402


def _git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        text=True,
        capture_output=True,
        check=check,
    )


def _init_repo(path: Path) -> str:
    path.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "init"], cwd=path, check=True, capture_output=True)
    subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=path, check=True, capture_output=True)
    subprocess.run(["git", "config", "user.name", "test"], cwd=path, check=True, capture_output=True)
    (path / "README").write_text("x\n")
    subprocess.run(["git", "add", "README"], cwd=path, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=path, check=True, capture_output=True)
    return cp.git_output(path, "rev-parse", "HEAD")


class CoreProvenanceTest(unittest.TestCase):
    def test_empty_and_comment_only_patch_is_skip(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rpcsx = root / "rpcsx"
            _init_repo(rpcsx)
            empty = root / "empty.patch"
            empty.write_text("")
            comment = root / "comment.patch"
            comment.write_text("# engine edits live in submodule 2b074811c\n\n# empty on purpose\n")
            self.assertEqual(cp.apply_patch(rpcsx, empty), "empty-skip")
            self.assertEqual(cp.apply_patch(rpcsx, comment), "empty-skip")
            self.assertTrue(cp.patch_is_noop(empty))
            self.assertTrue(cp.patch_is_noop(comment))
            self.assertEqual(cp.patch_digest(empty), "none")
            self.assertEqual(cp.patch_digest(comment), "none")

    def test_incompatible_patch_fails_with_actionable_message(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rpcsx = root / "rpcsx"
            _init_repo(rpcsx)
            junk = root / "junk.patch"
            junk.write_text(
                "diff --git a/does-not-exist.c b/does-not-exist.c\n"
                "index 1111111..2222222 100644\n"
                "--- a/does-not-exist.c\n"
                "+++ b/does-not-exist.c\n"
                "@@ -1,1 +1,1 @@\n"
                "-old\n"
                "+new\n"
            )
            proc = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS / "lib" / "core_provenance.py"),
                    "apply-patch",
                    "--rpcsx-dir",
                    str(rpcsx),
                    "--patch-file",
                    str(junk),
                ],
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(proc.returncode, 0)
            combined = proc.stdout + proc.stderr
            self.assertIn("does not apply cleanly", combined)
            self.assertIn("samba-android", combined)

    def test_library_hash_not_in_embedded_identity(self):
        ident = cp.format_identity(
            rpcsx_sha="abc123",
            integration_digest_hex="def456",
            patch_digest_hex="none",
            build_type="RelWithDebInfo",
            abi="arm64-v8a",
            ndk="30.0.14904198",
            cmake="3.31.6",
            compiler="clang-19",
        )
        self.assertNotIn("library_sha256=", ident)
        self.assertIn("rpcsx=abc123", ident)
        self.assertIn("samba=def456", ident)
        self.assertIn("abi=arm64-v8a", ident)
        self.assertNotIn("library_sha256", cp.identity_cpp(ident))

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rpcsx = root / "rpcsx"
            _init_repo(rpcsx)
            lib = root / "librpcsx-android.so"
            lib.write_bytes(b"fake-so-bytes")
            ident_with_src = ident
            man = cp.write_manifest(
                output=root / "m.json",
                library=lib,
                rpcsx_dir=rpcsx,
                root=root,
                abi="arm64-v8a",
                build_type="RelWithDebInfo",
                ndk_dir=None,
                patch_file=root / "p.patch",
                build_dir=root,
                embedded_identity=ident_with_src,
            )
            self.assertIn("library_sha256", man)
            self.assertNotIn("library_sha256=", man["embedded_identity"])
            self.assertEqual(man["library_sha256"], hashlib.sha256(b"fake-so-bytes").hexdigest())
            self.assertEqual(man["schema_version"], 1)

    def test_missing_required_abi_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rpcsx = root / "app" / "src" / "main" / "cpp" / "rpcsx"
            rev = _init_repo(rpcsx)
            jni = root / "app" / "src" / "main" / "jniLibs"
            arm = jni / "arm64-v8a"
            arm.mkdir(parents=True)
            (arm / cp.LIBRARY_NAME).write_bytes(b"arm")
            (arm / cp.MANIFEST_NAME).write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "abi": "arm64-v8a",
                        "backend_revision": rev,
                        "source_digest": None,
                        "library_sha256": hashlib.sha256(b"arm").hexdigest(),
                        "embedded_identity": f"rpcsx={rev} samba=def patch_sha256=none build_type=RelWithDebInfo abi=arm64-v8a",
                    }
                )
            )
            proc = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS / "lib" / "core_provenance.py"),
                    "verify-jnilibs",
                    "--root",
                    str(root),
                    "--required-abis",
                    "arm64-v8a",
                    "x86_64",
                ],
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn("x86_64", proc.stderr)
            self.assertIn("single-ABI", proc.stderr)

    def test_identity_only_sha_waiver_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rpcsx = root / "app" / "src" / "main" / "cpp" / "rpcsx"
            rev = _init_repo(rpcsx)
            ident = f"rpcsx={rev} samba=def patch_sha256=none build_type=RelWithDebInfo abi=arm64-v8a"
            jni = root / "app" / "src" / "main" / "jniLibs"
            artifact = root / "out.apk"
            with zipfile.ZipFile(artifact, "w") as zf:
                for abi, payload in (("arm64-v8a", b"packaged-arm"), ("x86_64", b"packaged-x64")):
                    dest = jni / abi
                    dest.mkdir(parents=True)
                    (dest / cp.LIBRARY_NAME).write_bytes(payload + b"-jni-mismatch")
                    (dest / cp.MANIFEST_NAME).write_text(
                        json.dumps(
                            {
                                "schema_version": 1,
                                "abi": abi,
                                "backend_revision": rev,
                                "source_digest": None,
                                "library_sha256": hashlib.sha256(payload + b"-jni-mismatch").hexdigest(),
                                "embedded_identity": ident.replace("arm64-v8a", abi),
                            }
                        )
                    )
                    # Embed the identity text so S3CORE matches while bytes differ.
                    zf.writestr(f"lib/{abi}/{cp.LIBRARY_NAME}", payload + ident.encode() + b"\0")
            proc = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS / "lib" / "core_provenance.py"),
                    "verify-package",
                    "--root",
                    str(root),
                    "--artifact",
                    str(artifact),
                ],
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(proc.returncode, 0)
            combined = proc.stdout + proc.stderr
            self.assertIn("identity match does not waive a byte mismatch", combined)
            self.assertNotIn("treating as PASS", combined)

    def test_empty_missing_build_id_is_unknown_not_pass(self):
        self.assertEqual(cp.normalize_build_id(None), "unknown")
        self.assertEqual(cp.normalize_build_id(""), "unknown")
        self.assertEqual(cp.normalize_build_id("   "), "unknown")
        self.assertEqual(cp.extract_embedded_identity(b"\x00no identity here\x00"), "unknown")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rpcsx = root / "app" / "src" / "main" / "cpp" / "rpcsx"
            rev = _init_repo(rpcsx)
            jni = root / "app" / "src" / "main" / "jniLibs"
            artifact = root / "out.apk"
            with zipfile.ZipFile(artifact, "w") as zf:
                for abi, payload in (("arm64-v8a", b"arm-core"), ("x86_64", b"x64-core")):
                    dest = jni / abi
                    dest.mkdir(parents=True)
                    (dest / cp.LIBRARY_NAME).write_bytes(payload)
                    (dest / cp.MANIFEST_NAME).write_text(
                        json.dumps(
                            {
                                "schema_version": 1,
                                "abi": abi,
                                "backend_revision": rev,
                                "source_digest": None,
                                "library_sha256": hashlib.sha256(payload).hexdigest(),
                                "embedded_identity": f"rpcsx={rev} samba=def patch_sha256=none build_type=RelWithDebInfo abi=" + abi,
                            }
                        )
                    )
                    zf.writestr(f"lib/{abi}/{cp.LIBRARY_NAME}", payload)
            proc = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS / "lib" / "core_provenance.py"),
                    "verify-package",
                    "--root",
                    str(root),
                    "--artifact",
                    str(artifact),
                ],
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn("unknown", proc.stderr)
            self.assertNotIn("RESULT: PASS", proc.stdout)

    def test_docs_only_parent_head_not_in_identity_inputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel in cp.INTEGRATION_RELPATHS:
                path = root / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"content-{rel}\n")
            before = cp.integration_digest(root)
            ident_before = cp.format_identity(
                rpcsx_sha="deadbeef",
                integration_digest_hex=before,
                patch_digest_hex="none",
                build_type="RelWithDebInfo",
                abi="arm64-v8a",
                ndk="30",
                cmake="3.31",
                compiler="clang",
            )
            (root / "docs").mkdir()
            (root / "docs" / "README.md").write_text("docs only\n")
            (root / "HEAD").write_text("this is not git HEAD\n")
            after = cp.integration_digest(root)
            ident_after = cp.format_identity(
                rpcsx_sha="deadbeef",
                integration_digest_hex=after,
                patch_digest_hex="none",
                build_type="RelWithDebInfo",
                abi="arm64-v8a",
                ndk="30",
                cmake="3.31",
                compiler="clang",
            )
            self.assertEqual(before, after)
            self.assertEqual(ident_before, ident_after)
            self.assertNotIn("parent", ident_after)
            # samba= is the integration digest, not a git HEAD.
            self.assertIn(f"samba={before}", ident_after)
            self.assertNotEqual(before, "deadbeef")

    def test_matching_package_passes_apk_and_aab(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rpcsx = root / "app" / "src" / "main" / "cpp" / "rpcsx"
            rev = _init_repo(rpcsx)
            ident_arm = f"rpcsx={rev} samba=def patch_sha256=none build_type=RelWithDebInfo abi=arm64-v8a"
            ident_x64 = f"rpcsx={rev} samba=def patch_sha256=none build_type=RelWithDebInfo abi=x86_64"
            payloads = {
                "arm64-v8a": b"ARMCORE" + ident_arm.encode() + b"\0",
                "x86_64": b"X64CORE" + ident_x64.encode() + b"\0",
            }
            jni = root / "app" / "src" / "main" / "jniLibs"
            for abi, payload in payloads.items():
                dest = jni / abi
                dest.mkdir(parents=True)
                (dest / cp.LIBRARY_NAME).write_bytes(payload)
                (dest / cp.MANIFEST_NAME).write_text(
                    json.dumps(
                        {
                            "schema_version": 1,
                            "abi": abi,
                            "backend_revision": rev,
                            "source_digest": None,
                            "library_sha256": hashlib.sha256(payload).hexdigest(),
                            "embedded_identity": ident_arm if abi == "arm64-v8a" else ident_x64,
                        }
                    )
                )
            apk = root / "out.apk"
            with zipfile.ZipFile(apk, "w") as zf:
                for abi, payload in payloads.items():
                    zf.writestr(f"lib/{abi}/{cp.LIBRARY_NAME}", payload)
            aab = root / "out.aab"
            with zipfile.ZipFile(aab, "w") as zf:
                for abi, payload in payloads.items():
                    zf.writestr(f"base/lib/{abi}/{cp.LIBRARY_NAME}", payload)
            for artifact in (apk, aab):
                proc = subprocess.run(
                    [
                        sys.executable,
                        str(SCRIPTS / "lib" / "core_provenance.py"),
                        "verify-package",
                        "--root",
                        str(root),
                        "--artifact",
                        str(artifact),
                    ],
                    text=True,
                    capture_output=True,
                )
                self.assertEqual(proc.returncode, 0, artifact.name + proc.stderr + proc.stdout)
                self.assertIn("RESULT: PASS", proc.stdout)

    def test_real_empty_parent_patch_is_skip(self):
        patch = ROOT / "patches" / "rpcsx-submodule-changes.patch"
        self.assertTrue(patch.is_file())
        text = patch.read_text(encoding="utf-8")
        self.assertTrue(
            cp.patch_is_noop(patch),
            f"parent patch must be empty/comment-only, got {len(text)} bytes",
        )

    # --- Phase 1 Defect Tests (R01, R02, R03, R04, R05, R18, R19, R20) ---

    def test_r01_stale_or_single_abi_rejected(self):
        """R01: Packaging validation binds every required ABI to current source. Stale ABIs rejected."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rpcsx = root / "app" / "src" / "main" / "cpp" / "rpcsx"
            rev_A = _init_repo(rpcsx)
            # Make a commit to get rev_B
            (rpcsx / "newfile").write_text("rev_B\n")
            subprocess.run(["git", "add", "newfile"], cwd=rpcsx, check=True)
            subprocess.run(["git", "commit", "-m", "commit B"], cwd=rpcsx, check=True)
            rev_B = cp.git_output(rpcsx, "rev-parse", "HEAD")
            self.assertNotEqual(rev_A, rev_B)

            jni = root / "app" / "src" / "main" / "jniLibs"
            # arm64-v8a built at current rev_B
            arm_dir = jni / "arm64-v8a"
            arm_dir.mkdir(parents=True)
            arm_payload = b"ARM_REV_B_BYTES"
            (arm_dir / cp.LIBRARY_NAME).write_bytes(arm_payload)
            (arm_dir / cp.MANIFEST_NAME).write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "abi": "arm64-v8a",
                        "backend_revision": rev_B,
                        "source_digest": None,
                        "library_sha256": hashlib.sha256(arm_payload).hexdigest(),
                        "embedded_identity": f"rpcsx={rev_B} samba=def patch_sha256=none build_type=RelWithDebInfo abi=arm64-v8a",
                    }
                )
            )

            # x86_64 stale: built at older rev_A
            x64_dir = jni / "x86_64"
            x64_dir.mkdir(parents=True)
            x64_payload = b"X64_REV_A_BYTES"
            (x64_dir / cp.LIBRARY_NAME).write_bytes(x64_payload)
            (x64_dir / cp.MANIFEST_NAME).write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "abi": "x86_64",
                        "backend_revision": rev_A,
                        "source_digest": None,
                        "library_sha256": hashlib.sha256(x64_payload).hexdigest(),
                        "embedded_identity": f"rpcsx={rev_A} samba=def patch_sha256=none build_type=RelWithDebInfo abi=x86_64",
                    }
                )
            )

            # 1. verify-jnilibs must fail because x86_64 is stale
            proc_jni = subprocess.run(
                [sys.executable, str(SCRIPTS / "lib" / "core_provenance.py"), "verify-jnilibs", "--root", str(root), "--required-abis", "arm64-v8a", "x86_64"],
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(proc_jni.returncode, 0)
            self.assertIn("x86_64: manifest backend_revision", proc_jni.stderr)

            # 2. verify-package must also reject the stale ABI in the APK
            apk = root / "stale.apk"
            with zipfile.ZipFile(apk, "w") as zf:
                zf.writestr(f"lib/arm64-v8a/{cp.LIBRARY_NAME}", arm_payload + f"rpcsx={rev_B} patch_sha256=none".encode())
                zf.writestr(f"lib/x86_64/{cp.LIBRARY_NAME}", x64_payload + f"rpcsx={rev_A} patch_sha256=none".encode())
            proc_pkg = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS / "lib" / "core_provenance.py"),
                    "verify-package",
                    "--root",
                    str(root),
                    "--artifact",
                    str(apk),
                    "--required-abis",
                    "arm64-v8a",
                    "x86_64",
                ],
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(proc_pkg.returncode, 0)
            self.assertIn("x86_64: manifest backend_revision", proc_pkg.stderr)

    def test_r02_recursive_submodules_and_dirty_content(self):
        """R02: Identify nested submodule content recursively, record commit SHAs, fail closed on failure."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            # Create a submodule repository
            sub_repo = tmp_path / "sub_repo"
            sub_sha = _init_repo(sub_repo)

            # Create main backend repository
            main_repo = tmp_path / "main_repo"
            _init_repo(main_repo)

            # Add submodule
            subprocess.run(
                ["git", "-c", "protocol.file.allow=always", "submodule", "add", str(sub_repo), "3rdparty/testsub"],
                cwd=main_repo,
                check=True,
                capture_output=True,
            )
            subprocess.run(["git", "commit", "-m", "add submodule"], cwd=main_repo, check=True, capture_output=True)

            # 1. Test nested_revisions records the submodule SHA
            revs = cp.nested_revisions(main_repo)
            self.assertIn("3rdparty/testsub", revs)
            self.assertEqual(revs["3rdparty/testsub"], sub_sha)

            # Clean worktree has None source_digest
            self.assertIsNone(cp.source_digest(main_repo))

            # 2. Modify a file inside the submodule -> source_digest becomes non-None
            sub_file = main_repo / "3rdparty/testsub" / "README"
            sub_file.write_text("dirty content v1\n")
            digest1 = cp.source_digest(main_repo)
            self.assertIsNotNone(digest1)

            # 3. Different edit inside the submodule produces a DIFFERENT source_digest
            sub_file.write_text("dirty content v2\n")
            digest2 = cp.source_digest(main_repo)
            self.assertIsNotNone(digest2)
            self.assertNotEqual(digest1, digest2)

            # 4. Untracked file inside submodule is also detected
            sub_file.write_text("x\n")  # restore to clean
            subprocess.run(["git", "checkout", "README"], cwd=main_repo / "3rdparty/testsub", check=True)
            self.assertIsNone(cp.source_digest(main_repo))

            (main_repo / "3rdparty/testsub" / "untracked.txt").write_text("hello\n")
            digest_untracked = cp.source_digest(main_repo)
            self.assertIsNotNone(digest_untracked)

    def test_r03_llvm_version_and_archive_manifest(self):
        """R03: Reconcile LLVM version declaration to 20.1.3 and record archive checksums before deletion."""
        android_cm = ROOT / "app" / "src" / "main" / "cpp" / "rpcsx" / "android" / "CMakeLists.txt"
        llvm_cm = ROOT / "app" / "src" / "main" / "cpp" / "rpcsx" / "3rdparty" / "llvm" / "CMakeLists.txt"

        ver = cp.llvm_declared_version(android_cm, ROOT / "app" / "src" / "main" / "cpp" / "rpcsx")
        self.assertEqual(ver, "20.1.3")

        # Test archive manifest loading in input_archives
        with tempfile.TemporaryDirectory() as tmp:
            build_dir = Path(tmp)
            man_content = {
                "archive": "20.1.3-llvm-android-arm64-v8a.7z",
                "version": "20.1.3",
                "sha256": "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff",
                "url": "https://example.com/llvm.7z",
            }
            (build_dir / "llvm-archive-manifest.json").write_text(json.dumps(man_content))
            archives = cp.input_archives(build_dir)
            self.assertIn("20.1.3-llvm-android-arm64-v8a.7z", archives)
            self.assertEqual(archives["20.1.3-llvm-android-arm64-v8a.7z"], man_content["sha256"])

    def test_r04_compiler_and_effective_flags_identity(self):
        """R04: Derive identity from selected compiler and normalized effective compile/link options."""
        with tempfile.TemporaryDirectory() as tmp:
            build_dir = Path(tmp)
            cfg = {
                "compiler": "Clang 19.0.2",
                "compiler_path": "/opt/ndk/bin/clang++",
                "cxx_flags": "-O3 -Wall -fPIC",
                "shared_linker_flags": "-Wl,--build-id=sha1 -Wl,-z,defs",
                "build_type": "RelWithDebInfo",
                "use_arch": "armv8.2-a+crc",
                "android_abi": "arm64-v8a",
            }
            (build_dir / "cmake-effective-config.json").write_text(json.dumps(cfg))

            loaded_cfg = cp.effective_cmake_config(build_dir)
            self.assertEqual(loaded_cfg["compiler"], "Clang 19.0.2")

            token, cxx_opts, link_opts, use_arch = cp.derive_flags_token(loaded_cfg)
            self.assertIsNotNone(token)
            self.assertIn("armv8.2-a+crc", token)
            self.assertEqual(cxx_opts, "-O3 -Wall -fPIC")
            self.assertEqual(use_arch, "armv8.2-a+crc")

            # Changing flags changes the token
            cfg["cxx_flags"] = "-O2 -Wall -fPIC"
            (build_dir / "cmake-effective-config.json").write_text(json.dumps(cfg))
            loaded_cfg2 = cp.effective_cmake_config(build_dir)
            token2, _, _, _ = cp.derive_flags_token(loaded_cfg2)
            self.assertNotEqual(token, token2)

    def test_r05_release_requires_clean_committed_source(self):
        """R05: Require clean committed source for release builds unless --allow-dirty is passed."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rpcsx = root / "rpcsx"
            _init_repo(rpcsx)
            out_cpp = root / "out.cpp"
            patch_file = root / "empty.patch"
            patch_file.write_text("")

            # Clean repo succeeds
            ident = cp.stamp(
                output=out_cpp,
                rpcsx_dir=rpcsx,
                root=root,
                abi="arm64-v8a",
                build_type="RelWithDebInfo",
                ndk_dir=None,
                patch_file=patch_file,
                allow_dirty=False,
            )
            self.assertIn("abi=arm64-v8a", ident)

            # Make repo dirty
            (rpcsx / "dirty.txt").write_text("uncommitted\n")

            # Release build without allow_dirty must raise SystemExit
            with self.assertRaises(SystemExit):
                cp.stamp(
                    output=out_cpp,
                    rpcsx_dir=rpcsx,
                    root=root,
                    abi="arm64-v8a",
                    build_type="RelWithDebInfo",
                    ndk_dir=None,
                    patch_file=patch_file,
                    allow_dirty=False,
                )

            # With allow_dirty=True it succeeds and records src_digest
            ident_dirty = cp.stamp(
                output=out_cpp,
                rpcsx_dir=rpcsx,
                root=root,
                abi="arm64-v8a",
                build_type="RelWithDebInfo",
                ndk_dir=None,
                patch_file=patch_file,
                allow_dirty=True,
            )
            self.assertIn("src_digest=", ident_dirty)

    def test_r18_gradle_tasks_bind_artifacts_and_install(self):
        """R18: app/build.gradle.kts binds verification to APK/AAB outputs and install tasks."""
        build_gradle = ROOT / "app" / "build.gradle.kts"
        text = build_gradle.read_text(encoding="utf-8")

        self.assertIn("verifyStandardReleaseApk", text)
        self.assertIn("verifyStandardReleaseBundle", text)
        self.assertIn("verifyPackagedArtifacts", text)
        self.assertIn("assembleStandardRelease", text)
        self.assertIn("bundleStandardRelease", text)
        self.assertIn("finalizedBy(verifyStandardReleaseApk)", text)
        self.assertIn("finalizedBy(verifyStandardReleaseBundle)", text)
        self.assertIn("startsWith(\"install\")", text)

    def test_r19_semantic_patch_normalization_and_hashing(self):
        """R19: Semantic patch hashing normalizes comments, blank lines, index headers."""
        patch1 = (
            "# Comment A\n"
            "\n"
            "diff --git a/foo.cpp b/foo.cpp\n"
            "index 1111111..2222222 100644\n"
            "--- a/foo.cpp\t2026-09-24 10:00:00\n"
            "+++ b/foo.cpp\t2026-09-24 10:00:00\n"
            "@@ -1,3 +1,3 @@\n"
            "-old\n"
            "+new\n"
        )
        patch2 = (
            "# Different comment B\n"
            "# Another comment\n"
            "\n"
            "\n"
            "diff --git a/foo.cpp b/foo.cpp\n"
            "index 3333333..4444444 100644\n"
            "--- a/foo.cpp\n"
            "+++ b/foo.cpp\n"
            "@@ -1,3 +1,3 @@\n"
            "-old\n"
            "+new\n"
        )
        norm1 = cp.normalize_patch_text(patch1)
        norm2 = cp.normalize_patch_text(patch2)
        self.assertEqual(norm1, norm2)

        with tempfile.TemporaryDirectory() as tmp:
            p1_path = Path(tmp) / "p1.patch"
            p2_path = Path(tmp) / "p2.patch"
            p1_path.write_text(patch1)
            p2_path.write_text(patch2)

            d1 = cp.patch_digest(p1_path)
            d2 = cp.patch_digest(p2_path)
            self.assertEqual(d1, d2)
            self.assertNotEqual(d1, "none")

    def test_r20_workflow_archives_manifests_and_sha_bindings(self):
        """R20: .github/workflows/build.yml archives manifests and artifact SHA bindings on release paths."""
        workflow = ROOT / ".github" / "workflows" / "build.yml"
        text = workflow.read_text(encoding="utf-8")

        self.assertIn("Generate artifact SHA bindings", text)
        self.assertIn("artifact-sha256.txt", text)
        self.assertIn("librpcsx-android.manifest.json", text)
        self.assertIn("build-provenance", text)


if __name__ == "__main__":
    unittest.main()
