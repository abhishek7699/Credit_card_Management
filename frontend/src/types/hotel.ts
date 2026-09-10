export type RoomStatus = 'OCCUPIED' | 'NEEDS_CLEANING' | 'READY' | 'MAINTENANCE';

export interface RoomInfo {
  id: string;
  number: string;
  status: RoomStatus;
  guestName: string | null;
  checkIn: string | null;
  checkOut: string | null;
}

export interface StatCard {
  label: string;
  value: string;
  detail: string;
}

export interface AttentionItem {
  id: string;
  severity: 'warning' | 'error';
  message: string;
}

export interface RevenuePoint {
  day: string;
  amount: number;
}

export interface DashboardData {
  stats: [StatCard, StatCard, StatCard, StatCard];
  revenue: RevenuePoint[];
  attention: AttentionItem[];
}

export type HotelPaymentStatus = 'AUTHORIZED' | 'DECLINED' | 'FAILED' | 'SETTLED';

export interface HotelPaymentRecord {
  id: string;
  guestName: string;
  roomNumber: string;
  amount: number;
  fee: number;
  status: HotelPaymentStatus;
  date: string;
}

export interface PayoutSummary {
  nextPayoutAmount: number;
  nextPayoutDate: string;
  processedThisMonth: number;
}

export interface PaymentsData {
  payout: PayoutSummary;
  records: HotelPaymentRecord[];
}

export type ReservationStatus = 'UPCOMING' | 'CURRENT' | 'PAST' | 'CANCELLED';

export interface Reservation {
  id: string;
  guestName: string;
  email: string;
  phone: string;
  roomNumber: string;
  roomType: string;
  checkIn: string;
  checkOut: string;
  nights: number;
  total: number;
  status: ReservationStatus;
  paymentStatus: HotelPaymentStatus;
}

export interface RoomType {
  id: string;
  name: string;
  basePricePerNight: number;
  count: number;
  amenities: string[];
}

export interface RateCalendarEntry {
  date: string;
  dayLabel: string;
  isWeekend: boolean;
  prices: Record<string, number>;
}
