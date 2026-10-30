import { LoadingSpinner } from '../../components/LoadingSpinner';
import { RetryBanner } from '../../components/RetryBanner';
import { useGetBookingQuery } from './bookingApi';
import type { BookingStatus } from '../../types/staff';

interface BookingStatusViewProps {
  bookingId: string;
}

// Record<BookingStatus, string>, not Record<string, string>: exhaustive over
// every value the type can hold, so there's no "else" case needing a fallback
// — and the compiler catches it if a new BookingStatus value is ever added
// here without a label.
const STATUS_LABEL: Record<BookingStatus, string> = {
  HELD: 'Holding the room while payment is processed…',
  CONFIRMED: 'Confirmed',
  CANCELLED: 'Cancelled',
  CHECKED_IN: 'Checked in',
  CHECKED_OUT: 'Checked out',
};

export function BookingStatusView({ bookingId }: BookingStatusViewProps) {
  const { data, isLoading, isError, refetch } = useGetBookingQuery(bookingId);

  if (isLoading) {
    return <LoadingSpinner />;
  }

  if (isError || !data) {
    return <RetryBanner onRetry={refetch} />;
  }

  return (
    <div className="rounded border border-gray-200 p-4">
      <p className="text-sm text-gray-500">Booking status</p>
      <p className="text-lg font-semibold text-gray-900">{STATUS_LABEL[data.status]}</p>
    </div>
  );
}
