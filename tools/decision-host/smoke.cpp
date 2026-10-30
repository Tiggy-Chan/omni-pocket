#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <numeric>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

#include "DecisionSession.h"
#include <MNN/Interpreter.hpp>
#include <MNN/expr/ExecutorScope.hpp>
#include <MNN/expr/ExprCreator.hpp>

using omni::DecisionSession;
using MNN::Transformer::DecisionBranch;
using MNN::Transformer::DecisionLlm;
using MNN::Transformer::DecisionQuestion;
using MNN::Transformer::DecisionResult;

namespace {

void Require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

void TestStableSoftplus() {
    MNN::BackendConfig backend;
    backend.precision = MNN::BackendConfig::Precision_Low;
    auto executor = MNN::Express::Executor::newExecutor(MNN_FORWARD_CPU, backend, 1);
    MNN::Express::ExecutorScope scope(executor);
    const std::vector<double> inputs = {-20.0, -12.0, -9.0, -1.0, 0.0, 1.0, 10.0, 20.0, 25.0, 100.0};
    auto input = MNN::Express::_Input({static_cast<int>(inputs.size())}, MNN::Express::NCHW);
    std::copy(inputs.begin(), inputs.end(), input->writeMap<float>());
    const auto output = MNN::Express::_Softplus(input);
    const auto* values = output->readMap<float>();
    Require(values != nullptr, "Softplus did not produce output");
    for (size_t index = 0; index < inputs.size(); ++index) {
        const double expected = std::max(inputs[index], 0.0) + std::log1p(std::exp(-std::fabs(inputs[index])));
        const double tolerance = expected < 0.05 ? 0.00005 : std::max(0.0005, expected * 0.01);
        const double error = std::fabs(values[index] - expected);
        Require(error <= tolerance, "Softplus instability at " + std::to_string(inputs[index]) + ": " +
                                    std::to_string(values[index]) + " vs " + std::to_string(expected));
    }
    std::cout << "PASS low-precision stable softplus\n";
}

void TestMetadata(const std::filesystem::path& root) {
    std::filesystem::remove_all(root);
    std::filesystem::create_directories(root);
    const auto write = [&](const std::string& name, const std::string& content) {
        std::ofstream output(root / name);
        output << content;
        Require(output.good(), "Cannot write metadata test file");
    };
    const std::string config = std::string("{\"model_type\":\"qwen3_5\",\"hidden_size\":4096,"
        "\"layer_nums\":32,\"attention_mask\":\"float\",\"jinja\":{\"chat_template\":\"test\"}}");
    write("llm_config.json", config);
    write("config.json", "{\"base_dir\":\"\",\"backend_type\":\"cpu\",\"kvcache_mmap\":true,\"max_all_tokens\":16384}");
    const std::string valid = R"({"protocol":"qev-labels-v2","modelType":"qwen3_5","modelRepo":"baselabs/elephant-1","modelRevision":"b3f8a93462fb1051a9de031e94a7ff380e254072","qkvHeadOrder":"gguf-tiled-v","attention":{"full_attention":"block_bidirectional","linear_attention":"causal"},"readout":{"readout":"slot_lm","head_dim":256,"n_slots":256},"maxSequenceTokens":16384,"excludedTwoLetterLabels":["BQ","BZ","CJ","CQ","CZ","DQ","DZ","EJ","EY","FJ","FQ","FV","FZ","GJ","GK","GQ","GZ","HJ","IY","JF","JG","JH","JL","JN","JQ"],"chatTemplateSha256":"a4aee8afcf2e0711942cf848899be66016f8d14a889ff9ede07bca099c28f715","chatTemplate":"test"})";
    write("decision.json", valid);
    std::string manifest = R"({"schemaVersion":1,"models":[{"id":"elephant-1","name":"Elephant-1 9B","family":"qwen3_5","kind":"decision","repo":"baselabs/elephant-1","revision":"b3f8a93462fb1051a9de031e94a7ff380e254072","localOnly":true,"files":[)";
    for (const auto* path : {"config.json", "llm_config.json", "decision.json", "provenance.json", "elephant-1.Q4_K_M.gguf"}) {
        if (manifest.back() == '}') manifest += ',';
        const std::string digest = std::string(path) == "elephant-1.Q4_K_M.gguf"
            ? "764ef1834ffe7721f11ff7ed2ba42c3616140493b7505e2928eee057496c95a4" : std::string(64, '0');
        const auto size = std::string(path) == "elephant-1.Q4_K_M.gguf" ? 5629109376LL : 1;
        manifest += "{\"path\":\"" + std::string(path) + "\",\"size\":" + std::to_string(size) +
            ",\"sha256\":\"" + digest + "\",\"url\":\"\"}";
    }
    manifest += "]}]}";
    write("manifest.json", manifest);
    write("provenance.json", "{}");
    write("elephant-1.Q4_K_M.gguf", "");
    const std::string incompatible = "模型图或决策运行配置不兼容";
    const auto loadError = [&]() {
        std::unique_ptr<DecisionLlm> model(DecisionLlm::create((root / "config.json").string(),
            (root / "cache").string(), 4));
        Require(model != nullptr, "Cannot create metadata test model");
        std::string error;
        Require(!model->load(error), "Metadata test unexpectedly loaded without model files");
        return error;
    };
    Require(loadError() == "Elephant GGUF 文件大小不正确", "Valid metadata was rejected");
    const std::vector<std::pair<std::string, std::string>> invalid = {
        {"qev-labels-v2", "invalid-protocol"},
        {"b3f8a93462fb1051a9de031e94a7ff380e254072", "invalid-revision"},
        {"gguf-tiled-v", "grouped"},
        {"block_bidirectional", "causal"},
        {"slot_lm", "token"},
        {"a4aee8afcf2e0711942cf848899be66016f8d14a889ff9ede07bca099c28f715", "invalid-template"},
        {"\"chatTemplate\":\"test\"", "\"chatTemplate\":\"changed\""},
    };
    for (const auto& item : invalid) {
        auto document = valid;
        const auto position = document.find(item.first);
        Require(position != std::string::npos, "Missing metadata fixture field");
        document.replace(position, item.first.size(), item.second);
        write("decision.json", document);
        Require(loadError() == incompatible, "Invalid metadata was accepted: " + item.first);
    }
    auto wrongLength = valid;
    wrongLength.replace(wrongLength.find("16384"), 5, "1");
    write("decision.json", wrongLength);
    Require(loadError() == incompatible, "Invalid sequence limit was accepted");
    std::filesystem::remove(root / "decision.json");
    Require(loadError() == incompatible, "Missing decision metadata was accepted");
    write("decision.json", "{");
    Require(loadError() == incompatible, "Malformed decision metadata was accepted");
    write("decision.json", valid);
    write("manifest.json", "{\"models\":[]}");
    Require(loadError() == "缺少已校验的 Elephant GGUF 模型", "Empty manifest was accepted");
    write("manifest.json", manifest);
    std::filesystem::remove(root / "manifest.json");
    Require(loadError() == incompatible, "Missing manifest was accepted");
    write("manifest.json", manifest);
    std::filesystem::remove_all(root);
    std::cout << "PASS decision metadata validation\n";
}

void TestBasics(const std::string& executable) {
    TestMetadata(std::filesystem::path(executable).parent_path() / "decision-metadata");
    TestStableSoftplus();
    const auto labels = DecisionLlm::defaultSlotLabels();
    Require(labels.size() == 256, "Unexpected OneJev slot count");
    Require(std::set<std::string>(labels.begin(), labels.end()).size() == labels.size(), "Duplicate slot labels");
    Require(labels.front() == "A" && labels[25] == "Z" && labels[26] == "AA", "Slot order changed");
    Require(std::find(labels.begin(), labels.end(), "BQ") == labels.end(), "Excluded slot was retained");
    Require(MNN::Transformer::decisionAttentionAllowed(4, 0, 3), "Prefix must see its future tokens");
    Require(!MNN::Transformer::decisionAttentionAllowed(4, 3, 4), "Prefix must not see a question suffix");
    Require(MNN::Transformer::decisionAttentionAllowed(4, 4, 3), "Suffix must see the prefix");
    Require(MNN::Transformer::decisionAttentionAllowed(4, 4, 5), "Suffix must see its future options");
    Require(MNN::Transformer::decisionAttentionAllowed(4, 3, 2), "Past tokens must remain visible");
    Require(!MNN::Transformer::decisionAttentionAllowed(0, 0, 0), "Empty prefix must be rejected");
    std::cout << "PASS block-bidirectional mask and slot metadata\n";
}

int TopOption(const DecisionBranch& branch) {
    Require(!branch.probabilities.empty(), "Empty branch probabilities");
    return static_cast<int>(std::max_element(branch.probabilities.begin(), branch.probabilities.end()) -
                            branch.probabilities.begin());
}

void CheckBranch(const DecisionBranch& branch, size_t options) {
    Require(branch.logits.size() == options && branch.probabilities.size() == options, "Wrong option count");
    double sum = 0.0;
    float largestDeviation = 0.0f;
    for (const auto probability : branch.probabilities) {
        Require(std::isfinite(probability) && probability >= 0.0f && probability <= 1.0f, "Invalid probability");
        sum += probability;
        largestDeviation = std::max(largestDeviation, std::fabs(probability - 1.0f / options));
    }
    Require(std::fabs(sum - 1.0) < 0.0001, "Option probabilities are not normalized");
    Require(largestDeviation > 0.0001f, "All options have identical probabilities");
    Require(std::isfinite(branch.allowedMass) && branch.allowedMass > 0.0 && branch.allowedMass <= 1.000001,
            "Invalid allowed-token mass");
}

void CompareBranches(const DecisionBranch& left, const DecisionBranch& right, float tolerance = 0.0001f) {
    Require(left.probabilities.size() == right.probabilities.size(), "Cannot compare different option counts");
    for (size_t index = 0; index < left.probabilities.size(); ++index) {
        const auto difference = std::fabs(left.probabilities[index] - right.probabilities[index]);
        Require(difference <= tolerance, "Decision probabilities differ by " + std::to_string(difference));
    }
}

int RequestDirectories(const std::string& path) {
    int count = 0;
    for (const auto& entry : std::filesystem::directory_iterator(path)) {
        if (entry.path().filename().string().rfind("request-", 0) == 0) ++count;
    }
    return count;
}

DecisionResult Run(DecisionSession& session, const std::string& state,
                   const std::vector<DecisionQuestion>& questions, const std::string& label) {
    std::vector<int> progress;
    std::cout << label << " started" << std::endl;
    const auto result = session.Decide(state, questions, [&](int done, int) {
        progress.push_back(done);
        return true;
    });
    Require(result.error.empty() && !result.cancelled, label + " failed: " + result.error);
    Require(result.branches.size() == questions.size(), label + " returned incomplete answers");
    Require(result.prefixTokens > 0 && result.prefixPrefillUs > 0, label + " has no real prefix timing");
    Require(progress.size() == questions.size() + 1 && progress.front() == 0, label + " progress is invalid");
    std::cout << label << " prefix_tokens=" << result.prefixTokens << " prefix_us=" << result.prefixPrefillUs << '\n';
    for (size_t index = 0; index < result.branches.size(); ++index) {
        const auto& branch = result.branches[index];
        CheckBranch(branch, questions[index].optionCount);
        std::cout << "  branch=" << index << " tokens=" << branch.suffixTokens << " prefill_us=" << branch.prefillUs
                  << " allowed_mass=" << branch.allowedMass << " probabilities=";
        if (branch.probabilities.size() > 16) {
            const int top = TopOption(branch);
            std::cout << "top=" << top << " top_probability=" << branch.probabilities[top]
                      << " top_logit=" << branch.logits[top] << std::endl;
            continue;
        }
        for (const auto probability : branch.probabilities) std::cout << probability << ' ';
        std::cout << "top=" << TopOption(branch) << " logits=";
        for (const auto logit : branch.logits) std::cout << logit << ' ';
        std::cout << std::endl;
    }
    return result;
}

DecisionQuestion LongQuestion(int begin, int end) {
    const auto labels = DecisionLlm::defaultSlotLabels();
    std::string suffix = "Question: Which option is explicitly required?\n\nOptions:\n";
    for (int index = begin; index < end; ++index) {
        const std::string name = index == 41 ? "refund" : "option-" + std::to_string(index);
        suffix += labels[index - begin] + ". " + name + ": alternative\n";
    }
    suffix += labels[end - begin] +
        ". none of the options listed here: The correct option is not among the options listed in this question.\n\n";
    suffix += "Answer with one label: ";
    for (int index = 0; index <= end - begin; ++index) {
        suffix += labels[index];
        suffix += index == end - begin ? "." : ", ";
    }
    return {suffix, end - begin + 1};
}

void TestLongChoices(DecisionSession& session) {
    const auto result = Run(session, "Select the option named refund. It is the only correct option.",
                            {LongQuestion(0, 150), LongQuestion(150, 300)}, "LONG_CHOICE_300");
    Require(result.branches.size() == 2, "Long choice did not produce two chunks");
    Require(TopOption(result.branches[0]) == 41, "Long choice did not select the named target");
    Require(TopOption(result.branches[1]) == 150,
            "Long choice did not select the reserved option when the target was absent");
    const double totalWeight = (1.0 - result.branches[0].probabilities[150]) +
        (1.0 - result.branches[1].probabilities[150]);
    Require(totalWeight > 0.0, "Long choice has no non-reserved probability mass");
    std::vector<double> merged(300, 0.0);
    for (int index = 0; index < 300; ++index) {
        merged[index] = index < 150 ? result.branches[0].probabilities[index] / totalWeight
                                    : result.branches[1].probabilities[index - 150] / totalWeight;
    }
    const double sum = std::accumulate(merged.begin(), merged.end(), 0.0);
    Require(std::fabs(sum - 1.0) < 0.0001, "Long choice probabilities are not normalized");
    Require(std::max_element(merged.begin(), merged.end()) - merged.begin() == 41,
            "Merged long choice probabilities selected the wrong option");
    std::cout << "PASS 300-option real chunks and OneJev weighted merge" << std::endl;
}

} // namespace

