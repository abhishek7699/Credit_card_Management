import { useState } from 'react';
import { useGetReservationsQuery } from './reservationsApi';
import { Toast } from '../../components/Toast';
import type { HotelPaymentStatus, Reservation, ReservationStatus } from '../../types/hotel';

function ReservationStatusBadge({ status }: { status: ReservationStatus }) {
  const classes: Record<ReservationStatus, string> = {
    UPCOMING: 'bg-blue-100 text-blue-800',
    CURRENT: 'bg-green-100 text-green-800',
    PAST: 'bg-gray-100 text-gray-600',
    CANCELLED: 'bg-red-100 text-red-800',
  };
  const labels: Record<ReservationStatus, string> = {
    UPCOMING: 'Upcoming',
    CURRENT: 'Current',
    PAST: 'Past',
    CANCELLED: 'Cancelled',
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

function PaymentBadge({ status }: { status: HotelPaymentStatus }) {
  const isGreen = status === 'AUTHORIZED' || status === 'SETTLED';
  return (
    <span
      className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${
        isGreen ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
      }`}
      data-payment-status={status}
    >
      {status.charAt(0) + status.slice(1).toLowerCase()}
    </span>
  );
}

function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

function fmt(n: number) {
  return n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
}

interface ExpandedDetailProps {
  reservation: Reservation;
}

function ExpandedDetail({ reservation: r }: ExpandedDetailProps) {
  return (
    <div className="grid gap-4 p-4 sm:grid-cols-3">
      <div>
        <p className="text-xs text-gray-500">Contact</p>
        <p className="mt-0.5 text-sm font-medium text-gray-900">{r.email}</p>
        <p className="text-sm text-gray-600">{r.phone}</p>
      </div>
      <div>
        <p className="text-xs text-gray-500">Room Details</p>
        <p className="mt-0.5 text-sm font-medium text-gray-900">Room {r.roomNumber} — {r.roomType}</p>
        <p className="text-sm text-gray-600">{r.nights} nights · {fmt(r.total)}</p>
      </div>
      <div>
        <p className="text-xs text-gray-500">Payment</p>
        <div className="mt-0.5">
          <PaymentBadge status={r.paymentStatus} />
        </div>
      </div>
    </div>
  );
}

export default function OwnerReservations() {
  const { data, isLoading, isError } = useGetReservationsQuery();
  const [statusFilter, setStatusFilter] = useState<ReservationStatus | 'ALL'>('ALL');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [expandedId, setExpandedId] = useState<string | null>(null);
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
        Failed to load reservations.
      </div>
    );
  }

  const filtered = data.filter((r) => {
    if (statusFilter !== 'ALL' && r.status !== statusFilter) return false;
    if (fromDate && r.checkIn < fromDate) return false;
    if (toDate && r.checkIn > toDate) return false;
    return true;
  });

  return (
    <div className="space-y-6 p-6">
      <h1 className="text-xl font-bold text-gray-900">Reservations</h1>

      {/* Filters */}
      <div className="flex flex-wrap items-end gap-4 rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
        <div>
          <label className="block text-xs font-medium text-gray-600">Status</label>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as ReservationStatus | 'ALL')}
            className="mt-1 rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          >
            <option value="ALL">All</option>
            <option value="UPCOMING">Upcoming</option>
            <option value="CURRENT">Current</option>
            <option value="PAST">Past</option>
            <option value="CANCELLED">Cancelled</option>
          </select>
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-600">Check-in From</label>
          <input
            type="date"
            value={fromDate}
            onChange={(e) => setFromDate(e.target.value)}
            className="mt-1 rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-600">Check-in To</label>
          <input
            type="date"
            value={toDate}
            onChange={(e) => setToDate(e.target.value)}
            className="mt-1 rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
        </div>
        {(statusFilter !== 'ALL' || fromDate || toDate) && (
          <button
            onClick={() => { setStatusFilter('ALL'); setFromDate(''); setToDate(''); }}
            className="rounded border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50"
          >
            Clear filters
          </button>
        )}
      </div>

      {/* Table */}
      <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-4 py-3 text-left font-medium text-gray-600">Guest</th>
              <th className="px-4 py-3 text-left font-medium text-gray-600">Room</th>
              <th className="px-4 py-3 text-left font-medium text-gray-600">Type</th>
              <th className="px-4 py-3 text-left font-medium text-gray-600">Check-in</th>
              <th className="px-4 py-3 text-left font-medium text-gray-600">Check-out</th>
              <th className="px-4 py-3 text-right font-medium text-gray-600">Nights</th>
              <th className="px-4 py-3 text-right font-medium text-gray-600">Total</th>
              <th className="px-4 py-3 text-left font-medium text-gray-600">Status</th>
              <th className="px-4 py-3 text-left font-medium text-gray-600">Payment</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {filtered.map((r) => (
              <>
                <tr
                  key={r.id}
                  onClick={() => setExpandedId(expandedId === r.id ? null : r.id)}
                  className="cursor-pointer hover:bg-gray-50"
                  data-testid={`reservation-row-${r.id}`}
                >
                  <td className="px-4 py-3 font-medium text-gray-900">{r.guestName}</td>
                  <td className="px-4 py-3 text-gray-600">{r.roomNumber}</td>
                  <td className="px-4 py-3 text-gray-600">{r.roomType}</td>
                  <td className="px-4 py-3 text-gray-600">{fmtDate(r.checkIn)}</td>
                  <td className="px-4 py-3 text-gray-600">{fmtDate(r.checkOut)}</td>
                  <td className="px-4 py-3 text-right text-gray-600">{r.nights}</td>
                  <td className="px-4 py-3 text-right text-gray-900">{fmt(r.total)}</td>
                  <td className="px-4 py-3"><ReservationStatusBadge status={r.status} /></td>
                  <td className="px-4 py-3"><PaymentBadge status={r.paymentStatus} /></td>
                </tr>
                {expandedId === r.id && (
                  <tr key={`${r.id}-detail`} data-testid={`reservation-detail-${r.id}`}>
                    <td colSpan={9} className="bg-indigo-50 px-4">
                      <ExpandedDetail reservation={r} />
                    </td>
                  </tr>
                )}
              </>
            ))}
            {filtered.length === 0 && (
              <tr>
                <td colSpan={9} className="px-4 py-8 text-center text-sm text-gray-500">
                  No reservations match the current filters.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {toast && <Toast message={toast} onDismiss={() => setToast(null)} />}
    </div>
  );
}
