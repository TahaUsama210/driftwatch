import { Check, Copy } from 'lucide-react';
import { useEffect, useId, useState } from 'react';
import { Button } from './Button';

type CopyState = 'idle' | 'copied' | 'failed';

const FEEDBACK_MS = 2000;

interface CodeBlockProps {
  code: string;
  /** Accessible name for the snippet, e.g. "Docker command". */
  label: string;
}

export function CodeBlock({ code, label }: CodeBlockProps) {
  const [state, setState] = useState<CopyState>('idle');
  const labelId = useId();

  useEffect(() => {
    if (state === 'idle') return;
    const timer = setTimeout(() => {
      setState('idle');
    }, FEEDBACK_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [state]);

  const copy = () => {
    // The Clipboard API only exists in secure contexts (HTTPS or localhost).
    if (!('clipboard' in navigator)) {
      setState('failed');
      return;
    }
    navigator.clipboard.writeText(code).then(
      () => {
        setState('copied');
      },
      () => {
        setState('failed');
      },
    );
  };

  return (
    <figure
      aria-labelledby={labelId}
      className="min-w-0 overflow-hidden rounded-md border border-border bg-sunken"
    >
      <figcaption className="flex items-center justify-between gap-2 border-b border-border px-3 py-1.5">
        <span id={labelId} className="text-xs font-medium text-ink-secondary">
          {label}
        </span>
        <Button size="sm" variant="ghost" onClick={copy} aria-label={`Copy ${label}`}>
          {state === 'copied' ? (
            <Check aria-hidden className="size-3.5" />
          ) : (
            <Copy aria-hidden className="size-3.5" />
          )}
          <span aria-hidden>{state === 'copied' ? 'Copied' : 'Copy'}</span>
        </Button>
      </figcaption>
      <pre className="overflow-x-auto p-3 font-mono text-xs leading-relaxed text-ink">
        <code>{code}</code>
      </pre>
      <p role="status" className="sr-only">
        {state === 'copied' ? 'Copied to clipboard' : null}
        {state === 'failed' ? 'Copy failed. Select the text and copy it manually.' : null}
      </p>
    </figure>
  );
}
