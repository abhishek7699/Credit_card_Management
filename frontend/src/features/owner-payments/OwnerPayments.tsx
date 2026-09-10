import { useState } from 'react';
import { useGetPaymentsQuery } from './paymentsApi';
import { Toast } from '../../components/Toast';
import type { HotelPaymentStatus } from '../../types/hotel';

function StatusBadge({ status }: { status: HotelPaymentStatus }) {
  const classes: Record<HotelPaymentStatus, string> = {
    AUTHORIZED: 'bg-green-100 text-green-800',
    SETTLED: 'bg-green-100 text-green-800',
    DECLINED: 'bg-red-100 text-red-800',
    FAILED: 'bg-red-100 text-red-800',
  };
  const labels: Record<HotelPaymentStatus, string> = {
    AUTHORIZED: 'Authorized',
    SETTLED: 'Settled',
    DECLINED: 'Declined',
    FAILED: 'Failed',
  };
  return (
    <span
      className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${classes[status]}`}
      data-status={status}
    >
      {labels[status]}
    </span>
  );
}

function fmt(n: number) {
  return n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
}

function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

export default function OwnerPayments() {
  const { data, isLoading, isError } = useGetPaymentsQuery();
  const [toast, setToast] = useState<string | null>(null);

  if (isLoading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div role="status" aria-label="Loading" className="h-8 w-8 animate-spin rounded-full border-4 border-indigo-600 border-t-transparent" />
      </div>
    );
  }

  if (isError || !data) {
    return (
      <div role="alert" className="m-6 rounded border border-red-300 bg-red-50 p-4 text-red-700">
        Failed to load payments data.
      </div>
    );
  }

  const { payout, records } = data;
  const nextDate = fmtDate(payout.nextPayoutDate);

  return (
    <div className="space-y-6 p-6">
      <h1 className="text-xl font-bold text-gray-900">Payments &amp; Payouts</h1>

      {/* Payout summary card */}
      <div className="rounded-lg border-l-4 border-indigo-500 bg-white p-5 shadow-sm">
        <h2 className="mb-3 text-sm font-semibold text-gray-700">Payout Summary</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <p className="text-xs text-gray-500">Next Payout</p>
            <p className="mt-1 text-2xl font-bold text-indigo-700">{fmt(payout.nextPayoutAmount)}</p>
            <p className="text-sm text-gray-500">on {nextDate}</p>
          </div>
          <div>
            <p className="text-xs text-gray-500">Total Processed This Month</p>
            <p className="mt-1 text-2xl font-bold text-gray-900">{fmt(payout.processedThisMonth)}</p>
          </div>
        </div>
      </div>

      {/* Payments table */}
      <div>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-gray-700">Transaction History</h2>
          <button
            onClick={() => setToast('CSV export coming soon')}
            className="rounded border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50"
          >
            Export CSV
          </button>
        </div>

        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white">
          <table className="w-full text-sm">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-4 py-3 text-left font-medium text-gray-600">Guest</th>
                <th className="px-4 py-3 text-left font-medium text-gray-600">Room</th>
                <th className="px-4 py-3 text-right font-medium text-gray-600">Amount</th>
                <th className="px-4 py-3 text-right font-medium text-gray-600">Fee</th>
                <th className="px-4 py-3 text-left font-medium text-gray-600">Status</th>
                <th className="px-4 py-3 text-left font-medium text-gray-600">Date</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {records.map((r) => (
                <tr key={r.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium text-gray-900">{r.guestName}</td>
                  <td className="px-4 py-3 text-gray-600">{r.roomNumber}</td>
                  <td className="px-4 py-3 text-right text-gray-900">{fmt(r.amount)}</td>
                  <td className="px-4 py-3 text-right text-gray-500">{fmt(r.fee)}</td>
                  <td className="px-4 py-3">
                    <StatusBadge status={r.status} />
                  </td>
                  <td className="px-4 py-3 text-gray-500">{fmtDate(r.date)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {toast && <Toast message={toast} onDismiss={() => setToast(null)} />}
    </div>
  );
}
