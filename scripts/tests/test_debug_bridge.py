"""Host tests for automation failure modes; no Android device required."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


class DebugBridgeTest(unittest.TestCase):
    def bash(self, code, *args):
        return subprocess.run(
            ["bash", "-c", code, "bridge-test", *map(str, args)],
            text=True, capture_output=True, timeout=15,
        )

    def test_remote_shell_preserves_path_metacharacters(self):
        path = "/games/RDR's ($USER) `false`; literal.iso"
        result = self.bash('''
            set -euo pipefail
            source "$1/lib/debug-bridge.sh"
            SERIAL=test
            # Execute exactly the remote shell string, replacing the transport.
            timeout() { shift 5; sh -c "$1"; }
            bridge_shell printf '%s' "$2"
        ''', SCRIPTS, path)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, path)

    def test_stale_or_press_only_log_cannot_acknowledge_release(self):
        for line in (
            'BUTTON START release request_id=old',
            'BUTTON START press 120ms request_id=current',
        ):
            with self.subTest(line=line), tempfile.TemporaryDirectory() as tmp:
                log = Path(tmp) / "log"
                log.write_text(line + "\n")
                result = self.bash('''
                    source "$1/lib/debug-bridge.sh"
                    BRIDGE_LOG="$2" REQUEST_ID=current
                    bridge_wait 'BUTTON START release' 1
                ''', SCRIPTS, log)
                self.assertNotEqual(result.returncode, 0)

    def test_matching_release_acknowledges_delivery(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "log"
            log.write_text('BUTTON START release request_id=current\n')
            result = self.bash('''
                source "$1/lib/debug-bridge.sh"
                BRIDGE_LOG="$2" REQUEST_ID=current
                bridge_wait 'BUTTON START release' 1
            ''', SCRIPTS, log)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_invalid_raw_input_fails_before_device_access(self):
        for args in (
            ['--ei', 'lx', '256'], ['--ei', 'd1', '-1'],
            ['--ei', 'unknown', '0'], ['--es', 'lx', '0'],
            ['--ei', 'lx', '0;false'],
        ):
            with self.subTest(args=args):
                result = subprocess.run(
                    [str(SCRIPTS / 'debug-pad.sh'), 'absent-device', '--raw', *args],
                    text=True, capture_output=True, timeout=5,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('device', result.stderr.lower())

    def test_stop_script_rejects_extra_arguments(self):
        result = subprocess.run(
            [str(SCRIPTS / 'debug-stop-game.sh'), 'one', 'two'],
            text=True, capture_output=True, timeout=5,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn('Usage:', result.stderr)


if __name__ == '__main__':
    unittest.main()
