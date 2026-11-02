import React, { useRef, useEffect } from 'react';

export interface SandboxedIframeProps {
  content: string;
  title?: string;
  minHeight?: number;
  className?: string;
  style?: React.CSSProperties;
}

function escapeHtml(str: string | null | undefined): string {
  if (!str) return '';
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function sanitizeRawContent(content: string | null | undefined): string {
  if (!content) return '';
  return content
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
    .replace(/javascript:/gi, 'about:blank#blocked');
}

function generateSafeDoc(rawContent: string | null | undefined): string {
  const content = rawContent ?? '';
  const isHtml = /<[a-z][\s\S]*>/i.test(content);
  const cspMeta = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; form-action 'none'; connect-src 'none'; frame-src 'none';">`;

  if (isHtml) {
    const cleanContent = sanitizeRawContent(content);
    // Wrap with baseline styles to prevent default unstyled margin/font jarring
    return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  ${cspMeta}
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      font-size: 14px;
      line-height: 1.6;
      color: #16294A;
      margin: 16px;
      word-break: break-word;
    }
    img { max-width: 100%; height: auto; }
  </style>
</head>
<body>${cleanContent}</body>
</html>`;
  }

  // Plain text fallback wrapped in safe markup
  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  ${cspMeta}
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      font-size: 14px;
      line-height: 1.6;
      color: #16294A;
      margin: 16px;
      white-space: pre-wrap;
      word-break: break-word;
    }
  </style>
</head>
<body>${escapeHtml(content)}</body>
</html>`;
}

export const SandboxedIframe: React.FC<SandboxedIframeProps> = ({
  content,
  title = 'Message Content Preview',
  minHeight = 240,
  className = '',
  style,
}) => {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const srcDoc = generateSafeDoc(content);

  useEffect(() => {
    // Auto-fit height based on content if accessible
    const iframe = iframeRef.current;
    if (!iframe) return;

    const handleLoad = () => {
      try {
        if (iframe.contentWindow?.document?.body) {
          const contentHeight = iframe.contentWindow.document.body.scrollHeight;
          if (contentHeight > minHeight) {
            iframe.style.height = `${contentHeight + 32}px`;
          }
        }
      } catch {
        // Cross-origin restriction expected when sandbox is bare "", fallback to minHeight
      }
    };

    iframe.addEventListener('load', handleLoad);
    return () => iframe.removeEventListener('load', handleLoad);
  }, [srcDoc, minHeight]);

  return (
    <div
      className={`sandboxed-iframe-wrapper ${className}`}
      style={{
        width: '100%',
        backgroundColor: 'var(--color-paper)',
        border: '1px solid var(--color-rail)',
        borderRadius: 'var(--radius-control)',
        overflow: 'hidden',
        ...style,
      }}
    >
      <iframe
        ref={iframeRef}
        title={title}
        srcDoc={srcDoc}
        sandbox=""
        style={{
          width: '100%',
          minHeight,
          border: 'none',
          display: 'block',
          backgroundColor: '#FFFFFF',
        }}
      />
    </div>
  );
};

export default SandboxedIframe;
