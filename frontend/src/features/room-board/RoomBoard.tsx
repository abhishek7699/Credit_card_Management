import { useState } from 'react';
import { useAppSelector } from '../../store';
import { LoadingSpinner } from '../../components/LoadingSpinner';
import { RetryBanner } from '../../components/RetryBanner';
import { stateText } from '../../components/ui';
import { useGetRoomBoardQuery } from './roomBoardApi';
import { RoomActionPanel } from './RoomActionPanel';
import { RoomStatusLegend } from './RoomStatusLegend';
import { RoomTile } from './RoomTile';
import type { RoomBoardEntry } from '../../types/staff';

export default function RoomBoard() {
  const { data, isError, refetch } = useGetRoomBoardQuery();
  const role = useAppSelector((s) => s.staffSession.role);
  const [selectedRoom, setSelectedRoom] = useState<RoomBoardEntry | null>(null);

  // Nothing to show yet: either still loading, or the first load failed.
  if (!data) {
    if (isError) {
      return (
        <div className="p-6">
          <RetryBanner onRetry={refetch} />
        </div>
      );
    }
    return <LoadingSpinner label="Loading the room board…" />;
  }

  const hasRooms = data.some((floor) => floor.rooms.length > 0);

  return (
    <div className="p-6">
      <h1 className="mb-4 text-2xl font-bold text-gray-900">Room Board</h1>

      {/* Data we already have stays on screen; the banner only says it may be out of date. */}
      {isError && <RetryBanner onRetry={refetch} />}

      {!hasRooms ? (
        <p className={`${stateText} rounded-lg border border-dashed border-gray-300 bg-white p-6`}>
          No rooms are set up yet. Ask an administrator to add rooms, then reload this page.
        </p>
      ) : (
        <>
          <RoomStatusLegend />
          {data.map((floor) => (
            <section key={floor.floor} className="mb-6">
              <h2 className="mb-2 text-base font-semibold text-gray-600">Floor {floor.floor}</h2>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-6">
                {floor.rooms.map((room) => (
                  <RoomTile key={room.roomId} room={room} onClick={setSelectedRoom} />
                ))}
              </div>
            </section>
          ))}
        </>
      )}

      {selectedRoom && role && (
        <RoomActionPanel room={selectedRoom} role={role} onClose={() => setSelectedRoom(null)} />
      )}
    </div>
  );
}
