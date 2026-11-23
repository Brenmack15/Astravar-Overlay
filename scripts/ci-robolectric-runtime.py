"""Prime Robolectric's Maven cache with the two Android versions selected by @Config."""
import argparse
import hashlib
from pathlib import Path
import shutil
import tempfile
from urllib.request import Request, urlopen


COORDINATE = "org/robolectric/android-all-instrumented"
REPOSITORIES = (
    "https://maven-central.storage-download.googleapis.com/maven2",
    "https://repo.maven.apache.org/maven2",
)
# Published SHA-512 digests for each JAR and POM, independently checked against the mirror.
PINNED = {
    "12.1-robolectric-8229987-i7": {
        "jar": "747d403cd1e80a898a9ffb917920ce094216312b3dabaa312410e100647102b4141d2fbd1efca32251308e713fd69dca72c0971f93f7d6f1247d19a13cbac4ef",
        "pom": "6593aa91a6d1cf671c8bc0217615150a56b865b288552d5eda6c6a36888ed14284531ad2827b8f8a02148556fd622a5394f964342243014a44dc19aeab0feca9",
    },
    "15-robolectric-13954326-i7": {
        "jar": "5fc64ffccf4788154162686b3966ddd63abea8326542c465e2a7f0129fc92770d0e1e8ddcfabd7f1500e491ec02e3b4104b9e51b61fccd9cf08006c9dc02987c",
        "pom": "e16eb42ba12b823ede906bec317f73688c58d05ebc7f6f4c39ff555c08926515aac59dbddddd888b87c85a022d756d6aa1520cc0276e2adee2caf946ad79e659",
    },
}


def digest(file):
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha512").hexdigest()


def fetch(destination, relative, expected):
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
        staging = Path(temporary) / destination.name
        for repository in REPOSITORIES:
            try:
                request = Request(f"{repository}/{relative}", headers={"User-Agent": "Astravar-Robolectric-CI"})
                with urlopen(request, timeout=90) as source, staging.open("wb") as output:
                    shutil.copyfileobj(source, output)
                if digest(staging) != expected:
                    raise ValueError("checksum mismatch")
                staging.replace(destination)
                return
            except (OSError, ValueError) as error:
                print(f"{destination.name} from {repository}: {error}", flush=True)
    raise RuntimeError(f"Could not verify {destination.name} from either repository")


def prepare(repository):
    for version, artifacts in PINNED.items():
        for extension, expected in artifacts.items():
            relative = f"{COORDINATE}/{version}/android-all-instrumented-{version}.{extension}"
            destination = repository / relative
            if not destination.is_file() or digest(destination) != expected:
                fetch(destination, relative, expected)
            destination.with_suffix(destination.suffix + ".sha512").write_text(expected, encoding="ascii")
            print(f"Verified {destination.name}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=Path.home() / ".m2" / "repository")
    prepare(parser.parse_args().repository)
