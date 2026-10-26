import type { Decision } from '../api/types';
import { formatDateTime } from '../utils/format';

export function DecisionHistory({ decisions }: { decisions: Decision[] }) {
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <h2 className="mb-3 font-semibold">Decision history</h2>
      <ul className="space-y-1 text-sm">
        {decisions.map((d) => (
          <li key={d.id}><span className="font-mono text-xs">{formatDateTime(d.decidedAt)}</span> — <strong>{d.action}</strong>
            {d.stepCode ? ` (${d.stepCode})` : ''} by {d.actorUsername}{d.comment ? `: “${d.comment}”` : ''}</li>
        ))}
        {decisions.length === 0 && <li className="text-slate-500">Not yet submitted.</li>}
      </ul>
    </section>
  );
}
