import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ArrowDown, ArrowUp, ChevronLeft, ChevronRight, Search } from 'lucide-react';
import { customersApi } from '@/api/endpoints';
import { EmptyState, ErrorState, LoadingBlock, PageHeader, RiskGradeBadge } from '@/components/common';
import { Button } from '@/components/ui/button';
import { Card, Input, Table, Td, Th, Tr } from '@/components/ui/primitives';
import { Select } from '@/components/ui/radix';
import { formatMoney, formatNumber, formatPercent } from '@/lib/format';
import { SEGMENTS, segmentLabel, statusLabel } from '@/lib/labels';
import { cn } from '@/lib/utils';

const GRADE_FILTERS = [
  { value: 'all', label: 'Tüm dereceler' },
  { value: '1-3', label: 'Düşük risk (1–3)' },
  { value: '4-6', label: 'Orta risk (4–6)' },
  { value: '7-10', label: 'Yüksek risk (7–10)' },
  { value: '9-10', label: 'Çok yüksek (9–10)' },
];

type SortKey = 'id' | 'pd' | 'churn' | 'riskGrade' | 'utilization' | 'buffer' | 'debt' | 'income';

export function CustomersPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get('q') ?? '');
  const segment = params.get('segment') ?? 'all';
  const grade = params.get('grade') ?? 'all';
  const sort = (params.get('sort') ?? 'pd') as SortKey;
  const direction = (params.get('dir') ?? 'desc') as 'asc' | 'desc';
  const page = Number(params.get('page') ?? 0);

  const update = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([k, v]) => (v === null || v === '' ? next.delete(k) : next.set(k, v)));
    if (!('page' in changes)) next.delete('page');
    setParams(next, { replace: true });
  };

  useEffect(() => {
    const t = setTimeout(() => {
      if ((params.get('q') ?? '') !== search) update({ q: search || null });
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  const [minGrade, maxGrade] = grade === 'all' ? [undefined, undefined] : grade.split('-').map(Number);
  const query = useQuery({
    queryKey: ['customers', params.toString()],
    queryFn: () => customersApi.list({ search: params.get('q') ?? undefined, segment: segment === 'all' ? undefined
      : segment, minGrade, maxGrade, sort, direction, page, size: 25 }),
    placeholderData: keepPreviousData,
  });

  const sortBy = (key: SortKey) => update({ sort: key, dir: sort === key && direction === 'desc' ? 'asc' : 'desc' });
  const header = (key: SortKey, label: string, right = false) => (
    <Th className={right ? 'text-right' : undefined}>
      <button onClick={() => sortBy(key)} className={cn('inline-flex items-center gap-1 hover:text-foreground',
        sort === key && 'text-foreground')} aria-label={`${label} sütununa göre sırala`}>
        {label}
        {sort === key && (direction === 'desc' ? <ArrowDown className="size-3" /> : <ArrowUp className="size-3" />)}
      </button>
    </Th>
  );

  const data = query.data;
  return (
    <div>
      <PageHeader eyebrow="Risk analisti" title="Müşteriler"
        description="Son risk skoru ve güncel ikiz özellikleriyle tüm müşteriler. Bir satıra tıklayarak müşterinin
          ikizini açın." />
      <Card className="mb-4 flex flex-col gap-3 p-4 md:flex-row md:items-center">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Müşteri no veya şehir ara"
                 className="pl-9" aria-label="Ara" />
        </div>
        <Select ariaLabel="Segment" className="md:w-48" value={segment} onValueChange={(v) => update({ segment: v === 'all'
          ? null : v })} options={[{ value: 'all', label: 'Tüm segmentler' },
          ...SEGMENTS.map((s) => ({ value: s, label: segmentLabel(s) }))]} />
        <Select ariaLabel="Risk derecesi" className="md:w-52" value={grade}
                onValueChange={(v) => update({ grade: v === 'all' ? null : v })} options={GRADE_FILTERS} />
      </Card>

      <Card className="overflow-hidden">
        {query.isError ? <div className="p-5"><ErrorState error={query.error} onRetry={() => query.refetch()} /></div>
          : query.isLoading ? <LoadingBlock className="p-5" rows={10} />
          : !data?.content.length ? <div className="p-5"><EmptyState title="Eşleşen müşteri yok"
              description="Arama veya filtreleri değiştirin." /></div> : (
            <>
              <Table className={cn(query.isFetching && 'opacity-70 transition-opacity')}>
                <thead><tr className="border-b bg-muted/40">
                  {header('id', 'Müşteri')}<Th>Segment</Th><Th>Durum</Th>
                  {header('riskGrade', 'Derece')}{header('pd', 'PD', true)}{header('churn', 'Churn', true)}
                  {header('utilization', 'Kart kullanımı', true)}{header('buffer', 'Tampon (ay)', true)}
                  {header('debt', 'Borç', true)}{header('income', 'Aylık gelir', true)}
                </tr></thead>
                <tbody>
                  {data.content.map((c) => (
                    <Tr key={c.id} className="cursor-pointer" onClick={() => navigate(`/customers/${c.id}`)}>
                      <Td className="font-medium">#{c.id}<span className="ml-2 text-xs text-muted-foreground">
                        {c.city} · {c.age}</span></Td>
                      <Td>{segmentLabel(c.segment)}</Td>
                      <Td><span className={cn('text-xs', c.status === 'ACTIVE' ? 'text-muted-foreground'
                        : 'font-medium text-negative')}>{statusLabel(c.status)}</span></Td>
                      <Td><RiskGradeBadge grade={c.riskGrade} /></Td>
                      <Td className="text-right font-medium">{formatPercent(c.pd)}</Td>
                      <Td className="text-right">{formatPercent(c.churn)}</Td>
                      <Td className="text-right">{formatPercent(c.utilizationRate, 0)}</Td>
                      <Td className="text-right">{formatNumber(c.liquidityBufferMonths === null ? null
                        : Math.min(c.liquidityBufferMonths, 60), 1)}</Td>
                      <Td className="text-right">{formatMoney(c.totalDebt)}</Td>
                      <Td className="text-right">{formatMoney(c.monthlyNetIncome)}</Td>
                    </Tr>
                  ))}
                </tbody>
              </Table>
              <div className="flex items-center justify-between border-t px-4 py-3 text-sm">
                <span className="text-muted-foreground">{formatNumber(data.totalElements)} müşteri · sayfa {page + 1} /
                  {' '}{formatNumber(Math.max(data.totalPages, 1))}</span>
                <div className="flex gap-2">
                  <Button size="sm" variant="outline" disabled={page === 0}
                          onClick={() => update({ page: String(page - 1) })}><ChevronLeft /> Önceki</Button>
                  <Button size="sm" variant="outline" disabled={page + 1 >= data.totalPages}
                          onClick={() => update({ page: String(page + 1) })}>Sonraki <ChevronRight /></Button>
                </div>
              </div>
            </>
          )}
      </Card>
    </div>
  );
}
