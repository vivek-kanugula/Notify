package com.notify.template.engine;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRenderingEngineTest {

    private TemplateRenderingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TemplateRenderingEngine();
    }

    @Test
    @DisplayName("Renders simple scalar variables in TEXT mode without HTML escaping")
    void renderTextScalars() {
        String template = "Hello {{name}}, your balance is {{balance}} < 100 & status is 'OK'.";
        Map<String, Object> vars = Map.of(
                "name", "Alice",
                "balance", 50
        );

        String result = engine.render(template, vars, ContentType.TEXT);

        assertThat(result).isEqualTo("Hello Alice, your balance is 50 < 100 & status is 'OK'.");
    }

    @Test
    @DisplayName("Renders variables in HTML mode with automatic HTML escaping")
    void renderHtmlEscaping() {
        String template = "<p>Hello {{name}}, welcome to {{service}}!</p>";
        Map<String, Object> vars = Map.of(
                "name", "<script>alert('xss')</script>",
                "service", "Tom & Jerry \"Premium\""
        );

        String result = engine.render(template, vars, ContentType.HTML);

        assertThat(result).contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;");
        assertThat(result).contains("Tom &amp; Jerry &quot;Premium&quot;");
    }

    @Test
    @DisplayName("Renders nested map variables via dot notation")
    void renderNestedMap() {
        String template = "Hello {{user.firstName}} {{user.lastName}}!";
        Map<String, Object> vars = Map.of(
                "user", Map.of(
                        "firstName", "John",
                        "lastName", "Doe"
                )
        );

        String result = engine.render(template, vars, ContentType.TEXT);

        assertThat(result).isEqualTo("Hello John Doe!");
    }

    @Test
    @DisplayName("Throws TEMPLATE_VARIABLE_MISSING (422) when top-level variable is absent")
    void missingTopLevelVariable() {
        String template = "Hello {{name}}, your code is {{code}}.";
        Map<String, Object> vars = Map.of("name", "Alice");

        assertThatThrownBy(() -> engine.render(template, vars, ContentType.TEXT))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_VARIABLE_MISSING);
                    assertThat(ne.getMessage()).contains("code");
                });
    }

    @Test
    @DisplayName("Throws TEMPLATE_VARIABLE_MISSING (422) when nested variable is absent")
    void missingNestedVariable() {
        String template = "Hello {{user.address.city}}!";
        Map<String, Object> vars = Map.of("user", Map.of("name", "Alice"));

        assertThatThrownBy(() -> engine.render(template, vars, ContentType.TEXT))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_VARIABLE_MISSING);
                    assertThat(ne.getMessage()).contains("user.address.city");
                });
    }

    @Test
    @DisplayName("Rejects Mustache sections {{#section}} with VALIDATION_FAILED (422)")
    void rejectSections() {
        String template = "Items: {{#items}}{{name}}{{/items}}";
        assertThatThrownBy(() -> engine.validateTemplateSyntax(template))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ne.getMessage()).contains("sections are not allowed");
                });
    }

    @Test
    @DisplayName("Rejects inverted sections {{^opt}} with VALIDATION_FAILED (422)")
    void rejectInvertedSections() {
        String template = "{{^items}}No items{{/items}}";
        assertThatThrownBy(() -> engine.validateTemplateSyntax(template))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                });
    }

    @Test
    @DisplayName("Rejects partials {{>partial}} with VALIDATION_FAILED (422)")
    void rejectPartials() {
        String template = "Hello {{>header}}";
        assertThatThrownBy(() -> engine.validateTemplateSyntax(template))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ne.getMessage()).contains("partials are not allowed");
                });
    }

    @Test
    @DisplayName("Rejects comments {{!comment}} with VALIDATION_FAILED (422)")
    void rejectComments() {
        String template = "Hello {{! secret comment }} world";
        assertThatThrownBy(() -> engine.validateTemplateSyntax(template))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                });
    }

    @Test
    @DisplayName("Rejects delimiter changes {{=<% %>=}} with VALIDATION_FAILED (422)")
    void rejectDelimiters() {
        String template = "{{=<% %>=}} <%name%>";
        assertThatThrownBy(() -> engine.validateTemplateSyntax(template))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                });
    }

    @Test
    @DisplayName("Rejects unescaped triple braces {{{raw}}} with VALIDATION_FAILED (422)")
    void rejectTripleBraces() {
        String template = "Raw content: {{{rawHtml}}}";
        assertThatThrownBy(() -> engine.validateTemplateSyntax(template))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ne.getMessage()).contains("triple braces");
                });
    }

    @Test
    @DisplayName("Rejects unescaped ampersand {{&raw}} with VALIDATION_FAILED (422)")
    void rejectAmpersandUnescaped() {
        String template = "Raw content: {{&rawHtml}}";
        assertThatThrownBy(() -> engine.validateTemplateSyntax(template))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                });
    }

    @Test
    @DisplayName("Rejects method calls and expressions {{user.getName()}} with VALIDATION_FAILED (422)")
    void rejectExpressions() {
        String template = "Hello {{user.getName()}}";
        assertThatThrownBy(() -> engine.validateTemplateSyntax(template))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ne.getMessage()).contains("Invalid placeholder syntax");
                });
    }

    @Test
    @DisplayName("Rejects non-scalar / complex object variable values")
    void rejectComplexObjectVariables() {
        class CustomObject {
            public String getSecret() { return "secret"; }
        }

        Map<String, Object> vars = Map.of(
                "name", "Alice",
                "obj", new CustomObject()
        );

        assertThatThrownBy(() -> engine.validateVariablesInput(vars))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ne.getMessage()).contains("must be a scalar");
                });
    }

    @Test
    @DisplayName("Extracts unique variables from subject and body")
    void extractVariablesFromSubjectAndBody() {
        String subject = "Order Confirmation: {{orderId}} for {{user.name}}";
        String body = "Dear {{user.name}},\nYour order {{orderId}} has shipped to {{city}}.";

        List<String> vars = engine.extractVariables(body, subject);

        assertThat(vars).containsExactly("orderId", "user.name", "city");
    }

    @Test
    @DisplayName("Accepts and renders valid leading-underscore variable names")
    void validLeadingUnderscoreVariables() {
        String template = "System ID: {{_id}}, Meta: {{_meta}}, Internal: {{__internal_code}}";
        engine.validateTemplateSyntax(template);

        Map<String, Object> vars = Map.of(
                "_id", "sys-123",
                "_meta", "tenant-alpha",
                "__internal_code", "CODE_42"
        );

        String result = engine.render(template, vars, ContentType.TEXT);
        assertThat(result).isEqualTo("System ID: sys-123, Meta: tenant-alpha, Internal: CODE_42");

        List<String> extracted = engine.extractVariables(template, null);
        assertThat(extracted).containsExactly("_id", "_meta", "__internal_code");
    }

    @Test
    @DisplayName("Accepts and renders nested segments starting with letters or underscores")
    void validNestedLeadingUnderscoreSegments() {
        String template = "User: {{_user._profile._id}}, Tenant: {{tenant._config_key}}, Deep: {{a._b.c_1._2}}";
        engine.validateTemplateSyntax(template);

        Map<String, Object> vars = Map.of(
                "_user", Map.of("_profile", Map.of("_id", "prof-999")),
                "tenant", Map.of("_config_key", "cfg-val"),
                "a", Map.of("_b", Map.of("c_1", Map.of("_2", "deep-val")))
        );

        String result = engine.render(template, vars, ContentType.TEXT);
        assertThat(result).isEqualTo("User: prof-999, Tenant: cfg-val, Deep: deep-val");

        List<String> extracted = engine.extractVariables(template, null);
        assertThat(extracted).containsExactly("_user._profile._id", "tenant._config_key", "a._b.c_1._2");
    }

    @Test
    @DisplayName("Rejects invalid variable names starting with digits, containing hyphens, spaces, or special characters")
    void rejectInvalidVariableNames() {
        // Starts with digit
        assertThatThrownBy(() -> engine.validateTemplateSyntax("{{1var}}"))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        // Nested segment starts with digit
        assertThatThrownBy(() -> engine.validateTemplateSyntax("{{user.1id}}"))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        // Hyphens are not allowed
        assertThatThrownBy(() -> engine.validateTemplateSyntax("{{user-name}}"))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        // Spaces inside tag are not allowed
        assertThatThrownBy(() -> engine.validateTemplateSyntax("{{user name}}"))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        // Empty segments / dot syntax violations
        assertThatThrownBy(() -> engine.validateTemplateSyntax("{{user..name}}"))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> engine.validateTemplateSyntax("{{.name}}"))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> engine.validateTemplateSyntax("{{name.}}"))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        // Special characters
        assertThatThrownBy(() -> engine.validateTemplateSyntax("{{user$name}}"))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> engine.validateTemplateSyntax("{{user@name}}"))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
