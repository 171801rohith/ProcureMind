import React, { useCallback, useEffect, useState } from 'react';
import { UserPlus, Users, RefreshCw, CheckCircle2, AlertTriangle, ShieldCheck } from 'lucide-react';
import { ApiClient } from '../../api/client';
import { ROLE_ADMIN, ROLE_ANALYST, ROLE_VIEWER } from '../../auth/roles';
import { Card } from '../ui/Card';
import { Button } from '../ui/Button';
import { Badge } from '../ui/Badge';
import { EmptyState } from '../ui/EmptyState';
import { Skeleton } from '../ui/Skeleton';

const ALL_ROLES = [ROLE_VIEWER, ROLE_ANALYST, ROLE_ADMIN];

const ROLE_HINTS = {
  [ROLE_VIEWER]: 'Read dashboards, contracts and analysis',
  [ROLE_ANALYST]: 'Everything a viewer can do, plus upload and chat',
  [ROLE_ADMIN]: 'Full access, including user management',
};

const ROLE_TONES = {
  [ROLE_ADMIN]: 'bg-blue-500/10 text-blue-300 border-blue-500/25',
  [ROLE_ANALYST]: 'bg-emerald-500/10 text-emerald-300 border-emerald-500/25',
  [ROLE_VIEWER]: 'bg-slate-700/40 text-slate-300 border-slate-600/40',
};

const EMPTY_FORM = { username: '', password: '', email: '', roles: [ROLE_VIEWER] };

