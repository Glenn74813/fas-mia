import { useRef, useState, type ReactNode } from 'react';
import { Button } from './Button';

interface Props {
  label: string;
  title: string;
  children: ReactNode;
  onConfirm: () => Promise<unknown>;
  variant?: 'primary' | 'danger' | 'secondary';
  disabled?: boolean;
}

/** A button that asks for confirmation in a modal dialog before performing a consequential action. */
export function ConfirmButton({ label, title, children, onConfirm, variant = 'danger', disabled }: Props) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [busy, setBusy] = useState(false);

  const confirm = async () => {
    setBusy(true);
    try {
      await onConfirm();
      dialog.current?.close();
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Button variant={variant === 'danger' ? 'secondary' : variant} disabled={disabled}
        onClick={() => dialog.current?.showModal()}>{label}</Button>
      <dialog ref={dialog} aria-labelledby={`${label}-title`} className="m-auto w-full max-w-md rounded-lg p-0 backdrop:bg-black/40">
        <div className="space-y-4 p-6">
          <h2 id={`${label}-title`} className="text-lg font-semibold">{title}</h2>
          <div className="text-sm text-slate-700">{children}</div>
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => dialog.current?.close()} disabled={busy}>Cancel</Button>
            <Button variant={variant} busy={busy} onClick={confirm}>Confirm</Button>
          </div>
        </div>
      </dialog>
    </>
  );
}
