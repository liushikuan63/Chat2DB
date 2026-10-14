"""Stateful fault injection for the runner; no Docker daemon or database is used."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("runner", Path(__file__).with_name("run-import-it.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class DockerState:
    def __init__(self, failure):
        self.failure = failure
        self.containers = {}
        self.created = []

    def __call__(self, *args, env=None):
        operation = args[0]
        if operation == "run":
            cid = str(len(self.created) + 1) * 64
            self.created.append(cid)
            self.containers[cid] = args[args.index("--label") + 1].split("=", 1)[1]
            return cid
        if operation == "port":
            return "127.0.0.1:49152"
        cid = args[-1]
        if operation == "ps":
            cid = args[args.index("--filter") + 1].split("=", 1)[1]
            if self.failure == "unavailable":
                raise RuntimeError("Docker ps failed (exit 1)")
            return cid if cid in self.containers else ""
        first_cleanup = cid == self.created[-1]
        if operation == "inspect":
            if self.failure == "multiple" and cid in self.created[1:]:
                raise RuntimeError("Docker inspect failed (exit 1)")
            if first_cleanup and self.failure == "absent":
                self.containers.pop(cid, None)
                raise RuntimeError("Docker inspect failed (exit 1)")
            if first_cleanup and self.failure in ("inspect", "unavailable"):
                raise RuntimeError("Docker inspect failed (exit 1)")
            if first_cleanup and self.failure == "owner":
                return "someone-else"
            return self.containers[cid]
        if operation == "rm":
            if first_cleanup and self.failure in ("rm", "removed-during-rm"):
                if self.failure == "removed-during-rm":
                    self.containers.pop(cid)
                raise RuntimeError("Docker rm failed (exit 1)")
            self.containers.pop(cid)
            return cid
        raise AssertionError("Unexpected Docker operation " + operation)


class CleanupTest(unittest.TestCase):
    def run_scenario(self, failure, primary=0):
        state = DockerState(failure)
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "script/test").mkdir(parents=True)
            (root / "script/test/verify-import-it-reports.py").write_text(
                "EXPECTED = {'FixtureIT': 1}\ndef verify(root): pass\n", encoding="utf-8")

            def execute(command, **kwargs):
                if command[0] == "docker":
                    return SimpleNamespace(returncode=0)
                if isinstance(primary, Exception):
                    raise primary
                return SimpleNamespace(returncode=primary)

            with patch.object(runner, "ROOT", root), patch.object(runner, "REPORTS", root / "reports"), \
                    patch.object(runner, "docker", state), patch.object(runner.subprocess, "run", execute), \
                    patch("socket.create_connection") as connection, contextlib.redirect_stdout(output):
                connection.return_value.__enter__.return_value.recv.return_value = b"x"
                try:
                    result = runner.main()
                except Exception as error:
                    result = error
        return state, result, output.getvalue()

    def test_success_removes_every_owned_container(self):
        state, result, output = self.run_scenario(None)
        self.assertEqual({}, state.containers)
        self.assertEqual(0, result)
        self.assertNotIn("CLEANUP_FAILED", output)

    def test_multiple_cleanup_failures_are_reported_without_stranding_final_id(self):
        state, result, output = self.run_scenario("multiple")
        self.assertEqual(set(state.created[1:]), set(state.containers))
        self.assertEqual(1, result)
        for cid in state.created[1:]:
            self.assertIn("CLEANUP_FAILED " + cid, output)

    def test_inspect_or_remove_failure_does_not_strand_other_owned_containers(self):
        for failure in ("inspect", "rm", "unavailable"):
            with self.subTest(failure=failure):
                state, result, output = self.run_scenario(failure)
                self.assertEqual({state.created[-1]}, set(state.containers))
                self.assertEqual(1, result)
                self.assertIn("CLEANUP_FAILED", output)

    def test_confirmed_absence_is_cleaned_and_does_not_stop_following_ids(self):
        for failure in ("absent", "removed-during-rm"):
            with self.subTest(failure=failure):
                state, result, output = self.run_scenario(failure)
                self.assertEqual({}, state.containers)
                self.assertEqual(0, result)
                self.assertIn("ALREADY_REMOVED", output)

    def test_wrong_owner_survives_and_other_owned_containers_are_removed(self):
        state, result, output = self.run_scenario("owner")
        self.assertEqual({state.created[-1]}, set(state.containers))
        self.assertEqual(1, result)
        self.assertIn("ownership mismatch", output)

    def test_cleanup_failure_preserves_maven_exit_code_and_diagnostic(self):
        state, result, output = self.run_scenario("rm", primary=23)
        self.assertEqual(23, result)
        self.assertEqual({state.created[-1]}, set(state.containers))
        self.assertIn("Maven exited with code 23", output)
        self.assertIn("CLEANUP_FAILED", output)

    def test_cleanup_failure_preserves_primary_exception_identity(self):
        primary = RuntimeError("primary test failure")
        state, result, output = self.run_scenario("inspect", primary=primary)
        self.assertIs(primary, result)
        self.assertEqual({state.created[-1]}, set(state.containers))
        self.assertIn("CLEANUP_FAILED", output)


if __name__ == "__main__":
    unittest.main()
