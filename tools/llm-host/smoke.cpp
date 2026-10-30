#include "LocalLlmSession.h"

#include <chrono>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using omni::ChatMessages;
using omni::LlmFinish;
using omni::LlmRequest;
using omni::LlmResult;
using omni::LocalLlmSession;

namespace {

void Require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

const char* FinishName(LlmFinish finish) {
    switch (finish) {
        case LlmFinish::Finished: return "finished";
        case LlmFinish::Cancelled: return "cancelled";
        case LlmFinish::MaxTokens: return "max_tokens";
        case LlmFinish::Error: return "error";
    }
    return "unknown";
}

void TestUtf8() {
    const std::string text = "中文、emoji 🙂🚀、English 混合";
    const std::vector<size_t> splits = {1, 2, 3, 4, 5, 7, 13};
    for (const size_t split : splits) {
        std::string actual;
        omni::Utf8Chunker chunker([&](const std::string& part) {
            actual += part;
            return true;
        });
        for (size_t offset = 0; offset < text.size(); offset += split) {
            Require(chunker.Feed(text.data() + offset, std::min(split, text.size() - offset)), "UTF-8 callback stopped");
        }
        Require(chunker.Finish(), "UTF-8 flush failed");
        Require(actual == text, "UTF-8 split changed Unicode content");
    }
    std::string invalid;
    omni::Utf8Chunker chunker([&](const std::string& part) {
        invalid += part;
        return true;
    });
    const std::string broken = "\xF0\x9F";
    chunker.Feed(broken.data(), broken.size());
    chunker.Finish();
    Require(invalid == "\xEF\xBF\xBD", "Incomplete UTF-8 was not handled safely");
    std::cout << "PASS UTF-8 split and malformed-input checks\n";
}

LlmResult Run(LocalLlmSession& session, const LlmRequest& request) {
    std::cout << "\n--- generation ---" << std::endl;
    auto result = session.Generate(request, [](const std::string& token) {
        std::cout << token << std::flush;
        return true;
    });
    std::cout << "\nstatus=" << FinishName(result.finish)
              << " prompt_tokens=" << result.promptTokens
              << " generated_tokens=" << result.generatedTokens
              << " dropped=" << result.droppedMessages
              << " prefill_us=" << result.prefillUs
              << " decode_us=" << result.decodeUs
              << " error=" << result.error << std::endl;
    return result;
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 3) {
        std::cerr << "usage: omni_llm_smoke /path/to/config.json /path/to/cache" << std::endl;
        return 2;
    }
    try {
        TestUtf8();
        LocalLlmSession session;
        std::string error;
        Require(session.Load(argv[1], argv[2], 4, 4096, error), error);
        ChatMessages history = {{"system", "You are a helpful assistant."},
                                {"user", "水的化学式是什么？只回答化学式，不要解释。"}};
        LlmRequest request{"smoke", history, 64, false};
        const auto disabledThinking = session.RenderPrompt(history);
        std::cout << "disabled_prompt=" << disabledThinking << std::endl;
        Require(disabledThinking.find("<think>\n\n</think>\n\n") != std::string::npos,
                "Non-thinking chat template was not applied");
        auto first = Run(session, request);
        Require(first.finish != LlmFinish::Error, first.error);
        Require(!first.text.empty(), "First answer was empty");
        std::string formula = first.text;
        for (const char formatting : {'_', '$', '{', '}'}) {
            formula.erase(std::remove(formula.begin(), formula.end(), formatting), formula.end());
        }
        const std::string subscriptTwo = "₂";
        size_t subscript;
        while ((subscript = formula.find(subscriptTwo)) != std::string::npos) formula.replace(subscript, subscriptTwo.size(), "2");
        Require(formula.find("H2O") != std::string::npos,
                "First answer did not contain the chemical formula for water: " + first.text);
        history.emplace_back("assistant", first.text);
        history.emplace_back("user", "它由哪两种元素组成？只回答元素名称。");
        auto second = Run(session, LlmRequest{"smoke", history, 64, false});
        Require(second.finish != LlmFinish::Error, second.error);
        Require(second.text.find("氢") != std::string::npos && second.text.find("氧") != std::string::npos,
                "Second turn did not answer the contextual question: " + second.text);
        history.emplace_back("assistant", second.text);
        history.emplace_back("user", "写一首关于雨天的长诗。");
        int callbackCount = 0;
        auto cancelled = session.Generate(LlmRequest{"smoke", history, 128, false},
                                          [&](const std::string&) {
                                              if (++callbackCount == 3) {
                                                  session.Cancel();
                                                  return false;
                                              }
                                              return true;
                                          });
        std::cout << "\ncancel_status=" << FinishName(cancelled.finish)
                  << " partial=" << cancelled.text << std::endl;
        Require(cancelled.finish == LlmFinish::Cancelled, "Cancellation did not stop generation");
        Require(!cancelled.text.empty(), "Cancellation did not preserve streamed output");
        Require(cancelled.text.find("\xEF\xBF\xBD") == std::string::npos,
                "Cancellation flushed an incomplete UTF-8 token");
        if (!cancelled.text.empty()) history.emplace_back("assistant", cancelled.text);
        history.emplace_back("user", "现在不要写诗。只回答：重新开始。");
        auto retry = Run(session, LlmRequest{"smoke", history, 32, false});
        Require(retry.finish != LlmFinish::Error && !retry.text.empty(), "Retry after cancellation failed");
        Require(retry.text.find("重新开始") != std::string::npos && retry.text.find("雨") == std::string::npos,
                "Retry followed the cancelled poem instead of the new instruction: " + retry.text);

        ChatMessages thinkingHistory = {{"system", "You are a helpful assistant."},
                                       {"user", "2+2 等于几？只回答数字。"}};
        auto thinkingResult = Run(session, LlmRequest{"thinking", thinkingHistory, 512, true});
        Require(thinkingResult.finish != LlmFinish::Error, thinkingResult.error);
        const auto enabledThinking = session.RenderPrompt(thinkingHistory);
        const std::string thinkingSuffix = "<|im_start|>assistant\n<think>\n";
        Require(enabledThinking.size() >= thinkingSuffix.size() &&
                enabledThinking.compare(enabledThinking.size() - thinkingSuffix.size(), thinkingSuffix.size(), thinkingSuffix) == 0,
                "Thinking mode was not applied to the chat template");
        const auto thinkEnd = thinkingResult.text.find("</think>");
        const bool completedThinking = thinkingResult.finish == LlmFinish::Finished &&
            thinkEnd != std::string::npos && thinkingResult.text.find("4", thinkEnd + 8) != std::string::npos;
        const bool boundedThinking = thinkingResult.finish == LlmFinish::MaxTokens &&
            thinkingResult.generatedTokens == 512 && !thinkingResult.text.empty();
        Require(completedThinking || boundedThinking,
                "Thinking completion or token-limit handling failed: " + thinkingResult.text);
        if (boundedThinking) {
            std::cout << "NOTE thinking reached the configured cap; Qwen3.5-0.8B documents possible thinking loops\n";
        }
        std::cout << "PASS Chinese two-turn, cancellation/retry and real thinking/limit checks\n";

        LocalLlmSession smallContext;
        Require(smallContext.Load(argv[1], std::string(argv[2]) + "-small", 4, 384, error), error);
        ChatMessages longHistory = {{"system", "You are a helpful assistant."}};
        for (int i = 0; i < 160; ++i) {
            std::string padding;
            for (int j = 0; j < 80; ++j) padding += "字";
            longHistory.emplace_back("user", "请解释本地推理的上下文预算。" + padding);
            longHistory.emplace_back("assistant", "本地模型只使用有限的上下文。" + padding);
        }
        longHistory.emplace_back("user", "只回答：收到。");
        auto trimmed = Run(smallContext, LlmRequest{"small", longHistory, 32, false});
        Require(trimmed.finish != LlmFinish::Error, trimmed.error);
        Require(trimmed.droppedMessages > 0, "Old turns were not dropped to fit the context budget");
        Require(trimmed.text.find("收到") != std::string::npos,
                "Long-conversation trimming lost the current instruction: " + trimmed.text);
        std::cout << "PASS context-budget trimming\nALL HOST LLM CHECKS PASSED\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "HOST LLM CHECK FAILED: " << error.what() << std::endl;
        return 1;
    }
}
