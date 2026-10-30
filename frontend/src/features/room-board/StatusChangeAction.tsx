import { useState } from 'react';
import { useUpdateRoomStatusMutation } from './roomStatusApi';
import type { RoomStatus } from '../../types/staff';

/** Only DIRTY and MAINTENANCE — the two statuses this component is ever actually
 *  invoked with (RoomActionPanel guards the rest); narrowed to match real usage
 *  rather than accepting RoomStatus and defensively guarding against VACANT_CLEAN/
 *  OCCUPIED cases that can't reach here. */
type ChangeableStatus = 'DIRTY' | 'MAINTENANCE';

interface StatusChangeActionProps {
  roomId: string;
  status: ChangeableStatus;
}

/** FR-018/FR-019: only DIRTY and MAINTENANCE rooms get a status-change action in
 *  this UI (spec Story 5's own acceptance scenarios scope "offered to either role"
 *  to exactly these two statuses) — a VACANT_CLEAN room's click target is booking a
 *  new reservation (Story 2), not a status change, even though the backend itself
 *  also allows VACANT_CLEAN→MAINTENANCE. */
const TARGETS: Record<ChangeableStatus, { label: string; target: RoomStatus }[]> = {
  DIRTY: [
    { label: 'Mark Clean', target: 'VACANT_CLEAN' },
    { label: 'Mark Maintenance', target: 'MAINTENANCE' },
  ],
  MAINTENANCE: [{ label: 'Mark Clean', target: 'VACANT_CLEAN' }],
};

export function StatusChangeAction({ roomId, status }: StatusChangeActionProps) {
  const [updateStatus, { isLoading, isError }] = useUpdateRoomStatusMutation();
  const [appliedTo, setAppliedTo] = useState<RoomStatus | null>(null);

  if (appliedTo) {
    return <p className="text-sm text-green-700">Status updated.</p>;
  }

  async function handleClick(target: RoomStatus) {
    try {
      await updateStatus({ roomId, status: target }).unwrap();
      setAppliedTo(target);
    } catch {
      // isError is managed by RTK Query; the board's next poll corrects the
      // displayed status either way.
    }
  }

  const targets = TARGETS[status];

  return (
    <div className="space-y-2">
      {isError && (
        <div role="alert" className="mb-1 rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700">
          Status update failed. Please try again.
        </div>
      )}
      {targets.map((t) => (
        <button
          key={t.target}
          type="button"
          onClick={() => handleClick(t.target)}
          disabled={isLoading}
          className="w-full rounded border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}
