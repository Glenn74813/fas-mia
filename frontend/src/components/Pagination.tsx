import { Button } from './Button';

interface Props {
  page: number;
  totalPages: number;
  totalItems: number;
  onPage: (page: number) => void;
}

export function Pagination({ page, totalPages, totalItems, onPage }: Props) {
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between px-1 py-3 text-sm text-slate-500">
      <span>{totalItems} record{totalItems === 1 ? '' : 's'} · page {totalPages === 0 ? 0 : page + 1} of {totalPages}</span>
      <div className="flex gap-2">
        <Button variant="secondary" disabled={page <= 0} onClick={() => onPage(page - 1)}>Previous</Button>
        <Button variant="secondary" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next</Button>
      </div>
    </nav>
  );
}
