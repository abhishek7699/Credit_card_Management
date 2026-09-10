import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useGetTransactionQuery } from './transactionApi';
import type { TransactionStatus as TxStatus } from '../../types/transaction';

interface StatusConfig {
  label: string;
  badgeClass: string;
  icon: string;
}

function getStatusConfig(status: TxStatus): StatusConfig {
  switch (status) {
    case 'PENDING':
      return { label: 'Pending', badgeClass: 'bg-yellow-100 text-yellow-800', icon: '⏳' };
    case 'AUTHORIZED':
      return { label: 'Authorized', badgeClass: 'bg-green-100 text-green-800', icon: '✓' };
    case 'DECLINED':
      return { label: 'Declined', badgeClass: 'bg-red-100 text-red-800', icon: '✗' };
    case 'FAILED':
      return { label: 'Failed', badgeClass: 'bg-red-100 text-red-800', icon: '⚠' };
  }
}

export default function TransactionStatus() {
  const { id } = useParams<{ id: string }>();
  const [shouldPoll, setShouldPoll] = useState(true);

  const { data, isLoading, isError } = useGetTransactionQuery(id ?? '', {
    skip: !id,
    pollingInterval: shouldPoll ? 2000 : 0,
  });

  useEffect(() => {
    if (data && data.status !== 'PENDING') {
      setShouldPoll(false);
    }
  }, [data]);

  if (isLoading) {
    return (
      <main className="mx-auto max-w-lg px-4 py-10 text-center">
        <div
          role="status"
          aria-label="Loading"
          className="mx-auto h-10 w-10 animate-spin rounded-full border-4 border-blue-600 border-t-transparent"
        />
        <p className="mt-4 text-gray-500">Loading transaction…</p>
      </main>
    );
  }

  if (isError || !data) {
    return (
      <main className="mx-auto max-w-lg px-4 py-10">
        <div role="alert" className="rounded border border-red-300 bg-red-50 p-4 text-red-700">
          Could not load transaction. Please check the ID and try again.
        </div>
      </main>
    );
  }

  const config = getStatusConfig(data.status);

  return (
    <main className="mx-auto max-w-lg px-4 py-10">
      <h1 className="mb-6 text-2xl font-bold text-gray-900">Transaction Status</h1>

      <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <div className="mb-4 flex items-center justify-between">
          <span className="text-sm text-gray-500">Status</span>
          <span
            className={`inline-flex items-center gap-1 rounded-full px-3 py-1 text-sm font-medium ${config.badgeClass}`}
            data-status={data.status}
          >
            {data.status === 'PENDING' ? (
              <span
                className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-yellow-700 border-t-transparent"
                aria-hidden="true"
              />
            ) : (
              <span aria-hidden="true">{config.icon}</span>
            )}
            {config.label}
          </span>
        </div>

        <dl className="space-y-3 text-sm">
          <div className="flex justify-between">
            <dt className="text-gray-500">Transaction ID</dt>
            <dd className="font-mono text-gray-900">{data.id}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-gray-500">Card</dt>
            <dd className="text-gray-900">{data.cardNumber}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-gray-500">Amount</dt>
            <dd className="font-medium text-gray-900">
              {data.amount.toFixed(2)} {data.currency}
            </dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-gray-500">Date</dt>
            <dd className="text-gray-900">{new Date(data.createdAt).toLocaleString()}</dd>
          </div>
        </dl>
      </div>

      <Link to="/payment" className="mt-6 inline-block text-sm text-blue-600 hover:underline">
        ← Submit Another Payment
      </Link>
    </main>
  );
}
