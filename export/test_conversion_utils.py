import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from conversion_utils import run_mnnconvert


class ConversionUtilsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.output = os.path.join(self.temp.name, "model.mnn")

    def tearDown(self):
        self.temp.cleanup()

    def test_nonzero_converter_status_is_fatal(self):
        with patch("conversion_utils.subprocess.run", return_value=subprocess.CompletedProcess(["mnnconvert"], 1, stdout="failed")):
            with self.assertRaises(subprocess.CalledProcessError):
                run_mnnconvert(["mnnconvert"], self.output)

    def test_missing_output_is_fatal(self):
        with patch("conversion_utils.subprocess.run", return_value=subprocess.CompletedProcess(["mnnconvert"], 0, stdout="")):
            with self.assertRaisesRegex(RuntimeError, "non-empty output"):
                run_mnnconvert(["mnnconvert"], self.output)

    def test_stale_output_is_fatal(self):
        with open(self.output, "wb") as output:
            output.write(b"old")
        with patch("conversion_utils.subprocess.run", return_value=subprocess.CompletedProcess(["mnnconvert"], 0, stdout="")):
            with self.assertRaisesRegex(RuntimeError, "stale output"):
                run_mnnconvert(["mnnconvert"], self.output)

    def test_fresh_output_is_accepted(self):
        def convert(_args, **_kwargs):
            with open(self.output, "wb") as output:
                output.write(b"new")
            return subprocess.CompletedProcess(["mnnconvert"], 0, stdout="ok")

        with patch("conversion_utils.subprocess.run", side_effect=convert):
            result = run_mnnconvert(["mnnconvert"], self.output)
        self.assertEqual(0, result.returncode)
        self.assertTrue(os.path.getsize(self.output) > 0)


if __name__ == "__main__":
    unittest.main()
