import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from gguf import constants, gguf_reader
from gguf2mnn import write_external_weight
from utils.mnn_utils import write_quant_parameters


def write_embedding_weight(tensor, output, offset):
    if tensor.tensor_type == constants.GGMLQuantizationType.Q4_0:
        width, rows = map(int, tensor.shape)
        source = tensor.data.reshape((-1, 18))
        if source.shape[0] * 32 != width * rows:
            raise ValueError(f"embedding shape mismatch: {tensor.shape}")
        raw = source[:, 2:]
        packed = (raw >> 4) | ((raw & 0x0F) << 4)
        scales = np.frombuffer(source[:, :2].tobytes(), dtype="<f2").astype(np.float32)
        converted, header_len, offset = write_quant_parameters(
            4, False, output, width, rows, packed, scales, offset, scale_bit=32
        )
        return offset, converted, True, 32, 4, header_len
    if tensor.tensor_type not in {
        constants.GGMLQuantizationType.Q4_K,
        constants.GGMLQuantizationType.Q8_0,
    }:
        raise ValueError(f"unsupported embedding quantization: {tensor.tensor_type}")
    return write_external_weight(tensor, output, offset, scale_bit=32)


MODEL_REPO = "baselabs/elephant-1"
MODEL_REVISION = "b3f8a93462fb1051a9de031e94a7ff380e254072"
GRAPH_REPO = "taobao-mnn/Qwen3.5-9B-MNN"
GRAPH_REVISION = "149e1e052267ffa377b87e3bb0ddb75f46217a9a"
QUANT_REPO = "prithivMLmods/elephant-1-GGUF"
QUANT_REVISION = "77d938a13309d7b3da168d87abf5589f9329b122"
ONEJEV_REVISION = "81ce62f1597c91e46767d4d02d6ac2e18534fe94"
EXCLUDED_TWO_LETTER_LABELS = [
    "BQ", "BZ", "CJ", "CQ", "CZ", "DQ", "DZ", "EJ", "EY", "FJ", "FQ", "FV", "FZ",
    "GJ", "GK", "GQ", "GZ", "HJ", "IY", "JF", "JG", "JH", "JL", "JN", "JQ",
]

PROJECTIONS = {
    "in_proj_qkv": "attn_qkv.weight",
    "in_proj_a": "ssm_alpha.weight",
    "in_proj_b": "ssm_beta.weight",
    "in_proj_z": "attn_gate.weight",
    "out_proj": "ssm_out.weight",
    "q_proj": "attn_q.weight",
    "k_proj": "attn_k.weight",
    "v_proj": "attn_v.weight",
    "o_proj": "attn_output.weight",
    "gate_proj": "ffn_gate.weight",
    "up_proj": "ffn_up.weight",
    "down_proj": "ffn_down.weight",
}
NORMS = {
    "input_layernorm": "attn_norm.weight",
    "post_attention_layernorm": "post_attention_norm.weight",
    "norm": "ssm_norm.weight",
    "q_norm": "attn_q_norm.weight",
    "k_norm": "attn_k_norm.weight",
}


@dataclass(frozen=True)
class Replacement:
    op_index: int | None
    tensor_name: str
    transform: str


def read_json(path):
    with open(path, encoding="utf-8") as source:
        return json.load(source)


