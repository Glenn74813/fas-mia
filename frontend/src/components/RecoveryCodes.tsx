import { Check, Copy, Download } from 'lucide-react';
import { useState } from 'react';
import { Button } from './Button';

/** A freshly issued set of recovery codes, shown once, with copy and download. Never stored by the app. */
export function RecoveryCodes({ codes, username }: { codes: string[]; username?: string }) {
  const [copied, setCopied] = useState(false);
  const text = `AVTECH CAS recovery codes${username ? ` for ${username}` : ''}\n`
    + `Each code works once, in place of the authenticator code.\n\n${codes.join('\n')}\n`;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(codes.join('\n'));
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  const download = () => {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'avtech-cas-recovery-codes.txt';
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="space-y-3">
      <ol aria-label="Recovery codes" className="grid grid-cols-2 gap-x-6 gap-y-1.5 rounded-lg border border-slate-200 bg-slate-50 p-4 font-mono text-sm text-slate-900">
        {codes.map((c) => <li key={c} className="tracking-wide">{c}</li>)}
      </ol>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" onClick={copy}>
          {copied ? <Check aria-hidden className="h-4 w-4" /> : <Copy aria-hidden className="h-4 w-4" />}{copied ? 'Copied' : 'Copy'}
        </Button>
        <Button variant="secondary" onClick={download}><Download aria-hidden className="h-4 w-4" />Download .txt</Button>
      </div>
    </div>
  );
}
