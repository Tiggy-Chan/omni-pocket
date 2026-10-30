import argparse
import hashlib
import json
import math
import os
import re
import subprocess
from pathlib import Path

SYSTEM = (
    "Apply the question to the state. Choose exactly one of the listed options. "
    "Respond with only its uppercase letter, with no explanation or reasoning."
)
STATE = "I was charged twice this month, please refund one of them."
QUESTIONS = [
    ("noul", "Question: Is the customer asking for a refund?\n\nOptions:\n"
     "A. yes: the statement is true / the answer is yes\n"
     "B. no: the statement is false / the answer is no\n\nAnswer with one letter: A, B."),
    ("choice", "Question: Which team should handle this?\n\nOptions:\n"
     "A. billing: Charges, refunds, invoices\nB. technical: App or site faults\n"
     "C. fraud: Suspected unauthorised use\n\nAnswer with one letter: A, B, C."),
    ("score", "Rate the state: How urgent is this?\n\nOptions:\n"
     "A. level 0: Can wait\nB. level 1: Today\n"
     "C. level 2: Blocking the customer now\n\nAnswer with one letter: A, B, C."),
]


def choice_confidence(probabilities):
    if len(probabilities) == 1:
        return 1.0
    uniform = 1.0 / len(probabilities)
    return (max(probabilities) - uniform) / (1.0 - uniform)


def expected_score(probabilities):
    return sum(index * probability for index, probability in enumerate(probabilities))


def score_confidence(probabilities):
    if len(probabilities) == 1:
        return 1.0
    mode = max(range(len(probabilities)), key=probabilities.__getitem__)
    distance = sum(probability * abs(index - mode) for index, probability in enumerate(probabilities))
    center = (len(probabilities) - 1) / 2
    uniform_deviation = sum(abs(index - center) for index in range(len(probabilities))) / len(probabilities)
    return max(0.0, 1.0 - distance / uniform_deviation)


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def capture(args):
    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    revision = subprocess.check_output(["git", "-C", args.llama_source, "rev-parse", "HEAD"], text=True).strip()
    result = {
        "schemaVersion": 1,
        "protocol": "qev-labels-v2",
        "referenceRuntime": "llama.cpp",
        "referenceRevision": revision,
        "modelRepo": "baselabs/elephant-1",
        "ggufSha256": sha256_file(args.gguf),
        "kvType": "float32",
        "probabilityTolerance": 0.05,
        "cases": [],
    }
    for index, (kind, suffix) in enumerate(QUESTIONS):
        prompt = (f"<|im_start|>system\n{SYSTEM}<|im_end|>\n<|im_start|>user\n"
                  f"<state>\n{STATE}\n</state>\n\n{suffix}<|im_end|>\n"
                  "<|im_start|>assistant\n<think>\n\n</think>\n\n")
        path = work / f"{kind}.txt"
        path.write_text(prompt, encoding="utf-8")
        output = subprocess.check_output(
            [str(Path(args.probe).resolve()), str(Path(args.gguf).resolve()), str(path.resolve()), "block", "58"],
            text=True, stderr=subprocess.STDOUT, env=dict(os.environ, LLAMA_FP32_KV="1"))
        slots = re.findall(r"^([ABC]) token=(\d+) logit=(\S+) vocab_prob=(\S+)$", output, re.MULTILINE)
        mass_match = re.search(r"mode=block tokens=(\d+) prefix=(\d+)", output)
        if len(slots) != 3 or not mass_match:
            raise RuntimeError(f"cannot parse llama.cpp output for {kind}:\n{output}")
        count = 2 if kind == "noul" else 3
        full = [float(slot[3]) for slot in slots[:count]]
        total = sum(full)
        if not math.isfinite(total) or total <= 0.0 or total > 1.000001:
            raise RuntimeError(f"invalid reference mass for {kind}: {total}")
        result["cases"].append({
            "branch": index,
            "kind": kind,
            "prefixTokens": int(mass_match.group(2)),
            "promptSha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
            "logits": [float(slot[2]) for slot in slots[:count]],
            "probabilities": [probability / total for probability in full],
            "allowedMass": total,
        })
        print(f"captured {kind}: normalized={result['cases'][-1]['probabilities']} mass={total}")
    output = Path(args.output)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"reference written: {output}")


def verify(args):
    reference = json.loads(Path(args.reference).read_text(encoding="utf-8"))
    log = Path(args.log).read_text(encoding="utf-8")
    section = log.split("OFFICIAL_TICKET started", 1)[-1].split("REPEAT started", 1)[0]
    matches = re.findall(r"branch=(\d+) .*?allowed_mass=(\S+) probabilities=(.*?) top=(\d+) logits=(.*)", section)
    if len(matches) != len(reference["cases"]):
        raise RuntimeError(f"expected {len(reference['cases'])} native branches, found {len(matches)}")
    tolerance = reference["probabilityTolerance"]
    errors = []
    for case, match in zip(reference["cases"], matches):
        branch, mass, probabilities, top, logits = match
        if int(branch) != case["branch"]:
            raise RuntimeError(f"branch order mismatch: {branch} != {case['branch']}")
        actual = [float(value) for value in probabilities.split()]
        expected = case["probabilities"]
        if len(actual) != len(expected) or not all(math.isfinite(value) and value >= 0.0 for value in actual):
            raise RuntimeError(f"invalid probabilities for {case['kind']}: {actual}")
        if abs(sum(actual) - 1.0) > 0.0001:
            raise RuntimeError(f"unnormalized probabilities for {case['kind']}: {actual}")
        difference = max(abs(left - right) for left, right in zip(actual, expected))
        mass = float(mass)
        if not math.isfinite(mass) or mass <= 0.0 or mass > 1.000001:
            raise RuntimeError(f"invalid native allowed mass for {case['kind']}: {mass}")
        print(f"{case['kind']}: actual={actual} reference={expected} max_error={difference:.6f}")
        print(f"  allowed_mass actual={mass:.6f} reference={case['allowedMass']:.6f} "
              f"delta={mass - case['allowedMass']:.6f}")
        if case["kind"] == "choice":
            print(f"  confidence actual={choice_confidence(actual):.6f} reference={choice_confidence(expected):.6f}")
        elif case["kind"] == "score":
            print(f"  score actual={expected_score(actual):.6f} reference={expected_score(expected):.6f}")
            print(f"  confidence actual={score_confidence(actual):.6f} reference={score_confidence(expected):.6f}")
        else:
            print(f"  yes_probability actual={actual[0]:.6f} reference={expected[0]:.6f}")
        if difference > tolerance:
            errors.append(f"{case['kind']} probability error {difference:.6f} > {tolerance:.6f}")
        actual_logits = [float(value) for value in logits.split()]
        if len(actual_logits) != len(case["logits"]):
            raise RuntimeError(f"missing native logits for {case['kind']}")
    if errors:
        raise RuntimeError("; ".join(errors))
    print(f"PASS: llama.cpp normalized probabilities within {tolerance:.3f}; confidence semantics verified")


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    capture_parser = commands.add_parser("capture")
    capture_parser.add_argument("--probe", required=True)
    capture_parser.add_argument("--gguf", required=True)
    capture_parser.add_argument("--llama-source", required=True)
    capture_parser.add_argument("--work", required=True)
    capture_parser.add_argument("--output", required=True)
    capture_parser.set_defaults(function=capture)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--reference", required=True)
    verify_parser.add_argument("--log", required=True)
    verify_parser.set_defaults(function=verify)
    args = parser.parse_args()
    args.function(args)


if __name__ == "__main__":
    main()
