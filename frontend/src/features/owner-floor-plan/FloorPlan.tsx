import { useState } from 'react';
import { useGetRoomsQuery } from './floorPlanApi';
import { Toast } from '../../components/Toast';
import type { RoomInfo, RoomStatus } from '../../types/hotel';

// SVG layout constants
const ROOM_W = 80;
const ROOM_H = 88;
const GAP = 8;
const START_X = 60; // leave room for stair label
const TOP_Y = 24;
const HALL_Y = 120;
const HALL_H = 48;
const BOT_Y = HALL_Y + HALL_H + 8;

function roomFill(status: RoomStatus): string {
  switch (status) {
    case 'OCCUPIED': return '#fca5a5';
    case 'NEEDS_CLEANING': return '#fde68a';
    case 'READY': return '#86efac';
    case 'MAINTENANCE': return '#d1d5db';
  }
}

function roomIcon(status: RoomStatus): string {
  switch (status) {
    case 'NEEDS_CLEANING': return '🧹';
    case 'MAINTENANCE': return '🔧';
    default: return '';
  }
}

function roomLabel(status: RoomStatus): string {
  switch (status) {
    case 'OCCUPIED': return 'Occupied';
    case 'NEEDS_CLEANING': return 'Cleaning';
    case 'READY': return 'Ready';
    case 'MAINTENANCE': return 'Maint.';
  }
}

interface SidePanelProps {
  room: RoomInfo;
  onClose: () => void;
  onAction: (msg: string) => void;
}

function SidePanel({ room, onClose, onAction }: SidePanelProps) {
  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 z-30 bg-black/20"
        onClick={onClose}
        aria-hidden="true"
      />
      {/* Panel */}
      <div
        role="dialog"
        aria-label={`Room ${room.number} details`}
        className="fixed inset-y-0 right-0 z-40 flex w-72 flex-col border-l border-gray-200 bg-white shadow-xl"
      >
        <div className="flex items-center justify-between border-b border-gray-100 px-5 py-4">
          <h2 className="font-semibold text-gray-900">Room {room.number}</h2>
          <button
            onClick={onClose}
            aria-label="Close panel"
            className="text-gray-400 hover:text-gray-600"
          >
            ✕
          </button>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto px-5 py-5">
          <div>
            <span className="text-xs text-gray-500">Status</span>
            <p className="mt-0.5 font-medium text-gray-900">{roomLabel(room.status)}</p>
          </div>
          <div>
            <span className="text-xs text-gray-500">Guest</span>
            <p className="mt-0.5 font-medium text-gray-900">
              {room.guestName ?? 'Vacant'}
            </p>
          </div>
          {room.checkIn && (
            <div className="grid grid-cols-2 gap-3">
              <div>
                <span className="text-xs text-gray-500">Check-in</span>
                <p className="mt-0.5 text-sm text-gray-900">{room.checkIn}</p>
              </div>
              <div>
                <span className="text-xs text-gray-500">Check-out</span>
                <p className="mt-0.5 text-sm text-gray-900">{room.checkOut}</p>
              </div>
            </div>
          )}
        </div>

        <div className="space-y-2 border-t border-gray-100 px-5 py-4">
          <button
            onClick={() => { onAction('Room marked as cleaned'); onClose(); }}
            className="w-full rounded bg-green-600 px-3 py-2 text-sm font-medium text-white hover:bg-green-700"
          >
            Mark Cleaned
          </button>
          <button
            onClick={() => { onAction('Room marked for maintenance'); onClose(); }}
            className="w-full rounded bg-yellow-500 px-3 py-2 text-sm font-medium text-white hover:bg-yellow-600"
          >
            Mark Maintenance
          </button>
          <button
            onClick={() => { onAction('Opening reservation…'); onClose(); }}
            className="w-full rounded border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
          >
            View Reservation
          </button>
        </div>
      </div>
    </>
  );
}

