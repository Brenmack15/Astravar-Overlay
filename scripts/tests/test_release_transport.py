"""Package-level migration checks reject forbidden merged APK content."""
import importlib.util
from pathlib import Path
import struct
import unittest

SPEC = importlib.util.spec_from_file_location(
    "inspect_release_apk", Path(__file__).resolve().parents[1] / "inspect_release_apk.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseTransportTests(unittest.TestCase):
    def dex(self, defined, referenced=()):
        names = list(defined) + list(referenced)
        string_offset, type_offset = 112, 112 + len(names) * 4
        class_offset = type_offset + len(names) * 4
        data = bytearray(class_offset + len(defined) * 32)
        data[:8] = b'dex\n039\0'
        struct.pack_into('<IIII', data, 56, len(names), string_offset, len(names), type_offset)
        struct.pack_into('<II', data, 96, len(defined), class_offset)
        for index, name in enumerate(names):
            struct.pack_into('<I', data, string_offset + index * 4, len(data))
            struct.pack_into('<I', data, type_offset + index * 4, index)
            data.extend(bytes([len(name)]) + name.encode() + b'\0')
        for index in range(len(defined)):
            struct.pack_into('<I', data, class_offset + index * 32, index)
        return bytes(data)

    def test_existing_overlay_permission_and_optional_socket_are_allowed(self):
        result = release.verify_push_transport(
            'android.permission.FOREGROUND_SERVICE_SPECIAL_USE me.pushy.sdk.services.PushySocketService',
            ':string/app_name:', [self.dex(['Lcom/yazsras/astravar/MainActivity;'])])
        self.assertTrue(result['firebase_components_absent'])

    def test_retired_provider_or_special_access_in_merged_manifest_is_rejected(self):
        for value in ('com.google.firebase.provider.FirebaseInitProvider',
                      'com.google.android.c2dm.permission.RECEIVE',
                      'android.permission.SCHEDULE_EXACT_ALARM',
                      'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
                      'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
                      'PushyForegroundService'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.verify_push_transport(value, '', [self.dex(['Lapp/Activity;'])])

    def test_retired_client_configuration_is_rejected_after_resource_path_minification(self):
        with self.assertRaises(ValueError):
            release.verify_push_transport('', 'resource 0x7f0b0004 pkg:string/google_api_key:',
                                          [self.dex(['Lapp/Activity;'])])

    def test_retired_provider_dex_types_are_rejected(self):
        with self.assertRaises(ValueError):
            release.verify_push_transport('', '', [self.dex(['Lcom/google/firebase/FirebaseApp;'])])

    def test_optional_unbundled_fcm_reference_is_not_misreported_as_a_dependency(self):
        result = release.verify_push_transport('', '', [self.dex(['Lme/pushy/sdk/Pushy;'],
                                                ['Lcom/google/firebase/FirebaseApp;'])])
        self.assertTrue(result['firebase_dex_types_absent'])

    def test_invalid_dex_does_not_pass_package_audit(self):
        with self.assertRaises(ValueError):
            release.verify_push_transport('', '', [b'dex\n'])
