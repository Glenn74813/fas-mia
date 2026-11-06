import { useMutation } from '@tanstack/react-query';
import { reportingApi } from '../../api/endpoints';
import type { ReportJob } from '../../api/types';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';

const FORMATS = [{ format: 'PDF', label: 'PDF' }, { format: 'XLSX', label: 'Excel' }, { format: 'CSV', label: 'CSV' }] as const;

/** Waits for a queued report job; the report worker picks one up every few seconds. */
async function finished(job: ReportJob): Promise<ReportJob> {
  let current = job;
  for (let i = 0; i < 60 && (current.status === 'QUEUED' || current.status === 'RUNNING'); i++) {
    await new Promise((r) => setTimeout(r, 1500));
    current = await reportingApi.job(current.id);
  }
  if (current.status !== 'DONE') {
    throw new Error(current.error ?? 'The report is still being prepared; it will appear under Reports › My report files.');
  }
  return current;
}

/**
 * PDF, Excel and CSV downloads of a report-job report, on the organisation's letterhead. Each download is queued,
 * audited and kept under Reports › My report files like any other export.
 */
export function ReportDownloadButtons({ reportType, parameters }: { reportType: string; parameters: Record<string, string> }) {
  const download = useMutation({
    mutationFn: async (format: string) => reportingApi.download(await finished(await reportingApi.requestJob(reportType, format, parameters))),
  });
  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex items-center gap-1.5 text-sm">
        <span className="text-slate-500">Download</span>
        {FORMATS.map((f) => (
          <Button key={f.format} variant="secondary" disabled={download.isPending && download.variables !== f.format}
            busy={download.isPending && download.variables === f.format} onClick={() => download.mutate(f.format)}>{f.label}</Button>
        ))}
      </div>
      <ErrorAlert error={download.error} />
    </div>
  );
}
