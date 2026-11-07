#!/usr/bin/env python3
"""Local checks only: no registry calls, publication or credentials."""
import importlib.util
import sys
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('publication', Path(__file__).with_name('publish-image.py'))
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)


class HistoryTests(unittest.TestCase):
    old, new, divergent = '1' * 40, '2' * 40, '3' * 40

    def ancestor(self, old, new):
        return (old, new) == (self.old, self.new)

    def test_forward(self):
        publication.require_forward(self.old, self.new, self.ancestor)

    def test_same_revision(self):
        publication.require_forward(self.new, self.new, self.ancestor)

    def test_older_run_after_newer_publication(self):
        with self.assertRaises(ValueError):
            publication.require_forward(self.new, self.old, self.ancestor)

    def test_divergent_history(self):
        with self.assertRaises(ValueError):
            publication.require_forward(self.new, self.divergent, self.ancestor)

    def test_missing_revision(self):
        with self.assertRaises(ValueError):
            publication.require_forward('', self.new, self.ancestor)

    def test_unavailable_history(self):
        def unavailable(old, new):
            raise ValueError('History unavailable')
        with self.assertRaises(ValueError):
            publication.require_forward(self.old, self.new, unavailable)


import hashlib
import json
import os
import subprocess
import urllib.error
import urllib.request
import io
from unittest.mock import patch


