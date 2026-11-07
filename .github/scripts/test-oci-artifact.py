#!/usr/bin/env python3
"""Test the allowed paths for OCI archives."""

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("oci-artifact.py")
SPEC = importlib.util.spec_from_file_location("oci_artifact", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class OciArchivePathTests(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)

        self.base = Path(self.temp.name).resolve()
        self.first = self.base / "cloudforge-image.tar"
        self.second = (
            self.base / "validated-image" / "cloudforge-image.tar"
        )

        self.second.parent.mkdir()
        self.first.write_bytes(b"test")
        self.second.write_bytes(b"test")

        environment = patch.dict(
            os.environ, {"RUNNER_TEMP": str(self.base)}
        )
        environment.start()
        self.addCleanup(environment.stop)

    def test_build_archive_is_allowed(self):
        self.assertEqual(
            MODULE.validate_archive_path(self.first),
            self.first,
        )

    def test_transferred_archive_is_allowed(self):
        self.assertEqual(
            MODULE.validate_archive_path(self.second),
            self.second,
        )

    def test_unexpected_archive_is_rejected(self):
        unexpected = self.base / "unexpected.tar"
        unexpected.write_bytes(b"test")

        with self.assertRaises(ValueError):
            MODULE.validate_archive_path(unexpected)

    def test_symlink_archive_is_rejected(self):
        self.first.unlink()
        self.first.symlink_to(self.second)

        with self.assertRaises(ValueError):
            MODULE.validate_archive_path(self.first)

    def test_symlink_parent_is_rejected(self):
        real_directory = self.base / "real-directory"
        real_directory.mkdir()

        real_archive = real_directory / "cloudforge-image.tar"
        real_archive.write_bytes(b"test")

        # Remove the file before replacing its directory with a symlink.
        self.second.unlink()
        self.second.parent.rmdir()
        self.second.parent.symlink_to(
            real_directory,
            target_is_directory=True,
        )

        with self.assertRaises(ValueError):
            MODULE.validate_archive_path(self.second)

    def test_missing_runner_temp_is_rejected(self):
        with patch.dict(os.environ, {"RUNNER_TEMP": ""}):
            with self.assertRaises(ValueError):
                MODULE.validate_archive_path(self.first)


if __name__ == "__main__":
    unittest.main()