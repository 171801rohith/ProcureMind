import React from 'react';
import { useAuth } from 'react-oidc-context';
import { ShieldCheck, LogIn, AlertTriangle, FileSearch, BarChart3, Users } from 'lucide-react';

const HIGHLIGHTS = [
  { icon: FileSearch, text: 'Hierarchical clause indexing across every agreement' },
  { icon: BarChart3, text: 'Automated risk scoring and financial exposure' },
  { icon: Users, text: 'Role-based access for analysts and administrators' },
];

/**
 * Pre-redirect sign-in gate (Phase 5).
 *
 * No credentials are ever entered here. The button starts the Authorization Code + PKCE
 * redirect to auth-service, which hosts the actual username and password form. Keeping the
 * two screens visually consistent is the point: the redirect should not feel like leaving
 * the product.
 */
export function LoginScreen() {
  const auth = useAuth();
  const isBusy = auth.isLoading || auth.activeNavigator === 'signinRedirect';

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 font-sans lg:grid lg:grid-cols-2">
      {/* Brand panel, mirrored from the auth-service sign-in page. */}
      <aside className="hidden lg:flex flex-col justify-center border-r border-slate-800 p-12 bg-[radial-gradient(120%_90%_at_12%_8%,rgba(37,99,235,0.22)_0%,transparent_58%)]">
        <div className="max-w-md">
          <div className="inline-flex h-12 w-12 items-center justify-center rounded-xl border border-blue-500/25 bg-blue-500/10 text-blue-400">
            <ShieldCheck className="h-6 w-6" />
          </div>
          <h2 className="mt-6 text-3xl font-bold tracking-tight text-slate-100">ProcureMind</h2>
          <p className="mt-1.5 text-slate-400">Procurement Document Risk Intelligence</p>

          <ul className="mt-9 space-y-4">
            {HIGHLIGHTS.map(({ icon: Icon, text }) => (
              <li key={text} className="flex items-start gap-3 text-sm text-slate-400">
                <Icon className="mt-0.5 h-4 w-4 shrink-0 text-blue-400" />
                <span>{text}</span>
              </li>
            ))}
          </ul>
        </div>
      </aside>

      <main className="flex min-h-screen flex-col items-center justify-center gap-5 p-6 lg:min-h-0">
        <div className="w-full max-w-sm rounded-2xl border border-slate-800 bg-slate-900/60 p-8 shadow-2xl backdrop-blur">
          <div className="inline-flex h-12 w-12 items-center justify-center rounded-xl border border-blue-500/25 bg-blue-500/10 text-blue-400 lg:hidden">
            <ShieldCheck className="h-6 w-6" />
          </div>

          <h1 className="mt-5 text-xl font-bold text-slate-100 lg:mt-0">Sign in</h1>
          <p className="mt-1 text-sm text-slate-400">
            Continue with your ProcureMind ID. You will be taken to the secure sign-in page.
          </p>

          {auth.error && (
            <div
              role="alert"
              className="mt-5 flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-2.5 text-left text-xs text-amber-300"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{auth.error.message || 'Sign-in failed. Please try again.'}</span>
            </div>
          )}

          <button
            type="button"
            onClick={() => auth.signinRedirect()}
            disabled={isBusy}
            className="mt-6 inline-flex w-full items-center justify-center gap-2 rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white shadow-lg shadow-blue-600/20 transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:ring-offset-slate-950 disabled:opacity-60"
          >
            <LogIn className="h-4 w-4" />
            {isBusy ? 'Redirecting…' : 'Sign in with ProcureMind ID'}
          </button>

          <p className="mt-6 border-t border-slate-800 pt-4 text-xs text-slate-500">
            Protected by OAuth2 and OpenID Connect. Accounts are issued by your ProcureMind
            administrator.
          </p>
        </div>

        <p className="text-xs text-slate-600">ProcureMind · Enterprise Procurement Intelligence</p>
      </main>
    </div>
  );
}
