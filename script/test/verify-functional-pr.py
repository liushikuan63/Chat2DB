"""Read-only gate binding a complete functional PR's scope, diff sizes and actual reports to its tree."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


def git(repo, *args):
    result = subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True, encoding="utf-8")
    return result.stdout.strip()


def classify(path):
    if "/src/test/" in path or ".test." in path or path.startswith("script/test/"):
        return "tests_fixtures"
    if path.endswith((".md", ".yml", ".yaml", ".json", ".xml", ".properties", ".lock")) or path.startswith(".github/"):
        return "docs_config"
    return "production"


def verify(repo, contract, evidence):
    base = git(repo, "rev-parse", contract["base"] + "^{commit}")
    head = git(repo, "rev-parse", contract["head"] + "^{commit}")
    git(repo, "merge-base", "--is-ancestor", base, head)
    if not contract.get("functions") or not contract.get("entrypoints"):
        raise ValueError("Declare delivered functions and their runtime entrypoints")
    for path in contract["required_paths"]:
        git(repo, "cat-file", "-e", head + ":" + path)
    tree = git(repo, "rev-parse", head + "^{tree}")
    if evidence.get("tested_tree") != tree:
        raise ValueError("Verification evidence belongs to another source tree")
    if evidence.get("command_exit") != 0 or evidence.get("run_started_epoch", 0) <= 0:
        raise ValueError("Record an actual successful verification command and its start time")
    groups = {name: {"added": 0, "deleted": 0} for name in ("production", "tests_fixtures", "docs_config")}
    changes = git(repo, "diff", "--no-renames", "--numstat", base, head)
    for line in changes.splitlines():
        added, deleted, path = line.split("\t", 2)
        if added == "-" or deleted == "-":
            raise ValueError("Binary changes need a separate explicit review contract")
        group = groups[classify(path)]
        group["added"] += int(added)
        group["deleted"] += int(deleted)
    production = sum(groups["production"].values())
    total = sum(sum(group.values()) for group in groups.values())
    if (production > contract["production_review_target"] or total > contract["total_review_target"]) and not contract.get("size_explanation"):
        raise ValueError("Explain a diff exceeding the declared review target")
    report_root = Path(evidence["report_root"]).resolve()
    reports = {}
    for name in contract["required_suites"]:
        filename = evidence["reports"].get(name)
        if not filename:
            raise ValueError("Missing required suite: " + name)
        path = (report_root / filename).resolve()
        if not path.is_relative_to(report_root):
            raise ValueError("Report path escapes evidence root")
        if path.stat().st_mtime < evidence["run_started_epoch"]:
            raise ValueError("Required report predates this verification run: " + name)
        suite = ET.parse(path).getroot()
        counts = {key: int(suite.attrib.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")}
        if suite.attrib.get("name") != name or counts["tests"] <= 0 or any(counts[key] for key in ("failures", "errors", "skipped")):
            raise ValueError("Required suite did not execute successfully: " + name)
        reports[name] = counts
    return {"base": base, "head": head, "tree": tree, "functions": contract["functions"],
            "diff": groups, "changed_lines": total, "required_reports": reports}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--contract", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.repo, json.loads(args.contract.read_text(encoding="utf-8")),
                    json.loads(args.evidence.read_text(encoding="utf-8")))
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("FUNCTIONAL_PR_REJECTED " + str(error), file=sys.stderr)
        sys.exit(1)
