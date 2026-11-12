import type { UserSummary } from '../api';

/** A labeled list of the users the API knows about; nothing here lists them by hand. */
export function UserPicker({ id, users, value, onChange }: {
  id: string;
  users: UserSummary[];
  value: string;
  onChange: (userId: string) => void;
}) {
  return (
    <div className="field">
      <label htmlFor={id}>User</label>
      <select id={id} value={value} onChange={(event) => onChange(event.target.value)}>
        {users.map((user) => (
          <option key={user.userId} value={user.userId}>
            {user.userId} ({user.cardIds.length > 3 ? `${user.cardIds.length} cards` : user.cardIds.join(', ')})
          </option>
        ))}
      </select>
    </div>
  );
}
