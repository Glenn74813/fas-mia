import { useQuery } from '@tanstack/react-query';
import { roleApi } from '../../api/endpoints';
import { Spinner } from '../../components/Spinner';
import { humanize } from '../../utils/format';

export function RolePicker({ value, onChange }: { value: string[]; onChange: (codes: string[]) => void }) {
  const roles = useQuery({ queryKey: ['roles'], queryFn: roleApi.list });
  if (roles.isPending) return <Spinner label="Loading roles" />;
  return (
    <fieldset className="grid gap-2 sm:grid-cols-2">
      <legend className="mb-2 text-sm font-medium text-slate-700">Roles</legend>
      {roles.data?.map((r) => (
        <label key={r.code} className="flex items-start gap-2 text-sm">
          <input type="checkbox" className="mt-1" aria-label={humanize(r.code)} checked={value.includes(r.code)}
            onChange={(e) => onChange(e.target.checked ? [...value, r.code] : value.filter((c) => c !== r.code))} />
          <span>{humanize(r.code)}<span className="block text-xs text-slate-500">{r.permissionCodes.length} permissions</span></span>
        </label>
      ))}
    </fieldset>
  );
}
