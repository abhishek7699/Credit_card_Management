import type {
  DashboardData,
  HotelPaymentRecord,
  PaymentsData,
  RateCalendarEntry,
  Reservation,
  RoomInfo,
  RoomType,
} from '../types/hotel';

export const MOCK_DASHBOARD_DATA: DashboardData = {
  stats: [
    { label: 'Occupancy Today', value: '18/24 rooms', detail: '75%' },
    { label: 'Revenue Today', value: '$4,280', detail: '+12% vs yesterday' },
    { label: 'Arrivals Today', value: '6', detail: '2 early check-in' },
    { label: 'Departures Today', value: '4', detail: '1 late checkout' },
  ],
  revenue: [
    { day: 'Mon', amount: 3200 },
    { day: 'Tue', amount: 2800 },
    { day: 'Wed', amount: 4100 },
    { day: 'Thu', amount: 3750 },
    { day: 'Fri', amount: 5600 },
    { day: 'Sat', amount: 6200 },
    { day: 'Sun', amount: 4280 },
  ],
  attention: [
    { id: 'a1', severity: 'warning', message: 'Room 204 needs cleaning' },
    { id: 'a2', severity: 'error', message: '1 payment declined – Room 112' },
    { id: 'a3', severity: 'warning', message: 'Maintenance request – Room 308' },
  ],
};

export const MOCK_ROOMS: RoomInfo[] = [
  { id: 'r101', number: '101', status: 'OCCUPIED', guestName: 'James Carter', checkIn: '2026-08-24', checkOut: '2026-08-28' },
  { id: 'r102', number: '102', status: 'READY', guestName: null, checkIn: null, checkOut: null },
  { id: 'r103', number: '103', status: 'NEEDS_CLEANING', guestName: null, checkIn: null, checkOut: null },
  { id: 'r104', number: '104', status: 'OCCUPIED', guestName: 'Priya Mehta', checkIn: '2026-08-25', checkOut: '2026-08-27' },
  { id: 'r105', number: '105', status: 'MAINTENANCE', guestName: null, checkIn: null, checkOut: null },
  { id: 'r106', number: '106', status: 'READY', guestName: null, checkIn: null, checkOut: null },
  { id: 'r107', number: '107', status: 'OCCUPIED', guestName: 'Sofia Reyes', checkIn: '2026-08-23', checkOut: '2026-08-29' },
  { id: 'r108', number: '108', status: 'NEEDS_CLEANING', guestName: null, checkIn: null, checkOut: null },
  { id: 'r109', number: '109', status: 'READY', guestName: null, checkIn: null, checkOut: null },
  { id: 'r110', number: '110', status: 'MAINTENANCE', guestName: null, checkIn: null, checkOut: null },
];

const MOCK_PAYMENT_RECORDS: HotelPaymentRecord[] = [
  { id: 'hp1', guestName: 'James Carter', roomNumber: '101', amount: 560.00, fee: 16.80, status: 'SETTLED', date: '2026-08-26' },
  { id: 'hp2', guestName: 'Priya Mehta', roomNumber: '104', amount: 280.00, fee: 8.40, status: 'AUTHORIZED', date: '2026-08-26' },
  { id: 'hp3', guestName: 'Sofia Reyes', roomNumber: '107', amount: 840.00, fee: 25.20, status: 'SETTLED', date: '2026-08-25' },
  { id: 'hp4', guestName: 'Tom Larsen', roomNumber: '112', amount: 420.00, fee: 12.60, status: 'DECLINED', date: '2026-08-25' },
  { id: 'hp5', guestName: 'Aiko Yamada', roomNumber: '203', amount: 1120.00, fee: 33.60, status: 'SETTLED', date: '2026-08-24' },
  { id: 'hp6', guestName: 'Marco Bianchi', roomNumber: '215', amount: 350.00, fee: 10.50, status: 'FAILED', date: '2026-08-24' },
  { id: 'hp7', guestName: 'Claire Dubois', roomNumber: '301', amount: 700.00, fee: 21.00, status: 'SETTLED', date: '2026-08-23' },
  { id: 'hp8', guestName: 'David Okonkwo', roomNumber: '308', amount: 490.00, fee: 14.70, status: 'AUTHORIZED', date: '2026-08-23' },
];

