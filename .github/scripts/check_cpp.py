#!/usr/bin/env python3
"""
Static member/name check for the native sources.

This exists because of a real build break. The JNI bridge referred to
`engine->player()` inside a function whose engine alias was `e`, and to a `Track`
member named `speed` when the field is `speedNorm`. Clang reported both, but
only after a full NDK configure and compile cycle, and only for the first
translation unit it reached - so the same class of mistake could have been hiding
in the other two files.

What it checks, per file:

  * `ptr->member` where `ptr` is a known pointer to a class declared in
    rendera_core.h: the member must exist on that class.
  * `value.member` where `value` is a known local reference or pointer to a
    struct declared in rendera_core.h: the field must exist on that struct.
  * The engine pointer alias used in each function must be the one that function
    actually declares, which is the specific mistake that broke the build.

It is deliberately narrow. It does not attempt to be a C++ parser, and it
reports "clean" for anything it cannot classify rather than guessing.

Usage:  python3 .github/scripts/check_cpp.py
Exit code 0 on success, 1 on any finding.
"""

from __future__ import annotations

import pathlib
import re
import sys

CPP_DIR = pathlib.Path("app/src/main/cpp")
HEADER = CPP_DIR / "rendera_core.h"

# Types the bridge is expected to spell exactly right, and the C++ spelling each
# maps to. The JNI layer refers to them as `rendera::X`.
KNOWN_TYPES = {
    "VisionEngine": "VisionEngine",
    "MotionEstimate": "MotionEstimate",
    "Track": "Track",
    "Blob": "Blob",
    "EnemyMark": "EnemyMark",
    "PlayerState": "PlayerState",
    "ThreatSolution": "ThreatSolution",
    "FrameStats": "FrameStats",
    "MaskRegion": "MaskRegion",
    "EscapePlan": "EscapePlan",
    "TrackKind": "TrackKind",
}

# Files that legitimately reach outside rendera_core.h and are skipped.
SKIP_TYPES = {"Mat", "Exception", "Point2d", "Scalar"}


def strip_comments_and_strings(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)
    return text


def parse_header(text: str) -> dict[str, set[str]]:
    """Map each struct/enum name to the set of its member names."""
    out: dict[str, set[str]] = {}

    for m in re.finditer(r"\bstruct\s+(\w+)\s*\{(.*?)\n\};", text, flags=re.S):
        name, body = m.group(1), m.group(2)
        members: set[str] = set()
        for line in body.split("\n"):
            line = re.sub(r"//.*$", "", line).strip()
            if not line or line.startswith(("/*", "*", "#")):
                continue
            # Skip nested declarations and function prototypes.
            if "(" in line or "struct " in line or "enum " in line:
                continue
            # `int gridW = 200;` and, importantly, the comma separated form
            # `int minX = 0, minY = 0, maxX = 0, maxY = 0;` which declares four
            # members and would otherwise register only the first.
            head = line.rstrip(";").strip()
            if "=" in head and "(" not in head:
                head = head.split("=", 1)[0].strip()
            for decl in head.split(","):
                decl = decl.strip()
                if not decl:
                    continue
                nm = re.search(r"([A-Za-z_]\w*)\s*$", decl)
                if nm and nm.group(1) not in (
                    "const", "static", "inline", "constexpr", "float", "int", "bool",
                    "uint8_t", "unsigned", "signed", "double", "char",
                ):
                    members.add(nm.group(1))
            # `bool playerAnchorLocked = false;` handled above; also catch
            # `enum class TrackKind : int {` style leftovers and plain members.
        out[name] = members

    for m in re.finditer(r"\benum\s+(?:class\s+)?(\w+)[^{;]*\{(.*?)\n\};", text, flags=re.S):
        name, body = m.group(1), m.group(2)
        members = set()
        for line in body.split("\n"):
            line = re.sub(r"//.*$", "", line).strip()
            fm = re.match(r"(\w+)\s*(?:=\s*-?\d+)?\s*,?$", line)
            if fm:
                members.add(fm.group(1))
        out.setdefault(name, set()).update(members)

    # A class body is `class X { ... };` with private members and methods.
    for m in re.finditer(r"\bclass\s+(\w+)\s*\{(.*?)\n\};", text, flags=re.S):
        name, body = m.group(1), m.group(2)
        members: set[str] = set()
        for line in body.split("\n"):
            line = re.sub(r"//.*$", "", line).strip()
            if not line or line.startswith(("/*", "*", "#")):
                continue
            fm = re.match(
                r"(?:static\s+|inline\s+|const\s+|constexpr\s+|explicit\s+)*"
                r"(?:void|bool|float|int|uint8_t|uint32_t|int32_t|int64_t|double|size_t|jint|jsize)"
                r"[\w\s*&:<>,]*?\b(\w+)\s*\(",
                line,
            )
            if fm:
                members.add(fm.group(1))
                continue
            fm = re.match(
                r"(?:static\s+|inline\s+|const\s+|constexpr\s+)*"
                r"[\w:<>\s*&]+?\b(\w+)\s*(?:=[^;]*)?;\s*$",
                line,
            )
            if fm and fm.group(1) not in ("return", "if", "for", "while"):
                members.add(fm.group(1))
        out.setdefault(name, set()).update(members)

    return out


