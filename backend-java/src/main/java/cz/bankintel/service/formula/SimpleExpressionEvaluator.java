package cz.bankintel.service.formula;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class SimpleExpressionEvaluator {

    private SimpleExpressionEvaluator() {}

    static double evaluate(String expression) {
        List<String> tokens = tokenize(expression);
        return parseExpression(tokens);
    }

    private static List<String> tokenize(String expression) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (Character.isWhitespace(ch)) {
                flush(current, tokens);
                continue;
            }
            if ("+-*/()".indexOf(ch) >= 0) {
                flush(current, tokens);
                tokens.add(String.valueOf(ch));
            } else {
                current.append(ch);
            }
        }
        flush(current, tokens);
        return tokens;
    }

    private static void flush(StringBuilder current, List<String> tokens) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static double parseExpression(List<String> tokens) {
        Deque<String> stack = new ArrayDeque<>(tokens);
        return parseAddSub(stack);
    }

    private static double parseAddSub(Deque<String> tokens) {
        double value = parseMulDiv(tokens);
        while (!tokens.isEmpty()) {
            String op = tokens.peekFirst();
            if (!"+".equals(op) && !"-".equals(op)) {
                break;
            }
            tokens.removeFirst();
            double rhs = parseMulDiv(tokens);
            value = "+".equals(op) ? value + rhs : value - rhs;
        }
        return value;
    }

    private static double parseMulDiv(Deque<String> tokens) {
        double value = parseUnary(tokens);
        while (!tokens.isEmpty()) {
            String op = tokens.peekFirst();
            if (!"*".equals(op) && !"/".equals(op)) {
                break;
            }
            tokens.removeFirst();
            double rhs = parseUnary(tokens);
            value = "*".equals(op) ? value * rhs : rhs == 0.0 ? 0.0 : value / rhs;
        }
        return value;
    }

    private static double parseUnary(Deque<String> tokens) {
        if (tokens.isEmpty()) {
            return 0.0;
        }
        if ("+".equals(tokens.peekFirst())) {
            tokens.removeFirst();
            return parseUnary(tokens);
        }
        if ("-".equals(tokens.peekFirst())) {
            tokens.removeFirst();
            return -parseUnary(tokens);
        }
        if ("(".equals(tokens.peekFirst())) {
            tokens.removeFirst();
            double value = parseAddSub(tokens);
            if (!tokens.isEmpty() && ")".equals(tokens.peekFirst())) {
                tokens.removeFirst();
            }
            return value;
        }
        String token = tokens.removeFirst();
        return Double.parseDouble(token);
    }
}
