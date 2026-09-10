import { useState } from 'react';
import { useGetRatesQuery, useGetRoomTypesQuery } from './roomManagementApi';
import { Toast } from '../../components/Toast';
import type { RoomType } from '../../types/hotel';

const AMENITY_OPTIONS = ['WiFi', 'TV', 'Mini Bar', 'Coffee Maker', 'Safe', 'Balcony', 'Jacuzzi', 'Ocean View'];

interface AddRoomTypeModalProps {
  onClose: () => void;
  onSave: () => void;
}

function AddRoomTypeModal({ onClose, onSave }: AddRoomTypeModalProps) {
  const [name, setName] = useState('');
  const [price, setPrice] = useState('');
  const [count, setCount] = useState('');
  const [amenities, setAmenities] = useState<string[]>([]);

  function toggleAmenity(a: string) {
    setAmenities((prev) => prev.includes(a) ? prev.filter((x) => x !== a) : [...prev, a]);
  }

  return (
    <>
      <div className="fixed inset-0 z-40 bg-black/30" onClick={onClose} aria-hidden="true" />
      <div
        role="dialog"
        aria-label="Add Room Type"
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <div className="w-full max-w-md rounded-xl border border-gray-200 bg-white shadow-xl">
          <div className="flex items-center justify-between border-b border-gray-100 px-5 py-4">
            <h2 className="font-semibold text-gray-900">Add Room Type</h2>
            <button onClick={onClose} aria-label="Close" className="text-gray-400 hover:text-gray-600">✕</button>
          </div>
          <div className="space-y-4 px-5 py-5">
            <div>
              <label className="block text-xs font-medium text-gray-600">Name</label>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="e.g. Standard King"
                className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-medium text-gray-600">Base Price / Night ($)</label>
                <input
                  type="number"
                  value={price}
                  onChange={(e) => setPrice(e.target.value)}
                  placeholder="149"
                  className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-gray-600">Room Count</label>
                <input
                  type="number"
                  value={count}
                  onChange={(e) => setCount(e.target.value)}
                  placeholder="4"
                  className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>
            </div>
            <div>
              <p className="text-xs font-medium text-gray-600">Amenities</p>
              <div className="mt-2 flex flex-wrap gap-2">
                {AMENITY_OPTIONS.map((a) => (
                  <label key={a} className="flex cursor-pointer items-center gap-1.5">
                    <input
                      type="checkbox"
                      checked={amenities.includes(a)}
                      onChange={() => toggleAmenity(a)}
                      className="h-3.5 w-3.5 rounded border-gray-300 text-indigo-600"
                    />
                    <span className="text-xs text-gray-700">{a}</span>
                  </label>
                ))}
              </div>
            </div>
          </div>
          <div className="flex gap-2 border-t border-gray-100 px-5 py-4">
            <button
              onClick={() => { onSave(); onClose(); }}
              className="flex-1 rounded bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
            >
              Save
            </button>
            <button
              onClick={onClose}
              className="flex-1 rounded border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              Cancel
            </button>
          </div>
        </div>
      </div>
    </>
  );
}

function RoomTypeCard({ rt }: { rt: RoomType }) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white shadow-sm" data-testid={`room-type-card-${rt.id}`}>
      <div className="flex h-32 items-center justify-center rounded-t-lg bg-gray-100 text-4xl">
        🛏
      </div>
      <div className="p-4">
        <h3 className="font-semibold text-gray-900">{rt.name}</h3>
        <p className="mt-0.5 text-lg font-bold text-indigo-700">
          ${rt.basePricePerNight.toLocaleString()}
          <span className="ml-1 text-sm font-normal text-gray-500">/ night</span>
        </p>
        <p className="mt-0.5 text-xs text-gray-500">{rt.count} rooms</p>
        <div className="mt-3 flex flex-wrap gap-1.5">
          {rt.amenities.map((a) => (
            <span key={a} className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
              {a}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

export default function RoomManagement() {
  const { data: roomTypes, isLoading: rtLoading, isError: rtError } = useGetRoomTypesQuery();
  const { data: rates, isLoading: rateLoading, isError: rateError } = useGetRatesQuery();
  const [showModal, setShowModal] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const isLoading = rtLoading || rateLoading;
  const isError = rtError || rateError;

  if (isLoading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div role="status" aria-label="Loading" className="h-8 w-8 animate-spin rounded-full border-4 border-indigo-600 border-t-transparent" />
      </div>
    );
  }

  if (isError || !roomTypes || !rates) {
    return (
      <div role="alert" className="m-6 rounded border border-red-300 bg-red-50 p-4 text-red-700">
        Failed to load room data.
      </div>
    );
  }

  return (
    <div className="space-y-8 p-6">
      {/* Room Types */}
      <section>
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-gray-900">Room Types</h2>
          <button
            onClick={() => setShowModal(true)}
            className="rounded bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-700"
          >
            + Add Room Type
          </button>
        </div>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {roomTypes.map((rt) => (
            <RoomTypeCard key={rt.id} rt={rt} />
          ))}
        </div>
      </section>

      {/* Rate Calendar */}
      <section>
        <h2 className="mb-4 text-lg font-semibold text-gray-900">Rate Calendar — Next 14 Days</h2>
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-sm">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-4 py-3 text-left font-medium text-gray-600">Date</th>
                {roomTypes.map((rt) => (
                  <th key={rt.id} className="px-4 py-3 text-right font-medium text-gray-600">
                    {rt.name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {rates.map((entry) => (
                <tr key={entry.date} className={entry.isWeekend ? 'bg-amber-50' : ''}>
                  <td className="px-4 py-2.5 text-gray-700">
                    {entry.dayLabel}
                    {entry.isWeekend && (
                      <span className="ml-2 rounded-full bg-amber-100 px-1.5 py-0.5 text-xs text-amber-700">
                        WE
                      </span>
                    )}
                  </td>
                  {roomTypes.map((rt) => (
                    <td key={rt.id} className="px-4 py-2.5 text-right text-gray-900">
                      ${entry.prices[rt.id]?.toLocaleString() ?? '—'}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {showModal && (
        <AddRoomTypeModal
          onClose={() => setShowModal(false)}
          onSave={() => setToast('Room type saved')}
        />
      )}

      {toast && <Toast message={toast} onDismiss={() => setToast(null)} />}
    </div>
  );
}