def image(revision):
    raw = json.dumps({'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                      'revision': revision}).encode()
    return 'sha256:' + hashlib.sha256(raw).hexdigest(), raw


class PromotionTests(unittest.TestCase):
    old, new, future, divergent = ('1' * 40, '2' * 40, '3' * 40, '4' * 40)

    def setUp(self):
        self.branch = 'develop'
        self.candidate = image(self.new)
        self.tags = {'develop': image(self.old)}
        self.heads = [self.new, self.new]
        self.copies = []
        self.puts = []
        self.fail_head = self.fail_read = self.fail_copy = False
        self.wrong_copy = self.external_change = self.wrong_final = False
        self.timeout_put = False
        self.apply_put = True
        self.mobile_reads = 0

    def run_publication(self):
        outer = self
        class FakeRegistry:
            def __init__(self, *args): pass
            def manifest(self, tag):
                if outer.fail_read: raise TimeoutError('fixture')
                if tag in ('develop', 'latest'):
                    outer.mobile_reads += 1
                    if outer.external_change and outer.mobile_reads == 2:
                        outer.tags[tag] = image(outer.future)
                    if outer.wrong_final and outer.mobile_reads == 3:
                        return image(outer.future)
                return outer.tags.get(tag)
            def revision(self, raw, source): return json.loads(raw)['revision']
            def request(self, suffix, data, method, content_type):
                outer.puts.append(suffix)
                if outer.apply_put:
                    outer.tags[suffix.split('/')[-1]] = ('sha256:' + hashlib.sha256(data).hexdigest(), data)
                if outer.timeout_put: raise TimeoutError('fixture')
                return b''
        def command(args, **kwargs):
            if args[:2] == ['git', 'ls-remote']:
                if self.fail_head: raise subprocess.CalledProcessError(128, args)
                return subprocess.CompletedProcess(args, 0, self.heads.pop(0) + '\trefs/heads/' + self.branch + '\n')
            if args[:2] == ['git', 'merge-base']:
                forward = args[-2:] in ([self.old, self.new], [self.old, self.future], [self.new, self.future])
                return subprocess.CompletedProcess(args, 0 if forward else 1)
            self.assertEqual(args[0:2], ['skopeo', 'copy'])
            self.assertIn('--preserve-digests', args)
            self.assertEqual(args[args.index('--retry-times') + 1], '0')
            self.copies.append(args)
            if self.fail_copy: raise subprocess.CalledProcessError(1, args)
            self.tags[args[-1].split(':')[-1]] = image(self.future) if self.wrong_copy else self.candidate
            return subprocess.CompletedProcess(args, 0)
        environment = {'GITHUB_REPOSITORY':'alaminediassy/cloudforge-api', 'GITHUB_EVENT_NAME':'push',
                       'GITHUB_REF_NAME': self.branch, 'GITHUB_SHA': self.new,
                       'MANIFEST_DIGEST': self.candidate[0], 'GITHUB_ACTOR':'fixture', 'GHCR_TOKEN':'fixture',
                       'REGISTRY_AUTH_FILE':'fixture-auth', 'OCI_ARCHIVE':'fixture.tar'}
        with patch.dict(os.environ, environment), patch.object(publication, 'Registry', FakeRegistry), \
                patch.object(publication.subprocess, 'run', side_effect=command), patch('builtins.print'):
            publication.main()

    def test_forward_develop(self):
        self.run_publication()
        self.assertEqual(self.tags['develop'], self.candidate)
        self.assertEqual(self.tags['develop-sha-' + self.new], self.candidate)
        self.assertNotIn('latest', self.tags)

    def test_main_has_distinct_tags(self):
        self.branch = 'main'
        self.run_publication()
        self.assertEqual(self.tags['latest'], self.candidate)
        self.assertIn('main-sha-' + self.new, self.tags)
        self.assertEqual(self.tags['develop'], image(self.old))

    def test_older_execution_finishes_after_newer(self):
        self.tags['develop'] = image(self.future)
        with self.assertRaises(ValueError): self.run_publication()
        self.assertFalse(self.puts or self.copies)

    def test_divergent_history(self):
        self.tags['develop'] = image(self.divergent)
        with self.assertRaises(ValueError): self.run_publication()
        self.assertFalse(self.puts or self.copies)

    def test_stale_head_before_upload(self):
        self.heads[0] = self.future
        with self.assertRaises(ValueError): self.run_publication()
        self.assertFalse(self.puts or self.copies)

    def test_head_changes_during_upload(self):
        self.heads[1] = self.future
        with self.assertRaises(ValueError): self.run_publication()
        self.assertTrue(self.copies)
        self.assertFalse(self.puts)
        self.assertEqual(self.tags['develop'], image(self.old))

    def test_head_read_failure(self):
        self.fail_head = True
        with self.assertRaises(subprocess.CalledProcessError): self.run_publication()
        self.assertFalse(self.puts or self.copies)

    def test_registry_read_failure(self):
        self.fail_read = True
        with self.assertRaises(TimeoutError): self.run_publication()
        self.assertFalse(self.puts or self.copies)

    def test_same_digest_rerun_is_idempotent(self):
        self.tags['develop'] = self.candidate
        self.tags['develop-sha-' + self.new] = self.candidate
        self.run_publication()
        self.assertFalse(self.puts or self.copies)

    def test_same_commit_different_digest_is_rejected(self):
        self.tags['develop'] = ('sha256:' + 'a' * 64, self.candidate[1])
        with self.assertRaises(ValueError): self.run_publication()
        self.assertFalse(self.puts or self.copies)

    def test_conflicting_version_tag_is_rejected(self):
        self.tags['develop-sha-' + self.new] = image(self.future)
        with self.assertRaises(ValueError): self.run_publication()
        self.assertFalse(self.puts or self.copies)

    def test_copy_failure_does_not_promote(self):
        self.fail_copy = True
        with self.assertRaises(subprocess.CalledProcessError): self.run_publication()
        self.assertFalse(self.puts)

    def test_changed_digest_does_not_promote(self):
        self.wrong_copy = True
        with self.assertRaises(ValueError): self.run_publication()
        self.assertFalse(self.puts)

    def test_external_writer_detected_before_promotion(self):
        self.external_change = True
        with self.assertRaises(ValueError): self.run_publication()
        self.assertFalse(self.puts)

    def test_timeout_can_leave_remote_tag_changed(self):
        self.timeout_put = True
        with self.assertRaises(TimeoutError): self.run_publication()
        self.assertEqual(self.tags['develop'], self.candidate)
        self.assertEqual(len(self.puts), 1)  # No automatic retry, rollback or success declaration.

    def test_timeout_can_leave_remote_tag_unchanged(self):
        self.timeout_put = True
        self.apply_put = False
        with self.assertRaises(TimeoutError): self.run_publication()
        self.assertEqual(self.tags['develop'], image(self.old))
        self.assertEqual(len(self.puts), 1)

    def test_failed_readback_does_not_declare_success(self):
        self.wrong_final = True
        with self.assertRaises(ValueError): self.run_publication()
        self.assertEqual(self.tags['develop'], self.candidate)

    def test_resume_after_version_upload_without_mobile_promotion(self):
        self.tags['develop-sha-' + self.new] = self.candidate
        self.run_publication()
        self.assertFalse(self.copies)
        self.assertEqual(self.tags['develop'], self.candidate)


class RegistryTests(unittest.TestCase):
    def registry(self):
        return object.__new__(publication.Registry)

    def test_only_explicit_manifest_absence_can_bootstrap(self):
        error = urllib.error.HTTPError('https://ghcr.io', 404, 'fixture', {},
                io.BytesIO(b'{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'))
        with patch.object(publication.Registry, 'request', side_effect=[error, b'{"tags":[]}']):
            self.assertIsNone(self.registry().manifest('latest'))

    def test_missing_package_is_not_an_empty_tag(self):
        error = urllib.error.HTTPError('https://ghcr.io', 404, 'fixture', {},
                io.BytesIO(b'{"errors":[{"code":"NAME_UNKNOWN"}]}'))
        with patch.object(publication.Registry, 'request', side_effect=error):
            with self.assertRaises(ValueError): self.registry().manifest('latest')

    def test_auth_failure_is_not_an_empty_tag(self):
        error = urllib.error.HTTPError('https://ghcr.io', 403, 'fixture', {}, io.BytesIO(b''))
        with patch.object(publication.Registry, 'request', side_effect=error):
            with self.assertRaises(urllib.error.HTTPError): self.registry().manifest('latest')

    def test_redirect_drops_registry_credentials(self):
        request = urllib.request.Request('https://ghcr.io/blob', headers={'Authorization':'Bearer fixture'})
        redirected = publication.SafeRedirect().redirect_request(request, None, 302, '', {}, 'https://storage.example.test/blob')
        self.assertIsNone(redirected.get_header('Authorization'))

    def test_promotion_redirect_is_not_followed(self):
        request = urllib.request.Request('https://ghcr.io/manifest', data=b'{}', method='PUT')
        with self.assertRaises(ValueError):
            publication.SafeRedirect().redirect_request(request, None, 307, '', {}, 'https://storage.example.test/manifest')


if __name__ == '__main__':
    unittest.main()
