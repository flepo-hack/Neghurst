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

    def balanced_arg(text: str, open_paren: int) -> str:
        """The text between a call's open paren and its matching close paren.

        A regex cannot do this: `dp(6f.roundToInt())` has nested parentheses, so
        a `[^()]*` pattern cannot see inside it, and a comma split of
        `setPadding(0, 0, 0, dp(6f))` trips over dp's own comma-less but
        parenthesised argument. Both mistakes produced silent misses before.
        """
        depth, cur = 0, []
        i = open_paren
        while i < len(text):
            c = text[i]
            if c == "(":
                depth += 1
                if depth == 1:
                    i += 1
                    continue
            elif c == ")":
                depth -= 1
                if depth == 0:
                    return "".join(cur)
            cur.append(c)
            i += 1
        return "".join(cur)

    def top_level_args(text: str, open_paren: int) -> list[str]:
        depth, cur, out = 0, [], []
        i = open_paren
        while i < len(text):
            c = text[i]
            if c == "(":
                depth += 1
                if depth == 1:
                    i += 1
                    continue
            elif c == ")":
                depth -= 1
                if depth == 0:
                    if "".join(cur).strip():
                        out.append("".join(cur).strip())
                    return out
            elif c == "," and depth == 1:
                out.append("".join(cur).strip())
                cur = []
                i += 1
                continue
            cur.append(c)
            i += 1
        return out

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
        # Only a `get() = ...` one-liner. A property INITIALISER may legally
        # reference a bare constructor parameter, so the earlier version of this
        # check - which also scanned `= run { ... }` windows - flagged correct
        # code like `private val appContext = appContext.applicationContext` and
        # would have blocked a legitimate change. A check with false positives on
        # valid Kotlin is worse than no check, because people stop reading it.
        for m in re.finditer(r"\bget\(\)\s*=\s*([^\n]*)", body):
            for um in re.finditer(r"(?<![\w.])([A-Za-z_]\w*)\s*\[", m.group(1)):
                name = um.group(1)
                if name in declared or name in ("it", "this", "int", "float"):
                    continue
                line_no = body[: m.start()].count("\n") + 1
                findings.append(
                    f"{path}:{line_no}: a getter reads `{name}`, which the file "
                    f"never declares as a property. A bare constructor parameter "
                    f"is out of scope in a function body; declare it `private val`."
                )

    # 8. A duplicated declaration on one line. This is the exact shape of the
    #    damage a line-range edit does when the new text re-emits the line the
    #    slice already excluded, and the compiler then reports a dozen unrelated
    #    unresolved references instead of the one real problem.
    # Bounded and lazy between the two occurrences, so an ordinary signature -
    # which contains a parameter list full of `val`s and `fun`s - is not matched.
    # The scan is per line, so two declarations on separate lines never match.
    dup_re = re.compile(r"\b(fun|val|var|class|object)\s+(\w+)\b[^;]{0,140}?\b\1\s+\2\b")
    for path in files:
        for ln, line in enumerate(path.read_text().split("\n"), 1):
            code = re.sub(r"//.*$", "", line)
            m = dup_re.search(code)
            if m:
                findings.append(
                    f"{path}:{ln}: `{m.group(1)} {m.group(2)}` is declared twice on "
                    f"one line, which is the signature of a bad line-range edit"
                )

    # 6. Braces must balance. String surgery on a source file can silently
    #    truncate it - this exact check exists because a line-range edit removed
    #    the last 100 lines of a service and nothing noticed until the compiler
    #    reported a stray brace.
    for path in files:
        body = re.sub(r"/\*.*?\*/", "", path.read_text(), flags=re.S)
        body = re.sub(r'"""(?:.|\n)*?"""', '""', body, flags=re.S)
        body = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', body)
        body = re.sub(r"'(?:\\.|[^'\\\n])'", "''", body)
        depth = 0
        lowest = 0
        for ch in body:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                lowest = min(lowest, depth)
        if depth != 0 or lowest < 0:
            findings.append(
                f"{path}: braces do not balance (final depth {depth}, "
                f"lowest {lowest}); the file is truncated or has a stray brace"
            )

    # 7. Cross-references to VisionTuning's own members, used by the
    #    wire-format test. A companion member referenced from the wrong class
    #    resolves nowhere, and the only report is a wall of "Unresolved
    #    reference" from the test compile. Deliberately narrow: a general
    #    cross-class reference checker produces false positives on `Companion`,
    #    `::class`, enum `entries`, and extensions, and a check that blocks the
    #    build on its own noise is worse than no check.
    tuning_path = pathlib.Path(
        "app/src/main/java/com/example/vision/nativebridge/VisionTypes.kt"
    )
    if tuning_path.exists():
        src = tuning_path.read_text()
        m = re.search(r"data class VisionTuning\((.*?)\n\) \{", src, re.S)
        if not m:
            findings.append("VisionTuning is not found in VisionTypes.kt")
        else:
            start = m.end()
            nxt = re.search(r"\n(?:data class|class|enum class|object)\s+\w+", src[start:])
            body = src[start : start + nxt.start()] if nxt else src[start:]
            declared_tuning = set(
                re.findall(
                    r"\b(?:val|var|fun|const)\s+(?:<[^>]*>\s*)?(\w+)", body
                )
            )
            for path in files:
                for um in re.finditer(
                    r"\bVisionTuning\.(\w+)", re.sub(r"/\*.*?\*/", "", path.read_text(), flags=re.S)
                ):
                    member = um.group(1)
                    if member not in declared_tuning:
                        findings.append(
                            f"{path}: `VisionTuning.{member}` is not declared in "
                            f"VisionTuning. A companion member is only reachable "
                            f"through the class that declares it."
                        )

    # 9. Every field a test looks up by name must actually be on the wire.
    #    `at("x")` in the contract test resolves through WIRE_ORDER, and an
    #    unknown name yields -1, which turns a real assertion into a silent
    #    comparison against garbage. It happened once: a field that had
    #    deliberately been taken off the wire was still asserted.
    tpath = pathlib.Path("app/src/test/java/com/example/vision/NativeVisionEngineContractTest.kt")
    if tpath.exists():
        tests = tpath.read_text()
        kw = set(re.findall(r'val WIRE_ORDER: List<String> = listOf\(\n(.*?)\n        \)',
                            tuning_path.read_text(), re.S)[0].count('"') and
                 re.findall(r'"(\w+)"', re.findall(r'val WIRE_ORDER: List<String> = listOf\(\n(.*?)\n        \)',
                 tuning_path.read_text(), re.S)[0]))
        for name in sorted(set(re.findall(r'at\("(\w+)"\)', tests))):
            if name not in kw:
                findings.append(
                    f"{tpath}: looks up `{name}` on the wire, but it is not in "
                    f"VisionTuning.WIRE_ORDER, so wireIndexOf returns -1 and the "
                    f"assertion compares against garbage"
                )

    # 12. Two numeric type traps around the `dp()` helper, both of which had
    #     already cost a CI round trip in this project.
    #
    #       `setPadding`/`setStroke` take Int and are often given `dp(...)`, which
    #       returns Float.  -> "actual type is Float, but Int was expected"
    #
    #       `dp()` takes Float, so `dp(6f.roundToInt())` is an Int into Float.
    #       A regex normalisation produced exactly that: `dp(6f)` became
    #       `dp(6f.roundToInt())` and a second pass added another around it.
    #
    #     Both need the paren-balanced walk; a `[^()]*` pattern sees neither.
    for path in files:
        src = path.read_text()
        for m in re.finditer(r"\b(setPadding|setStroke)\s*\(", src):
            for arg in top_level_args(src, m.end() - 1):
                if re.fullmatch(r"dp\s*\([\d.]+f\)", arg):
                    line_no = src[: m.start()].count("\n") + 1
                    findings.append(
                        f"{path}:{line_no}: `{m.group(1)}` takes Int and is given "
                        f"`{arg}`, but dp() returns Float. Add .roundToInt()."
                    )
        for m in re.finditer(r"\bdp\s*\(", src):
            arg = balanced_arg(src, m.end() - 1)
            if "roundToInt" in arg or ".toInt()" in arg:
                line_no = src[: m.start()].count("\n") + 1
                findings.append(
                    f"{path}:{line_no}: `dp({arg})` passes an Int to dp(), which "
                    f"takes Float. Narrow at the call site: `dp(6f).roundToInt()`."
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
