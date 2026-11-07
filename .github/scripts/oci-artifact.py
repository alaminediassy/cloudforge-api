#!/usr/bin/env python3
"""Check the archive hash and OCI digest separately, without extracting files.

Revision labels identify the expected source; they are not signed provenance.
"""
import hashlib
import json
import os
import re
import sys
import tarfile


def inspect(path, revision, source, expected_archive=None, expected_manifest=None):
    archive_hash = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            archive_hash.update(chunk)
    digest = archive_hash.hexdigest()
    if expected_archive and digest != expected_archive:
        raise ValueError('Archive checksum mismatch')
    with tarfile.open(path) as archive:
        names = archive.getnames()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate archive members')
        def read(name):
            member = archive.getmember(name)
            if not member.isfile():
                raise ValueError('Expected regular OCI file')
            return archive.extractfile(member).read()
        def blob(descriptor):
            value = descriptor['digest']
            if not re.fullmatch(r'sha256:[0-9a-f]{64}', value):
                raise ValueError('Unsupported digest')
            data = read('blobs/sha256/' + value[7:])
            if len(data) != descriptor['size'] or hashlib.sha256(data).hexdigest() != value[7:]:
                raise ValueError('OCI blob checksum mismatch')
            return data
        index = json.loads(read('index.json'))
        if len(index['manifests']) != 1:
            raise ValueError('Expected exactly one platform image')
        descriptor = index['manifests'][0]
        manifest_digest = descriptor['digest']
        manifest = json.loads(blob(descriptor))
        if manifest['mediaType'] != 'application/vnd.oci.image.manifest.v1+json':
            raise ValueError('Expected OCI image manifest')
        config = json.loads(blob(manifest['config']))
        if config.get('os') != 'linux' or config.get('architecture') != 'amd64':
            raise ValueError('Unexpected image platform')
        labels = config.get('config', {}).get('Labels', {})
        if labels.get('org.opencontainers.image.revision') != revision or labels.get('org.opencontainers.image.source') != source:
            raise ValueError('Image provenance mismatch')
        for layer in manifest['layers']:
            blob(layer)
        if expected_manifest and manifest_digest != expected_manifest:
            raise ValueError('Manifest identity mismatch')
    return digest, manifest_digest


if __name__ == '__main__':
    try:
        checksum, manifest = inspect(sys.argv[1], os.environ['GITHUB_SHA'],
                'https://github.com/' + os.environ['GITHUB_REPOSITORY'],
                os.environ.get('ARCHIVE_SHA256'), os.environ.get('MANIFEST_DIGEST'))
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'archive_sha256={checksum}\nmanifest_digest={manifest}\n')
        print('OCI archive, manifest, config, layers and source revision verified.')
    except Exception:
        sys.exit('OCI artifact verification failed; publication denied.')
