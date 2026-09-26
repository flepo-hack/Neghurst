#!/usr/bin/env python3
"""
Static check for the JNI bridge.

This exists because of a real, shipped bug: a JNI function took an *unnamed*
`JNIEnv*` parameter while its body used `env->`. The C++ compiler reports that as
"use of undeclared identifier 'env'", but only once you actually run the NDK
build, which in this project took several minutes of CI to reach. This check
costs milliseconds and turns a late, confusing failure into an immediate one.

It also checks the two invariants that are otherwise only enforced by
convention:

  * every exported JNI symbol matches a Kotlin `external fun`, and vice versa,
    because a typo in either one fails at runtime with an UnsatisfiedLinkError
    rather than at build time;
  * no JNI function body touches the engine on a thread it did not create,
    which would be a data race rather than a compile error.

Usage:  python3 .github/scripts/check_jni.py
Exit code 0 on success, 1 on any finding.
"""

from __future__ import annotations

import pathlib
import re
import sys

CPP = pathlib.Path("app/src/main/cpp/rendera_jni.cpp")
KT = pathlib.Path(
    "app/src/main/java/com/example/vision/nativebridge/NativeVisionEngine.kt"
)
PACKAGE = "com.example.vision.nativebridge.NativeVisionEngine"

SIGNATURE = re.compile(
    r"JNIEXPORT\s+(\w+)\s+JNICALL\s*\n(Java_\w+)\s*\(([^)]*)\)\s*\{"
)


def function_bodies(text: str):
    """Yield (return_type, symbol, params, body) for each JNI function."""
    for m in SIGNATURE.finditer(text):
        depth, i = 1, m.end()
        while depth > 0 and i < len(text):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
            i += 1
        yield m.group(1), m.group(2), m.group(3), text[m.end() : i - 1]


def main() -> int:
    if not CPP.exists() or not KT.exists():
        print(f"::error::expected {CPP} and {KT} to exist")
        return 1

    cpp = CPP.read_text()
    kt = KT.read_text()
    findings: list[str] = []

    # -- 1. env must be named wherever it is used ----------------------------
    for ret, symbol, params, body in function_bodies(cpp):
        first = params.split(",")[0].strip()
        uses_env = "env->" in body
        names_env = first.startswith("JNIEnv* env")
        line = cpp[: cpp.index(symbol)].count("\n") + 1
        short = symbol.rsplit("_", 1)[-1]

        if uses_env and not names_env:
            findings.append(
                f"{CPP}:{line}: {short}() uses `env->` but its first parameter is "
                f"`{first}` (unnamed). Name it `JNIEnv* env`."
            )
        if names_env and not uses_env:
            findings.append(
                f"{CPP}:{line}: {short}() names `env` but never uses it; "
                f"drop the name to silence -Wunused-parameter."
            )

        # -- 2. instance methods must take a jobject, not a jclass ----------
        if " jclass" in params:
            findings.append(
                f"{CPP}:{line}: {short}() takes a jclass but is declared as a Kotlin "
                f"instance method; it must be a jobject."
            )
        # The engine pointer must be a jlong, never an int or a pointer.
        if "jlong handle" not in params and "handle" in params:
            findings.append(
                f"{CPP}:{line}: {short}() takes a handle that is not a jlong; the "
                f"Kotlin side passes a Long."
            )

    # -- 3. symbol parity with the Kotlin external declarations --------------
    cpp_symbols = {s.rsplit("_", 1)[-1] for _, s, _, _ in function_bodies(cpp)}
    kt_symbols = set(re.findall(r"private external fun (\w+)\(", kt))
    expected_prefix = "Java_" + PACKAGE.replace(".", "_") + "_"

    for _, symbol, _, _ in function_bodies(cpp):
        if not symbol.startswith(expected_prefix):
            findings.append(
                f"{CPP}: symbol `{symbol}` does not follow the "
                f"`{expected_prefix}<name>` convention for package {PACKAGE}."
            )

    for missing in sorted(cpp_symbols - kt_symbols):
        findings.append(
            f"{CPP}: exports native{missing[6:].capitalize()} with no matching Kotlin "
            f"`external fun`; the JNI name would never resolve."
        )
    for missing in sorted(kt_symbols - cpp_symbols):
        findings.append(
            f"{KT}: declares `external fun {missing}` with no JNI implementation; "
            f"loading or calling it will throw UnsatisfiedLinkError."
        )

    # -- 4. no background-thread JNI work in this translation unit -----------
    # The vision engine runs on a coroutine dispatcher, not a thread this file
    # created, so any JNI call here must be a direct Java-invoked function.
    for ret, symbol, params, body in function_bodies(cpp):
        if "AttachCurrentThread" in body or "DetachCurrentThread" in body:
            findings.append(
                f"{CPP}: {symbol}() attaches to the JVM. The engine runs on a "
                f"coroutine thread, not one this file owns; pass the JNIEnv through "
                f"from the Java call instead."
            )

    if findings:
        for f in findings:
            print(f"::error::{f}")
        print(f"{len(findings)} JNI problem(s) found.")
        return 1

    print(
        f"OK: {len(cpp_symbols)} JNI functions, all with a named env, all matching "
        f"a Kotlin external declaration."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
