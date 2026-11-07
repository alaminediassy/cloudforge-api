#!/usr/bin/env python3
"""Run in the branch publication concurrency group. Stop when registry reads are uncertain."""
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

MEDIA = 'application/vnd.oci.image.manifest.v1+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json'


def require_forward(previous, candidate, ancestor):
    if not re.fullmatch('[0-9a-f]{40}', previous):
        raise ValueError('Unverifiable published revision')
    if previous != candidate and not ancestor(previous, candidate):
        raise ValueError('Older or divergent revision')


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if req.get_method() != 'GET' or urllib.parse.urlparse(newurl).scheme != 'https':
            raise ValueError('Unexpected registry redirect')
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if urllib.parse.urlparse(req.full_url).netloc != urllib.parse.urlparse(newurl).netloc:
            redirected.remove_header('Authorization')
        return redirected


class Registry:
    def __init__(self, repository, actor, password):
        self.http = urllib.request.build_opener(SafeRedirect())
        self.base = 'https://ghcr.io/v2/' + repository
        auth = base64.b64encode((actor + ':' + password).encode()).decode()
        url = 'https://ghcr.io/token?' + urllib.parse.urlencode(
                {'service': 'ghcr.io', 'scope': 'repository:' + repository + ':pull,push'})
        request = urllib.request.Request(url, headers={'Authorization': 'Basic ' + auth})
        with self.http.open(request, timeout=30) as response:
            self.token = json.load(response)['token']

    def request(self, suffix, data=None, method='GET', content_type=None):
        headers = {'Authorization': 'Bearer ' + self.token, 'Accept': MEDIA}
        if content_type:
            headers['Content-Type'] = content_type
        request = urllib.request.Request(self.base + suffix, data=data, headers=headers, method=method)
        with self.http.open(request, timeout=60) as response:
            return response.read()

    def manifest(self, reference):
        try:
            raw = self.request('/manifests/' + reference)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            body = json.loads(error.read())
            if not any(e.get('code') == 'MANIFEST_UNKNOWN' for e in body.get('errors', [])):
                raise ValueError('Ambiguous registry absence')
            # Confirm repository access. An auth error or missing package is not an empty tag.
            self.request('/tags/list?n=1')
            return None
        return 'sha256:' + hashlib.sha256(raw).hexdigest(), raw

    def revision(self, raw, source):
        manifest = json.loads(raw)
        if 'manifests' in manifest:
            images = [m for m in manifest['manifests'] if m.get('platform', {}).get('os') == 'linux'
                      and m.get('platform', {}).get('architecture') == 'amd64']
            if len(images) != 1:
                raise ValueError('Unverifiable platform')
            result = self.manifest(images[0]['digest'])
            if result is None or result[0] != images[0]['digest']:
                raise ValueError('Manifest identity mismatch')
            manifest = json.loads(result[1])
        config = self.request('/blobs/' + manifest['config']['digest'])
        if 'sha256:' + hashlib.sha256(config).hexdigest() != manifest['config']['digest']:
            raise ValueError('Config identity mismatch')
        labels = json.loads(config).get('config', {}).get('Labels', {}) or {}
        if labels.get('org.opencontainers.image.source') != source:
            raise ValueError('Unknown image source')
        return labels.get('org.opencontainers.image.revision', '')


def main():
    repo = os.environ['GITHUB_REPOSITORY']
    branch = os.environ['GITHUB_REF_NAME']
    revision = os.environ['GITHUB_SHA']
    expected = os.environ['MANIFEST_DIGEST']
    if os.environ.get('GITHUB_EVENT_NAME') != 'push' or not re.fullmatch('sha256:[0-9a-f]{64}', expected):
        raise ValueError('Unauthorized event or digest')
    if repo != 'alaminediassy/cloudforge-api' or branch not in ('main', 'develop') or not re.fullmatch('[0-9a-f]{40}', revision):
        raise ValueError('Unauthorized source')
    source = 'https://github.com/' + repo
    mobile = 'latest' if branch == 'main' else 'develop'
    version = branch + '-sha-' + revision
    registry = Registry(repo, os.environ['GITHUB_ACTOR'], os.environ['GHCR_TOKEN'])

    def fresh():
        result = subprocess.run(['git', 'ls-remote', '--exit-code', 'origin', 'refs/heads/' + branch],
                                capture_output=True, text=True, check=True)
        if result.stdout.strip() != revision + '\trefs/heads/' + branch:
            raise ValueError('Remote branch head changed')

    def ancestor(old, new):
        result = subprocess.run(['git', 'merge-base', '--is-ancestor', old, new], capture_output=True)
        if result.returncode not in (0, 1):
            raise ValueError('History unavailable')
        return result.returncode == 0

    fresh()
    previous = registry.manifest(mobile)
    if previous:
        previous_revision = registry.revision(previous[1], source)
        require_forward(previous_revision, revision, ancestor)
        if previous_revision == revision and previous[0] != expected:
            raise ValueError('Same commit already published with another digest')
    existing = registry.manifest(version)
    if existing and existing[0] != expected:
        raise ValueError('Version tag already references another image')
    if not existing:
        subprocess.run(['skopeo', 'copy', '--all', '--preserve-digests', '--retry-times', '0', '--authfile', os.environ['REGISTRY_AUTH_FILE'],
                        'oci-archive:' + os.environ['OCI_ARCHIVE'], 'docker://ghcr.io/' + repo + ':' + version],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    candidate = registry.manifest(version)
    if candidate is None or candidate[0] != expected or registry.revision(candidate[1], source) != revision:
        raise ValueError('Published image identity mismatch')
    # Detect tag changes seen during upload. This check does not lock the registry.
    current = registry.manifest(mobile)
    if (current[0] if current else None) != (previous[0] if previous else None):
        raise ValueError('Mobile tag changed outside this coordinated publication')
    fresh()  # Git can change again after this last check.
    if current is None or current[0] != expected:
        print('Promoting mobile manifest; an interrupted write can leave remote state uncertain.', flush=True)
        registry.request('/manifests/' + mobile, data=candidate[1], method='PUT',
                         content_type=json.loads(candidate[1])['mediaType'])
    result = registry.manifest(mobile)
    if result is None or result[0] != expected:
        raise ValueError('Mobile digest verification failed')
    print('Published verified OCI digest: ' + expected)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        sys.exit('Publication failed or could not be verified. Remote tags may have changed; inspect them before retrying. No automatic retry or rebuild is performed.')
