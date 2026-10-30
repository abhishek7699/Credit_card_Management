import { useState } from 'react';
import { useAppSelector } from '../../store';
import { LoadingSpinner } from '../../components/LoadingSpinner';
import { RetryBanner } from '../../components/RetryBanner';
import { useGetRoomBoardQuery } from './roomBoardApi';
import { RoomActionPanel } from './RoomActionPanel';
import { RoomTile } from './RoomTile';
import type { RoomBoardEntry } from '../../types/staff';

export default function RoomBoard() {
  const { data, isLoading, isError, refetch } = useGetRoomBoardQuery();
  const role = useAppSelector((s) => s.staffSession.role);
  const [selectedRoom, setSelectedRoom] = useState<RoomBoardEntry | null>(null);

  if (isLoading) {
    return <LoadingSpinner />;
  }

  if (isError || !data) {
    return <RetryBanner onRetry={refetch} />;
  }

  return (
    <main className="p-6">
      <h1 className="mb-4 text-xl font-bold text-gray-900">Room Board</h1>
      {data.map((floor) => (
        <section key={floor.floor} className="mb-6">
          <h2 className="mb-2 text-sm font-semibold text-gray-500">Floor {floor.floor}</h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-6">
            {floor.rooms.map((room) => (
              <RoomTile key={room.roomId} room={room} onClick={setSelectedRoom} />
            ))}
          </div>
        </section>
      ))}

      {selectedRoom && role && (
        <RoomActionPanel room={selectedRoom} role={role} onClose={() => setSelectedRoom(null)} />
      )}
    </main>
  );
}
