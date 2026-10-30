#ifndef OMNI_DECISION_HPP
#define OMNI_DECISION_HPP

#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace MNN {
namespace Transformer {

struct DecisionQuestion {
    std::string suffix;
    int optionCount = 0;
};

struct DecisionBranch {
    std::vector<float> logits;
    std::vector<float> probabilities;
    double allowedMass = 0.0;
    int suffixTokens = 0;
    int64_t prefillUs = 0;
};

struct DecisionResult {
    int prefixTokens = 0;
    int64_t prefixPrefillUs = 0;
    std::vector<DecisionBranch> branches;
    bool cancelled = false;
    std::string error;
};

class DecisionLlm {
public:
    static DecisionLlm* create(const std::string& configPath, const std::string& cachePath, int threadCount = 4);
    ~DecisionLlm();

    bool load(std::string& error);
    void cancel();
    void reset();
    std::vector<std::string> slotLabels();
    std::string render(const std::string& state, const std::string& suffix);
    std::vector<int> tokenize(const std::string& text);
    bool decide(const std::string& state, const std::vector<DecisionQuestion>& questions,
                DecisionResult& result, const std::function<bool(int, int)>& progress = {});

    static std::vector<std::string> defaultSlotLabels();

private:
    DecisionLlm(const std::string& configPath, const std::string& cachePath, int threadCount);
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

bool decisionAttentionAllowed(int prefixLength, int queryPosition, int keyPosition);

} // namespace Transformer
} // namespace MNN

#endif // OMNI_DECISION_HPP
