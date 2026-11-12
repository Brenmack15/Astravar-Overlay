"""Synthetic publication-boundary regressions; no APK or device access."""
import importlib.util
from pathlib import Path
import struct
import unittest
import zlib


SPEC = importlib.util.spec_from_file_location(
    "owner_upgrade_png", Path(__file__).resolve().parents[1] / "owner_upgrade_png.py")
PNG = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PNG)


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


class OwnerUpgradePngTest(unittest.TestCase):
    def setUp(self):
        self.prefix = PNG.PNG_SIGNATURE
        self.header = chunk(b"IHDR", struct.pack(">IIBBBBB", 100, 100, 8, 2, 0, 0, 0))
        self.pixels = b"".join(b"\0" + b"\0" * 300 for _ in range(100))
        self.compressed = zlib.compress(self.pixels)
        self.data = chunk(b"IDAT", self.compressed)
        self.end = chunk(b"IEND", b"")
        self.normal = self.prefix + self.header + self.data + self.end

    def test_valid_native_png_is_unchanged_and_idempotent(self):
        rgba_header = chunk(b"IHDR", struct.pack(">IIBBBBB", 100, 100, 8, 6, 0, 0, 0))
        rgba_pixels = b"".join(b"\0" + b"\0" * 400 for _ in range(100))
        rgba = self.prefix + rgba_header + chunk(b"IDAT", zlib.compress(rgba_pixels)) + self.end
        for image in (self.normal, rgba):
            with self.subTest(color_type=image[25]):
                self.assertEqual(PNG.sanitize_png(image), image)
                self.assertEqual(PNG.sanitize_png(PNG.sanitize_png(image)), image)

    def test_ancillary_payload_is_stripped_and_fails_canonical_publication_check(self):
        original = self.prefix + self.header + chunk(b"tEXt", b"PK\x03\x04PRIVATE_PAYLOAD") + self.data + self.end
        cleaned = PNG.sanitize_png(original)
        self.assertEqual(cleaned, self.normal)
        self.assertNotEqual(cleaned, original)
        self.assertNotIn(b"PRIVATE_PAYLOAD", cleaned)

    def test_appended_plaintext_payload_is_rejected(self):
        with self.assertRaises(ValueError):
            PNG.sanitize_png(self.normal + b"PK\x03\x04PRIVATE_APK_SUFFIX")

    def test_second_crc_valid_ihdr_cannot_carry_a_payload(self):
        for header in (chunk(b"IHDR", b"PK\x03\x04PRIVATE_PAYLOAD"), self.header):
            with self.subTest(header=header), self.assertRaises(ValueError):
                PNG.sanitize_png(self.prefix + self.header + header + self.data + self.end)

    def test_missing_idat_is_rejected(self):
        with self.assertRaises(ValueError):
            PNG.sanitize_png(self.prefix + self.header + self.end)

    def test_second_or_nonempty_iend_is_rejected(self):
        for ending in (self.end + self.end, chunk(b"IEND", b"payload")):
            with self.subTest(ending=ending), self.assertRaises(ValueError):
                PNG.sanitize_png(self.prefix + self.header + self.data + ending)

    def test_nonconsecutive_idat_is_rejected(self):
        broken = (self.prefix + self.header + chunk(b"IDAT", self.compressed[:3]) +
                  chunk(b"tEXt", b"metadata") + chunk(b"IDAT", self.compressed[3:]) + self.end)
        with self.assertRaises(ValueError):
            PNG.sanitize_png(broken)
        consecutive = (self.prefix + self.header + chunk(b"IDAT", self.compressed[:3]) +
                       chunk(b"IDAT", self.compressed[3:]) + self.end)
        self.assertEqual(PNG.sanitize_png(consecutive), consecutive)

    def test_unknown_critical_chunk_is_rejected(self):
        with self.assertRaises(ValueError):
            PNG.sanitize_png(self.prefix + self.header + chunk(b"ABCD", b"PRIVATE_PAYLOAD") + self.data + self.end)

    def test_corrupt_crc_or_truncated_chunk_is_rejected(self):
        for corrupt in (self.normal[:-1], self.normal[:-1] + bytes([self.normal[-1] ^ 1])):
            with self.subTest(corrupt=corrupt), self.assertRaises(ValueError):
                PNG.sanitize_png(corrupt)

    def test_bounded_pixels_reject_extra_stream_bytes_and_inflated_length(self):
        for compressed in (self.compressed + b"PRIVATE_PAYLOAD", zlib.compress(self.pixels + b"extra")):
            with self.subTest(compressed=compressed), self.assertRaises(ValueError):
                PNG.sanitize_png(self.prefix + self.header + chunk(b"IDAT", compressed) + self.end)

    def test_size_dimensions_format_and_scanline_filters_are_bounded(self):
        with self.assertRaises(ValueError):
            PNG.sanitize_png(b"x" * (PNG.MAX_PNG_BYTES + 1))
        for width, depth, color, interlace in ((4097, 8, 2, 0), (99, 8, 2, 0),
                                             (100, 16, 2, 0), (100, 8, 3, 0), (100, 8, 2, 1)):
            header = chunk(b"IHDR", struct.pack(">IIBBBBB", width, 100, depth, color, 0, 0, interlace))
            with self.subTest(width=width, depth=depth, color=color, interlace=interlace), self.assertRaises(ValueError):
                PNG.sanitize_png(self.prefix + header + self.data + self.end)
        wrong_filter = zlib.compress(b"\xff" + self.pixels[1:])
        with self.assertRaises(ValueError):
            PNG.sanitize_png(self.prefix + self.header + chunk(b"IDAT", wrong_filter) + self.end)


if __name__ == "__main__":
    unittest.main()
