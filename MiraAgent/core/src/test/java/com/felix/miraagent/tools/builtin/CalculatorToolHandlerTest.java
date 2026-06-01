package com.felix.miraagent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CalculatorToolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CalculatorToolHandler handler = new CalculatorToolHandler();

    private ToolExecutionResult eval(String expr) {
        ObjectNode args = mapper.createObjectNode();
        args.put("expression", expr);
        return handler.execute("c1", args);
    }

    @Test
    void evaluatesPrecedenceAndParentheses() {
        assertEquals("3+4*2 = 11", eval("3+4*2").getModelVisibleContent());
        assertEquals("(3+4)*2 = 14", eval("(3+4)*2").getModelVisibleContent());
    }

    @Test
    void handlesNegativeAndDecimals() {
        assertEquals("-2 + 5 = 3", eval("-2 + 5").getModelVisibleContent());
        assertEquals("1.5*2 = 3", eval("1.5*2").getModelVisibleContent());
    }

    @Test
    void divisionByZeroIsError() {
        var r = eval("1/0");
        assertEquals(ToolStatus.ERROR, r.getStatus());
        assertTrue(r.getError().contains("division by zero"));
    }

    @Test
    void invalidExpressionIsError() {
        assertEquals(ToolStatus.ERROR, eval("3 +* 4").getStatus());
        assertEquals(ToolStatus.ERROR, eval("abc").getStatus());
    }

    @Test
    void blankExpressionIsError() {
        assertEquals(ToolStatus.ERROR, eval("").getStatus());
    }

    @Test
    void riskLevelIsLow() {
        assertEquals(com.felix.miraagent.tools.ToolRiskLevel.LOW,
                CalculatorToolHandler.definition().getRiskLevel());
    }
}