export function UserManagement() {
  const [users, setUsers] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);

  const [form, setForm] = useState(EMPTY_FORM);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [formError, setFormError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [successMessage, setSuccessMessage] = useState(null);

  const loadUsers = useCallback(async () => {
    setIsLoading(true);
    setLoadError(null);
    try {
      setUsers(await ApiClient.listUsers());
    } catch (err) {
      setLoadError(
        err.status === 403
          ? 'Your account is not permitted to manage users.'
          : err.message || 'Unable to load users.'
      );
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadUsers();
  }, [loadUsers]);

  const toggleRole = (role) => {
    setForm((current) => ({
      ...current,
      roles: current.roles.includes(role)
        ? current.roles.filter((r) => r !== role)
        : [...current.roles, role],
    }));
  };

  const validateLocally = () => {
    const errors = {};
    if (!form.username.trim()) errors.username = 'Username is required.';
    if (form.password.length < 8) errors.password = 'Password must be at least 8 characters.';
    if (form.roles.length === 0) errors.roles = 'Select at least one role.';
    return errors;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSuccessMessage(null);
    setFormError(null);

    const localErrors = validateLocally();
    setFieldErrors(localErrors);
    if (Object.keys(localErrors).length > 0) return;

    setIsSubmitting(true);
    try {
      const created = await ApiClient.createUser({
        username: form.username.trim(),
        password: form.password,
        email: form.email.trim(),
        roles: form.roles,
      });
      setSuccessMessage(
        'User "' + created.username + '" created with ' + created.roles.join(', ') +
          '. They can sign in now.'
      );
      setForm(EMPTY_FORM);
      setFieldErrors({});
      await loadUsers();
    } catch (err) {
      // 409 is a duplicate username; 400 carries per-field validation messages from the API.
      setFormError(
        err.status === 409
          ? 'That username is already taken. Choose a different one.'
          : err.message || 'Unable to create the user.'
      );
      setFieldErrors(err.fieldErrors || {});
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-start gap-3">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border border-blue-500/25 bg-blue-500/10 text-blue-400">
          <ShieldCheck className="h-5 w-5" />
        </div>
        <div>
          <h2 className="text-lg font-bold text-slate-100">User Management</h2>
          <p className="text-sm text-slate-400">
            Create ProcureMind accounts and assign roles. Roles are enforced by the API
            gateway and by every service independently.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-12">
        <Card className="xl:col-span-5">
          <div className="mb-5 flex items-center gap-2">
            <UserPlus className="h-4 w-4 text-blue-400" />
            <h3 className="text-sm font-semibold text-slate-200">Create user</h3>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4" noValidate>
            <Field
              label="Username"
              error={fieldErrors.username}
              input={
                <input
                  type="text"
                  value={form.username}
                  autoComplete="off"
                  spellCheck="false"
                  onChange={(e) => setForm({ ...form, username: e.target.value })}
                  className={inputClass(fieldErrors.username)}
                  placeholder="j.rivera"
                />
              }
            />

            <Field
              label="Password"
              hint="Minimum 8 characters. Stored only as a BCrypt hash."
              error={fieldErrors.password}
              input={
                <input
                  type="password"
                  value={form.password}
                  autoComplete="new-password"
                  onChange={(e) => setForm({ ...form, password: e.target.value })}
                  className={inputClass(fieldErrors.password)}
                  placeholder="At least 8 characters"
                />
              }
            />

            <Field
              label="Email (optional)"
              error={fieldErrors.email}
              input={
                <input
                  type="email"
                  value={form.email}
                  autoComplete="off"
                  onChange={(e) => setForm({ ...form, email: e.target.value })}
                  className={inputClass(fieldErrors.email)}
                  placeholder="j.rivera@example.com"
                />
              }
            />

            <div>
              <span className="mb-2 block text-xs font-semibold tracking-wide text-slate-400">
                Roles
              </span>
              <div className="space-y-2">
                {ALL_ROLES.map((role) => (
                  <label
                    key={role}
                    className="flex cursor-pointer items-start gap-3 rounded-lg border border-slate-800 bg-slate-950/40 px-3 py-2.5 transition-colors hover:border-slate-700"
                  >
                    <input
                      type="checkbox"
                      checked={form.roles.includes(role)}
                      onChange={() => toggleRole(role)}
                      className="mt-0.5 h-4 w-4 shrink-0 accent-blue-600"
                    />
                    <span className="min-w-0">
                      <span className="block text-sm font-medium text-slate-200">{role}</span>
                      <span className="block text-xs text-slate-500">{ROLE_HINTS[role]}</span>
                    </span>
                  </label>
                ))}
              </div>
              {fieldErrors.roles && (
                <p className="mt-1.5 text-xs text-red-400">{fieldErrors.roles}</p>
              )}
            </div>

            {formError && <Alert tone="error" icon={AlertTriangle} message={formError} />}
            {successMessage && <Alert tone="success" icon={CheckCircle2} message={successMessage} />}

            <Button type="submit" variant="primary" isLoading={isSubmitting} className="w-full gap-2">
              <UserPlus className="h-4 w-4" />
              Create user
            </Button>
          </form>
        </Card>

        <Card className="xl:col-span-7">
          <div className="mb-5 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Users className="h-4 w-4 text-blue-400" />
              <h3 className="text-sm font-semibold text-slate-200">
                Existing users{!isLoading && !loadError ? ' (' + users.length + ')' : ''}
              </h3>
            </div>
            <Button
              variant="secondary"
              size="sm"
              onClick={loadUsers}
              isLoading={isLoading}
              title="Refresh user list"
              aria-label="Refresh user list"
            >
              <RefreshCw className="h-4 w-4" />
            </Button>
          </div>

          {isLoading && (
            <div className="space-y-2">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </div>
          )}

          {!isLoading && loadError && <Alert tone="error" icon={AlertTriangle} message={loadError} />}

          {!isLoading && !loadError && users.length === 0 && (
            <EmptyState title="No users yet" description="Create the first account using the form." />
          )}

          {!isLoading && !loadError && users.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-800 text-xs uppercase tracking-wider text-slate-500">
                    <th className="py-2 pr-4 font-semibold">Username</th>
                    <th className="py-2 pr-4 font-semibold">Email</th>
                    <th className="py-2 pr-4 font-semibold">Roles</th>
                    <th className="py-2 font-semibold">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {users.map((user) => (
                    <tr key={user.id} className="border-b border-slate-900 last:border-0">
                      <td className="py-3 pr-4 font-medium text-slate-200">{user.username}</td>
                      <td className="py-3 pr-4 text-slate-400">{user.email || '—'}</td>
                      <td className="py-3 pr-4">
                        <span className="flex flex-wrap gap-1.5">
                          {user.roles.map((role) => (
                            <span
                              key={role}
                              className={
                                'inline-flex rounded-full border px-2 py-0.5 text-[11px] font-semibold ' +
                                (ROLE_TONES[role] || ROLE_TONES[ROLE_VIEWER])
                              }
                            >
                              {role}
                            </span>
                          ))}
                        </span>
                      </td>
                      <td className="py-3">
                        <Badge variant={user.enabled ? 'low' : 'neutral'}>
                          {user.enabled ? 'Enabled' : 'Disabled'}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}

function Field({ label, hint, error, input }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-semibold tracking-wide text-slate-400">{label}</span>
      {input}
      {hint && !error && <span className="mt-1.5 block text-xs text-slate-500">{hint}</span>}
      {error && <span className="mt-1.5 block text-xs text-red-400">{error}</span>}
    </label>
  );
}

function Alert({ tone, icon: Icon, message }) {
  const tones = {
    error: 'border-red-500/30 bg-red-500/10 text-red-300',
    success: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300',
  };
  return (
    <div
      className={'flex items-start gap-2 rounded-lg border px-3 py-2.5 text-xs ' + tones[tone]}
      role="alert"
    >
      <Icon className="mt-0.5 h-4 w-4 shrink-0" />
      <span>{message}</span>
    </div>
  );
}

function inputClass(hasError) {
  return (
    'w-full rounded-lg border bg-slate-950 px-3 py-2 text-sm text-slate-100 placeholder-slate-600 transition-colors focus:outline-none ' +
    (hasError ? 'border-red-500/50 focus:border-red-500' : 'border-slate-800 focus:border-blue-500')
  );
}
