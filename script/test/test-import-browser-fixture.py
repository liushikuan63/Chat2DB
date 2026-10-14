"""Verify fixture ownership guards without Docker, credentials or datasource writes."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).with_name('prepare-import-browser-fixture.py')
spec = importlib.util.spec_from_file_location('browser_fixture', MODULE_PATH)
fixture_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture_module)


class FixtureOwnershipTest(unittest.TestCase):
    def cleanup(self, directory, receipt, replies):
        file = Path(directory) / 'fixture.json'
        file.write_text(json.dumps(receipt), encoding='utf8')
        with patch('sys.argv', [str(MODULE_PATH), '--cleanup', str(file)]), \
                patch.object(fixture_module.subprocess, 'run', side_effect=replies) as commands, \
                contextlib.redirect_stdout(io.StringIO()):
            fixture_module.main()
            return commands.call_args_list

    def receipt(self):
        return {'database': 'c2d_ui_fixture_20261013', 'marker': 'c2db-browser-unit-fixture',
                'containerId': 'a' * 64}

    def test_cleanup_uses_full_recorded_id_after_matching_owner(self):
        receipt = self.receipt()
        with tempfile.TemporaryDirectory() as directory:
            commands = self.cleanup(directory, receipt, [
                subprocess.CompletedProcess([], 0, receipt['marker'], ''),
                subprocess.CompletedProcess([], 0, receipt['containerId'], ''),
            ])
        self.assertEqual(commands[1].args[0], ['docker', 'rm', '--force', 'a' * 64])
        self.assertEqual(len(commands), 2)

    def test_mismatched_owner_never_removes_container(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'fixture.json'
            file.write_text(json.dumps(self.receipt()), encoding='utf8')
            with patch.object(fixture_module.subprocess, 'run', return_value=
                    subprocess.CompletedProcess([], 0, 'another-owner', '')) as commands, \
                    patch('sys.argv', [str(MODULE_PATH), '--cleanup', str(file)]):
                with self.assertRaisesRegex(ValueError, 'owner label changed'):
                    fixture_module.main()
                self.assertEqual(commands.call_count, 1)
                self.assertEqual(commands.call_args.args[0][1], 'inspect')

    def test_unknown_database_and_short_ids_fail_before_docker(self):
        for replacement in [{'database': 'user_database'}, {'containerId': 'a' * 12}]:
            receipt = {**self.receipt(), **replacement}
            with tempfile.TemporaryDirectory() as directory, \
                    patch.object(fixture_module.subprocess, 'run') as commands:
                file = Path(directory) / 'fixture.json'
                file.write_text(json.dumps(receipt), encoding='utf8')
                with patch('sys.argv', [str(MODULE_PATH), '--cleanup', str(file)]):
                    with self.assertRaises(ValueError):
                        fixture_module.main()
                commands.assert_not_called()


if __name__ == '__main__':
    unittest.main()
