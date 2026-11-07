#!/usr/bin/env python3
"""Check the archive hash and OCI digest without extracting files.

Revision labels identify the expected source.
They are not signed provenance.
"""

import hashlib
import json
import os
import re
import sys
import tarfile
from pathlib import Path


def validate_archive_path(path):
    """Allow only the OCI archive paths used by GitHub Actions."""
    runner_temp = os.environ.get("RUNNER_TEMP")
    if not runner_temp:
        raise ValueError("RUNNER_TEMP is required")

    base = Path(runner_temp).resolve(strict=True)

    allowed_paths = {
        base / "cloudforge-image.tar",
        base / "validated-image" / "cloudforge-image.tar",
    }

    candidate = Path(path)

    # Reject unexpected paths before following symbolic links.
    if candidate.name != "cloudforge-image.tar":
        raise ValueError("Unexpected OCI archive name")

    if candidate.is_symlink():
        raise ValueError("Symbolic links are not allowed")

    archive = candidate.resolve(strict=True)

    # Reject symbolic links in parent directories too.
    if archive not in allowed_paths:
        raise ValueError("Unexpected resolved OCI archive path")

    if not archive.is_file():
        raise ValueError("Expected a regular OCI archive")

    return archive


def inspect(path, revision, source, expected_archive=None, expected_manifest=None):
    path = validate_archive_path(path)

    archive_hash = hashlib.sha256()

    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            archive_hash.update(chunk)

    digest = archive_hash.hexdigest()

    if expected_archive and digest != expected_archive:
        raise ValueError("Archive checksum mismatch")

    with tarfile.open(path, mode="r:") as archive:
        names = archive.getnames()

        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive members")

        def read(name):
            member = archive.getmember(name)

            if not member.isfile():
                raise ValueError("Expected regular OCI file")

            stream = archive.extractfile(member)
            if stream is None:
                raise ValueError("Cannot read OCI archive member")

            return stream.read()

        def blob(descriptor):
            value = descriptor["digest"]

            if not re.fullmatch(r"sha256:[0-9a-f]{64}", value):
                raise ValueError("Unsupported digest")

            data = read("blobs/sha256/" + value[7:])

            if (
                len(data) != descriptor["size"]
                or hashlib.sha256(data).hexdigest() != value[7:]
            ):
                raise ValueError("OCI blob checksum mismatch")

            return data

        index = json.loads(read("index.json"))

        if len(index["manifests"]) != 1:
            raise ValueError("Expected exactly one platform image")

        descriptor = index["manifests"][0]
        manifest_digest = descriptor["digest"]
        manifest = json.loads(blob(descriptor))

        if manifest["mediaType"] != "application/vnd.oci.image.manifest.v1+json":
            raise ValueError("Expected OCI image manifest")

        config = json.loads(blob(manifest["config"]))

        if config.get("os") != "linux" or config.get("architecture") != "amd64":
            raise ValueError("Unexpected image platform")

        labels = config.get("config", {}).get("Labels", {})

        if (
            labels.get("org.opencontainers.image.revision") != revision
            or labels.get("org.opencontainers.image.source") != source
        ):
            raise ValueError("Image provenance mismatch")

        for layer in manifest["layers"]:
            blob(layer)

        if expected_manifest and manifest_digest != expected_manifest:
            raise ValueError("Manifest identity mismatch")

    return digest, manifest_digest


if __name__ == "__main__":
    try:
        checksum, manifest = inspect(
            sys.argv[1],
            os.environ["GITHUB_SHA"],
            "https://github.com/" + os.environ["GITHUB_REPOSITORY"],
            os.environ.get("ARCHIVE_SHA256"),
            os.environ.get("MANIFEST_DIGEST"),
        )

        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(
                f"archive_sha256={checksum}\n"
                f"manifest_digest={manifest}\n"
            )

        print(
            "OCI archive, manifest, config, layers "
            "and source revision verified."
        )

    except Exception:
        sys.exit("OCI artifact verification failed; publication denied.")