export const MOCK_RESERVATIONS: Reservation[] = [
  { id: 'res1', guestName: 'James Carter', email: 'james.carter@email.com', phone: '+1 555-0101', roomNumber: '101', roomType: 'Standard King', checkIn: '2026-08-24', checkOut: '2026-08-28', nights: 4, total: 596.00, status: 'CURRENT', paymentStatus: 'SETTLED' },
  { id: 'res2', guestName: 'Priya Mehta', email: 'priya.mehta@email.com', phone: '+1 555-0102', roomNumber: '104', roomType: 'Deluxe Suite', checkIn: '2026-08-25', checkOut: '2026-08-27', nights: 2, total: 598.00, status: 'CURRENT', paymentStatus: 'AUTHORIZED' },
  { id: 'res3', guestName: 'Sofia Reyes', email: 'sofia.reyes@email.com', phone: '+1 555-0103', roomNumber: '107', roomType: 'Standard King', checkIn: '2026-08-23', checkOut: '2026-08-29', nights: 6, total: 894.00, status: 'CURRENT', paymentStatus: 'SETTLED' },
  { id: 'res4', guestName: 'Henry Walsh', email: 'henry.walsh@email.com', phone: '+1 555-0104', roomNumber: '102', roomType: 'Standard King', checkIn: '2026-08-29', checkOut: '2026-09-02', nights: 4, total: 596.00, status: 'UPCOMING', paymentStatus: 'AUTHORIZED' },
  { id: 'res5', guestName: 'Yuki Tanaka', email: 'yuki.tanaka@email.com', phone: '+1 555-0105', roomNumber: '205', roomType: 'Ocean View Suite', checkIn: '2026-09-01', checkOut: '2026-09-05', nights: 4, total: 1796.00, status: 'UPCOMING', paymentStatus: 'AUTHORIZED' },
  { id: 'res6', guestName: 'Marco Bianchi', email: 'marco.bianchi@email.com', phone: '+1 555-0106', roomNumber: '301', roomType: 'Deluxe Suite', checkIn: '2026-09-10', checkOut: '2026-09-14', nights: 4, total: 1196.00, status: 'UPCOMING', paymentStatus: 'AUTHORIZED' },
  { id: 'res7', guestName: 'Claire Dubois', email: 'claire.dubois@email.com', phone: '+1 555-0107', roomNumber: '103', roomType: 'Standard King', checkIn: '2026-08-15', checkOut: '2026-08-20', nights: 5, total: 745.00, status: 'PAST', paymentStatus: 'SETTLED' },
  { id: 'res8', guestName: 'David Okonkwo', email: 'david.okonkwo@email.com', phone: '+1 555-0108', roomNumber: '202', roomType: 'Deluxe Suite', checkIn: '2026-08-10', checkOut: '2026-08-13', nights: 3, total: 897.00, status: 'PAST', paymentStatus: 'SETTLED' },
  { id: 'res9', guestName: 'Aiko Yamada', email: 'aiko.yamada@email.com', phone: '+1 555-0109', roomNumber: '110', roomType: 'Standard King', checkIn: '2026-08-01', checkOut: '2026-08-05', nights: 4, total: 596.00, status: 'PAST', paymentStatus: 'SETTLED' },
  { id: 'res10', guestName: 'Tom Larsen', email: 'tom.larsen@email.com', phone: '+1 555-0110', roomNumber: '205', roomType: 'Ocean View Suite', checkIn: '2026-08-20', checkOut: '2026-08-24', nights: 4, total: 1796.00, status: 'CANCELLED', paymentStatus: 'DECLINED' },
  { id: 'res11', guestName: 'Nina Patel', email: 'nina.patel@email.com', phone: '+1 555-0111', roomNumber: '308', roomType: 'Deluxe Suite', checkIn: '2026-08-18', checkOut: '2026-08-21', nights: 3, total: 897.00, status: 'CANCELLED', paymentStatus: 'FAILED' },
];

export const MOCK_ROOM_TYPES: RoomType[] = [
  { id: 'rt1', name: 'Standard King', basePricePerNight: 149, count: 8, amenities: ['WiFi', 'TV', 'Coffee Maker', 'Safe'] },
  { id: 'rt2', name: 'Deluxe Suite', basePricePerNight: 299, count: 4, amenities: ['WiFi', 'TV', 'Mini Bar', 'Balcony', 'Jacuzzi'] },
  { id: 'rt3', name: 'Ocean View Suite', basePricePerNight: 449, count: 2, amenities: ['WiFi', 'TV', 'Mini Bar', 'Ocean View', 'Butler Service', 'Jacuzzi'] },
];

function buildRateCalendar(): RateCalendarEntry[] {
  const entries: RateCalendarEntry[] = [];
  const base = new Date('2026-08-27');
  for (let i = 0; i < 14; i++) {
    const d = new Date(base);
    d.setDate(base.getDate() + i);
    const dow = d.getDay();
    const isWeekend = dow === 0 || dow === 6;
    const label = d.toLocaleDateString('en-US', { weekday: 'short', day: 'numeric' });
    entries.push({
      date: d.toISOString().slice(0, 10),
      dayLabel: label,
      isWeekend,
      prices: {
        rt1: isWeekend ? 179 : 149,
        rt2: isWeekend ? 359 : 299,
        rt3: isWeekend ? 539 : 449,
      },
    });
  }
  return entries;
}

export const MOCK_RATE_CALENDAR: RateCalendarEntry[] = buildRateCalendar();

export const MOCK_PAYMENTS_DATA: PaymentsData = {
  payout: {
    nextPayoutAmount: 3120.00,
    nextPayoutDate: '2026-09-01',
    processedThisMonth: 12850.00,
  },
  records: MOCK_PAYMENT_RECORDS,
};
