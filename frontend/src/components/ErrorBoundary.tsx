import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  message: string;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, message: '' };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, message: error.message };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // eslint-disable-next-line no-console
    console.error('Unhandled render error:', error, info.componentStack);
  }

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-screen flex-col items-center justify-center gap-5 bg-gray-50 p-8 text-center">
          <h1 className="text-2xl font-bold text-gray-900">This screen ran into a problem</h1>
          <p className="max-w-md text-base text-gray-700">
            Tap Reload to start again. If it keeps happening, tell your manager and note the time.
          </p>
          <button
            type="button"
            className="min-h-12 rounded-lg bg-indigo-600 px-6 text-base font-semibold text-white hover:bg-indigo-700"
            onClick={() => window.location.reload()}
          >
            Reload
          </button>
          {import.meta.env.DEV && <p className="max-w-md text-sm text-gray-500">{this.state.message}</p>}
        </div>
      );
    }
    return this.props.children;
  }
}
