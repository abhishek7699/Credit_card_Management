import {
  Bar,
  BarChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { useGetDashboardQuery } from './dashboardApi';
import type { StatCard as StatCardType, AttentionItem } from '../../types/hotel';

function StatCard({ label, value, detail }: StatCardType) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-5 shadow-sm">
      <p className="text-sm font-medium text-gray-500">{label}</p>
      <p className="mt-1 text-2xl font-bold text-gray-900">{value}</p>
      <p className="mt-1 text-xs text-gray-400">{detail}</p>
    </div>
  );
}

function AttentionRow({ item }: { item: AttentionItem }) {
  const borderClass =
    item.severity === 'error' ? 'border-red-400' : 'border-yellow-400';
  return (
    <li className={`border-l-4 ${borderClass} rounded-r bg-white py-3 pl-4 pr-5 shadow-sm`}>
      <span className="text-sm text-gray-800">{item.message}</span>
    </li>
  );
}

export default function DashboardHome() {
  const { data, isLoading, isError } = useGetDashboardQuery();

  if (isLoading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div
          role="status"
          aria-label="Loading"
          className="h-8 w-8 animate-spin rounded-full border-4 border-indigo-600 border-t-transparent"
        />
      </div>
    );
  }

  if (isError || !data) {
    return (
      <div role="alert" className="m-6 rounded border border-red-300 bg-red-50 p-4 text-red-700">
        Failed to load dashboard data.
      </div>
    );
  }

  return (
    <div className="space-y-6 p-6">
      <h1 className="text-xl font-bold text-gray-900">Dashboard</h1>

      {/* Stat cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {data.stats.map((s) => (
          <StatCard key={s.label} {...s} />
        ))}
      </div>

      {/* Revenue chart */}
      <div className="rounded-lg border border-gray-200 bg-white p-5 shadow-sm">
        <h2 className="mb-4 text-sm font-semibold text-gray-700">Revenue — Last 7 Days</h2>
        <div data-testid="revenue-chart">
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={data.revenue} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
              <XAxis dataKey="day" tick={{ fontSize: 12 }} axisLine={false} tickLine={false} />
              <YAxis hide />
              <Tooltip
                formatter={(v) => [`$${Number(v).toLocaleString()}`, 'Revenue']}
                cursor={{ fill: '#e0e7ff' }}
              />
              <Bar dataKey="amount" fill="#6366f1" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* Needs attention */}
      <div>
        <h2 className="mb-3 text-sm font-semibold text-gray-700">Needs Attention</h2>
        <ul className="space-y-2">
          {data.attention.map((item) => (
            <AttentionRow key={item.id} item={item} />
          ))}
        </ul>
      </div>
    </div>
  );
}