int main(int argc, char** argv) {
    try {
        TestBasics(argv[0]);
        if (argc == 2 && std::string(argv[1]) == "--protocol-only") {
            std::cout << "ALL DECISION PROTOCOL CHECKS PASSED\n";
            return 0;
        }
        const bool renderOnly = argc == 4 && std::string(argv[3]) == "--render-only";
        if (argc != 3 && !renderOnly) {
            std::cerr << "usage: omni_decision_smoke /path/to/config.json /path/to/cache [--render-only]\n";
            return 2;
        }
        DecisionSession session;
        std::string error;
        Require(session.Load(argv[1], argv[2], 4, error), error);
        const auto slots = session.SlotLabels();
        Require(slots.size() >= 26 && slots.size() <= 256, "Invalid validated slot count");
        std::cout << "Validated decision slots: " << slots.size() << '\n';
        const std::string state = "I was charged twice this month, please refund one of them.";
        const DecisionQuestion refund{
            "Question: Is the customer asking for a refund?\n\n"
            "Options:\n"
            "A. yes: the statement is true / the answer is yes\n"
            "B. no: the statement is false / the answer is no\n\n"
            "Answer with one letter: A, B.", 2};
        const DecisionQuestion team{
            "Question: Which team should handle this?\n\n"
            "Options:\n"
            "A. billing: Charges, refunds, invoices\n"
            "B. technical: App or site faults\n"
            "C. fraud: Suspected unauthorised use\n\n"
            "Answer with one letter: A, B, C.", 3};
        const DecisionQuestion urgency{
            "Rate the state: How urgent is this?\n\n"
            "Options:\n"
            "A. level 0: Can wait\n"
            "B. level 1: Today\n"
            "C. level 2: Blocking the customer now\n\n"
            "Answer with one letter: A, B, C.", 3};
        const std::vector<DecisionQuestion> questions = {refund, team, urgency};
        const auto rendered = session.Render(state, refund.suffix);
        Require(rendered.find("<state>\n" + state) != std::string::npos, "State block was not rendered");
        Require(rendered.find(refund.suffix) != std::string::npos, "Question suffix was not rendered verbatim");
        Require(!session.Tokenize(rendered).empty(), "Rendered prompt cannot be tokenized");
        Require(rendered.find("<think>\n\n</think>\n\n") != std::string::npos, "Decision prompt must disable thinking");
        std::cout << "Rendered decision prompt and tokenizer verified\n";
        if (renderOnly) {
            std::cout << rendered << '\n';
            std::cout << "Slot token ids:";
            for (const auto& label : slots) std::cout << ' ' << session.Tokenize(label).at(0);
            std::cout << '\n';
            return 0;
        }

        const auto first = Run(session, state, questions, "OFFICIAL_TICKET");
        Require(TopOption(first.branches[0]) == 0, "Refund question did not select yes");
        Require(TopOption(first.branches[1]) == 0, "Ticket was not routed to billing");
        Require(RequestDirectories(argv[2]) == 0, "Request cache was not cleaned after success");

        const auto repeat = Run(session, state, questions, "REPEAT");
        for (size_t index = 0; index < questions.size(); ++index) CompareBranches(first.branches[index], repeat.branches[index]);

        const std::vector<DecisionQuestion> reordered = {team, refund, urgency};
        const auto order = Run(session, state, reordered, "REORDERED");
        CompareBranches(first.branches[1], order.branches[0]);
        CompareBranches(first.branches[0], order.branches[1]);
        CompareBranches(first.branches[2], order.branches[2]);

        for (size_t index = 0; index < questions.size(); ++index) {
            const auto independent = Run(session, state, {questions[index]}, "INDEPENDENT_" + std::to_string(index));
            Require(independent.branches.size() == 1, "Independent decision returned extra branches");
            CompareBranches(first.branches[index], independent.branches[0]);
        }

        const auto other = Run(session, "The application crashes whenever I open the billing page.", {team}, "NEW_STATE");
        Require(TopOption(other.branches[0]) == 1, "New state reused the previous ticket decision");
        TestLongChoices(session);

        session.Reset();
        const auto cancelled = session.Decide(state, questions, [&](int done, int) {
            if (done == 0) {
                session.Cancel();
                return false;
            }
            return true;
        });
        Require(cancelled.cancelled && cancelled.branches.empty(), "Cancellation returned a partial answer");
        Require(RequestDirectories(argv[2]) == 0, "Request cache was not cleaned after cancellation");

        const auto empty = session.Decide(state, {}, {});
        Require(!empty.error.empty(), "Empty request was accepted");
        const auto oversized = session.Decide(std::string(200000, 'x'), {refund}, {});
        Require(!oversized.error.empty(), "Oversized request was silently truncated or accepted");
        const auto oversizedSuffix = session.Decide(state, {{std::string(200000, 'x'), 2}}, {});
        Require(!oversizedSuffix.error.empty(), "Oversized question was silently truncated or accepted");
        const auto tooMany = session.Decide(state, std::vector<DecisionQuestion>(33, refund), {});
        Require(!tooMany.error.empty(), "Too many questions were accepted");
        Require(RequestDirectories(argv[2]) == 0, "Request cache was not cleaned after errors");
        std::cout << "PASS real decision probabilities, cache isolation, cancellation and request validation\n";
        std::cout << "ALL DECISION CHECKS PASSED\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "DECISION CHECK FAILED: " << error.what() << '\n';
        return 1;
    }
}
