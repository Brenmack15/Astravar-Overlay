"""Strict bounded sanitizer for synthetic native RGB/RGBA PNG evidence.

Only the PNG signature and validated IHDR/IDAT/IEND chunks are retained. Callers
checking an already-public file must require ``sanitize_png(data) == data``.
This is a file-format/publication boundary, not a claim about screenshot content.
"""
import struct
import zlib


MAX_PNG_BYTES = 16 * 1024 * 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def sanitize_png(data: bytes) -> bytes:
    """Strip ancillary metadata or raise ValueError for unsupported/invalid PNG.

    Native screenshots must be non-interlaced, eight-bit RGB/RGBA, with sides
    between 100 and 4096 pixels. Compressed input is bounded to 16 MiB and the
    decompressor is capped at the exact expected pixel length plus one byte.
    """
    if not isinstance(data, bytes) or not 32 < len(data) <= MAX_PNG_BYTES:
        raise ValueError("PNG input size/type is invalid")
    if data[:16] != PNG_SIGNATURE + b"\x00\x00\x00\rIHDR":
        raise ValueError("PNG requires an initial 13-byte IHDR")
    width, height, depth, color, compression, filtering, interlace = struct.unpack(
        ">IIBBBBB", data[16:29])
    if not (100 <= width <= 4096 and 100 <= height <= 4096):
        raise ValueError("PNG dimensions are outside native evidence bounds")
    if depth != 8 or color not in (2, 6) or compression or filtering or interlace:
        raise ValueError("PNG must be non-interlaced eight-bit RGB/RGBA")

    offset = 8
    cleaned = bytearray(PNG_SIGNATURE)
    compressed = bytearray()
    seen_header = seen_data = data_ended = ended = False
    while offset + 12 <= len(data):
        start = offset
        size = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        offset += size + 12
        if offset > len(data):
            raise ValueError("PNG chunk extends past the input")
        if any(not (65 <= byte <= 90 or 97 <= byte <= 122) for byte in kind) or kind[2] & 0x20:
            raise ValueError("PNG chunk type/reserved bit is invalid")
        if zlib.crc32(data[start + 4:offset - 4]) != struct.unpack(">I", data[offset - 4:offset])[0]:
            raise ValueError("PNG chunk CRC is invalid")
        if kind == b"IHDR":
            if seen_header or start != 8 or size != 13:
                raise ValueError("PNG has a duplicate or misplaced IHDR")
            seen_header = True
        elif kind == b"IDAT":
            if not seen_header or data_ended:
                raise ValueError("PNG IDAT chunks must be consecutive after IHDR")
            seen_data = True
            compressed.extend(data[start + 8:offset - 4])
        elif kind == b"IEND":
            if not seen_header or not seen_data or size != 0 or offset != len(data):
                raise ValueError("PNG requires exactly one final empty IEND")
            ended = True
        else:
            if not seen_header or not kind[0] & 0x20:
                raise ValueError("Unsupported critical PNG chunk")
            if seen_data:
                data_ended = True
        if kind in (b"IHDR", b"IDAT", b"IEND"):
            cleaned.extend(data[start:offset])
        if ended:
            break
    if not ended:
        raise ValueError("PNG is missing its complete IEND")

    stride = 1 + width * (3 if color == 2 else 4)
    expected = height * stride
    decoder = zlib.decompressobj()
    try:
        pixels = decoder.decompress(compressed, expected + 1)
    except zlib.error as error:
        raise ValueError("PNG pixel stream is invalid") from error
    if len(pixels) != expected or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("PNG pixel stream length or end is invalid")
    if any(filter_type > 4 for filter_type in pixels[::stride]):
        raise ValueError("PNG scanline filter is invalid")
    return bytes(cleaned)
