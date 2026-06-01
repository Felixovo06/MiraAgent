package com.felix.miraagent.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.felix.miraagent.tools.ToolDefinition;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolHandler;
import com.felix.miraagent.tools.ToolRiskLevel;

import java.util.Map;

/**
 * 计算器工具（LOW 风险，纯计算无副作用）。
 * 支持 + - * / 与括号的四则运算，自带安全解析器（不使用脚本 eval）。
 */
public class CalculatorToolHandler implements ToolHandler {

    public static final String NAME = "calculator";

    public static ToolDefinition definition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description("Evaluate an arithmetic expression with + - * / and parentheses, e.g. \"(3+4)*2\".")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "expression", Map.of("type", "string",
                                        "description", "The arithmetic expression to evaluate")),
                        "required", new String[]{"expression"}))
                .riskLevel(ToolRiskLevel.LOW)
                .build();
    }

    @Override
    public ToolExecutionResult execute(String toolCallId, JsonNode arguments) {
        String expr = arguments.path("expression").asText("");
        if (expr.isBlank()) {
            return ToolExecutionResult.error(toolCallId, NAME, "Missing required field: expression");
        }
        try {
            double value = new Parser(expr).parse();
            String rendered = value == Math.rint(value) && !Double.isInfinite(value)
                    ? String.valueOf((long) value)
                    : String.valueOf(value);
            return ToolExecutionResult.success(toolCallId, NAME, expr + " = " + rendered);
        } catch (ArithmeticException e) {
            return ToolExecutionResult.error(toolCallId, NAME, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.error(toolCallId, NAME, "Invalid expression: " + expr);
        }
    }

    /** 递归下降解析器：expr = term (('+'|'-') term)*；term = factor (('*'|'/') factor)*。 */
    private static final class Parser {
        private final String s;
        private int pos = 0;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            double v = expr();
            skipWs();
            if (pos < s.length()) {
                throw new IllegalArgumentException("unexpected char at " + pos);
            }
            return v;
        }

        private double expr() {
            double v = term();
            while (true) {
                skipWs();
                if (consume('+')) {
                    v += term();
                } else if (consume('-')) {
                    v -= term();
                } else {
                    return v;
                }
            }
        }

        private double term() {
            double v = factor();
            while (true) {
                skipWs();
                if (consume('*')) {
                    v *= factor();
                } else if (consume('/')) {
                    double d = factor();
                    if (d == 0) {
                        throw new ArithmeticException("division by zero");
                    }
                    v /= d;
                } else {
                    return v;
                }
            }
        }

        private double factor() {
            skipWs();
            if (consume('(')) {
                double v = expr();
                skipWs();
                if (!consume(')')) {
                    throw new IllegalArgumentException("missing )");
                }
                return v;
            }
            if (consume('-')) {
                return -factor();
            }
            if (consume('+')) {
                return factor();
            }
            return number();
        }

        private double number() {
            skipWs();
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("expected number at " + pos);
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        private boolean consume(char c) {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }
    }
}
