/**
 * Utility functions for extracting and rendering template variables like {{name}}.
 */

export function extractTemplateVariables(...texts: (string | null | undefined)[]): string[] {
  const variableSet = new Set<string>();
  const regex = /\{\{\s*([a-zA-Z0-9_.-]+)\s*\}\}/g;

  for (const text of texts) {
    if (!text) continue;
    let match: RegExpExecArray | null;
    while ((match = regex.exec(text)) !== null) {
      if (match[1]) {
        variableSet.add(match[1].trim());
      }
    }
  }

  return Array.from(variableSet);
}

export function interpolateTemplateVariables(
  template: string,
  variables: Record<string, unknown>
): string {
  if (!template) return '';
  return template.replace(/\{\{\s*([a-zA-Z0-9_.-]+)\s*\}\}/g, (_, key) => {
    const cleanKey = key.trim();
    if (!Object.hasOwn(variables, cleanKey)) {
      return `{{${key}}}`;
    }
    const val = variables[cleanKey];
    if (val === undefined || val === null) {
      return `{{${key}}}`;
    }
    return String(val);
  });
}
