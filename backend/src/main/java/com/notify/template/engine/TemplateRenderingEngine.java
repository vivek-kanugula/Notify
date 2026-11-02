package com.notify.template.engine;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.ContentType;
import com.samskivert.mustache.Mustache;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe template rendering engine using JMustache.
 *
 * Security and syntax constraints:
 * - Accepts only explicitly supported placeholder grammar: {{variable}} or {{nested.variable}}.
 * - Rejects unsupported Mustache features (sections {{#...}}, inverted sections {{^...}},
 *   partials {{>...}}, comments {{!...}}, delimiters {{=...}}, unescaped {{{...}}} or {{&...}}).
 * - Rejects expression execution, operators, and method invocations.
 * - Enforces that input variables contain only scalar types (String, Number, Boolean) or nested Maps of scalars.
 * - Missing required variables trigger HTTP 422 TEMPLATE_VARIABLE_MISSING.
 * - HTML content type enforces automatic HTML escaping (<, >, &, ", '). TEXT content type disables HTML escaping.
 */
@Component
public class TemplateRenderingEngine {

    // Identifies valid variable placeholders: each path segment starts with a letter or underscore, continued by letters, digits, or underscores
    private static final Pattern VALID_PLACEHOLDER = Pattern.compile(
            "^\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)\\s*\\}\\}$"
    );

    // Matches any {{ ... }} tag
    private static final Pattern ANY_TAG = Pattern.compile("\\{\\{([\\s\\S]*?)\\}\\}");

    // Matches triple-curly unescaped tags {{{ ... }}}
    private static final Pattern TRIPLE_CURLY = Pattern.compile("\\{\\{\\{[\\s\\S]*?\\}\\}\\}");

    /**
     * Validates template syntax for supported placeholder grammar.
     * Throws NotifyException(ErrorCode.VALIDATION_FAILED) if unsupported tags,
     * sections, unescaped tags, or invalid expressions are found.
     */
    public void validateTemplateSyntax(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        // 1. Reject unescaped triple-curly braces {{{ ... }}}
        if (TRIPLE_CURLY.matcher(text).find()) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                    "Unsupported template syntax: unescaped triple braces {{{ ... }}} are not allowed");
        }

        // 2. Check for unmatched single/double curly braces
        // Find every {{ ... }} tag and validate its grammar
        Matcher matcher = ANY_TAG.matcher(text);
        while (matcher.find()) {
            String fullTag = matcher.group(0);
            String inner = matcher.group(1).trim();

            if (inner.startsWith("#") || inner.startsWith("^") || inner.startsWith("/")) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                        "Unsupported template syntax: Mustache sections are not allowed (" + fullTag + ")");
            }
            if (inner.startsWith(">")) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                        "Unsupported template syntax: Mustache partials are not allowed (" + fullTag + ")");
            }
            if (inner.startsWith("!")) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                        "Unsupported template syntax: Mustache comments are not allowed (" + fullTag + ")");
            }
            if (inner.startsWith("=")) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                        "Unsupported template syntax: delimiter changes are not allowed (" + fullTag + ")");
            }
            if (inner.startsWith("&")) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                        "Unsupported template syntax: unescaped & tags are not allowed (" + fullTag + ")");
            }

            // Must match valid placeholder grammar
            Matcher validMatcher = VALID_PLACEHOLDER.matcher(fullTag);
            if (!validMatcher.matches()) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                        "Invalid placeholder syntax in tag: " + fullTag);
            }
        }

        // 3. Ensure no trailing unclosed "{{" exists
        int lastOpen = text.lastIndexOf("{{");
        int lastClose = text.lastIndexOf("}}");
        if (lastOpen > lastClose) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                    "Malformed template: unclosed '{{' detected");
        }
    }

    /**
     * Extracts unique required variable names from subject and body.
     */
    public List<String> extractVariables(String body, String subject) {
        Set<String> variables = new LinkedHashSet<>();
        if (subject != null) {
            extractVariablesFromText(subject, variables);
        }
        if (body != null) {
            extractVariablesFromText(body, variables);
        }
        return new ArrayList<>(variables);
    }

    private void extractVariablesFromText(String text, Set<String> target) {
        if (text == null) {
            return;
        }
        Matcher matcher = ANY_TAG.matcher(text);
        while (matcher.find()) {
            String fullTag = matcher.group(0);
            Matcher valid = VALID_PLACEHOLDER.matcher(fullTag);
            if (valid.matches()) {
                target.add(valid.group(1));
            }
        }
    }

    /**
     * Validates that the input map contains only supported scalar types (String, Number, Boolean)
     * or nested Maps of scalars.
     */
    public void validateVariablesInput(Map<String, Object> variables) {
        if (variables == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            validateValue(entry.getKey(), entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private void validateValue(String keyPath, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String k)) {
                    throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                            "Template variable map key must be a string: " + entry.getKey());
                }
                validateValue(keyPath + "." + k, entry.getValue());
            }
            return;
        }
        throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                "Template variable '" + keyPath + "' must be a scalar (String, Number, Boolean) or Map, but got: "
                        + value.getClass().getSimpleName());
    }

    /**
     * Renders template text with provided variables and content type.
     * Enforces missing-variable check and HTML escaping rules.
     */
    public String render(String templateText, Map<String, Object> variables, ContentType contentType) {
        if (templateText == null) {
            return null;
        }
        Map<String, Object> vars = variables != null ? variables : Collections.emptyMap();

        // Validate syntax
        validateTemplateSyntax(templateText);

        // Validate variable inputs
        validateVariablesInput(vars);

        // Check required variables exist and are non-null
        Set<String> requiredVars = new LinkedHashSet<>();
        extractVariablesFromText(templateText, requiredVars);
        for (String required : requiredVars) {
            Object val = resolveVariable(vars, required);
            if (val == null) {
                throw new NotifyException(ErrorCode.TEMPLATE_VARIABLE_MISSING,
                        "Missing required template variable: " + required);
            }
        }

        // Configure JMustache compiler based on content type
        boolean escapeHtml = contentType == ContentType.HTML;
        Mustache.Compiler compiler = Mustache.compiler()
                .escapeHTML(escapeHtml)
                .defaultValue("")
                .emptyStringIsFalse(true);

        try {
            return compiler.compile(templateText).execute(vars);
        } catch (Exception e) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                    "Template rendering error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Object resolveVariable(Map<String, Object> map, String path) {
        if (map == null || path == null) {
            return null;
        }
        if (map.containsKey(path)) {
            return map.get(path);
        }
        if (path.contains(".")) {
            String[] parts = path.split("\\.", 2);
            Object child = map.get(parts[0]);
            if (child instanceof Map<?, ?> childMap) {
                return resolveVariable((Map<String, Object>) childMap, parts[1]);
            }
            return null;
        }
        return map.get(path);
    }
}
