#!/usr/bin/env python3
"""
Summarise the Gradle unit test results and fail the job if anything failed.

Gradle already fails the task on a test failure, but its console output is
truncated on a CI log, so a red run often gives you a page of stack traces and
no indication of which test actually broke. This prints one line per failure as
a GitHub `::error::` annotation, which shows up directly in the run summary.

Exits 1 when there are failures or errors so the `if: always()` step can still
be the thing that reports it.
"""

from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    root = "app/build/test-results"
    if not os.path.isdir(root):
        print("::warning::no test-results directory; the test task probably never ran")
        return 0

    files = glob.glob(os.path.join(root, "**", "*.xml"), recursive=True)
    if not files:
        print("::warning::no test result XML found")
        return 0

    total = failures = errors = skipped = 0
    broken: list[tuple[str, str, str]] = []

    for f in files:
        try:
            r = ET.parse(f).getroot()
        except ET.ParseError as e:
            print(f"::error::could not parse {f}: {e}")
            return 1

        suite = r.get("name", "?")
        total += int(r.get("tests", 0))
        failures += int(r.get("failures", 0))
        errors += int(r.get("errors", 0))
        skipped += int(r.get("skipped", 0))

        for tc in r.iter("testcase"):
            for bad in list(tc.iter("failure")) + list(tc.iter("error")):
                body = (bad.text or "").strip()
                raw = (bad.get("message") or "").strip()
                # JUnit puts the actionable part on the FIRST line of the
                # message: "expected:<160> but was:<200>". The stack trace below it
                # only says which line failed, which is the half that is already
                # in the test name. Reporting both, with the first line first, is
                # what makes a run diagnosable without the raw log.
                first = raw.splitlines()[0] if raw else ""
                exc = ""
                for line in body.splitlines():
                    ls = line.strip()
                    if ls.startswith(("java.lang.AssertionError",
                                      "org.junit.ComparisonFailure",
                                      "java.lang.NullPointerException",
                                      "java.lang.UnsupportedOperationException")):
                        exc = ls
                        break
                detail = " | ".join(x for x in (exc, first) if x) or "no message"
                broken.append((suite, tc.get("name", "?"), detail))
                print(f"::error::{suite}.{tc.get('name')}::{detail}")
                for line in body.splitlines()[:4]:
                    print(f"    {line}")

    print(
        f"tests={total} failures={failures} errors={errors} skipped={skipped} "
        f"in {len(files)} suite(s)"
    )
    if failures or errors:
        print(f"::error::{failures + errors} test(s) failed")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
