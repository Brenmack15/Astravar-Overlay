"""Read-only verification of original JPEG particle art with Android .jpg extensions.

This does not decode, replace, recompress, or alter the artwork.
"""
import hashlib
import json
from pathlib import Path

ELEMENTS = (
    'acid', 'cold', 'fire', 'force', 'lightning',
    'necrotic', 'poison', 'psychic', 'radiant', 'thunder',
)
RESOURCE_DIR = Path('app/src/main/res/drawable-nodpi')
MANIFEST = Path('scripts/ui_foundation_sha256.json')


def inspect(root: Path) -> int:
    root = Path(root)
    manifest = json.loads((root / MANIFEST).read_text(encoding='utf-8'))
    files = manifest['files']
    for element in ELEMENTS:
        jpg = root / RESOURCE_DIR / f'particle_{element}.jpg'
        png = root / RESOURCE_DIR / f'particle_{element}.png'
        assert not png.exists(), f'Legacy mislabeled .png remains: {png}'
        assert jpg.is_file(), f'Missing particle: {jpg}'
        content = jpg.read_bytes()
        assert content.startswith(b'\xff\xd8\xff'), f'Not a JPEG: {jpg}'
        assert content.endswith(b'\xff\xd9'), f'Incomplete JPEG: {jpg}'
        name = jpg.relative_to(root).as_posix()
        actual = hashlib.sha256(content).hexdigest()
        assert files.get(name) == actual, f'Art bytes do not match preserved digest: {name}'
    return len(ELEMENTS)


if __name__ == '__main__':
    count = inspect(Path(__file__).resolve().parents[1])
    print(f'Preserved {count} approved particle resources byte-for-byte; original JPEG extensions verified')