def write_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + ".tmp")
    with open(temporary, "w", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write("\n")
    os.replace(temporary, path)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def consumers_by_tensor(graph):
    consumers = defaultdict(list)
    for op in graph["oplists"]:
        for tensor_index in op.get("inputIndexes", []):
            consumers[tensor_index].append(op)
    return consumers


def fold_softplus(graph):
    producers = {output: op for op in graph["oplists"] for output in op.get("outputIndexes", [])}
    folded = 0
    for op in graph["oplists"]:
        if op.get("type") != "UnaryOp" or op.get("main", {}).get("opType") != "LOG":
            continue
        if not re.fullmatch(r"/blocks\.\d+/self_attn/Softplus_output_0", op["name"]):
            continue
        if len(op.get("inputIndexes", [])) != 1:
            raise ValueError(f"invalid softplus input: {op['name']}")
        add = producers.get(op["inputIndexes"][0])
        if add is None or add.get("type") != "BinaryOp" or add.get("main", {}).get("opType") != "ADD":
            raise ValueError(f"invalid softplus addition: {op['name']}")
        if len(add.get("inputIndexes", [])) != 2:
            raise ValueError(f"invalid softplus addition inputs: {op['name']}")
        exp, one = (producers.get(index) for index in add["inputIndexes"])
        if exp is None or exp.get("type") != "UnaryOp" or exp.get("main", {}).get("opType") != "EXP":
            raise ValueError(f"invalid softplus exponential: {op['name']}")
        if one is None or one.get("type") != "Const" or one.get("main", {}).get("float32s") != [1.0]:
            raise ValueError(f"invalid softplus constant: {op['name']}")
        if len(exp.get("inputIndexes", [])) != 1:
            raise ValueError(f"invalid softplus exponential input: {op['name']}")
        op["inputIndexes"] = list(exp["inputIndexes"])
        op["main"] = {"opType": "BNLL", "T": "DT_FLOAT"}
        folded += 1
    return folded


def set_tiled_head_order(graph):
    converted = 0
    for op in graph["oplists"]:
        if op.get("type") != "LinearAttention":
            continue
        if op.get("main", {}).get("attn_type") != "gated_delta_rule":
            raise ValueError(f"unexpected linear attention type: {op['name']}")
        op["main"]["attn_type"] = "gated_delta_rule_tiled"
        converted += 1
    return converted


def map_layer_number(name, prefix):
    match = re.fullmatch(re.escape(prefix) + r"(\d+)/.*", name)
    if not match:
        raise ValueError(f"unexpected layer operation: {name}")
    return int(match.group(1))


def map_const(graph, index, consumers):
    op = graph["oplists"][index]
    main = op.get("main", {})
    name = op["name"]
    match = re.fullmatch(r"/blocks\.(\d+)/self_attn/Neg_output_0", name)
    if match:
        return Replacement(index, f"blk.{match.group(1)}.ssm_a", "const")
    if main.get("dataType") != "DT_FLOAT":
        return None
    values = main.get("float32s", [])
    if main.get("dims") == [8192, 1, 4]:
        candidates = []
        for output_index in op.get("outputIndexes", []):
            for consumer in consumers[output_index]:
                if consumer.get("type") == "LinearAttention":
                    layer = map_layer_number(consumer["name"], "/layers.")
                    candidates.append(f"blk.{layer}.ssm_conv1d.weight")
        if len(set(candidates)) != 1:
            raise ValueError(f"cannot identify recurrent convolution for {name}")
        return Replacement(index, candidates[0], "const")
    if name.startswith("onnx::Add_") and len(values) == 32:
        candidates = []
        for output_index in op.get("outputIndexes", []):
            for consumer in consumers[output_index]:
                match = re.fullmatch(r"/blocks\.(\d+)/self_attn/Add_output_0", consumer["name"])
                if match:
                    candidates.append(f"blk.{match.group(1)}.ssm_dt.bias")
        if len(set(candidates)) != 1:
            raise ValueError(f"cannot identify dt bias for {name}")
        return Replacement(index, candidates[0], "const")
    if len(values) > 16 and not name.startswith("/rotary/"):
        raise ValueError(f"unmapped learned constant: {name} ({len(values)} values)")
    return None


def map_operation(graph, index, consumers):
    op = graph["oplists"][index]
    name = op["name"]
    kind = op.get("type")
    if kind == "Convolution":
        if name == "/lm/lm_head/Linear":
            return Replacement(index, "output.weight", "linear")
        match = re.fullmatch(r"/layers\.(\d+)/(?:self_attn|mlp)/([a-z0-9_]+)/Linear", name)
        if not match or match.group(2) not in PROJECTIONS:
            raise ValueError(f"unmapped linear operation: {name}")
        return Replacement(index, f"blk.{match.group(1)}.{PROJECTIONS[match.group(2)]}", "linear")
    if kind == "LayerNorm":
        if name == "hidden_states":
            return Replacement(index, "output_norm.weight", "norm")
        match = re.fullmatch(r"/blocks\.(\d+)/(?:self_attn/)?([a-z_]+)/Mul_1_output_0", name)
        if not match or match.group(2) not in NORMS:
            raise ValueError(f"unmapped normalization operation: {name}")
        return Replacement(index, f"blk.{match.group(1)}.{NORMS[match.group(2)]}", "norm")
    if kind == "Const":
        return map_const(graph, index, consumers)
    return None


def build_parameter_plan(graph, available=None):
    consumers = consumers_by_tensor(graph)
    plan = []
    for index in range(len(graph["oplists"])):
        replacement = map_operation(graph, index, consumers)
        if replacement is not None:
            plan.append(replacement)
    plan.append(Replacement(None, "token_embd.weight", "embedding"))
    names = [replacement.tensor_name for replacement in plan]
    if len(names) != len(set(names)):
        raise ValueError("duplicate tensor mapping")
    if available is not None:
        expected = set(available)
        if set(names) != expected:
            raise ValueError(
                f"tensor coverage mismatch: missing={sorted(expected - set(names))}, "
                f"unmapped={sorted(set(names) - expected)}"
            )
    return plan


def validate_structure(graph, source_config, readout):
    counts = defaultdict(int)
    for op in graph["oplists"]:
        counts[op.get("type")] += 1
    expected = {"LinearAttention": 24, "Attention": 8, "Convolution": 249, "LayerNorm": 105}
    for kind, count in expected.items():
        if counts[kind] != count:
            raise ValueError(f"unexpected {kind} count: {counts[kind]} != {count}")
    text = source_config.get("text_config", {})
    if source_config.get("model_type") != "qwen3_5":
        raise ValueError("source is not Qwen3.5")
    if text.get("num_hidden_layers") != 32 or text.get("hidden_size") != 4096:
        raise ValueError("source and graph dimensions differ")
    overrides = source_config.get("modeling_overrides", {})
    if overrides.get("attention", {}).get("full_attention") != "block_bidirectional":
        raise ValueError("source is not the block-bidirectional Elephant checkpoint")
    if overrides.get("readout") != "slot_lm" or readout.get("readout") != "slot_lm":
        raise ValueError("only the Elephant slot_lm readout is supported")
    if readout.get("n_slots") != 256 or "attention" in readout:
        raise ValueError("unexpected decision readout metadata")


def tensor_values(tensor, transform, dims=None):
    if tensor.tensor_type != constants.GGMLQuantizationType.F32:
        raise ValueError("learned constants and norms must be FP32")
    values = tensor.data.astype(np.float32)
    if dims is not None:
        values = values.reshape(dims)
    return values.reshape(-1).tolist()


def apply_replacement(replacement, graph, tensors, output, offset, llm_config):
    tensor = tensors[replacement.tensor_name]
    if replacement.transform == "embedding":
        if tensor.tensor_type not in {
            constants.GGMLQuantizationType.Q4_K,
            constants.GGMLQuantizationType.Q6_K,
            constants.GGMLQuantizationType.Q8_0,
            constants.GGMLQuantizationType.Q4_0,
        }:
            raise ValueError(f"unsupported embedding quantization: {tensor.tensor_type}")
        offset, conv, _, quant_block, quant_bit, header_len = write_embedding_weight(tensor, output, offset)
        external = conv["external"]
        llm_config["tie_embeddings"] = {
            "weight_offset": external[0] + header_len,
            "alpha_offset": external[0] + external[1],
            "alpha_size": external[2],
            "quant_bit": quant_bit,
            "quant_block": quant_block,
            "alpha_dtype": "fp32",
        }
        return offset

    op = graph["oplists"][replacement.op_index]
    main = op["main"]
    if replacement.transform == "norm":
        values = tensor_values(tensor, "norm")
        expected = main.get("external", [0, len(values) * 4])[1] // 4
        if len(values) != expected:
            raise ValueError(f"norm size mismatch for {replacement.tensor_name}: {len(values)} != {expected}")
        main.pop("external", None)
        main["gamma"] = values
        main["beta"] = [0.0] * len(values)
        return offset
    if replacement.transform in {"const", "negative_exp"}:
        main["float32s"] = tensor_values(tensor, replacement.transform, main.get("dims"))
        return offset
    if replacement.transform == "linear":
        common = main["common"]
        input_count, output_count = int(tensor.shape[0]), int(tensor.shape[1])
        if common["inputCount"] != input_count or common["outputCount"] != output_count:
            raise ValueError(
                f"linear shape mismatch for {replacement.tensor_name}: "
                f"{common['inputCount']}x{common['outputCount']} != {input_count}x{output_count}"
            )
        main.pop("quanParameter", None)
        offset, converted, _, _, _, _ = write_external_weight(tensor, output, offset, scale_bit=32)
        main["quanParameter"] = converted["quanParameter"]
        main["external"] = converted["external"]
        bias = np.zeros(output_count, dtype=np.float32)
        offset += output.write(bias.tobytes())
        return offset
    raise ValueError(f"unsupported transform: {replacement.transform}")


def activate(staging, output):
    staging, output = Path(staging), Path(output)
    backup = output.with_name("." + output.name + ".backup")
    if backup.exists() and not output.exists():
        os.replace(backup, output)
    if backup.exists():
        shutil.rmtree(backup)
    if output.exists():
        os.replace(output, backup)
    try:
        os.replace(staging, output)
    except Exception:
        if backup.exists() and not output.exists():
            os.replace(backup, output)
        raise
    if backup.exists():
        shutil.rmtree(backup)


def convert(source, output, mnnconvert):
    source, output = Path(source), Path(output)
    graph = read_json(source / "llm.mnn.json")
    source_config = read_json(source / "source_config.json")
    readout = read_json(source / "readout.json")
    llm_config = read_json(source / "llm_config.json")
    validate_structure(graph, source_config, readout)
    if fold_softplus(graph) != 24:
        raise ValueError("unexpected softplus chain count")
    if set_tiled_head_order(graph) != 24:
        raise ValueError("unexpected gated delta layer count")
    reader = gguf_reader.GGUFReader(str(source / "elephant-1.Q4_K_M.gguf"))
    architecture = reader.fields["general.architecture"].parts[-1].tobytes().decode("utf-8")
    if architecture != "qwen35":
        raise ValueError(f"unexpected GGUF architecture: {architecture}")
    tensors = {tensor.name: tensor for tensor in reader.tensors}
    if len(tensors) != len(reader.tensors):
        raise ValueError("duplicate GGUF tensor names")
    plan = build_parameter_plan(graph, tensors)
    if len(plan) != 427:
        raise ValueError(f"unexpected learned parameter count: {len(plan)}")
    if not Path(mnnconvert).is_file():
        raise FileNotFoundError(f"MNNConvert not found: {mnnconvert}")
    if shutil.disk_usage(output.parent).free < 10_000_000_000:
        raise RuntimeError("insufficient disk for converted Elephant model")

    staging = output.with_name("." + output.name + ".converting")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    llm_config.pop("tie_embeddings", None)
    llm_config["jinja"]["chat_template"] = (source / "chat_template.jinja").read_text(encoding="utf-8")
    llm_config["jinja"]["context"] = {"enable_thinking": False}
    with open(staging / "llm.mnn.weight", "wb") as weight_file:
        offset = 0
        for index, replacement in enumerate(plan):
            print(f"[{index + 1}/{len(plan)}] {replacement.tensor_name}", flush=True)
            offset = apply_replacement(replacement, graph, tensors, weight_file, offset, llm_config)
    weight_size = (staging / "llm.mnn.weight").stat().st_size
    if offset != weight_size:
        raise RuntimeError(f"weight offset mismatch: {offset} != {weight_size}")
    for replacement in plan:
        if replacement.op_index is None:
            continue
        op = graph["oplists"][replacement.op_index]
        external = op["main"].get("external")
        if external and external[0] + sum(external[1:4]) > weight_size:
            raise RuntimeError(f"weight reference is out of bounds: {op['name']}")
    write_json(staging / "llm.mnn.json", graph)
    write_json(staging / "llm_config.json", llm_config)
    subprocess.run([
        str(mnnconvert), "-f", "JSON", "--modelFile", str(staging / "llm.mnn.json"),
        "--MNNModel", str(staging / "llm.mnn"),
    ], check=True)
    if not (staging / "llm.mnn").is_file() or (staging / "llm.mnn").stat().st_size < 1_000_000:
        raise RuntimeError("MNNConvert did not produce a plausible model graph")

    runtime_config = read_json(source / "config.json")
    runtime_config.update({
        "backend_type": "cpu",
        "precision": "normal",
        "thread_num": 4,
        "max_new_tokens": 0,
        "max_all_tokens": 16384,
        "use_mmap": True,
        "use_cached_mmap": False,
        "kvcache_mmap": True,
        "block_size": 0,
        "jinja": {"context": {"enable_thinking": False}},
    })
    write_json(staging / "config.json", runtime_config)
    for name in ("elephant-1.Q4_K_M.gguf", "tokenizer.txt", "visual.mnn", "visual.mnn.weight"):
        shutil.copyfile(source / name, staging / name)
    decision = {
        "protocol": "qev-labels-v2",
        "modelType": "qwen3_5",
        "modelRepo": MODEL_REPO,
        "modelRevision": MODEL_REVISION,
        "qkvHeadOrder": "gguf-tiled-v",
        "attention": {"full_attention": "block_bidirectional", "linear_attention": "causal"},
        "readout": readout,
        "maxSequenceTokens": 16384,
        "chatTemplate": (source / "chat_template.jinja").read_text(encoding="utf-8"),
        "excludedTwoLetterLabels": EXCLUDED_TWO_LETTER_LABELS,
        "chatTemplateSha256": sha256(source / "chat_template.jinja"),
    }
    write_json(staging / "decision.json", decision)
    provenance = {
        "model": {"repo": MODEL_REPO, "revision": MODEL_REVISION, "license": "MIT"},
        "graph": {"repo": GRAPH_REPO, "revision": GRAPH_REVISION},
        "quantization": {"repo": QUANT_REPO, "revision": QUANT_REVISION, "method": "Q4_K_M", "official": False},
        "protocol": {"repo": "OmniJev/OneJev", "revision": ONEJEV_REVISION, "license": "Apache-2.0"},
    }
    write_json(staging / "provenance.json", provenance)
    artifacts = [
        "config.json", "llm_config.json", "decision.json", "provenance.json",
        "elephant-1.Q4_K_M.gguf",
    ]
    spec = {
        "id": "elephant-1",
        "name": "Elephant-1 9B",
        "family": "qwen3_5",
        "kind": "decision",
        "repo": MODEL_REPO,
        "revision": MODEL_REVISION,
        "localOnly": True,
        "files": [
            {"path": name, "size": (staging / name).stat().st_size,
             "sha256": sha256(staging / name), "url": ""}
            for name in artifacts
        ],
    }
    manifest = {"schemaVersion": 1, "models": [spec]}
    write_json(staging / "manifest.json", manifest)
    write_json(source / "catalog-entry.json", spec)
    for artifact in spec["files"]:
        path = staging / artifact["path"]
        if path.stat().st_size != artifact["size"] or sha256(path) != artifact["sha256"]:
            raise RuntimeError(f"artifact verification failed: {path}")
    activate(staging, output)
    print(f"Elephant model ready: {output}")
    print(f"Catalog entry: {source / 'catalog-entry.json'}")
    print(f"Converted weight: {weight_size} bytes; learned tensors replaced: {len(plan)}")
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--mnnconvert", required=True)
    args = parser.parse_args()
    convert(args.source, args.output, args.mnnconvert)


if __name__ == "__main__":
    main()
