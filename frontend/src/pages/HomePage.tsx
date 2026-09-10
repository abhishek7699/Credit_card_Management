import { Link } from 'react-router-dom';

export default function HomePage() {
  return (
    <main className="flex min-h-[calc(100vh-3.5rem)] flex-col items-center justify-center gap-6 p-8 text-center">
      <h1 className="text-4xl font-bold text-gray-900">Credit Card Gateway</h1>
      <p className="max-w-md text-lg text-gray-500">
        Secure payment processing for merchants. Submit payments or view transaction history.
      </p>
      <div className="flex gap-4">
        <Link
          to="/payment"
          className="rounded bg-blue-600 px-5 py-2.5 font-medium text-white hover:bg-blue-700"
        >
          New Payment
        </Link>
        <Link
          to="/history"
          className="rounded border border-gray-300 px-5 py-2.5 font-medium text-gray-700 hover:bg-gray-50"
        >
          View History
        </Link>
      </div>
    </main>
  );
}
