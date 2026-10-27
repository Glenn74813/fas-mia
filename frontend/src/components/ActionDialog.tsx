import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Button } from './Button';
import { ErrorAlert } from './ErrorAlert';

export interface ActionSpec {
  title: string;
  text: string;
  confirm: string;
  commentRequired?: boolean;
  commentLabel?: string;
  danger?: boolean;
  minComment?: number;
}

interface Props {
  spec: ActionSpec | null;
  busy: boolean;
  error: unknown;
  onConfirm: (comment: string) => void;
  onClose: () => void;
  children?: ReactNode;
  confirmDisabled?: boolean;
}

/** Modal confirmation for consequential document actions, with an optional/required comment. */
export function ActionDialog({ spec, busy, error, onConfirm, onClose, children, confirmDisabled }: Props) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    if (spec) {
      if (!ref.current?.open) ref.current?.showModal();
    } else {
      ref.current?.close();
    }
  }, [spec]);
  return (
    <dialog ref={ref} aria-labelledby="action-dialog-title" onClose={onClose}
      className="m-auto w-full max-w-lg rounded-lg p-0 backdrop:bg-black/40">
      {spec && (
        // Keyed by the action so the comment starts empty each time a dialog opens.
        <DialogBody key={spec.title} spec={spec} busy={busy} error={error} onConfirm={onConfirm} onClose={onClose}
          confirmDisabled={confirmDisabled}>{children}</DialogBody>
      )}
    </dialog>
  );
}

function DialogBody({ spec, busy, error, onConfirm, onClose, children, confirmDisabled }: Props & { spec: ActionSpec }) {
  const [comment, setComment] = useState('');
  const min = spec.commentRequired ? spec.minComment ?? 5 : 0;
  return (
    <div className="space-y-4 p-6">
      <h2 id="action-dialog-title" className="text-lg font-semibold">{spec.title}</h2>
      <p className="text-sm text-slate-700">{spec.text}</p>
      {children}
      <label className="block text-sm">{spec.commentLabel ?? 'Comment'}{spec.commentRequired ? ' (required)' : ' (optional)'}
        <textarea value={comment} onChange={(e) => setComment(e.target.value)} rows={3} maxLength={1000}
          className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs" /></label>
      <ErrorAlert error={error} />
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={onClose} disabled={busy}>Close</Button>
        <Button variant={spec.danger ? 'danger' : 'primary'} busy={busy}
          disabled={comment.trim().length < min || confirmDisabled} onClick={() => onConfirm(comment)}>{spec.confirm}</Button>
      </div>
    </div>
  );
}

export const COMMON_ACTIONS: Record<string, ActionSpec> = {
  submit: { title: 'Submit for approval?', text: 'The document is locked and routed to the approvers.', confirm: 'Submit' },
  approve: { title: 'Approve this step?', text: 'Your approval is recorded with your name and the time.', confirm: 'Approve' },
  return: { title: 'Return to preparer?', text: 'The preparer can correct and resubmit it.', confirm: 'Return', commentRequired: true },
  reject: { title: 'Reject?', text: 'Rejection is final.', confirm: 'Reject', commentRequired: true, danger: true },
  cancel: { title: 'Cancel this document?', text: 'It is withdrawn and kept for the record.', confirm: 'Cancel document', danger: true },
  post: { title: 'Post to the general ledger?', text: 'An official, immutable journal entry is created.', confirm: 'Post' },
};
