#!/usr/bin/env python3
"""
Static checks for Kotlin mistakes the compiler reports late and vaguely.

The NDK build and the Kotlin compile are the only real compilers available, and
each of the patterns below cost a full CI cycle to find. They are all cheap to
detect by reading, so they are checked before anything slow runs.

  1. `DisplayMetrics` has no `width` or `height`. Those are on
     `android.util.Point`; the metrics object exposes `widthPixels` /
     `heightPixels`. Written as `metrics.width` this fails to resolve, and the
     compiler reports it as "Unresolved reference 'width'" at the *use* site,
     which reads like a classpath problem rather than a field name.

  2. An `Int` property written straight into a `FloatArray` needs `.toFloat()`.
     This is the kind of thing a code generator emits, and it is invisible until
     the compiler sees it.

  3. A bare (non-`val`) constructor parameter is only in scope for property
     initialisers and `init` blocks. Using one inside a member function body -
     typically a `get() = i[n]` - resolves nothing, and the error lands on the
     use site rather than on the parameter.

  4. An 8-digit hex literal whose top bit is set does not fit an `Int` literal,
     so `const val COLOR = 0xCC2A0845` is inferred as `Long` and every use as a
     colour is a type error.

  5. `DisplayManager.DEFAULT_DISPLAY` does not exist. The constant is on
     `android.view.Display`.

Usage:  python3 .github/scripts/check_kotlin.py
Exit code 0 on success, 1 on any finding.
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOTS = ["app/src/main/java", "app/src/test/java"]

CHECKS: list[tuple[str, re.Pattern]] = [
    (
        "DisplayMetrics has no .width/.height; use widthPixels/heightPixels",
        re.compile(r"\bmetrics\.(width|height)\b(?!\w)"),
    ),
    (
        "DisplayManager.DEFAULT_DISPLAY does not exist; it is on android.view.Display",
        re.compile(r"\bDisplayManager\.DEFAULT_DISPLAY\b"),
    ),
    (
        "8-digit hex const val is inferred as Long because the top bit is set",
        re.compile(r"\bconst\s+val\s+\w+\s*(?::\s*\w+\s*)?=\s*0x[89a-fA-F][0-9a-fA-F]{7}\b"),
    ),
]

# `writeInto` assigns into a FloatArray; an Int-valued property needs narrowing.
INT_INTO_FLOAT_ARRAY = re.compile(
    r"dst\[i(?:\+\+|\])\]\s*=\s*([A-Za-z_]\w*)\s*$", re.M
)
INT_PROPERTIES = {"diffNoiseFloor", "diffStrongThreshold"}


def split_top_level(text: str) -> list[str]:
    """Split a parameter list on commas that are not inside <> or []."""
    parts: list[str] = []
    depth = 0
    cur: list[str] = []
    for ch in text:
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth = max(0, depth - 1)
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
            continue
        cur.append(ch)
    if "".join(cur).strip():
        parts.append("".join(cur))
    return parts


def kotlin_files() -> list[pathlib.Path]:
    out: list[pathlib.Path] = []
    for root in ROOTS:
        p = pathlib.Path(root)
        if p.is_dir():
            out.extend(sorted(p.rglob("*.kt")))
    return out


def main() -> int:
    findings: list[str] = []
    files = kotlin_files()
    if not files:
        print(f"::error::no Kotlin sources found under {', '.join(ROOTS)}")
        return 1

    for path in files:
        text = path.read_text()
        # Drop comments so a doc mentioning the pattern is not a finding.
        body = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        body = re.sub(r"^\s*//.*$", "", body, flags=re.M)
        lines = body.split("\n")

        for label, pat in CHECKS:
            for m in pat.finditer(body):
                line_no = body[: m.start()].count("\n") + 1
                findings.append(f"{path}:{line_no}: {label} -> {lines[line_no-1].strip()[:110]}")

        # 2. Int assigned into a FloatArray without narrowing.
        if "FloatArray" in text and "dst[i" in text:
            for m in INT_INTO_FLOAT_ARRAY.finditer(body):
                name = m.group(1)
                if name in INT_PROPERTIES and ".toFloat()" not in m.group(0):
                    line_no = body[: m.start()].count("\n") + 1
                    findings.append(
                        f"{path}:{line_no}: `{name}` is an Int assigned into a "
                        f"FloatArray; needs .toFloat()"
                    )

        # 3. A bare constructor parameter used inside a member function body.
        #
        # Rather than parse the class hierarchy, which is where the first version
        # of this check went wrong, collect every name the file actually declares
        # as a property and flag any getter that reaches for something else. A
        # bare constructor parameter is a property only when written `val`, so it
        # will not be in the declared set, while a real property always is.
        declared = set(
            re.findall(r"\b(?:private\s+|internal\s+|protected\s+)?(?:val|var)\s+(\w+)", body)
        )
        for m in re.finditer(r"\bget\(\)\s*=\s*[^\n]*?([A-Za-z_]\w*)\s*\[", body):
            name = m.group(1)
            if name in declared:
                continue
            if name in ("it", "this"):
                continue
            line_no = body[: m.start()].count("\n") + 1
            findings.append(
                f"{path}:{line_no}: getter reads `{name}`, which the file never "
                f"declares as a property. A bare constructor parameter is out of "
                f"scope in a function body; declare it `private val`."
            )

    if findings:
        for f in findings:
            print(f"::error::{f}")
        print(f"{len(findings)} Kotlin problem(s) found.")
        return 1

    print(f"OK: {len(files)} Kotlin files clean of the checked mistake classes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
