import { useRef } from 'react';
import { voucherApi } from '../api/endpoints';
import type { DocumentInfo } from '../api/types';
import { formatDateTime } from '../utils/format';
import { Badge } from './Badge';
import { Button } from './Button';
import { ErrorAlert } from './ErrorAlert';

interface Props {
  documents: DocumentInfo[];
  canUpload: boolean;
  onUpload: (file: File) => void;
  onRemove?: (id: string) => void;
  uploading: boolean;
  error: unknown;
  requiredNote?: string;
}

/** Supporting documents of any document type: list, secure download, upload while editable. */
export function DocumentsPanel({ documents, canUpload, onUpload, onRemove, uploading, error, requiredNote }: Props) {
  const fileRef = useRef<HTMLInputElement>(null);
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="font-semibold">Supporting documents {requiredNote && <span className="text-xs font-normal text-slate-500">({requiredNote})</span>}</h2>
        {canUpload && (
          <>
            <input ref={fileRef} type="file" className="hidden" accept=".pdf,.png,.jpg,.jpeg,.csv,.xlsx,.docx" aria-label="Choose supporting document"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) onUpload(f); e.target.value = ''; }} />
            <Button variant="secondary" busy={uploading} onClick={() => fileRef.current?.click()}>Attach file</Button>
          </>
        )}
      </div>
      <ErrorAlert error={error} />
      <ul className="divide-y divide-slate-100 text-sm">
        {documents.map((d) => (
          <li key={d.id} className={`flex flex-wrap items-center justify-between gap-2 py-2 ${d.removedAt ? 'text-slate-400 line-through' : ''}`}>
            <span>
              <button type="button" className="text-brand-700 hover:underline disabled:no-underline" disabled={!!d.removedAt}
                onClick={() => voucherApi.download(d)}>{d.fileName}</button>
              <span className="ml-2 text-xs text-slate-500">{(d.sizeBytes / 1024).toFixed(1)} KB · {d.uploadedBy} · {formatDateTime(d.uploadedAt)}</span>
              {d.scanStatus === 'NOT_SCANNED' && <span className="ml-2"><Badge tone="amber">NOT SCANNED</Badge></span>}
            </span>
            {canUpload && onRemove && !d.removedAt && (
              <button type="button" className="text-xs text-red-700 hover:underline" onClick={() => onRemove(d.id)}>Remove</button>
            )}
          </li>
        ))}
        {documents.length === 0 && <li className="py-2 text-slate-500">No documents attached.</li>}
      </ul>
    </section>
  );
}