export default function FloorPlan() {
  const { data: rooms, isLoading, isError } = useGetRoomsQuery();
  const [selectedRoom, setSelectedRoom] = useState<RoomInfo | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  if (isLoading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div role="status" aria-label="Loading" className="h-8 w-8 animate-spin rounded-full border-4 border-indigo-600 border-t-transparent" />
      </div>
    );
  }

  if (isError || !rooms) {
    return (
      <div role="alert" className="m-6 rounded border border-red-300 bg-red-50 p-4 text-red-700">
        Failed to load floor plan.
      </div>
    );
  }

  // Split: first 5 rooms top row, next 5 bottom row
  const topRooms = rooms.slice(0, 5);
  const botRooms = rooms.slice(5, 10);
  const SVG_W = START_X + 5 * (ROOM_W + GAP) + GAP + 60;
  const SVG_H = BOT_Y + ROOM_H + 16;

  return (
    <div className="p-6">
      <h1 className="mb-4 text-xl font-bold text-gray-900">Floor Plan — Level 1</h1>

      {/* Legend */}
      <div className="mb-4 flex flex-wrap gap-4 text-xs">
        {([['OCCUPIED', '#fca5a5', 'Occupied'], ['NEEDS_CLEANING', '#fde68a', 'Needs Cleaning'], ['READY', '#86efac', 'Ready'], ['MAINTENANCE', '#d1d5db', 'Maintenance']] as const).map(([, fill, label]) => (
          <span key={label} className="flex items-center gap-1.5">
            <span className="inline-block h-3 w-3 rounded-sm" style={{ background: fill }} />
            {label}
          </span>
        ))}
      </div>

      <div className="overflow-x-auto rounded-xl border border-gray-200 bg-white p-4 shadow-sm">
        <svg
          viewBox={`0 0 ${SVG_W} ${SVG_H}`}
          width="100%"
          style={{ maxWidth: SVG_W }}
          aria-label="Floor plan"
          role="img"
        >
          {/* Hallway */}
          <rect x={0} y={HALL_Y} width={SVG_W} height={HALL_H} fill="#f3f4f6" rx={4} />
          <text x={SVG_W / 2} y={HALL_Y + HALL_H / 2 + 5} textAnchor="middle" fontSize={11} fill="#9ca3af" fontWeight={500}>
            HALLWAY
          </text>

          {/* Stairwell (left) */}
          <rect x={2} y={HALL_Y - 30} width={50} height={HALL_H + 60} fill="#e5e7eb" rx={4} />
          <text x={27} y={HALL_Y + HALL_H / 2 - 6} textAnchor="middle" fontSize={13}>🪜</text>
          <text x={27} y={HALL_Y + HALL_H / 2 + 8} textAnchor="middle" fontSize={8} fill="#6b7280">STAIR</text>

          {/* Elevator (right) */}
          <rect x={SVG_W - 52} y={HALL_Y - 30} width={50} height={HALL_H + 60} fill="#e5e7eb" rx={4} />
          <text x={SVG_W - 27} y={HALL_Y + HALL_H / 2 - 6} textAnchor="middle" fontSize={13}>🛗</text>
          <text x={SVG_W - 27} y={HALL_Y + HALL_H / 2 + 8} textAnchor="middle" fontSize={8} fill="#6b7280">ELEV</text>

          {/* Top rooms (101–105) */}
          {topRooms.map((room, i) => {
            const x = START_X + i * (ROOM_W + GAP);
            const fill = roomFill(room.status);
            const icon = roomIcon(room.status);
            return (
              <g
                key={room.id}
                onClick={() => setSelectedRoom(room)}
                style={{ cursor: 'pointer' }}
                data-testid={`room-${room.number}`}
                data-status={room.status}
              >
                <rect x={x} y={TOP_Y} width={ROOM_W} height={ROOM_H} fill={fill} rx={6} stroke="#e5e7eb" strokeWidth={1} />
                <text x={x + ROOM_W / 2} y={TOP_Y + 26} textAnchor="middle" fontSize={13} fontWeight={600} fill="#1f2937">
                  {room.number}
                </text>
                {icon && (
                  <text x={x + ROOM_W / 2} y={TOP_Y + 52} textAnchor="middle" fontSize={14}>
                    {icon}
                  </text>
                )}
              </g>
            );
          })}

          {/* Bottom rooms (106–110) */}
          {botRooms.map((room, i) => {
            const x = START_X + i * (ROOM_W + GAP);
            const fill = roomFill(room.status);
            const icon = roomIcon(room.status);
            return (
              <g
                key={room.id}
                onClick={() => setSelectedRoom(room)}
                style={{ cursor: 'pointer' }}
                data-testid={`room-${room.number}`}
                data-status={room.status}
              >
                <rect x={x} y={BOT_Y} width={ROOM_W} height={ROOM_H} fill={fill} rx={6} stroke="#e5e7eb" strokeWidth={1} />
                <text x={x + ROOM_W / 2} y={BOT_Y + 26} textAnchor="middle" fontSize={13} fontWeight={600} fill="#1f2937">
                  {room.number}
                </text>
                {icon && (
                  <text x={x + ROOM_W / 2} y={BOT_Y + 52} textAnchor="middle" fontSize={14}>
                    {icon}
                  </text>
                )}
              </g>
            );
          })}
        </svg>
      </div>

      {selectedRoom && (
        <SidePanel
          room={selectedRoom}
          onClose={() => setSelectedRoom(null)}
          onAction={(msg) => setToast(msg)}
        />
      )}

      {toast && <Toast message={toast} onDismiss={() => setToast(null)} />}
    </div>
  );
}
