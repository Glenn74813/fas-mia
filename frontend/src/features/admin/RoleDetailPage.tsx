import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useParams } from 'react-router';
import { roleApi } from '../../api/endpoints';
import type { Permission, SodConflict } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';

export function RoleDetailPage() {
  const { code = '' } = useParams();
  const { hasPermission, user } = useAuth();
  const qc = useQueryClient();
  const role = useQuery({ queryKey: ['role', code], queryFn: () => roleApi.get(code) });
  const catalog = useQuery({ queryKey: ['permissions'], queryFn: roleApi.permissions });
  const [draft, setDraft] = useState<string[] | null>(null);
  const [warnings, setWarnings] = useState<SodConflict[]>([]);
  const [saved, setSaved] = useState(false);
  const editable = hasPermission('ROLE_ADMINISTER') && !user?.roles.includes(code);

  const grouped = useMemo(() => {
    const groups = new Map<string, Permission[]>();
    catalog.data?.forEach((p) => groups.set(p.category, [...(groups.get(p.category) ?? []), p]));
    return [...groups.entries()];
  }, [catalog.data]);

  const save = useMutation({
    mutationFn: () => roleApi.setPermissions(code, role.data!.version, draft ?? role.data!.permissionCodes),
    onSuccess: (res) => {
      setDraft(null);
      qc.setQueryData(['role', code], res.role);
      qc.invalidateQueries({ queryKey: ['roles'] });
      setWarnings(res.warnings);
      setSaved(true);
    },
    onError: () => qc.invalidateQueries({ queryKey: ['role', code] }),
  });

  if (role.isPending || catalog.isPending) return <Spinner />;
  if (role.error) return <ErrorAlert error={role.error} />;
  const selected = draft ?? role.data.permissionCodes;

  return (
    <>
      <PageHeader title={role.data.name} crumbs={[{ label: 'Administration' }, { label: 'Roles', to: '/admin/roles' }, { label: code }]} />
      <div className="space-y-4">
        {saved && !save.isPending && !save.error && <p role="status" className="rounded-md border border-emerald-300 bg-emerald-50 p-3 text-sm">Permissions saved.</p>}
        <ErrorAlert error={save.error} />
        {warnings.length > 0 && (
          <div className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm">
            {warnings.map((w) => <p key={w.permissionA + w.permissionB}>Warning: {w.permissionA} + {w.permissionB} — {w.rationale}</p>)}
          </div>
        )}
        {!editable && <p className="text-sm text-slate-600">Read-only{user?.roles.includes(code) ? ': you cannot edit a role you hold.' : '.'}</p>}
        {grouped.map(([category, perms]) => (
          <fieldset key={category} className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-4">
            <legend className="px-1 text-sm font-semibold">{category}</legend>
            <div className="grid gap-2 sm:grid-cols-2">
              {perms.map((p) => (
                <label key={p.code} className="flex items-start gap-2 text-sm">
                  <input type="checkbox" className="mt-1" aria-label={p.code} disabled={!editable} checked={selected.includes(p.code)}
                    onChange={(e) => { setSaved(false); setDraft(e.target.checked ? [...selected, p.code] : selected.filter((c) => c !== p.code)); }} />
                  <span>{p.code} {p.financial && <Badge tone="amber">FINANCIAL</Badge>}
                    <span className="block text-xs text-slate-500">{p.description}</span></span>
                </label>
              ))}
            </div>
          </fieldset>
        ))}
        {editable && <Button busy={save.isPending} onClick={() => save.mutate()}>Save permissions</Button>}
      </div>
    </>
  );
}