def main() -> int:
    if not HEADER.exists():
        print(f"::error::{HEADER} not found")
        return 1

    header = HEADER.read_text()
    members_of = parse_header(strip_comments_and_strings(header))
    findings: list[str] = []

    # --- 0. the Kotlin tuning wire order must match the C++ declaration order ---
    kt = pathlib.Path(
        "app/src/main/java/com/example/vision/nativebridge/VisionTypes.kt"
    )
    if kt.exists():
        st = re.search(r"struct EngineConfig \{", header).end()
        cpp_order = [
            n
            for _, n in re.findall(
                r"^[ \t]+(int|float|bool|uint8_t)[ \t]+(\w+)[ \t]*=",
                header[st : header.index("\n};", st)],
                re.M,
            )
            if n not in ("gridW", "gridH")
        ]
        m = re.search(r"val WIRE_ORDER:\s*List<String>\s*=\s*listOf\((.*?)\n\s*\)", kt.read_text(), re.S)
        if not m:
            findings.append(f"{kt}: WIRE_ORDER is missing")
        else:
            kt_order = re.findall(r'"(\w+)"', m.group(1))
            if kt_order != cpp_order:
                findings.append(
                    f"{kt}: WIRE_ORDER does not match the C++ EngineConfig order. "
                    f"nativeConfigure assigns positionally, so every field after the "
                    f"first divergence is written to the wrong knob. "
                    f"{len(kt_order)} vs {len(cpp_order)} fields."
                )
                for i, (a, b) in enumerate(zip(cpp_order, kt_order)):
                    if a != b:
                        findings.append(f"    index {i}: C++ {a} vs Kotlin {b}")
                        break
            else:
                print(f"OK: WIRE_ORDER matches EngineConfig ({len(cpp_order)} fields).")

    for path in sorted(CPP_DIR.glob("*.cpp")):
        text = strip_comments_and_strings(path.read_text())
        rel = str(path)

        # --- 1. engine pointer alias consistency -----------------------------
        # The specific mistake that broke the build: a function declared its
        # engine alias as `e` and then used `engine->`.
        for fm in re.finditer(
            r"(JNIEXPORT\s+\w+\s+JNICALL\s*\n)?Java_\w+\s*\(([^)]*)\)\s*\{(.*?)\n\}",
            text,
            flags=re.S,
        ):
            body = fm.group(3)
            declared = re.search(
                r"auto\*\s*(\w+)\s*=\s*asEngine\(", body
            ) or re.search(
                r"auto\*\s*(\w+)\s*=\s*reinterpret_cast<rendera::VisionEngine\*>", body
            )
            if not declared:
                continue
            alias = declared.group(1)
            for use in re.findall(r"\b(\w+)\s*->\s*(?:player|stats|tracks|blobs|enemies)\(\)", body):
                if use != alias:
                    findings.append(
                        f"{rel}: uses `{use}->...()` but the engine alias in this "
                        f"function is `{alias}`. 'use of undeclared identifier'."
                    )

        # --- 2. member existence --------------------------------------------
        # Resolved per function, not per file. A whole file has two unrelated
        # `b`es in it (a Blob in the readback loop and a Track pointer in a
        # lambda parameter) and a file-wide map silently keeps only one of them,
        # which produced three false positives.
        # One scope per top level definition. Whole-file scoping is what caused
        # the false positives: two unrelated `b`es in one file collided.
        bodies: list[str] = []
        scope_re = re.compile(
            r"(?:JNIEXPORT\s+\w+\s+JNICALL\s*\n)?"
            r"(?:Java_\w+|[\w:<>]+\s+\w+)\s*\([^;{]*\)\s*(?:const\s*\n?\s*)?\{",
            re.M,
        )
        for fm in scope_re.finditer(text):
            start = fm.end() - 1
            depth = 0
            i = start
            while i < len(text):
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            bodies.append(text[start + 1 : i])

        accessors = {
            "tracks": "Track",
            "blobs": "Blob",
            "enemies": "EnemyMark",
            "player": "PlayerState",
            "stats": "FrameStats",
            "motion": "MotionEstimate",
            "threat": "ThreatSolution",
        }

        for body in bodies:
            if not body.strip():
                continue
            containers: dict[str, str] = {}
            local_type: dict[str, str] = {}

            for tm in re.finditer(
                r"std::vector<((?:const\s+)?rendera::)?(\w+)[^>]*>\s*(\w+)\b", body
            ):
                containers[tm.group(3)] = tm.group(2)

            # An accessor-bound reference is used both directly and indexed, so
            # it is recorded as a typed local and as a container.
            for tm in re.finditer(
                r"(?:const\s+)?auto\s*&\s*(\w+)\s*=\s*(\w+)\s*->\s*(\w+)\(\)\s*;", body
            ):
                if tm.group(3) in accessors:
                    local_type[tm.group(1)] = accessors[tm.group(3)]
                    containers[tm.group(1)] = accessors[tm.group(3)]

            for tm in re.finditer(
                r"(?:const\s+)?rendera::(\w+)\s*&\s*(\w+)\s*=\s*(\w+)\s*->\s*(\w+)\(\)\s*;",
                body,
            ):
                local_type[tm.group(2)] = tm.group(1)
                containers[tm.group(2)] = tm.group(1)

            # An index alias, with or without a deref.
            for tm in re.finditer(
                r"(?:const\s+)?auto\s*&\s*(\w+)\s*=\s*(\*?)\s*(\w+)\s*\[", body
            ):
                alias, holder = tm.group(1), tm.group(3)
                if holder in containers:
                    local_type[alias] = containers[holder]

            # A lambda parameter, which shadows any outer name of the same
            # spelling for the whole lambda.
            for tm in re.finditer(
                r"\[[^\]]*\]\s*\(([^)]*)\)\s*(?:->[^\{]*)?\{", body
            ):
                for pm in re.finditer(
                    r"(?:const\s+)?(?:rendera::(\w+)\s*\*|(\w+)\s*\*|(\w+)\s*&)"
                    r"\s*(\w+)", tm.group(1)
                ):
                    t = pm.group(1) or pm.group(2) or pm.group(3)
                    if t in members_of and t not in SKIP_TYPES:
                        local_type[pm.group(4)] = t

            for name, tname in local_type.items():
                if tname not in members_of or tname in SKIP_TYPES:
                    continue
                for use in re.findall(rf"\b{re.escape(name)}\s*(?:->|\.)\s*(\w+)", body):
                    if use in (
                        "at", "data", "size", "empty", "begin", "end", "front",
                        "back", "push_back", "resize", "clear", "erase", "cbegin",
                        "cend", "rbegin", "rend", "insert", "assign", "get", "put",
                        "count", "sort", "first", "second", "code", "swap",
                    ):
                        continue
                    if use not in members_of[tname]:
                        findings.append(
                            f"{rel}: `{tname}` has no member `{use}` "
                            f"(from `{name}.{use}`)"
                        )

        # --- 3. direct `rendera::Type` field access on a known object ---------
        for tm in re.finditer(r"rendera::(Track|Blob|EnemyMark|PlayerState|FrameStats|"
                              r"ThreatSolution|MotionEstimate)\b", text):
            tname = tm.group(1)
            if tname not in members_of:
                findings.append(f"{rel}: references unknown type rendera::{tname}")

    if findings:
        seen = set()
        for f in findings:
            if f in seen:
                continue
            seen.add(f)
            print(f"::error::{f}")
        print(f"{len(seen)} C++ name problem(s) found.")
        return 1

    print("OK: C++ member and alias names check out.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
