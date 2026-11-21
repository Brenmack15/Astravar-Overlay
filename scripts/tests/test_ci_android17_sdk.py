"""Run SDK preparation against fake host tools; missing runtime libraries fail closed."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'ci-android17-sdk.sh'


class Android17RuntimeTest(unittest.TestCase):
    def run_fixture(self, ready=False, other_missing=False, launcher_failure=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        sdk = root/'sdk'
        (sdk/'emulator').mkdir(parents=True)
        (sdk/'emulator/package.xml').write_text('<repository><localPackage><revision><major>37</major><minor>3</minor><micro>10</micro></revision></localPackage></repository>')
        image = sdk/'system-images/android-37.0/default/x86_64/package.xml'
        image.parent.mkdir(parents=True)
        image.write_text('<package/>')
        binary = root/'bin'
        binary.mkdir()
        tools = {
            'ldd': '''if [ "$TEST_OTHER_MISSING" = true ]; then
echo 'libunexpected.so => not found'
elif [ -f "$TEST_ROOT/pulse-installed" ]; then
echo 'libpulse.so.0 => /usr/lib/x86_64-linux-gnu/libpulse.so.0'
else
echo 'libpulse.so.0 => not found'
fi
''',
            'sudo': 'exec "$@"\n',
            'apt-get': '''printf '%s\\n' "$*" >>"$TEST_ROOT/packages.log"
if [ "$1" = install ]; then
  [ "$*" = 'install --no-install-recommends -y libpulse0' ] || exit 1
  touch "$TEST_ROOT/pulse-installed"
fi
''',
            'sdkmanager': 'echo UnexpectedSDKDownload >&2; exit 1\n',
        }
        for name, body in tools.items():
            path = binary/name
            path.write_text('#!/bin/sh\nset -eu\n'+body)
            path.chmod(0o755)
        emulator = sdk/'emulator/emulator'
        emulator.write_text('#!/bin/sh\nset -eu\ntest "$1" = -version\n'
                            'if [ "$TEST_LAUNCHER_FAILURE" = true ]; then echo RuntimeLibraryMissing >&2; exit 127; fi\n'
                            'touch "$TEST_ROOT/version-verified"\n')
        emulator.chmod(0o755)
        if ready:
            (root/'pulse-installed').touch()
        env = dict(os.environ, ANDROID_HOME=str(sdk), TEST_ROOT=str(root),
                   TEST_OTHER_MISSING='true' if other_missing else 'false',
                   TEST_LAUNCHER_FAILURE='true' if launcher_failure else 'false',
                   PATH=str(binary)+os.pathsep+os.environ['PATH'])
        result = subprocess.run(['bash', str(SCRIPT)], cwd=root, env=env, capture_output=True, text=True)
        return result, root

    def test_installs_observed_missing_pulse_then_verifies_version(self):
        result, root = self.run_fixture()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['update', 'install --no-install-recommends -y libpulse0'], (root/'packages.log').read_text().splitlines())
        self.assertTrue((root/'version-verified').exists())
        self.assertNotIn('not found', (root/'validation/android17-emulator-libraries.txt').read_text())

    def test_existing_runtime_and_image_require_no_downloads(self):
        result, root = self.run_fixture(ready=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((root/'packages.log').exists())
        self.assertTrue((root/'version-verified').exists())

    def test_raw_ldd_bundled_library_miss_is_not_a_launcher_failure(self):
        result, root = self.run_fixture(other_missing=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('libunexpected.so => not found', (root/'validation/android17-emulator-libraries.txt').read_text())
        self.assertTrue((root/'version-verified').exists())
        self.assertFalse((root/'packages.log').exists())

    def test_actual_launcher_failure_stops_preparation(self):
        result, root = self.run_fixture(ready=True, launcher_failure=True)
        self.assertEqual(127, result.returncode)
        self.assertIn('RuntimeLibraryMissing', result.stderr)
        self.assertFalse((root/'version-verified').exists())
        self.assertTrue((root/'validation/android17-emulator-libraries.txt').exists())


if __name__ == '__main__':
    unittest.main()
