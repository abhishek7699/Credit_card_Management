import { api } from '../../store/api';
import type { RoomStatus } from '../../types/staff';

interface UpdateStatusResponse {
  roomId: string;
  status: RoomStatus;
}

export const roomStatusApi = api.injectEndpoints({
  endpoints: (build) => ({
    updateRoomStatus: build.mutation<UpdateStatusResponse, { roomId: string; status: RoomStatus }>({
      query: ({ roomId, status }) => ({
        url: `/api/v1/rooms/${roomId}/status`,
        method: 'PATCH',
        body: { status },
      }),
    }),
  }),
});

export const { useUpdateRoomStatusMutation } = roomStatusApi;
