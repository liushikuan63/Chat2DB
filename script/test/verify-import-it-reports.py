"""Fail closed: every named integration suite must actually execute without skips."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

EXPECTED = {"MySQLTaskRoundTripIT": 5, "PostgreSQLStagingIT": 6,
            "MariaDBStagingIT": 6, "StagingManifestPerformanceIT": 1}


def verify(root):
    found = {}
    for report in Path(root).glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        name = suite.get("name", "").rsplit(".", 1)[-1]
        if name not in EXPECTED:
            continue
        counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        if name in found:
            raise ValueError("Duplicate suite " + name)
        found[name] = counts
        if counts["tests"] < EXPECTED[name] or any(counts[key] for key in ("failures", "errors", "skipped")):
            raise ValueError(f"Integration gate rejected {name}: {counts}")
        print(f"PASS {name}: {counts}")
    missing = EXPECTED.keys() - found.keys()
    if missing:
        raise ValueError("Missing integration reports: " + ", ".join(sorted(missing)))
    print("PASS integration gate: " + str(sum(c["tests"] for c in found.values())) + " tests; zero skips")


if __name__ == "__main__":
    verify(sys.argv[1])
