import { useState } from 'react';
import { useUpdateRoomStatusMutation } from './roomStatusApi';
import { alertError, alertSuccess, btnSecondary } from '../../components/ui';
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
    return <p className={alertSuccess}>Status updated.</p>;
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
    <div className="space-y-3">
      {isError && <div role="alert" className={alertError}>Status didn&apos;t change. Tap the button to try again.</div>}
      {targets.map((t) => (
        <button
          key={t.target}
          type="button"
          onClick={() => handleClick(t.target)}
          disabled={isLoading}
          className={btnSecondary}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}
