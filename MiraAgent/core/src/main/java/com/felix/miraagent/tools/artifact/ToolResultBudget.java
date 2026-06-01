package com.felix.miraagent.tools.artifact;

public class ToolResultBudget {

    public static final int THRESHOLD_TOKENS = 200;

    public static boolean shouldExternalize(String content) {
        return estimateTokens(content) > THRESHOLD_TOKENS;
    }

    public static int estimateTokens(String content) {
        if (content == null) return 0;
        int tokens = 0;
        for (char c : content.toCharArray()) {
            tokens += (c > 0x2E7F) ? 6 : 2;
        }
        return tokens / 10;
    }
}
