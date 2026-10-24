import os
import subprocess


def run_mnnconvert(args, output_path, reference_path=None):
    previous_mtime = os.stat(output_path).st_mtime_ns if os.path.exists(output_path) else None
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    print(result.stdout[-600:])
    result.check_returncode()
    if not os.path.isfile(output_path) or os.path.getsize(output_path) == 0:
        raise RuntimeError(f"MNNConvert did not produce a non-empty output: {output_path}")
    output_mtime = os.stat(output_path).st_mtime_ns
    if previous_mtime is not None and output_mtime <= previous_mtime:
        raise RuntimeError(f"MNNConvert left a stale output: {output_path}")
    if reference_path is not None and output_mtime < os.stat(reference_path).st_mtime_ns:
        raise RuntimeError(f"MNN output is older than its input: {output_path}")
    return result
