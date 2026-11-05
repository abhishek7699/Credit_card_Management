import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { GuestDetails } from './GuestDetails';
import type { GuestDetails as GuestDetailsType } from '../../types/staff';

const MINIMAL: GuestDetailsType = {
  guestName: 'Jane Doe',
  guestPhone: '+1-555-0100',
  guestEmail: null,
  visitingFrom: null,
  adults: 1,
  children: 0,
  expectedArrivalTime: null,
  expectedDepartureTime: null,
  notes: null,
};

test('renders the required fields with no optional fields present', () => {
  render(<GuestDetails guest={MINIMAL} />);

  expect(screen.getByText('Jane Doe')).toBeInTheDocument();
  expect(screen.getByText('+1-555-0100')).toBeInTheDocument();
  expect(screen.getByText(/1 adult\b/i)).toBeInTheDocument();
  expect(screen.queryByText(/email/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/visiting from/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/expected arrival/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/expected departure/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/notes/i)).not.toBeInTheDocument();
});

test('renders every optional field when present', () => {
  render(
    <GuestDetails
      guest={{
        ...MINIMAL,
        guestEmail: 'jane@example.com',
        visitingFrom: 'Chicago, IL',
        adults: 2,
        children: 1,
        expectedArrivalTime: '15:00',
        expectedDepartureTime: '11:00',
        notes: 'Requested a crib',
      }}
    />,
  );

  expect(screen.getByText('jane@example.com')).toBeInTheDocument();
  expect(screen.getByText('Chicago, IL')).toBeInTheDocument();
  expect(screen.getByText(/2 adults, 1 child\b/i)).toBeInTheDocument();
  expect(screen.getByText('15:00')).toBeInTheDocument();
  expect(screen.getByText('11:00')).toBeInTheDocument();
  expect(screen.getByText('Requested a crib')).toBeInTheDocument();
});

test('pluralizes children correctly for more than one', () => {
  render(<GuestDetails guest={{ ...MINIMAL, adults: 2, children: 2 }} />);
  expect(screen.getByText(/2 adults, 2 children\b/i)).toBeInTheDocument();
});

test('omits the children clause entirely when zero', () => {
  render(<GuestDetails guest={{ ...MINIMAL, adults: 1, children: 0 }} />);
  expect(screen.getByText(/^1 adult$/i)).toBeInTheDocument();
});
