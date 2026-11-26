"""Print per-test skip reasons and the overall totals from target/surefire-reports.

Surefire 2.x records @Ignore skips without a message, so for those the matching
@Ignore line from the test source is printed. A class-level @Ignore is reported
by surefire as a testcase whose name equals its classname.
A per-run nonce is printed with the totals so .travis/wait-for-log.sh can tell
this run's archived log from a previous run of the same job.
"""
import glob
import os
import re
import sys
import uuid
import xml.etree.ElementTree as ET


def read_source(classname):
    path = os.path.join("src/test/java", classname.split("$")[0].replace(".", "/") + ".java")
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return path, f.readlines()
    except OSError:
        return path, None


def ignore_near(lines, i, path):
    for j in range(max(0, i - 5), i):
        if "@Ignore" in lines[j]:
            return "%s at %s:%d" % (lines[j].strip(), path, j + 1)
    return None


def source_reason(classname, method):
    path, lines = read_source(classname)
    if lines is None:
        return None
    simple = classname.split(".")[-1].split("$")[-1]
    name = re.sub(r"\[.*$", "", method or "")
    if name == classname or name == simple or not name:
        for i, line in enumerate(lines):
            if re.search(r"\bclass\s+%s\b" % re.escape(simple), line):
                found = ignore_near(lines, i, path)
                if found:
                    return "whole class disabled upstream by %s" % found
        return None
    for i, line in enumerate(lines):
        if re.search(r"\bvoid\s+%s\s*\(" % re.escape(name), line):
            found = ignore_near(lines, i, path)
            if found:
                return "disabled upstream by %s" % found
            for body in lines[i + 1:i + 40]:
                if re.search(r"assume", body, re.IGNORECASE):
                    return "assumption not met: %s" % body.strip()
    for i, line in enumerate(lines):
        if re.search(r"\bclass\s+%s\b" % re.escape(simple), line):
            found = ignore_near(lines, i, path)
            if found:
                return "whole class disabled upstream by %s" % found
    return None


tests = failures = errors = skipped = 0
skips = []
for path in sorted(glob.glob("target/surefire-reports/TEST-*.xml")):
    root = ET.parse(path).getroot()
    tests += int(root.get("tests", 0))
    failures += int(root.get("failures", 0))
    errors += int(root.get("errors", 0))
    skipped += int(root.get("skipped", 0))
    for case in root.iter("testcase"):
        sk = case.find("skipped")
        if sk is None:
            continue
        message = (sk.get("message") or "").strip()
        if not message:
            message = source_reason(case.get("classname", ""), case.get("name", "")) or "(no message)"
        skips.append("%s.%s: %s" % (case.get("classname"), case.get("name"), message))

nonce = uuid.uuid4().hex[:12]
with open("/tmp/seal-log-nonce", "w") as f:
    f.write(nonce)

print("Skipped tests (%d):" % len(skips))
for line in skips:
    print("  SKIPPED " + line)
print("Surefire totals: tests=%d passed=%d failures=%d errors=%d skipped=%d (run %s)"
      % (tests, tests - failures - errors - skipped, failures, errors, skipped, nonce))
sys.exit(0 if tests > 0 and failures == 0 and errors == 0 else 1)
