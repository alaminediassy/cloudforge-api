#!/usr/bin/env python3
"""Reject missing or failed required test reports and missing JaCoCo coverage."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def verify(target):
    required = {
        "io.cloudforge.identity.api.SecurityHttpTest",
        "io.cloudforge.identity.infrastructure.persistence.OtpRecoveryIntegrationTest",
        "io.cloudforge.identity.infrastructure.notification.SmtpEmailProviderTest",
    }
    reports = sorted((target / "surefire-reports").glob("TEST-*.xml"))
    if not reports:
        raise ValueError("Surefire reports are missing")
    executed = 0
    for path in reports:
        suite = ET.parse(path).getroot()
        count = int(suite.get("tests", "0"))
        if any(int(suite.get(field, "0")) for field in ("failures", "errors", "skipped")):
            raise ValueError("A test failed, errored or was skipped")
        if count > 0:
            required.discard(suite.get("name"))
        executed += count
    if required or executed == 0:
        raise ValueError("Mandatory security, OTP or SMTP tests did not execute")
    coverage = ET.parse(target / "site/jacoco/jacoco.xml").getroot()
    lines = coverage.find("counter[@type='LINE']")
    if lines is None or int(lines.get("covered", "0")) <= 0:
        raise ValueError("JaCoCo line coverage is missing or empty")
    if not (target / "classes").is_dir() or not (target / "test-classes").is_dir():
        raise ValueError("Compiled classes required by SonarCloud are missing")
    print(f"Validated {executed} tests and JaCoCo XML; SonarCloud Quality Gate is a separate required check.")


if __name__ == "__main__":
    try:
        verify(Path("target"))
    except (ValueError, OSError, ET.ParseError):
        # Do not print XML payloads: test reports may contain application data.
        print("::error::Required test/coverage inputs are missing, invalid, failed or skipped.", file=sys.stderr)
        sys.exit(1)
