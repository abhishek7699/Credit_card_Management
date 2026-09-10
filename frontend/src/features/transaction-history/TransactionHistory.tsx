import { useListTransactionsQuery } from './historyApi';
import type { TransactionStatus } from '../../types/transaction';

function StatusBadge({ status }: { status: TransactionStatus }) {
  const classes: Record<TransactionStatus, string> = {
    PENDING: 'bg-yellow-100 text-yellow-800',
    AUTHORIZED: 'bg-green-100 text-green-800',
    DECLINED: 'bg-red-100 text-red-800',
    FAILED: 'bg-red-100 text-red-800',
  };
  const label: Record<TransactionStatus, string> = {
    PENDING: 'Pending',
    AUTHORIZED: 'Authorized',
    DECLINED: 'Declined',
    FAILED: 'Failed',
  };
  return (
    <span
      className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${classes[status]}`}
      data-status={status}
    >
      {label[status]}
    </span>
  );
}

export default function TransactionHistory() {
  const { data, isLoading, isError } = useListTransactionsQuery();

  if (isLoading) {
    return (
      <main className="mx-auto max-w-4xl px-4 py-10 text-center">
        <div
          role="status"
          aria-label="Loading"
          className="mx-auto h-10 w-10 animate-spin rounded-full border-4 border-blue-600 border-t-transparent"
        />
        <p className="mt-4 text-gray-500">Loading transactions…</p>
      </main>
    );
  }

  if (isError) {
    return (
      <main className="mx-auto max-w-4xl px-4 py-10">
        <div role="alert" className="rounded border border-red-300 bg-red-50 p-4 text-red-700">
          Failed to load transactions. Please try again later.
        </div>
      </main>
    );
  }

  const transactions = data ?? [];

  return (
    <main className="mx-auto max-w-4xl px-4 py-10">
      <h1 className="mb-6 text-2xl font-bold text-gray-900">Transaction History</h1>

      {transactions.length === 0 ? (
        <p className="text-gray-500">No transactions found.</p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-gray-200">
          <table className="w-full text-sm">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-4 py-3 text-left font-medium text-gray-600">Transaction ID</th>
                <th className="px-4 py-3 text-left font-medium text-gray-600">Card</th>
                <th className="px-4 py-3 text-right font-medium text-gray-600">Amount</th>
                <th className="px-4 py-3 text-left font-medium text-gray-600">Status</th>
                <th className="px-4 py-3 text-left font-medium text-gray-600">Date</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {transactions.map((tx) => (
                <tr key={tx.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-mono text-gray-700">
                    {tx.id.length > 16 ? `${tx.id.slice(0, 16)}…` : tx.id}
                  </td>
                  <td className="px-4 py-3 text-gray-700">{tx.cardNumber}</td>
                  <td className="px-4 py-3 text-right text-gray-900">
                    {tx.amount.toFixed(2)} {tx.currency}
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge status={tx.status} />
                  </td>
                  <td className="px-4 py-3 text-gray-500">
                    {new Date(tx.createdAt).toLocaleDateString()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}
