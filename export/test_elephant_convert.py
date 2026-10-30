import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np
from gguf import constants

from elephant_convert import (
    Replacement,
    activate,
    apply_replacement,
    build_parameter_plan,
    fold_softplus,
    set_tiled_head_order,
    tensor_values,
    validate_structure,
)


def tensor(name, shape, kind=constants.GGMLQuantizationType.F32, data=None):
    return SimpleNamespace(name=name, shape=shape, tensor_type=kind, data=data)


def operation(name, kind, **kwargs):
    return {"name": name, "type": kind, "main": {}, **kwargs}


class ElephantMappingTest(unittest.TestCase):
    def graph(self):
        ops = [
            operation("/layers.0/self_attn/in_proj_qkv/Linear", "Convolution"),
            operation("/layers.0/mlp/gate_proj/Linear", "Convolution"),
            operation("/lm/lm_head/Linear", "Convolution"),
            operation("/blocks.0/input_layernorm/Mul_1_output_0", "LayerNorm"),
            operation("/blocks.0/self_attn/norm/Mul_1_output_0", "LayerNorm"),
            operation("/blocks.3/self_attn/q_norm/Mul_1_output_0", "LayerNorm"),
            operation("hidden_states", "LayerNorm"),
            operation("/blocks.0/self_attn/Neg_output_0", "Const"),
            operation("onnx::Add_4703", "Const", outputIndexes=[11]),
            operation("/blocks.0/self_attn/Add_output_0", "BinaryOp", inputIndexes=[11]),
            operation("LlmExporter::FusedLinearAttention_721", "Const", outputIndexes=[12]),
            operation("/layers.0/self_attn/FusedLinearAttention", "LinearAttention", inputIndexes=[12]),
            operation("/rotary/Constant_1_output_0", "Const"),
        ]
        ops[7]["main"] = {"dataType": "DT_FLOAT", "float32s": [0.0] * 32}
        ops[8]["main"] = {"dataType": "DT_FLOAT", "float32s": [0.0] * 32}
        ops[10]["main"] = {"dataType": "DT_FLOAT", "dims": [8192, 1, 4], "float32s": [0.0]}
        ops[12]["main"] = {"dataType": "DT_FLOAT", "float32s": [0.0] * 32}
        return {"oplists": ops}

    def test_all_parameter_categories_map_exactly_once(self):
        expected = {
            "blk.0.attn_qkv.weight", "blk.0.ffn_gate.weight", "output.weight",
            "blk.0.attn_norm.weight", "blk.0.ssm_norm.weight", "blk.3.attn_q_norm.weight",
            "output_norm.weight", "blk.0.ssm_a", "blk.0.ssm_dt.bias",
            "blk.0.ssm_conv1d.weight", "token_embd.weight",
        }
        plan = build_parameter_plan(self.graph(), expected)
        self.assertEqual(expected, {item.tensor_name for item in plan})
        self.assertEqual(len(expected), len(plan))

    def test_unexpected_or_missing_tensor_is_fatal(self):
        graph = self.graph()
        expected = {item.tensor_name for item in build_parameter_plan(graph)}
        with self.assertRaisesRegex(ValueError, "tensor coverage mismatch"):
            build_parameter_plan(graph, expected | {"unexpected.weight"})
        with self.assertRaisesRegex(ValueError, "tensor coverage mismatch"):
            build_parameter_plan(graph, expected - {"token_embd.weight"})

    def test_unknown_large_constant_is_fatal(self):
        graph = self.graph()
        graph["oplists"].append(operation("untrained_looking_but_unknown", "Const"))
        graph["oplists"][-1]["main"] = {"dataType": "DT_FLOAT", "float32s": [0.0] * 32}
        with self.assertRaisesRegex(ValueError, "unmapped learned constant"):
            build_parameter_plan(graph)

    def test_structure_rejects_wrong_graph(self):
        with self.assertRaisesRegex(ValueError, "LinearAttention count"):
            validate_structure({"oplists": []}, {}, {})

    def test_softplus_chain_uses_native_bnll(self):
        exp = operation("exp", "UnaryOp", inputIndexes=[0], outputIndexes=[2])
        exp["main"] = {"opType": "EXP", "T": "DT_INVALID"}
        one = operation("one", "Const", outputIndexes=[1])
        one["main"] = {"dataType": "DT_FLOAT", "float32s": [1.0]}
        add = operation("add", "BinaryOp", inputIndexes=[2, 1], outputIndexes=[3])
        add["main"] = {"opType": "ADD", "T": "DT_FLOAT"}
        log = operation("/blocks.0/self_attn/Softplus_output_0", "UnaryOp",
                        inputIndexes=[3], outputIndexes=[4])
        log["main"] = {"opType": "LOG", "T": "DT_INVALID"}
        graph = {"oplists": [exp, one, add, log]}
        self.assertEqual(1, fold_softplus(graph))
        self.assertEqual([0], log["inputIndexes"])
        self.assertEqual({"opType": "BNLL", "T": "DT_FLOAT"}, log["main"])

    def test_gguf_tiled_head_order_is_explicit(self):
        graph = {"oplists": [operation("gdn", "LinearAttention")]}
        graph["oplists"][0]["main"] = {"attn_type": "gated_delta_rule"}
        self.assertEqual(1, set_tiled_head_order(graph))
        self.assertEqual("gated_delta_rule_tiled", graph["oplists"][0]["main"]["attn_type"])

    def test_decay_preserves_gguf_negative_exponential(self):
        replacement = next(item for item in build_parameter_plan(self.graph())
                           if item.tensor_name == "blk.0.ssm_a")
        self.assertEqual("const", replacement.transform)
        source = tensor("blk.0.ssm_a", (2,), data=np.array([-0.07021102, -0.00892633], dtype=np.float32))
        values = tensor_values(source, replacement.transform, [2])
        np.testing.assert_array_equal(values, source.data)

    def test_recurrent_convolution_keeps_mnn_layout(self):
        source = tensor("blk.0.ssm_conv1d.weight", (4, 2), data=np.arange(8, dtype=np.float32))
        self.assertEqual(list(range(8)), tensor_values(source, "const", [2, 1, 4]))

    def test_linear_shape_mismatch_is_fatal(self):
        graph = {"oplists": [{
            "name": "/layers.0/self_attn/q_proj/Linear", "type": "Convolution",
            "main": {"common": {"inputCount": 4096, "outputCount": 8192}},
        }]}
        source = tensor("blk.0.attn_q.weight", (4096, 8191))
        replacement = Replacement(0, source.name, "linear")
        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(ValueError, "shape mismatch"):
            with open(Path(directory) / "weight", "wb") as output:
                apply_replacement(replacement, graph, {source.name: source}, output, 0, {})

    def convert_embedding(self, source):
        replacement = Replacement(None, source.name, "embedding")
        config = {}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "weight"
            with open(path, "wb") as output:
                apply_replacement(replacement, {"oplists": []}, {source.name: source}, output, 0, config)
            return path.read_bytes(), config

    def test_q4k_embedding_preserves_gguf_weight_order(self):
        data = np.zeros((1, 144), dtype=np.uint8)
        data[0, :4] = [0, 60, 0, 0]
        data[0, 4:16] = 1
        data[0, 16:] = np.arange(128, dtype=np.uint8)
        source = tensor("token_embd.weight", (256, 1), constants.GGMLQuantizationType.Q4_K, data)
        content, config = self.convert_embedding(source)
        tie = config["tie_embeddings"]
        expected, actual = [], []
        for group in range(4):
            values = data[0, 16 + group * 32:16 + (group + 1) * 32]
            expected.extend(values & 15)
            expected.extend(values >> 4)
        for group in range(8):
            start = tie["weight_offset"] + group * 16
            values = np.frombuffer(content[start:start + 16], dtype=np.uint8)
            bias, scale = np.frombuffer(content[tie["alpha_offset"] + group * 8:tie["alpha_offset"] + (group + 1) * 8],
                                        dtype="<f4")
            zero = bias - 8 * scale
            converted = np.column_stack(((values >> 4) * scale + zero, (values & 15) * scale + zero))
            actual.extend(converted.reshape(-1))
        self.assertEqual(4, tie["quant_bit"])
        self.assertEqual(32, tie["quant_block"])
        np.testing.assert_array_equal(np.asarray(expected, dtype=np.float32), np.asarray(actual, dtype=np.float32))

    def test_q4_0_embedding_preserves_gguf_weight_order(self):
        data = np.zeros((1, 18), dtype=np.uint8)
        data[0, :2] = [0, 60]
        data[0, 2:] = np.arange(16, dtype=np.uint8)
        source = tensor("token_embd.weight", (32, 1), constants.GGMLQuantizationType.Q4_0, data)
        content, config = self.convert_embedding(source)
        tie = config["tie_embeddings"]
        values = np.frombuffer(content[tie["weight_offset"]:tie["weight_offset"] + 16], dtype=np.uint8)
        actual = np.empty(32)
        actual[::2], actual[1::2] = values >> 4, values & 15
        expected = np.empty(32, dtype=np.float32)
        expected[::2] = np.arange(16)
        expected[1::2] = 0
        np.testing.assert_array_equal(expected, actual)

    def test_q8_0_embedding_preserves_gguf_weight_order(self):
        data = np.zeros((1, 34), dtype=np.uint8)
        data[0, :2] = [0, 60]
        data[0, 2:] = np.arange(32, dtype=np.uint8)
        source = tensor("token_embd.weight", (32, 1), constants.GGMLQuantizationType.Q8_0, data)
        content, config = self.convert_embedding(source)
        tie = config["tie_embeddings"]
        values = np.frombuffer(content[tie["weight_offset"]:tie["weight_offset"] + 32], dtype=np.uint8)
        np.testing.assert_array_equal(np.arange(32), values.astype(np.int16) - 128)

    def test_q6k_embedding_is_rejected_instead_of_misdecoding(self):
        source = tensor("token_embd.weight", (256, 1), constants.GGMLQuantizationType.Q6_K,
                        np.zeros((1, 210), dtype=np.uint8))
        with tempfile.TemporaryDirectory() as directory:
            with open(Path(directory) / "weight", "wb") as output:
                with self.assertRaisesRegex(ValueError, "unsupported embedding quantization"):
                    apply_replacement(Replacement(None, source.name, "embedding"),
                                      {"oplists": []}, {source.name: source}, output, 0, {})


class ElephantActivationTest(unittest.TestCase):
    def test_failed_activation_restores_previous_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            staging = root / ".elephant-1.converting"
            output = root / "elephant-1"
            staging.mkdir()
            output.mkdir()
            (staging / "new").write_text("new")
            (output / "old").write_text("old")
            original_replace = os.replace

            def fail_activation(src, dst):
                if Path(src) == staging:
                    raise OSError("simulated activation failure")
                return original_replace(src, dst)

            with patch("elephant_convert.os.replace", side_effect=fail_activation):
                with self.assertRaisesRegex(OSError, "simulated"):
                    activate(staging, output)
            self.assertEqual("old", (output / "old").read_text())
            self.assertTrue(staging.exists())
            self.assertFalse((root / ".elephant-1.backup").exists())


if __name__ == "__main__":
    unittest.main()
