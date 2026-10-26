import { CircleAlert } from 'lucide-react';
import { ApiError } from '../api/client';
import type { SodConflict } from '../api/types';

/** Renders a backend problem document, including policy violations, SoD conflicts and the correlation ID. */
export function ErrorAlert({ error }: { error: unknown }) {
  if (!error) return null;
  const apiError = error instanceof ApiError ? error : null;
  const message = apiError?.message ?? (error instanceof Error ? error.message : 'Something went wrong.');
  const conflicts = (apiError?.body.conflicts as SodConflict[] | undefined) ?? [];
  return (
    <div role="alert" className="my-3 flex gap-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900">
      <CircleAlert aria-hidden className="mt-0.5 h-4 w-4 shrink-0 text-red-600" strokeWidth={2} />
      <div className="min-w-0">
      <p className="font-medium">{message}</p>
      {apiError && apiError.violations.length > 0 && (
        <ul className="mt-1 list-disc pl-5">{apiError.violations.map((v) => <li key={v}>{v}</li>)}</ul>
      )}
      {apiError && apiError.fieldErrors.length > 0 && (
        <ul className="mt-1 list-disc pl-5">
          {apiError.fieldErrors.map((f) => <li key={f.field}>{f.field}: {f.message}</li>)}
        </ul>
      )}
      {conflicts.length > 0 && (
        <ul className="mt-1 list-disc pl-5">
          {conflicts.map((c) => (
            <li key={`${c.permissionA}-${c.permissionB}`}>{c.permissionA} + {c.permissionB}: {c.rationale}</li>
          ))}
        </ul>
      )}
      {apiError?.correlationId && <p className="mt-2 text-xs text-red-700">Reference: {apiError.correlationId}</p>}
      </div>
    </div>
  );
}
